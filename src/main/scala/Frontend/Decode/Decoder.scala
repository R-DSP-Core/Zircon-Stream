import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._
import ZirconConfig.Issue._
import ZirconUtil._
import ZirconConfig.Stream._

class StreamInfo extends Bundle{
    val op = Output(UInt(stInstBits.W))
    val state = Output(Vec(streamCfgBits,Bool()))
    val useBuffer = Output(Vec(3,Bool()))
    val usePPBuffer = Output(Vec(2,Bool()))
    val fifoId = Output(Vec(3, UInt(streamBits.W)))
    val words = Output(Vec(3, UInt(4.W)))
    val bank = Output(UInt(3.W))
}

class DecoderIO extends Bundle{
    val inst    = Input(UInt(32.W))
    val rinfo   = Input(new RegisterInfo())
    val op      = Output(UInt(7.W))
    val imm     = Output(UInt(32.W))
    val func    = Output(UInt(niq.W))
    val sinfo  = Output(new StreamInfo())
    val isCalStream = Output(Bool())
    val needStreamSrc = Output(Bool())
}

class Decoder extends Module{
    val io = IO(new DecoderIO)

    val inst         = io.inst
    val funct3       = inst(14, 12)
    val funct7       = inst(31, 25)
    val isAlgebraReg = inst(6, 0) === 0x33.U && funct7(0) === 0.U
    val isAlgebraImm = inst(6, 0) === 0x13.U
    val isLui        = inst(6, 0) === 0x37.U
    val isAuipc      = inst(6, 0) === 0x17.U
    val isJal        = inst(6, 0) === 0x6f.U
    val isJalr       = inst(6, 0) === 0x67.U
    val isBr         = inst(6, 0) === 0x63.U
    val isPriv       = inst(6, 0) === 0x73.U || inst(6, 0) === 0x0f.U
    val isAtom       = inst(6, 0) === 0x2f.U
    val isLoad       = inst(6, 0) === 0x03.U || isAtom && inst(31, 27) === 0x02.U
    val isStore      = inst(6, 0) === 0x23.U || isAtom && inst(31, 27) === 0x03.U
    val isMem        = isLoad || isStore
    val isMuldiv     = inst(6, 0) === 0x33.U && funct7(0) === 1.U
    val isStream     = inst(6, 0) === 0x0b.U

    // These are the raw low bits of the architectural register fields. Their
    // role is instruction-dependent: SSS uses all three as stream IDs, SSR
    // uses rs1/rs2 as stream IDs and rd as a GPR destination, while CFG/RRS
    // keeps its register fields as ordinary GPR operands. useBuffer selects
    // only the fields that actually carry stream IDs.
    val encodedFifoId = VecInit(
        inst(15 + streamBits - 1, 15),
        inst(20 + streamBits - 1, 20),
        inst(7 + streamBits - 1, 7)
    )

