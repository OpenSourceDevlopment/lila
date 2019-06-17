package lidraughts.study

import draughts.format.pdn.Tags
import lidraughts.game.GameRepo

private final class ChapterTagsFixer(
    repo: ChapterRepo,
    gamePdnDump: lidraughts.game.PdnDump
) {

  def apply(chapter: Chapter, draughtsResult: Boolean): Fu[Chapter] =
    if (chapter.tags.value.nonEmpty) fuccess(chapter)
    else makeNewTags(chapter, draughtsResult) flatMap {
      _.fold(fuccess(chapter)) { newTags =>
        val c2 = chapter.copy(tags = newTags)
        repo update c2 inject c2
      }
    }

  private def makeNewTags(c: Chapter, draughtsResult: Boolean): Fu[Option[Tags]] =
    c.setup.gameId.??(GameRepo.gameWithInitialFen) map {
      _ map {
        case (game, fen) => PdnTags(gamePdnDump.tags(game, fen.map(_.value), none, draughtsResult))
      }
    }
}
