import chisel3._
import chisel3.util._
import ZirconConfig.Stream._
import ZirconConfig.Cache._
import ZirconConfig.EXEOp._
import ZirconConfig.FifoRole._
import ZirconConfig.Issue._
import ZirconConfig.Decode._
import ZirconConfig.Commit._

class SERFIO extends Bundle {
    val iterCnt = Input(Vec(3,UInt(32.W)))
    val fifoId = Input(Vec(3, UInt(streamBits.W)))
    val rdata1 = Output(UInt(32.W))
    val rdata2 = Output(UInt(32.W))
}

class SEWBIO extends Bundle {
    val wvalid = Input(Bool())
    val useBuffer = Input(Vec(3,Bool()))
    val usePPBuffer = Input(Bool())
    val iterCnt = Input(Vec(3,UInt(32.W)))
    val fifoId = Input(Vec(3, UInt(streamBits.W)))
    val wdata  = Input(UInt(32.W))
}

class SEISSIO extends Bundle {
    val isCalStream = Input(Bool())
    val usePPBuffer = Input(Bool())
    val useBuffer = Input(Vec(3,Bool()))
    val iterCnt = Input(Vec(3,UInt(32.W)))
    val fifoId = Input(Vec(3, UInt(streamBits.W)))
    val words = Input(Vec(3, UInt(4.W)))
    val ready  = Output(Bool())
}

class SEPipelineIO extends Bundle {
    val op      = Input(UInt(stInstBits.W))
    val src1    = Input(UInt(32.W))
    val src2    = Input(UInt(32.W))
    val cfgState = Input(Vec(streamCfgBits,Bool()))
    val valid = Input(Bool())
    val busy  = Output(Bool())
}

class SEMemIO extends Bundle {
    val rreq        = Output(Bool())
    val rrsp        = Input(Bool())
    val rlast       = Input(Bool())
    val raddr       = Output(UInt(32.W))
    val rdata       = Input(UInt(32.W))
    val rlen        = Output(UInt(8.W))
    val rsize       = Output(UInt(2.W))

    val wreq       = Output(Bool())
    val wrsp       = Input(Bool())
    val wlast      = Output(Bool())
    val waddr      = Output(UInt(32.W))
    val wdata      = Output(UInt(32.W))
    val wlen       = Output(UInt(8.W))
    val wsize      = Output(UInt(2.W))
    val wstrb      = Output(UInt(4.W))
}

class SEDCIO extends Bundle {
    val rreq       = Output(Bool())
    val rreqD1       = Output(Bool())
    val mtype      = Output(UInt(3.W))
    val isLatest   = Output(Bool())
    val vaddr      = Output(UInt(32.W))
    val paddrD1      = Output(UInt(32.W))
    val rdata      = Input(UInt(32.W))
    val miss       = Input(Bool()) 
    val rrsp       = Input(Bool())
    val sbFull     = Input(Bool())
    val lsuRfValid     = Input(Bool())
}

class SEL2IO extends Bundle {
    val rreq        = Output(Bool())
    val rrsp        = Input(Bool())
    val rdata       = Input(UInt(32.W))
    val wreq        = Output(Bool())
    val wrsp        = Input(Bool())
    val wdata       = Output(UInt(32.W))
    val paddr       = Output(UInt(32.W))
    val mtype       = Output(UInt(2.W))
    val miss        = Input(Bool())
    val dcHazard   = Input(Bool())
    val l2S1Valid   = Input(Bool())
}

class StreamEngineIO extends Bundle {
    val outerRF = new SEOuterRFIO
    val outerReserve = Input(new StreamOuterEvent)
    val storesDrained = Output(Bool())
    val rf = Vec(4, new SERFIO) // 4 is muldiv
    val wb = Vec(4, new SEWBIO)
    val iss = Vec(16, new SEISSIO) //alu 12  mdu 4
    val rdIter = Flipped(new SERdIterIO)
    val pp  = new SEPipelineIO
    val mem = new SEMemIO
    val dc = new SEDCIO
    val l2 = new SEL2IO
    val cmt = new CommitStreamIO
}

class loadPPBundle extends Bundle {
  val wordCnt = UInt((l2Offset - 2).W)
  val fifoId  = UInt(streamBits.W)
  val segSel  = UInt(log2Ceil(fifoSegNum).W)
  val addr    = UInt(32.W)
  val valid   = Bool()
  val rdata    = UInt(32.W)
  def apply(wordCnt: UInt, fifoId: UInt, segSel: UInt, addr: UInt, valid: Bool, rdata: UInt): loadPPBundle = {
    val bundle = WireDefault(0.U.asTypeOf(new loadPPBundle))
    bundle.wordCnt := wordCnt
    bundle.fifoId  := fifoId
    bundle.segSel  := segSel
    bundle.addr    := addr
    bundle.valid   := valid
    bundle.rdata   := rdata
    bundle
  }
}

class LoadSelect extends Module {
  val io = IO(new Bundle {
    val fifoSegEmpty = Input(Vec(2, Vec(fifoSegNum, Bool())))
    val burstCntMap  = Input(Vec(streamNum, UInt(32.W)))

    val loadValid    = Output(Bool())
    val loadFifoId   = Output(UInt(streamBits.W))
    val loadSegSel   = Output(UInt(log2Ceil(fifoSegNum).W))
  })

  val fifo0Valid = io.fifoSegEmpty(0).asUInt.orR
  val fifo1Valid = io.fifoSegEmpty(1).asUInt.orR

  io.loadValid := fifo0Valid || fifo1Valid

  val pick1 = fifo1Valid && (!fifo0Valid || io.burstCntMap(1) < io.burstCntMap(0))

  io.loadFifoId := Mux(pick1, 1.U, 0.U)

  io.loadSegSel := Mux(
    pick1,
    io.fifoSegEmpty(1).asUInt - 1.U,
    io.fifoSegEmpty(0).asUInt - 1.U
  )
}

class dynPPState extends Bundle {
  val ppCnt = UInt(ppCntWidth.W)
  val ppStride = UInt(ppCntWidth.W)
  val ppLimit = UInt(ppCntWidth.W)
  val ppStage = UInt(stageWidth.W)
}

class dynIState extends Bundle {
  val iCnt  = UInt(32.W)
  val iLimit = UInt(32.W)
  val iRepeat = UInt(32.W)
}

class StreamEngine extends Module {
    val io = IO(new StreamEngineIO)

    val ppDecBase = RegInit(0.U(1.W))

    val archPP = RegInit(VecInit.fill(2)(0.U.asTypeOf(new dynPPState)))
    val specPP = RegInit(VecInit.fill(2)(0.U.asTypeOf(new dynPPState)))
    val ppBaseCfg = RegInit(VecInit.fill(2)(0.U(ppCntWidth.W))) 
    val stageLimitCfg = RegInit(VecInit.fill(2)(5.U(stageWidth.W)))//TODO

