lidraughts = window.lidraughts || {};

lidraughts.engineName = 'Scan 3.1';

lidraughts.raf = window.requestAnimationFrame.bind(window);
lidraughts.requestIdleCallback = (window.requestIdleCallback || window.setTimeout).bind(window);
lidraughts.dispatchEvent = function(el, eventName) {
  el.dispatchEvent(new Event(eventName));
};

lidraughts.hasTouchEvents = 'ontouchstart' in window;
lidraughts.mousedownEvent = lidraughts.hasTouchEvents ? 'touchstart' : 'mousedown';

lidraughts.isCol1 = (function() {

  var isCol1Cache = 'init'; // 'init' | 'rec' | boolean

  return function() {
    if (typeof isCol1Cache == 'string') {
      if (isCol1Cache == 'init') { // only once
        window.addEventListener('resize', function() { isCol1Cache = 'rec' }); // recompute on resize
        if (navigator.userAgent.indexOf('Edge/') > -1) // edge gets false positive on page load, fix later
          window.lidraughts.raf(function() { isCol1Cache = 'rec' });
      }
      isCol1Cache = !!getComputedStyle(document.body).getPropertyValue('--col1');
    }
    return isCol1Cache;
  };
})();

{
  const buildStorage = (storage) => ({
    get: (k) => storage.getItem(k),
    set: (k, v) => storage.setItem(k, v),
    remove: (k) => storage.removeItem(k),
    make: (k) => ({
      get: () => api.get(k),
      set: (v) => api.set(k, v),
      remove: () => api.remove(k),
      listen: (f) => window.addEventListener('storage', e => {
        if (e.key === k &&
          e.storageArea === storage &&
          e.newValue !== null) f(e);
      })
    }),
    makeBoolean: (k) => ({
      get: () => api.get(k) == 1,
      set: (v) => api.set(k, v ? 1 : 0),
      toggle: () => api.set(k, api.get(k) == 1 ? 0 : 1)
    })
  });

  lidraughts.storage = buildStorage(window.localStorage);
  lidraughts.tempStorage = buildStorage(window.sessionStorage);
}

