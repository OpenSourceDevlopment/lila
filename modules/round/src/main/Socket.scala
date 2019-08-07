package lidraughts.round

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.{ ask, pipe }
import draughts.{ Color, White, Black }
import play.api.libs.iteratee._
import play.api.libs.json._

import actorApi._
import lidraughts.chat.Chat
import lidraughts.common.LightUser
import lidraughts.game.actorApi.{ SimulNextGame, StartGame, UserStartGame }
import lidraughts.game.{ Game, GameRepo, Event }
import lidraughts.hub.actorApi.Deploy
import lidraughts.hub.actorApi.game.ChangeFeatured
import lidraughts.hub.actorApi.round.{ IsOnGame, TourStanding, SimulStanding }
import lidraughts.hub.actorApi.tv.{ Select => TvSelect }
import lidraughts.hub.TimeBomb
import lidraughts.socket._
import lidraughts.socket.actorApi.{ Connected => _, _ }
import lidraughts.socket.Socket.Uid
import makeTimeout.short

private[round] final class Socket(
    gameId: Game.ID,
    history: History,
    lightUser: LightUser.Getter,
    uidTimeout: FiniteDuration,
    socketTimeout: FiniteDuration,
    disconnectTimeout: FiniteDuration,
    ragequitTimeout: FiniteDuration,
    simulActor: ActorSelection
) extends SocketActor[Member](uidTimeout) {

  private var hasAi = false
  private var mightBeSimul = true // until proven false
  private var chatIds = Socket.ChatIds(
    priv = Chat.Id(gameId), // until replaced with tourney/simul chat
    pub = Chat.Id(s"$gameId/w")
  )
  private var tournamentId = none[String] // until set, to listen to standings
  private var simulId = none[String]

  private val timeBomb = new TimeBomb(socketTimeout)

  private[this] var delayedCrowdNotification = false

  private final class Player(color: Color) {

    // when the player has been seen online for the last time
    private var time: Long = nowMillis
    // wether the player closed the window intentionally
    private var bye: Int = 0
    // connected as a bot
    private var botConnected: Boolean = false

    var userId = none[String]

    def ping: Unit = {
      isGone foreach { _ ?? notifyGone(color, false) }
      if (bye > 0) bye = bye - 1
      time = nowMillis
    }
    def setBye: Unit = {
      bye = 3
    }
    private def isBye = bye > 0

    private def isHostingSimul: Fu[Boolean] = userId.ifTrue(mightBeSimul) ?? { u =>
      simulActor ? lidraughts.hub.actorApi.simul.GetHostIds mapTo manifest[Set[String]] map (_ contains u)
    }

    def isGone: Fu[Boolean] = {
      time < (nowMillis - (if (isBye) ragequitTimeout else disconnectTimeout).toMillis) &&
        !botConnected
    } ?? !isHostingSimul

    def setBotConnected(v: Boolean) =
      botConnected = v

    def isBotConnected = botConnected
  }

  private val whitePlayer = new Player(White)
  private val blackPlayer = new Player(Black)

  override def preStart(): Unit = {
    super.preStart()
    buscriptions.all
    GameRepo game gameId map SetGame.apply pipeTo self
  }

  override def postStop(): Unit = {
    super.postStop()
    lidraughtsBus.unsubscribe(self)
  }

  private object buscriptions {

    def all = {
      tv
      chat
      tournament
      simul
    }

    def tv = members.flatMap { case (_, m) => m.userTv }.toSet foreach { (userId: String) =>
      lidraughtsBus.subscribe(self, Symbol(s"userStartGame:$userId"), Symbol(s"simulNextGame:$userId"))
    }

    def chat = lidraughtsBus.subscribe(self, Symbol(s"chat:${chatIds.priv}"), Symbol(s"chat:${chatIds.pub}"))

    def tournament = tournamentId foreach { id =>
      lidraughtsBus.subscribe(self, Symbol(s"tour-standing-$id"))
    }

    def simul = simulId foreach { id =>
      lidraughtsBus.subscribe(self, Symbol(s"simul-standing-$id"))
    }
  }

  def receiveSpecific = ({

    case SetGame(Some(game)) =>
      hasAi = game.hasAi
      whitePlayer.userId = game.player(White).userId
      blackPlayer.userId = game.player(Black).userId
      mightBeSimul = game.isSimul
      game.tournamentId orElse game.simulId map Chat.Id.apply foreach { chatId =>
        chatIds = chatIds.copy(priv = chatId)
        buscriptions.chat
      }
      game.tournamentId foreach { tourId =>
        tournamentId = tourId.some
        buscriptions.tournament
      }
      game.simulId foreach { simId =>
        simulId = simId.some
        buscriptions.simul
      }
    case SetGame(None) => self ! PoisonPill // should never happen but eh

    // from lidraughtsBus 'startGame
    // sets definitive user ids
    // in case one joined after the socket creation
    case StartGame(game) => self ! SetGame(game.some)

    case d: Deploy =>
      onDeploy(d)
      history.enablePersistence

    case Ping(uid, vOpt, lagCentis) =>
      timeBomb.delay
      ping(uid, lagCentis)
      ownerOf(uid) foreach { o =>
        playerDo(o.color, _.ping)
      }

      // Mobile backwards compat
      vOpt foreach { v =>
        withMember(uid) { member =>
          (history getEventsSince v).fold(resyncNow(member))(batch(member, _))
        }
      }

    case BotConnected(color, v) =>
      playerDo(color, _ setBotConnected v)
      notifyCrowd

    case Bye(color) => playerDo(color, _.setBye)

    case Broom =>
      broom
      if (timeBomb.boom) self ! PoisonPill
      else if (!hasAi) Color.all foreach { c =>
        playerGet(c, _.isGone) foreach { _ ?? notifyGone(c, true) }
      }

    case IsGone(color) => playerGet(color, _.isGone) pipeTo sender

    case IsOnGame(color) => sender ! ownerIsHere(color)

    case GetSocketStatus =>
      playerGet(White, _.isGone) zip playerGet(Black, _.isGone) map {
        case (whiteIsGone, blackIsGone) => SocketStatus(
          version = history.getVersion,
          whiteOnGame = ownerIsHere(White),
          whiteIsGone = whiteIsGone,
          blackOnGame = ownerIsHere(Black),
          blackIsGone = blackIsGone
        )
      } pipeTo sender

    case Join(uid, user, color, playerId, ip, userTv, version) =>
      val (enumerator, channel) = Concurrent.broadcast[JsValue]
      val member = Member(channel, user, color, playerId, ip, userTv = userTv)
      addMember(uid, member)
      notifyCrowd
      if (playerId.isDefined) playerDo(color, _.ping)
      if (member.userTv.isDefined) buscriptions.tv
      val events = version.fold(history.getRecentEvents(5).some) {
        history.getEventsSince
      }

      val initialMsgs = events.fold(resyncMessage.some) {
        batchMsgs(member, _)
      } map { m => Enumerator(m: JsValue) }

      sender ! Connected(
        initialMsgs.fold(enumerator) { _ >>> enumerator },
        member
      )

    case Nil =>
    case eventList: EventList => notify(eventList.events)

    case lidraughts.chat.actorApi.ChatLine(chatId, line) => notify(List(line match {
      case l: lidraughts.chat.UserLine => Event.UserMessage(l, chatId == chatIds.pub)
      case l: lidraughts.chat.PlayerLine => Event.PlayerMessage(l)
    }))

    case a: lidraughts.analyse.actorApi.AnalysisProgress =>
      import lidraughts.analyse.{ JsonView => analysisJson }
      notifyAll("analysisProgress", Json.obj(
        "analysis" -> analysisJson.bothPlayers(a.game, a.analysis),
        "tree" -> TreeBuilder(
          id = a.analysis.id,
          pdnmoves = a.game.pdnMoves,
          variant = a.variant,
          analysis = a.analysis.some,
          initialFen = a.initialFen,
          withFlags = JsonView.WithFlags(),
          clocks = none,
          iteratedCapts = a.game.imported,
          mergeCapts = true
        )
      ))

    case ChangeFeatured(_, msg) => foreachWatcher(_ push msg)

    case TvSelect(msg) => foreachWatcher(_ push msg)

    case UserStartGame(userId, game) => foreachWatcher { m =>
      if (m.onUserTv(userId) && m.userId.fold(true)(id => !game.userIds.contains(id)))
        m push makeMessage("resync")
    }

    case SimulNextGame(hostId, game) => foreachWatcher { m =>
      if (m.onUserTv(hostId) && m.userId.fold(true)(id => !game.userIds.contains(id)))
        m push makeMessage("simultv", game.id)
    }

    case NotifyCrowd =>
      delayedCrowdNotification = false
      showSpectators(lightUser)(members.values.filter(_.watcher)) foreach { spectators =>
        val event = Event.Crowd(
          white = ownerIsHere(White),
          black = ownerIsHere(Black),
          watchers = spectators
        )
        notifyAll(event.typ, event.data)
      }

    case TourStanding(json) => notifyOwners("tourStanding", json)
    case SimulStanding(json) => notifyAll("simulStanding", json)

  }: Actor.Receive) orElse lidraughts.chat.Socket.out(
    send = (t, d, _) => notifyAll(t, d)
  )

  override def quit(uid: Uid) = withMember(uid) { member =>
    super.quit(uid)
    notifyCrowd
  }

  def notifyCrowd: Unit = {
    if (!delayedCrowdNotification) {
      delayedCrowdNotification = true
      context.system.scheduler.scheduleOnce(1 second, self, NotifyCrowd)
    }
  }

  def notify(events: Events): Unit = {
    val vevents = history addEvents events
    members.foreachValue { m => batch(m, vevents) }
  }

  def batchMsgs(member: Member, vevents: List[VersionedEvent]) = vevents match {
    case Nil => None
    case List(one) => one.jsFor(member).some
    case many => makeMessage("b", many map (_ jsFor member)).some
  }

  def batch(member: Member, vevents: List[VersionedEvent]) =
    batchMsgs(member, vevents) foreach member.push

  def notifyOwner[A: Writes](color: Color, t: String, data: A) =
    withOwnerOf(color) {
      _ push makeMessage(t, data)
    }

  def notifyGone(color: Color, gone: Boolean): Unit = {
    notifyOwner(!color, "gone", gone)
  }

  def withOwnerOf(color: Color)(f: Member => Unit) =
    members.foreachValue { m =>
      if (m.owner && m.color == color) f(m)
    }

  def notifyOwners[A: Writes](t: String, data: A) =
    members.foreachValue { m =>
      if (m.owner) m push makeMessage(t, data)
    }

  def ownerIsHere(color: Color) = playerGet(color, _.isBotConnected) ||
    members.values.exists { m =>
      m.owner && m.color == color
    }

  def ownerOf(uid: Uid): Option[Member] =
    members get uid.value filter (_.owner)

  def foreachWatcher(f: Member => Unit): Unit = members.foreachValue { m =>
    if (m.watcher) f(m)
  }

  private def playerGet[A](color: Color, getter: Player => A): A =
    getter(color.fold(whitePlayer, blackPlayer))

  private def playerDo(color: Color, effect: Player => Unit): Unit = {
    effect(color.fold(whitePlayer, blackPlayer))
  }
}

object Socket {

  case class ChatIds(priv: Chat.Id, pub: Chat.Id) {
    def update(g: Game) =
      g.tournamentId.map { id => copy(priv = Chat.Id(id)) } orElse
        g.simulId.map { id => copy(priv = Chat.Id(id)) } getOrElse
        this
  }
}
