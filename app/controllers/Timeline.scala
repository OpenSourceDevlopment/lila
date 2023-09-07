package controllers

import play.api.libs.json._
import scala.concurrent.duration._

import lidraughts.app._
import lidraughts.common.HTTPRequest
import lidraughts.timeline.Entry.entryWrites
import views._

object Timeline extends LidraughtsController {

  def home = Auth { implicit ctx => me =>
    def nb = getInt("nb").fold(10)(_ atMost 30)
    lidraughts.mon.http.response.timeline.count()
    negotiate(
      html =
        if (HTTPRequest.isXhr(ctx.req))
          Env.timeline.entryApi.userEntries(me.id)
          .logTimeIfGt(s"timeline site entries for ${me.id}", 10 seconds)
          .map { html.timeline.entries(_) }
        else
          Env.timeline.entryApi.moreUserEntries(me.id, nb)
            .logTimeIfGt(s"timeline site more entries ($nb) for ${me.id}", 10 seconds)
            .map { html.timeline.more(_) },
      _ => Env.timeline.entryApi.moreUserEntries(me.id, nb atMost 20)
        .logTimeIfGt(s"timeline mobile $nb for ${me.id}", 10 seconds)
        .map { es => Ok(Json.obj("entries" -> es)) }
    ).mon(_.http.response.timeline.time)
  }

  def unsub(channel: String) = Auth { implicit ctx => me =>
    Env.timeline.unsubApi.set(channel, me.id, ~get("unsub") == "on")
  }
}
