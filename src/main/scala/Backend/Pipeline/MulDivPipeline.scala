import chisel3._
import chisel3.util._
import ZirconConfig.Issue._
import ZirconConfig.Commit._
import ZirconConfig.Stream.DONECFG

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
}

class MulDivPipeline extends Module {
    val io = IO(new MulDivPipelineIO)

    val mul = Module(new MulBooth2Wallce)
    val div = Module(new SRT2)
    val streamBusy = io.streamPP.busy

    // cycle stat
    val cycleReg = RegInit(0.U(64.W))
    cycleReg     := cycleReg + 1.U

    /* Issue Stage */
    val instPkgIs = WireDefault(io.iq.instPkg.bits)
    io.iq.instPkg.ready := !(div.io.busy || streamBusy)

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
    instPkgRF.src1  := io.rf.rd.prjData
    instPkgRF.src2  := io.rf.rd.prkData
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
    instPkgEX3.rfWdata := Mux(instPkgEX3.op(2), div.io.res, mul.io.res)
    instPkgEX3.cycles.wb := cycleReg  
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
    // regfile
    io.rf.wr.prd       := instPkgWB.prd
    io.rf.wr.prdVld    := instPkgWB.rdVld
    io.rf.wr.prdData   := instPkgWB.rfWdata
    // forward
    io.fwd.instPkgWB   := instPkgWB

    /* Debug */
    io.dbg.srt2 := div.io.dbg 
}

