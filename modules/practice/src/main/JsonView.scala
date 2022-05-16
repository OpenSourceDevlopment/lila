package lidraughts.practice

import play.api.libs.json._

import lidraughts.common.PimpedJson._
import lidraughts.study.JsonView._

object JsonView {

  case class JsData(study: JsObject, analysis: JsObject, practice: JsObject)

  implicit val nbMovesWrites: Writes[PracticeProgress.NbMoves] = intAnyValWriter(_.value)
  implicit val practiceStudyWrites: Writes[PracticeStudy] = OWrites { ps =>
    Json.obj(
      "id" -> ps.id,
      "name" -> ps.name,
      "desc" -> ps.desc
    )
  }
  import PracticeGoal._
  implicit val practiceGoalWrites: Writes[PracticeGoal] = OWrites {
    case Win => Json.obj("result" -> "win")
    case Promote => Json.obj("result" -> "promote")
    case WinIn(moves) => Json.obj("result" -> "winIn", "moves" -> moves)
    case PromoteIn(moves) => Json.obj("result" -> "promoteIn", "moves" -> moves)
    case DrawIn(moves) => Json.obj("result" -> "drawIn", "moves" -> moves)
    case AutoDrawIn(moves) => Json.obj("result" -> "autoDrawIn", "moves" -> moves)
    case EqualIn(moves) => Json.obj("result" -> "equalIn", "moves" -> moves)
    case EvalIn(cp, moves) => Json.obj("result" -> "evalIn", "cp" -> cp, "moves" -> moves)
  }

  def apply(us: UserStudy) = Json.obj(
    "study" -> us.practiceStudy,
    "url" -> us.url,
    "completion" -> JsObject {
      us.practiceStudy.chapters.flatMap { c =>
        us.practice.progress.chapters collectFirst {
          case (id, nbMoves) if id == c.id => id.value -> nbMovesWrites.writes(nbMoves)
        }
      }
    },
    "structure" -> us.practice.structure.sections.map { sec =>
      Json.obj(
        "id" -> sec.id,
        "name" -> sec.name,
        "studies" -> sec.studies.map { stu =>
          Json.obj(
            "id" -> stu.id,
            "slug" -> stu.slug,
            "name" -> stu.name
          )
        }
      )
    }
  )
}
