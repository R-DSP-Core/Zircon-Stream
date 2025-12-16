import chisel3._
import chisel3.util._
import ZirconConfig.Decode._
import ZirconConfig.Issue._
import ZirconConfig.Stream._
import ZirconUtil._


// 接口：向Stream Engine读取当前的iterCnt，副作用是：SE内部维护的iterCnt会根据 “当拍离开派发级的有效流计算指令数量” 递增
// 输出：当前派发段 stream fire情况{fire表示与IQ握手成功，下一拍离开派发级} 
// 输入： iter的值
// 把正确的 iter值带着，进入IQ
class SERdIterIO extends Bundle{
    val fireStreamOp = Output(Vec(3,Vec(ndcd,Bool())))
    val iterCnt = Input(Vec(3,UInt(32.W)))
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

    // cycle stat
    val cycleReg = RegInit(0.U(64.W))
    cycleReg     := cycleReg + 1.U

    // TODO
    for (i <- 0 until ndcd) {
        val instBits = io.fte.instPkg(i).bits
        val useBuffer = instBits.sinfo.useBuffer
        val fireStream = instBits.isCalStream && io.cmt.rob.enq(i).fire
        for (b <- 0 until 3) {
          io.seRIter.fireStreamOp(b)(i) := fireStream && useBuffer(b)
        }
    }     
    
    val seIter = WireInit(VecInit.fill(3)(VecInit.fill(ndcd)(0.U(32.W))))
    for (b <- 0 until 3) {
        seIter(b)(0) := io.seRIter.iterCnt(b)
        for (i <- 1 until ndcd) {
            seIter(b)(i) := Mux(io.seRIter.fireStreamOp(b)(i-1), seIter(b)(i-1) + 1.U, seIter(b)(i-1))
        } 
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

