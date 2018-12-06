package lidraughts.socket

import scala.concurrent.duration._

import lidraughts.hub.actorApi.HasUserIdP
import lidraughts.hub.Trouper

abstract class SocketTrouper[M <: SocketMember](
    uidTtl: Duration
) extends SocketBase[M] with Trouper {

  override def start() = {
    // #TODO find another way to propaget Deploy event (through the TrouperMap)
    // lidraughtsBus.publish(lidraughts.socket.SocketHub.Open(this), 'socket)
  }

  override def stop() = {
    members foreachKey ejectUidString
  }

  protected val receiveTrouper: PartialFunction[Any, Unit] = {
    case HasUserIdP(userId, promise) => promise success hasUserId(userId)
  }

  val process = receiveSpecific orElse receiveTrouper orElse receiveGeneric
}
