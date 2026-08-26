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
    val rdata1 = Output(UInt(32.W))
    val rdata2 = Output(UInt(32.W))
}

class SEWBIO extends Bundle {
    val wvalid = Input(Bool())
    val useBuffer = Input(Vec(3,Bool()))
    val usePPBuffer = Input(Bool())
    val iterCnt = Input(Vec(3,UInt(32.W)))
    val wdata  = Input(UInt(32.W))
}

class SEISSIO extends Bundle {
    val isCalStream = Input(Bool())
    val usePPBuffer = Input(Bool())
    val useBuffer = Input(Vec(3,Bool()))
    val iterCnt = Input(Vec(3,UInt(32.W)))
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
    val paddr       = Output(UInt(32.W))
    val mtype       = Output(UInt(2.W))
    val miss        = Input(Bool())
    val dcHazard   = Input(Bool())
    val l2S1Valid   = Input(Bool())
}

class StreamEngineIO extends Bundle {
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
    val tagR = RegInit(false.B)
    val cntInst = RegInit(0.U(32.W)) // TODO: CFG to 256

    val archPP = RegInit(VecInit.fill(2)(0.U.asTypeOf(new dynPPState)))
    val specPP = RegInit(VecInit.fill(2)(0.U.asTypeOf(new dynPPState)))
    val ppBaseCfg = RegInit(VecInit.fill(2)(0.U(ppCntWidth.W))) 
    val stageLimitCfg = RegInit(VecInit.fill(2)(5.U(stageWidth.W)))//TODO

    val archI  = RegInit(VecInit.fill(streamNum)(0.U.asTypeOf(new dynIState)))
    val specI  = RegInit(VecInit.fill(streamNum)(0.U.asTypeOf(new dynIState)))
    val iLimitCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_limit
    val iRepeatCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_repeat
    val iOffsetCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_offset

    val streamMap = RegInit(VecInit.fill(streamNum)(0.U(iterBits.W))) //fifo_id -> i_id
    val addrCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val addrDyn = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val strideCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val tileStrideCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val reuseCfg = RegInit(VecInit.fill(streamNum)(0.U(counterWidth.W)))
    val stateCfg = RegInit(VecInit.fill(streamNum)(VecInit.fill(streamCfgBits)(false.B))) //fifo_id -> [doneCfg,isLoad,...]

    val loadreadyMap = RegInit(VecInit.fill(streamNum-1)(VecInit.fill(fifoWord)(0.U(counterWidth.W))))
    val archLoadReadyMap = RegInit(VecInit.fill(streamNum-1)(VecInit.fill(fifoWord)(0.U(counterWidth.W))))

