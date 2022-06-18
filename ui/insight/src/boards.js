var m = require('mithril');

function miniGame(game) {
  return m(
    'a',
    {
      key: game.id,
      href: '/' + game.id + (game.color === 'sente' ? '' : '/gote'),
    },
    [
      m('span', {
        class: 'mini-board sg-wrap mini-board-' + game.id + ' parse-sfen',
        'data-color': game.color,
        'data-sfen': game.sfen,
        'data-lastmove': game.lastMove,
        config: function (el, isUpdate) {
          if (!isUpdate) lishogi.parseSfen($(el));
        },
      }),
      m('span.vstext', [
        m('span.vstext__pl', [
          game.user1.name,
          m('br'),
          game.user1.title ? game.user1.title + ' ' : '',
          game.user1.rating,
        ]),
        m('span.vstext__op', [
          game.user2.name,
          m('br'),
          game.user2.rating,
          game.user2.title ? ' ' + game.user2.title : '',
        ]),
      ]),
    ]
  );
}

module.exports = function (ctrl) {
  if (!ctrl.vm.answer) return;

  return m('div.game-sample.box', [
    m('div.top', 'Some of the games used to generate this insight'),
    m('div.boards', ctrl.vm.answer.games.map(miniGame)),
  ]);
};
