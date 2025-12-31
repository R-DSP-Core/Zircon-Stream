import chisel3._
import chisel3.util._
import ZirconConfig.Stream._
import ZirconConfig.Cache._
import ZirconConfig.EXEOp._
import ZirconConfig.FifoRole._
import ZirconConfig.Issue._

class SERFIO extends Bundle {
    val iterCnt = Input(Vec(3,UInt(32.W)))
    val rdata1 = Output(UInt(32.W))
    val rdata2 = Output(UInt(32.W))
}

class SEWBIO extends Bundle {
    val wvalid = Input(Bool())
    val useBuffer = Input(Vec(3,Bool()))
    val iterCnt = Input(Vec(3,UInt(32.W)))
    val wdata  = Input(UInt(32.W))
}

class SEISSIO extends Bundle {
    val isCalStream = Input(Bool())
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
        iRepeatDyn(fifoId(Dst)) := 0.U
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
        when(PopCount(io.rdIter.fireStreamOp(b)) =/= 0.U){
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
        }
        io.rdIter.iterCnt(b) := iCntMap(b)
    }

    // Issue stage
    for (i <- 0 until 16) {
        val issWordIdx = VecInit.fill(3)(0.U(log2Ceil(fifoWord).W))
        for (b <- 0 until 3) {
            issWordIdx(b) := (io.iss(i).iterCnt(b) % fifoWord.U) (log2Ceil(fifoWord)-1,0)
        }
        io.iss(i).ready :=  io.iss(i).isCalStream &
                          (loadreadyMap(0)(issWordIdx(0)) =/= 0.U || !io.iss(i).useBuffer(0)) &
                          (loadreadyMap(1)(issWordIdx(1)) =/= 0.U || !io.iss(i).useBuffer(1)) &
                          (!storereadyMap(issWordIdx(2)) || !io.iss(i).useBuffer(2))
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
        }
    }

    //----------------- 2:MEMORY -------------------
    val axiLen = (l2LineBits / 32 - 1).U
    val axiSize = 2.U

    //----------------- 2.1:READ -------------------
    // Vec[streamNum,Vec(fifoSegNum,bool())]
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
            //printf(p"id=$loadFifoIdAXIReg, burstCnt=$burstCntMap, oIterCntMap=$oIterCntMap, outerIterMap=$outerIterMap\n")
            oIterCntMap(loadFifoIdAXIReg) :=oIterCntMap(loadFifoIdAXIReg) + 1.U
        }
        addrDyn(loadFifoIdAXIReg)     := Mux(isWrap, addrCfg(loadFifoIdAXIReg), addrDyn(loadFifoIdAXIReg) + l2Line.U)
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
        printf(p"FIFO $loadFifoIdAXIReg [$wFifoAXIIdx]=${io.mem.rdata} \n")
    }

    val wFifoIdx  = (loadWB.segSel * l2LineWord.U + loadWB.wordCnt)(log2Ceil(fifoWord)-1,0) 
    when(loadWB.valid) {
        Fifo(loadWB.fifoId)(wFifoIdx) := loadWB.rdata
        loadreadyMap(loadWB.fifoId)(wFifoIdx) := reuseCfg(loadWB.fifoId)
        printf(p"FIFO ${loadWB.fifoId}[$wFifoIdx]=${loadWB.rdata}(Mem[${loadWB.addr}])  \n")
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