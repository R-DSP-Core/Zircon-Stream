import chisel3._
import chisel3.util._
import ZirconConfig.Stream._
import ZirconConfig.Cache._
import ZirconConfig.EXEOp._
import ZirconConfig.FifoRole._
import ZirconConfig.Issue._

class SERFIO extends Bundle {
    val iterCnt = Input(UInt(32.W))
    val rdata1 = Output(UInt(32.W))
    val rdata2 = Output(UInt(32.W))
}

class SEWBIO extends Bundle {
    val wvalid = Input(Bool())
    val iterCnt = Input(UInt(32.W))
    val wdata  = Input(UInt(32.W))
}

class SEISIO extends Bundle {
    val isCalStream = Input(Vec(arithNiq,Bool()))
    val iterCnt = Input(Vec(arithNiq,UInt(32.W)))
    val ready  = Output(Vec(arithNiq, Bool()))
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
}

class StreamEngineIO extends Bundle {
    val rf = Vec(3, new SERFIO)
    val wb = Vec(3, new SEWBIO)
    val is = new SEISIO
    val rdIter = Flipped(new SERdIterIO)
    val pp  = new SEPipelineIO
    val mem = new SEMemIO
    val dc = new SEDCIO
}

class StreamEngine extends Module {
    val io = IO(new StreamEngineIO)
    

    val iCntMap = RegInit(VecInit.fill(streamNum)(0.U(32.W)))    //fifo_id -> itercnt
    val iLimitCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_limit
    val iLimitDyn = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> cur i_limit
    val iRepeatCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> i_repeat
    val iRepeatDyn = RegInit(VecInit.fill(streamNum)(0.U(32.W)))  //fifo_id -> cur i_repeat 

    val streamMap = RegInit(VecInit.fill(streamNum)(0.U(iterBits.W))) //fifo_id -> i_id
    val addrCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val addrDyn = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val strideCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val tileStrideCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val reuseCfg = RegInit(VecInit.fill(streamNum)(0.U(counterWidth.W)))
    val stateCfg = RegInit(VecInit.fill(streamNum)(VecInit.fill(streamCfgBits)(false.B))) //fifo_id -> [doneCfg,isLoad,...]
    val loadreadyMap = RegInit(VecInit.fill(streamNum-1)(VecInit.fill(fifoWord)(0.U(counterWidth.W))))
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
    val isCfgStream = (op === CFGLOAD || op=== CFGSTORE) && valid
    val isCfgStride = op === CFGSTRIDE && valid
    val isCfgTileStride = op === CFGTILESTRIDE && valid
    val isCfgReuse = op === CFGREUSE && valid
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
    val fifoId = VecInit(src1(streamBits*2-1, streamBits),src1(streamBits-1, 0),src2(streamBits-1, 0))//fifo_src_0 fifo_src_1 fifo_dst    

