package views.html
package account

import play.api.data.Form

import lidraughts.api.Context
import lidraughts.app.templating.Environment._
import lidraughts.app.ui.ScalatagsTemplate._
import lidraughts.security.EmailConfirm.Help._

import controllers.routes

object emailConfirmHelp {

  def apply(form: Form[_], status: Option[Status])(implicit ctx: Context) = views.html.base.layout(
    title = trans.emailConfirmHelp.txt(),
    moreCss = cssTag("email-confirm"),
    moreJs = jsTag("emailConfirmHelp.js")
  )(frag(
      main(cls := "page-small box box-pad email-confirm-help")(
        h1(trans.emailConfirmHelp()),
        p(trans.emailConfirmNotReceived()),
        st.form(cls := "form3", action := routes.Account.emailConfirmHelp, method := "get")(
          form3.split(
            form3.group(
              form("username"),
              trans.username(),
              help = trans.whatSignupUsername().some
            ) { f =>
                form3.input(f)(pattern := lidraughts.user.User.newUsernameRegex.regex)
              },
            div(cls := "form-group")(
              form3.submit(trans.apply())
            )
          )
        ),
        div(cls := "replies")(
          status map {
            case NoSuchUser(name) => frag(
              p("We couldn't find any user by this name: ", strong(name), "."),
              p(
                "You can use it to ",
                a(href := routes.Auth.signup)("create a new account"), "."
              )
            )
            case EmailSent(name, email) => frag(
              p("We have sent an email to ", email.conceal, "."),
              p(
                "It can take some time to arrive.", br,
                strong("Wait 5 minutes and refresh your email inbox.")
              ),
              p("Also check your spam folder, it might end up there. If so, mark it as NOT spam."),
              p("If everything else fails, then send us this email:"),
              hr,
              p(i(s"Hello, please confirm my account: $name")),
              hr,
              p("Copy and paste the above text and send it to ", contactEmail),
              p("We will come back to you shortly to help you complete your signup.")
            )
            case Confirmed(name) => frag(
              p("The user ", strong(name), " is successfully confirmed."),
              p("You can ", a(href := routes.Auth.login)("login right now as ", name), "."),
              p("You do not need a confirmation email.")
            )
            case Closed(name) =>
              p("The account ", strong(name), " is closed.")
            case NoEmail(name) =>
              p("The account ", strong(name), " was registered without an email.")
          }
        )
      )
    ))
}
