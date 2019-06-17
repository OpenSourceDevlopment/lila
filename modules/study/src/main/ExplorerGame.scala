package lidraughts.study

import draughts.format.pdn.{ Parser, ParsedPdn, Tag }
import draughts.format.FEN
import lidraughts.common.LightUser
import lidraughts.game.{ Game, Namer }
import lidraughts.tree.Node.Comment
import lidraughts.user.User

private final class ExplorerGame(
    importer: lidraughts.explorer.ExplorerImporter,
    lightUser: LightUser.GetterSync,
    baseUrl: String
) {

  def quote(gameId: Game.ID): Fu[Option[Comment]] =
    importer(gameId) map {
      _ ?? { game =>
        gameComment(game).some
      }
    }

  def insert(userId: User.ID, study: Study, position: Position, gameId: Game.ID, draughtsResult: Boolean): Fu[Option[(Chapter, Path)]] =
    if (position.chapter.isOverweight) {
      logger.info(s"Overweight chapter ${study.id}/${position.chapter.id}")
      fuccess(none)
    } else importer(gameId) map {
      _ ?? { game =>
        position.node ?? { fromNode =>
          GameToRoot(game, none, false, draughtsResult, false).|> { root =>
            root.setCommentAt(
              comment = gameComment(game),
              path = Path(root.mainline.map(_.id))
            )
          } ?? { gameRoot =>
            merge(fromNode, position.path, gameRoot) flatMap {
              case (newNode, path) => position.chapter.addNode(newNode, path) map (_ -> path)
            }
          }
        }
      }
    }

  private def truncateFen(f: FEN) = f.value split ' ' take 4 mkString " "
  private def compareFens(a: FEN, b: FEN) = truncateFen(a) == truncateFen(b)

  private def merge(fromNode: RootOrNode, fromPath: Path, game: Node.Root): Option[(Node, Path)] = {
    val gameNodes = game.mainline.dropWhile(n => !compareFens(n.fen, fromNode.fen)) drop 1
    val (path, foundGameNode) = gameNodes.foldLeft((Path.root, none[Node])) {
      case ((path, None), gameNode) =>
        val nextPath = path + gameNode
        fromNode.children.nodeAt(nextPath) match {
          case Some(child) => (nextPath, none)
          case None => (path, gameNode.some)
        }
      case (found, _) => found
    }
    foundGameNode.map { _ -> fromPath.+(path) }
  }

  private def gameComment(game: Game) = Comment(
    id = Comment.Id.make,
    text = Comment.Text(s"${gameTitle(game)}, ${gameUrl(game)}"),
    by = Comment.Author.Lidraughts
  )

  private def gameUrl(game: Game) = s"$baseUrl/${game.id}"

  private def gameTitle(g: Game): String = {
    val pdn = g.pdnImport.flatMap(pdnImport => Parser.full(pdnImport.pdn).toOption)
    val white = pdn.flatMap(_.tags(_.White)) | Namer.playerText(g.whitePlayer)(lightUser)
    val black = pdn.flatMap(_.tags(_.Black)) | Namer.playerText(g.blackPlayer)(lightUser)
    val result = draughts.Color.showResult(g.winnerColor, lidraughts.pref.Pref.default.draughtsResult)
    val event: Option[String] =
      (pdn.flatMap(_.tags(_.Event)), pdn.flatMap(_.tags.year).map(_.toString)) match {
        case (Some(event), Some(year)) if event.contains(year) => event.some
        case (Some(event), Some(year)) => s"$event, $year".some
        case (eventO, yearO) => eventO orElse yearO
      }
    s"$white - $black, $result, ${event | "-"}"
  }
}