    //----------------- 1:CORE -------------------
    //config
    when(isCfgI){
        iCntMap(fifoId(Dst)) := 0.U
        streamMap(fifoId(Dst)) := 0.U
        lengthMap(fifoId(Dst)) := cfgLength / l2LineWord.U
        outerIterMap(fifoId(Dst)) := outerIter
    }
    when(isCfgILimit){
        iLimitCfg(fifoId(Dst)) := cfgIlimit
        iLimitDyn(fifoId(Dst)) := cfgIlimit
    }
    when(isCfgIRepeat){
        iRepeatCfg(fifoId(Dst)) := cfgIrepeat
        iRepeatDyn(fifoId(Dst)) := cfgIrepeat
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

    // dispatch stage
    //TODO：这里的假设是 0，1，2号流分别是RS1，RS2，RD
    for (b <- 0 until 3) {  
        val sum = iCntMap(b) + PopCount(io.rdIter.fireStreamOp(b))
        when(sum < iLimitDyn(b)){
            iCntMap(b) := sum
        }.elsewhen (iRepeatDyn(b) + 1.U === iRepeatCfg(b)){
            iCntMap(b) := sum
            iLimitDyn(b) := iLimitDyn(b) + iLimitCfg(b)
            iRepeatDyn(b) := 0.U
        }.otherwise{
            iCntMap(b) := sum - iLimitCfg(b) //sum - iLimitDyn(b) + iLimitDyn(b) - iLimitCfg(b)
            iRepeatDyn(b) := iRepeatDyn(b) + 1.U
        }
        io.rdIter.iterCnt(b) := iCntMap(b)
    }

    // Issue stage
    for (i <- 0 until arithNiq) {
        val issWordIdx = VecInit.fill(3)(0.U(log2Ceil(fifoWord).W))
        for (b <- 0 until 3) {
            issWordIdx(b) := (io.is.iterCnt(i)(b) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        }

        val isWordIdx = (io.is.iterCnt(i) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        io.is.ready(i) :=  io.is.isCalStream(i) &
                          (loadreadyMap(0)(isWordIdx) =/= 0.U) &
                          (loadreadyMap(1)(isWordIdx) =/= 0.U) &
                          !storereadyMap(isWordIdx) 
    }

    // ReadOp stage + writeback stage
    for(i <- 0 until 3){
        val rfWordIdx = (io.rf(i).iterCnt % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        io.rf(i).rdata1 := Fifo(0)(rfWordIdx)
        io.rf(i).rdata2 := Fifo(1)(rfWordIdx)
        when(io.wb(i).wvalid){
            val wbWordIdx = (io.wb(i).iterCnt % fifoWord.U) (log2Ceil(fifoWord)-1,0)
            Fifo(2)(wbWordIdx) := io.wb(i).wdata
            loadreadyMap(0)(wbWordIdx) := loadreadyMap(0)(wbWordIdx) - 1.U
            loadreadyMap(1)(wbWordIdx) := loadreadyMap(1)(wbWordIdx) - 1.U
            storereadyMap(wbWordIdx) := true.B 
        }
    }

    //----------------- 2:MEMORY -------------------
    val fifoSegNum = fifoWord / l2LineWord //FIFO大小= fifoSegNum * l2LineWord * 4B
    val axiLen = (l2LineBits / 32 - 1).U
    val axiSize = 2.U

    //----------------- 2.1:READ -------------------
    // fifoSegEmpty：Vec[streamNum,Vec(fifoSegNum,bool())]
    val fifoSegEmpty = VecInit.tabulate(streamNum-1){j=>
        VecInit.tabulate(fifoSegNum){k=>
            loadreadyMap(j).slice(k*l2LineWord, (k+1)*l2LineWord).map(_ === 0.U).reduce(_ && _)  &&  
            stateCfg(j)(LDSTRAEM) && stateCfg(j)(DONECFG)  && 
            (burstCntMap(j)(0)===k.U && oIterCntMap(j)=/=outerIterMap(j))
        }
    }

    //TODO 只使用两个load stream目前
    // fetch from Mem  //Regfile Stage
    val loadWordCnt     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val fifo0Valid = fifoSegEmpty(0).asUInt.orR
    val fifo1Valid = fifoSegEmpty(1).asUInt.orR
    val loadValid  = fifo0Valid | fifo1Valid
    val loadFifoId = Mux(fifo1Valid && (burstCntMap(1)<burstCntMap(0)), 1.U ,0.U)
    val loadSegSel = Mux(fifo1Valid && (burstCntMap(1)<burstCntMap(0)), fifoSegEmpty(1).asUInt-1.U  , fifoSegEmpty(0).asUInt-1.U)
    val loadValidReg      = RegInit(false.B)
    val loadFifoIdReg     = RegInit(0.U(streamBits.W))
    val loadSegSelReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val loadAddr = addrDyn(loadFifoIdReg) + loadWordCnt * strideCfg(loadFifoIdReg)
    val loadLastOne = loadWordCnt === (l2LineWord - 1).U 
    val loadDone = loadLastOne && (io.dc.rreq && !(io.dc.miss || io.dc.sbFull))
    val loadFirst = loadWordCnt === 0.U && loadValidReg
    
    when(io.dc.rreq && !(io.dc.miss || io.dc.sbFull)){
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

    when(loadValid && loadWordCnt === 0.U && !(io.dc.miss || io.dc.sbFull)){ //这段挺对的
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
    io.dc.rreq      := loadValidReg
    io.dc.mtype     := 2.U
    io.dc.isLatest  := true.B 
    io.dc.vaddr     := loadAddr

    // DCache Stage 1
    val loadWordCntRegD1 = RegInit(0.U((l2Offset-2).W))
    val loadValidRegD1  = RegInit(false.B)
    val loadFifoIdRegD1 = RegInit(0.U(streamBits.W))
    val loadSegSelRegD1 = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val loadAddrRegD1      = RegInit(0.U(32.W)) 
    when(!(io.dc.miss || io.dc.sbFull)){
        loadWordCntRegD1 := loadWordCnt
        loadValidRegD1  := loadValidReg
        loadFifoIdRegD1 := loadFifoIdReg
        loadSegSelRegD1 := loadSegSelReg
        loadAddrRegD1      := loadAddr
    }
    io.dc.rreqD1      := loadValidRegD1
    io.dc.paddrD1     := loadAddrRegD1
    // DCache Stage 2
    val loadWordCntRegD2 = RegInit(0.U((l2Offset-2).W))
    val loadValidRegD2  = RegInit(false.B)
    val loadFifoIdRegD2 = RegInit(0.U(streamBits.W))
    val loadSegSelRegD2 = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val loadAddrRegD2      = RegInit(0.U(32.W)) 
    when(!(io.dc.miss || io.dc.sbFull)){
        loadWordCntRegD2 := loadWordCntRegD1
        loadValidRegD2  := loadValidRegD1
        loadFifoIdRegD2 := loadFifoIdRegD1
        loadSegSelRegD2 := loadSegSelRegD1
        loadAddrRegD2   := loadAddrRegD1
    }

    // Write Back Stage todo:改成空泡类型
    val loadWordCntRegWB = WireDefault(ShiftRegister(
        Mux(io.dc.miss || io.dc.sbFull, 0.U, loadWordCntRegD2),
        1,
        0.U((l2Offset-2).W),
        true.B
    ))
    val loadValidRegWB = WireDefault(ShiftRegister(
        Mux(io.dc.miss || io.dc.sbFull, false.B, loadValidRegD2),
        1, 
        false.B, 
        true.B
    ))
    val loadFifoIdRegWB = WireDefault(ShiftRegister(
        Mux(io.dc.miss || io.dc.sbFull, 0.U, loadFifoIdRegD2),
        1,
        0.U(streamBits.W),
        true.B
    ))
    val loadSegSelRegWB = WireDefault(ShiftRegister(
        Mux(io.dc.miss || io.dc.sbFull, 0.U, loadSegSelRegD2),
        1,
        0.U(log2Ceil(fifoSegNum).W),
        true.B
    ))
    val loadAddrRegWB = WireDefault(ShiftRegister(
        Mux(io.dc.miss || io.dc.sbFull, 0.U, loadAddrRegD2),
        1,
        0.U(32.W),
        true.B
    ))
    // refill FIFO
    val wFifoWen    = loadValidRegWB
    val wFifoData   = io.dc.rdata
    val wFifoIdx  = (loadSegSelRegWB * l2LineWord.U + loadWordCntRegWB)(log2Ceil(fifoWord)-1,0) 
    when(wFifoWen) {
        Fifo(loadFifoIdRegWB)(wFifoIdx) := wFifoData
        loadreadyMap(loadFifoIdRegWB)(wFifoIdx) := reuseCfg(loadFifoIdRegWB)
        //printf(p"LOAD FIFO | id = $loadFifoIdReg | idx = $wFifoIdx | value = $wFifoData\n")
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