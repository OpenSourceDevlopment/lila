import AnalyseCtrl from '../../ctrl';
import { Goal } from './interfaces';
import { Comment } from '../../practice/practiceCtrl';
import { read as fenRead, countGhosts, countKings } from 'draughtsground/fen';

// returns null if not deep enough to know
function isDrawish(node: Tree.Node, v: VariantKey): boolean | null {
  if (!hasSolidEval(node, v)) return null;
  console.log(node.ceval!.cp!);
  return !node.ceval!.win && Math.abs(node.ceval!.cp!) < 85;
}
// returns null if not deep enough to know
function isWinning(node: Tree.Node, goalCp: number, color: Color, v: VariantKey): boolean | null {
  if (!hasSolidEval(node, v)) return null;
  const cp = node.ceval!.win! > 0 ? 99999 : (node.ceval!.win! < 0 ? -99999 : node.ceval!.cp);
  return color === 'white' ? cp! >= goalCp : cp! <= goalCp;
}
// returns null if not deep enough to know
function myWinIn(node: Tree.Node, color: Color, v: VariantKey): number | boolean | null {
  if (!hasSolidEval(node, v)) return null;
  if (!node.ceval!.win) return false;
  var winIn = node.ceval!.win! * (color === 'white' ? 1 : -1);
  return winIn > 0 ? winIn : false;
}
// returns null if not deep enough to know
function theirWinIn(node: Tree.Node, color: Color, v: VariantKey): number | boolean | null {
  if (!hasSolidEval(node, v)) return null;
  if (!node.ceval!.win) return false;
  var winIn = node.ceval!.win! * (color === 'white' ? -1 : 1);
  return winIn > 0 ? winIn : false;
}

function hasSolidEval(node: Tree.Node, v: VariantKey) {
  return node.ceval && node.ceval.depth >= (v === 'antidraughts' ? 7 : 17);
}

function isDraw(root: AnalyseCtrl, node: Tree.Node) {
  return node.threefold || root.gameOver() === 'draw';
}

function isWin(root: AnalyseCtrl,) {
  return root.gameOver() === 'checkmate';
}

function isMyWin(root: AnalyseCtrl) {
  return isWin(root) && root.turnColor() !== root.bottomColor();
}

function isTheirWin(root: AnalyseCtrl) {
  return isWin(root) && root.turnColor() === root.bottomColor();
}

function isMyPromotion(root: AnalyseCtrl, node: Tree.Node) {
  if (countGhosts(node.fen) || !node.uci || root.nodeList.length < 2) return false;
  const color = root.bottomColor(), 
    kings = countKings(node.fen);
  if (!kings || root.turnColor() === color) return false;
  const pieces = fenRead(node.fen),
    field = node.uci.slice(-2),
    piece = field in pieces && pieces[field];
  console.log('isMyPromotion(): field=' + field, piece);
  if (piece && piece.role == 'king' && piece.color === color) {
    const prevNode = root.nodeList[root.nodeList.length - 2],
      prevKings = countKings(prevNode.fen);
    console.log('isMyPromotion(): kings=' + kings + '; prevKings=' + prevKings, node)
    return kings === prevKings + 1;
  }
  return false;
}

function hasBlundered(comment: Comment | null) {
  return comment && (comment.verdict === 'mistake' || comment.verdict === 'blunder');
}


// returns null = ongoing, true = win, false = fail
export default function(root: AnalyseCtrl, goal: Goal, nbMoves: number): boolean | null {
  const node = root.node;
  if (!node.uci) return null;
  if (isTheirWin(root)) return false;
  if (isMyWin(root)) return true;
  if (hasBlundered(root.practice!.comment())) return false;
  const v = root.data.game.variant.key;
  switch (goal.result) {
    case 'autoDrawIn':
      if (isDraw(root, node)) return true;
      const loseIn = theirWinIn(node, root.bottomColor(), v);
      if (loseIn && (loseIn as number) + nbMoves <= goal.moves!) return false
      if (nbMoves >= goal.moves! && root.turnColor() === root.bottomColor()) return isDrawish(node, v);
      break;
    case 'drawIn':
    case 'equalIn':
      if (node.threefold) return true;
      if (isDrawish(node, v) === false) return false;
      if (nbMoves > goal.moves!) return false;
      if (root.gameOver() === 'draw') return true;
      if (nbMoves >= goal.moves!) return isDrawish(node, v);
      break;
    case 'evalIn':
      if (nbMoves >= goal.moves!) return isWinning(node, goal.cp!, root.bottomColor(), v);
      break;
    case 'winIn':
      if (nbMoves > goal.moves!) return false;
      const winIn = myWinIn(node, root.bottomColor(), v);
      if (winIn === null) return null;
      if (!winIn || (winIn as number) + nbMoves > goal.moves!) return false;
      break;
    case 'win':
      if (isDraw(root, node)) return false;
      break;
    case 'promote':
      if (isMyPromotion(root, node)) return true;
      break;
    case 'promoteIn':
      if (isMyPromotion(root, node)) return true;
      if (nbMoves >= goal.moves!) return false;
      break;
  }
  return null;
};
