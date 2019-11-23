package lidraughts.simul

import akka.actor._
import akka.pattern.ask
import com.typesafe.config.Config
import scala.concurrent.duration._

import lidraughts.hub.actorApi.map.Ask
import lidraughts.hub.{ Duct, DuctMap, TrouperMap }
import lidraughts.socket.History
import lidraughts.socket.Socket.{ GetVersionP, SocketVersion }
import makeTimeout.short

final class Env(
    config: Config,
    system: ActorSystem,
    scheduler: lidraughts.common.Scheduler,
    evalCacheApi: lidraughts.evalCache.EvalCacheApi,
    db: lidraughts.db.Env,
    hub: lidraughts.hub.Env,
    draughtsnetCommentator: lidraughts.draughtsnet.Commentator,
    roundMap: DuctMap[_],
    lightUser: lidraughts.common.LightUser.Getter,
    onGameStart: String => Unit,
    isOnline: String => Boolean,
    asyncCache: lidraughts.memo.AsyncCache.Builder
) {

  private val settings = new {
    val CollectionSimul = config getString "collection.simul"
    val SequencerTimeout = config duration "sequencer.timeout"
    val CreatedCacheTtl = config duration "created.cache.ttl"
    val UniqueCacheTtl = config duration "unique.cache.ttl"
    val HistoryMessageTtl = config duration "history.message.ttl"
    val UidTimeout = config duration "uid.timeout"
    val SocketTimeout = config duration "socket.timeout"
    val ActorName = config getString "actor.name"
  }
  import settings._

  lazy val repo = new SimulRepo(
    simulColl = simulColl
  )

  lazy val api = new SimulApi(
    repo = repo,
    system = system,
    socketMap = socketMap,
    roundMap = roundMap,
    site = hub.socket.site,
    renderer = hub.actor.renderer,
    timeline = hub.actor.timeline,
    userRegister = hub.actor.userRegister,
    lobby = hub.socket.lobby,
    onGameStart = onGameStart,
    sequencers = sequencerMap,
    asyncCache = asyncCache
  )

  def evalCache = evalCacheApi

  lazy val crudApi = new crud.CrudApi(repo)

  lazy val forms = new DataForm

  lazy val jsonView = new JsonView(lightUser, isOnline)

  private val socketMap: SocketMap = new TrouperMap[Socket](
    mkTrouper = (simulId: String) => new Socket(
      system = system,
      simulId = simulId,
      history = new History(ttl = HistoryMessageTtl),
      getSimul = repo.find,
      jsonView = jsonView,
      uidTtl = UidTimeout,
      lightUser = lightUser,
      keepMeAlive = () => socketMap touch simulId
    ),
    accessTimeout = SocketTimeout
  )

  lazy val socketHandler = new SocketHandler(
    hub = hub,
    socketMap = socketMap,
    chat = hub.actor.chat,
    exists = repo.exists
  )

  system.lidraughtsBus.subscribe(system.actorOf(Props(new Actor {
    import akka.pattern.pipe
    def receive = {
      case lidraughts.game.actorApi.FinishGame(game, _, _) => api finishGame game
      case lidraughts.hub.actorApi.mod.MarkCheater(userId, true) => api ejectCheater userId
      case lidraughts.hub.actorApi.simul.GetHostIds => api.currentHostIds pipeTo sender
      case lidraughts.hub.actorApi.round.SimulMoveEvent(move, simulId, opponentUserId) =>
        hub.actor.userRegister ! lidraughts.hub.actorApi.SendTo(
          opponentUserId,
          lidraughts.socket.Socket.makeMessage("simulPlayerMove", move.gameId)
        )
        allUniqueWithCommentaryIds.get.foreach { ids =>
          if (ids.contains(simulId))
            draughtsnetCommentator(move.gameId)
        }
      case lidraughts.hub.actorApi.draughtsnet.CommentaryEvent(gameId, simulId, json) if simulId.isDefined =>
        api.processCommentary(simulId.get, gameId, json)
      case m: lidraughts.hub.actorApi.Deploy => socketMap tellAll m
    }
  }), name = ActorName), 'finishGame, 'adjustCheater, 'moveEventSimul, 'draughtsnetComment, 'deploy)

  def isHosting(userId: String): Fu[Boolean] = api.currentHostIds map (_ contains userId)

  val allCreated = asyncCache.single(
    name = "simul.allCreated",
    repo.allCreated,
    expireAfter = _.ExpireAfterWrite(CreatedCacheTtl)
  )

  val allCreatedFeaturable = asyncCache.single(
    name = "simul.allCreatedFeaturable",
    repo.allCreatedFeaturable,
    expireAfter = _.ExpireAfterWrite(CreatedCacheTtl)
  )

  val allUniqueFeaturable = asyncCache.single(
    name = "simul.allUniqueFeaturable",
    repo.allUniqueFeaturable,
    expireAfter = _.ExpireAfterWrite(UniqueCacheTtl)
  )

  val allUniqueWithCommentaryIds = asyncCache.single(
    name = "simul.allUniqueWithCommentaryIds",
    repo.allUniqueWithCommentaryIds,
    expireAfter = _.ExpireAfterWrite(UniqueCacheTtl)
  )

  def version(simulId: String): Fu[SocketVersion] =
    socketMap.ask[SocketVersion](simulId)(GetVersionP.apply)

  private[simul] val simulColl = db(CollectionSimul)

  private val sequencerMap = new DuctMap(
    mkDuct = _ => Duct.extra.lazyFu,
    accessTimeout = SequencerTimeout
  )

  private lazy val simulCleaner = new SimulCleaner(repo, api, socketMap)

  scheduler.effect(15 seconds, "[simul] cleaner")(simulCleaner.apply)
}

object Env {

  lazy val current = "simul" boot new Env(
    config = lidraughts.common.PlayApp loadConfig "simul",
    system = lidraughts.common.PlayApp.system,
    scheduler = lidraughts.common.PlayApp.scheduler,
    evalCacheApi = lidraughts.evalCache.Env.current.api,
    db = lidraughts.db.Env.current,
    hub = lidraughts.hub.Env.current,
    draughtsnetCommentator = lidraughts.draughtsnet.Env.current.commentator,
    roundMap = lidraughts.round.Env.current.roundMap,
    lightUser = lidraughts.user.Env.current.lightUser,
    onGameStart = lidraughts.game.Env.current.onStart,
    isOnline = lidraughts.user.Env.current.isOnline,
    asyncCache = lidraughts.memo.Env.current.asyncCache
  )
}
