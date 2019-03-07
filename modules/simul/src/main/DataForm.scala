package lidraughts.simul

import play.api.data._
import play.api.data.Forms._

import lidraughts.common.Form._

final class DataForm {

  val clockTimes = (5 to 15 by 5) ++ (20 to 90 by 10) ++ (120 to 180 by 20)
  val clockTimeDefault = 20
  val clockTimeChoices = options(clockTimes, "%d minute{s}")

  val clockIncrements = (0 to 2 by 1) ++ (3 to 7) ++ (10 to 30 by 5) ++ (40 to 60 by 10) ++ (90 to 180 by 30)
  val clockIncrementDefault = 60
  val clockIncrementChoices = options(clockIncrements, "%d second{s}")

  val clockExtras = (0 to 15 by 5) ++ (20 to 60 by 10) ++ (90 to 120 by 30)
  val clockExtraChoices = options(clockExtras, "%d minute{s}")
  val clockExtraDefault = 0

  val colors = List("white", "random", "black")
  val colorChoices = List(
    "white" -> "White",
    "random" -> "Random",
    "black" -> "Black"
  )
  val colorDefault = "white"

  def create = Form(mapping(
    "clockTime" -> numberIn(clockTimeChoices),
    "clockIncrement" -> numberIn(clockIncrementChoices),
    "clockExtra" -> numberIn(clockExtraChoices),
    "variants" -> list {
      number.verifying(Set(draughts.variant.Standard.id, draughts.variant.Frisian.id, draughts.variant.Frysk.id, draughts.variant.Antidraughts.id, draughts.variant.Breakthrough.id) contains _)
    }.verifying("At least one variant", _.nonEmpty),
    "color" -> stringIn(colorChoices)
  )(SimulSetup.apply)(SimulSetup.unapply)) fill SimulSetup(
    clockTime = clockTimeDefault,
    clockIncrement = clockIncrementDefault,
    clockExtra = clockExtraDefault,
    variants = List(draughts.variant.Standard.id),
    color = colorDefault
  )
}

case class SimulSetup(
    clockTime: Int,
    clockIncrement: Int,
    clockExtra: Int,
    variants: List[Int],
    color: String
)
