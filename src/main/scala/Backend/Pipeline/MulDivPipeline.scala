import chisel3._
import chisel3.util._
import ZirconConfig.Issue._
import ZirconConfig.Commit._
import ZirconConfig.Stream.DONECFG
import ZirconConfig.EXEOp._

class MulDivROBIO extends PipelineROBIO

class MulDivBDBIO extends PipelineBDBIO

class MulDivCommitIO extends Bundle {
    val rob   = new MulDivROBIO
    val flush = Input(Bool())
}

class MulDivIQIO extends PipelineIQIO

class MulDivDBGIO extends PipelineDBGIO {
    val srt2 = new SRT2DBG
}

class MulDivForwardIO extends Bundle {
    val instPkgWB = Output(new BackendPackage)
    val instPkgEX = Output(new BackendPackage)
    val src1Fwd   = Flipped(Decoupled(UInt(32.W)))
    val src2Fwd   = Flipped(Decoupled(UInt(32.W)))
}
class MulDivWakeupIO extends Bundle {
    val wakeEX3 = Output(new WakeupBusPkg)
    val rplyIn  = Input(new ReplayBusPkg)
}

class MulDivPipelineIO extends Bundle {
    val iq  = new MulDivIQIO
    val rf  = Flipped(new RegfileSingleIO)
    val cmt = new MulDivCommitIO
    val fwd = new MulDivForwardIO
    val wk  = new MulDivWakeupIO
    val dbg = new MulDivDBGIO
    val streamPP  = Flipped(new SEPipelineIO)
    val serf = Flipped(new SERFIO)
    val sewb = Flipped(new SEWBIO)
    val outerRF = Vec(2, Flipped(new SEOuterRFIO))
    val outerReserve = Output(new StreamOuterEvent)
    val outerCommit = Input(Vec(ncommit, new StreamOuterEvent))
}

class MulDivPipeline extends Module {
    val io = IO(new MulDivPipelineIO)

    val mul = Module(new MulBooth2Wallce)
    val div = Module(new SRT2)
    // Lane zero shares the existing scalar multiplier; the MDU issues one
    // scalar or vector operation per cycle, never both.
    val outerMul = Seq(mul) ++ Seq.fill(7)(Module(new MulBooth2Wallce))
    val outerRegs = Module(new StreamOuterRegisters)
    outerRegs.io.commit := io.outerCommit
    outerRegs.io.flush := io.cmt.flush
    val streamBusy = io.streamPP.busy

    // cycle stat
    val cycleReg = RegInit(0.U(64.W))
    cycleReg     := cycleReg + 1.U

    /* Issue Stage */
    val instPkgIs = WireDefault(io.iq.instPkg.bits)
    io.iq.instPkg.ready := !(div.io.busy || streamBusy)
    io.outerReserve := 0.U.asTypeOf(new StreamOuterEvent)
    io.outerReserve.valid := io.iq.instPkg.fire && instPkgIs.isCalStream && StreamOuter.isVector(instPkgIs.sinfo.op) && !io.cmt.flush
    io.outerReserve.info := instPkgIs.sinfo
    io.outerReserve.index := instPkgIs.iterCnt

    def segFlush(instPkg: BackendPackage): Bool = {
        io.cmt.flush || io.wk.rplyIn.replay && (instPkg.prjLpv | instPkg.prkLpv).orR
    }
    instPkgIs.prjLpv := io.iq.instPkg.bits.prjLpv << 1
    instPkgIs.prkLpv := io.iq.instPkg.bits.prkLpv << 1
    instPkgIs.cycles.readOp := cycleReg
    
