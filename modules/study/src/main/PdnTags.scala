package lidraughts.study

import draughts.format.pdn.{ Tag, Tags, TagType }

object PdnTags {

  def apply(tags: Tags): Tags =
    tags |> replaceRatings |> filterRelevant |> removeContradictingTermination |> sort

  def setRootClockFromTags(c: Chapter): Option[Chapter] =
    c.updateRoot { _.setClockAt(c.tags.clockConfig map (_.limit), Path.root) } filter (c !=)

  private def replaceRatings(tags: Tags) =
    tags |> replaceWhiteRating |> replaceBlackRating

  private def replaceWhiteRating(tags: Tags) = {
    val whiteRating = tags(_.WhiteRating)
    if (whiteRating.isDefined && !tags.exists(_.WhiteElo)) Tags(tags.value.map(t => if (t.name == Tag.WhiteRating) Tag(_.WhiteElo, whiteRating.get) else t))
    else tags
  }

  private def replaceBlackRating(tags: Tags) = {
    val blackRating = tags(_.BlackRating)
    if (blackRating.isDefined && !tags.exists(_.BlackElo)) Tags(tags.value.map(t => if (t.name == Tag.BlackRating) Tag(_.BlackElo, blackRating.get) else t))
    else tags
  }

  private def filterRelevant(tags: Tags) =
    Tags(tags.value.filter { t =>
      relevantTypeSet(t.name) && !unknownValues(t.value)
    })

  private def removeContradictingTermination(tags: Tags) =
    if (tags.resultColor.isDefined)
      Tags(tags.value.filterNot { t =>
        t.name == Tag.Termination && t.value.toLowerCase == "unterminated"
      })
    else tags

  private val unknownValues = Set("", "?", "unknown")

  private val sortedTypes: List[TagType] = {
    import Tag._
    List(
      White, WhiteElo, WhiteTitle, WhiteTeam,
      Black, BlackElo, BlackTitle, BlackTeam,
      TimeControl,
      Date,
      Result,
      Termination,
      Site, Event, Round, Annotator
    )
  }

  val typesToString = sortedTypes mkString ","

  private val relevantTypeSet: Set[TagType] = sortedTypes.toSet

  private val typePositions: Map[TagType, Int] = sortedTypes.zipWithIndex.toMap

  private def sort(tags: Tags) = Tags {
    tags.value.sortBy { t =>
      typePositions.getOrElse(t.name, Int.MaxValue)
    }
  }
}
