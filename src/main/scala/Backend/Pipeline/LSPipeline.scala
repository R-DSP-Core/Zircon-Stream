import chisel3._
import chisel3.util._
import ZirconConfig.Decode._
import ZirconConfig.Issue._
import ZirconUtil._
import ZirconConfig.Commit._
import ZirconConfig.TCM._

class LSPipelineROBIO extends PipelineROBIO 

class LSPipelineBDBIO extends PipelineBDBIO

class LSCommitIO extends Bundle {
    val rob   = Vec(2, new LSPipelineROBIO)
    val dc    = new DCommitIO
    val flush = Input(Bool())
}
class LSIQIO extends PipelineIQIO 

class LSWakeupIO extends Bundle {
    val wakeRF  = Output(new WakeupBusPkg)
    val wakeD1  = Output(new WakeupBusPkg)
    val rplyIn  = Input(new ReplayBusPkg)
    val rplyOut = Output(new ReplayBusPkg)
}
class LSForwardIO extends Bundle {
    val instPkgWB = Output(Vec(2, new BackendPackage))
}

class LSMemoryIO extends Bundle {
    val l2 = Flipped(new L2DCacheIO)
}

class LSDBGIO extends PipelineDBGIO{
    val dc = MixedVec(new DCacheReadDBG, new DCacheWriteDBG)
}

class LSSEIO extends Bundle {
    val dc = Flipped(new SEDCIO)
}

class LSPipelineIO extends Bundle {
    val iq  = new LSIQIO
    val rf  = Flipped(new RegfileSingleIO)
    val tcmwr = Flipped(new RegfileWRIO)
    val cmt = new LSCommitIO
    val fwd = new LSForwardIO
    val wk  = new LSWakeupIO
    val mem = new LSMemoryIO
    val dbg = new LSDBGIO
    val dcProfiling = Output(new DCacheProfilingDBG)
    val se  = new LSSEIO
    val tcm = new TbMemIO
}

class LSPipeline extends Module {
    val io = IO(new LSPipelineIO)
    
    val agu = Module(new BLevelPAdder32)
    val dc  = Module(new DCache)

    //TCM
    val dtcm = Module(new DTCMBurst)
    io.tcm <> dtcm.io.mem

    io.dcProfiling := dc.io.profiling
    // cycle stat
    val cycleReg = RegInit(0.U(64.W))
    cycleReg     := cycleReg + 1.U

    /* Issue Stage */
    val instPkgIs = WireDefault(io.iq.instPkg.bits)
    io.iq.instPkg.ready := !(dc.io.pp.miss || dc.io.pp.sbFull || dtcm.io.pp.sbFull)  //&& !io.se.dc.rreq
    
    def segFlush(instPkg: BackendPackage): Bool = {
        io.cmt.flush || io.wk.rplyIn.replay && (instPkg.prjLpv | instPkg.prkLpv).orR
    }
    instPkgIs.prjLpv := io.iq.instPkg.bits.prjLpv << 1
    instPkgIs.prkLpv := io.iq.instPkg.bits.prkLpv << 1
    instPkgIs.cycles.readOp := cycleReg
    
    /* Regfile Stage */
    val instPkgRF = WireDefault(ShiftRegister(
        Mux(segFlush(instPkgIs), 0.U.asTypeOf(new BackendPackage), instPkgIs), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        !(dc.io.pp.miss || dc.io.pp.sbFull || dtcm.io.pp.sbFull) || io.cmt.flush
        //((!io.se.dc.rreq && !(dc.io.pp.miss || dc.io.pp.sbFull))) || io.cmt.flush
    ))
    // regfile read
    io.rf.rd.prj   := instPkgRF.prj
    io.rf.rd.prk   := instPkgRF.prk
    // agu
    agu.io.src1    := io.rf.rd.prjData
    agu.io.src2    := instPkgRF.imm
    agu.io.cin     := 0.U
    instPkgRF.src1 := agu.io.res
    instPkgRF.src2 := io.rf.rd.prkData
    // wakeup
    io.wk.wakeRF   := (new WakeupBusPkg)(instPkgRF, io.wk.rplyIn, 1)

    //memory Select : DCache or DTCM
    val selTcm = instPkgRF.src1(31,20) === tcmsAddrTag.asUInt

    dc.io.pp.rreq     := (instPkgRF.op(5) && !selTcm) || io.se.dc.rreq
    dc.io.pp.wdata    := instPkgRF.src2

    // SE优先 
    // dc.io.pp.mtype    := Mux(io.se.dc.rreq, io.se.dc.mtype, instPkgRF.op(2, 0))
    // dc.io.pp.isLatest := Mux(io.se.dc.rreq, io.se.dc.isLatest, instPkgRF.isLatest)
    // dc.io.pp.wreq     := Mux(io.se.dc.rreq, false.B, instPkgRF.op(6))
    // dc.io.pp.vaddr    := Mux(io.se.dc.rreq, io.se.dc.vaddr, instPkgRF.src1)


    // instPkgRF 优先
    dc.io.pp.mtype := Mux(instPkgRF.op(5)||instPkgRF.op(6), instPkgRF.op(2, 0), io.se.dc.mtype)
    dc.io.pp.isLatest := Mux(instPkgRF.op(5)||instPkgRF.op(6), instPkgRF.isLatest, io.se.dc.isLatest)
    dc.io.pp.wreq := instPkgRF.op(6) && !selTcm
    dc.io.pp.vaddr := Mux(instPkgRF.op(5)||instPkgRF.op(6), instPkgRF.src1, io.se.dc.vaddr)


