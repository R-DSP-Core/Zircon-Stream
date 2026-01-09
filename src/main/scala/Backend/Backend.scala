import chisel3._
import chisel3.util._
import ZirconConfig.Issue._
import ZirconConfig.Decode._
import ZirconConfig.Commit._

// class BackendFrontendIO extends Bundle {
//     val rf = new RegfilePredictIO
// }

class BackendDispatchIO extends Bundle {
    val instPkg = Vec(niq, Vec(ndcd, Flipped(Decoupled(new BackendPackage))))
    val wakeBus = Output(Vec(nis, new WakeupBusPkg))
    val rplyBus = Output(new ReplayBusPkg)
}

class BackendROBIO extends Bundle {
    val ridx  = Output(Vec(arithNissue, new ClusterEntry(nrobQ, ndcd)))
    val rdata = Input(Vec(arithNissue, new ROBEntry))
    val widx  = Output(Vec(nisplus, new ClusterEntry(nrobQ, ndcd)))
    val wen   = Output(Vec(nisplus, Bool()))
    val wdata = Output(Vec(nisplus, new ROBEntry))
}
class BackendBDBIO extends Bundle {
    val ridx  = Output(Vec(arithNissue, new ClusterEntry(nbdbQ, ndcd)))
    val rdata = Input(Vec(arithNissue, new BDBEntry))
    val widx  = Output(Vec(arithNissue, new ClusterEntry(nbdbQ, ndcd)))
    val wen   = Output(Vec(arithNissue, Bool()))
    val wdata = Output(Vec(arithNissue, new BDBEntry))
}
class BackendCommitIO extends Bundle {
    val rob = new BackendROBIO
    val bdb = new BackendBDBIO
    val sb    = new DCommitIO
    val flush = Input(Vec(nis, Bool()))
}

class BackendMemoryIO extends Bundle {
    val l2 = Flipped(new L2DCacheIO)
    val stream = new SEMemIO
    val sel2 = new SEL2IO
}
class BackendDBGIO extends Bundle {
    val rf   = new RegfileDBGIO
    val arIQ = new IssueQueueDBGIO
    val mdIQ = new IssueQueueDBGIO
    val lsIQ = new IssueQueueDBGIO
    val lsPP = new LSDBGIO
    val mdPP = new MulDivDBGIO
}

class BackendIO extends Bundle {
    // val fte = new BackendFrontendIO
    val dsp = new BackendDispatchIO
    val cmt = new BackendCommitIO
    val mem = new BackendMemoryIO
    val seRIter = Flipped(new SERdIterIO)
    val dbg = new BackendDBGIO
    val dcProfiling = Output(new DCacheProfilingDBG)
    val tcm = new TbMemIO
}

class Backend extends Module {
    val io   = IO(new BackendIO)
    
    val arIQ = Module(new IssueQueue(ndcd, arithNissue, arithNiq, false))
    val mdIQ = Module(new IssueQueue(ndcd, muldivNissue, muldivNiq, false))
    val lsIQ = Module(new IssueQueue(ndcd, lsuNissue, lsuNiq, true))

    val rf   = Module(new Regfile)

    val fwd  = Module(new Forward)

    val arPP = VecInit.fill(3)(Module(new ArithPipeline).io)
    val mdPP = Module(new MulDivPipeline)
    val lsPP = Module(new LSPipeline)
    io.dcProfiling := lsPP.io.dcProfiling
    val wakeBus = Wire(Vec(niq, Vec(nis, new WakeupBusPkg)))
    val rplyBus = Wire(new ReplayBusPkg)

    /* pipeline 0-2: arith and branch */
    // issue queue
    arIQ.io.enq.zip(io.dsp.instPkg(0)).foreach{case (enq, inst) => enq <> inst}
    arIQ.io.deq.zip(arPP).foreach{case (deq, pp) => deq <> pp.iq.instPkg}
    arIQ.io.wakeBus := wakeBus(0)
    arIQ.io.rplyBus := rplyBus
    arIQ.io.flush   := io.cmt.flush(0)

    // pipeline
    arPP.zipWithIndex.foreach{case(a, i) => 
        a.rf                <> rf.io.original(i)
        a.wk.rplyIn         := rplyBus
        fwd.io.instPkgWB(i) := a.fwd.instPkgWB
        fwd.io.instPkgEX(i) := a.fwd.instPkgEX
        a.fwd.src1Fwd       <> fwd.io.src1Fwd(i)
        a.fwd.src2Fwd       <> fwd.io.src2Fwd(i)
        a.cmt.flush         := io.cmt.flush(i)
        io.cmt.rob.ridx(i)  := a.cmt.rob.ridx
        a.cmt.rob.rdata     := io.cmt.rob.rdata(i)
        io.cmt.bdb.ridx(i)  := a.cmt.bdb.ridx
        a.cmt.bdb.rdata     := io.cmt.bdb.rdata(i)
        io.cmt.rob.widx(i)  := a.cmt.rob.widx
        io.cmt.rob.wen(i)   := a.cmt.rob.wen
        io.cmt.rob.wdata(i) := a.cmt.rob.wdata
        io.cmt.bdb.widx(i)  := a.cmt.bdb.widx
        io.cmt.bdb.wen(i)   := a.cmt.bdb.wen
        io.cmt.bdb.wdata(i) := a.cmt.bdb.wdata
    }
    wakeBus(0) := VecInit(
        arPP(0).wk.wakeIssue,
        arPP(1).wk.wakeIssue,
        arPP(2).wk.wakeIssue,
        mdPP.io.wk.wakeEX3,
        lsPP.io.wk.wakeRF
    )
    /* pipeline 3: muldiv */
    // issue queue
    mdIQ.io.enq.zip(io.dsp.instPkg(1)).foreach{case (enq, inst) => enq <> inst}
    mdIQ.io.deq.foreach{case deq => deq <> mdPP.io.iq.instPkg}
    mdIQ.io.wakeBus := wakeBus(1)
    mdIQ.io.rplyBus := rplyBus
    mdIQ.io.flush   := io.cmt.flush(3)

