import { h } from 'snabbdom'
import { VNodeData } from 'snabbdom/vnode'
import { Hooks } from 'snabbdom/hooks'
import * as cg from 'draughtsground/types'
import { opposite } from 'draughtsground/util';
import { Redraw, EncodedDests, DecodedDests } from './interfaces';
import { decomposeUci } from 'draughts'

const pieceScores = {
  man: 1,
  king: 2,
  ghostman: 0,
  ghostking: 0
};

export function justIcon(icon: string): VNodeData {
  return {
    attrs: { 'data-icon': icon }
  };
}

export function uci2move(uci: string): cg.Key[] | undefined {
    if (!uci)
        return undefined;
    else
        return decomposeUci(uci);
}

export function onInsert(f: (el: HTMLElement) => void): Hooks {
  return {
    insert(vnode) {
      f(vnode.elm as HTMLElement);
    }
  };
}

export function bind(eventName: string, f: (e: Event) => void, redraw?: Redraw, passive: boolean = true): Hooks {
  return onInsert(el => {
    el.addEventListener(eventName, !redraw ? f : e => {
      const res = f(e);
      redraw();
      return res;
    }, { passive });
  });
}

export function parsePossibleMoves(dests?: EncodedDests): DecodedDests {
  if (!dests) return {};
  const dec: DecodedDests = {};
  if (typeof dests == 'string')
    dests.split(' ').forEach(ds => {
      dec[ds.slice(0,2)] = ds.slice(2).match(/.{2}/g) as cg.Key[];
    });
  else for (let k in dests) dec[k] = dests[k].match(/.{2}/g) as cg.Key[];
  return dec;
}

// {white: {man: 3}, black: {king: 1}}
export function getMaterialDiff(pieces: cg.Pieces): cg.MaterialDiff {
  const diff: cg.MaterialDiff = {
    white: { king: 0, man: 0 },
    black: { king: 0, man: 0 }
  };
  for (let k in pieces) {
    const p = pieces[k]!;
    if (p.role != "ghostman" && p.role != "ghostking") {
      const them = diff[opposite(p.color)]
      if (them[p.role] > 0) them[p.role]--;
      else diff[p.color][p.role]++;
    }
  }
  return diff;
}

export function getScore(pieces: cg.Pieces): number {
  let score = 0, k;
  for (k in pieces) {
    score += pieceScores[pieces[k]!.role] * (pieces[k]!.color === 'white' ? 1 : -1);
  }
  return score;
}

export function spinner() {
  return h('div.spinner', {
    'aria-label': 'loading'
  }, [
    h('svg', { attrs: { viewBox: '0 0 40 40' } }, [
      h('circle', {
        attrs: { cx: 20, cy: 20, r: 18, fill: 'none' }
      })])]);
}
