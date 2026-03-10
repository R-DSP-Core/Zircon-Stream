import chisel3._
import chisel3.util._
import ZirconConfig.TCM._
import ZirconConfig.StoreBuffer._
import ZirconUtil._

class TbWriteBackSignal extends Bundle {
    val rreq     = Bool()
    val wreq     = Bool()
    val vaddr    = UInt(32.W)
    val mtype    = UInt(3.W)
    val wdata    = UInt(32.W)
    val sbHitData = UInt(32.W)
    val memData   = UInt(32.W)
    val sbHit     = UInt(4.W)

    def apply(pp: TbPipelineIO, sbHitData: UInt, memData: UInt, sbHit: UInt): TbWriteBackSignal = {
        val c = Wire(new TbWriteBackSignal)
        InheritFields(c, pp)
        c.sbHitData := sbHitData
        c.memData   := memData  >> (c.vaddr(1,0) << 3)
        c.sbHit     := sbHit
        c
    }
}

class TbWriteSignal extends Bundle {
    val wreq    = Bool()
    val wdata   = UInt(32.W)
    val vaddr   = UInt(32.W)
    val mtype   = UInt(3.W)
    val uncache = Bool()
    val sbIdx   = UInt(wsb.W)

    def apply(SBEntry: SBEntry, sbValid: Bool, sbIdx: UInt): TbWriteSignal = {
        val c = Wire(new TbWriteSignal)
        c.wreq    := sbValid
        c.wdata   := SBEntry.wdata >> (SBEntry.paddr(1, 0) << 3)
        c.vaddr   := SBEntry.paddr
        c.mtype   := MTypeEncode(SBEntry.wstrb >> SBEntry.paddr(1, 0))
        c.uncache := SBEntry.uncache
        c.sbIdx   := sbIdx
        c
    }
}

class TbPipelineIO extends Bundle {
    val rreq       = Input(Bool())
    val wreq       = Input(Bool())
    val vaddr      = Input(UInt(32.W))
    val mtype      = Input(UInt(3.W))
    val wdata      = Input(UInt(32.W))

    val rdata      = Output(UInt(32.W))    
    val sbFull     = Output(Bool())

}

class TbCommitIO extends Bundle {
    val stCmt      = Input(Bool())
    val flush      = Input(Bool())
}

class TcmBurstIO extends Bundle {
    val pp  = new TbPipelineIO
    val cmt = new TbCommitIO
    val mem = new TbMemIO
}

class DTCMBurst extends Module {
    val io = IO(new TcmBurstIO)

    //SRAM
    val mem = Module(new DTCMArbiter(tcmLine, 8, tcmIndexNum)).io
    io.mem <> mem.mem

    //Utils
    def index(addr: UInt)    = addr(tcmIndex+tcmByte-1, tcmByte)
    def byte(addr: UInt)     = addr(tcmByte-1, 0)

    val sb      = Module(new StoreBuffer)

    val rWreq  = WireDefault(false.B)
    val sbFull  = !sb.io.enq.ready && rWreq

    //read
    rWreq := io.pp.wreq

    mem.addra := index(io.pp.vaddr)
    mem.ena   := io.pp.rreq && !io.pp.vaddr(16)
    mem.dina  := io.pp.wdata
    mem.enctrlw   := io.pp.wreq && io.pp.vaddr(16)
    mem.enctrlr   := io.pp.rreq && io.pp.vaddr(16)
    
    val memData = WireDefault(0.U(32.W))
    memData := mem.douta

    val wbIn    = (new TbWriteBackSignal)(io.pp, sb.io.ldHitData, memData, sb.io.ldSBHit)
    val wb      = ShiftRegister(Mux(sbFull || io.cmt.flush, 0.U.asTypeOf(new TbWriteBackSignal), wbIn), 
                                1, 
                                0.U.asTypeOf(new TbWriteBackSignal), 
                                true.B)
    val rdata   = VecInit.tabulate(4)(i => 
        Mux(wb.sbHit(i), 
        wb.sbHitData(i*8+7, i*8), 
        wb.memData(i*8+7, i*8))
        ).asUInt
    io.pp.rdata := MuxLookup(wb.mtype(1, 0), 0.U(32.W))(Seq(
        0.U(2.W) -> Fill(24, Mux(wb.mtype(2), 0.U(1.W), rdata(7))) ## rdata(7, 0),
        1.U(2.W) -> Fill(16, Mux(wb.mtype(2), 0.U(1.W), rdata(15))) ## rdata(15, 0),
        2.U(2.W) -> rdata,
    ))

    // store buffer
    val sbEnq = (new SBEntry)(io.pp.vaddr, io.pp.wdata, io.pp.mtype, true.B)
    sb.io.enq.valid := io.pp.wreq && !io.cmt.flush && !io.pp.vaddr(16)
    sb.io.enq.bits  := sbEnq
    sb.io.stCmt     := io.cmt.stCmt
    sb.io.flush     := io.cmt.flush
    sb.io.lock      := false.B
    io.pp.sbFull    := sbFull

    //wirte
    val ws1 = (new TbWriteSignal)(
        sb.io.deq.bits,
        sb.io.deq.valid,
        sb.io.deqIdx
    )
    sb.io.deq.ready := true.B

    val ws2     = ShiftRegister(ws1, 
                                1, 
                                0.U.asTypeOf(new TbWriteSignal), 
                                true.B)


    val finishReg = RegInit(false.B)
    finishReg := ws2.wreq
    sb.io.stFinish  := finishReg

    val wdataShift = ws2.wdata << (byte(ws2.vaddr) << 3)
    val wmask = MTypeDecode(ws2.mtype) << byte(ws2.vaddr)
    
    mem.addrb := index(ws2.vaddr)
    mem.enb   := ws2.wreq
    mem.dinb  := wdataShift(31,0)
    mem.web   := wmask(3,0)

}
