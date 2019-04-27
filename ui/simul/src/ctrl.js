var socket = require('./socket');
var simul = require('./simul');

module.exports = function(env) {

  this.data = env.data;
  this.arbiterData = undefined;
  this.evals = this.data.evals;

  this.toggleCandidates = false;
  this.toggleArbiter = false;
  this.userId = env.userId;

  this.socket = new socket(env.socketSend, this);

  this.reload = function(data) {
    this.data = data;
    startWatching();
  }.bind(this);

  var alreadyWatching = [];
  var startWatching = function() {
    var newIds = this.data.pairings.map(function(p) {
      return p.game.id;
    }).filter(function(id) {
      return alreadyWatching.indexOf(id) === -1;
    });
    if (newIds.length) {
      setTimeout(function() {
        this.socket.send("startWatching", newIds.join(' '));
      }.bind(this), 1000);
      newIds.forEach(alreadyWatching.push.bind(alreadyWatching));
    }
  }.bind(this);
  startWatching();

  if (simul.createdByMe(this) && this.data.isCreated)
    lidraughts.storage.set('lidraughts.move_on', '1'); // hideous hack :D

  this.trans = lidraughts.trans(env.i18n);
};
