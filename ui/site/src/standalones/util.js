var lidraughts = window.lidraughts = window.lidraughts || {};

lidraughts.engineName = 'Scan 3.1';

lidraughts.raf = window.requestAnimationFrame.bind(window);
lidraughts.requestIdleCallback = (window.requestIdleCallback || window.setTimeout).bind(window);
lidraughts.dispatchEvent = function(el, eventName) {
  // compability for ie 11 instead of el.dispatchEvent(new Event(eventName)))
  var ev = document.createEvent('Event');
  ev.initEvent(eventName, false, false);
  el.dispatchEvent(ev);
};

function buildStorage(storageKey) {
  try {
    // just accessing localStorage can throw an exception...
    var storage = window[storageKey];
  } catch (e) {}
  var withStorage = storage ? function(f) {
    // this can throw exceptions as well.
    try { return f(storage); }
    catch (e) {}
  } : function() {};
  var storageObj = {
    get: function(k) {
      return withStorage(function(s) {
        return s.getItem(k);
      });
    },
    set: function(k, v) {
      withStorage(function(s) {
        try {
          s.setItem(k, v);
        } catch (e) {
          // removing first may help http://stackoverflow.com/questions/2603682/is-anyone-else-receiving-a-quota-exceeded-err-on-their-ipad-when-accessing-local
          s.removeItem(k);
          s.setItem(k, v);
        }
      });
    },
    remove: function(k) {
      withStorage(function(s) {
        s.removeItem(k);
      });
    },
    make: function(k) {
      return {
        get: function() {
          return storageObj.get(k);
        },
        set: function(v) {
          return storageObj.set(k, v);
        },
        remove: function() {
          return storageObj.remove(k);
        },
        listen: function(f) {
          window.addEventListener('storage', function(e) {
            if (e.key === k &&
              e.storageArea === storage &&
              e.newValue !== null) f(e);
          });
        }
      };
    }
  };
  return storageObj;
};

lidraughts.storage = buildStorage('localStorage');
lidraughts.tempStorage = buildStorage('sessionStorage');

lidraughts.reloadOtherTabs = (function() {
  var storage = lidraughts.storage.make('reload-other-tabs');
  storage.listen(function() {
    lidraughts.reload();
  });
  return function() {
    storage.set(1);
  }
})();
lidraughts.once = function(key, mod) {
  if (mod === 'always') return true;
  if (!lidraughts.storage.get(key)) {
    lidraughts.storage.set(key, 1);
    return true;
  }
  return false;
};
lidraughts.fp = {};
lidraughts.fp.contains = function(list, needle) {
  return list.indexOf(needle) !== -1;
};
lidraughts.fp.debounce = function(func, wait, immediate) {
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

  var elementIdContains = function(id, contained) {
    var el = document.getElementById(id);
    return el && el.contains(contained);
  };

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

  var userPowertip = function(el, pos) {
    pos = pos || el.getAttribute('data-pt-pos') || 's';
    $(el).removeClass('ulpt').powerTip({
      intentPollInterval: 200,
      placement: pos,
      mouseOnToPopup: true,
      closeDelay: 200
    }).data('powertip', ' ').on({
      powerTipRender: onPowertipPreRender('powerTip', function(url) {
        var u = url.substr(3);
        var preload = '<div class="upt__info"><div class="upt__info__top"><span class="user_link offline">' + $(el).html() + '</span></div></div><div class="upt__actions">' +
          '<a href="/@/' + u + '/tv" i data-icon="1"></a>' +
          '<a href="/inbox/new?user=' + u + '" i data-icon="c"></a>' +
          '<a href="/?user=' + u + '#friend" i data-icon="U"></a>' +
          '<a class="relation-button" disabled></a></div>';
        $('#powerTip').html(preload);
      })
    });
  };

  var gamePowertip = function(el) {
    $(el).removeClass('glpt').powerTip({
      intentPollInterval: 200,
      placement: 'w',
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
      if ($.data(this, name)) return $.error("widget " + name + " already bound to " + this);
      $.data(this, name, new constructor(method, this));
    });
    return returnValue;
  };
};
lidraughts.isResp = $('body').data('resp');
lidraughts.isHoverable = function () {
  if (typeof lidraughts.hoverable === 'undefined')
    lidraughts.hoverable = !!getComputedStyle(document.body).getPropertyValue('--hoverable');
  return lidraughts.hoverable;
};
lidraughts.spinnerHtml = '<div class="spinner"><svg viewBox="0 0 40 40"><circle cx=20 cy=20 r=18 fill="none"></circle></svg></div>';
lidraughts.assetUrl = function(path, opts) {
  opts = opts || {};
  var baseUrl = opts.sameDomain ? '' : document.body.getAttribute('data-asset-url');
  var version = document.body.getAttribute('data-asset-version');
  return baseUrl + '/assets' + (opts.noVersion ? '' : '/_' + version) + '/' + path;
};
lidraughts.cssPath = function(oldKey, respKey) {
  return lidraughts.isResp ?
    'css/lidraughts.' + respKey + '.' + $('body').data('theme') + '.' + ($('body').data('dev') ? 'dev' : 'min') + '.css' :
    'stylesheets/' + oldKey + '.css';
};
lidraughts.loadedCss = {};
lidraughts.loadCss = function(url, opts) {
  if (lidraughts.loadedCss[url]) return;
  lidraughts.loadedCss[url] = true;
  $('head').append($('<link rel="stylesheet" type="text/css" />').attr('href', lidraughts.assetUrl(url, opts)));
};
lidraughts.unloadCss = function(url) {
  if (lidraughts.loadedCss[url]) {
    lidraughts.loadedCss[url]  = false;
    $('head link[rel=stylesheet]')
      .filter(function() { return this.href.indexOf(url) >= 0 })
      .remove();
  }
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
  if (!lidraughts.isResp) lidraughts.loadCss('stylesheets/jquery-ui.css');
  return lidraughts.loadScript('javascripts/vendor/jquery-ui.slider.min.js', {noVersion:true});
};
lidraughts.shepherd = function(f) {
  var theme = 'shepherd-theme-' + ($('body').hasClass('dark') ? 'default' : 'dark');
  lidraughts.loadCss('vendor/shepherd/dist/css/' + theme + '.css');
  lidraughts.loadCss('stylesheets/shepherd.css');
  lidraughts.loadScript('vendor/shepherd/dist/js/tether.js', {noVersion:true}).done(function() {
    lidraughts.loadScript('vendor/shepherd/dist/js/shepherd.min.js', {noVersion:true}).done(function() {
      f(theme);
    });
  });
};
lidraughts.makeChat = function(id, data, callback) {
  lidraughts.requestIdleCallback(function() {
    data.loadCss = lidraughts.loadCss;
    (callback || $.noop)(LidraughtsChat.default(document.getElementById(id), data));
  });
};

