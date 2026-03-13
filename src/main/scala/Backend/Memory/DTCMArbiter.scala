import chisel3._
import chisel3.util._

class TbMemIO extends Bundle {
    val rreq    = Output(Bool())
    val rrsp    = Input(Bool())
    val rlast   = Input(Bool())
    val raddr   = Output(UInt(32.W))
    val rdata   = Input(UInt(32.W))
    val rlen    = Output(UInt(8.W))
    val rsize   = Output(UInt(2.W))

    val wreq    = Output(Bool())
    val wrsp    = Input(Bool())
    val wlast   = Output(Bool())
    val waddr   = Output(UInt(32.W))
    val wdata   = Output(UInt(32.W))
    val wlen    = Output(UInt(8.W))
    val wsize   = Output(UInt(2.W))
    val wstrb   = Output(UInt(4.W))
}

class DTCMArbiter(
    nByte: Int, byteWidth: Int, depth: Int
  ) extends Module {
    val io = IO(new Bundle {
      // Port A
      val ena   = Input(Bool())
      //val wea   = Input(UInt(nByte.W))
      val addra = Input(UInt(log2Ceil(depth).W))
      val dina  = Input(UInt((nByte * byteWidth).W))
      val douta = Output(UInt((nByte * byteWidth).W))
      // Port B
      val enb   = Input(Bool())
      val web   = Input(UInt(nByte.W))
      val addrb = Input(UInt(log2Ceil(depth).W))
      val dinb  = Input(UInt((nByte * byteWidth).W))
      //val doutb = Output(UInt((nByte * byteWidth).W))

      val enctrlw  = Input(Bool())
      val enctrlr  = Input(Bool())
      val mem   = new TbMemIO

    })

  // // 用 SyncReadMem 模拟伪双口
  // val mem = SyncReadMem(depth, Vec(nByte, UInt(byteWidth.W)))
  val mem = Module(new XilinxTrueDualPortReadFirstByteWrite1ClockRam(nByte, byteWidth, depth)).io

  val memAddr   = RegInit(0.U(32.W))
  val tcmAddr   = RegInit(0.U(32.W))
  val length    = RegInit(0.U(32.W))
  val ctrl      = RegInit(0.U(8.W))  // (7654):(fft bits) 3 2:(1:bit reverse) 1:(0:mem->tcm,1:tcm->mem) 0:(1:start,0:0)
  val status    = RegInit(false.B)  // 0:idle 1:busy

  //input process
  val memEna       = io.ena
  val memEnb       = io.enb
  val ctrlRegWrite = io.enctrlw
  val ctrlRegread  = io.enctrlr

  val fifoDepth = 32
  val fifo      = Module(new Queue(UInt(32.W), fifoDepth))

  def bitReverseIndex(idx: UInt, bits :UInt): UInt = {
    //val rev3  = Cat((0 until 3 ).map(i => idx(i)))   //测试用
    val rev8  = Cat((0 until 8 ).map(i => idx(i)))
    val rev9  = Cat((0 until 9 ).map(i => idx(i)))
    val rev10 = Cat((0 until 10).map(i => idx(i)))

    MuxLookup(bits, rev10)(Seq(
      //3.U  -> rev3,             //测试用
      8.U  -> rev8,
      9.U  -> rev9,
      10.U -> rev10,
    ))
  }

  val status1 = RegInit(false.B) //t2f/f2t 1:finish
  val status2 = RegInit(false.B)
  val idx = RegInit(0.U(16.W))

  val ent2f = WireDefault(false.B)
  ent2f := ctrl(0) && ctrl(1) && fifo.io.enq.ready && !status1
  val dot2f = !memEna && ent2f      // 本次进行一次有效的 TCM → FIFO 读

  val enf2t = WireDefault(false.B)
  enf2t := ctrl(0) && !ctrl(1) && fifo.io.deq.valid && !status1
  val dof2t = !memEnb && enf2t      // 本次进行一次有效的 FIFO → TCM 写

  when(status1){
    idx := 0.U
  }.elsewhen(dot2f || dof2t){
    idx := idx + 1.U
  }

  val rev  = bitReverseIndex(idx,ctrl(7,4).asUInt)
  val taddr = Mux(ctrl(2),tcmAddr + rev << 2,tcmAddr + idx << 2)

  val memOut  = WireDefault(0.U((nByte * byteWidth).W))
  // memOut := mem.read(Mux(memEna,io.addra,taddr),(memEna || ent2f)).asUInt
  memOut    := mem.douta
  mem.clka  := clock
  mem.addra := Mux(memEna,io.addra,taddr)
  mem.ena   := memEna || ent2f
  mem.dina  := 0.U
  mem.wea   := 0.U

  val ctrlOutReg = Mux(io.addra === "h10".U, status, 0.U)

  val ctrlRegreadReg = RegNext(ctrlRegread)

  val memEnaReg  = RegNext(memEna,false.B)
  val ent2fReg   = RegNext(ent2f,false.B)
  io.douta := Mux(memEnaReg,memOut,Mux(ctrlRegreadReg,Cat(0.U(31.W), ctrlOutReg),0.U))

  //control register writes
  when(ctrlRegWrite && !status){
    switch(io.addra(log2Ceil(depth) - 1,0)){
      is("h00".U) { memAddr := io.dina }
      is("h04".U) { tcmAddr := io.dina }
      is("h08".U) { length  := io.dina }
      is("h0C".U) { ctrl    := io.dina(7,0) }
    }
  }

  // val dataVec = VecInit(io.dinb(7,0),io.dinb(15,8),io.dinb(23,16),io.dinb(31,24))
  // val fifoVec = VecInit(fifo.io.deq.bits(7,0),fifo.io.deq.bits(15,8),fifo.io.deq.bits(23,16),fifo.io.deq.bits(31,24))
  // when(memEnb || enf2t) {
  //   mem.write(Mux(memEnb,io.addrb,taddr), Mux(memEnb,dataVec,fifoVec), Mux(memEnb,io.web,"b1111".U).asBools)
  // }
  mem.addrb := Mux(memEnb,io.addrb,taddr)
  mem.enb   := memEnb || enf2t
  mem.dinb  := Mux(memEnb,io.dinb,fifo.io.deq.bits)
  mem.web   := Mux(memEnb,io.web,"b1111".U)

  //---fifo
  fifo.io.enq.valid := false.B
  fifo.io.enq.bits  := 0.U
  fifo.io.deq.ready := false.B

  when(!ctrl(1)){
    fifo.io.enq.valid := io.mem.rreq && io.mem.rrsp && !status2
    fifo.io.enq.bits  := io.mem.rdata
    fifo.io.deq.ready := dof2t
  }.otherwise{
    fifo.io.deq.ready := io.mem.wreq && io.mem.wrsp && !status2
    fifo.io.enq.valid := ent2fReg && !memEnaReg
    fifo.io.enq.bits  := memOut
  }

  //---FIFO对接AXI接口
  io.mem.rreq   := false.B
  io.mem.rlen   := 0.U
  io.mem.rsize  := 2.U

  io.mem.wreq    := false.B
  io.mem.wlast   := false.B

  io.mem.wdata   := 0.U
  io.mem.wlen    := 0.U
  io.mem.wsize   := 2.U
  io.mem.wstrb   := 0xf.U
  io.mem.wdata   := fifo.io.deq.bits

  val remainLen  = RegInit(0.U(32.W))
  val maddr      = RegInit(0.U(32.W))
  io.mem.raddr   := maddr
  io.mem.waddr   := maddr

  val burstMax  = 16.U     //16.U 4.U为测试用
  val burstLen    = Mux(remainLen < burstMax,remainLen,burstMax)
  val burstRemainLen = RegInit(0.U(32.W))   //burst write remain len

  val mIdle :: m2f :: m2f2 :: f2m :: f2m2 :: mWait :: Nil = Enum(6)
  val mState = RegInit(mIdle)
  val axistate = WireDefault(false.B)

  switch(mState){
    is(mIdle){
      when(ctrl(0)){
        when(!ctrl(1)){
          mState := m2f
        }.otherwise{
          mState := f2m
        }
        remainLen := length
        maddr   := memAddr
        io.mem.wlast   := false.B
      }.otherwise{
        mState := mIdle
      }
    }
    is(m2f){
      when((fifo.io.count + burstLen) <= 32.U){
        io.mem.rreq := true.B
        mState := m2f2
      }.otherwise{
        io.mem.rreq := false.B
        mState := m2f
      }
    }
    is(m2f2){
      io.mem.rreq := true.B
      io.mem.rlen := burstLen - 1.U
      io.mem.raddr := maddr
      when(io.mem.rrsp && io.mem.rlast){
        remainLen := remainLen - burstLen
        when(remainLen === burstLen){
          mState := Mux(!ctrl(0),mIdle,mWait)
        }.otherwise{
          mState := m2f
          maddr := maddr + (burstLen << 2)
        }
      }.otherwise{
        mState := m2f2
      }
    }
    is(f2m){
      when(fifo.io.count >= burstLen){
        io.mem.wreq := true.B
        mState := f2m2
        burstRemainLen := burstLen
      }.otherwise{
        io.mem.wreq := false.B
        mState := f2m
      }
    }
    is(f2m2){
      io.mem.wreq := true.B
      io.mem.waddr := maddr
      io.mem.wlen := burstLen - 1.U
      when(io.mem.wrsp){
        burstRemainLen := burstRemainLen -1.U
        when(burstRemainLen === 1.U){
          io.mem.wlast := true.B
        }
      }.otherwise{
        io.mem.wlast := false.B
      }
      when(io.mem.wrsp && io.mem.wlast){
        remainLen := remainLen - burstLen
        when(remainLen === burstLen){
         mState := Mux(!ctrl(0),mIdle,mWait)
        }.otherwise{
          mState := f2m
          maddr := maddr + (burstLen << 2)
        }
      }.otherwise{
        mState := f2m2
      }
    }
    is(mWait){
      when(!ctrl(0)){
        mState := mIdle
      }.otherwise{
        mState := mWait
      }
    }
  }

  //---status
  val sIdle :: sdoing :: sdone :: Nil = Enum(3)
  val sState = RegInit(sIdle)
  switch(sState){
    is(sIdle){
      when(ctrl(0)){
        status := true.B     // busy=1
        status1 := false.B
        status2 := false.B
        sState := sdoing
      }.otherwise{
        sState := sIdle
      }
    }
    is(sdoing){
      when((idx + 1.U) === length){
        status1 := true.B
      }
      when(remainLen === 0.U){
        status2 := true.B
      }
      when(status1 && status2){
        ctrl := Cat(ctrl(7,1), 0.U(1.W))
        status  := false.B
        sState  := sdone
      }.otherwise{
        sState := sdoing
      }
    }
    is(sdone){

      sState := sIdle
    }
  }

}
