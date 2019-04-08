package lidraughts.simul

import play.api.libs.json._

import lidraughts.common.LightUser
import lidraughts.game.{ Game, GameRepo }

final class JsonView(getLightUser: LightUser.Getter) {

  private def fetchGames(simul: Simul) =
    if (simul.isFinished) GameRepo gamesFromSecondary simul.gameIds
    else GameRepo gamesFromPrimary simul.gameIds

  def apply(simul: Simul): Fu[JsObject] = for {
    games <- fetchGames(simul)
    lightHost <- getLightUser(simul.hostId)
    lightArbiter <- simul.arbiterId.??(getLightUser)
    applicants <- simul.applicants.sortBy(-_.player.rating).map(applicantJson).sequenceFu
    pairings <- simul.pairings.sortBy(-_.player.rating).map(pairingJson(games, simul.hostId)).sequenceFu
  } yield Json.obj(
    "id" -> simul.id,
    "host" -> lightHost.map { host =>
      Json.obj(
        "id" -> host.id,
        "username" -> host.name,
        "patron" -> host.isPatron,
        "title" -> host.title,
        "rating" -> simul.hostRating,
        "gameId" -> simul.hostGameId
      )
    },
    "name" -> simul.name,
    "fullName" -> simul.fullName,
    "variants" -> simul.variants.map(variantJson(draughts.Speed(simul.clock.config.some))),
    "applicants" -> applicants,
    "pairings" -> pairings,
    "isCreated" -> simul.isCreated,
    "isRunning" -> simul.isRunning,
    "isFinished" -> simul.isFinished,
    "quote" -> lidraughts.quote.Quote.one(simul.id)
  ).add("arbiter" -> lightArbiter.map { arbiter =>
      Json.obj(
        "id" -> arbiter.id,
        "username" -> arbiter.name,
        "patron" -> arbiter.isPatron,
        "title" -> arbiter.title
      )
    })

  private def variantJson(speed: draughts.Speed)(v: draughts.variant.Variant) = Json.obj(
    "key" -> v.key,
    "icon" -> lidraughts.game.PerfPicker.perfType(speed, v, none).map(_.iconChar.toString),
    "name" -> v.name
  )

  private def playerJson(player: SimulPlayer): Fu[JsObject] =
    getLightUser(player.user) map { light =>
      Json.obj(
        "id" -> player.user,
        "variant" -> player.variant.key,
        "rating" -> player.rating
      ).add("username" -> light.map(_.name))
        .add("title" -> light.map(_.title))
        .add("provisional" -> player.provisional.filter(identity))
        .add("patron" -> light.??(_.isPatron))
    }

  private def applicantJson(app: SimulApplicant): Fu[JsObject] =
    playerJson(app.player) map { player =>
      Json.obj(
        "player" -> player,
        "accepted" -> app.accepted
      )
    }

  private def gameJson(hostId: String)(g: Game) = Json.obj(
    "id" -> g.id,
    "status" -> g.status.id,
    "fen" -> (draughts.format.Forsyth exportBoard g.board),
    "lastMove" -> ~g.lastMoveKeys,
    "orient" -> g.playerByUserId(hostId).map(_.color)
  )

  private def pairingJson(games: List[Game], hostId: String)(p: SimulPairing): Fu[JsObject] =
    playerJson(p.player) map { player =>
      Json.obj(
        "player" -> player,
        "hostColor" -> p.hostColor,
        "winnerColor" -> p.winnerColor,
        "wins" -> p.wins, // can't be normalized because BC
        "game" -> games.find(_.id == p.gameId).map(gameJson(hostId))
      )
    }

  private implicit val colorWriter: Writes[draughts.Color] = Writes { c =>
    JsString(c.name)
  }
}
