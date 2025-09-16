import chisel3._
import chisel3.util._
import ZirconConfig.Stream._
import ZirconConfig.Cache._
import ZirconConfig.EXEOp._
import ZirconConfig.FifoRole._



class SEPipelineIO extends Bundle {
    val op      = Input(UInt(stInstBits.W))
    val src1    = Input(UInt(32.W))
    val src2    = Input(UInt(32.W))
    val cfgState = Input(Vec(streamCfgBits,Bool()))
    val valid = Input(Bool())
    val busy  = Output(Bool())
}


class StreamEngineIO extends Bundle {
    val pp  = new SEPipelineIO
    val mem = new MemIO(false)
}


class StreamEngine extends Module {
    val io = IO(new StreamEngineIO)
    

    val iCntMap = RegInit(VecInit.fill(iterNum)(0.U(32.W)))    //i_id -> itercnt
    val streamMap = RegInit(VecInit.fill(streamNum)(0.U(iterBits.W))) //fifo_id -> i_id
    val addrCfg = RegInit(VecInit.fill(streamNum)(0.U(32.W))) //fifo_id -> addr
    val stateCfg = RegInit(VecInit.fill(streamNum)(VecInit.fill(streamCfgBits)(false.B))) //fifo_id -> [doneCfg,isLoad,...]
    val readyMap = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(false.B)))  //fifo_id,itercnt -> ready
    val Fifo = RegInit(VecInit.fill(streamNum)(VecInit.fill(fifoWord)(0.U(32.W))))  //fifo_id,itercnt -> data

    val tmplengthMap  = RegInit(VecInit.fill(streamNum)(0.U(3.W))) //TODO 0->1->2->3 GG 64word

    val ppBits = io.pp
    val op = ppBits.op
    val src1 = ppBits.src1
    val src2 = ppBits.src2
    val valid = io.pp.valid


    val isCfgI = op === CFGI && valid
    val isStepI = op === STEPI && valid
    val isCfgStream = op === CFGSTREAM && valid
    val isCal = op === CALSTREAM && valid


    val iId = src1(iterBits-1,0)
    val addr = src1
    val fifoId = VecInit(src1(streamBits*2-1, streamBits),src1(streamBits-1, 0),src2(streamBits-1, 0))//fifo_src_0 fifo_src_1 fifo_dst
    val fifoIter = VecInit.tabulate(3){ j => iCntMap(streamMap(fifoId(j)))}
    val fifoWordIdx   = VecInit.tabulate(3){ j => (fifoIter(j) % fifoWord.U)(log2Ceil(fifoWord)-1,0)}
    

    //----------------- 1:CORE -------------------
    //config
    val iMapWen = isCfgI || isStepI
    val iMapWdata = Mux(isCfgI , 0.U(32.W), iCntMap(iId) + 1.U(32.W))
    when(iMapWen){
        iCntMap(iId) := iMapWdata
    }
    when(isCfgI){
        streamMap(fifoId(Dst)) := iId
    }
    when(isCfgStream){
        addrCfg(fifoId(Dst)) := addr 
        stateCfg(fifoId(Dst)) := ppBits.cfgState
    }

    //calculate
    val dbgCnt = RegInit(0.U(32.W))
    val srcRdy = VecInit.tabulate(2){ j=> readyMap(fifoId(j))(fifoWordIdx(j)) }.asUInt.andR //src0 src1 has data
    val dstRdy = !readyMap(fifoId(Dst))(fifoWordIdx(Dst)) //dst has space to write
    val datas = VecInit.tabulate(2){ j=> Fifo(fifoId(j))(fifoWordIdx(j)) }
    val res = datas(Src0) + datas(Src1)
    when(isCal && srcRdy && dstRdy ){
        Fifo(fifoId(Dst))(fifoWordIdx(Dst)) := res
        for(i <- 0 until 3){
            readyMap(fifoId(i))(fifoWordIdx(i)) := !readyMap(fifoId(i))(fifoWordIdx(i))
        }
        dbgCnt := dbgCnt + 1.U
        printf(p" dbgCnt=$dbgCnt \n")
        // printf(p"CAL FIFO | ${datas(0)} | ${datas(1)} | res=$res \n")
    }
    io.pp.busy := !(srcRdy && dstRdy) && isCal

    //----------------- 2:MEMORY -------------------
    val l2LineWord = l2Line >> 2
    val fifoSegNum = fifoWord / l2LineWord //FIFO大小= fifoSegNum * l2LineWord * 4B
    val axiLen = (l2LineBits / 32 - 1).U
    val axiSize = 2.U

    //----------------- 2.1:READ -------------------
    // fifoSegEmpty：Vec[streamNum,Vec(fifoSegNum,bool())]
    val fifoSegEmpty = VecInit.tabulate(streamNum){j=>
        VecInit.tabulate(fifoSegNum){k=>
            !readyMap(j).slice(k*l2LineWord, (k+1)*l2LineWord).reduce(_ || _)  &&  stateCfg(j)(LDSTRAEM) && stateCfg(j)(DONECFG)  && (tmplengthMap(j)(0)===k.U && !tmplengthMap(j)(2))//TODO
        }
    }
    //TODO 只使用两个load stream目前
    val fifo0Valid = fifoSegEmpty(0).asUInt.orR
    val fifo1Valid = fifoSegEmpty(1).asUInt.orR
    val loadValid  = fifo0Valid | fifo1Valid
    val loadFifoId = Mux(fifo1Valid && (tmplengthMap(1)<tmplengthMap(0)), 1.U ,0.U)
    val loadSegSel = Mux(fifo1Valid && (tmplengthMap(1)<tmplengthMap(0)), fifoSegEmpty(1).asUInt-1.U  , fifoSegEmpty(0).asUInt-1.U)

    // loadPerSegSel：Seq[(valid: Bool, chosenStreamIdx: UInt)]
    // val loadPerSegSel = (0 until fifoSegNum).map { segIdx =>
    //     val conds = (0 until streamNum).map { fifoId =>
    //     fifoSegEmpty(fifoId)(segIdx) -> fifoId.U
    //     }
    //     val selFifo = PriorityMux(conds) // 这一段里最小的 stream index
    //     val valid = VecInit.tabulate(streamNum)(j => fifoSegEmpty(j)(segIdx)).asUInt.orR
    //     (valid, selFifo)
    // }
    // // select fifo 
    // val loadSegValids = VecInit(loadPerSegSel.map(_._1))        // 每个 segment 是否有有效 fifo
    // val loadSegFifoIds = VecInit(loadPerSegSel.map(_._2))       // 每个 segment 对应的被选 stream idx
    // val loadSegSel = PriorityEncoder(loadSegValids)
    // val loadFifoId = loadSegFifoIds(loadSegSel)
    // val loadValid = loadSegValids.asUInt.orR

    
    // fetch from Mem
    val loadWordCnt     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val loadValidReg      = RegInit(false.B)
    val loadFifoIdReg     = RegInit(0.U(streamBits.W))
    val loadSegSelReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))
    when(io.mem.rreq && io.mem.rrsp){
        loadWordCnt := loadWordCnt + 1.U
    }
    when(!loadValidReg){
        loadValidReg := loadValid
        loadFifoIdReg := loadFifoId
        loadSegSelReg := loadSegSel
    }.elsewhen(io.mem.rreq && io.mem.rrsp && io.mem.rlast){
        loadValidReg := false.B
        tmplengthMap(loadFifoIdReg):= tmplengthMap(loadFifoIdReg) + 1.U
        addrCfg(loadFifoIdReg) := addrCfg(loadFifoIdReg) + l2Line.U
    }
    io.mem.rreq      := loadValidReg
    io.mem.raddr     := addrCfg(loadFifoIdReg)
    io.mem.rlen      := axiLen
    io.mem.rsize     := axiSize

    // refill FIFO
    val wFifoWen    = io.mem.rreq && io.mem.rrsp
    val wFifoData   = io.mem.rdata
    val wFifoIdx  = (loadSegSelReg * l2LineWord.U + loadWordCnt)(log2Ceil(fifoWord)-1,0) 
    when(wFifoWen) {
        Fifo(loadFifoIdReg)(wFifoIdx) := wFifoData
        readyMap(loadFifoIdReg)(wFifoIdx) := true.B
        //printf(p"LOAD FIFO | id = $loadFifoIdReg | idx = $wFifoIdx | value = $wFifoData\n")
    }


    //----------------- 2.2:WRITE -------------------
    val storeFifoId = 2

    val wFifoSegFull = VecInit.tabulate(fifoSegNum){ k=> readyMap(storeFifoId).slice(k*l2LineWord, (k+1)*l2LineWord).reduce(_ && _) }
    val storeSegSel = PriorityEncoder(wFifoSegFull)
    val storeValid = stateCfg(storeFifoId)(DONECFG) && !stateCfg(storeFifoId)(LDSTRAEM) && wFifoSegFull.asUInt.orR
    
    val storeWordCnt     = RegInit(0.U((l2Offset-2).W)) // word cnt
    val storeValidReg      = RegInit(false.B)
    val storeSegSelReg     = RegInit(0.U(log2Ceil(fifoSegNum).W))
    val storeFifoIdx  = (storeSegSelReg * l2LineWord.U + storeWordCnt)(log2Ceil(fifoWord)-1,0) 
    when (io.mem.wreq.get && io.mem.wrsp.get){
        storeWordCnt := storeWordCnt + 1.U
        readyMap(storeFifoId)(storeFifoIdx):=false.B
        addrCfg(storeFifoId) := addrCfg(storeFifoId) + l2Line.U
        printf(p"STORE FIFO | id = $storeFifoId | idx = $storeFifoIdx | value = ${io.mem.wdata.get}\n")
    }
    when(!storeValidReg){
        storeValidReg  := storeValid
        storeSegSelReg := storeSegSel
    }.elsewhen(io.mem.wreq.get && io.mem.wrsp.get && io.mem.wlast.get){
        storeValidReg := false.B
    }
    // write Mem
    io.mem.wreq.get  := storeValidReg
    io.mem.waddr.get := addrCfg(storeFifoId)
    io.mem.wlen.get  := axiLen
    io.mem.wsize.get := axiSize
    io.mem.wstrb.get := 0xf.U
    io.mem.wlast.get := storeWordCnt.andR
    io.mem.wdata.get := Fifo(storeFifoId)(storeFifoIdx)
}