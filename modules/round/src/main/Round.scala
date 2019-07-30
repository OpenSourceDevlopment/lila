package lidraughts.round

import akka.actor._
import akka.pattern.ask
import org.joda.time.DateTime

import scala.concurrent.duration._
import actorApi._
import round._
import draughts.{ Color, Pos }
import draughts.format.Uci
import lidraughts.game.{ Event, Game, Pov, Progress }
import lidraughts.hub.actorApi.DeployPost
import lidraughts.hub.actorApi.map._
import lidraughts.hub.actorApi.round.{ DraughtsnetPlay, BotPlay, RematchYes, RematchNo, Abort, Resign }
import lidraughts.hub.SequentialActor
import lidraughts.socket.UserLagCache
import makeTimeout.large

private[round] final class Round(
    dependencies: Round.Dependencies,
    gameId: String,
    /* Send a message to self,
     * but by going through the actor map,
     * so this actor is spawned again if it had died/expired */
    awakeWith: Any => Unit
) extends SequentialActor {

  import dependencies._

  context setReceiveTimeout activeTtl

  implicit val proxy = new GameProxy(gameId)

  override def preStart(): Unit = {
    context.system.lidraughtsBus.subscribe(self, 'deploy)
    scheduleExpiration
  }

  override def postStop(): Unit = {
    super.postStop()
    context.system.lidraughtsBus.unsubscribe(self)
  }

  var takebackSituation = Round.TakebackSituation()

  def process = {

    case ReceiveTimeout => fuccess {
      self ! SequentialActor.Terminate
    }

    case p: HumanPlay =>
      p.trace.finishFirstSegment()
      handleHumanPlay(p) { pov =>
        if (pov.game.outoftime(withGrace = true)) finisher.outOfTime(pov.game)
        else {
          recordLag(pov)
          player.human(p, self)(pov)
        }
      } >>- {
        p.trace.finish()
        lidraughts.mon.round.move.full.count()
        scheduleExpiration
      }

    case p: BotPlay =>
      handleBotPlay(p) { pov =>
        if (pov.game.outoftime(withGrace = true)) finisher.outOfTime(pov.game)
        else player.bot(p, self)(pov)
      } >>- scheduleExpiration

    case DraughtsnetPlay(uci, taken, currentFen) => handle { game =>
      if (taken.length > 2) {
        val takenList = (for { c <- 0 until taken.length by 2 } yield Pos.posAt(taken.slice(c, c + 2))).flatten.toList
        val takenSet = takenList.toSet
        val validMoves = game.variant.validMovesFrom(game.situation, uci.origDest._1, finalSquare = true)
        validMoves.find(
          move => move.dest == uci.origDest._2 && move.taken.fold(false) { _.toSet == takenSet }
        ) match {
            case Some(fullCapture) if fullCapture.captures && fullCapture.capture.get.size > 1 =>
              val captures = fullCapture.capture.get
              Uci(captures.last.key + captures.head.key) match {
                case Some(nextUci) =>
                  val newTaken = takenSet - fullCapture.taken.get.last
                  player.draughtsnet(game, Uci.Move(fullCapture.orig, captures.last), currentFen, self, context, (nextUci, newTaken.mkString).some)
                case _ =>
                  fufail(DraughtsnetError(s"Received invalid move $uci"))
              }
            case _ =>
              fufail(DraughtsnetError(s"Received invalid move $uci"))
          }
      } else
        player.draughtsnet(game, uci, currentFen, self, context)
    } >>- lidraughts.mon.round.move.full.count()

    case Abort(playerId) => handle(playerId) { pov =>
      pov.game.abortable ?? finisher.abort(pov)
    }

    case Resign(playerId) => handle(playerId) { pov =>
      pov.game.resignable ?? finisher.other(pov.game, _.Resign, Some(!pov.color))
    }

    case ResignAi => handleAi(proxy.game) { pov =>
      pov.game.resignable ?? finisher.other(pov.game, _.Resign, Some(!pov.color))
    }

    case GoBerserk(color) => handle(color) { pov =>
      pov.game.goBerserk(color) ?? { progress =>
        proxy.save(progress) >> proxy.invalidating(_ goBerserk pov) inject progress.events
      }
    }

    case ResignForce(playerId) => handle(playerId) { pov =>
      (pov.game.resignable && !pov.game.hasAi && pov.game.hasClock) ?? {
        socketHub ? Ask(pov.gameId, IsGone(!pov.color)) flatMap {
          case true => finisher.rageQuit(pov.game, Some(pov.color))
          case _ => fuccess(List(Event.Reload))
        }
      }
    }

    case DrawForce(playerId) => handle(playerId) { pov =>
      (pov.game.drawable && !pov.game.hasAi && pov.game.hasClock) ?? {
        socketHub ? Ask(pov.gameId, IsGone(!pov.color)) flatMap {
          case true => finisher.rageQuit(pov.game, None)
          case _ => fuccess(List(Event.Reload))
        }
      }
    }

    case ArbiterDraw => handle { game =>
      (game.isSimul && game.playable) ?? {
        finisher.other(game, _.Draw, None, Some(_.arbiterDraw))
      }
    }
    case ArbiterResign(color) => handle { game =>
      (game.isSimul && game.playable) ?? {
        finisher.other(game, _.Resign, Some(!color), Some(_.arbiterResign))
      }
    }

    // checks if any player can safely (grace) be flagged
    case QuietFlag => handle { game =>
      game.outoftime(withGrace = true) ?? finisher.outOfTime(game)
    }

    // flags a specific player, possibly without grace if self
    case ClientFlag(color, from) => handle { game =>
      (game.turnColor == color) ?? {
        val toSelf = from has game.player(color).id
        game.outoftime(withGrace = !toSelf) ?? finisher.outOfTime(game)
      }
    }

    // exceptionally we don't block nor publish events
    // if the game is abandoned, then nobody is around to see it
    // we can also terminate this actor
    case Abandon => fuccess {
      proxy withGame { game =>
        game.abandoned ?? {
          self ! PoisonPill
          if (game.abortable) finisher.other(game, _.Aborted, None)
          else finisher.other(game, _.Resign, Some(!game.player.color))
        }
      }
    }

    case DrawYes(playerRef) => handle(playerRef)(drawer.yes)
    case DrawNo(playerRef) => handle(playerRef)(drawer.no)
    case DrawClaim(playerId) => handle(playerId)(drawer.claim)
    case Cheat(color) => handle { game =>
      (game.playable && !game.imported) ?? {
        finisher.other(game, _.Cheat, Some(!color))
      }
    }
    case TooManyPlies => handle(drawer force _)

    case Threefold => proxy withGame { game =>
      drawer autoThreefold game map {
        _ foreach { pov =>
          self ! DrawClaim(pov.player.id)
        }
      }
    }

    case HoldAlert(playerId, mean, sd, ip) => handle(playerId) { pov =>
      !pov.player.hasHoldAlert ?? {
        lidraughts.log("cheat").info(s"hold alert $ip https://lidraughts.org/${pov.gameId}/${pov.color.name}#${pov.game.turns} ${pov.player.userId | "anon"} mean: $mean SD: $sd")
        lidraughts.mon.cheat.holdAlert()
        proxy.bypass(_.setHoldAlert(pov, mean, sd)) inject List.empty[Event]
      }
    }

    case RematchYes(playerRef) => handle(playerRef)(rematcher.yes)
    case RematchNo(playerRef) => handle(playerRef)(rematcher.no)

    case TakebackYes(playerRef) => handle(playerRef) { pov =>
      takebacker.yes(takebackSituation)(pov) map {
        case (events, situation) =>
          takebackSituation = situation
          events
      }
    }
    case TakebackNo(playerRef) => handle(playerRef) { pov =>
      takebacker.no(takebackSituation)(pov) map {
        case (events, situation) =>
          takebackSituation = situation
          events
      }
    }

    case Moretime(playerRef) => handle(playerRef) { pov =>
      (pov.game moretimeable !pov.color) ?? {
        val progress =
          if (pov.game.hasClock) giveMoretime(pov.game, List(!pov.color), moretimeDuration)
          else pov.game.correspondenceClock.fold(Progress(pov.game)) { clock =>
            messenger.system(pov.game, (_.untranslated(s"${!pov.color} gets more time")))
            val p = pov.game.correspondenceGiveTime
            p.game.correspondenceClock.map(Event.CorrespondenceClock.apply).fold(p)(p + _)
          }
        proxy save progress inject progress.events
      }
    }

    case ForecastPlay(lastMove) => handle { game =>
      val nextMove = lastMove.situationBefore.captureLengthFrom(lastMove.orig) match {
        case Some(captLen) if captLen > 1 => forecastApi.moveOpponent(game, lastMove) >> forecastApi.nextMove(game, lastMove)
        case _ => forecastApi.nextMove(game, lastMove)
      }
      nextMove map { move =>
        move foreach { m => self ! HumanPlay(game.player.id, m, blur = false) }
        Nil
      }
    }

    case DeployPost => handle { game =>
      game.playable ?? {
        val freeTime = 15.seconds
        messenger.system(game, (_.untranslated("Lidraughts has been updated! Sorry for the inconvenience.")))
        val progress = giveMoretime(game, Color.all, freeTime)
        proxy save progress inject progress.events
      }
    }

    case AbortForMaintenance => handle { game =>
      messenger.system(game, (_.untranslated("Game aborted for server maintenance. Sorry for the inconvenience!")))
      game.playable ?? finisher.other(game, _.Aborted, None)
    }

    case AbortForce => handle { game =>
      game.playable ?? finisher.other(game, _.Aborted, None)
    }

    case NoStart => handle { game =>
      game.timeBeforeExpiration.exists(_.centis == 0) ?? finisher.noStart(game)
    }

    case GetGame => fuccess {
      sender ! proxy.game
    }
  }

  private def giveMoretime(game: Game, colors: List[Color], duration: FiniteDuration): Progress =
    game.clock.fold(Progress(game)) { clock =>
      val centis = duration.toCentis
      val newClock = colors.foldLeft(clock) {
        case (c, color) => c.giveTime(color, centis)
      }
      colors.foreach { c =>
        messenger.system(game, (_.untranslated(
          "%s + %d seconds".format(c, duration.toSeconds)
        )))
      }
      (game withClock newClock) ++ colors.map { Event.ClockInc(_, centis) }
    }

  private def recordLag(pov: Pov) =
    if ((pov.game.playedTurns & 30) == 10) {
      // Triggers every 32 moves starting on ply 10.
      // i.e. 10, 11, 42, 43, 74, 75, ...
      for {
        user <- pov.player.userId
        clock <- pov.game.clock
        lag <- clock.lag(pov.color).lagMean
      } UserLagCache.put(user, lag)
    }

  private def scheduleExpiration: Funit = proxy.game map {
    case None => self ! PoisonPill
    case Some(game) =>
      game.timeBeforeExpiration foreach { centis =>
        context.system.scheduler.scheduleOnce((centis.millis + 1000).millis) {
          awakeWith(NoStart)
        }
      }
  }

  private def handle[A](op: Game => Fu[Events]): Funit =
    handleGame(proxy.game)(op)

  private def handle(playerId: String)(op: Pov => Fu[Events]): Funit =
    handlePov(proxy playerPov playerId)(op)

  private def handleHumanPlay(p: HumanPlay)(op: Pov => Fu[Events]): Funit =
    handlePov {
      p.trace.segment("fetch", "db") {
        proxy playerPov p.playerId
      }
    }(op)

  private def handleBotPlay(p: BotPlay)(op: Pov => Fu[Events]): Funit =
    handlePov(proxy playerPov p.playerId)(op)

  private def handle(color: Color)(op: Pov => Fu[Events]): Funit =
    handlePov(proxy pov color)(op)

  private def handlePov(pov: Fu[Option[Pov]])(op: Pov => Fu[Events]): Funit = publish {
    pov flatten "pov not found" flatMap { p =>
      if (p.player.isAi) fufail(s"player $p can't play AI") else op(p)
    }
  } recover errorHandler("handlePov")

  private def handleAi(game: Fu[Option[Game]])(op: Pov => Fu[Events]): Funit = publish {
    game.map(_.flatMap(_.aiPov)) flatten "pov not found" flatMap op
  } recover errorHandler("handleAi")

  private def handleGame(game: Fu[Option[Game]])(op: Game => Fu[Events]): Funit = publish {
    game flatten "game not found" flatMap op
  } recover errorHandler("handleGame")

  private def publish[A](op: Fu[Events]): Funit = op.map { events =>
    if (events.nonEmpty) {
      socketHub ! Tell(gameId, EventList(events))
      if (events exists {
        case e: Event.Move => e.threefold
        case _ => false
      }) self ! Threefold
    }
  }

  private def errorHandler(name: String): PartialFunction[Throwable, Unit] = {
    case e: ClientError =>
      logger.info(s"Round client error $name", e)
      lidraughts.mon.round.error.client()
    case e: DraughtsnetError =>
      logger.info(s"Round draughtsnet error $name", e)
      lidraughts.mon.round.error.draughtsnet()
    case e: Exception => logger.warn(s"$name: ${e.getMessage}")
  }
}

object Round {

  private[round] case class Dependencies(
      messenger: Messenger,
      takebacker: Takebacker,
      finisher: Finisher,
      rematcher: Rematcher,
      player: Player,
      drawer: Drawer,
      forecastApi: ForecastApi,
      socketHub: ActorRef,
      moretimeDuration: FiniteDuration,
      activeTtl: Duration
  )

  case class TakebackSituation(
      nbDeclined: Int = 0,
      lastDeclined: Option[DateTime] = none
  ) {

    def decline = TakebackSituation(nbDeclined + 1, DateTime.now.some)

    def delaySeconds = (math.pow(nbDeclined min 10, 2) * 10).toInt

    def offerable = lastDeclined.fold(true) { _ isBefore DateTime.now.minusSeconds(delaySeconds) }

    def reset = TakebackSituation()
  }
}
