import chisel3._
import chisel3.util._
import ZirconConfig.Decode._
import ZirconConfig.Issue._
import ZirconConfig.Stream._
import ZirconConfig.FifoRole._
import ZirconUtil._


// 接口：向Stream Engine读取当前的iterCnt，副作用是：SE内部维护的iterCnt会根据 “当拍离开派发级的有效流计算指令数量” 递增
// 输出：当前派发段 stream fire情况{fire表示与IQ握手成功，下一拍离开派发级} 
// 输入： iter的值
// 把正确的 iter值带着，进入IQ
class SERdIterIO extends Bundle{
    val fireStreamOp = Output(Vec(streamNum,Vec(ndcd,Bool())))
    val iterCnt =       Input(Vec(streamNum,Vec(ndcd,UInt(32.W))))
    val fireComputeWrite = Output(Vec(computeStreamNum, Vec(ndcd, Bool())))
    val computeWriteIterCnt = Input(Vec(computeStreamNum, Vec(ndcd, UInt(32.W))))
    val fireStreamOpPP = Output(Vec(2,Vec(ndcd,Bool())))
    val iterCntPP =       Input(Vec(2,Vec(ndcd,UInt(32.W))))
}


class DispatchIO extends Bundle {
    val cmt = Flipped(new CommitDispatchIO)
    val fte = Flipped(new FrontendDispatchIO)
    val bke = Flipped(new BackendDispatchIO)
    val seRIter = new SERdIterIO
}

class Dispatch extends Module {
    val io     = IO(new DispatchIO)

    val dsp    = Module(new Dispatcher)
    val rboard = Module(new ReadyBoard)

    def genIter( fire: Vec[Bool], iter: Vec[UInt]): Vec[UInt] = {
      val idx = fire.scanLeft(0.U(ndcd.W))(_ + _).dropRight(1)
      VecInit((0 until ndcd).map(i => iter(idx(i))))
    }

    // cycle stat
    val cycleReg = RegInit(0.U(64.W))
    cycleReg     := cycleReg + 1.U

    for (i <- 0 until ndcd) {
        val instBits = io.fte.instPkg(i).bits
        val useBuffer = instBits.sinfo.useBuffer
        val usePPBuffer = instBits.sinfo.usePPBuffer
        val fifoId = instBits.sinfo.fifoId
        val fireStream = instBits.isCalStream && io.cmt.rob.enq(i).fire
        val computeDst = fifoId(2) === ComputeX.U || fifoId(2) === ComputeY.U
        for (b <- 0 until streamNum) {
          val read = useBuffer(0) && fifoId(0) === b.U ||
                     useBuffer(1) && fifoId(1) === b.U
          val normalWrite = useBuffer(2) && !computeDst && fifoId(2) === b.U
          io.seRIter.fireStreamOp(b)(i) := fireStream && (read || normalWrite)
        }
        for (b <- 0 until computeStreamNum) {
          io.seRIter.fireComputeWrite(b)(i) := fireStream && useBuffer(2) && fifoId(2) === (ComputeX + b).U
        }
        for (k <- 0 until 2) {
          io.seRIter.fireStreamOpPP(k)(i) := fireStream && usePPBuffer(k)
        }
    }     
    
    val streamIter = VecInit.tabulate(streamNum) { b =>
      genIter(io.seRIter.fireStreamOp(b), io.seRIter.iterCnt(b))
    }
    val computeWriteIter = VecInit.tabulate(computeStreamNum) { b =>
      genIter(io.seRIter.fireComputeWrite(b), io.seRIter.computeWriteIterCnt(b))
    }
    val seIter = WireInit(VecInit.fill(3)(VecInit.fill(ndcd)(0.U(32.W))))
    for (i <- 0 until ndcd) {
      val fifoId = io.fte.instPkg(i).bits.sinfo.fifoId
      seIter(0)(i) := streamIter(fifoId(0))(i)
      seIter(1)(i) := streamIter(fifoId(1))(i)
    }
    // dst获取需要根据指令类型判断，常规 、Even PP 、OddPP ，对应三组独立的索引更新规则
    val ppEven    = genIter(io.seRIter.fireStreamOpPP(Even), io.seRIter.iterCntPP(Even))
    val ppOdd    = genIter(io.seRIter.fireStreamOpPP(Odd), io.seRIter.iterCntPP(Odd))
    for (i <- 0 until ndcd) {
      val dstId = io.fte.instPkg(i).bits.sinfo.fifoId(2)
      val normal = streamIter(dstId)(i)
      val compute = Mux(dstId === ComputeX.U, computeWriteIter(0)(i), computeWriteIter(1)(i))
      val normalOrCompute = Mux(dstId === ComputeX.U || dstId === ComputeY.U, compute, normal)
      seIter(2)(i) :=
        Mux(io.seRIter.fireStreamOpPP(Even)(i), ppEven(i),
          Mux(io.seRIter.fireStreamOpPP(Odd)(i), ppOdd(i),
            normalOrCompute))
    }


    // ready board
    rboard.io.pinfo   := io.fte.instPkg.map(_.bits.pinfo)
    rboard.io.wakeBus := io.bke.wakeBus
    rboard.io.rplyBus := io.bke.rplyBus
    rboard.io.flush   := io.cmt.flush

    val ftePkg = VecInit.tabulate(ndcd){ i =>  
        val iter = VecInit(seIter.map(_(i)))
        (new BackendPackage)(io.fte.instPkg(i).bits, io.cmt.rob.enqIdx(i), io.cmt.bdb.enqIdx(i), rboard.io.prjInfo(i), rboard.io.prkInfo(i), iter)
    }

    // dispatcher
    dsp.io.ftePkg.zipWithIndex.foreach{ case (d, i) =>
        d.valid := io.fte.instPkg(i).valid && io.cmt.rob.enq(0).ready && io.cmt.bdb.enq(0).ready
        d.bits  := ftePkg(i)
        io.fte.instPkg(i).ready := d.ready && io.cmt.rob.enq(0).ready && io.cmt.bdb.enq(0).ready
    }
    dsp.io.func.zipWithIndex.foreach{ case (func, i) =>
        func := io.fte.instPkg(i).bits.func
    }
    dsp.io.bkePkg <> io.bke.instPkg
    io.cmt.rob.enq.zipWithIndex.foreach{ case (enq, i) =>
        enq.valid := io.fte.instPkg(i).valid && dsp.io.ftePkg(i).ready
        enq.bits  := (new ROBEntry)(io.fte.instPkg(i).bits)
    }
    io.cmt.bdb.enq.zipWithIndex.foreach{ case (enq, i) =>
        enq.valid := io.fte.instPkg(i).valid && dsp.io.ftePkg(i).ready && io.fte.instPkg(i).bits.op(4)
        enq.bits  := (new BDBEntry)(io.fte.instPkg(i).bits)
    }
}