    /*  STREAM OP:
        4bit指示stream op
        由funct3 funct7共同decode得到
    */
    io.sinfo.op := 0.U(1.W) ## funct3 //默认情况
    when(funct3 === 0x0.U){ //CFGI
        io.sinfo.op := MuxCase(CFGI, Seq(
            (funct7 === 0x1.U) -> CFGILIMIT,
            (funct7 === 0x2.U) -> CFGIREPEAT,
            (funct7 === 0x3.U) -> CFGIOFFSET
        ))
    }.elsewhen(funct3 === 0x2.U){ //CALSTREAM
        io.sinfo.op := MuxCase(CALSTREAM, Seq(
            (funct7 === 0x1.U) -> CALSTREAMPP,
            (funct7 === 0x40.U) -> VADD8,
            (funct7 === 0x48.U) -> VSUB8,
            (funct7 === 0x50.U) -> VMUL8
        ))
    }.elsewhen(funct3 === 0x3.U){ // stream address descriptor
        io.sinfo.op := MuxCase(CFGSTRIDE, Seq(
            (funct7 === 0x1.U) -> CFGTILESTRIDE,
            (funct7 === 0x2.U) -> CFGDIMSIZE,
            (funct7 === 0x3.U) -> CFGDIMSTRIDE
        ))
    }.elsewhen(funct3 === 0x4.U){ //CFGREUSE
        io.sinfo.op := Mux(funct7 === 0x1.U, CFGREUSEEMPTY, CFGREUSE)
    }.elsewhen(funct3 === 0x6.U) {
        io.sinfo.op := MuxCase(CALRJRKSTREAMPP, Seq(
            (funct7 === 0x40.U) -> VOMAC8,
            (funct7 === 0x41.U) -> VCLR,
            (funct7 === 0x42.U) -> VREGOUT8
        ))
    }.elsewhen(funct3 === 0x7.U) {
        io.sinfo.op := Mux(funct7 === 0x60.U, VDOT8, CALSTREAMRD)
    }
    io.sinfo.state(DONECFG) := isStream
    io.sinfo.state(LDSTRAEM) := io.sinfo.op === CFGLOAD
    io.sinfo.state(LDAXISTREAM) := io.sinfo.op === CFGLOAD && funct7 === 0x1.U
    val isOuter = isStream && StreamOuter.isVector(io.sinfo.op)
    val isCalStream  = isStream && (io.sinfo.op === CALSTREAM || io.sinfo.op === CALSTREAMRD || io.sinfo.op === CALSTREAMPP || io.sinfo.op === CALRJRKSTREAMPP || isOuter)
    val isCalStreamRJRK = isStream && io.sinfo.op === CALRJRKSTREAMPP
    val isCalStreamRD = isStream && io.sinfo.op === CALSTREAMRD
    val isCalStreamNRD = isCalStream && !isCalStreamRD
    val isCfgStream  = isStream && !isCalStream
    for (i <- 0 until 2) { //rs1 rs2
        io.sinfo.useBuffer(i) := isCalStream && !isCalStreamRJRK && (!isOuter || StreamOuter.readsStream(io.sinfo.op))
    }       
    io.isCalStream := isCalStream
    io.needStreamSrc := isCalStream && (io.sinfo.op =/= CALRJRKSTREAMPP)
    io.sinfo.useBuffer(2)   := isStream && io.sinfo.op === CALSTREAM // RD写通用寄存器，另外两条指令写PPbuffer
    io.sinfo.usePPBuffer(Even) := isStream && io.sinfo.op === CALSTREAMPP
    io.sinfo.usePPBuffer(Odd) := isStream && io.sinfo.op === CALRJRKSTREAMPP
    when(isOuter) {
        io.sinfo.useBuffer(2) := StreamOuter.writesStream(io.sinfo.op)
    }
    val vectorSources = StreamOuter.isElementwise(io.sinfo.op) || io.sinfo.op === VDOT8
    io.sinfo.words := VecInit(Mux(vectorSources, 8.U(4.W), 1.U(4.W)),
        Mux(vectorSources || io.sinfo.op === VOMAC8, 8.U(4.W), 1.U(4.W)),
        Mux(StreamOuter.writesStream(io.sinfo.op), 8.U(4.W), 1.U(4.W)))
    io.sinfo.bank := Mux(io.sinfo.op === VREGOUT8, inst(17, 15), inst(9, 7))
    for (i <- 0 until 3) {
        io.sinfo.fifoId(i) := Mux(io.sinfo.useBuffer(i), encodedFifoId(i), 0.U)
    }
    val isStreamMul = isCalStream && !isCalStreamRJRK && io.sinfo.op =/= CALSTREAMPP && funct7 === 16.U
    val isStreamAlu = isCalStream && !isStreamMul && !isOuter
    val isStreamMdu = isStream && !isStreamAlu

    /* op: 
        bit6: indicates src1 source, 0-reg 1-pc, or indicates store, 1-store, 0-not
        bit5: indicates src2 source, 1-reg 0-imm, or indicates load, 1-load, 0-not
        bit4: indicates is branch or jump
        bit3-0: alu operation or memory operation(bit 3 indicates atom)
    */
    val op_6 = isJal || isJalr || isAuipc || isStore
    val op_5 = isAlgebraReg || isLoad
    val op_4 = isBr || isJal || isJalr
    val op_3_0 = Mux1H(Seq(
        (isAlgebraReg || isMuldiv)  -> funct7(5) ## funct3,
        isAlgebraImm  -> Mux(funct3 === 0x5.U, funct7(5) ## funct3, 0.U(1.W) ## funct3),
        isJalr        -> JALR(3, 0),
        isJal         -> JAL(3, 0),
        isBr          -> 1.U(1.W) ## funct3,
        isMem         -> isAtom ## funct3,
        isCalStreamRD -> funct7(3,0), // SSR add/sub/mul
        (isCalStreamNRD && isCalStreamRJRK) -> 6.U, // RRS currently uses or
        (isCalStreamNRD && io.sinfo.op === CALSTREAMPP) -> 0.U, // SSP funct7 selects PP, not ALU op
        (isCalStreamNRD && io.sinfo.op === CALSTREAM) -> funct7(3,0) // SSS add/sub/mul
    ))
    io.op := op_6 ## op_5 ## op_4 ## op_3_0

    /* imm */
    val IType = isAlgebraImm || isLoad || isJalr
    val SType = isStore
    val JType = isJal
    val UType = isLui || isAuipc
    val BType = isBr
    val imm   = Mux1H(Seq(
        IType   -> SE(inst(31, 20)),
        UType   -> inst(31, 12) ## 0.U(12.W),
        JType   -> SE(inst(31) ## inst(19, 12) ## inst(20) ## inst(30, 21) ## 0.U(1.W)),
        BType   -> SE(inst(31) ## inst(7) ## inst(30, 25) ## inst(11, 8) ## 0.U(1.W)),
        SType   -> SE(inst(31, 25) ## inst(11, 7)),
        // priv: bit11-0 is csr, bit 16-12 is uimm
        isPriv  -> ZE(inst(19, 15) ## inst(31, 20))
    ))
    io.imm := imm

    io.func := isMem ## (isMuldiv || isPriv || isStreamMdu) ## !(isMem || isMuldiv || isPriv || isStreamMdu)

}
