package lidraughts.practice

import scala.concurrent.duration._

import lidraughts.db.dsl._
import lidraughts.study.{ Chapter, Study }
import lidraughts.user.User

final class PracticeApi(
    coll: Coll,
    configStore: lidraughts.memo.ConfigStore[PracticeConfig],
    asyncCache: lidraughts.memo.AsyncCache.Builder,
    studyApi: lidraughts.study.StudyApi,
    bus: lidraughts.common.Bus
) {

  import BSONHandlers._

  def get(user: Option[User]): Fu[UserPractice] = for {
    struct <- structure.getLang(user.flatMap(_.lang))
    prog <- user.fold(fuccess(PracticeProgress.anon))(progress.get)
  } yield UserPractice(struct, prog)

  def getStudyWithFirstOngoingChapter(user: Option[User], studyId: Study.Id): Fu[Option[UserStudy]] = for {
    up <- get(user)
    chapters <- studyApi.chapterMetadatas(studyId)
    chapter = up.progress firstOngoingIn chapters
    studyOption <- chapter.fold(studyApi byIdWithFirstChapter studyId) { chapter =>
      studyApi.byIdWithChapter(studyId, chapter.id)
    }
  } yield makeUserStudy(studyOption, up, chapters)

  def getStudyWithChapter(user: Option[User], studyId: Study.Id, chapterId: Chapter.Id): Fu[Option[UserStudy]] = for {
    up <- get(user)
    chapters <- studyApi.chapterMetadatas(studyId)
    studyOption <- studyApi.byIdWithChapter(studyId, chapterId)
  } yield makeUserStudy(studyOption, up, chapters)

  private def makeUserStudy(studyOption: Option[Study.WithChapter], up: UserPractice, chapters: List[Chapter.Metadata]) = for {
    rawSc <- studyOption
    sc = rawSc.copy(
      study = rawSc.study.rewindTo(rawSc.chapter).withoutMembers,
      chapter = rawSc.chapter.withoutChildrenIfPractice
    )
    practiceStudy <- up.structure study sc.study.id
    section <- up.structure findSection sc.study.id
    publishedChapters = chapters.filterNot { c =>
      PracticeStructure isChapterNameCommented c.name
    }
    if publishedChapters.exists(_.id == sc.chapter.id)
  } yield UserStudy(up, practiceStudy, publishedChapters, sc, section)

  object config {
    def get = configStore.get map (_ | PracticeConfig.empty)
    def set = configStore.set _
    def form = configStore.makeForm
  }

  object structure {

    private val cacheAll = asyncCache.single[PracticeStructure](
      "practice.structure.all",
      f = for {
        conf <- config.get
        chapters <- studyApi.chapterIdNames(conf.studyIds)
      } yield PracticeStructure.make(conf, chapters, none),
      expireAfter = _.ExpireAfterAccess(3.hours)
    )

    private val cacheLang = asyncCache.clearable[String, PracticeStructure](
      "practice.structure.lang",
      f = lang => for {
        conf <- config.get
        chapters <- studyApi.chapterIdNames(conf.studyIds)
      } yield PracticeStructure.make(conf, chapters, lang.some),
      expireAfter = _.ExpireAfterAccess(3.hours)
    )

    def getAll = cacheAll.get
    def getLang(lang: Option[String]) = cacheLang.get(lang.getOrElse(PracticeStructure.defaultLang))

    def clear = {
      cacheAll.refresh
      cacheLang.invalidateAll
    }

    def onSave(study: Study) = getAll foreach { structure =>
      if (structure.hasStudy(study.id)) clear
    }
  }

  object progress {

    import PracticeProgress.NbMoves

    def get(user: User): Fu[PracticeProgress] =
      coll.uno[PracticeProgress]($id(user.id)) map { _ | PracticeProgress.empty(PracticeProgress.Id(user.id)) }

    private def save(p: PracticeProgress): Funit =
      coll.update($id(p.id), p, upsert = true).void

    def setNbMoves(user: User, chapterId: Chapter.Id, score: NbMoves) = {
      get(user) flatMap { prog =>
        save(prog.withNbMoves(chapterId, score))
      }
    } >>- studyApi.studyIdOf(chapterId).foreach {
      _ ?? { studyId =>
        bus.publish(PracticeProgress.OnComplete(user.id, studyId, chapterId), 'finishPractice)
      }
    }

    def reset(user: User) =
      coll.remove($id(user.id)).void
  }
}