lidraughts.desktopNotification = (function() {
  var notifications = [];
  function closeAll() {
    notifications.forEach(function(n) {
      n.close();
    });
    notifications = [];
  };
  window.addEventListener('focus', closeAll);
  var storage = lidraughts.storage.make('just-notified');
  function notify(msg) {
    var now = Date.now();
    if (document.hasFocus() || now - storage.get() < 1000) return;
    storage.set(now);
    if ($.isFunction(msg)) msg = msg();
    var notification = new Notification('lidraughts.org', {
      icon: '//lidraughts.org/assets/images/logo.256.png',
      body: msg
    });
    notification.onclick = function() {
      window.focus();
    };
    notifications.push(notification);
  };
  return function(msg) {
    if (document.hasFocus() || !('Notification' in window)) return;
    if (Notification.permission === 'granted') {
      // increase chances that the first tab can put a local storage lock
      setTimeout(notify, 10 + Math.random() * 500, msg);
    } else if (Notification.permission !== 'denied') {
      Notification.requestPermission(function(p) {
        if (p === 'granted') notify(msg);
      });
    };
  };
})();

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
      var domain = document.domain.replace(/^.+(\.[^\.]+\.[^\.]+)$/, '$1');
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
  return /[&<>\"\']/.test(str) ?
    str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, '&#39;')
    .replace(/"/g, '&quot;') :
    str;
};
lidraughts.toYouTubeEmbedUrl = function(url) {
  if (!url) return;
  var m = url.match(/(?:https?:\/\/)?(?:www\.)?(?:youtube\.com|youtu\.be)\/(?:watch)?(?:\?v=)?([^"&?\/ ]{11})(?:\?|&|)(\S*)/i);
  if (!m) return;
  var start = 0;
  m[2].split('&').forEach(function(p) {
    var s = p.split('=');
    if (s[0] === 't' || s[0] === 'start') {
      if (s[1].match(/^\d+$/)) start = parseInt(s[1]);
      else {
        var n = s[1].match(/(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?/);
        start = (parseInt(n[1]) || 0) * 3600 + (parseInt(n[2]) || 0) * 60 + (parseInt(n[3]) || 0);
      }
    }
  });
  var params = 'modestbranding=1&rel=0&controls=2&iv_load_policy=3' + (start ? '&start=' + start : '');
  return 'https://www.youtube.com/embed/' + m[1] + '?' + params;
};
$.fn.scrollTo = function(target, offsetTop) {
  return this.each(function() {
    if (typeof target === "number") this.scrollTop  = target;
    else {
      var $t = $(target);
      if (!$t.length) return;
      this.scrollTop = $t.offset().top + this.scrollTop - (offsetTop || 0);
    }
  });
};
$.modal = function(html) {
  if (!html.clone) html = $('<div>' + html + '</div>');
  var $wrap = $('<div id="modal-wrap">').html(html.clone().removeClass('none').show()).prepend('<span class="close" data-icon="L"></span>');
  var $overlay = $('<div id="modal-overlay">').html($wrap);
  $overlay.add($wrap.find('.close')).one('click', $.modal.close);
  $wrap.click(function(e) {
    e.stopPropagation();
  });
  $('body').prepend($overlay);
  return $wrap;
};
$.modal.close = function() {
  $('#modal-overlay').remove();
};

// polyfills

if (!Array.prototype.find) {
  Object.defineProperty(Array.prototype, 'find', {
    value: function(predicate) {
      for (var i in this) if (predicate(this[i])) return this[i];
    }
  });
}
