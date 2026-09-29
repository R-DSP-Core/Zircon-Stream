import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._
import ZirconConfig.Stream._
import ZirconConfig.Commit._

object StreamOuter {
  def isOp(op: UInt): Bool = op === VOMAC8 || op === VCLR || op === VREGOUT8

  // One instruction consumes the union of its source windows, not their sum.
  def readWords(info: StreamInfo, fifo: UInt): UInt = {
    val a = Mux(info.useBuffer(0) && info.fifoId(0) === fifo, info.words(0), 0.U)
    val b = Mux(info.useBuffer(1) && info.fifoId(1) === fifo, info.words(1), 0.U)
    Mux(a > b, a, b)
  }

  def isFence(inst: UInt): Bool = inst(6, 0) === 0x0f.U && inst(14, 12) === 0.U
}

object StreamIndex {
  def advance(state: dynIState, fire: Bool, words: UInt, limit: UInt,
              repeat: UInt, offset: UInt): dynIState = {
    val next = WireDefault(state)
    val sum = state.iCnt + words
    when(fire) {
      when(sum < state.iLimit) {
        next.iCnt := sum
      }.elsewhen(state.iRepeat + 1.U === repeat) {
        next.iCnt := sum
        next.iLimit := state.iLimit + limit
        next.iRepeat := 0.U
      }.otherwise {
        next.iCnt := sum - limit + offset
        next.iLimit := state.iLimit + offset
        next.iRepeat := state.iRepeat + 1.U
      }
    }
    next
  }
}

class SEOuterRFIO extends Bundle {
  val fifo = Input(UInt(streamBits.W))
  val index = Input(UInt(32.W))
  val data = Output(Vec(8, UInt(32.W)))
}

class StreamOuterEvent extends Bundle {
  val valid = Bool()
  val info = new StreamInfo
  val index = Vec(3, UInt(32.W))
  val data = Vec(8, UInt(32.W))
}

// Accumulator state is speculative until ROB retirement. Export captures a
// snapshot in the ROB; a younger clear/MAC cannot change an older export.
class StreamOuterRegisters extends Module {
  val io = IO(new Bundle {
    val execute = Input(new StreamOuterEvent)
    val commit = Input(Vec(ncommit, new StreamOuterEvent))
    val flush = Input(Bool())
    val result = Output(Vec(8, UInt(32.W)))
  })
  val spec = RegInit(VecInit.fill(8)(VecInit.fill(8)(0.U(32.W))))
  val arch = RegInit(VecInit.fill(8)(VecInit.fill(8)(0.U(32.W))))
  val nextArch = WireDefault(arch)
  for (bank <- 0 until 8; lane <- 0 until 8) {
    var value = arch(bank)(lane)
    for (event <- io.commit) {
      value = Mux(event.valid && event.info.op === VCLR, 0.U,
        Mux(event.valid && event.info.op === VOMAC8 && event.info.bank === bank.U,
          event.data(lane), value))
    }
    nextArch(bank)(lane) := value
  }
  arch := nextArch
  for (lane <- 0 until 8) {
    io.result(lane) := Mux(io.execute.info.op === VOMAC8,
      spec(io.execute.info.bank)(lane) + io.execute.data(lane), spec(io.execute.info.bank)(lane))
  }
  when(io.execute.valid) {
    when(io.execute.info.op === VCLR) {
      spec := VecInit.fill(8)(VecInit.fill(8)(0.U(32.W)))
    }.elsewhen(io.execute.info.op === VOMAC8) {
      spec(io.execute.info.bank) := io.result
    }
  }
  when(io.flush) { spec := nextArch }
}
