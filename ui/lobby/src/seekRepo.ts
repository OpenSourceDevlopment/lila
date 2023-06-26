import LobbyController from './ctrl';
import { Seek } from './interfaces';

function order(a: Seek, b: Seek) {
  return a.rating > b.rating ? -1 : 1;
}

export function sort(ctrl: LobbyController) {
  ctrl.data.seeks.sort(order);
}

export function initAll(ctrl: LobbyController) {
  ctrl.data.seeks.forEach(function (seek) {
    seek.action = ctrl.data.me && seek.username === ctrl.data.me.username ? 'cancelSeek' : 'joinSeek';
    seek.variant = seek.variant || 'standard';
  });
  sort(ctrl);
}

export function find(ctrl: LobbyController, id: string) {
  return ctrl.data.seeks.find(s => s.id === id);
}
