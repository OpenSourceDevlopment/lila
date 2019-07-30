package controllers

import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import lidraughts.api.{ Context, GameApiV2 }
import lidraughts.app._
import lidraughts.common.HTTPRequest
import lidraughts.game.GameRepo
import lidraughts.game.PdnDump.WithFlags
import lidraughts.simul.{ Simul => Sim }
import lidraughts.simul.DataForm.{ empty => emptyForm }
import lidraughts.chat.Chat
import views._

object Simul extends LidraughtsController {

  private def env = Env.simul

  private def simulNotFound(implicit ctx: Context) = NotFound(html.simul.notFound())

  private val settleResultOptions = Set("hostwin", "hostloss", "draw")

  val home = Open { implicit ctx =>
    fetchSimuls map {
      case ((created, started), finished) =>
        Ok(html.simul.home(created, started, finished))
    }
  }

  val homeReload = Open { implicit ctx =>
    fetchSimuls map {
      case ((created, started), finished) =>
        Ok(html.simul.homeInner(created, started, finished))
    }
  }

  private def fetchSimuls =
    env.allCreated.get zip env.repo.allStarted zip env.repo.allFinished(30)

  def show(id: String) = Open { implicit ctx =>
    env.repo find id flatMap {
      _.fold(simulNotFound.fuccess) { sim =>
        for {
          version <- env.version(sim.id)
          json <- env.jsonView(sim, sim.canHaveCevalUser(ctx.me), ctx.pref.some)
          chat <- canHaveChat(sim) ?? Env.chat.api.userChat.cached.findMine(Chat.Id(sim.id), ctx.me).map(some)
          _ <- chat ?? { c => Env.user.lightUserApi.preloadMany(c.chat.userIds) }
          stream <- Env.streamer.liveStreamApi one sim.hostId
        } yield html.simul.show(sim, version, json, chat, stream)
      }
    } map NoCache
  }

  private[controllers] def canHaveChat(sim: Sim)(implicit ctx: Context): Boolean = ctx.me ?? { u =>
    if (ctx.kid || !Env.chat.panic.allowed(u)) false
    else sim.canHaveChat(u.id)
  }

  def start(simulId: String) = Open { implicit ctx =>
    AsHostOrArbiter(simulId) { simul =>
      env.api start simul.id
      Ok(Json.obj("ok" -> true)) as JSON
    }
  }

  def abort(simulId: String) = Open { implicit ctx =>
    AsHostOrArbiter(simulId) { simul =>
      env.api abort simul.id
      Ok(Json.obj("ok" -> true)) as JSON
    }
  }

  def accept(simulId: String, userId: String) = Open { implicit ctx =>
    AsHostOrArbiter(simulId) { simul =>
      env.api.accept(simul.id, userId, true)
      Ok(Json.obj("ok" -> true)) as JSON
    }
  }

  def reject(simulId: String, userId: String) = Open { implicit ctx =>
    AsHostOrArbiter(simulId) { simul =>
      env.api.accept(simul.id, userId, false)
      Ok(Json.obj("ok" -> true)) as JSON
    }
  }

  def allow(simulId: String, userId: String) = Open { implicit ctx =>
    AsHostOrArbiter(simulId) { simul =>
      env.api.allow(simul.id, userId, true)
      Ok(Json.obj("ok" -> true)) as JSON
    }
  }

  def disallow(simulId: String, userId: String) = Open { implicit ctx =>
    AsHostOrArbiter(simulId) { simul =>
      env.api.allow(simul.id, userId, false)
      Ok(Json.obj("ok" -> true)) as JSON
    }
  }

  def settle(simulId: String, userId: String, result: String) = Open { implicit ctx =>
    AsArbiterOnly(simulId) { simul =>
      if (simul.hasPairing(userId) && settleResultOptions.contains(result)) {
        env.api.settle(simul.id, userId, result)
        fuccess(Ok(Json.obj("ok" -> true)) as JSON)
      } else fuccess(BadRequest)
    }
  }

  def arbiter(simulId: String) = Open { implicit ctx =>
    AsArbiterOnly(simulId) { sim =>
      env.jsonView.arbiterJson(sim) map { Ok(_) as JSON }
    }
  }

  def timeOutGame(simulId: String, gameId: String, seconds: Int) = Open { implicit ctx =>
    AsHostOnly(simulId) { simul =>
      simul.pairings.find(p => p.gameId == gameId && p.ongoing) map {
        case pairing if seconds == 0 =>
          GameRepo.unsetTimeOut(pairing.gameId)
          Ok(Json.obj("ok" -> true)) as JSON
        case pairing if seconds > 0 && seconds <= 600 =>
          GameRepo.setTimeOut(pairing.gameId, seconds)
          Ok(Json.obj("ok" -> true)) as JSON
        case _ => BadRequest
      } getOrElse BadRequest
    }
  }

