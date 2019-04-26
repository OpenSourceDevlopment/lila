package lidraughts.simul

import draughts.variant.Variant
import lidraughts.user.User
import org.joda.time.DateTime
import ornicar.scalalib.Random

case class Simul(
    _id: Simul.ID,
    name: String,
    status: SimulStatus,
    clock: SimulClock,
    applicants: List[SimulApplicant],
    pairings: List[SimulPairing],
    variants: List[Variant],
    allowed: Option[List[String]] = None,
    createdAt: DateTime,
    hostId: String,
    hostRating: Int,
    hostTitle: Option[String],
    hostGameId: Option[String], // game the host is focusing on
    startedAt: Option[DateTime],
    finishedAt: Option[DateTime],
    hostSeenAt: Option[DateTime],
    color: Option[String],
    chatmode: Option[Simul.ChatMode],
    arbiterId: Option[String] = None,
    spotlight: Option[Spotlight] = None,
    targetPct: Option[Int] = None
) {

  def id = _id

  def fullName = s"$name simul"

  def isCreated = !isStarted

  def isStarted = startedAt.isDefined

  def isFinished = status == SimulStatus.Finished

  def isRunning = status == SimulStatus.Started

  def isArbiter(userId: String) = arbiterId.??(userId ==)

  def hasParticipant(userId: String) = hostId == userId || hasPairing(userId)

  def hasApplicant(userId: String) = applicants.exists(_ is userId)

  def hasPairing(userId: String) = pairings.exists(_ is userId)

  def hasUser(userId: String) = hasApplicant(userId) || hasPairing(userId)

  def isPlaying(userId: String) = hostId == userId || pairings.exists(p => p.ongoing && p.is(userId))

  def isUnique = spotlight.isDefined

  def canHaveChat(user: Option[User]): Boolean = user ?? { u => canHaveChat(u.id) }

  def canHaveChat(userId: String): Boolean =
    if (!isRunning || isArbiter(userId)) true
    else chatmode match {
      case Some(Simul.ChatMode.Participants) => hasParticipant(userId)
      case Some(Simul.ChatMode.Spectators) => !isPlaying(userId)
      case _ => true
    }

  def addApplicant(applicant: SimulApplicant) = Created {
    if (!hasApplicant(applicant.player.user) && variants.has(applicant.player.variant))
      copy(applicants = applicants :+ applicant)
    else this
  }

  def removeApplicant(userId: String) = Created {
    copy(applicants = applicants filterNot (_ is userId))
  }

  def accept(userId: String, v: Boolean) = Created {
    copy(applicants = applicants map {
      case a if a is userId => a.copy(accepted = v)
      case a => a
    })
  }

  def canJoin(userId: String) = allowed match {
    case Some(allowedList) if allowedList.nonEmpty => allowedList.exists(userId ==)
    case _ => true
  }

  def allow(userId: String) = Created {
    val currentIds = ~allowed
    if (!currentIds.exists(userId ==))
      copy(allowed = (currentIds :+ userId).some)
    else this
  }

  def disallow(userId: String) = Created {
    val newIds = ~allowed filterNot (userId ==)
    copy(allowed = if (newIds.isEmpty) none else newIds.some)
  }

  def removePairing(userId: String) =
    copy(pairings = pairings filterNot (_ is userId)).finishIfDone

  def nbAccepted = applicants.count(_.accepted)

  def startable = isCreated && nbAccepted > 1

  def start = startable option copy(
    status = SimulStatus.Started,
    startedAt = DateTime.now.some,
    applicants = Nil,
    pairings = applicants collect {
      case a if a.accepted => SimulPairing(a.player)
    },
    hostSeenAt = none
  )

  def updatePairing(gameId: String, f: SimulPairing => SimulPairing) = copy(
    pairings = pairings collect {
      case p if p.gameId == gameId => f(p)
      case p => p
    }
  ).finishIfDone

  def ejectCheater(userId: String): Option[Simul] =
    hasUser(userId) option removeApplicant(userId).removePairing(userId)

  private def finishIfDone =
    if (pairings.forall(_.finished))
      copy(
        status = SimulStatus.Finished,
        finishedAt = DateTime.now.some,
        hostGameId = none
      )
    else this

  def gameIds = pairings.map(_.gameId)

  def perfTypes: List[lidraughts.rating.PerfType] = variants.flatMap { variant =>
    lidraughts.game.PerfPicker.perfType(
      speed = draughts.Speed(clock.config.some),
      variant = variant,
      daysPerTurn = none
    )
  }

  def applicantRatio = s"${applicants.count(_.accepted)}/${applicants.size}"

  def variantRich = variants.size > 3

  def isHost(userOption: Option[User]): Boolean = userOption ?? isHost
  def isHost(user: User): Boolean = user.id == hostId

  def playingPairings = pairings filterNot (_.finished)

  def hostColor = (color flatMap draughts.Color.apply) | draughts.Color(scala.util.Random.nextBoolean)

  def setPairingHostColor(gameId: String, hostColor: draughts.Color) =
    updatePairing(gameId, _.copy(hostColor = hostColor))

  def isNotBrandNew = createdAt isBefore DateTime.now.minusSeconds(10)

  private def Created(s: => Simul): Simul = if (isCreated) s else this

  def spotlightable = featureUnique ||
    (isCreated && (
      (hostRating >= 2400 || hostTitle.isDefined) &&
      applicants.size < 80
    ))

  private def featureUnique = spotlight match {
    case Some(spot) if isRunning => true
    case Some(spot) if isCreated => spot.startsAt minusHours ~spot.homepageHours isBefore DateTime.now
    case _ => false
  }

  def wins = pairings.count(p => p.finished && p.wins.has(false))
  def draws = pairings.count(p => p.finished && p.wins.isEmpty)
  def losses = pairings.count(p => p.finished && p.wins.has(true))
  def ongoing = pairings.count(_.ongoing)
  def finished = pairings.count(_.finished)

  def currentPct =
    if (finished == 0) 0.0
    else 100 * (wins + draws * 0.5) / finished
  def currentPctStr = {
    val pct = currentPct
    val pctDec = pct - Math.floor(pct)
    if (pctDec < 0.05 || pctDec > 0.95)
      "%.0f".format(pct) + '%'
    else
      "%.1f".format(pct) + '%'
  }

  private def requiredPoints(target: Double) = pairings.length * (target / 100d) - (wins + draws * 0.5)
  def targetReached = targetPct.fold(false)(requiredPoints(_) <= 0)
  def targetFailed = targetPct.fold(false)(requiredPoints(_) > ongoing)

  def requiredWins = targetPct flatMap { target =>
    val remaining = requiredPoints(target)
    if (remaining > 0.5) {
      val remainingDecimal = remaining - Math.floor(remaining)
      if (remainingDecimal > 0.5)
        Math.ceil(remaining).toInt.some
      else
        Math.floor(remaining).toInt.some
    } else none
  }
  def requiredDraws = targetPct flatMap { target =>
    val remaining = requiredPoints(target)
    if (remaining > 0) {
      val remainingDecimal = remaining - Math.floor(remaining)
      (remainingDecimal == 0 || remainingDecimal > 0.5).fold(none, 1.some)
    } else none
  }

}

