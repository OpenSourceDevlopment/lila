import { init } from 'snabbdom';
import { VNode } from 'snabbdom/vnode'
import klass from 'snabbdom/modules/class';
import attributes from 'snabbdom/modules/attributes';
import { Draughtsground } from 'draughtsground';
import { LobbyOpts, Tab } from './interfaces';
import LobbyController from './ctrl';

export const patch = init([klass, attributes]);

import makeCtrl from './ctrl';
import view from './view/main';
import boot = require('./boot');

export function start(opts: LobbyOpts) {

  let vnode: VNode, ctrl: LobbyController;

  function redraw() {
    vnode = patch(vnode, view(ctrl));
  }

  ctrl = new makeCtrl(opts, redraw);

  const blueprint = view(ctrl);
  opts.element.innerHTML = '';
  vnode = patch(opts.element, blueprint);

  return {
    socketReceive: ctrl.socket.receive,
    setTab(tab: Tab) {
      ctrl.setTab(tab);
      ctrl.redraw();
    },
    gameActivity: ctrl.gameActivity,
    setRedirecting: ctrl.setRedirecting,
    enterPool: ctrl.enterPool,
    leavePool: ctrl.leavePool,
    redraw: ctrl.redraw
  };
}

// that's for the rest of lidraughts to access draughtsground
// without having to include it a second time
window.Draughtsground = Draughtsground;

window.onload = function() {
  boot(window['lidraughts_lobby'], document.getElementById('hooks_wrap'));
};
