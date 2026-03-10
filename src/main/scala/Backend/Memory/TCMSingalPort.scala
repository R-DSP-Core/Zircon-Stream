import chisel3._
import chisel3.util._
import ZirconConfig.TCM._
import ZirconConfig.StoreBuffer._
import ZirconUtil._

class LspipelineSingal extends Bundle {
    val rreq     = Bool()
    val wreq     = Bool()
    val vaddr    = UInt(32.W)
    val mtype    = UInt(3.W)
    val wdata    = UInt(32.W)

    def apply(pp: TLSUMemIO): LspipelineSingal = {
        val c = Wire(new LspipelineSingal)
        InheritFields(c, pp)
        c
    }
}

class TMemTxnIO extends Bundle {
    val srcId    = Input(UInt(4.W))
    val rreq     = Output(Bool())
    val rrsp     = Input(Bool())
    val rlast    = Input(Bool())
    val raddr    = Output(UInt(32.W))
    val rdata    = Input(UInt(32.W))
    val rlen     = Output(UInt(8.W))
    val rsize    = Output(UInt(2.W))

    val wreq     = Output(Bool())
    val wrsp     = Input(Bool())
    val wlast    = Output(Bool())
    val waddr    = Output(UInt(32.W))
    val wdata    = Output(UInt(32.W))
    val wlen     = Output(UInt(8.W))
    val wsize    = Output(UInt(2.W))
    val wstrb    = Output(UInt(4.W))
}

class TLSUMemIO extends Bundle {
    val rreq       = Input(Bool())
    val wreq       = Input(Bool())
    val vaddr      = Input(UInt(32.W))     //Input(UInt((tcmIndex + tcmBank + tcmByte).W))
    val mtype      = Input(UInt(3.W))
    val wdata      = Input(UInt(32.W))

    val rdata      = Output(UInt(32.W))
}

class TSingalPortIO extends Bundle {
    val lsp  = Vec(2, new TLSUMemIO)
    //val mem = new TMemTxnIO
    val bankconflictstall = Output(Bool())
}

class SinglePortRAM(
  nByte: Int, byteWidth: Int, depth: Int
) extends Module {
  val io = IO(new Bundle {
    val enr   = Input(Bool())
    val enw   = Input(Bool())
    val we   = Input(UInt(nByte.W))
    val addr = Input(UInt(log2Ceil(depth).W))
    val din  = Input(UInt((nByte * byteWidth).W))
    val dout = Output(UInt((nByte * byteWidth).W))
  })

    val mem = SyncReadMem(depth, Vec(nByte, UInt(byteWidth.W)))

    val memOut = WireDefault(0.U((nByte * byteWidth).W))
    io.dout := memOut
    
    val dataVec = VecInit(io.din(7,0),io.din(15,8),io.din(23,16),io.din(31,24))
    val webbools = io.we.asBools

    when(io.enr){
        memOut := mem.read(io.addr, io.enr).asUInt
    }
    when(io.enw){
        mem.write(io.addr, dataVec, webbools)
    }
}

class TSingalPort extends Module {
    val io = IO(new TSingalPortIO)

    // SRAM
    val tcm = VecInit.fill(tcmBanknums)(Module(new SinglePortRAM(tcmLine,8,tcmIndexNum)).io)

    // Utils
    def byteSel(addr: UInt)   = addr(tcmByte - 1, 0)
    def bankSel(addr: UInt)   = addr(tcmBank + tcmByte - 1, tcmByte)
    def idxSel(addr: UInt)    = addr(tcmIndex + tcmBank + tcmByte - 1,tcmBank + tcmByte)

    // LSP
    val lsp0       = (new LspipelineSingal)(io.lsp(0))
    val lsp1       = (new LspipelineSingal)(io.lsp(1))
    val lsp0byte   = byteSel(lsp0.vaddr)
    val lsp1byte   = byteSel(lsp1.vaddr)
    val lsp0bank   = bankSel(lsp0.vaddr)
    val lsp1bank   = bankSel(lsp1.vaddr)
    val lsp0idx    = idxSel(lsp0.vaddr)
    val lsp1idx    = idxSel(lsp1.vaddr)
    val lsp0BankOH = UIntToOH(lsp0bank, tcmBanknums)
    val lsp1BankOH = UIntToOH(lsp1bank, tcmBanknums)
    val lsp0vaild  = lsp0.rreq || lsp0.wreq
    val lsp1vaild  = lsp1.rreq || lsp1.wreq
    val lsp0wmask = MTypeDecode(lsp0.mtype) << lsp0byte
    val lsp1wmask = MTypeDecode(lsp1.mtype) << lsp1byte
    val lsp0wdata = lsp0.wdata << (lsp0byte << 3)
    val lsp1wdata = lsp1.wdata << (lsp1byte << 3)

    // Bank conflict 检测
    val bankconflict    = lsp0vaild && lsp1vaild && (lsp0bank === lsp1bank)
    val conflictInFlight = RegInit(false.B)
    val stall_req = bankconflict && !conflictInFlight
    when(stall_req) {
        // 第一次看到冲突 → 进入 stall
        conflictInFlight := true.B
    }.elsewhen (conflictInFlight) {
        // 冲突事务第二拍执行完成
        conflictInFlight := false.B
    }
    io.bankconflictstall     := stall_req

    for (i <- 0 until tcmBanknums) {
        val hit0 = lsp0vaild && lsp0BankOH(i) && !conflictInFlight
        val hit1 = (lsp1vaild && lsp1BankOH(i)) && (!bankconflict || conflictInFlight)

        // 地址
        tcm(i).addr := Mux(hit0, lsp0idx, lsp1idx)

        // 读写使能
        tcm(i).enr :=
            (lsp0.rreq  && hit0) ||
            (lsp1.rreq  && hit1)
        tcm(i).enw :=
            (lsp0.wreq && hit0) ||
            (lsp1.wreq && hit1)

        // 写数据
        tcm(i).we  := Mux(hit0, lsp0wmask(3,0), lsp1wmask(3,0))
        tcm(i).din := Mux(hit0, lsp0wdata(31,0), lsp1wdata(31,0))
    }

    val lsp0_rdatanow = Mux1H(lsp0BankOH, tcm.map(_.dout))
    val lsp0_rdatalast = RegNext(lsp0_rdatanow)
    val lsp0_rdata = Mux(RegNext(conflictInFlight), lsp0_rdatalast, lsp0_rdatanow)
    val lsp1_rdata = Mux1H(lsp1BankOH, tcm.map(_.dout))

    io.lsp(0).rdata := MuxLookup(lsp0.mtype(1, 0), 0.U(32.W))(Seq(
        0.U(2.W) -> Fill(24, Mux(lsp0.mtype(2), 0.U(1.W), lsp0_rdata(7))) ## lsp0_rdata(7, 0),
        1.U(2.W) -> Fill(16, Mux(lsp0.mtype(2), 0.U(1.W), lsp0_rdata(15))) ## lsp0_rdata(15, 0),
        2.U(2.W) -> lsp0_rdata,
    ))
    io.lsp(1).rdata := MuxLookup(lsp1.mtype(1, 0), 0.U(32.W))(Seq(
        0.U(2.W) -> Fill(24, Mux(lsp1.mtype(2), 0.U(1.W), lsp1_rdata(7))) ## lsp1_rdata(7, 0),
        1.U(2.W) -> Fill(16, Mux(lsp1.mtype(2), 0.U(1.W), lsp1_rdata(15))) ## lsp1_rdata(15, 0),
        2.U(2.W) -> lsp1_rdata,
    ))

}