  def form = Auth { implicit ctx => me =>
    NoLameOrBot {
      Ok(html.simul.form(env.forms.create, env.forms)).fuccess
    }
  }

  def create = AuthBody { implicit ctx => implicit me =>
    NoLameOrBot {
      implicit val req = ctx.body
      env.forms.create.bindFromRequest.fold(
        err => BadRequest(html.simul.form(
          env.forms.applyVariants.bindFromRequest.fold(
            err2 => err,
            data => err.copy(value = emptyForm.copy(variants = data.variants).some)
          ),
          env.forms
        )).fuccess,
        setup => env.api.create(setup, me) map { simul =>
          Redirect(routes.Simul.show(simul.id))
        }
      )
    }
  }

  def join(id: String, variant: String) = Auth { implicit ctx => implicit me =>
    NoLameOrBot {
      fuccess {
        env.api.addApplicant(id, me, variant)
        if (HTTPRequest isXhr ctx.req) Ok(Json.obj("ok" -> true)) as JSON
        else Redirect(routes.Simul.show(id))
      }
    }
  }

  def withdraw(id: String) = Auth { implicit ctx => me =>
    fuccess {
      env.api.removeApplicant(id, me)
      if (HTTPRequest isXhr ctx.req) Ok(Json.obj("ok" -> true)) as JSON
      else Redirect(routes.Simul.show(id))
    }
  }

  def exportForm(id: String) = Auth { implicit ctx => me =>
    Env.security.forms.emptyWithCaptcha map {
      case (form, captcha) => Ok(html.simul.export(form, captcha, id))
    }
  }

  def exportConfirm(id: String) = AuthBody { implicit ctx => me =>
    implicit val req = ctx.body
    Env.security.forms.empty.bindFromRequest.fold(
      err => Env.security.forms.anyCaptcha map { captcha =>
        BadRequest(html.simul.export(err, captcha, id))
      },
      _ => env.repo.find(id) flatMap {
        case Some(simul) if simul.isFinished =>
          streamGamesPdn(me, id, GameApiV2.ManyConfig(
            ids = simul.gameIds,
            format = GameApiV2.Format.PDN,
            flags = WithFlags(draughtsResult = ctx.pref.draughtsResult)
          )).fuccess
        case _ => fuccess(BadRequest)
      }
    )
  }

  def websocket(id: String, apiVersion: Int) = SocketOption[JsValue] { implicit ctx =>
    getSocketUid("sri") ?? { uid =>
      env.socketHandler.join(id, uid, ctx.me)
    }
  }

  private val ExportRateLimitPerUser = new lidraughts.memo.RateLimit[lidraughts.user.User.ID](
    credits = 20,
    duration = 1 hour,
    name = "simul export per user",
    key = "simul_export.user"
  )

  private def streamGamesPdn(user: lidraughts.user.User, simulId: String, config: GameApiV2.ManyConfig) =
    ExportRateLimitPerUser(user.id, cost = 1) {
      val date = (DateTimeFormat forPattern "yyyy-MM-dd") print new DateTime
      Ok.chunked(Env.api.gameApiV2.exportGamesByIds(config)).withHeaders(
        CONTENT_TYPE -> gameContentType(config),
        CONTENT_DISPOSITION -> ("attachment; filename=" + s"lidraughts_simul_$simulId.${config.format.toString.toLowerCase}")
      )
    }

  private def AsHostOrArbiter(simulId: Sim.ID)(f: Sim => Result)(implicit ctx: Context): Fu[Result] =
    env.repo.find(simulId) flatMap {
      case None => notFound
      case Some(simul) if ctx.userId.exists(simul.hostId ==) || ctx.userId.exists(simul.isArbiter) => fuccess(f(simul))
      case _ => fuccess(Unauthorized)
    }

  private def AsHostOnly(simulId: Sim.ID)(f: Sim => Result)(implicit ctx: Context): Fu[Result] =
    env.repo.find(simulId) flatMap {
      case None => notFound
      case Some(simul) if ctx.userId.exists(simul.hostId ==) => fuccess(f(simul))
      case _ => fuccess(Unauthorized)
    }

  private def AsArbiterOnly(simulId: Sim.ID)(f: Sim => Fu[Result])(implicit ctx: Context): Fu[Result] =
    env.repo.find(simulId) flatMap {
      case None => notFound
      case Some(simul) if ctx.userId.exists(simul.isArbiter) => f(simul)
      case _ => fuccess(Unauthorized)
    }

  private def gameContentType(config: GameApiV2.Config) = config.format match {
    case GameApiV2.Format.PDN => pdnContentType
    case GameApiV2.Format.JSON => ndJsonContentType
  }
}
