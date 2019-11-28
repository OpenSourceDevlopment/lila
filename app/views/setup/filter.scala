package views.html.setup

import play.api.data.{ Form, Field }

import lidraughts.api.Context
import lidraughts.app.templating.Environment._
import lidraughts.app.ui.ScalatagsTemplate._
import lidraughts.rating.RatingRange

import controllers.routes

object filter {

  import bits._

  def apply(form: Form[_], filter: lidraughts.setup.FilterConfig)(implicit ctx: Context) =
    st.form(action := routes.Setup.filter(), novalidate := true)(
      table(
        tbody(
          tr(cls := "variant")(
            td(trans.variant()),
            td(renderCheckboxes(form, "variant", filter.variant.map(_.id.toString), translatedVariantChoicesWithVariants))
          ),
          tr(
            td(trans.timeControl()),
            td(renderCheckboxes(form, "speed", filter.speed.map(_.id.toString), translatedSpeedChoices))
          ),
          ctx.isAuth option tr(
            td(trans.mode()),
            td(renderCheckboxes(form, "mode", filter.mode.map(_.id.toString), translatedModeChoices))
          ),
          ctx.me.map { me =>
            tr(
              td(trans.ratingRange()),
              td(cls := "rating_range_config")(
                label(cls := "range")("? - ?"),
                div(cls := "rating_range")(
                  renderInput(form("ratingRange"))(
                    `type` := "hidden",
                    dataMin := RatingRange.min,
                    dataMax := RatingRange.max
                  )
                )
              )
            )
          }
        )
      ),
      ctx.isAnon option frag(
        renderInput(form("mode"))(`type` := "hidden"),
        renderInput(form("ratingRange"))(`type` := "hidden")
      ),
      div(cls := "actions")(
        button(`type` := "submit", cls := "reset button text", dataIcon := "k")(trans.reset()),
        button(`type` := "submit", cls := "submit button text", dataIcon := "E")(trans.apply())
      )
    )

  def renderCheckboxes(
    form: Form[_],
    key: String,
    checks: List[String],
    options: Seq[(Any, String, Option[String])],
    disabled: Boolean = false
  )(implicit ctx: Context): Frag =
    options.zipWithIndex.map {
      case ((value, text, hint), index) => div(cls := "checkable")(
        renderCheckbox(form, key, index, value.toString, checks, raw(text), hint, disabled)
      )
    }

  private def renderCheckbox(
    form: Form[_],
    key: String,
    index: Int,
    value: String,
    checks: List[String],
    content: Frag,
    hint: Option[String],
    disabled: Boolean = false
  ) = frag(
    label(cls := "hover", title := hint)(
      input(
        `type` := "checkbox",
        name := s"${form(key).name}[$index]",
        st.value := value,
        checked := checks.has(value).option(true),
        st.disabled := disabled.option(true)
      )(content)
    ),
    (disabled && checks.has(value)) ?? st.input(
      name := s"${form(key).name}[$index]",
      st.value := value,
      `type` := "hidden"
    ).toHtml
  )
}