    val storereadyMap = RegInit(VecInit.fill(fifoWord)(false.B))
    val Fifo = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(0.U(32.W))))  //fifo_id,itercnt -> data

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
    val fifoId = VecInit(src1(streamBits*2-1, streamBits),src1(streamBits-1, 0),src2(streamBits-1, 0))//fifo_src_0 fifo_src_1 fifo_dst
    val rearm = isCfgI && fifoId(Dst) === 0.U

    //----------------- 1:CORE -------------------
    //config
    when(isCfgI){
        specI(fifoId(Dst)).iCnt := 0.U
        archI(fifoId(Dst)).iCnt := 0.U
        streamMap(fifoId(Dst)) := 0.U
        lengthMap(fifoId(Dst)) := cfgLength / l2LineWord.U
        outerIterMap(fifoId(Dst)) := outerIter
        // CFGI begins a new phase, but stateCfg still describes the previous
        // phase's CFGLOAD.  Disable memory loading until the new CFGLOAD (or
        // CFGAXILOAD) supplies this phase's base address; otherwise the FIFO
        // is immediately refilled from the stale address after it is cleared.
        stateCfg(fifoId(Dst)) := VecInit.fill(streamCfgBits)(false.B)
        // A new CFGI starts an independent stream phase.  The iterator state
        // was already reset above, so the memory-side progress counters must
        // be reset with it; otherwise a second K=32 phase inherits the first
        // phase's completed burst/outer-iteration state and cannot re-arm.
        burstCntMap(fifoId(Dst)) := 0.U
        oIterCntMap(fifoId(Dst)) := 0.U
        // A completed phase can leave speculative/architectural reuse counts
        // in the ping-pong FIFO maps.  CFGI is the phase boundary, so make both
        // segments empty before CFGLOAD starts refilling the new address range.
        for (b <- 0 until streamNum - 1) {
            when(fifoId(Dst) === b.U) {
                for (i <- 0 until fifoWord) {
                    loadreadyMap(b)(i) := 0.U
                    archLoadReadyMap(b)(i) := 0.U
                }
            }
        }
        when(fifoId(Dst) === 0.U) {
            // CALSTREAMRD also uses the shared ping-pong iterator.  It used to
            // reset only with CPU reset, which made a second stream phase start
            // from the previous phase's PP position.
            ppDecBase := 0.U
            tagR := false.B
            cntInst := 0.U
            for (b <- 0 until 2) {
                specPP(b).ppCnt := (2 * b).U
                specPP(b).ppStride := 2.U
                specPP(b).ppLimit := (2 * b + 2).U
                specPP(b).ppStage := 0.U
                archPP(b).ppCnt := (2 * b).U
                archPP(b).ppStride := 2.U
                archPP(b).ppLimit := (2 * b + 2).U
                archPP(b).ppStage := 0.U
            }
        }
    }
    when(isCfgILimit){
        iLimitCfg(fifoId(Dst)) := cfgIlimit
        specI(fifoId(Dst)).iLimit := cfgIlimit
        archI(fifoId(Dst)).iLimit := cfgIlimit
    }
    when(isCfgIRepeat){
        iRepeatCfg(fifoId(Dst)) := cfgIrepeat
        specI(fifoId(Dst)).iRepeat := 0.U
        archI(fifoId(Dst)).iRepeat := 0.U
    }
    when(isCfgIOffset){
        iOffsetCfg(fifoId(Dst)) := cfgIoffset
    }
    when(isCfgStream){
        addrCfg(fifoId(Dst)) := addr 
        addrDyn(fifoId(Dst)) := addr 
        stateCfg(fifoId(Dst)) := ppBits.cfgState
    }
    when(isCfgStride){
        strideCfg(fifoId(Dst)) := stride
    }
    when(isCfgTileStride){
        tileStrideCfg(fifoId(Dst)) := tileStride
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

    //----------------- UPDATE I STATE -------------------
    def updateIState(
      state: dynIState,
      fireVec: Vec[Bool],
      iLimitCfg: UInt,
      iRepeatCfg: UInt,
      iOffsetCfg: UInt,
    ): (dynIState, Vec[UInt]) = {

      val width = fireVec.length
      val nextState = Wire(new dynIState)
      nextState := state  // default hold
      val iterCnt = Wire(Vec(width, UInt(32.W)))

      val fireNum = PopCount(fireVec)
      val sumBase = state.iCnt

      // 1. 状态更新
      when(fireNum =/= 0.U) {
        val sum = sumBase + fireNum
        when(sum < state.iLimit) {
          nextState.iCnt := sum
        }.elsewhen(state.iRepeat + 1.U === iRepeatCfg) {
          nextState.iCnt    := sum
          nextState.iLimit  := state.iLimit + iLimitCfg
          nextState.iRepeat := 0.U
        }.otherwise {
          nextState.iCnt    := sum - iLimitCfg + iOffsetCfg
          nextState.iLimit  := state.iLimit + iOffsetCfg
          nextState.iRepeat := state.iRepeat + 1.U
        }
      }
    // 2. iterCnt 计算（展开）
    for (i <- 0 until width) {
      val sum = sumBase + i.U
      iterCnt(i) := Mux(
        sum < state.iLimit,
        sum,
        Mux(
          state.iRepeat + 1.U === iRepeatCfg,
          sum,
          sum - iLimitCfg + iOffsetCfg
        )
      )
    }
    (nextState, iterCnt)
    }

    // 对 0 1 2号流的状态分别进行更新
    for (b <- 0 until 3) {
      // DISPATCH
      val (nextState, iterCnt) = updateIState(
        state        = specI(b),
        fireVec      = io.rdIter.fireStreamOp(b),
        iLimitCfg    = iLimitCfg(b),
        iRepeatCfg   = iRepeatCfg(b),
        iOffsetCfg   = iOffsetCfg(b)
      )
      when(!isCfgI && !isCfgILimit && !isCfgIRepeat && !isCfgIOffset ){ // 配置指令不更新状态，其他指令才更新 TODO:confuse
        specI(b) := nextState
      }
      io.rdIter.iterCnt(b) := iterCnt
      // COMMIT
      val (nextArchState, iterCntArch) = updateIState(
        state        = archI(b),
        fireVec      = io.cmt.fireStreamOp(b),
        iLimitCfg    = iLimitCfg(b),
        iRepeatCfg   = iRepeatCfg(b),
        iOffsetCfg   = iOffsetCfg(b)
      )
      when(!isCfgI && !isCfgILimit && !isCfgIRepeat && !isCfgIOffset ){ // 配置指令不更新状态，其他指令才更新
        archI(b) := nextArchState
      }
      if( b != 2 ){ //TODO: 当前仅对src0 src1恢复
        for (i <- 0 until ncommit) {
        when(io.cmt.fireStreamOp(b)(i)){
          //assert(iterCntArch(i) === io.cmt.iterCnt(b)(i), p"isLLL:${io.cmt.fireStreamOpPP(Even)(i)},slot${i}, op${b}; arch iterCnt ${iterCntArch(i)} != cmt iterCnt ${io.cmt.iterCnt(b)(i)}")
          val idx = (io.cmt.iterCnt(b)(i) % fifoWord.U)(log2Ceil(fifoWord)-1,0)
          archLoadReadyMap(b)(idx) := archLoadReadyMap(b)(idx) - 1.U
        }
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
        ( 0.U ## ppRaw(5), Mux(revSeg === 0.U, Cat(~ppRaw(6), ppRaw(4,0)), Cat(ppRaw(6), ppRaw(4,0))) )
    }

    // b = 0，流流流；b = 1，寄寄流
    for (b <- 0 until 2) {
      val (nextState, iterCnt, omitTag) = updatePPState(
        state       = specPP(b),
        fireVec     = io.rdIter.fireStreamOpPP(b),
        stageLimit  = stageLimitCfg(b),
        base        = ppBaseCfg(b),
        isOdd       = (b.U === Odd.U)
      )
      when(initDone && !rearm){
        specPP(b) := nextState
      }
      io.rdIter.iterCntPP(b) := iterCnt
      // COMMIT
      val (nextArchState, iterCntArch, archTag) = updatePPState(
        state       = archPP(b),
        fireVec     = io.cmt.fireStreamOpPP(b),
        stageLimit  = stageLimitCfg(b),
        base        = ppBaseCfg(b),
        isOdd       = (b.U === Odd.U)
      )
      if(b == 0){
        when(!rearm) {
          tagR := archTag//TODO
        }
      }
      when(initDone && !rearm){
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
    when(!rearm) {
      cntInst := cntInst + inc
      when(cntInst === 256.U){
        cntInst := 0.U
        ppDecBase := ~ppDecBase
      }
    }

    // Flush
    when(io.cmt.flush){
      specI := archI
      specPP := archPP
      loadreadyMap := archLoadReadyMap
    }


    // Issue stage
    for (i <- 0 until 16) {
        val issWordIdx = VecInit.fill(3)(0.U(log2Ceil(fifoWord).W))
        for (b <- 0 until 3) {
            issWordIdx(b) := (io.iss(i).iterCnt(b) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        }
        val (ppId, ppIdx) = getPP(io.iss(i).iterCnt(2), ppDecBase)
        val ppRdy = !io.iss(i).usePPBuffer || loadreadyMap(ppId)(ppIdx) === 0.U
        io.iss(i).ready :=  io.iss(i).isCalStream &
                          (loadreadyMap(0)(issWordIdx(0)) =/= 0.U || !io.iss(i).useBuffer(0)) &
                          (loadreadyMap(1)(issWordIdx(1)) =/= 0.U || !io.iss(i).useBuffer(1)) &
                          (!storereadyMap(issWordIdx(2)) || !io.iss(i).useBuffer(2)) & ppRdy
    }

    // ReadOp stage + writeback stage
    for(i <- 0 until 4){
        // readop stage
        val rfWordIdx0 = (io.rf(i).iterCnt(0) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        val rfWordIdx1 = (io.rf(i).iterCnt(1) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        io.rf(i).rdata1 := Fifo(0)(rfWordIdx0)
        io.rf(i).rdata2 := Fifo(1)(rfWordIdx1)
        // writeback stage
        when(io.wb(i).wvalid){
            for (b <- 0 until 2){
                when(io.wb(i).useBuffer(b)){
                    val wbSrcIdx = (io.wb(i).iterCnt(b) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
                    loadreadyMap(b)(wbSrcIdx) := loadreadyMap(b)(wbSrcIdx) - 1.U
                }
            }
            when(io.wb(i).useBuffer(2)){
                val wbWordIdx = (io.wb(i).iterCnt(2) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
                Fifo(2)(wbWordIdx) := io.wb(i).wdata
                storereadyMap(wbWordIdx) := true.B 
            }
            when(io.wb(i).usePPBuffer){
                val (ppId, ppIdx) = getPP(io.wb(i).iterCnt(2), ppDecBase)
                Fifo(ppId)(ppIdx) := io.wb(i).wdata
                assert(loadreadyMap(ppId)(ppIdx) === 0.U,p"itercnt = ${io.wb(i).iterCnt(2)}, buffer ${ppId}[${ppIdx}] should be empty")
                loadreadyMap(ppId)(ppIdx) := reuseCfg(ppId)
            }
        }
    }

    //----------------- 2:MEMORY -------------------
    val axiLen = (l2LineBits / 32 - 1).U
    val axiSize = 2.U

    //----------------- 2.1:READ -------------------
    when(initDone && tagR && !rearm){
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
          .map(_ === 0.U)
          .reduce(_ && _) &&
        stateCfg(j)(DONECFG) && 
        stateCfg(j)(LDSTRAEM) &&
        (burstCntMap(j)(0) === k.U) &&
        (oIterCntMap(j) =/= outerIterMap(j))
      }
    }

    // ---- AXI ----
    val fifoSegEmptyAXI = fifoSegEmptyBase.zipWithIndex.map { case (seg, j) =>
        Mux(stateCfg(j)(LDAXISTREAM), seg, VecInit.fill(fifoSegNum)(false.B))}
    val loadSelAXI = Module(new LoadSelect())
    loadSelAXI.io.fifoSegEmpty := fifoSegEmptyAXI
    loadSelAXI.io.burstCntMap  := burstCntMap
    val loadWordCntAXI     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val loadValidAXI  = loadSelAXI.io.loadValid
    val loadFifoIdAXI = loadSelAXI.io.loadFifoId
    val loadSegSelAXI = loadSelAXI.io.loadSegSel
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
        val isWrap = (burstCntMap(loadFifoIdAXIReg) + 1.U) % lengthMap(loadFifoIdAXIReg) === 0.U
        when(isWrap)
        {
            oIterCntMap(loadFifoIdAXIReg) :=oIterCntMap(loadFifoIdAXIReg) + 1.U
        }
        addrDyn(loadFifoIdAXIReg)     := Mux(isWrap, addrCfg(loadFifoIdAXIReg), addrDyn(loadFifoIdAXIReg) + tileStrideCfg(loadFifoIdAXIReg))
        burstCntMap(loadFifoIdAXIReg)  := burstCntMap(loadFifoIdAXIReg) + 1.U
    }
    io.mem.rreq      := loadValidAXIReg
    io.mem.raddr     := addrDyn(loadFifoIdAXIReg)
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
    val loadAddr = addrDyn(loadFifoIdReg) + loadWordCnt * strideCfg(loadFifoIdReg)
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
        val isWrap = (burstCntMap(loadFifoIdReg) + 1.U) % lengthMap(loadFifoIdReg) === 0.U
        when(isWrap)
        {
            oIterCntMap(loadFifoIdReg) :=oIterCntMap(loadFifoIdReg) + 1.U
        }
        addrDyn(loadFifoIdReg)     := Mux(isWrap, addrCfg(loadFifoIdReg), addrDyn(loadFifoIdReg) + tileStrideCfg(loadFifoIdReg))
        burstCntMap(loadFifoIdReg)  := burstCntMap(loadFifoIdReg) + 1.U
    }
    io.l2.rreq      := loadValidReg
    io.l2.mtype     := 2.U //Dontcare
    io.l2.paddr     := loadAddr
    // DCache Stage 1
    val loadD1 = WireDefault(ShiftRegister(
        Mux(!io.l2.l2S1Valid && !io.l2.dcHazard, 
        (new loadPPBundle)(loadWordCnt, loadFifoIdReg, loadSegSelReg, loadAddr, loadValidReg, 0.U(32.W)),
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
        loadreadyMap(loadFifoIdAXIReg)(wFifoAXIIdx) := reuseCfg(loadFifoIdAXIReg)
        archLoadReadyMap(loadFifoIdAXIReg)(wFifoAXIIdx) := reuseCfg(loadFifoIdAXIReg)
    }

    val wFifoIdx  = (loadWB.segSel * l2LineWord.U + loadWB.wordCnt)(log2Ceil(fifoWord)-1,0) 
    when(loadWB.valid) {
        Fifo(loadWB.fifoId)(wFifoIdx) := loadWB.rdata
        loadreadyMap(loadWB.fifoId)(wFifoIdx) := reuseCfg(loadWB.fifoId)
        archLoadReadyMap(loadWB.fifoId)(wFifoIdx) := reuseCfg(loadWB.fifoId)
    }

    //----------------- 2.2:WRITE -------------------
    val storeFifoId = 2

    val wFifoSegFull = VecInit.tabulate(fifoSegNum){ k=> storereadyMap.slice(k*l2LineWord, (k+1)*l2LineWord).reduce(_ && _) }
    val storeSegSel = PriorityEncoder(wFifoSegFull)
    val storeValid = stateCfg(storeFifoId)(DONECFG) && !stateCfg(storeFifoId)(LDSTRAEM) && wFifoSegFull.asUInt.orR
    
    val storeWordCnt     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val storeValidReg      = RegInit(false.B)
    val storeSegSelReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val storeFifoIdx  = (storeSegSelReg * l2LineWord.U + storeWordCnt)(log2Ceil(fifoWord)-1,0) 
    when (io.mem.wreq && io.mem.wrsp){
        storeWordCnt := storeWordCnt + 1.U
        storereadyMap(storeFifoIdx):=false.B
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
}