object Simul {

  type ID = String

  case class OnStart(simul: Simul)

  sealed trait ChatMode {
    lazy val key = toString.toLowerCase
  }
  object ChatMode {
    case object Everyone extends ChatMode
    case object Spectators extends ChatMode
    case object Participants extends ChatMode
    val byKey = List(Everyone, Spectators, Participants).map { v => v.key -> v }.toMap
  }

  private def makeName(host: User) =
    if (host.title.isDefined) host.titleUsername
    else RandomName()

  def make(
    host: User,
    clock: SimulClock,
    variants: List[Variant],
    color: String,
    chatmode: String,
    targetPct: Option[Int]
  ): Simul = Simul(
    _id = Random nextString 8,
    name = makeName(host),
    status = SimulStatus.Created,
    clock = clock,
    hostId = host.id,
    hostRating = host.perfs.bestRatingIn {
      variants flatMap { variant =>
        lidraughts.game.PerfPicker.perfType(
          speed = draughts.Speed(clock.config.some),
          variant = variant,
          daysPerTurn = none
        )
      }
    },
    hostTitle = host.title,
    hostGameId = none,
    createdAt = DateTime.now,
    variants = variants,
    applicants = Nil,
    pairings = Nil,
    startedAt = none,
    finishedAt = none,
    hostSeenAt = DateTime.now.some,
    color = color.some,
    chatmode = ChatMode.byKey get chatmode,
    targetPct = targetPct
  )
}
