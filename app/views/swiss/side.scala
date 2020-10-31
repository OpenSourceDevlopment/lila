package views
package html.swiss

import lidraughts.api.Context
import lidraughts.app.templating.Environment._
import lidraughts.app.ui.ScalatagsTemplate._
import lidraughts.common.String.html.richText
import lidraughts.swiss.Swiss

import controllers.routes

object side {

  private val separator = " • "

  def apply(s: Swiss, chat: Boolean)(implicit ctx: Context) = frag(
    div(cls := "swiss__meta")(
      st.section(dataIcon := s.perfType.map(_.iconChar.toString))(
        div(
          p(
            s.clock.show,
            separator,
            if (s.variant.exotic) {
              views.html.game.bits.variantLink(
                s.variant,
                s.variant.name
              )
            } else s.perfType.map(_.name),
            separator,
            s"${s.round}/${s.nbRounds} rounds"
          ),
          if (s.rated) trans.ratedTournament() else trans.casualTournament(),
          separator,
          "Swiss",
          (isGranted(_.ManageTournament) || (ctx.userId.has(s.createdBy) && !s.isFinished)) option frag(
            " ",
            a(href := routes.Tournament.edit(s.id.value), title := "Edit tournament")(iconTag("%"))
          )
        )
      ),
      s.description map { d =>
        st.section(cls := "description")(richText(d))
      },
      teamLink(s.teamId),
      !s.isStarted option absClientDateTime(s.startsAt)
    ),
    chat option views.html.chat.frag
  )
}
