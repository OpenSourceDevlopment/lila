package lidraughts.app
package templating

import play.api.libs.json.JsObject
import play.twirl.api.Html

import lidraughts.common.Lang
import lidraughts.i18n.{ LangList, I18nKey, Translator, JsQuantity, I18nDb, JsDump, TimeagoLocales }
import lidraughts.user.UserContext

trait I18nHelper {

  implicit def ctxLang(implicit ctx: UserContext): Lang = ctx.lang

  def transKey(key: String, db: I18nDb.Ref, args: Seq[Any] = Nil)(implicit lang: Lang): Html =
    Translator.html.literal(key, db, args, lang)

  def i18nJsObject(keys: Seq[I18nKey])(implicit lang: Lang): JsObject =
    JsDump.keysToObject(keys, I18nDb.Site, lang)

  def i18nOptionJsObject(keys: Option[I18nKey]*)(implicit lang: Lang): JsObject =
    JsDump.keysToObject(keys.flatten, I18nDb.Site, lang)

  def i18nFullDbJsObject(db: I18nDb.Ref)(implicit lang: Lang): JsObject =
    JsDump.dbToObject(db, lang)

  def timeagoLocaleScript(implicit ctx: lidraughts.api.Context): String = {
    TimeagoLocales.js.get(ctx.lang.code) orElse
      TimeagoLocales.js.get(ctx.lang.language) getOrElse
      ~TimeagoLocales.js.get("en")
  }

  def langName = LangList.nameByStr _

  def shortLangName(str: String) = langName(str).takeWhile(','!=)
}
