package lidraughts.lobby

import scala.concurrent.duration._

import akka.actor.ActorSystem
import play.api.libs.iteratee._
import play.api.libs.json._

import actorApi._
import lidraughts.game.{ Game, AnonCookie }
import lidraughts.hub.actorApi.game.ChangeFeatured
import lidraughts.hub.actorApi.lobby._
import lidraughts.hub.actorApi.timeline._
import lidraughts.socket.actorApi.{ Connected => _, _ }
import lidraughts.socket.Socket.{ Uid, Uids }
import lidraughts.socket.SocketTrouper

private[lobby] final class Socket(
    val system: ActorSystem,
    uidTtl: FiniteDuration
) extends SocketTrouper[Member](uidTtl) {

  system.lidraughtsBus.subscribe(this, 'changeFeaturedGame, 'streams, 'nbMembers, 'nbRounds, 'poolGame, 'lobbySocket, 'deploy)
  system.scheduler.scheduleOnce(5 seconds)(this ! SendHookRemovals)
  system.scheduler.schedule(1 minute, 1 minute)(this ! Cleanup)

  private var idleUids = collection.mutable.Set[String]()

  private var hookSubscriberUids = collection.mutable.Set[String]()

  private var removedHookIds = ""

  def receiveSpecific = {

    case GetUidsP(promise) =>
      promise success Uids(members.keySet.map(Uid.apply)(scala.collection.breakOut))
      lidraughts.mon.lobby.socket.idle(idleUids.size)
      lidraughts.mon.lobby.socket.hookSubscribers(hookSubscriberUids.size)
      lidraughts.mon.lobby.socket.mobile(members.count(_._2.mobile))

    case Cleanup =>
      idleUids retain members.contains
      hookSubscriberUids retain members.contains

    case JoinP(uid, user, blocks, mobile, promise) =>
      val (enumerator, channel) = Concurrent.broadcast[JsValue]
      val member = Member(channel, user, blocks, uid, mobile)
      addMember(uid, member)
      promise success Connected(enumerator, member)

    case ReloadTournaments(html) => notifyAllActive(makeMessage("tournaments", html))

    case ReloadSimuls(html) => notifyAllActive(makeMessage("simuls", html))

    case NewForumPost => notifyAllActive(makeMessage("reload_forum"))

    case ReloadTimelines(userIds) => userIds foreach { userId =>
      membersByUserId(userId) foreach (_ push messages.reloadTimeline)
    }

    case AddHook(hook) =>
      val msg = makeMessage("had", hook.render)
      hookSubscriberUids.foreach { uid =>
        withActiveMemberByUidString(uid) { member =>
          if (Biter.showHookTo(hook, member)) member push msg
        }
      }
      if (hook.likePoolFiveO) withMember(hook.uid) { member =>
        lidraughts.mon.lobby.hook.createdLikePoolFiveO(member.mobile)()
      }

    case AddSeek(_) => notifySeeks

    case RemoveHook(hookId) =>
      removedHookIds = s"$removedHookIds$hookId"

    case SendHookRemovals =>
      if (removedHookIds.nonEmpty) {
        val msg = makeMessage("hrm", removedHookIds)
        hookSubscriberUids.foreach { uid =>
          withActiveMemberByUidString(uid)(_ push msg)
        }
        removedHookIds = ""
      }
      system.scheduler.scheduleOnce(1249 millis)(this ! SendHookRemovals)

    case RemoveSeek(_) => notifySeeks

    case JoinHook(uid, hook, game, creatorColor) =>
      withMember(hook.uid) { member =>
        lidraughts.mon.lobby.hook.joinMobile(member.mobile)()
        notifyPlayerStart(game, creatorColor)(member)
      }
      withMember(uid) { member =>
        lidraughts.mon.lobby.hook.joinMobile(member.mobile)()
        if (hook.likePoolFiveO)
          lidraughts.mon.lobby.hook.acceptedLikePoolFiveO(member.mobile)()
        notifyPlayerStart(game, !creatorColor)(member)
      }

    case JoinSeek(userId, seek, game, creatorColor) =>
      membersByUserId(seek.user.id) foreach { member =>
        lidraughts.mon.lobby.seek.joinMobile(member.mobile)()
        notifyPlayerStart(game, creatorColor)(member)
      }
      membersByUserId(userId) foreach { member =>
        lidraughts.mon.lobby.seek.joinMobile(member.mobile)()
        notifyPlayerStart(game, !creatorColor)(member)
      }

    case pairing: lidraughts.pool.PoolApi.Pairing =>
      def goPlayTheGame = redirectPlayers(pairing)
      goPlayTheGame // go play the game now
      system.scheduler.scheduleOnce(1 second)(goPlayTheGame) // I said go
      system.scheduler.scheduleOnce(3 second)(goPlayTheGame) // Darn it

    case HookIds(ids) =>
      val msg = makeMessage("hli", ids mkString "")
      hookSubscriberUids.foreach { uid =>
        withActiveMemberByUidString(uid)(_ push msg)
      }

    case lidraughts.hub.actorApi.StreamsOnAir(html) => notifyAll(makeMessage("streams", html))

    case NbMembers(nb) => pong = pong + ("d" -> JsNumber(nb))
    case lidraughts.hub.actorApi.round.NbRounds(nb) =>
      pong = pong + ("r" -> JsNumber(nb))

    case ChangeFeatured(_, msg) => notifyAllActive(msg)

    case SetIdle(uid, true) => idleUids += uid.value
    case SetIdle(uid, false) => idleUids -= uid.value

    case HookSub(member, false) => hookSubscriberUids -= member.uid.value
    case AllHooksFor(member, hooks) =>
      notifyMember("hooks", JsArray(hooks.map(_.render)))(member)
      hookSubscriberUids += member.uid.value
  }

  private def redirectPlayers(p: lidraughts.pool.PoolApi.Pairing) = {
    withMember(p.whiteUid)(notifyPlayerStart(p.game, draughts.White))
    withMember(p.blackUid)(notifyPlayerStart(p.game, draughts.Black))
  }

  private def notifyPlayerStart(game: Game, color: draughts.Color) =
    notifyMember("redirect", Json.obj(
      "id" -> (game fullIdOf color),
      "url" -> playerUrl(game fullIdOf color)
    ).add("cookie" -> AnonCookie.json(game, color))) _

  private def notifyAllActive(msg: JsObject) =
    members.foreach {
      case (uid, member) => if (!idleUids(uid)) member push msg
    }

  private def withActiveMemberByUidString(uid: String)(f: Member => Unit): Unit =
    if (!idleUids(uid)) members get uid foreach f

  override def quit(uid: Uid): Unit = {
    super.quit(uid)
    idleUids -= uid.value
    hookSubscriberUids -= uid.value
  }

  private def playerUrl(fullId: String) = s"/$fullId"

  private def notifySeeks = notifyAllActive(messages.reloadSeeks)

  private object messages {
    lazy val reloadSeeks = makeMessage("reload_seeks")
    lazy val reloadTimeline = makeMessage("reload_timeline")
  }

  private case object Cleanup
}
