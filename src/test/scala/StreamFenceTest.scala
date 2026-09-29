import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import ZirconConfig.EXEOp._

// Exercise the actual FIFO2 -> AXI arbiter -> drain -> dispatch path. Only
// retirement events and the external AXI slave are driven by the testbench.
class StreamFenceHarness extends Module {
  val io = IO(new Bundle {
    val cfgValid = Input(Bool())
    val cfgOp = Input(UInt(stInstBits.W))
    val cfgValue = Input(UInt(32.W))
    val exportValid = Input(Bool())
    val exportIndex = Input(UInt(32.W))
    val instruction = Input(UInt(32.W))
    val instructionValid = Input(Bool())
    val fenceRetired = Input(Bool())
    val robEmpty = Input(Bool())
    val cpuStoresDrained = Input(Bool())
    val awready = Input(Bool())
    val wready = Input(Bool())
    val bvalid = Input(Bool())
    val awvalid = Output(Bool())
    val awaddr = Output(UInt(32.W))
    val wvalid = Output(Bool())
    val wlast = Output(Bool())
    val wdata = Output(UInt(32.W))
    val bready = Output(Bool())
    val writePending = Output(Bool())
    val storesDrained = Output(Bool())
    val dispatched = Output(Bool())
  })
  val stream = Module(new StreamEngine)
  val arbiter = Module(new AXIArbiter)
  val dispatch = Module(new Dispatch)

  // Unused memory/read/issue ports are quiescent, not unconstrained DontCare.
  def tieInputs(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(tieInputs)
    case v: Vec[_] => v.foreach(x => tieInputs(x.asInstanceOf[Data]))
    case b: Bits if DataMirror.directionOf(b) == ActualDirection.Input => b := 0.U.asTypeOf(b)
    case _ =>
  }
  Seq(stream.io, arbiter.io, dispatch.io).foreach(tieInputs)
  stream.io.mem <> arbiter.io.stream
  stream.io.rdIter <> dispatch.io.seRIter
  stream.io.pp.valid := io.cfgValid
  stream.io.pp.op := io.cfgOp
  stream.io.pp.src1 := io.cfgValue
  stream.io.pp.src2 := 2.U
  stream.io.pp.cfgState(ZirconConfig.Stream.DONECFG) := true.B
  stream.io.cmt.outer(0).valid := io.exportValid
  stream.io.cmt.outer(0).info.op := VREGOUT8
  stream.io.cmt.outer(0).info.fifoId(2) := 2.U
  stream.io.cmt.outer(0).index(2) := io.exportIndex
  for (lane <- 0 until 8) stream.io.cmt.outer(0).data(lane) := io.exportIndex + lane.U

  arbiter.io.axi.awready := io.awready
  arbiter.io.axi.wready := io.wready
  arbiter.io.axi.bvalid := io.bvalid
  dispatch.io.cmt.rob.enq.foreach(_.ready := true.B)
  dispatch.io.cmt.bdb.enq.foreach(_.ready := true.B)
  dispatch.io.cmt.robEmpty := io.robEmpty
  dispatch.io.cmt.fenceRetired := io.fenceRetired
  dispatch.io.bke.instPkg.foreach(_.foreach(_.ready := true.B))
  dispatch.io.bke.storesDrained := stream.io.storesDrained && io.cpuStoresDrained
  dispatch.io.fte.instPkg(0).valid := io.instructionValid
  dispatch.io.fte.instPkg(0).bits.valid := io.instructionValid
  dispatch.io.fte.instPkg(0).bits.inst := io.instruction
  dispatch.io.fte.instPkg(0).bits.func := 1.U

  io.awvalid := arbiter.io.axi.awvalid
  io.awaddr := arbiter.io.axi.awaddr
  io.wvalid := arbiter.io.axi.wvalid
  io.wlast := arbiter.io.axi.wlast
  io.wdata := arbiter.io.axi.wdata
  io.bready := arbiter.io.axi.bready
  io.writePending := arbiter.io.stream.writePending
  io.storesDrained := stream.io.storesDrained
  io.dispatched := dispatch.io.cmt.rob.enq(0).fire
}

class StreamFenceTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "stream write completion fence"

  it should "drain W beats without bubbles but hold the fence through delayed B responses" in {
    test(new StreamFenceHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.cfgValid.poke(false.B); c.io.cfgOp.poke(0.U); c.io.cfgValue.poke(0.U)
      c.io.exportValid.poke(false.B); c.io.exportIndex.poke(0.U)
      c.io.instruction.poke("h0330000f".U); c.io.instructionValid.poke(false.B)
      c.io.fenceRetired.poke(false.B); c.io.robEmpty.poke(true.B)
      c.io.cpuStoresDrained.poke(true.B)
      c.io.awready.poke(false.B); c.io.wready.poke(false.B); c.io.bvalid.poke(false.B)
      c.clock.step()
      c.io.storesDrained.expect(true.B)
      def config(op: UInt, value: BigInt): Unit = {
        c.io.cfgValid.poke(true.B); c.io.cfgOp.poke(op); c.io.cfgValue.poke(value.U)
        c.clock.step(); c.io.cfgValid.poke(false.B)
      }
      config(CFGI, (BigInt(64) << 16) | 1)
      config(CFGSTORE, BigInt("80004000", 16))
      for (index <- 0 until 64 by 8) {
        c.io.exportValid.poke(true.B); c.io.exportIndex.poke(index.U); c.clock.step()
      }
      c.io.exportValid.poke(false.B)
      c.io.instructionValid.poke(true.B)

      for (burst <- 0 until 2) {
        var waited = 0
        while (!c.io.awvalid.peek().litToBoolean && waited < 10) {
          c.io.dispatched.expect(false.B); c.clock.step(); waited += 1
        }
        c.io.awvalid.expect(true.B)
        c.io.awaddr.expect((BigInt("80004000", 16) + burst * 128).U)
        for (_ <- 0 until 3) {
          c.io.writePending.expect(true.B); c.io.dispatched.expect(false.B); c.clock.step()
        }
        c.io.awready.poke(true.B); c.clock.step(); c.io.awready.poke(false.B)
        for (word <- 0 until 32) {
          if (word == 7) {
            c.io.wready.poke(false.B)
            for (_ <- 0 until 3) {
              c.io.wvalid.expect(true.B); c.io.wdata.expect((burst * 32 + word).U)
              c.io.dispatched.expect(false.B); c.clock.step()
            }
          }
          c.io.wready.poke(true.B)
          c.io.wvalid.expect(true.B); c.io.wlast.expect((word == 31).B)
          c.io.wdata.expect((burst * 32 + word).U)
          c.io.dispatched.expect(false.B); c.clock.step()
        }
        c.io.wready.poke(false.B)
        // All data beats have been accepted, but the slave withholds B.
        for (_ <- 0 until (if (burst == 0) 32 else 128)) {
          c.io.wvalid.expect(false.B); c.io.awvalid.expect(false.B)
          c.io.bready.expect(true.B); c.io.writePending.expect(true.B)
          c.io.storesDrained.expect(false.B); c.io.dispatched.expect(false.B)
          c.clock.step()
        }
        c.io.cpuStoresDrained.poke(false.B)
        c.io.bvalid.poke(true.B)
        c.io.dispatched.expect(false.B); c.clock.step(); c.io.bvalid.poke(false.B)
        if (burst == 0) c.io.cpuStoresDrained.poke(true.B)
      }
      c.io.writePending.expect(false.B); c.io.storesDrained.expect(true.B)
      // Completion of stream writes does not bypass pending CPU stores or ROB work.
      c.io.dispatched.expect(false.B); c.clock.step(3)
      c.io.robEmpty.poke(false.B); c.io.cpuStoresDrained.poke(true.B)
      c.io.dispatched.expect(false.B); c.clock.step(3)
      c.io.robEmpty.poke(true.B); c.io.dispatched.expect(true.B); c.clock.step()
      c.io.instruction.poke("h00000013".U)
      c.io.dispatched.expect(false.B); c.clock.step(3)
      c.io.fenceRetired.poke(true.B); c.clock.step(); c.io.fenceRetired.poke(false.B)
      c.io.dispatched.expect(true.B)
    }
  }
}
