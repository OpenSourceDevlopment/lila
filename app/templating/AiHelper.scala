package lidraughts.app
package templating

import lidraughts.app.ui.ScalatagsTemplate._
import lidraughts.user.UserContext

trait AiHelper { self: I18nHelper =>

  def aiName(level: Int, withRating: Boolean = true)(implicit ctx: UserContext): String = {
    val name = lidraughts.i18n.I18nKeys.aiNameLevelAiLevel.txt("Scan AI", level)
    val rating = withRating ?? {
      aiRating(level) ?? { r => s" ($r)" }
    }
    s"$name$rating"
  }

  def aiNameFrag(level: Int, withRating: Boolean = true)(implicit ctx: UserContext) =
    raw(aiName(level, withRating).replace(" ", "&nbsp;"))

  def aiRating(level: Int): Option[Int] = Env.draughtsnet.aiPerfApi.intRatings get level
}
