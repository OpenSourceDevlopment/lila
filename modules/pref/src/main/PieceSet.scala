package lidraughts.pref

sealed class PieceSet private[pref] (val name: String) {

  override def toString = name

  def cssClass = name
}

sealed trait PieceSetObject {

  val all: List[PieceSet]

  val default: PieceSet

  lazy val allByName = all map { c => c.name -> c } toMap

  def apply(name: String) = allByName.getOrElse(name, default)

  def contains(name: String) = allByName contains name
}

object PieceSet extends PieceSetObject {

  val default = new PieceSet("wide_crown")

  val all = List(
    default.name, "wide", "narrow_edge", "narrow", "fabirovsky", "flat", "phin", "ringed", "basic", "frisianovsky", "eightbit"
  ) map { name => new PieceSet(name) }
}

