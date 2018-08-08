package controllers

import lidraughts.api.Context
import lidraughts.app._
import lidraughts.game.{ Game => GameModel, Pov, AnonCookie }
import lidraughts.security.Granter
import play.api.mvc._

private[controllers] trait TheftPrevention { self: LidraughtsController =>

  protected def PreventTheft(pov: Pov)(ok: => Fu[Result])(implicit ctx: Context): Fu[Result] =
    isTheft(pov).fold(fuccess(Redirect(routes.Round.watcher(pov.gameId, pov.color.name))), ok)

  protected def isTheft(pov: Pov)(implicit ctx: Context) = pov.game.isPdnImport || pov.player.isAi || {
    (pov.player.userId, ctx.userId) match {
      case (Some(playerId), None) => true
      case (Some(playerId), Some(userId)) => playerId != userId
      case (None, _) =>
        !lidraughts.api.Mobile.Api.requested(ctx.req) &&
          !ctx.req.cookies.get(AnonCookie.name).map(_.value).contains(pov.playerId)
    }
  }

  protected def isMyPov(pov: Pov)(implicit ctx: Context) = !isTheft(pov)

  protected def playablePovForReq(game: GameModel)(implicit ctx: Context) =
    (!game.isPdnImport && game.playable) ?? {
      ctx.userId.flatMap(game.playerByUserId).orElse {
        ctx.req.cookies.get(AnonCookie.name).map(_.value)
          .flatMap(game.player).filterNot(_.hasUser)
      }.filterNot(_.isAi).map { Pov(game, _) }
    }

  protected lazy val theftResponse = Unauthorized(jsonError(
    "This game requires authentication"
  )) as JSON
}
