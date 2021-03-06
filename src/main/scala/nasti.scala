// See LICENSE for license details.

package junctions
import Chisel._
import scala.math.max

case object BusId extends Field[String]
case object NASTIDataBits extends Field[Int]
case object NASTIAddrBits extends Field[Int]
case object NASTIIdBits extends Field[Int]
case object NASTIUserBits extends Field[Int]

trait NASTIParameters extends UsesParameters {
  val nastiXDataBits = params(NASTIDataBits)
  val nastiWStrobeBits = nastiXDataBits / 8
  val nastiXOffBits = log2Up(nastiWStrobeBits)
  val nastiXAddrBits = params(NASTIAddrBits)
  val nastiXIdBits = params(NASTIIdBits)
  val nastiXUserBits = params(NASTIUserBits)
  val nastiXLenBits = 8
  val nastiXSizeBits = 3
  val nastiXBurstBits = 2
  val nastiXCacheBits = 4
  val nastiXProtBits = 3
  val nastiXQosBits = 4
  val nastiXRegionBits = 4
  val nastiXRespBits = 2

  def bytesToXSize(bytes: UInt) = MuxLookup(bytes, UInt("b111"), Array(
    UInt(1) -> UInt(0),
    UInt(2) -> UInt(1),
    UInt(4) -> UInt(2),
    UInt(8) -> UInt(3),
    UInt(16) -> UInt(4),
    UInt(32) -> UInt(5),
    UInt(64) -> UInt(6),
    UInt(128) -> UInt(7)))

  def opSizeToXSize(s: UInt) = MuxLookup(s, UInt("b111"), Array(
    MT_B  -> UInt(0),
    MT_H  -> UInt(1),
    MT_W  -> UInt(2),
    MT_D  -> UInt(3),
    MT_BU -> UInt(0),
    MT_HU -> UInt(1),
    MT_WU -> UInt(2)))
}

abstract class NASTIBundle extends Bundle with NASTIParameters
abstract class NASTIModule extends Module with NASTIParameters

trait NASTIChannel extends NASTIBundle
trait NASTIMasterToSlaveChannel extends NASTIChannel
trait NASTISlaveToMasterChannel extends NASTIChannel

//---------------------- fields ------------------------//
class NASTIMasterIO extends Bundle {
  val aw = Decoupled(new NASTIWriteAddressChannel)
  val w  = Decoupled(new NASTIWriteDataChannel)
  val b  = Decoupled(new NASTIWriteResponseChannel).flip
  val ar = Decoupled(new NASTIReadAddressChannel)
  val r  = Decoupled(new NASTIReadDataChannel).flip
}

class NASTILiteMasterIO extends Bundle {
  val aw = Decoupled(new NASTILiteWriteAddressChannel)
  val w  = Decoupled(new NASTILiteWriteDataChannel)
  val b  = Decoupled(new NASTILiteWriteResponseChannel).flip
  val ar = Decoupled(new NASTILiteReadAddressChannel)
  val r  = Decoupled(new NASTILiteReadDataChannel).flip
}

class NASTISlaveIO extends NASTIMasterIO { flip() }
class NASTILiteSlaveIO extends NASTILiteMasterIO { flip() }

// the detailed field definitions

trait HasNASTIId extends NASTIBundle {
  val id   = UInt(width = nastiXIdBits)
}

trait HasNASTIMetadata extends NASTIBundle {
  val len    = UInt(width = nastiXLenBits)
  val size   = UInt(width = nastiXSizeBits)
  val burst  = UInt(width = nastiXBurstBits)
  val lock   = Bool()
  val cache  = UInt(width = nastiXCacheBits)
}

trait HasNASTIData extends NASTIBundle {
  val data = UInt(width = nastiXDataBits)
}

trait HasWStrb extends NASTIBundle {
  val strb = UInt(width = nastiWStrobeBits)
}

trait HasNASTILast extends NASTIBundle {
  val last = Bool()
}

trait HasNASTIUser extends NASTIBundle {
  val user = UInt(width = nastiXUserBits)
}

//---------------------- channels ------------------------//
class NASTIAddressChannel extends NASTIMasterToSlaveChannel with HasNASTIId with HasNASTIUser {
  val addr   = UInt(width = nastiXAddrBits)
  val prot   = UInt(width = nastiXProtBits)
  val qos    = UInt(width = nastiXQosBits)
  val region = UInt(width = nastiXRegionBits)
}

