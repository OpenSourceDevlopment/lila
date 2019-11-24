package lidraughts.bot

import akka.actor._

final class Env(
    system: ActorSystem,
    hub: lidraughts.hub.Env,
    onlineUserIds: lidraughts.memo.ExpireSetMemo,
    lightUserApi: lidraughts.user.LightUserApi
) {

  lazy val jsonView = new BotJsonView(lightUserApi)

  lazy val gameStateStream = new GameStateStream(system, jsonView)

  lazy val player = new BotPlayer(hub.actor.chat)(system)

  val form = BotForm
}

object Env {

  lazy val current: Env = "bot" boot new Env(
    system = lidraughts.common.PlayApp.system,
    hub = lidraughts.hub.Env.current,
    onlineUserIds = lidraughts.user.Env.current.onlineUserIdMemo,
    lightUserApi = lidraughts.user.Env.current.lightUserApi
  )
}
