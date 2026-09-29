import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._
import ZirconConfig.Stream._
import ZirconConfig.Commit._

object StreamOuter {
  def isOp(op: UInt): Bool = op === VOMAC8 || op === VCLR || op === VREGOUT8
  def isElementwise(op: UInt): Bool = op === VADD8 || op === VSUB8 || op === VMUL8
  def isVector(op: UInt): Bool = isOp(op) || isElementwise(op) || op === VDOT8
  def readsStream(op: UInt): Bool = op === VOMAC8 || isElementwise(op) || op === VDOT8
  def writesStream(op: UInt): Bool = op === VREGOUT8 || isElementwise(op)

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
    val operands = Input(Vec(2, Vec(8, UInt(32.W))))
    val commit = Input(Vec(ncommit, new StreamOuterEvent))
    val flush = Input(Bool())
    val result = Output(Vec(8, UInt(32.W)))
    val dot = Output(UInt(32.W))
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
  // The same eight adders handle lane ADD/SUB, accumulator MAC, or the
  // seven nodes of a balanced DOT tree. DOT does not touch accumulator state.
  val sums = Wire(Vec(8, UInt(32.W)))
  val op = io.execute.info.op
  for (lane <- 0 until 8) {
    val treeA = if (lane < 4) io.execute.data(2 * lane)
      else if (lane < 6) sums(2 * (lane - 4)) else if (lane == 6) sums(4) else 0.U(32.W)
    val treeB = if (lane < 4) io.execute.data(2 * lane + 1)
      else if (lane < 6) sums(2 * (lane - 4) + 1) else if (lane == 6) sums(5) else 0.U(32.W)
    val lhs = Mux(op === VDOT8, treeA,
      Mux(op === VOMAC8, spec(io.execute.info.bank)(lane), io.operands(0)(lane)))
    val rhs = Mux(op === VDOT8, treeB,
      Mux(op === VOMAC8, io.execute.data(lane), io.operands(1)(lane)))
    sums(lane) := BLevelPAdder32(lhs, Mux(op === VSUB8, ~rhs, rhs), (op === VSUB8).asUInt).io.res
    io.result(lane) := Mux(op === VOMAC8 || op === VADD8 || op === VSUB8, sums(lane),
      Mux(op === VMUL8 || op === VDOT8, io.execute.data(lane), spec(io.execute.info.bank)(lane)))
  }
  io.dot := sums(6)
  when(io.execute.valid) {
    when(io.execute.info.op === VCLR) {
      spec := VecInit.fill(8)(VecInit.fill(8)(0.U(32.W)))
    }.elsewhen(io.execute.info.op === VOMAC8) {
      spec(io.execute.info.bank) := io.result
    }
  }
  when(io.flush) { spec := nextArch }
}
