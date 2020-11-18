package lidraughts.swiss

import scala.concurrent.duration._

import draughts.Clock.{ Config => ClockConfig }
import draughts.Color
import draughts.variant.Variant
import lidraughts.db.BSON
import lidraughts.db.dsl._
import lidraughts.user.User
import reactivemongo.bson._

private object BsonHandlers {

  implicit val clockHandler = new BSONHandler[BSONDocument, ClockConfig] {
    def read(doc: BSONDocument) = ClockConfig(
      doc.getAs[Int]("limit").get,
      doc.getAs[Int]("increment").get
    )

    def write(config: ClockConfig) = BSONDocument(
      "limit" -> config.limitSeconds,
      "increment" -> config.incrementSeconds
    )
  }
  implicit val variantHandler = new BSONHandler[BSONInteger, Variant] {
    def read(b: BSONInteger): Variant = Variant.orDefault(b.value)
    def write(x: Variant) = BSONInteger(x.id)
  }
  implicit val swissPointsHandler = intAnyValHandler[Swiss.Points](_.double, Swiss.Points.apply)
  implicit val swissTieBreakHandler = doubleAnyValHandler[Swiss.TieBreak](_.value, Swiss.TieBreak.apply)
  implicit val swissPerformanceHandler = doubleAnyValHandler[Swiss.Performance](_.value, Swiss.Performance.apply)
  implicit val swissScoreHandler = intAnyValHandler[Swiss.Score](_.value, Swiss.Score.apply)
  implicit val roundNumberHandler = intAnyValHandler[SwissRound.Number](_.value, SwissRound.Number.apply)
  implicit val swissIdHandler = stringAnyValHandler[Swiss.Id](_.value, Swiss.Id.apply)
  implicit val playerIdHandler = stringAnyValHandler[SwissPlayer.Id](_.value, SwissPlayer.Id.apply)

  implicit val playerHandler = new BSON[SwissPlayer] {
    import SwissPlayer.Fields._
    def reads(r: BSON.Reader) = SwissPlayer(
      id = r.get[SwissPlayer.Id](id),
      swissId = r.get[Swiss.Id](swissId),
      userId = r str userId,
      rating = r int rating,
      provisional = r boolD provisional,
      points = r.get[Swiss.Points](points),
      tieBreak = r.get[Swiss.TieBreak](tieBreak),
      performance = r.getO[Swiss.Performance](performance),
      score = r.get[Swiss.Score](score),
      absent = r.boolD(absent),
      byes = ~r.getO[Set[SwissRound.Number]](byes)
    )
    def writes(w: BSON.Writer, o: SwissPlayer) = $doc(
      id -> o.id,
      swissId -> o.swissId,
      userId -> o.userId,
      rating -> o.rating,
      provisional -> w.boolO(o.provisional),
      points -> o.points,
      tieBreak -> o.tieBreak,
      performance -> o.performance,
      score -> o.score,
      absent -> w.boolO(o.absent),
      byes -> o.byes.some.filter(_.nonEmpty)
    )
  }

  implicit val pairingStatusHandler: BSONHandler[BSONValue, SwissPairing.Status] = new BSONHandler[BSONValue, SwissPairing.Status] {
    def read(v: BSONValue): SwissPairing.Status = v match {
      case BSONBoolean(true) => Left(SwissPairing.Ongoing)
      case BSONInteger(index) => Right(Color(index == 0).some)
      case _ => Right(none)
    }
    def write(s: SwissPairing.Status) = s match {
      case Left(_) => BSONBoolean(true)
      case Right(Some(c)) => BSONInteger(c.fold(0, 1))
      case _ => BSONNull
    }
  }
  implicit val pairingHandler = new BSON[SwissPairing] {
    import SwissPairing.Fields._
    def reads(r: BSON.Reader) =
      r.get[List[User.ID]](players) match {
        case List(w, b) =>
          SwissPairing(
            id = r str id,
            swissId = r.get[Swiss.Id](swissId),
            round = r.get[SwissRound.Number](round),
            white = w,
            black = b,
            status = r.getO[SwissPairing.Status](status) | Right(none)
          )
        case _ => sys error "Invalid swiss pairing users"
      }
    def writes(w: BSON.Writer, o: SwissPairing) = $doc(
      id -> o.id,
      swissId -> o.swissId,
      round -> o.round,
      players -> o.players,
      status -> o.status
    )
  }

  implicit val settingsHandler = new BSON[Swiss.Settings] {
    def reads(r: BSON.Reader) = Swiss.Settings(
      nbRounds = r.get[Int]("n"),
      rated = r.boolO("r") | true,
      description = r.strO("d"),
      hasChat = r.boolO("c") | true,
      roundInterval = (r.intO("i") | 60).seconds
    )
    def writes(w: BSON.Writer, s: Swiss.Settings) = $doc(
      "n" -> s.nbRounds,
      "r" -> (!s.rated).option(false),
      "d" -> s.description,
      "c" -> (!s.hasChat).option(false),
      "i" -> s.roundInterval.toSeconds.toInt
    )
  }

  implicit val swissHandler = Macros.handler[Swiss]

  def addFeaturable(s: Swiss) =
    swissHandler.writeTry(s).get ++ {
      s.isNotFinished ?? $doc("featurable" -> true)
    }
}