    val archI  = RegInit(VecInit.fill(streamNum)(0.U.asTypeOf(new dynIState)))
    val specI  = RegInit(VecInit.fill(streamNum)(0.U.asTypeOf(new dynIState)))
    val archComputeWriteI = RegInit(VecInit.fill(computeStreamNum)(0.U.asTypeOf(new dynIState)))
    val specComputeWriteI = RegInit(VecInit.fill(computeStreamNum)(0.U.asTypeOf(new dynIState)))
    val iLimitCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_limit
    val iRepeatCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_repeat
    val iOffsetCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_offset

    val streamMap = RegInit(VecInit.fill(streamNum)(0.U(iterBits.W))) //fifo_id -> i_id
    val addrCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val addrDyn = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val strideCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val tileStrideCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val useDimAddr = RegInit(VecInit.fill(streamNum)(false.B))
    val dimSizeCfg = RegInit(VecInit.fill(streamNum)(
      VecInit.tabulate(streamAddrDims) { d =>
        if (d == 0) l2LineWord.U(32.W) else 1.U(32.W)
      }
    ))
    val dimStrideCfg = RegInit(VecInit.fill(streamNum)(VecInit.fill(streamAddrDims)(0.U(32.W))))
    val dimCnt = RegInit(VecInit.fill(streamNum)(VecInit.fill(streamAddrDims)(0.U(32.W))))
    val dimDone = RegInit(VecInit.fill(streamNum)(true.B))
    val reuseCfg = RegInit(VecInit.fill(streamNum)(0.U(counterWidth.W)))
    val stateCfg = RegInit(VecInit.fill(streamNum)(VecInit.fill(streamCfgBits)(false.B))) //fifo_id -> [doneCfg,isLoad,...]

