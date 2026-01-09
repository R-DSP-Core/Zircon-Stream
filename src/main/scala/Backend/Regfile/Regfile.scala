import chisel3._
import chisel3.util._
import ZirconConfig.RegisterFile._
import ZirconConfig.Issue._
import ZirconUtil._

class RegfileRDIO extends Bundle{
    val prj     = Input(UInt(wpreg.W))
    val prk     = Input(UInt(wpreg.W))
    val prjData = Output(UInt(32.W))
    val prkData = Output(UInt(32.W))
}
class RegfileWRIO extends Bundle{
    val prd     = Input(UInt(wpreg.W))
    val prdVld  = Input(Bool())
    val prdData = Input(UInt(32.W))
}

// class RegfilePredictIO extends Bundle {
//     val pra        = Input(UInt(wpreg.W))
//     val praData    = Output(UInt(32.W))
// }

class RegfileDBGIO extends Bundle{
    val rf = Output(Vec(npreg, UInt(32.W)))
}

class RegfileSingleIO extends Bundle{
    val rd = new RegfileRDIO
    val wr = new RegfileWRIO
}

class Regfile extends Module {
        val io  = IO(new Bundle {
        val original = Vec(nis, new RegfileSingleIO)
        val tcmwr = new RegfileWRIO
    })
    // val predictIO = IO(new RegfilePredictIO)
    val dbg = IO(new RegfileDBGIO)

    val regfile = RegInit(VecInit.tabulate(npreg)(i => 0.U(32.W)))

    for(i <- 0 until nis){
        io.original(i).rd.prjData := WFirstRead(regfile(io.original(i).rd.prj), io.original(i).rd.prj, io.original.map(_.wr.prd), io.original.map(_.wr.prdData), io.original.map(_.wr.prdVld))
        io.original(i).rd.prkData := WFirstRead(regfile(io.original(i).rd.prk), io.original(i).rd.prk, io.original.map(_.wr.prd), io.original.map(_.wr.prdData), io.original.map(_.wr.prdVld))
        when(io.original(i).wr.prdVld){
            regfile(io.original(i).wr.prd) := io.original(i).wr.prdData
        }
    }
    when(io.tcmwr.prdVld){
        regfile(io.tcmwr.prd) := io.tcmwr.prdData
    }
    // predictIO.praData := ShiftRegister(regfile(predictIO.pra), 1, 0.U, true.B)
    dbg.rf := regfile
}