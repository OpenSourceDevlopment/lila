import { h, thunk } from 'snabbdom'
import { VNode } from 'snabbdom/vnode'
import { bind, spinner, richHTML, option } from '../../util';
import { StudyCtrl } from '../interfaces';
import { MaybeVNodes } from '../../interfaces';
import { StudyPracticeData, StudyPracticeCtrl } from './interfaces';
import { boolSetting } from '../../boolSetting';
import { view as descView } from '../description';

function selector(data: StudyPracticeData, trans: Trans) {
  return h('select.selector', {
    hook: bind('change', e => {
      location.href = '/practice/' + (e.target as HTMLInputElement).value;
    })
  }, [
    h('option', {
      attrs: { disabled: true, selected: true }
    }, trans.noarg('practiceList')),
    ...data.structure.map(function(section) {
      return h('optgroup', {
        attrs: { label: section.name }
      }, section.studies.map(function(study) {
        return option(
          section.id + '/' + study.slug + '/' + study.id,
          '',
          study.name);
      }));
    })
  ]);
}

function renderGoal(practice: StudyPracticeCtrl, trans: Trans, inMoves: number) {
  const goal = practice.goal();
  switch (goal.result) {
    case 'win':
      return trans.noarg('winTheGame');
    case 'promote':
      return trans.noarg('promoteAKing');
    case 'capture':
      return trans.noarg('playALegalCapture');
    case 'captureAll':
      return trans.plural('playAllLegalCapturesXRemaining', inMoves);
    case 'winIn':
      return trans.plural('winTheGameInX', inMoves);
    case 'promoteIn':
      return trans.plural('promoteAKingInX', inMoves);
    case 'drawIn':
    case 'autoDrawIn':
      return trans.plural('holdTheDrawForX', inMoves);
    case 'equalIn':
      return trans.plural('equalizeInX', inMoves);
    case 'evalIn':
      if (practice.isWhite() === (goal.cp! >= 0))
        return trans.plural('getAWinningPositionInX', inMoves);;
      return trans.plural('defendForX', inMoves);;
  }
}

export function underboard(ctrl: StudyCtrl): MaybeVNodes {
  if (ctrl.vm.loading) return [h('div.feedback', spinner())];
  const p = ctrl.practice!,
    gb = ctrl.gamebookPlay(),
    next = ctrl.nextChapter(),
    pinned = ctrl.data.chapter.description,
    noarg = ctrl.trans.noarg;
  const feedbackSuccess = () => [
    h('a.feedback.win', next ? {
      hook: bind('click', p.goToNext)
    } : {
      attrs: { href: '/practice' }
    }, [
      h('span', noarg('success')),
      next ? noarg('goToNextExercise') : noarg('backToPracticeMenu')
    ])
  ];
  if (gb) {
    if (gb.state.feedback === 'end') return feedbackSuccess();
    else return pinned ? [h('div.feedback.ongoing', [
      h('div.comment', { hook: richHTML(pinned) })
    ])] : [];
  }
  else if (!ctrl.data.chapter.practice) return [descView(ctrl, true)];
  switch (p.success()) {
    case true:
      return feedbackSuccess();
    case false:
      return [
        h('a.feedback.fail', {
          hook: bind('click', p.reset, ctrl.redraw)
        }, [
          h('span', [renderGoal(p, ctrl.trans, p.goal().moves!)]),
          h('strong', noarg('clickToRetry'))
        ])
      ];
    default:
      return [
        h('div.feedback.ongoing', [
          h('div.goal', [renderGoal(p, ctrl.trans, p.goal().moves! - p.nbMoves())]),
          pinned ? h('div.comment', { hook: richHTML(pinned) }) : null
        ]),
        boolSetting({
          name: 'loadNextExerciseImmediately',
          id: 'autoNext',
          checked: p.autoNext(),
          change: p.autoNext
        }, ctrl.trans, ctrl.redraw)
      ];
  }
}

export function side(ctrl: StudyCtrl): VNode {

  const current = ctrl.currentChapter(),
    data = ctrl.practice!.data;

  return h('div.practice__side', [
    h('div.practice__side__title', [
      h('i.' + data.study.id),
      h('div.text', [
        h('h1', data.study.name),
        h('em', data.study.desc)
      ])
    ]),
    h('div.practice__side__chapters', {
      hook: bind('click', e => {
        e.preventDefault();
        const target = e.target as HTMLElement,
          id = (target.parentNode as HTMLElement).getAttribute('data-id') || target.getAttribute('data-id');
        if (id) ctrl.setChapter(id, true);
        return false;
      })
    }, ctrl.chapters.list().map(function(chapter) {
      const loading = ctrl.vm.loading && chapter.id === ctrl.vm.nextChapterId,
        active = !ctrl.vm.loading && current && current.id === chapter.id,
        completion = data.completion[chapter.id] >= 0 ? 'done' : 'ongoing';
      return [
        h('a.ps__chapter', {
          key: chapter.id,
          attrs: {
            href: data.url + '/' + chapter.id,
            'data-id': chapter.id
          },
          class: { active, loading }
        }, [
          h('span.status.' + completion, {
            attrs: {
              'data-icon': ((loading || active) && completion === 'ongoing') ? 'G' : 'E'
            }
          }),
          h('h3', chapter.name)
        ])
      ];
    }).reduce((a, b) => a.concat(b), [])),
    h('div.finally', [
      h('a.back', {
        attrs: {
          'data-icon': 'I',
          href: '/practice',
          title: ctrl.trans.noarg('backToPracticeMenu')
        }
      }),
      thunk('select.selector', selector, [data, ctrl.trans])
    ])
  ]);
}
