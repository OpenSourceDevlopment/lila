import { h } from 'snabbdom';
import { VNode } from 'snabbdom/vnode';
import AnalyseCtrl from '../../ctrl';
import { innerHTML } from '../../util';
import { view as multiBoardView } from '../multiBoard';
import { view as keyboardView } from '../../keyboard';
import * as studyView from '../studyView';

export default function(ctrl: AnalyseCtrl): VNode | undefined {
  const study = ctrl.study;
  const relay = study && study.relay;
  if (study && relay && relay.intro.active) return h('div.intro', [
    h('div.intro__text', [
      h('h1', study.data.name),
      h('div', {
        hook: innerHTML(relay.data.markup, () => relay.data.markup!)
      })
    ]),
    ctrl.keyboardHelp ? keyboardView(ctrl) : null,
    ctrl.study ? studyView.overboard(ctrl.study) : null,
    multiBoardView(study.multiBoard, study)
  ]);
}