    /* Regfile Stage */
    val instPkgRFReplay = WireDefault(false.B)
    val instPkgRF = WireDefault(ShiftRegister(
        Mux(segFlush(io.iq.instPkg.bits) || instPkgRFReplay && (div.io.busy || streamBusy), 0.U.asTypeOf(new BackendPackage), instPkgIs), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        io.cmt.flush || !(div.io.busy || streamBusy) || instPkgRFReplay 
    ))
    instPkgRFReplay := segFlush(instPkgRF)
    io.rf.rd.prj    := instPkgRF.prj
    io.rf.rd.prk    := instPkgRF.prk
    io.serf.iterCnt  := instPkgRF.iterCnt
    io.serf.fifoId   := instPkgRF.sinfo.fifoId
    for (operand <- 0 until 2) {
        io.outerRF(operand).fifo := instPkgRF.sinfo.fifoId(operand)
        io.outerRF(operand).index := instPkgRF.iterCnt(operand)
    }
    val vectorEX1 = RegEnable(VecInit(io.outerRF.map(_.data)), io.cmt.flush || !(div.io.busy || streamBusy))
    val vectorEX3 = ShiftRegister(vectorEX1, 2, VecInit.fill(2)(VecInit.fill(8)(0.U(32.W))),
        io.cmt.flush || !(div.io.busy || streamBusy))
    instPkgRF.src1  := Mux(instPkgRF.needStreamSrc, io.serf.rdata1 ,io.rf.rd.prjData)
    instPkgRF.src2  := Mux(instPkgRF.needStreamSrc, io.serf.rdata2 ,io.rf.rd.prkData)
    instPkgRF.cycles.exe := cycleReg  // for profiling
    /* Execute Stage 1 */
    val instPkgEX1 = WireDefault(ShiftRegister(
        Mux(segFlush(instPkgRF), 0.U.asTypeOf(new BackendPackage), instPkgRF), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        io.cmt.flush || !(div.io.busy || streamBusy)
    ))

    // multiply
    mul.io.src1    := Mux(io.fwd.src1Fwd.valid, io.fwd.src1Fwd.bits, instPkgEX1.src1)
    mul.io.src2    := Mux(io.fwd.src2Fwd.valid, io.fwd.src2Fwd.bits, instPkgEX1.src2)
    mul.io.op      := instPkgEX1.op(3, 0)
    mul.io.divBusy := div.io.busy || streamBusy
    for (lane <- 0 until 8) {
        val vectorMultiply = instPkgEX1.isCalStream && (instPkgEX1.sinfo.op === VOMAC8 ||
            instPkgEX1.sinfo.op === VMUL8 || instPkgEX1.sinfo.op === VDOT8)
        val vectorA = Mux(instPkgEX1.sinfo.op === VOMAC8, vectorEX1(0)(0), vectorEX1(0)(lane))
        if (lane == 0) {
            when(vectorMultiply) {
                mul.io.src1 := vectorA
                mul.io.src2 := vectorEX1(1)(0)
                mul.io.op := 0.U
            }
        } else {
            outerMul(lane).io.src1 := vectorA
            outerMul(lane).io.src2 := vectorEX1(1)(lane)
            outerMul(lane).io.op := 0.U // low 32 bits: signed/unsigned products agree
            outerMul(lane).io.divBusy := div.io.busy || streamBusy
        }
    }

    // divide
    div.io.src1 := Mux(io.fwd.src1Fwd.valid, io.fwd.src1Fwd.bits, instPkgEX1.src1)
    div.io.src2 := Mux(io.fwd.src2Fwd.valid, io.fwd.src2Fwd.bits, instPkgEX1.src2)
    div.io.op   := instPkgEX1.op(3, 0)

    // stream 
    io.streamPP.valid := instPkgEX1.sinfo.state(DONECFG)
    val stBits = io.streamPP
    val sInfo  = instPkgEX1.sinfo
    stBits.cfgState := sInfo.state
    stBits.op := sInfo.op
    stBits.src1 := Mux(io.fwd.src1Fwd.valid, io.fwd.src1Fwd.bits, instPkgEX1.src1)
    stBits.src2 := Mux(io.fwd.src2Fwd.valid, io.fwd.src2Fwd.bits, instPkgEX1.src2)


