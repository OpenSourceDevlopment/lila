package lidraughts.tournament

import org.joda.time.DateTime
import reactivemongo.api.ReadPreference
import scala.concurrent.duration._

import lidraughts.db.dsl._
import lidraughts.rating.PerfType
import lidraughts.user.User

final class TournamentShieldApi(
    coll: Coll,
    asyncCache: lidraughts.memo.AsyncCache.Builder
) {

  import TournamentShield._
  import BSONHandlers._

  def active(u: User): Fu[List[Award]] = cache.get map {
    _.value.values.flatMap(_.headOption.filter(_.owner.value == u.id)).toList
  }

  def history(maxPerCateg: Option[Int]): Fu[History] = cache.get map { h =>
    maxPerCateg.fold(h)(h.take)
  }

  def byCategKey(k: String): Fu[Option[(Category, List[Award])]] =
    Category.byKey(k).fold(fuccess(none[(Category, List[Award])])) { categ =>
      cache.get map { history =>
        Some(categ -> history.value.getOrElse(categ, Nil))
      }
    }

  def currentOwner(tour: Tournament): Fu[Option[OwnerId]] = tour.isShield ?? {
    Category.of(tour) ?? { cat =>
      history(none).map(_.current(cat).map(_.owner))
    }
  }

  private[tournament] def clear = cache.refresh

  private val cache = asyncCache.single[History](
    name = "tournament.shield",
    expireAfter = _.ExpireAfterWrite(1 day),
    f = coll.find($doc(
      "schedule.freq" -> scheduleFreqHandler.write(Schedule.Freq.Shield),
      "status" -> statusBSONHandler.write(Status.Finished)
    )).sort($sort asc "startsAt").list[Tournament](none, ReadPreference.secondaryPreferred) map { tours =>
      for {
        tour <- tours
        categ <- Category of tour
        winner <- tour.winnerId
      } yield Award(
        categ = categ,
        owner = OwnerId(winner),
        date = tour.finishesAt,
        tourId = tour.id
      )
    } map {
      _.foldLeft(Map.empty[Category, List[Award]]) {
        case (hist, entry) => hist + (entry.categ -> hist.get(entry.categ).fold(List(entry))(entry :: _))
      }
    } map History.apply
  )
}

object TournamentShield {

  case class OwnerId(value: String) extends AnyVal

  case class Award(
      categ: Category,
      owner: OwnerId,
      date: DateTime,
      tourId: Tournament.ID
  )
  // newer entry first
  case class History(value: Map[Category, List[Award]]) {

    def sorted: List[(Category, List[Award])] = Category.all map { categ =>
      categ -> ~(value get categ)
    }

    def userIds: List[User.ID] = value.values.flatMap(_.map(_.owner.value)).toList

    def current(cat: Category): Option[Award] = value get cat flatMap (_.headOption)

    def take(max: Int) = copy(
      value = value.mapValues(_ take max)
    )
  }

  private type SpeedOrVariant = Either[Schedule.Speed, draughts.variant.Variant]

  sealed abstract class Category(
      val of: SpeedOrVariant,
      val iconChar: Char
  ) {
    def key = of.fold(_.name, _.key)
    def name = of.fold(_.toString, _.name)
    def matches(tour: Tournament) =
      if (tour.variant.standard) ~(for {
        tourSpeed <- tour.schedule.map(_.speed)
        categSpeed <- of.left.toOption
      } yield tourSpeed == categSpeed)
      else of.right.toOption.has(tour.variant)
  }

  object Category {

    case object HyperBullet extends Category(
      of = Left(Schedule.Speed.HyperBullet),
      iconChar = 'T'
    )

    case object Bullet extends Category(
      of = Left(Schedule.Speed.Bullet),
      iconChar = 'T'
    )

    case object SuperBlitz extends Category(
      of = Left(Schedule.Speed.SuperBlitz),
      iconChar = ')'
    )

    case object Blitz extends Category(
      of = Left(Schedule.Speed.Blitz),
      iconChar = ')'
    )

    case object Rapid extends Category(
      of = Left(Schedule.Speed.Rapid),
      iconChar = '#'
    )

    case object Frisian extends Category(
      of = Right(draughts.variant.Frisian),
      iconChar = '\''
    )

    case object Frysk extends Category(
      of = Right(draughts.variant.Frysk),
      iconChar = '_'
    )

    case object Antidraughts extends Category(
      of = Right(draughts.variant.Antidraughts),
      iconChar = '@'
    )

    case object Breakthrough extends Category(
      of = Right(draughts.variant.Breakthrough),
      iconChar = ''
    )

    case object Russian extends Category(
      of = Right(draughts.variant.Russian),
      iconChar = ''
    )

    case object Brazilian extends Category(
      of = Right(draughts.variant.Brazilian),
      iconChar = ''
    )

    val all: List[Category] = List(Bullet, SuperBlitz, Blitz, Rapid, HyperBullet, Frisian, Frysk, Antidraughts, Breakthrough, Russian, Brazilian)

    def of(t: Tournament): Option[Category] = all.find(_ matches t)

    def byKey(k: String): Option[Category] = all.find(_.key == k)
  }

  def spotlight(name: String) = Spotlight(
    iconFont = "5".some,
    headline = s"Battle for the $name Shield",
    description = s"""This Shield trophy is unique.
The winner keeps it for one month,
then must defend it during the next $name Shield tournament!""",
    homepageHours = 6.some
  )
}
