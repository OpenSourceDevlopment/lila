package views.html.tournament

import lidraughts.api.Context
import lidraughts.app.templating.Environment._
import lidraughts.app.ui.ScalatagsTemplate._
import lidraughts.rating.PerfType
import lidraughts.tournament.TournamentShield

import controllers.routes

object shields {

  private val section = st.section(cls := "tournament-shields__item")

  def apply(history: TournamentShield.History)(implicit ctx: Context) =
    views.html.base.layout(
      title = "Tournament shields",
      moreCss = cssTag("tournament.leaderboard"),
      wrapClass = "full-screen-force"
    ) {
        main(cls := "page-menu")(
          views.html.user.bits.communityMenu("shield"),
          div(cls := "page-menu__content box box-pad")(
            h1("Tournament shields"),
            div(cls := "tournament-shields")(
              history.sorted.map {
                case (categ, awards) => {
                  section(
                    h2(
                      a(href := routes.Tournament.categShields(categ.key))(
                        span(cls := "shield-trophy")(categ.iconChar.toString),
                        categ.name
                      )
                    ),
                    ol(
                      if (awards.isEmpty) li(nbsp)
                      else awards.map { aw =>
                        li(
                          userIdLink(aw.owner.value.some),
                          a(href := routes.Tournament.show(aw.tourId))(showDate(aw.date))
                        )
                      }
                    )
                  )
                }
              }
            )
          )
        )
      }

  def byCateg(categ: TournamentShield.Category, awards: List[TournamentShield.Award])(implicit ctx: Context) =
    views.html.base.layout(
      title = "Tournament shields",
      moreCss = frag(cssTag("tournament.leaderboard"), cssTag("slist"))
    ) {
        main(cls := "page-menu page-small tournament-categ-shields")(
          views.html.user.bits.communityMenu("shield"),
          div(cls := "page-menu__content box")(
            h1(
              a(href := routes.Tournament.shields, dataIcon := "I", cls := "text"),
              categ.name, " shields"
            ),
            ol(
              if (awards.isEmpty) li(span("No shield winners yet!"))
              else awards.map { aw =>
                li(
                  span(cls := "shield-trophy")(categ.iconChar.toString),
                  userIdLink(aw.owner.value.some),
                  a(href := routes.Tournament.show(aw.tourId))(showDate(aw.date))
                )
              }
            )
          )
        )
      }
}