    io.se.dc.rdata := dc.io.pp.rdata
    io.se.dc.miss  := dc.io.pp.miss
    io.se.dc.rrsp  := dc.io.pp.rrsp
    io.se.dc.sbFull := dc.io.pp.sbFull
    io.se.dc.lsuRfValid := instPkgRF.valid

    instPkgRF.cycles.exe := cycleReg  // for profiling

    // DTCM: only when memSel === 1
    dtcm.io.pp.rreq  := instPkgRF.op(5) && selTcm
    dtcm.io.pp.wreq  := instPkgRF.op(6) && selTcm
    dtcm.io.pp.vaddr := instPkgRF.src1
    dtcm.io.pp.wdata := instPkgRF.src2
    dtcm.io.pp.mtype := instPkgRF.op(2,0)

    val commitSelQ = Module(new Queue(Bool(), 8))
    commitSelQ.io.enq.valid := instPkgRF.op(6) && (!selTcm || (selTcm && instPkgRF.src1(16)))
    commitSelQ.io.enq.bits := selTcm

    /* DCache Stage 1 */
    val instPkgD1 = WireDefault(ShiftRegister(
        Mux(io.cmt.flush, 0.U.asTypeOf(new BackendPackage), instPkgRF), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        !(dc.io.pp.miss || dc.io.pp.sbFull) || io.cmt.flush
        //((!io.se.dc.rreq && !(dc.io.pp.miss || dc.io.pp.sbFull))) || io.cmt.flush
    ))
    io.wk.wakeD1 := (new WakeupBusPkg)(instPkgD1, io.wk.rplyIn, 2)

    commitSelQ.io.deq.ready := io.cmt.dc.stCmt
    val commitSel_d = commitSelQ.io.deq.bits

    // dcache
    dc.io.cmt.stCmt     := !commitSel_d && io.cmt.dc.stCmt
    dc.io.cmt.flush     := io.cmt.dc.flush
    dc.io.mmu.paddr     := Mux(io.se.dc.rreqD1, io.se.dc.paddrD1, instPkgD1.src1)
    // TODO: add mmu
    dc.io.mmu.uncache   := Mux(io.se.dc.rreqD1, 0.U, instPkgD1.src1(31, 28) === 0xa.U)
    dc.io.mmu.exception := 0.U(8.W)
    dc.io.l2            <> io.mem.l2
    instPkgD1.cycles.exe1 := cycleReg  // for profiling

    // dtcm cmt
    dtcm.io.cmt.stCmt     := commitSel_d && io.cmt.dc.stCmt
    dtcm.io.cmt.flush     := io.cmt.dc.flush

    /* DCache Stage 2 */
    val instPkgD2 = WireDefault(ShiftRegister(
        Mux(segFlush(instPkgD1), 0.U.asTypeOf(new BackendPackage), instPkgD1), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        !(dc.io.pp.miss || dc.io.pp.sbFull) || io.cmt.flush
    ))

    instPkgD2.nxtCmtEn := !instPkgD2.op(6)
    instPkgD2.cycles.wb := cycleReg
    // replay
    io.wk.rplyOut.prd    := instPkgD2.prd
    io.wk.rplyOut.replay := (dc.io.pp.miss || dc.io.pp.loadReplay || dc.io.pp.sbFull) && instPkgD2.valid

    /* Write Back Stage */
    val instPkgWB = WireDefault(ShiftRegister(
        Mux(io.cmt.flush || dc.io.pp.miss || dc.io.pp.sbFull, 0.U.asTypeOf(new BackendPackage), instPkgD2), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        true.B
    ))
    instPkgWB.rfWdata  := dc.io.pp.rdata
    instPkgWB.cycles.wbROB := cycleReg

    /* TCM Write Back Stage */
    val instPkgTDWB = WireDefault(ShiftRegister(
        Mux(io.cmt.flush || !selTcm, 
            0.U.asTypeOf(new BackendPackage), 
            instPkgRF), 
        1, 
        0.U.asTypeOf(new BackendPackage), 
        true.B
    ))
    instPkgTDWB.nxtCmtEn := !instPkgTDWB.op(6)
    instPkgTDWB.rfWdata  := dtcm.io.pp.rdata

    // rob
    io.cmt.rob(0).widx.offset := UIntToOH(instPkgWB.robIdx.offset)
    io.cmt.rob(0).widx.qidx   := UIntToOH(instPkgWB.robIdx.qidx)
    io.cmt.rob(0).widx.high   := DontCare
    io.cmt.rob(0).wen         := instPkgWB.valid
    io.cmt.rob(0).wdata       := (new ROBEntry)(instPkgWB) 
    io.cmt.rob(1).widx.offset := UIntToOH(instPkgTDWB.robIdx.offset)
    io.cmt.rob(1).widx.qidx   := UIntToOH(instPkgTDWB.robIdx.qidx)
    io.cmt.rob(1).widx.high   := DontCare
    io.cmt.rob(1).wen         := instPkgTDWB.valid
    io.cmt.rob(1).wdata       := (new ROBEntry)(instPkgTDWB) 
    // regfile
    io.rf.wr.prd       := instPkgWB.prd
    io.rf.wr.prdVld    := instPkgWB.rdVld
    io.rf.wr.prdData   := instPkgWB.rfWdata
    io.tcmwr.prd       := instPkgTDWB.prd
    io.tcmwr.prdVld    := instPkgTDWB.rdVld
    io.tcmwr.prdData   := instPkgTDWB.rfWdata
    // forward
    io.fwd.instPkgWB(0)   := instPkgWB
    io.fwd.instPkgWB(1)   := instPkgTDWB
    // debug
    io.dbg.dc          := dc.io.dbg
}
