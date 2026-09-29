import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import ZirconConfig.EXEOp._

class SimdReadWordsHarness extends Module {
  val io = IO(new Bundle {
    val info = Input(new StreamInfo)
    val words = Output(Vec(6, UInt(4.W)))
  })
  for (i <- 0 until 6) io.words(i) := StreamOuter.readWords(io.info, i.U)
}

class StreamSimdTest extends AnyFlatSpec with ChiselScalatestTester {
  private def zero(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case v: Vec[_] => v.foreach(x => zero(x.asInstanceOf[Data]))
    case r: Record => r.elements.values.foreach(zero)
  }
  private def encoding(f7: Int, f3: Int, rd: Int, a: Int, b: Int): BigInt =
    (BigInt(f7) << 25) | (BigInt(b) << 20) | (BigInt(a) << 15) | (f3 << 12) | (rd << 7) | 0x0b

  behavior of "SIMD8 stream operations"
  it should "decode two vector sources and distinguish a stream destination from a real GPR" in {
    test(new OuterDecodeHarness) { c =>
      for ((f7, op) <- Seq((0x40, VADD8), (0x48, VSUB8), (0x50, VMUL8));
           (a, b, d) <- Seq((0, 1, 2), (4, 4, 5), (5, 1, 4))) {
        c.io.inst.poke(encoding(f7, 2, d, a, b).U)
        c.io.info.op.expect(op)
        c.io.info.useBuffer.foreach(_.expect(true.B))
        c.io.info.words.foreach(_.expect(8.U))
        c.io.info.fifoId(0).expect(a.U); c.io.info.fifoId(1).expect(b.U)
        c.io.info.fifoId(2).expect(d.U)
        c.io.regs.rjVld.expect(false.B); c.io.regs.rkVld.expect(false.B)
        c.io.regs.rdVld.expect(false.B); c.io.func.expect(2.U)
      }
      for (rd <- Seq(0, 10, 29)) {
        c.io.inst.poke(encoding(0x60, 7, rd, 4, 5).U)
        c.io.info.op.expect(VDOT8)
        c.io.info.useBuffer(0).expect(true.B); c.io.info.useBuffer(1).expect(true.B)
        c.io.info.useBuffer(2).expect(false.B)
        c.io.info.words(0).expect(8.U); c.io.info.words(1).expect(8.U)
        c.io.regs.rjVld.expect(false.B); c.io.regs.rkVld.expect(false.B)
        c.io.regs.rdVld.expect((rd != 0).B); c.io.func.expect(2.U)
      }
    }
  }

  it should "consume the union of aliased source windows" in {
    test(new SimdReadWordsHarness) { c =>
      zero(c.io.info)
      c.io.info.useBuffer(0).poke(true.B); c.io.info.useBuffer(1).poke(true.B)
      c.io.info.words(0).poke(8.U); c.io.info.words(1).poke(8.U)
      c.io.info.fifoId(0).poke(4.U); c.io.info.fifoId(1).poke(4.U)
      for (i <- 0 until 6) c.io.words(i).expect((if (i == 4) 8 else 0).U)
      c.io.info.fifoId(1).poke(5.U)
      c.io.words(4).expect(8.U); c.io.words(5).expect(8.U)
    }
  }

  it should "share modulo-32 adders for lane arithmetic and dot without altering accumulator banks" in {
    test(new StreamOuterRegisters) { c =>
      zero(c.io.execute); zero(c.io.operands); c.io.commit.foreach(zero)
      c.io.flush.poke(false.B)
      c.io.execute.valid.poke(true.B); c.io.execute.info.op.poke(VOMAC8)
      c.io.execute.info.bank.poke(3.U)
      for (i <- 0 until 8) c.io.execute.data(i).poke((i + 11).U)
      c.clock.step()
      val rng = new scala.util.Random(0x51ad)
      val mask = (BigInt(1) << 32) - 1
      for (_ <- 0 until 100) {
        val a = Seq.fill(8)(BigInt(32, rng)); val b = Seq.fill(8)(BigInt(32, rng))
        val products = a.zip(b).map { case (x, y) => (x * y) & mask }
        for (i <- 0 until 8) {
          c.io.operands(0)(i).poke(a(i).U); c.io.operands(1)(i).poke(b(i).U)
          c.io.execute.data(i).poke(products(i).U)
        }
        for (op <- Seq(VADD8, VSUB8, VMUL8, VDOT8)) {
          c.io.execute.info.op.poke(op)
          val expected = if (op.litValue == VADD8.litValue) a.zip(b).map { case (x, y) => (x + y) & mask }
            else if (op.litValue == VSUB8.litValue) a.zip(b).map { case (x, y) => (x - y) & mask }
            else products
          for (i <- 0 until 8) c.io.result(i).expect(expected(i).U)
          if (op.litValue == VDOT8.litValue) c.io.dot.expect((products.sum & mask).U)
          c.clock.step()
        }
      }
      c.io.execute.info.op.poke(VREGOUT8)
      for (i <- 0 until 8) c.io.result(i).expect((i + 11).U)
    }
  }
}
