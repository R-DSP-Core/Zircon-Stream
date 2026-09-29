import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import ZirconConfig.EXEOp._

class OuterDecodeHarness extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val info = Output(new StreamInfo)
    val regs = Output(new RegisterInfo)
    val func = Output(UInt(3.W))
  })
  val pre = Module(new PreDecoder)
  pre.io.instPkg := 0.U.asTypeOf(new FrontendPackage)
  pre.io.instPkg.inst := io.inst
  pre.io.returnOffset := 0.U
  val decode = Module(new Decoder)
  decode.io.inst := io.inst
  decode.io.rinfo := pre.io.rinfo
  io.info := decode.io.sinfo
  io.regs := pre.io.rinfo
  io.func := decode.io.func
}

class OuterIndexHarness extends Module {
  val io = IO(new Bundle {
    val state = Input(new dynIState)
    val limit = Input(UInt(32.W))
    val repeat = Input(UInt(32.W))
    val offset = Input(UInt(32.W))
    val fire = Input(Vec(2, Bool()))
    val words = Input(Vec(2, UInt(4.W)))
    val index = Output(Vec(2, UInt(32.W)))
    val next = Output(new dynIState)
  })
  val middle = StreamIndex.advance(io.state, io.fire(0), io.words(0), io.limit, io.repeat, io.offset)
  io.index(0) := io.state.iCnt
  io.index(1) := middle.iCnt
  io.next := StreamIndex.advance(middle, io.fire(1), io.words(1), io.limit, io.repeat, io.offset)
}

class StreamOuterTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "stream outer extension"
  private def encoding(f7: Int, rd: Int, a: Int, b: Int, f3: Int = 6): BigInt =
    (BigInt(f7) << 25) | (BigInt(b) << 20) | (BigInt(a) << 15) | (BigInt(f3) << 12) | (rd << 7) | 0x0b

  it should "decode literal IDs without GPR dependencies and retain legacy RRS/SSR" in {
    test(new OuterDecodeHarness) { c =>
      c.io.inst.poke(encoding(0x40, 7, 4, 5).U)
      c.io.info.op.expect(VOMAC8)
      c.io.info.bank.expect(7.U)
      c.io.info.fifoId(0).expect(4.U)
      c.io.info.fifoId(1).expect(5.U)
      c.io.info.words(0).expect(1.U)
      c.io.info.words(1).expect(8.U)
      c.io.info.useBuffer(2).expect(false.B)
      c.io.func.expect(2.U)
      c.io.regs.rjVld.expect(false.B)
      c.io.regs.rkVld.expect(false.B)
      c.io.regs.rdVld.expect(false.B)
      c.io.inst.poke(encoding(0x42, 4, 7, 0).U)
      c.io.info.op.expect(VREGOUT8)
      c.io.info.bank.expect(7.U)
      c.io.info.fifoId(2).expect(4.U)
      c.io.info.words(2).expect(8.U)
      c.io.info.useBuffer(0).expect(false.B)
      c.io.inst.poke(encoding(0x41, 0, 0, 0).U)
      c.io.info.op.expect(VCLR)
      c.io.info.useBuffer.foreach(_.expect(false.B))
      c.io.inst.poke(encoding(0, 0, 10, 11).U)
      c.io.info.op.expect(CALRJRKSTREAMPP)
      c.io.regs.rjVld.expect(true.B)
      c.io.regs.rkVld.expect(true.B)
      c.io.inst.poke(encoding(16, 10, 4, 5, 7).U)
      c.io.info.op.expect(CALSTREAMRD)
      c.io.regs.rdVld.expect(true.B)
      c.io.regs.rjVld.expect(false.B)
      c.io.regs.rkVld.expect(false.B)
    }
  }

  it should "fold both slots across the last B repeat without reusing the old window" in {
    test(new OuterIndexHarness) { c =>
      c.io.limit.poke(8.U); c.io.repeat.poke(8.U); c.io.offset.poke(0.U)
      c.io.state.iCnt.poke(24.U); c.io.state.iLimit.poke(32.U); c.io.state.iRepeat.poke(7.U)
      c.io.fire.foreach(_.poke(true.B)); c.io.words.foreach(_.poke(8.U))
      c.io.index(0).expect(24.U); c.io.index(1).expect(32.U)
      c.io.next.iCnt.expect(32.U); c.io.next.iLimit.expect(40.U); c.io.next.iRepeat.expect(1.U)
      // Scalar windows can wrap twice in one dispatch/retirement cycle.
      c.io.limit.poke(1.U); c.io.repeat.poke(2.U)
      c.io.state.iCnt.poke(0.U); c.io.state.iLimit.poke(1.U); c.io.state.iRepeat.poke(0.U)
      c.io.words.foreach(_.poke(1.U))
      c.io.index(1).expect(0.U)
      c.io.next.iCnt.expect(1.U); c.io.next.iLimit.expect(2.U); c.io.next.iRepeat.expect(0.U)
      c.io.fire(0).poke(false.B)
      c.io.index(1).expect(0.U); c.io.next.iCnt.expect(0.U); c.io.next.iRepeat.expect(1.U)
    }
  }

  it should "issue in enqueue order even when ROB bank offsets sort backwards" in {
    test(new IssueQueue(2, 1, 4, false, ordered = true)) { c =>
      def zero(data: Data): Unit = data match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case v: Vec[_] => v.foreach(x => zero(x.asInstanceOf[Data]))
        case r: Record => r.elements.values.foreach(zero)
      }
      c.io.flush.poke(false.B)
      c.io.wakeBus.foreach(zero); zero(c.io.rplyBus)
      c.io.se.foreach(_.ready.poke(true.B))
      c.io.deq(0).ready.poke(false.B)
      c.io.enq.foreach(p => { p.valid.poke(false.B); zero(p.bits) })
      c.clock.step()
      for (i <- 0 until 2) {
        val port = c.io.enq(i)
        port.valid.poke(true.B); port.bits.valid.poke(true.B)
        port.bits.isCalStream.poke(true.B); port.bits.needStreamSrc.poke(true.B)
        port.bits.pc.poke((0x100 + 4 * i).U)
        port.bits.robIdx.offset.poke((5 - i).U)
        port.bits.robIdx.qidx.poke(i.U)
        port.bits.sinfo.op.poke(if (i == 0) VCLR else VOMAC8)
        c.io.enq(i).ready.expect(true.B)
      }
      c.clock.step()
      c.io.enq.foreach(_.valid.poke(false.B))
      c.io.deq(0).ready.poke(true.B)
      c.io.deq(0).valid.expect(true.B)
      c.io.deq(0).bits.pc.expect(0x100.U)
      c.clock.step()
      c.io.deq(0).valid.expect(true.B)
      c.io.deq(0).bits.pc.expect(0x104.U)
      c.clock.step()
      c.io.deq(0).valid.expect(false.B)
      // Exercise sequence wrap and free-list reuse with unrelated ROB indices.
      for (batch <- 0 until 40) {
        c.io.deq(0).ready.poke(false.B)
        var waited = 0
        while (!c.io.enq(1).ready.peek().litToBoolean && waited < 8) {
          c.clock.step(); waited += 1
        }
        c.io.enq(1).ready.expect(true.B)
        for (i <- 0 until 2) {
          c.io.enq(i).valid.poke(true.B)
          c.io.enq(i).bits.pc.poke((0x200 + batch * 8 + i * 4).U)
        }
        c.clock.step()
        c.io.enq.foreach(_.valid.poke(false.B))
        c.io.deq(0).ready.poke(true.B)
        for (i <- 0 until 2) {
          c.io.deq(0).valid.expect(true.B)
          c.io.deq(0).bits.pc.expect((0x200 + batch * 8 + i * 4).U)
          c.clock.step()
        }
      }
    }
  }

  it should "accumulate consecutive MACs and recover the committed prefix after a flush" in {
    test(new StreamOuterRegisters) { c =>
      def event(e: StreamOuterEvent, valid: Boolean, op: UInt, bank: Int, value: Long): Unit = {
        e.valid.poke(valid.B); e.info.op.poke(op); e.info.bank.poke(bank.U)
        e.info.state.foreach(_.poke(false.B)); e.info.useBuffer.foreach(_.poke(false.B))
        e.info.usePPBuffer.foreach(_.poke(false.B)); e.info.fifoId.foreach(_.poke(0.U))
        e.info.words.foreach(_.poke(1.U)); e.index.foreach(_.poke(0.U))
        e.data.foreach(_.poke(value.U))
      }
      c.io.flush.poke(false.B)
      c.io.commit.foreach(e => event(e, false, VCLR, 0, 0))
      event(c.io.execute, true, VOMAC8, 3, 7)
      c.io.result.foreach(_.expect(7.U)); c.clock.step()
      event(c.io.execute, true, VOMAC8, 3, 9)
      c.io.result.foreach(_.expect(16.U)); c.clock.step()
      event(c.io.execute, false, VREGOUT8, 3, 0)
      c.io.result.foreach(_.expect(16.U))
      // Two retirement slots: clear precedes an older MAC in the same cycle.
      event(c.io.commit(0), true, VCLR, 0, 0)
      event(c.io.commit(1), true, VOMAC8, 3, 7)
      c.io.flush.poke(true.B); c.clock.step()
      c.io.flush.poke(false.B)
      c.io.commit.foreach(e => event(e, false, VCLR, 0, 0))
      c.io.result.foreach(_.expect(7.U))
      event(c.io.execute, true, VCLR, 0, 0); c.clock.step()
      event(c.io.execute, false, VREGOUT8, 3, 0)
      c.io.result.foreach(_.expect(0.U))
      c.io.flush.poke(true.B); c.clock.step()
      c.io.result.foreach(_.expect(7.U))
    }
  }
}
