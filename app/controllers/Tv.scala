package controllers

import play.api.mvc._

import lidraughts.api.Context
import lidraughts.app._
import lidraughts.game.{ GameRepo, Pov }
import views._

object Tv extends LidraughtsController {

  def index = onChannel(lidraughts.tv.Tv.Channel.Best.key)

  def onChannel(chanKey: String) = Open { implicit ctx =>
    (lidraughts.tv.Tv.Channel.byKey get chanKey).fold(notFound)(lidraughtsTv)
  }

  def sides(chanKey: String, gameId: String, color: String) = Open { implicit ctx =>
    lidraughts.tv.Tv.Channel.byKey get chanKey match {
      case None => notFound
      case Some(channel) =>
        OptionFuResult(GameRepo.pov(gameId, color)) { pov =>
          Env.tv.tv.getChampions zip
            Env.game.crosstableApi.withMatchup(pov.game) map {
              case (champions, crosstable) => Ok(html.tv.side.sides(channel, champions, pov, crosstable))
            }
        }
    }
  }

  def channels = Api.ApiRequest { implicit ctx =>
    import play.api.libs.json._
    implicit val championWrites = Json.writes[lidraughts.tv.Tv.Champion]
    Env.tv.tv.getChampions map {
      _.channels map { case (chan, champ) => chan.name -> champ }
    } map { Json.toJson(_) } map Api.Data.apply
  }

  private def lidraughtsTv(channel: lidraughts.tv.Tv.Channel)(implicit ctx: Context) =
    Env.tv.tv getGameAndHistory channel flatMap {
      case Some((game, history)) =>
        val flip = getBool("flip")
        val pov = if (flip) Pov second game else Pov first game
        val onTv = lidraughts.round.OnLidraughtsTv(channel.key, flip)
        negotiate(
          html = {
            Env.api.roundApi.watcher(pov, lidraughts.api.Mobile.Api.currentVersion, tv = onTv.some) zip
              Env.game.crosstableApi.withMatchup(game) zip
              Env.tv.tv.getChampions map {
                case ((data, cross), champions) => NoCache {
                  NoIframe { // can be heavy as TV reloads for each game
                    Ok(html.tv.index(channel, champions, pov.some, data, cross, flip, history))
                  }
                }
              }
          },
          api = apiVersion => Env.api.roundApi.watcher(pov, apiVersion, tv = onTv.some) map { Ok(_) }
        )
      case _ => negotiate(
        html = Env.tv.tv.getChampions map { champions =>
          NoIframe {
            Ok(html.tv.index(channel, champions, none, play.api.libs.json.Json.obj(), none, false, Nil))
          }
        },
        api = _ => notFoundJson("No game found")
      )
    }

  def games = gamesChannel(lidraughts.tv.Tv.Channel.Best.key)

  def gamesChannel(chanKey: String) = Open { implicit ctx =>
    (lidraughts.tv.Tv.Channel.byKey get chanKey) ?? { channel =>
      Env.tv.tv.getChampions zip Env.tv.tv.getGames(channel, 9) map {
        case (champs, games) => NoCache {
          Ok(html.tv.games(channel, games map Pov.first, champs))
        }
      }
    }
  }

  def gamesCollection = Open { implicit ctx =>
    val maxGames = 21
    val gameIds = get("games") match {
      case Some(gamesStr) if gamesStr.nonEmpty =>
        gamesStr.split(",").toList.take(maxGames).map(_.split('/'))
      case _ => Nil
    }
    def side(gameId: String) = gameIds.find(_.headOption.contains(gameId))
      .flatMap(_.lastOption).flatMap(draughts.Color.apply).getOrElse(draughts.White)
    Env.tv.tv.getChampions zip Env.tv.tv.getGamesFromIds(gameIds.flatMap(_.headOption)) map {
      case (champs, games) => NoCache {
        Ok(html.tv.gamesCollection(
          games map { g => side(g.id).fold(Pov.white(g), Pov.black(g)) },
          champs
        ))
      }
    }
  }

  def feed = Action.async { req =>
    import makeTimeout.short
    import akka.pattern.ask
    import lidraughts.round.TvBroadcast
    import play.api.libs.EventSource
    Env.round.tvBroadcast ? TvBroadcast.GetEnumerator mapTo
      manifest[TvBroadcast.EnumeratorType] map { enum =>
        Ok.chunked(enum &> EventSource()).as("text/event-stream") |> noProxyBuffer
      }
  }

  def embed = Action { req =>
    Ok {
      val bg = get("bg", req) | "light"
      val theme = get("theme", req) | "maple"
      val url = s"""${req.domain + routes.Tv.frame}?bg=$bg&theme=$theme"""
      s"""document.write("<iframe src='https://$url&embed=" + document.domain + "' class='lidraughts-tv-iframe' allowtransparency='true' frameBorder='0' style='width: 224px; height: 264px;' title='Lidraughts free online draughts'></iframe>");"""
    } as JAVASCRIPT withHeaders (CACHE_CONTROL -> "max-age=86400")
  }

  def frame = Action.async { implicit req =>
    Env.tv.tv.getBestGame map {
      case None => NotFound
      case Some(game) => Ok(views.html.tv.embed(
        Pov first game,
        get("bg", req) | "light",
        lidraughts.pref.Theme(~get("theme", req)).cssClass
      ))
    }
  }
}
