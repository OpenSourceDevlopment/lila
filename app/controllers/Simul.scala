package controllers

import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import lidraughts.api.Context
import lidraughts.app._
import lidraughts.common.HTTPRequest
import lidraughts.game.{ GameRepo, Pov }
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
          json <- env.jsonView(sim, sim.canHaveCeval(ctx.me))
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

  def form = Auth { implicit ctx => me =>
    NoEngine {
      Ok(html.simul.form(env.forms.create, env.forms)).fuccess
    }
  }

  def create = AuthBody { implicit ctx => implicit me =>
    NoEngine {
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
    NoEngine {
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
        case Some(simul) if simul.isFinished => fuccess(streamGamesPdn(me, simul.gameIds, id))
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

  private def streamGamesPdn(user: lidraughts.user.User, gameIds: List[String], simulId: String) =
    ExportRateLimitPerUser(user.id, cost = 1) {
      val date = (DateTimeFormat forPattern "yyyy-MM-dd") print new DateTime
      Ok.chunked(Env.api.pdnDump.exportGamesFromIds(gameIds)).withHeaders(
        CONTENT_TYPE -> pdnContentType,
        CONTENT_DISPOSITION -> ("attachment; filename=" + s"lidraughts_simul_$simulId.pdn")
      )
    }

  private def AsHostOrArbiter(simulId: Sim.ID)(f: Sim => Result)(implicit ctx: Context): Fu[Result] =
    env.repo.find(simulId) flatMap {
      case None => notFound
      case Some(simul) if ctx.userId.exists(simul.hostId ==) || ctx.userId.exists(simul.isArbiter) => fuccess(f(simul))
      case _ => fuccess(Unauthorized)
    }

  private def AsArbiterOnly(simulId: Sim.ID)(f: Sim => Fu[Result])(implicit ctx: Context): Fu[Result] =
    env.repo.find(simulId) flatMap {
      case None => notFound
      case Some(simul) if ctx.userId.exists(simul.isArbiter) => f(simul)
      case _ => fuccess(Unauthorized)
    }
}
