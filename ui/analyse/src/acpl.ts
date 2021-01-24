import { h, thunk } from 'snabbdom'
import { VNode, VNodeData } from 'snabbdom/vnode'
import AnalyseCtrl from './ctrl';
import { findTag } from './study/studyChapters';
import * as game from 'game';
import { defined } from 'common';
import { bind, dataIcon } from './util';

function renderRatingDiff(rd: number | undefined): VNode | undefined {
  if (rd === 0) return h('span', '±0');
  if (rd && rd > 0) return h('good', '+' + rd);
  if (rd && rd < 0) return h('bad', '−' + (-rd));
  return;
}

function renderPlayer(ctrl: AnalyseCtrl, color: Color): VNode {
  const p = game.getPlayer(ctrl.data, color);
  if (p.user) return h('a.user-link.ulpt', {
    attrs: { href: '/@/' + p.user.username }
  }, [
    p.user.username, ' ',
    renderRatingDiff(p.ratingDiff)
  ]);
  return h('span',
     p.name ||
     (p.ai && 'Scan level ' + p.ai) ||
     (ctrl.study && findTag(ctrl.study.data.chapter.tags, color)) ||
     'Anonymous');
}

function renderBestMovesPct(ctrl: AnalyseCtrl, color: Color): VNode[] {
  const d = ctrl.data;
  if (d.analysis && d.treeParts.length) {
    const nbm = d.analysis[color].nbm;
    if (nbm) {
      const first = d.treeParts[0], 
        last = d.treeParts[d.treeParts.length - 1],
        totalPlies = Math.floor(((last.displayPly || last.ply) - first.ply) / 2),
        pct = 100 - Math.floor(nbm.length * 100 / totalPlies);
      return [h('span.bestmoves',
        { hook: bind('mousedown', () => ctrl.toggleBestMoves(color)) },
        pct + '%'
      ), h('span', ' (' + (totalPlies - nbm.length) + '/' + totalPlies + ')')];
    }
  }
  return [];
}

const advices = [
  ['inaccuracy', 'inaccuracies', '?!'],
  ['mistake', 'mistakes', '?'],
  ['blunder', 'blunders', '??']
];

function playerTable(ctrl: AnalyseCtrl, color: Color): VNode {
  const d = ctrl.data, trans = ctrl.trans.noarg;
  const acpl = d.analysis![color].acpl;
  const mt = d.analysis![color].mt;
  return h('table', {
    hook: {
      insert(vnode) {
        window.lidraughts.powertip.manualUserIn(vnode.elm);
      }
    }
  }, [
      h('thead', h('tr', [
        h('td', h('i.is.color-icon.' + color)),
        h('th', [renderPlayer(ctrl, color)].concat(renderBestMovesPct(ctrl, color)))
      ])),
      h('tbody',
        advices.map(a => {
          const nb: number = d.analysis![color][a[0]];
          const attrs: VNodeData = nb ? {
            'data-color': color,
            'data-symbol': a[2]
          } : {};
          return h('tr' + (nb ? '.symbol' : ''), { attrs }, [
            h('td', '' + nb),
            h('th', trans(a[1]))
          ]);
        }).concat(
          h('tr', [
            h('td', '' + (defined(acpl) ? acpl : '?')),
            h('th', trans('averageCentipieceLoss'))
          ])
        ).concat(
          (mt ? [mt] : []).map(mt =>
            h('tr', [
              h('td', Math.round(mt.avg / 10) + '±' + Math.round(mt.sd / 10)),
              h('th', 'Move times' + (mt.cmt ? ' (' + mt.cmt + ')' : ''))
            ])
          )
        )
      )
    ])
}

function doRender(ctrl: AnalyseCtrl): VNode {
  return h('div.advice-summary', {
    hook: {
      insert: vnode => {
        $(vnode.elm as HTMLElement).on('click', 'tr.symbol', function(this: Element) {
          ctrl.jumpToGlyphSymbol($(this).data('color'), $(this).data('symbol'));
        });
      }
    }
  }, [
    playerTable(ctrl, 'white'),
    ctrl.study ? null : h('a.button.text', {
      class: { active: !!ctrl.retro },
      attrs: dataIcon('G'),
      hook: bind('click', ctrl.toggleRetro, ctrl.redraw)
    }, ctrl.trans.noarg('learnFromYourMistakes')),
    playerTable(ctrl, 'black')
  ]);
}

export function render(ctrl: AnalyseCtrl): VNode | undefined {

  if (ctrl.studyPractice || ctrl.embed) return;

  if (!ctrl.data.analysis || !ctrl.showComputer() || (ctrl.study && ctrl.study.vm.toolTab() !== 'serverEval'))
    return h('div.analyse__acpl');

  // don't cache until the analysis is complete!
  const buster = ctrl.data.analysis.partial ? Math.random() : '';
  let cacheKey = '' + buster + !!ctrl.retro;
  if (ctrl.study) cacheKey += ctrl.study.data.chapter.id;

  return h('div.analyse__acpl', thunk('div.advice-summary', doRender, [ctrl, cacheKey]));
}