    // forward
    io.fwd.instPkgEX     := instPkgEX1
    io.fwd.src1Fwd.ready := DontCare
    io.fwd.src2Fwd.ready := DontCare

    instPkgEX1.cycles.exe1 := cycleReg
    /* Execute Stage 2 */
    val instPkgEX2 = WireDefault(ShiftRegister(
        Mux(io.cmt.flush, 0.U.asTypeOf(new BackendPackage), instPkgEX1), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        io.cmt.flush || !(div.io.busy || streamBusy)
    ))
    instPkgEX2.cycles.exe2 := cycleReg
    /* Execute Stage 3 */
    val instPkgEX3 = WireDefault(ShiftRegister(
        Mux(io.cmt.flush, 0.U.asTypeOf(new BackendPackage), instPkgEX2), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        io.cmt.flush || !(div.io.busy || streamBusy)
    ))
    val instPkgEX3ForWakeup = WireDefault(instPkgEX3)
    instPkgEX3ForWakeup.prd := Mux(div.io.busy || streamBusy, 0.U, instPkgEX3.prd)
    io.wk.wakeEX3 := (new WakeupBusPkg)(instPkgEX3ForWakeup, 0.U.asTypeOf(new ReplayBusPkg))
    instPkgEX3.rfWdata := Mux(instPkgEX3.isCalStream && instPkgEX3.sinfo.op === VDOT8,
        outerRegs.io.dot, Mux(instPkgEX3.op(2), div.io.res, mul.io.res))
    instPkgEX3.cycles.wb := cycleReg
    outerRegs.io.execute := 0.U.asTypeOf(new StreamOuterEvent)
    outerRegs.io.execute.valid := instPkgEX3.valid && instPkgEX3.isCalStream && StreamOuter.isVector(instPkgEX3.sinfo.op) &&
        !(io.cmt.flush || div.io.busy || streamBusy)
    outerRegs.io.execute.info := instPkgEX3.sinfo
    outerRegs.io.execute.data := VecInit(outerMul.map(_.io.res))
    outerRegs.io.operands := vectorEX3
    val outerWB = RegNext(outerRegs.io.result, VecInit.fill(8)(0.U(32.W)))
    /* Write Back Stage */
    val instPkgWB = WireDefault(ShiftRegister(
        Mux(io.cmt.flush || div.io.busy || streamBusy , 0.U.asTypeOf(new BackendPackage), instPkgEX3), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        true.B
    ))
    instPkgWB.cycles.wbROB := cycleReg
    // rob
    io.cmt.rob.widx.offset := UIntToOH(instPkgWB.robIdx.offset)
    io.cmt.rob.widx.qidx   := UIntToOH(instPkgWB.robIdx.qidx)
    io.cmt.rob.widx.high   := DontCare
    io.cmt.rob.wen         := instPkgWB.valid
    io.cmt.rob.wdata       := (new ROBEntry)(instPkgWB)
    io.cmt.rob.wdata.outerData := outerWB
    // regfile
    io.rf.wr.prd       := instPkgWB.prd
    io.rf.wr.prdVld    := instPkgWB.rdVld
    io.rf.wr.prdData   := instPkgWB.rfWdata
    // forward
    io.fwd.instPkgWB   := instPkgWB

    /* Debug */
    io.dbg.srt2 := div.io.dbg 

    //不写buffer也要valid，为了change flag
    io.sewb.wvalid  := instPkgWB.isCalStream && !StreamOuter.isVector(instPkgWB.sinfo.op)
    io.sewb.useBuffer := instPkgWB.sinfo.useBuffer
    io.sewb.iterCnt := instPkgWB.iterCnt
    io.sewb.fifoId := instPkgWB.sinfo.fifoId
    io.sewb.wdata   :=  instPkgWB.rfWdata
    io.sewb.usePPBuffer := instPkgWB.sinfo.usePPBuffer.asUInt.orR
}