lidraughts.once = function(key, mod) {
  if (mod === 'always') return true;
  if (!lidraughts.storage.get(key)) {
    lidraughts.storage.set(key, 1);
    return true;
  }
  return false;
};
lidraughts.debounce = function(func, wait, immediate) {
  var timeout;
  var lastBounce = 0;
  return function() {
    var context = this,
      args = arguments,
      elapsed = Date.now() - lastBounce;
    lastBounce = Date.now();
    var later = function() {
      timeout = null;
      func.apply(context, args);
    };
    clearTimeout(timeout);
    if (immediate && elapsed > wait) func.apply(context, args);
    else timeout = setTimeout(later, wait);
  };
};
lidraughts.powertip = (function() {

  function containedIn(el, container) {
    return container && container.contains(el);
  }
  function inCrosstable(el) {
    return containedIn(el, document.querySelector('.crosstable'));
  }

  var onPowertipPreRender = function(id, preload) {
    return function() {
      var url = ($(this).data('href') || $(this).attr('href')).replace(/\?.+$/, '');
      if (preload) preload(url);
      $.ajax({
        url: url + '/mini',
        success: function(html) {
          $('#' + id).html(html);
          lidraughts.pubsub.emit('content_loaded')();
        }
      });
    };
  };

  var uptA = function(url, icon) {
    return '<a class="btn-rack__btn" href="' + url + '" data-icon="' + icon + '"></a>';
  }
  var userPowertip = function(el, pos) {
    pos = pos || el.getAttribute('data-pt-pos') || (
      inCrosstable(el) ? 'n' : 's'
    );
    $(el).removeClass('ulpt').powerTip({
      intentPollInterval: 200,
      placement: pos,
      smartPlacement: true,
      mouseOnToPopup: true,
      closeDelay: 200
    }).data('powertip', ' ').on({
      powerTipRender: onPowertipPreRender('powerTip', function(url) {
        var u = url.substr(3);
        $('#powerTip').html('<div class="upt__info"><div class="upt__info__top"><span class="user-link offline">' + $(el).html() + '</span></div></div><div class="upt__actions btn-rack">' +
          uptA('/@/' + u + '/tv', '1') +
          uptA('/inbox/new?user=' + u, 'c') +
          uptA('/?user=' + u + '#friend', 'U') +
          '<a class="btn-rack__btn relation-button" disabled></a></div>');
      })
    });
  };

  var gamePowertip = function(el) {
    $(el).removeClass('glpt').powerTip({
      intentPollInterval: 200,
      placement: inCrosstable(el) ? 'n' : 'w',
      smartPlacement: true,
      mouseOnToPopup: true,
      closeDelay: 200,
      popupId: 'miniGame'
    }).on({
      powerTipPreRender: onPowertipPreRender('miniGame')
    }).data('powertip', lidraughts.spinnerHtml);
  };

  var powerTipWith = function(el, ev, f) {
    if (lidraughts.isHoverable()) {
      f(el);
      $.powerTip.show(el, ev);
    }
  };

  function onIdleForAll(par, sel, fun) {
    lidraughts.requestIdleCallback(function() {
      Array.prototype.forEach.call(par.querySelectorAll(sel), fun);
    });
  }

  return {
    mouseover: function(e) {
      var t = e.target,
        cl = t.classList;
      if (cl.contains('ulpt')) powerTipWith(t, e, userPowertip);
      else if (cl.contains('glpt')) powerTipWith(t, e, gamePowertip);
    },
    manualGameIn: function(parent) {
      onIdleForAll(parent, '.glpt', gamePowertip);
    },
    manualUserIn: function(parent) {
      onIdleForAll(parent, '.ulpt', function(el) { userPowertip(el) });
    }
  };
})();
lidraughts.widget = function(name, prototype) {
  var constructor = $[name] = function(options, element) {
    var self = this;
    self.element = $(element);
    $.data(element, name, self);
    self.options = options;
    self._create();
  };
  constructor.prototype = prototype;
  $.fn[name] = function(method) {
    var returnValue = this;
    var args = Array.prototype.slice.call(arguments, 1);
    if (typeof method === 'string') this.each(function() {
      var instance = $.data(this, name);
      if (!instance) return;
      if (!$.isFunction(instance[method]) || method.charAt(0) === "_")
        return $.error("no such method '" + method + "' for " + name + " widget instance");
      returnValue = instance[method].apply(instance, args);
    });
    else this.each(function() {
      if (!$.data(this, name)) $.data(this, name, new constructor(method, this));
    });
    return returnValue;
  };
};
lidraughts.isHoverable = function () {
  if (typeof lidraughts.hoverable === 'undefined')
    lidraughts.hoverable =  !lidraughts.hasTouchEvents || !!getComputedStyle(document.body).getPropertyValue('--hoverable');
  return lidraughts.hoverable;
};
lidraughts.spinnerHtml = '<div class="spinner"><svg viewBox="0 0 40 40"><circle cx=20 cy=20 r=18 fill="none"></circle></svg></div>';
lidraughts.assetUrl = function(path, opts) {
  opts = opts || {};
  var baseUrl = opts.sameDomain ? '' : document.body.getAttribute('data-asset-url');
  var version = document.body.getAttribute('data-asset-version');
  return baseUrl + '/assets' + (opts.noVersion ? '' : '/_' + version) + '/' + path;
};
lidraughts.loadedCss = {};
lidraughts.loadCss = function(url, opts) {
  if (lidraughts.loadedCss[url]) return;
  lidraughts.loadedCss[url] = true;
  $('head').append($('<link rel="stylesheet" type="text/css" />').attr('href', lidraughts.assetUrl(url, opts)));
};
lidraughts.loadCssPath = function(key) {
  lidraughts.loadCss('css/' + key + '.' + $('body').data('theme') + '.' + ($('body').data('dev') ? 'dev' : 'min') + '.css');
}
lidraughts.compiledScript = function(name) {
  return 'compiled/lidraughts.' + name + ($('body').data('dev') ? '' : '.min') + '.js';
}
lidraughts.loadScript = function(url, opts) {
  return $.ajax({
    dataType: "script",
    cache: true,
    url: lidraughts.assetUrl(url, opts)
  });
};
lidraughts.hopscotch = function(f) {
  lidraughts.loadCss('vendor/hopscotch/dist/css/hopscotch.min.css');
  lidraughts.loadScript('vendor/hopscotch/dist/js/hopscotch.min.js', {noVersion:true}).done(f);
}
lidraughts.slider = function() {
  return lidraughts.loadScript(
    'javascripts/vendor/jquery-ui.slider' + (lidraughts.hasTouchEvents ? '.touch' : '') + '.min.js'
  );
};
lidraughts.makeChat = function(data, callback) {
  lidraughts.raf(function() {
    data.loadCss = lidraughts.loadCssPath;
    (callback || $.noop)(LidraughtsChat.default(document.querySelector('.mchat'), data));
  });
};

