var m = require('mithril');
var simul = require('../simul');
var util = require('./util');
var xhr = require('../xhr');
var ceval = require('./ceval');
var status = require('game').status;

function pad2(num) {
  return (num < 10 ? '0' : '') + num;
}

function gameDesc(pairing, host) {
  if (pairing.hostColor === 'white')
    return host + ' vs. ' + pairing.player.username;
  else
    return pairing.player.username + ' vs. ' + host;
}

function formatClockTime(seconds) {
  var date = new Date(seconds * 1000),
    millis = date.getUTCMilliseconds(),
    sep = (millis < 500) ? '<sep class="low">:</sep>' : '<sep>:</sep>',
  baseStr = pad2(date.getUTCMinutes()) + sep + pad2(date.getUTCSeconds());
  if (seconds >= 3600) {
    var hours = pad2(Math.floor(seconds / 3600));
    return hours + '<sep>:</sep>' + baseStr;
  }
  return baseStr;
}

function evalDesc(eval) {
  return eval ? ('Depth ' + eval.depth + ':\n' + eval.moves) : '';
}

module.exports = function(ctrl) {
  return (ctrl.toggleArbiter && ctrl.arbiterData && simul.amArbiter(ctrl)) ? [ m('div.arbiter-panel', [
    m('table.slist.user_list',
      m('thead', m('tr', [
        m('th', { colspan: 2 }, 'Arbiter control panel'),
        m('th', m('span.hint--top-left', { 'data-hint': 'The FMJD rating set on the user\'s profile.' }, 'FMJD')),
        m('th', m('span.hint--top-left', { 'data-hint': 'Simul participant clock time remaining.' }, 'Player clock')),
        m('th', m('span.hint--top-left', { 'data-hint': 'Simul host clock time remaining.' }, 'Host clock')),
        m('th', m('span.hint--top-left', { 'data-hint': 'Scan 3.0 evaluation of the current position.' }, 'Eval')),
        m('th', m('span.hint--top-left', { 'data-hint': 'The percentage of moves in which the user left the game page.' }, 'Blurs')),
        m('th', m('span.hint--top-left', { 'data-hint': 'Stop the game by settling it as a win, draw or loss.' }, 'Settle'))
      ])),
      m('tbody', ctrl.data.pairings.map(function(pairing) {
      var variant = util.playerVariant(ctrl, pairing.player);
      var data = ctrl.arbiterData.find( (d) => d.id == pairing.player.id)
      var playing = pairing.game.status < status.ids.aborted;
      var result = !playing ? (
        pairing.winnerColor === 'white' ? '1-0' : (pairing.winnerColor === 'black' ? '0-1' : '½-½')
      ) : '*';
      return m('tr', [
        m('td', util.player(pairing.player, pairing.player.rating, pairing.player.provisional, '')),
        m('td.variant', { 'data-icon': variant.icon }, m('span', result)),
        m('td', pairing.player.officialRating ? pairing.player.officialRating : '-'),
        m('td', (data && data.clock !== undefined) ? m(
          (playing && pairing.hostColor !== data.turnColor) ? 'div.time.running' : 'div.time',
          m.trust(formatClockTime(data.clock))
        ) : '-'),
        m('td', (data && data.hostClock !== undefined) ? m(
          (playing && pairing.hostColor === data.turnColor) ? 'div.time.running' : 'div.time',
          m.trust(formatClockTime(data.hostClock))
        ) : '-'),
        m('td', m('span', { title: evalDesc(data.ceval) }, ceval.renderEval(data.ceval))),
        m('td', (data && data.blurs !== undefined) ? (data.blurs + '%') : '-' ),
        m('td.action', !playing ? '-' : m('a.button.hint--top-left', {
          'data-icon': '2',
          'title': 'Settle ' + gameDesc(pairing, ctrl.data.host.username) + ' as a win/draw/loss',
          onclick: function(e) {
            $('#simul #settle-info').text('Choose one of the options below to settle the game ' + gameDesc(pairing, ctrl.data.host.username) + '. Only continue when you are very sure, because this cannot be undone!');
            $('#simul #settle-hostloss').text('Simul participant ' + pairing.player.username + ' wins')
            $.modal($('#simul .settle_choice'));
            $('#modal-wrap .settle_choice a').click(function() {
              var result = $(this).data('settle'),
                confirmation = 'Please confirm that you want to settle the game ' + gameDesc(pairing, ctrl.data.host.username);
              if (result === 'hostwin') confirmation += ' as a win for simul host ' + ctrl.data.host.username;
              else if (result === 'hostloss') confirmation += ' as a win for simul participant ' + pairing.player.username;
              else confirmation += ' as a draw';
              if (confirm(confirmation + '. This action is irreversible!')) {
                $.modal.close();
                xhr.settle(pairing.player.id, result)(ctrl);
              }
            });
          }
        }))
      ]);
    })))
  ]),
  m('div.settle_choice.block_buttons', [
    m('span', { id: 'settle-info' } ),
    m('a.button', { 'data-settle': 'hostwin' }, 'Simul host ' + ctrl.data.host.username + ' wins'),
    m('a.button', { 'data-settle': 'draw' }, 'Settle as a draw'),
    m('a.button', { 'data-settle': 'hostloss', id: 'settle-hostloss' })
  ])] : null;
}