package lidraughts.site
package actorApi

import scala.concurrent.Promise

import lidraughts.socket.SocketMember
import lidraughts.socket.Socket.Uid

case class Member(
    channel: JsChannel,
    userId: Option[String],
    flag: Option[String]
) extends SocketMember {

  val troll = false

  def isApi = flag has "api"
}

case class JoinP(uid: Uid, userId: Option[String], flag: Option[String], promise: Promise[Connected])
private[site] case class Connected(enumerator: JsEnumerator, member: Member)
