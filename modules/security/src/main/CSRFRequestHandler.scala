package lidraughts.security

import play.api.mvc.RequestHeader

import lidraughts.common.HTTPRequest._

final class CSRFRequestHandler(domain: String, enabled: Boolean) {

  private def logger = lidraughts.log("csrf")

  def check(req: RequestHeader): Boolean = {
    if (isXhr(req)) true // cross origin xhr not allowed by browsers
    else if (isSafe(req) && !isSocket(req)) true
    else origin(req) match {
      case None =>
        lidraughts.mon.http.csrf.missingOrigin()
        logger.debug(print(req))
        true
      case Some("file://") =>
        true
      case Some(o) if o == localAppOrigin =>
        true
      case Some(o) if isSubdomain(o) =>
        true
      case Some(_) =>
        if (isSocket(req)) {
          lidraughts.mon.http.csrf.websocket()
          logger.info(s"WS ${print(req)}")
        } else {
          lidraughts.mon.http.csrf.forbidden()
          logger.info(print(req))
        }
        !enabled
    }
  }

  private val topDomain = s"://$domain"
  private val subDomain = s".$domain"

  // origin = "https://lidraughts.org"
  // domain = "lidraughts.org"
  private def isSubdomain(origin: String) =
    origin.endsWith(subDomain) || origin.endsWith(topDomain)
}