    // pipeline
    mdPP.io.rf          <> rf.io.original(3)
    mdPP.io.wk.rplyIn   := rplyBus
    fwd.io.instPkgWB(3) := mdPP.io.fwd.instPkgWB
    fwd.io.instPkgEX(3) := mdPP.io.fwd.instPkgEX
    fwd.io.src1Fwd(3)   <> mdPP.io.fwd.src1Fwd
    fwd.io.src2Fwd(3)   <> mdPP.io.fwd.src2Fwd
    mdPP.io.cmt.flush   := io.cmt.flush(3)
    io.cmt.rob.widx(3)  := mdPP.io.cmt.rob.widx
    io.cmt.rob.wen(3)   := mdPP.io.cmt.rob.wen
    io.cmt.rob.wdata(3) := mdPP.io.cmt.rob.wdata

    wakeBus(1) := VecInit(
        arPP(0).wk.wakeRF,
        arPP(1).wk.wakeRF,
        arPP(2).wk.wakeRF,
        mdPP.io.wk.wakeEX3,
        lsPP.io.wk.wakeRF
    )

    /* pipeline 4: lsu */
    lsIQ.io.enq.zip(io.dsp.instPkg(2)).foreach{case (enq, inst) => enq <> inst}
    lsIQ.io.deq.foreach{case deq => deq <> lsPP.io.iq.instPkg}
    lsIQ.io.wakeBus := wakeBus(2)
    lsIQ.io.rplyBus := rplyBus
    lsIQ.io.flush   := io.cmt.flush(4)

    // pipeline
    lsPP.io.rf          <> rf.io.original(4)
    lsPP.io.tcmwr       <> rf.io.tcmwr
    io.tcm              <> lsPP.io.tcm
    lsPP.io.wk.rplyIn   := rplyBus
    fwd.io.instPkgWB(4) := lsPP.io.fwd.instPkgWB(0)
    fwd.io.instPkgWB(5) := lsPP.io.fwd.instPkgWB(1)
    lsPP.io.cmt.flush   := io.cmt.flush(4)
    lsPP.io.cmt.dc      := io.cmt.sb
    io.cmt.rob.widx(4)  := lsPP.io.cmt.rob(0).widx
    io.cmt.rob.wen(4)   := lsPP.io.cmt.rob(0).wen
    io.cmt.rob.wdata(4) := lsPP.io.cmt.rob(0).wdata
    io.cmt.rob.widx(5)  := lsPP.io.cmt.rob(1).widx
    io.cmt.rob.wen(5)   := lsPP.io.cmt.rob(1).wen
    io.cmt.rob.wdata(5) := lsPP.io.cmt.rob(1).wdata

    wakeBus(2) := VecInit(
        arPP(0).wk.wakeRF,
        arPP(1).wk.wakeRF,
        arPP(2).wk.wakeRF,
        mdPP.io.wk.wakeEX3,
        lsPP.io.wk.wakeD1
    )
    rplyBus := lsPP.io.wk.rplyOut
    io.mem.l2 <> lsPP.io.mem.l2
    io.dsp.wakeBus := wakeBus(0)
    io.dsp.rplyBus := rplyBus

    // io.fte.rf   <> rf.predictIO

    io.dbg.rf   := rf.dbg
    io.dbg.arIQ := arIQ.io.dbg
    io.dbg.mdIQ := mdIQ.io.dbg
    io.dbg.lsIQ := lsIQ.io.dbg
    io.dbg.mdPP := mdPP.io.dbg
    io.dbg.lsPP := lsPP.io.dbg

    //stream engine
    val stream = Module(new StreamEngine)
    io.seRIter <> stream.io.rdIter //dispatch
    for (i <- 0 until arithNiq){
        arIQ.io.se(i)  <> stream.io.iss(i)
    }
    for (j <- 0 until muldivNiq){
        mdIQ.io.se(j) <> stream.io.iss(j+arithNiq)
    }
    arPP(0).serf <> stream.io.rf(0)
    arPP(1).serf <> stream.io.rf(1)
    arPP(2).serf <> stream.io.rf(2)
    arPP(0).sewb <> stream.io.wb(0)
    arPP(1).sewb <> stream.io.wb(1)
    arPP(2).sewb <> stream.io.wb(2)
    mdPP.io.serf <> stream.io.rf(3)
    mdPP.io.sewb <> stream.io.wb(3)
    io.mem.stream <> stream.io.mem
    mdPP.io.streamPP <> stream.io.pp //cfgstream
    lsPP.io.se.dc   <> stream.io.dc
    io.mem.sel2  <> stream.io.l2
    
    //Dontcare
    for (k <- 0 until lsuNiq){
        lsIQ.io.se(k).ready := true.B
    }
}   