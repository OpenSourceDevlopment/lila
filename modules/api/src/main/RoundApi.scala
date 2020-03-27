package lidraughts.api

import play.api.libs.json._
import draughts.format.FEN
import lidraughts.analyse.{ Analysis, JsonView => analysisJson }
import lidraughts.common.ApiVersion
import lidraughts.game.{ Game, GameRepo, Pov }
import lidraughts.pref.Pref
import lidraughts.round.JsonView.WithFlags
import lidraughts.round.{ Forecast, JsonView, logger }
import lidraughts.security.Granter
import lidraughts.simul.Simul
import lidraughts.tournament.TourAndRanks
import lidraughts.tree.Node.partitionTreeJsonWriter
import lidraughts.user.User

private[api] final class RoundApi(
    jsonView: JsonView,
    noteApi: lidraughts.round.NoteApi,
    forecastApi: lidraughts.round.ForecastApi,
    bookmarkApi: lidraughts.bookmark.BookmarkApi,
    getTourAndRanks: Game => Fu[Option[TourAndRanks]],
    getSimul: Simul.ID => Fu[Option[Simul]]
) {

  def player(pov: Pov, apiVersion: ApiVersion)(implicit ctx: Context): Fu[JsObject] =
    GameRepo.initialFen(pov.game).flatMap { initialFen =>
      jsonView.playerJson(pov, ctx.pref, apiVersion, ctx.me,
        withFlags = WithFlags(blurs = ctx.me ?? Granter(_.ViewBlurs)),
        initialFen = initialFen,
        nvui = ctx.blind) zip
        getTourAndRanks(pov.game) zip
        (pov.game.simulId ?? getSimul) zip
        (ctx.me.ifTrue(ctx.isMobileApi) ?? (me => noteApi.get(pov.gameId, me.id))) zip
        forecastApi.loadForDisplay(pov) zip
        bookmarkApi.exists(pov.game, ctx.me) map {
          case json ~ tourOption ~ simulOption ~ note ~ forecast ~ bookmarked => (
            withTournament(pov, tourOption) _ compose
            withSimul(pov, simulOption, true) _ compose
            withSteps(pov, initialFen) _ compose
            withNote(note) _ compose
            withBookmark(bookmarked) _ compose
            withForecastCount(forecast.map(_.steps.size)) _
          )(json)
        }
    }.mon(_.round.api.player)

  def watcher(pov: Pov, apiVersion: ApiVersion, tv: Option[lidraughts.round.OnTv],
    initialFenO: Option[Option[FEN]] = None)(implicit ctx: Context): Fu[JsObject] =
    initialFenO.fold(GameRepo initialFen pov.game)(fuccess).flatMap { initialFen =>
      jsonView.watcherJson(pov, ctx.pref, apiVersion, ctx.me, tv,
        initialFen = initialFen,
        withFlags = WithFlags(blurs = ctx.me ?? Granter(_.ViewBlurs))) zip
        getTourAndRanks(pov.game) zip
        (pov.game.simulId ?? getSimul) zip
        (ctx.me.ifTrue(ctx.isMobileApi) ?? (me => noteApi.get(pov.gameId, me.id))) zip
        bookmarkApi.exists(pov.game, ctx.me) map {
          case json ~ tourOption ~ simulOption ~ note ~ bookmarked => (
            withTournament(pov, tourOption)_ compose
            withSimul(pov, simulOption, false)_ compose
            withNote(note)_ compose
            withBookmark(bookmarked)_ compose
            withSteps(pov, initialFen)_
          )(json)
        }
    }.mon(_.round.api.watcher)

  def review(pov: Pov, apiVersion: ApiVersion,
    tv: Option[lidraughts.round.OnTv] = None,
    analysis: Option[Analysis] = None,
    initialFenO: Option[Option[FEN]] = None,
    withFlags: WithFlags)(implicit ctx: Context): Fu[JsObject] =
    initialFenO.fold(GameRepo initialFen pov.game)(fuccess).flatMap { initialFen =>
      jsonView.watcherJson(pov, ctx.pref, apiVersion, ctx.me, tv,
        initialFen = initialFen,
        withFlags = withFlags.copy(blurs = ctx.me ?? Granter(_.ViewBlurs))) zip
        getTourAndRanks(pov.game) zip
        (pov.game.simulId ?? getSimul) zip
        (ctx.me.ifTrue(ctx.isMobileApi) ?? (me => noteApi.get(pov.gameId, me.id))) zip
        bookmarkApi.exists(pov.game, ctx.me) map {
          case json ~ tourOption ~ simulOption ~ note ~ bookmarked => (
            withTournament(pov, tourOption)_ compose
            withSimul(pov, simulOption, false)_ compose
            withNote(note)_ compose
            withBookmark(bookmarked)_ compose
            withTree(pov, analysis, initialFen, withFlags, pov.game.metadata.pdnImport.isDefined)_ compose
            withAnalysis(pov.game, analysis)_
          )(json)
        }
    }.mon(_.round.api.watcher)

  def userAnalysisJson(pov: Pov, pref: Pref, initialFen: Option[FEN], orientation: draughts.Color, owner: Boolean, me: Option[User], iteratedCapts: Boolean = false) =
    owner.??(forecastApi loadForDisplay pov).map { fco =>
      withForecast(pov, owner, fco) {
        withTree(pov, analysis = none, initialFen, WithFlags(opening = true), iteratedCapts) {
          jsonView.userAnalysisJson(pov, pref, initialFen, orientation, owner = owner, me = me)
        }
      }
    }

  def puzzleEditorJson(pov: Pov, pref: Pref, initialFen: Option[FEN], orientation: draughts.Color, owner: Boolean, me: Option[User], iteratedCapts: Boolean = false) =
    owner.??(forecastApi loadForDisplay pov).map { fco =>
      withForecast(pov, owner, fco) {
        withTree(pov, analysis = none, initialFen, WithFlags(opening = true), iteratedCapts) {
          jsonView.puzzleEditorJson(pov, pref, initialFen, orientation, owner = owner, me = me)
        }
      }
    }

  def freeStudyJson(pov: Pov, pref: Pref, initialFen: Option[FEN], orientation: draughts.Color, me: Option[User]) =
    withTree(pov, analysis = none, initialFen, WithFlags(opening = true))(
      jsonView.userAnalysisJson(pov, pref, initialFen, orientation, owner = false, me = me)
    )

  private def withTree(pov: Pov, analysis: Option[Analysis], initialFen: Option[FEN], withFlags: WithFlags, iteratedCapts: Boolean = false)(obj: JsObject) =
    obj + ("treeParts" -> partitionTreeJsonWriter.writes(lidraughts.round.TreeBuilder(
      id = pov.gameId,
      pdnmoves = pov.game.pdnMoves,
      variant = pov.game.variant,
      analysis = analysis,
      initialFen = initialFen | FEN(pov.game.variant.initialFen),
      withFlags = withFlags,
      clocks = withFlags.clocks ?? pov.game.bothClockStates,
      iteratedCapts
    )))

  private def withSteps(pov: Pov, initialFen: Option[FEN])(obj: JsObject) =
    obj + ("steps" -> lidraughts.round.StepBuilder(
      id = pov.gameId,
      pdnmoves = pov.game.pdnMoves,
      variant = pov.game.variant,
      initialFen = initialFen.fold(pov.game.variant.initialFen)(_.value)
    ))

  private def withNote(note: String)(json: JsObject) =
    if (note.isEmpty) json else json + ("note" -> JsString(note))

  private def withBookmark(v: Boolean)(json: JsObject) =
    json.add("bookmarked" -> v)

  private def withForecastCount(count: Option[Int])(json: JsObject) =
    count.filter(0 !=).fold(json) { c =>
      json + ("forecastCount" -> JsNumber(c))
    }

  private def withForecast(pov: Pov, owner: Boolean, fco: Option[Forecast])(json: JsObject) =
    if (pov.game.forecastable && owner) json + (
      "forecast" -> {
        if (pov.forecastable) fco.fold[JsValue](Json.obj("none" -> true)) { fc =>
          import Forecast.forecastJsonWriter
          Json toJson fc
        }
        else Json.obj("onMyTurn" -> true)
      }
    )
    else json

  private def withAnalysis(g: Game, o: Option[Analysis])(json: JsObject) =
    json.add("analysis", o.map { a => analysisJson.bothPlayers(g, a) })

  private def withTournament(pov: Pov, tourOption: Option[TourAndRanks])(json: JsObject) =
    json.add("tournament" -> tourOption.map { data =>
      Json.obj(
        "id" -> data.tour.id,
        "name" -> data.tour.name,
        "running" -> data.tour.isStarted
      ).add("secondsToFinish" -> data.tour.isStarted.option(data.tour.secondsToFinish))
        .add("berserkable" -> data.tour.isStarted.option(data.tour.berserkable))
        // mobile app API BC / should use game.expiration instead
        .add("nbSecondsForFirstMove" -> data.tour.isStarted.option {
          pov.game.timeForFirstMove.toSeconds
        })
        .add("ranks" -> data.tour.isStarted.option(Json.obj(
          "white" -> data.whiteRank,
          "black" -> data.blackRank
        )))
    })

  private def withSimul(pov: Pov, simulOption: Option[Simul], player: Boolean)(json: JsObject) =
    json.add("simul", simulOption.map { simul =>
      Json.obj(
        "id" -> simul.id,
        "hostId" -> simul.hostId,
        "name" -> simul.name,
        "nbPlaying" -> simul.ongoing
      ).add("timeOutUntil" -> pov.game.isWithinTimeOut ?? pov.game.metadata.timeOutUntil)
        .add("isUnique" -> simul.isUnique.option(true))
        .add("noAssistance" -> simul.spotlight.flatMap(_.noAssistance).ifTrue(player))
    })
}
