var editor = require('./editor');
var m = require('mithril');
var keyboard = require('./keyboard');
var fenRead = require('draughtsground/fen').read;
var fenCompare = require('draughts').fenCompare;

module.exports = function(cfg) {

  this.cfg = cfg;
  this.data = editor.init(cfg);
  this.options = cfg.options || {};
  this.embed = cfg.embed;

  this.trans = lidraughts.trans(this.data.i18n);

  this.selected = m.prop('pointer');

  this.extraPositions = [{
      fen: 'start',
      name: this.trans('startPosition')
  }, {
      fen: 'W:W:B',
      name: this.trans('clearBoard')
  }, {
      fen: 'prompt',
      name: this.trans('loadPosition')
  }];

  this.positionsKey = function() {
    return (this.data.variant.key === 'russian' || this.data.variant.key === 'brazilian') ? 'draughts64' : this.data.variant.key;
  }.bind(this);
  
  this.makePositionMap = function() {
    const positionMap = {},
      positions = cfg.positions && cfg.positions[this.positionsKey()];
    if (positions) positions.forEach(function(cat) { 
      cat.positions.forEach(function(pos) {
        positionMap[pos.fen.split(':').slice(0, 3).join(':')] = pos;
      });
    });
    this.positionMap = positionMap
  }.bind(this)
  this.makePositionMap();

  this.draughtsground; // will be set from the view when instanciating draughtsground

  this.onChange = function() {
    this.options.onChange && this.options.onChange(this.computeFen());
    m.redraw();
  }.bind(this);

  this.computeFen = function() {
    return this.draughtsground ?
    editor.computeFen(this.data, this.draughtsground.getFen()) :
    cfg.fen;
  }.bind(this);

  this.bottomColor = function() {
    return this.draughtsground ?
    this.draughtsground.state.orientation :
    this.options.orientation || 'white';
  }.bind(this);

  this.setColor = function (letter) {
      this.data.color(letter.toLowerCase());
      this.onChange();
  }.bind(this);

  this.startPosition = function() {
    this.draughtsground.set({
      fen: this.data.variant.initialFen
    });
    this.data.color('w');
    this.onChange();
  }.bind(this);

  this.clearBoard = function() {
    this.draughtsground.set({
      fen: 'W:W:B'
    });
    this.onChange();
  }.bind(this);

  this.loadNewFen = function(fen) {
    if (fen === 'prompt') {
      fen = prompt('Paste FEN position').trim();
    } else if (fen === 'start') {
      fen = this.data.variant.initialFen;
    }
    if (fen) {
      this.changeFen(fen);
    }
  }.bind(this);

  this.isAlgebraic = function() {
    return this.cfg.coordSystem === 1 && this.data.variant.board.key === '64';
  }.bind(this);

  this.coordSystem = function() {
    return this.isAlgebraic() ? 1 : 0;
  }.bind(this);


  this.changeFen = function(fen) {
    window.location = editor.makeUrl(this.data.baseUrl + (this.data.variant.key !== 'standard' ? this.data.variant.key + '/' : ''), fen);
  }.bind(this);

  this.changeVariant = function(key) {
    const variant = this.data.variants.find(v => v.key === key);
    if (!variant) return;
    const newSize = variant.board.size[0] !== this.data.variant.board.size[0] || variant.board.size[1] !== this.data.variant.board.size[1],
      changeInitial = variant.initialFen !== this.data.variant.initialFen && fenCompare(this.computeFen(), this.data.variant.initialFen);
    if (newSize || changeInitial) {
      // recreate draughtsground on startingposition with new boardsize
      this.cfg.fen = variant.initialFen;
      this.draughtsground = undefined;
    }
    this.data.variant = variant;
    this.makePositionMap();
    m.redraw();
  }.bind(this);

  this.positionLooksLegit = function() {
    const totals = { white: 0, black: 0 },
      boardSize = this.data.variant.board.size,
      fields = boardSize[0] * boardSize[1] / 2,
      width = boardSize[0] / 2,
      pieces = this.draughtsground ? this.draughtsground.state.pieces : fenRead(this.cfg.fen, fields),
      backrankWhite = [], backrankBlack = [];
    for (let i = 1; i <= width; i++) {
      backrankWhite.push(i < 10 ? '0' +  i.toString() :  i.toString());
    }
    for (let i = fields - width + 1; i <= fields; i++) {
      backrankBlack.push(i < 10 ? '0' +  i.toString() :  i.toString());
    }
    for (let pos in pieces) {
      if (pieces[pos] && (pieces[pos].role === 'king' || pieces[pos].role === 'man')) {
        if (pieces[pos].role === 'man') {
          if (pieces[pos].color === 'white' && backrankWhite.includes(pos))
            return false;
          else if (pieces[pos].color === 'black' && backrankBlack.includes(pos))
            return false;
        }
        totals[pieces[pos].color]++;
      }
    }
    return totals.white !== 0 && totals.black !== 0 && (totals.white + totals.black) < fields;
  }.bind(this);

  this.setOrientation = function(o) {
    this.options.orientation = o;
    if (this.draughtsground.state.orientation !== o)
    this.draughtsground.toggleOrientation();
    m.redraw();
  }.bind(this);

  keyboard(this);
};