lidraughts.numberFormat = (function() {
  var formatter = false;
  return function(n) {
    if (formatter === false) formatter = (window.Intl && Intl.NumberFormat) ? new Intl.NumberFormat() : null;
    if (formatter === null) return n;
    return formatter.format(n);
  };
})();
lidraughts.idleTimer = function(delay, onIdle, onWakeUp) {
  var events = ['mousemove', 'touchstart'];
  var listening = false;
  var active = true;
  var lastSeenActive = Date.now();
  var onActivity = function() {
    if (!active) {
      // console.log('Wake up');
      onWakeUp();
    }
    active = true;
    lastSeenActive = Date.now();
    stopListening();
  };
  var startListening = function() {
    if (!listening) {
      events.forEach(function(e) {
        document.addEventListener(e, onActivity);
      });
      listening = true;
    }
  };
  var stopListening = function() {
    if (listening) {
      events.forEach(function(e) {
        document.removeEventListener(e, onActivity);
      });
      listening = false;
    }
  };
  setInterval(function() {
    if (active && Date.now() - lastSeenActive > delay) {
      // console.log('Idle mode');
      onIdle();
      active = false;
    }
    startListening();
  }, 10000);
};
lidraughts.pubsub = (function() {
  var subs = [];
  return {
    on: function(name, cb) {
      subs[name] = subs[name] || [];
      subs[name].push(cb);
    },
    off: function(name, cb) {
      if (!subs[name]) return;
      for (var i in subs[name]) {
        if (subs[name][i] === cb) {
          subs[name].splice(i);
          break;
        }
      }
    },
    emit: function(name) {
      return function() {
        if (!subs[name]) return;
        var args = Array.prototype.slice.call(arguments, 0);
        for (var i in subs[name]) subs[name][i].apply(null, args);
      }
    }
  };
})();
lidraughts.hasToReload = false;
lidraughts.redirectInProgress = false;
lidraughts.redirect = function(obj) {
  var url;
  if (typeof obj == "string") url = obj;
  else {
    url = obj.url;
    if (obj.cookie) {
      var domain = document.domain.replace(/^.+(\.[^.]+\.[^.]+)$/, '$1');
      var cookie = [
        encodeURIComponent(obj.cookie.name) + '=' + obj.cookie.value,
        '; max-age=' + obj.cookie.maxAge,
        '; path=/',
        '; domain=' + domain
      ].join('');
      document.cookie = cookie;
    }
  }
  var href = '//' + location.host + '/' + url.replace(/^\//, '');
  lidraughts.redirectInProgress = href;
  location.href = href;
};
lidraughts.reload = function() {
  if (lidraughts.redirectInProgress) return;
  lidraughts.hasToReload = true;
  if (location.hash) location.reload();
  else location.href = location.href;
};
lidraughts.escapeHtml = function(str) {
  return /[&<>"']/.test(str) ?
    str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, '&#39;')
    .replace(/"/g, '&quot;') :
    str;
};
$.modal = function(html, cls, onClose) {
  $.modal.close();
  if (!html.clone) html = $('<div>' + html + '</div>');
  var $wrap = $('<div id="modal-wrap">')
    .html(html.clone().removeClass('none'))
    .prepend('<span class="close" data-icon="L"></span>');
  var $overlay = $('<div id="modal-overlay">')
    .addClass(cls)
    .data('onClose', onClose)
    .html($wrap);
  $wrap.find('.close').on('click', $.modal.close);
  $overlay.on('click', function() {
    // disgusting hack
    // dragging slider out of a modal closes the modal
    if (!$('.ui-slider-handle.ui-state-focus').length) $.modal.close();
  });
  $wrap.on('click', function(e) {
    e.stopPropagation();
  });
  $('body').addClass('overlayed').prepend($overlay);
  return $wrap;
};
$.modal.close = function() {
  $('body').removeClass('overlayed');
  $('#modal-overlay').each(function() {
    ($(this).data('onClose') || $.noop)();
    $(this).remove();
  });
};
