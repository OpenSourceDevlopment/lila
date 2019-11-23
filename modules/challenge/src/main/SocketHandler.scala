package lidraughts.challenge

import akka.actor._
import akka.pattern.ask

import lidraughts.hub.actorApi.map._
import lidraughts.socket.actorApi.{ Connected => _, _ }
import lidraughts.socket.Handler
import lidraughts.socket.Socket.{ Uid, SocketVersion }
import lidraughts.user.User
import makeTimeout.short

private[challenge] final class SocketHandler(
    hub: lidraughts.hub.Env,
    socketHub: ActorRef,
    pingChallenge: Challenge.ID => Funit
) {

  def join(
    challengeId: Challenge.ID,
    uid: Uid,
    userId: Option[User.ID],
    owner: Boolean,
    version: Option[SocketVersion]
  ): Fu[Option[JsSocketHandler]] = for {
    socket ← socketHub ? Get(challengeId) mapTo manifest[ActorRef]
    join = Socket.Join(uid, userId, owner, version)
    handler ← Handler.forActor(hub, socket, uid, join) {
      case Socket.Connected(enum, member) =>
        (controller(socket, challengeId, uid, member), enum, member)
    }
  } yield handler.some

  private def controller(
    socket: ActorRef,
    challengeId: Challenge.ID,
    uid: Uid,
    member: Socket.Member
  ): Handler.Controller = {
    case ("p", o) => socket ! Ping(uid, o)
    case ("ping", _) if member.owner => pingChallenge(challengeId)
  }
}
