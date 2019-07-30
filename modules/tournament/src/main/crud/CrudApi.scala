package lidraughts.tournament
package crud

import lidraughts.user.User

final class CrudApi {

  def list = TournamentRepo uniques 50

  def one(id: String) = TournamentRepo uniqueById id

  def editForm(tour: Tournament) = CrudForm.apply fill CrudForm.Data(
    name = tour.name,
    homepageHours = ~tour.spotlight.flatMap(_.homepageHours),
    clockTime = tour.clock.limitInMinutes,
    clockIncrement = tour.clock.incrementSeconds,
    minutes = tour.minutes,
    variant = tour.variant.key,
    position = tour.position.fen,
    date = tour.startsAt,
    image = ~tour.spotlight.flatMap(_.iconImg),
    headline = tour.spotlight.??(_.headline),
    description = tour.spotlight.??(_.description),
    conditions = Condition.DataForm.AllSetup(tour.conditions),
    berserkable = !tour.noBerserk
  )

  def update(old: Tournament, data: CrudForm.Data) =
    TournamentRepo update updateTour(old, data) void

  def createForm = CrudForm.apply

  def create(data: CrudForm.Data, owner: User): Fu[Tournament] = {
    val tour = updateTour(empty, data).copy(createdBy = owner.id)
    TournamentRepo insert tour inject tour
  }

  private def empty = Tournament.make(
    by = Left(User.lidraughtsId),
    name = none,
    clock = draughts.Clock.Config(0, 0),
    minutes = 0,
    system = System.Arena,
    variant = draughts.variant.Standard,
    position = draughts.StartingPosition.initial,
    mode = draughts.Mode.Rated,
    `private` = false,
    password = None,
    waitMinutes = 0,
    berserkable = true
  )

  private def updateTour(tour: Tournament, data: CrudForm.Data) = {
    import data._
    val clock = draughts.Clock.Config((clockTime * 60).toInt, clockIncrement)
    tour.copy(
      name = name,
      clock = clock,
      minutes = minutes,
      variant = realVariant,
      startsAt = date,
      schedule = Schedule(
        freq = Schedule.Freq.Unique,
        speed = Schedule.Speed.fromClock(clock),
        variant = realVariant,
        position = draughts.StartingPosition.initial,
        at = date
      ).some,
      spotlight = Spotlight(
        headline = headline,
        description = description,
        homepageHours = homepageHours.some.filterNot(0 ==),
        iconFont = none,
        iconImg = image.some.filter(_.nonEmpty)
      ).some,
      position = DataForm.startingPosition(data.position, realVariant),
      noBerserk = !data.berserkable
    ) |> { tour =>
        tour.perfType.fold(tour) { perfType =>
          tour.copy(conditions = data.conditions.convert(perfType, Map.empty)) // the CRUD form doesn't support team restrictions so Map.empty is fine
        }
      }
  }
}
