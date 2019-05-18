package lidraughts.simul

import org.joda.time.DateTime

case class Spotlight(
    headline: String,
    description: String,
    startsAt: DateTime,
    homepageHours: Option[Int] = None, // feature on homepage hours before start
    iconImg: Option[String] = None,
    ceval: Option[Simul.EvalSetting] = None,
    fmjdRating: Option[Simul.ShowFmjdRating] = None,
    drawLimit: Option[Int] = None
) {

  def isNow = startsAt.isBefore(DateTime.now)
}
