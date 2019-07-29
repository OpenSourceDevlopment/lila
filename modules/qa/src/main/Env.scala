package lidraughts.qa

import com.typesafe.config.Config
import lidraughts.common.DetectLanguage
import akka.actor._

final class Env(
    config: Config,
    hub: lidraughts.hub.Env,
    detectLanguage: DetectLanguage,
    mongoCache: lidraughts.memo.MongoCache.Builder,
    asyncCache: lidraughts.memo.AsyncCache.Builder,
    notifyApi: lidraughts.notify.NotifyApi,
    system: akka.actor.ActorSystem,
    db: lidraughts.db.Env
) {

  private val CollectionQuestion = config getString "collection.question"
  private val CollectionAnswer = config getString "collection.answer"

  private lazy val questionColl = db(CollectionQuestion)

  lazy val api = new QaApi(
    questionColl = questionColl,
    answerColl = db(CollectionAnswer),
    mongoCache = mongoCache,
    asyncCache = asyncCache,
    notifier = notifier
  )

  private lazy val notifier = new Notifier(
    notifyApi = notifyApi,
    timeline = hub.actor.timeline
  )

  lazy val search = new Search(questionColl)

  lazy val forms = new DataForm(hub.actor.captcher, detectLanguage)

  system.lidraughtsBus.subscribe(system.actorOf(Props(new Actor {
    def receive = {
      case lidraughts.user.User.GDPRErase(user) => for {
        _ <- api.question erase user
        _ <- api.answer erase user
      } yield ()
    }
  })), 'gdprErase)
}

object Env {

  lazy val current = "qa" boot new Env(
    config = lidraughts.common.PlayApp loadConfig "qa",
    hub = lidraughts.hub.Env.current,
    detectLanguage = DetectLanguage(lidraughts.common.PlayApp loadConfig "detectlanguage"),
    mongoCache = lidraughts.memo.Env.current.mongoCache,
    asyncCache = lidraughts.memo.Env.current.asyncCache,
    notifyApi = lidraughts.notify.Env.current.api,
    system = lila.common.PlayApp.system,
    db = lidraughts.db.Env.current
  )
}