    val loadreadyMap = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(0.U(counterWidth.W))))
    val archLoadReadyMap = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(0.U(counterWidth.W))))

    val storereadyMap = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(false.B)))
    val pendingOuterWrites = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(false.B)))
    val Fifo = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(0.U(32.W))))  //fifo_id,itercnt -> data

    // FIFO3 is a load-to-store layout buffer. Its load and store descriptors are
    // snapshotted independently so later store configuration cannot disturb an
    // in-flight AXI fill.
    val layoutLoadActive = RegInit(false.B)
    val layoutLoadDone = RegInit(true.B)
    val layoutLoadBase = RegInit(0.U(32.W))
    val layoutLoadDimSize = RegInit(VecInit.tabulate(streamAddrDims) { d =>
      if (d == 0) l2LineWord.U(32.W) else 1.U(32.W)
    })
    val layoutLoadDimStride = RegInit(VecInit.fill(streamAddrDims)(0.U(32.W)))
    val layoutLoadDimCnt = RegInit(VecInit.fill(streamAddrDims)(0.U(32.W)))

    val layoutStoreActive = RegInit(false.B)
    val layoutStoreBase = RegInit(0.U(32.W))
    val layoutStoreDimSize = RegInit(VecInit.tabulate(streamAddrDims) { d =>
      if (d == 0) l2LineWord.U(32.W) else 1.U(32.W)
    })
    val layoutStoreDimStride = RegInit(VecInit.fill(streamAddrDims)(0.U(32.W)))
    val layoutStoreDimCnt = RegInit(VecInit.fill(streamAddrDims)(0.U(32.W)))
    val layoutStoreReadIdx = RegInit(0.U(log2Ceil(fifoWord).W))
    val layoutStoreLineActive = RegInit(false.B)

    val lengthMap = RegInit(VecInit.fill(streamNum)(0.U(16.W))) //fifo_id -> load length
    val burstCntMap = RegInit(VecInit.fill(streamNum)(0.U(16.W))) //fifo_id -> load cnt
    val outerIterMap = RegInit(VecInit.fill(streamNum)(0.U(16.W))) //fifo_id -> outer Iter
    val oIterCntMap = RegInit(VecInit.fill(streamNum)(0.U(16.W))) //fifo_id -> outer Iter cnt

    val ppBits = io.pp
    val op = ppBits.op
    val src1 = ppBits.src1
    val src2 = ppBits.src2
    val valid = io.pp.valid
    io.pp.busy := false.B

    val isCfgI = op === CFGI && valid
    val isCfgILimit = op === CFGILIMIT && valid
    val isCfgIRepeat = op === CFGIREPEAT && valid
    val isCfgIOffset = op === CFGIOFFSET && valid
    val isCfgStream = (op === CFGLOAD || op=== CFGSTORE) && valid
    val isCfgStride = op === CFGSTRIDE && valid
    val isCfgTileStride = op === CFGTILESTRIDE && valid
    val isCfgDimSize = op === CFGDIMSIZE && valid
    val isCfgDimStride = op === CFGDIMSTRIDE && valid
    val isCfgReuse = op === CFGREUSE && valid
    val isCfgReuseEmpty = op === CFGREUSEEMPTY && valid
    val isCal = op === CALSTREAM && valid
    val isCalRd = op === CALSTREAMRD && valid


    val iId = src1(iterBits-1,0)
    val addr = src1
    val stride = src1
    val tileStride = src1
    val reusecnt = src1
    val cfgLength = src1(31,16)
    val outerIter = src1(15,0)
    val cfgIlimit = src1
    val cfgIrepeat = src1
    val cfgIoffset = src1
    val cfgDimSize = src1
    val cfgDimStride = src1
    val fifoId = VecInit(src1(streamBits*2-1, streamBits),src1(streamBits-1, 0),src2(streamBits-1, 0))//fifo_src_0 fifo_src_1 fifo_dst
    val dimId = src2(streamBits + 1, streamBits)
    val isComputeFifo = fifoId(Dst) === ComputeX.U || fifoId(Dst) === ComputeY.U
    val computeFifoIdx = (fifoId(Dst) - ComputeX.U)(log2Ceil(computeStreamNum)-1, 0)
    val isLayoutLoad = op === CFGLOAD && valid && fifoId(Dst) === Layout.U
    val isLayoutStore = op === CFGSTORE && valid && fifoId(Dst) === Layout.U

    //----------------- 1:CORE -------------------
    //config
    when(isCfgI){
        specI(fifoId(Dst)).iCnt := 0.U
        archI(fifoId(Dst)).iCnt := 0.U
        when(isComputeFifo) {
            specComputeWriteI(computeFifoIdx).iCnt := 0.U
            archComputeWriteI(computeFifoIdx).iCnt := 0.U
        }
        streamMap(fifoId(Dst)) := 0.U
        lengthMap(fifoId(Dst)) := cfgLength / l2LineWord.U
        outerIterMap(fifoId(Dst)) := outerIter
        dimSizeCfg(fifoId(Dst))(1) := Mux(cfgLength / l2LineWord.U === 0.U, 1.U, cfgLength / l2LineWord.U)
        dimSizeCfg(fifoId(Dst))(2) := Mux(outerIter === 0.U, 1.U, outerIter)
    }
    when(isCfgILimit){
        iLimitCfg(fifoId(Dst)) := cfgIlimit
        specI(fifoId(Dst)).iLimit := cfgIlimit
        archI(fifoId(Dst)).iLimit := cfgIlimit
        when(isComputeFifo) {
            specComputeWriteI(computeFifoIdx).iLimit := cfgIlimit
            archComputeWriteI(computeFifoIdx).iLimit := cfgIlimit
        }
    }
    when(isCfgIRepeat){
        iRepeatCfg(fifoId(Dst)) := cfgIrepeat
        specI(fifoId(Dst)).iRepeat := 0.U
        archI(fifoId(Dst)).iRepeat := 0.U
        when(isComputeFifo) {
            specComputeWriteI(computeFifoIdx).iRepeat := 0.U
            archComputeWriteI(computeFifoIdx).iRepeat := 0.U
        }
    }
    when(isCfgIOffset){
        iOffsetCfg(fifoId(Dst)) := cfgIoffset
    }
    when(isCfgStream && !isLayoutStore){
        addrCfg(fifoId(Dst)) := addr
        addrDyn(fifoId(Dst)) := addr
        stateCfg(fifoId(Dst)) := ppBits.cfgState
    }
    when(isLayoutLoad) {
        layoutLoadActive := ppBits.cfgState(LDAXISTREAM)
        layoutLoadDone := false.B
        layoutLoadBase := addr
        layoutLoadDimSize := dimSizeCfg(Layout)
        layoutLoadDimStride := dimStrideCfg(Layout)
        layoutLoadDimCnt := VecInit.fill(streamAddrDims)(0.U(32.W))
        layoutStoreActive := false.B
        layoutStoreReadIdx := 0.U
        layoutStoreLineActive := false.B
        storereadyMap(Layout) := VecInit.fill(fifoWord)(false.B)
    }
    when(isLayoutStore) {
        layoutStoreActive := true.B
        layoutStoreBase := addr
        layoutStoreDimSize := dimSizeCfg(Layout)
        layoutStoreDimStride := dimStrideCfg(Layout)
        layoutStoreDimCnt := VecInit.fill(streamAddrDims)(0.U(32.W))
        layoutStoreLineActive := false.B
    }
    when(op === CFGLOAD && valid) {
        dimCnt(fifoId(Dst)) := VecInit.fill(streamAddrDims)(0.U(32.W))
        dimDone(fifoId(Dst)) := false.B
        burstCntMap(fifoId(Dst)) := 0.U
        oIterCntMap(fifoId(Dst)) := 0.U
    }
    when(isCfgStride){
        strideCfg(fifoId(Dst)) := stride
        dimStrideCfg(fifoId(Dst))(0) := stride
    }
    when(isCfgTileStride){
        tileStrideCfg(fifoId(Dst)) := tileStride
        dimStrideCfg(fifoId(Dst))(1) := tileStride
    }
    when(isCfgDimSize && dimId =/= 0.U){
        dimSizeCfg(fifoId(Dst))(dimId) := Mux(cfgDimSize === 0.U, 1.U, cfgDimSize)
        useDimAddr(fifoId(Dst)) := true.B
    }
    when(isCfgDimStride){
        dimStrideCfg(fifoId(Dst))(dimId) := cfgDimStride
        useDimAddr(fifoId(Dst)) := true.B
    }
    when(isCfgReuse){
        reuseCfg(fifoId(Dst)) := reusecnt
    }
    //TODO:这里默认对1号流进行处理
    val hasClearedLoadReady = RegInit(true.B)
    when(isCfgReuseEmpty) {
        hasClearedLoadReady := false.B
    }
    //repeat次数与复用次数相等，说明kernel刚好错开第一个lane，但此时第一个lane前面的word的复用次数并未消耗完，需要清空
    when(specI(1).iRepeat === reuseCfg(1) && !hasClearedLoadReady) {
        for (i <- 0 until fifoWord) { // 只有第一个lane会受影响，实际上这里应该用 fifoword/2
            when(i.U < reuseCfg(1)) { // 对8点 16点 kernel 的支持
                loadreadyMap(1)(i) := 0.U
                archLoadReadyMap(1)(i) := 0.U //TODO：need think more carefully
            }
        }
        hasClearedLoadReady := true.B
    }

    def lineBaseAddr(fifo: UInt): UInt = {
      var result = addrCfg(fifo)
      for (d <- 1 until streamAddrDims) {
        result = (result + (dimCnt(fifo)(d) * dimStrideCfg(fifo)(d))(31, 0))(31, 0)
      }
      result
    }

    def advanceLine(fifo: UInt): Unit = {
      val nextCnt = Wire(Vec(streamAddrDims, UInt(32.W)))
      nextCnt := dimCnt(fifo)
      var carry = true.B
      for (d <- 1 until streamAddrDims) {
        val last = dimCnt(fifo)(d) + 1.U >= dimSizeCfg(fifo)(d)
        when(carry) {
          nextCnt(d) := Mux(last, 0.U, dimCnt(fifo)(d) + 1.U)
        }
        carry = carry && last
      }
      dimCnt(fifo) := nextCnt
      dimDone(fifo) := carry
    }

    def layoutLoadLineBaseAddr(): UInt = {
      var result = layoutLoadBase
      for (d <- 1 until streamAddrDims) {
        result = (result + (layoutLoadDimCnt(d) * layoutLoadDimStride(d))(31, 0))(31, 0)
      }
      result
    }

    def advanceLayoutLoadLine(): Unit = {
      val nextCnt = Wire(Vec(streamAddrDims, UInt(32.W)))
      nextCnt := layoutLoadDimCnt
      var carry = true.B
      for (d <- 1 until streamAddrDims) {
        val last = layoutLoadDimCnt(d) + 1.U >= layoutLoadDimSize(d)
        when(carry) {
          nextCnt(d) := Mux(last, 0.U, layoutLoadDimCnt(d) + 1.U)
        }
        carry = carry && last
      }
      layoutLoadDimCnt := nextCnt
      layoutLoadDone := carry
    }

    def layoutStoreAddr(): UInt = {
      var result = layoutStoreBase
      for (d <- 0 until streamAddrDims) {
        result = (result + (layoutStoreDimCnt(d) * layoutStoreDimStride(d))(31, 0))(31, 0)
      }
      result
    }

    def advanceLayoutStoreWord(): Unit = {
      val nextCnt = Wire(Vec(streamAddrDims, UInt(32.W)))
      nextCnt := layoutStoreDimCnt
      var carry = true.B
      for (d <- 0 until streamAddrDims) {
        val last = layoutStoreDimCnt(d) + 1.U >= layoutStoreDimSize(d)
        when(carry) {
          nextCnt(d) := Mux(last, 0.U, layoutStoreDimCnt(d) + 1.U)
        }
        carry = carry && last
      }
      layoutStoreDimCnt := nextCnt
    }

    //----------------- UPDATE I STATE -------------------
    def updateIState(
      state: dynIState,
      fireVec: Vec[Bool],
      stepVec: Vec[UInt],
      iLimitCfg: UInt,
      iRepeatCfg: UInt,
      iOffsetCfg: UInt,
    ): (dynIState, Vec[UInt]) = {

      val width = fireVec.length
      val iterCnt = Wire(Vec(width, UInt(32.W)))
      // Fold instructions in program order. The second slot must observe the
      // first slot's repeat/limit transition, including an 8-word vector step.
      var cursor = state
      for (i <- 0 until width) {
        iterCnt(i) := cursor.iCnt
        cursor = StreamIndex.advance(cursor, fireVec(i), stepVec(i), iLimitCfg, iRepeatCfg, iOffsetCfg)
      }
      (cursor, iterCnt)
    }

    for (b <- 0 until streamNum) {
      // DISPATCH
      val (nextState, iterCnt) = updateIState(
        state        = specI(b),
        fireVec      = io.rdIter.fireStreamOp(b),
        stepVec      = io.rdIter.stepStreamOp(b),
        iLimitCfg    = iLimitCfg(b),
        iRepeatCfg   = iRepeatCfg(b),
        iOffsetCfg   = iOffsetCfg(b)
      )
      when(!isCfgI && !isCfgILimit && !isCfgIRepeat && !isCfgIOffset ){ // 配置指令不更新状态，其他指令才更新 TODO:confuse
        specI(b) := nextState
        if(b == 0){
          when(io.rdIter.fireStreamOp(b).asUInt.orR){
            printf(p"\n\n---------------------- SRC --------------------------\n")
            printf(p"specI$b: cnt=${specI(b).iCnt}, firenum=${PopCount(io.rdIter.fireStreamOp(b))},nextSpecI$b: cnt=${nextState.iCnt} \n")
        }
        }
      }
      io.rdIter.iterCnt(b) := iterCnt
      for (i <- 0 until ndcd) {
        when(io.rdIter.fireStreamOp(b)(i)) {
          if(b == 0){
            when(io.rdIter.fireStreamOpPP(Even)(i)){
              printf(p"inst $i is LLL, src iter=${iterCnt(i)}\n");
            }.otherwise{
              printf(p"inst $i is LLR, src iter=${iterCnt(i)}\n");
            }
          }
        }
      }
      // COMMIT
      val (nextArchState, iterCntArch) = updateIState(
        state        = archI(b),
        fireVec      = io.cmt.fireStreamOp(b),
        stepVec      = io.cmt.stepStreamOp(b),
        iLimitCfg    = iLimitCfg(b),
        iRepeatCfg   = iRepeatCfg(b),
        iOffsetCfg   = iOffsetCfg(b)
      )
      when(!isCfgI && !isCfgILimit && !isCfgIRepeat && !isCfgIOffset ){ // 配置指令不更新状态，其他指令才更新
        archI(b) := nextArchState
      }
    }

    for (b <- 0 until computeStreamNum) {
      val fifo = ComputeX + b
      val (nextState, iterCnt) = updateIState(
        state        = specComputeWriteI(b),
        fireVec      = io.rdIter.fireComputeWrite(b),
        stepVec      = io.rdIter.stepComputeWrite(b),
        iLimitCfg    = iLimitCfg(fifo),
        iRepeatCfg   = iRepeatCfg(fifo),
        iOffsetCfg   = iOffsetCfg(fifo)
      )
      when(!isCfgI && !isCfgILimit && !isCfgIRepeat && !isCfgIOffset) {
        specComputeWriteI(b) := nextState
      }
      io.rdIter.computeWriteIterCnt(b) := iterCnt

      val (nextArchState, iterCntArch) = updateIState(
        state        = archComputeWriteI(b),
        fireVec      = io.cmt.fireComputeWrite(b),
        stepVec      = io.cmt.stepComputeWrite(b),
        iLimitCfg    = iLimitCfg(fifo),
        iRepeatCfg   = iRepeatCfg(fifo),
        iOffsetCfg   = iOffsetCfg(fifo)
      )
      when(!isCfgI && !isCfgILimit && !isCfgIRepeat && !isCfgIOffset) {
        archComputeWriteI(b) := nextArchState
      }
    }

    // Fold committed intermediate writes and stream reads in ROB order. A
    // producer and its consumer may retire together, so independent register
    // assignments would otherwise lose one of the ready-count updates.
    for (b <- 0 until streamNum) {
      for (word <- 0 until fifoWord) {
        var nextReady = archLoadReadyMap(b)(word)
        var touched = false.B
        for (i <- 0 until ncommit) {
          val readIdx = (io.cmt.streamReadIterCnt(b)(i) % fifoWord.U)(log2Ceil(fifoWord)-1, 0)
          val readHit = io.cmt.fireStreamRead(b)(i) &&
            (word.U - readIdx)(log2Ceil(fifoWord)-1, 0) < io.cmt.readWords(b)(i)
          val writeHit = if (b >= ComputeX && b <= ComputeY) {
            val computeIdx = b - ComputeX
            val writeIdx = (io.cmt.computeWriteIterCnt(computeIdx)(i) % fifoWord.U)(log2Ceil(fifoWord)-1, 0)
            io.cmt.fireComputeWrite(computeIdx)(i) &&
              (word.U - writeIdx)(log2Ceil(fifoWord)-1, 0) < io.cmt.stepComputeWrite(computeIdx)(i)
          } else {
            false.B
          }
          nextReady = Mux(writeHit, reuseCfg(b), nextReady)
          nextReady = Mux(readHit, nextReady - 1.U, nextReady)
          touched = touched || writeHit || readHit
        }
        when(touched) {
          archLoadReadyMap(b)(word) := nextReady
        }
      }
    }


    //----------------- UPDATE PP STATE -------------------
    def nextIndex(
      sum: UInt,
      limit: UInt,
      stride: UInt,
      stage: UInt,
      stageLimit: UInt,
      base: UInt,
      odd: Bool
    ): UInt = {
        val Even = 
        Mux(sum < limit, sum, 
          Mux(stride + limit < (64.U * (stage+1.U)), 
          sum + stride,
          Mux(stage + 1.U < stageLimit- 1.U,  sum + stride, base + sum - limit  
          ))
        )
        val Odd =       
        Mux(sum < limit, sum,                          // inner
          Mux(stride + limit < (64.U * (stage+1.U)),        // stage内跳stride
            sum + stride,
            Mux(stage + 1.U < stageLimit- 1.U,              // stage++
              sum + (stride << 1),
              base + sum - limit                       // block++
            )
          )
        )
        Mux(odd,Odd,Even)
    }

    def updatePPState(
      state: dynPPState,
      fireVec: Vec[Bool],
      stageLimit: UInt,
      base: UInt,
      isOdd: Bool
    ): (dynPPState, Vec[UInt], Bool) = {

      val width = fireVec.length
      val nextState = Wire(new dynPPState)
      nextState := state
      val iterCnt = Wire(Vec(width, UInt(ppCntWidth.W)))
      val fireNum = PopCount(fireVec)
      val sumBase = state.ppCnt
      val tag = WireInit(false.B)
      // 1. 状态更新
      when(fireNum =/= 0.U) {
        val sum = sumBase + fireNum
        nextState.ppCnt := nextIndex(
          sum,
          state.ppLimit,
          state.ppStride,
          state.ppStage,
          stageLimit,
          base,
          isOdd
        )
        when(sum >= state.ppLimit) {
          when(state.ppStride + state.ppLimit < (64.U * (state.ppStage+1.U))) {
            nextState.ppLimit := state.ppLimit + (state.ppStride << 1)
          }
          .elsewhen(state.ppStage + 1.U < stageLimit - 1.U) { //-1.U表示实际上到stage4就重置
            nextState.ppStride := state.ppStride << 1
            nextState.ppLimit := Mux(
              isOdd,
              state.ppLimit + (state.ppStride << 2),
              state.ppLimit + state.ppStride + (state.ppStride << 1)
            )
            nextState.ppStage := state.ppStage + 1.U
          }
          .otherwise {
            nextState.ppStride := 2.U
            nextState.ppLimit  := base + 2.U
            nextState.ppStage  := 0.U
            tag := true.B
          }
        }
      }
      // 2. iterCnt 展开（组合）
      for (i <- 0 until width) {
        val sum = sumBase + i.U
        iterCnt(i) := nextIndex(
          sum,
          state.ppLimit,
          state.ppStride,
          state.ppStage,
          stageLimit,
          base,
          isOdd
        )
      }
      (nextState, iterCnt, tag)
    }

    //TODO:CFG
    val initDone = RegInit(false.B)
    when(!initDone) {
      ppBaseCfg(0) := 0.U
      ppBaseCfg(1) := 2.U
      stageLimitCfg(0) := 5.U
      stageLimitCfg(1) := 5.U
      for (b <- 0 until 2) {
        val initState = Wire(new dynPPState)
        initState.ppCnt    := (2 * b).U
        initState.ppStride := 2.U
        initState.ppLimit  := (2 * b + 2).U
        initState.ppStage  := 0.U
        specPP(b) := initState
        archPP(b) := initState
      }
      initDone := true.B
    }

    def getPP( ppRaw: UInt , revSeg: UInt): (UInt,UInt) = {
        ( Cat(0.U((streamBits - 1).W), ppRaw(5)), Mux(revSeg === 0.U, Cat(~ppRaw(6), ppRaw(4,0)), Cat(ppRaw(6), ppRaw(4,0))) )
    }

    // b = 0，流流流；b = 1，寄寄流
    val tagR = RegInit(false.B)
    val cntInst = RegInit(0.U(32.W))//TODO: CFG to 256
    for (b <- 0 until 2) {
      val (nextState, iterCnt, omitTag) = updatePPState(
        state       = specPP(b),
        fireVec     = io.rdIter.fireStreamOpPP(b),
        stageLimit  = stageLimitCfg(b),
        base        = ppBaseCfg(b),
        isOdd       = (b.U === Odd.U)
      )
      when(initDone){
        specPP(b) := nextState
        when(io.rdIter.fireStreamOpPP(b).asUInt.orR){
            printf(p"---------------------- DST --------------------------\n")
            printf(p"specPP$b: cnt=${specPP(b).ppCnt}, stride=${specPP(b).ppStride}, limit=${specPP(b).ppLimit}, stage=${specPP(b).ppStage}\n")
            printf(p"firenum=${PopCount(io.rdIter.fireStreamOpPP(b))}, nextSpecPP$b: cnt=${nextState.ppCnt}, stride=${nextState.ppStride}, limit=${nextState.ppLimit}, stage=${nextState.ppStage}\n")
        }
      }
      io.rdIter.iterCntPP(b) := iterCnt
      for (i <- 0 until ndcd) {
        when(io.rdIter.fireStreamOpPP(b)(i)) {
          if (b == 0) {
            printf(p"inst $i is LLL, wb iter=${io.rdIter.iterCntPP(b)(i)}\n")
          }
          else if (b == 1) {
            printf(p"inst $i is RRL, wb iter=${io.rdIter.iterCntPP(b)(i)}\n")
          }
        }
      }
      // COMMIT
      val (nextArchState, iterCntArch, archTag) = updatePPState(
        state       = archPP(b),
        fireVec     = io.cmt.fireStreamOpPP(b),
        stageLimit  = stageLimitCfg(b),
        base        = ppBaseCfg(b),
        isOdd       = (b.U === Odd.U)
      )
      if(b == 0){
        tagR := archTag//TODO
      }
      when(initDone){
        archPP(b) := nextArchState
      }
    }

    // COMMIT
    for (i <- 0 until ncommit) {
      when(io.cmt.fireStreamOpPP(0)(i) || io.cmt.fireStreamOpPP(1)(i)){
        assert(!io.cmt.fireStreamOpPP(0)(i) || !io.cmt.fireStreamOpPP(1)(i))
        val (ppId, ppIdx) = getPP(io.cmt.iterCnt(2)(i), ppDecBase)
        archLoadReadyMap(ppId)(ppIdx) := reuseCfg(ppId)
      }
    }
    val inc = PopCount((0 until ncommit).map(i => 
      io.cmt.fireStreamOpPP(0)(i) || io.cmt.fireStreamOpPP(1)(i)
    ))
    cntInst := cntInst + inc
    when(cntInst === 256.U){
      cntInst := 0.U
      ppDecBase := ~ppDecBase
    }

    // Flush
    when(io.cmt.flush){
      specI := archI
      specComputeWriteI := archComputeWriteI
      specPP := archPP
      loadreadyMap := archLoadReadyMap
      printf(p"\n\n---------------------- FLUSH --------------------------\n")
      printf(p"flush! specI0 cnt=${specI(0).iCnt}, specI1 cnt=${specI(1).iCnt}, archI0 cnt=${archI(0).iCnt},archI1 cnt=${archI(1).iCnt}\n")
      printf(p"flush! specPP0 cnt=${specPP(0).ppCnt}, stage=${specPP(0).ppStage}, specPP1 cnt=${specPP(1).ppCnt}, stage=${specPP(1).ppStage}, archPP0 cnt=${archPP(0).ppCnt}, stage=${archPP(0).ppStage}, archPP1 cnt=${archPP(1).ppCnt}, stage=${archPP(1).ppStage}\n")
    }


    // Issue stage
    for (i <- 0 until 16) {
        val issWordIdx = VecInit.fill(3)(0.U(log2Ceil(fifoWord).W))
        for (b <- 0 until 3) {
            issWordIdx(b) := (io.iss(i).iterCnt(b) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        }
        val src0Id = io.iss(i).fifoId(0)
        val src1Id = io.iss(i).fifoId(1)
        val dstId = io.iss(i).fifoId(2)
        val (ppId, ppIdx) = getPP(io.iss(i).iterCnt(2), ppDecBase)
        val ppRdy = !io.iss(i).usePPBuffer || loadreadyMap(ppId)(ppIdx) === 0.U
        val windowReady = (0 until 3).map { operand =>
          (0 until (if (i < arithNiq) 1 else 8)).map { lane =>
            val fifo = io.iss(i).fifoId(operand)
            val idx = (issWordIdx(operand) + lane.U)(log2Ceil(fifoWord)-1, 0)
            val ready = if (operand < 2) loadreadyMap(fifo)(idx) =/= 0.U
              else !pendingOuterWrites(fifo)(idx) && Mux(fifo === Dst.U,
                !storereadyMap(Dst)(idx), loadreadyMap(fifo)(idx) === 0.U && archLoadReadyMap(fifo)(idx) === 0.U)
            lane.U >= io.iss(i).words(operand) || ready
          }.reduce(_ && _)
        }
        io.iss(i).ready :=  io.iss(i).isCalStream &
                          (windowReady(0) || !io.iss(i).useBuffer(0)) &
                          (windowReady(1) || !io.iss(i).useBuffer(1)) &
                          (windowReady(2) || !io.iss(i).useBuffer(2)) & ppRdy
    }

    // ReadOp stage + writeback stage
    for(i <- 0 until 4){
        // readop stage
        val rfWordIdx0 = (io.rf(i).iterCnt(0) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        val rfWordIdx1 = (io.rf(i).iterCnt(1) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        io.rf(i).rdata1 := Fifo(io.rf(i).fifoId(0))(rfWordIdx0)
        io.rf(i).rdata2 := Fifo(io.rf(i).fifoId(1))(rfWordIdx1)
        // writeback stage
        // A flushed instruction must not consume or produce stream data after
        // the speculative maps have been restored from committed state.
        when(io.wb(i).wvalid && !io.cmt.flush){
            val src0Id = io.wb(i).fifoId(0)
            val src1Id = io.wb(i).fifoId(1)
            val wbSrcIdx0 = (io.wb(i).iterCnt(0) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
            val wbSrcIdx1 = (io.wb(i).iterCnt(1) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
            when(io.wb(i).useBuffer(2)){
                val dstId = io.wb(i).fifoId(2)
                val wbWordIdx = (io.wb(i).iterCnt(2) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
                Fifo(dstId)(wbWordIdx) := io.wb(i).wdata
                when(dstId === Dst.U) {
                    storereadyMap(Dst)(wbWordIdx) := true.B
                }.otherwise {
                    assert(loadreadyMap(dstId)(wbWordIdx) === 0.U)
                    loadreadyMap(dstId)(wbWordIdx) := reuseCfg(dstId)
                }
            }
            when(io.wb(i).usePPBuffer){
                val (ppId, ppIdx) = getPP(io.wb(i).iterCnt(2), ppDecBase)
                Fifo(ppId)(ppIdx) := io.wb(i).wdata
                assert(loadreadyMap(ppId)(ppIdx) === 0.U,p"itercnt = ${io.wb(i).iterCnt(2)}, buffer ${ppId}[${ppIdx}] should be empty")
                loadreadyMap(ppId)(ppIdx) := reuseCfg(ppId)
            }
        }
    }

    for (lane <- 0 until 8) {
      val idx = (io.outerRF.index + lane.U)(log2Ceil(fifoWord)-1, 0)
      io.outerRF.data(lane) := Fifo(io.outerRF.fifo)(idx)
    }

    // Reserve source credits at vector issue, before another instruction can
    // reuse them. Memory refill also waits for architectural readers below.
    for (b <- 0 until streamNum; word <- 0 until fifoWord) {
      val scalarReads = io.wb.map { wb =>
        val readA = wb.useBuffer(0) && wb.fifoId(0) === b.U &&
          wb.iterCnt(0)(log2Ceil(fifoWord)-1, 0) === word.U
        val readB = wb.useBuffer(1) && wb.fifoId(1) === b.U &&
          wb.iterCnt(1)(log2Ceil(fifoWord)-1, 0) === word.U
        wb.wvalid && (readA || readB)
      }
      val reserve = io.outerReserve
      val start = Mux(reserve.info.fifoId(0) === b.U, reserve.index(0), reserve.index(1))
      val vectorRead = reserve.valid && reserve.info.op === VOMAC8 &&
        (word.U - start)(log2Ceil(fifoWord)-1, 0) < StreamOuter.readWords(reserve.info, b.U)
      val count = PopCount(scalarReads) +& vectorRead
      when(count =/= 0.U && !io.cmt.flush) {
        assert(loadreadyMap(b)(word) >= count, "stream source credit underflow")
        loadreadyMap(b)(word) := loadreadyMap(b)(word) - count
      }
      when(reserve.valid && reserve.info.op === VREGOUT8 && reserve.info.fifoId(2) === b.U &&
        (word.U - reserve.index(2))(log2Ceil(fifoWord)-1, 0) < 8.U) {
        pendingOuterWrites(b)(word) := true.B
      }
      when(io.cmt.flush) { pendingOuterWrites(b)(word) := false.B }
    }

    // Only retired exports become visible to memory or to X/Y consumers.
    for (event <- io.cmt.outer) {
      when(event.valid && event.info.op === VREGOUT8) {
        val dst = event.info.fifoId(2)
        for (lane <- 0 until 8) {
          val idx = (event.index(2) + lane.U)(log2Ceil(fifoWord)-1, 0)
          Fifo(dst)(idx) := event.data(lane)
          pendingOuterWrites(dst)(idx) := false.B
          when(dst === Dst.U) {
            storereadyMap(Dst)(idx) := true.B
          }.otherwise {
            loadreadyMap(dst)(idx) := reuseCfg(dst)
          }
        }
      }
    }

    //----------------- 2:MEMORY -------------------
    val axiLen = (l2LineBits / 32 - 1).U
    val axiSize = 2.U

    //----------------- 2.1:READ -------------------
    when(initDone && tagR){
      for (i <- 0 until 2){
        oIterCntMap(i) := oIterCntMap(i) - 1.U // TODO 0 < 1
        addrDyn(i) := addrCfg(i) + lengthMap(i) * tileStrideCfg(i) // TODO 只适用于奇数TODO
        when(!stageLimitCfg(i)(0)){ //偶数次stage，每个新block都是seg_0取数
            burstCntMap(i) := burstCntMap(i) - 1.U
        }.otherwise{ //奇数次stage，每个新block会切换seg取数
            lengthMap(i) := lengthMap(i) + 1.U
        }
      }
      tagR := false.B
    }
    
    val fifoSegEmptyBase = VecInit.tabulate(2){ j =>
      VecInit.tabulate(fifoSegNum){ k =>
        loadreadyMap(j)
          .slice(k*l2LineWord, (k+1)*l2LineWord)
          .zip(archLoadReadyMap(j).slice(k*l2LineWord, (k+1)*l2LineWord))
          .map { case (spec, arch) => spec === 0.U && arch === 0.U }
          .reduce(_ && _) &&
        stateCfg(j)(DONECFG) &&
        stateCfg(j)(LDSTRAEM) &&
        (burstCntMap(j)(0) === k.U) &&
        Mux(useDimAddr(j), !dimDone(j), oIterCntMap(j) =/= outerIterMap(j))
      }
    }

    val layoutSegEmpty = VecInit.tabulate(fifoSegNum) { k =>
      storereadyMap(Layout)
        .slice(k*l2LineWord, (k+1)*l2LineWord)
        .map(bit => !bit)
        .reduce(_ && _) &&
      layoutLoadActive &&
      !layoutLoadDone &&
      (burstCntMap(Layout)(0) === k.U)
    }
    val layoutLoadValid = layoutSegEmpty.asUInt.orR
    val layoutLoadSegSel = PriorityEncoder(layoutSegEmpty)

    // ---- AXI ----
    val fifoSegEmptyAXI = fifoSegEmptyBase.zipWithIndex.map { case (seg, j) =>
        Mux(stateCfg(j)(LDAXISTREAM), seg, VecInit.fill(fifoSegNum)(false.B))}
    val loadSelAXI = Module(new LoadSelect())
    loadSelAXI.io.fifoSegEmpty := fifoSegEmptyAXI
    loadSelAXI.io.burstCntMap  := burstCntMap
    val loadWordCntAXI     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val legacyLoadValidAXI  = loadSelAXI.io.loadValid
    val chooseLayoutAXI = layoutLoadValid &&
      (!legacyLoadValidAXI || burstCntMap(Layout) < burstCntMap(loadSelAXI.io.loadFifoId))
    val loadValidAXI  = legacyLoadValidAXI || layoutLoadValid
    val loadFifoIdAXI = Mux(chooseLayoutAXI, Layout.U, loadSelAXI.io.loadFifoId)
    val loadSegSelAXI = Mux(chooseLayoutAXI, layoutLoadSegSel, loadSelAXI.io.loadSegSel)
    val loadValidAXIReg      = RegInit(false.B)
    val loadFifoIdAXIReg     = RegInit(0.U(streamBits.W))
    val loadSegSelAXIReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))

    when(io.mem.rreq && io.mem.rrsp){
        loadWordCntAXI := loadWordCntAXI + 1.U
    }
    when(!loadValidAXIReg){
        loadValidAXIReg := loadValidAXI
        loadFifoIdAXIReg := loadFifoIdAXI
        loadSegSelAXIReg := loadSegSelAXI
    }.elsewhen(io.mem.rreq && io.mem.rrsp && io.mem.rlast){
        loadValidAXIReg := false.B
        when(loadFifoIdAXIReg === Layout.U) {
            advanceLayoutLoadLine()
            burstCntMap(Layout) := burstCntMap(Layout) + 1.U
        }.elsewhen(useDimAddr(loadFifoIdAXIReg)) {
            advanceLine(loadFifoIdAXIReg)
            burstCntMap(loadFifoIdAXIReg) := burstCntMap(loadFifoIdAXIReg) + 1.U
        }.otherwise {
            val isWrap = (burstCntMap(loadFifoIdAXIReg) + 1.U) % lengthMap(loadFifoIdAXIReg) === 0.U
            when(isWrap)
            {
                oIterCntMap(loadFifoIdAXIReg) :=oIterCntMap(loadFifoIdAXIReg) + 1.U
            }
            addrDyn(loadFifoIdAXIReg)     := Mux(isWrap, addrCfg(loadFifoIdAXIReg), addrDyn(loadFifoIdAXIReg) + tileStrideCfg(loadFifoIdAXIReg))
            burstCntMap(loadFifoIdAXIReg)  := burstCntMap(loadFifoIdAXIReg) + 1.U
        }
    }
    io.mem.rreq      := loadValidAXIReg
    io.mem.raddr     := Mux(
      loadFifoIdAXIReg === Layout.U,
      layoutLoadLineBaseAddr(),
      Mux(useDimAddr(loadFifoIdAXIReg), lineBaseAddr(loadFifoIdAXIReg), addrDyn(loadFifoIdAXIReg))
    )
    io.mem.rlen      := axiLen
    io.mem.rsize     := axiSize

    // ---- CACHE ----
    io.dc.rreq       := false.B
    io.dc.rreqD1    := false.B
    io.dc.mtype      := 0.U
    io.dc.isLatest   := false.B
    io.dc.vaddr      := 0.U
    io.dc.paddrD1    := 0.U

    val fifoSegEmpty = fifoSegEmptyBase.zipWithIndex.map { case (seg, j) =>
        Mux(!stateCfg(j)(LDAXISTREAM), seg, VecInit.fill(fifoSegNum)(false.B))}
    val loadSel = Module(new LoadSelect())
    loadSel.io.fifoSegEmpty := fifoSegEmpty
    loadSel.io.burstCntMap  := burstCntMap
    val loadWordCnt     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val loadValid =  loadSel.io.loadValid
    val loadFifoId = loadSel.io.loadFifoId
    val loadSegSel = loadSel.io.loadSegSel
    val loadValidReg      = RegInit(false.B)
    val loadFifoIdReg     = RegInit(0.U(streamBits.W))
    val loadSegSelReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val loadAddr = Mux(
      useDimAddr(loadFifoIdReg),
      lineBaseAddr(loadFifoIdReg) + loadWordCnt * dimStrideCfg(loadFifoIdReg)(0),
      addrDyn(loadFifoIdReg) + loadWordCnt * strideCfg(loadFifoIdReg)
    )
    val loadLastOne = loadWordCnt === (l2LineWord - 1).U 
    val l2AllowSEReq = !io.l2.miss && !io.l2.l2S1Valid && !io.l2.dcHazard
    val loadDone = loadLastOne && (io.l2.rreq && l2AllowSEReq)
    
    when(io.l2.rreq && l2AllowSEReq){
        loadWordCnt := loadWordCnt + 1.U
        when(loadLastOne){
            loadWordCnt := 0.U
        }
    }

    when(loadDone){
        loadValidReg := false.B
    }.elsewhen(!loadValidReg){
        loadValidReg := loadValid
    }

    when(loadValid && loadWordCnt === 0.U){ 
        loadSegSelReg := loadSegSel
        loadFifoIdReg := loadFifoId
    }

    when(loadDone){
        when(useDimAddr(loadFifoIdReg)) {
            advanceLine(loadFifoIdReg)
            burstCntMap(loadFifoIdReg) := burstCntMap(loadFifoIdReg) + 1.U
        }.otherwise {
            val isWrap = (burstCntMap(loadFifoIdReg) + 1.U) % lengthMap(loadFifoIdReg) === 0.U
            when(isWrap)
            {
                oIterCntMap(loadFifoIdReg) :=oIterCntMap(loadFifoIdReg) + 1.U
            }
            addrDyn(loadFifoIdReg)     := Mux(isWrap, addrCfg(loadFifoIdReg), addrDyn(loadFifoIdReg) + tileStrideCfg(loadFifoIdReg))
            burstCntMap(loadFifoIdReg)  := burstCntMap(loadFifoIdReg) + 1.U
        }
    }
    val layoutStoreSegFull = VecInit.tabulate(fifoSegNum) { k =>
      storereadyMap(Layout)
        .slice(k*l2LineWord, (k+1)*l2LineWord)
        .reduce(_ && _)
    }
    val layoutStoreSegIdx = layoutStoreReadIdx(log2Ceil(fifoWord)-1, log2Ceil(l2LineWord))
    val layoutStoreLineReady = layoutStoreSegFull(layoutStoreSegIdx)
    val layoutStoreReq = layoutStoreActive &&
      (layoutStoreLineActive || layoutStoreLineReady)
    val layoutStoreFire = layoutStoreReq && l2AllowSEReq
    val layoutWritesPending = RegInit(0.U(8.W))
    layoutWritesPending := layoutWritesPending + layoutStoreFire - io.l2.wrsp

    // Match the DCache-to-L2 write pipeline: once L2 accepts S1, it owns the
    // word, so FIFO3 can advance without waiting for the later write response.
    io.l2.rreq      := loadValidReg && !layoutStoreReq
    io.l2.wreq      := layoutStoreReq
    io.l2.wdata     := Fifo(Layout)(layoutStoreReadIdx)
    io.l2.mtype     := 2.U // 32-bit word write
    io.l2.paddr     := Mux(layoutStoreReq, layoutStoreAddr(), loadAddr)
    when(layoutStoreFire) {
        storereadyMap(Layout)(layoutStoreReadIdx) := false.B
        layoutStoreReadIdx := layoutStoreReadIdx + 1.U
        layoutStoreLineActive :=
          layoutStoreReadIdx(log2Ceil(l2LineWord)-1, 0) =/= (l2LineWord - 1).U
        advanceLayoutStoreWord()
    }
    // DCache Stage 1
    val loadD1 = WireDefault(ShiftRegister(
        Mux(io.l2.rreq && !io.l2.l2S1Valid && !io.l2.dcHazard,
        (new loadPPBundle)(loadWordCnt, loadFifoIdReg, loadSegSelReg, loadAddr, io.l2.rreq, 0.U(32.W)),
        0.U.asTypeOf(new loadPPBundle)),
        1, 
        0.U.asTypeOf(new loadPPBundle), 
        !io.l2.miss 
    ))
    
    // DCache Stage 2
    val loadD2       = ShiftRegister(loadD1, 1, 0.U.asTypeOf(new loadPPBundle), !io.l2.miss )
      
    // Write Back Stage todo:改成空泡类型
    val loadWB = WireDefault(ShiftRegister(
        Mux( !io.l2.miss, loadD2, 0.U.asTypeOf(new loadPPBundle)),
        1, 0.U.asTypeOf(new loadPPBundle), true.B))
    val l2RdataWB = RegNext(io.l2.rdata, 0.U)
    loadWB.rdata := l2RdataWB

    // refill FIFO
    val wFifoAXIIdx  = (loadSegSelAXIReg * l2LineWord.U + loadWordCntAXI)(log2Ceil(fifoWord)-1,0)
    when(io.mem.rreq && io.mem.rrsp ) {
        Fifo(loadFifoIdAXIReg)(wFifoAXIIdx) := io.mem.rdata
        when(loadFifoIdAXIReg === Layout.U) {
            storereadyMap(Layout)(wFifoAXIIdx) := true.B
        }.otherwise {
            loadreadyMap(loadFifoIdAXIReg)(wFifoAXIIdx) := reuseCfg(loadFifoIdAXIReg)
            archLoadReadyMap(loadFifoIdAXIReg)(wFifoAXIIdx) := reuseCfg(loadFifoIdAXIReg)
        }
        printf(p"FIFO $loadFifoIdAXIReg [$wFifoAXIIdx]=${io.mem.rdata} \n")
    }

    val wFifoIdx  = (loadWB.segSel * l2LineWord.U + loadWB.wordCnt)(log2Ceil(fifoWord)-1,0) 
    when(loadWB.valid) {
        Fifo(loadWB.fifoId)(wFifoIdx) := loadWB.rdata
        loadreadyMap(loadWB.fifoId)(wFifoIdx) := reuseCfg(loadWB.fifoId)
        archLoadReadyMap(loadWB.fifoId)(wFifoIdx) := reuseCfg(loadWB.fifoId)
        printf(p"FIFO ${loadWB.fifoId}[$wFifoIdx]=${loadWB.rdata}(Mem[${loadWB.addr}])  \n")
    }

    //----------------- 2.2:WRITE -------------------
    // FIFO2 remains the legacy AXI store. FIFO3 is drained independently by
    // layoutStoreReq through L2, so the two paths need no store arbitration.
    val storeFifoId = Dst

    val wFifoSegFull = VecInit.tabulate(fifoSegNum){ k=> storereadyMap(Dst).slice(k*l2LineWord, (k+1)*l2LineWord).reduce(_ && _) }
    val storeSegSel = PriorityEncoder(wFifoSegFull)
    val storeValid = stateCfg(storeFifoId)(DONECFG) && !stateCfg(storeFifoId)(LDSTRAEM) && wFifoSegFull.asUInt.orR
    
    val storeWordCnt     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val storeValidReg      = RegInit(false.B)
    val storeSegSelReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val storeFifoIdx  = (storeSegSelReg * l2LineWord.U + storeWordCnt)(log2Ceil(fifoWord)-1,0) 
    when (io.mem.wreq && io.mem.wrsp){
        storeWordCnt := storeWordCnt + 1.U
        storereadyMap(Dst)(storeFifoIdx):=false.B
        //printf(p"STORE FIFO | id = $storeFifoId | idx = $storeFifoIdx | value = ${io.mem.wdata.get}\n")
    }
    when(!storeValidReg){
        storeValidReg  := storeValid
        storeSegSelReg := storeSegSel
    }.elsewhen(io.mem.wreq && io.mem.wrsp && io.mem.wlast){
        storeValidReg := false.B
        val isWrap = (burstCntMap(2) + 1.U) === lengthMap(2) 
        addrDyn(2)     := Mux(isWrap, addrCfg(2), addrDyn(2) + l2Line.U)
        burstCntMap(2) := Mux(isWrap, 0.U, burstCntMap(2) + 1.U)
        oIterCntMap(2) := Mux(isWrap, oIterCntMap(2) + 1.U, oIterCntMap(2))
    }
    // write Mem
    io.mem.wreq  := storeValidReg
    io.mem.waddr := addrDyn(storeFifoId)
    io.mem.wlen  := axiLen
    io.mem.wsize := axiSize
    io.mem.wstrb := 0xf.U
    io.mem.wlast := storeWordCnt.andR
    io.mem.wdata := Fifo(storeFifoId)(storeFifoIdx)
    io.storesDrained := !storereadyMap(Dst).asUInt.orR && !storeValidReg &&
      !storereadyMap(Layout).asUInt.orR && !layoutStoreLineActive && layoutWritesPending === 0.U
}