class NASTIResponseChannel extends NASTISlaveToMasterChannel with HasNASTIId with HasNASTIUser {
  val resp = UInt(width = nastiXRespBits)
}

class NASTILiteWriteAddressChannel extends NASTIAddressChannel
class NASTIWriteAddressChannel extends NASTILiteWriteAddressChannel with HasNASTIMetadata

class NASTILiteReadAddressChannel extends NASTIAddressChannel
class NASTIReadAddressChannel extends NASTILiteReadAddressChannel with HasNASTIMetadata

class NASTILiteWriteDataChannel extends NASTIMasterToSlaveChannel with HasNASTIData with HasNASTIUser {
  val strb = UInt(width = nastiWStrobeBits)
}
class NASTIWriteDataChannel extends NASTILiteWriteDataChannel with HasNASTILast

class NASTILiteWriteResponseChannel extends NASTIResponseChannel
class NASTIWriteResponseChannel extends NASTILiteWriteResponseChannel

class NASTILiteReadDataChannel extends NASTIResponseChannel with HasNASTIData
class NASTIReadDataChannel extends NASTILiteReadDataChannel with HasNASTILast

//---------------------- Converters ------------------------//
class MemIONASTISlaveIOConverter(cacheBlockOffsetBits: Int) extends MIFModule with NASTIParameters {
  val io = new Bundle {
    val nasti = new NASTISlaveIO
    val mem = new MemIO
  }

  require(mifDataBits == nastiXDataBits, "Data sizes between LLC and MC don't agree")
  val (mif_cnt_out, mif_wrap_out) = Counter(io.mem.resp.fire(), mifDataBeats)
  
  io.mem.req_cmd.bits.addr := Mux(io.nasti.aw.valid, io.nasti.aw.bits.addr, io.nasti.ar.bits.addr) >>
                                UInt(cacheBlockOffsetBits)
  io.mem.req_cmd.bits.tag := Mux(io.nasti.aw.valid, io.nasti.aw.bits.id, io.nasti.ar.bits.id)
  io.mem.req_cmd.bits.rw := io.nasti.aw.valid
  io.mem.req_cmd.valid := (io.nasti.aw.valid && io.nasti.b.ready) || io.nasti.ar.valid
  io.nasti.ar.ready := io.mem.req_cmd.ready && !io.nasti.aw.valid
  io.nasti.aw.ready := io.mem.req_cmd.ready && io.nasti.b.ready

  io.nasti.b.valid := io.nasti.aw.valid && io.mem.req_cmd.ready
  io.nasti.b.bits.id := io.nasti.aw.bits.id
  io.nasti.b.bits.resp := UInt(0)

  io.nasti.w.ready := io.mem.req_data.ready
  io.mem.req_data.valid := io.nasti.w.valid
  io.mem.req_data.bits.data := io.nasti.w.bits.data
  assert(!io.nasti.w.valid || io.nasti.w.bits.strb.andR, "MemIO must write full cache line")

  io.nasti.r.valid := io.mem.resp.valid
  io.nasti.r.bits.data := io.mem.resp.bits.data
  io.nasti.r.bits.last := mif_wrap_out
  io.nasti.r.bits.id := io.mem.resp.bits.tag
  io.nasti.r.bits.resp := UInt(0)
  io.mem.resp.ready := io.nasti.r.ready
}

// a NASTI pipeline stage sometimes used to break critical path
class NASTIPipe extends NASTIModule {
  val io = new Bundle {
    val slave = new NASTISlaveIO
    val master = new NASTIMasterIO
  }

  val awPipe = Module(new DecoupledPipe(io.slave.aw.bits))
  awPipe.io.pi <> io.slave.aw
  awPipe.io.po <> io.master.aw

  val wPipe = Module(new DecoupledPipe(io.slave.w.bits))
  wPipe.io.pi <> io.slave.w
  wPipe.io.po <> io.master.w

  val bPipe = Module(new DecoupledPipe(io.slave.b.bits))
  bPipe.io.pi <> io.master.b
  bPipe.io.po <> io.slave.b

  val arPipe = Module(new DecoupledPipe(io.slave.ar.bits))
  arPipe.io.pi <> io.slave.ar
  arPipe.io.po <> io.master.ar

  val rPipe = Module(new DecoupledPipe(io.master.r.bits))
  rPipe.io.pi <> io.master.r
  rPipe.io.po <> io.slave.r

}

