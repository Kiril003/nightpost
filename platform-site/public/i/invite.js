(function () {
  'use strict';

  // Той самий ключ і те саме правило, що в перемикачі сайту (src/i18n/index.tsx).
  var LANG_KEY = 'phantom-lang';

  var UK = {
    title: 'Запрошення в Nightpost',
    'invite-title': 'Вас запрошують у Nightpost',
    'invite-sub': 'Одне торкання відкриє запрошення в застосунку. Застосунку ще немає? Візьміть його нижче й відкрийте посилання знову.',
    open: 'Відкрити в Nightpost',
    get: 'Отримати застосунок',
    'code-summary': 'Показати код запрошення',
    'code-hint': 'Вставте його в застосунку там, де додаєте людину.',
    copy: 'Скопіювати код',
    'pc-title': 'Це запрошення для комп’ютера',
    'pc-sub': 'Відкрийте його в Nightpost на комп’ютері, не на телефоні.',
    'pc-code-summary': 'Показати код запрошення',
    'pc-code-hint': 'Вставте його в Nightpost на комп’ютері.',
    'pc-copy': 'Скопіювати код',
    'empty-title': 'У цьому посиланні немає запрошення',
    'empty-sub': 'Попросіть людину надіслати посилання ще раз, цілим: запрошення — це частина після #.',
    'get-empty': 'Отримати застосунок',
    'legacy-title': 'Це запрошення зі старої версії',
    'legacy-sub': 'Попросіть людину оновити Nightpost і надіслати нове.',
    'get-legacy': 'Отримати застосунок',
    privacy: 'Код лишається у вашому браузері. Сервер не бачить того, що після #.'
  };

  var COPIED = { en: 'Copied', uk: 'Скопійовано' };

  // Дзеркало PeerInvite.IN_TEXT: PH2, base64url без доповнення, шість hex.
  var PH2 = /^PH2:[A-Za-z0-9_-]+:[0-9a-fA-F]{6}$/;
  var PH1 = /^PH1:/;

  // Дзеркало PeerInvite.looksLikePcEnvelope: без двокрапок, перші байти під
  // base64url декодуються в «PHI». ПК-конверт — чинне запрошення, лише не на
  // цей пристрій, тож його не можна плутати з «посилання зіпсоване».
  function looksLikePcEnvelope(code) {
    if (code.length < 4 || code.indexOf(':') !== -1) return false;
    var head = code.slice(0, 8).replace(/=+$/, '');
    var b64 = head.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4) b64 += '=';
    var decoded;
    try {
      decoded = atob(b64);
    } catch (e) {
      return false;
    }
    return decoded.length >= 3 && decoded.slice(0, 3) === 'PHI';
  }

  // Дві збірки — дві схеми (build.gradle.kts: release «phantom», debug
  // «phantom-debug»); на апараті, де встановлено лише одну з них, спроба
  // відкрити чужу схему мовчки нічого не робить — жодного фолбеку немає в
  // самій схемі. Пробуємо обидві по черзі, а як застосунок не відповів —
  // ведемо на /download. Перехід у фон (visibilitychange) означає, що
  // застосунок прийняв адресу, і далі пробувати не треба.
  var APP_SCHEMES = ['phantom', 'phantom-debug'];
  var OPEN_TIMEOUT_MS = 900;

  function openInApp(code, downloadHref) {
    var i = 0;
    var left = true;
    var onVisibility = function () {
      if (document.visibilityState === 'hidden') left = false;
    };
    document.addEventListener('visibilitychange', onVisibility);
    function stop() {
      document.removeEventListener('visibilitychange', onVisibility);
    }
    function next() {
      if (!left) {
        stop();
        return;
      }
      if (i >= APP_SCHEMES.length) {
        stop();
        location.href = downloadHref;
        return;
      }
      location.href = APP_SCHEMES[i++] + '://invite/' + code;
      setTimeout(next, OPEN_TIMEOUT_MS);
    }
    next();
  }

  function lang() {
    try {
      var stored = localStorage.getItem(LANG_KEY);
      if (stored === 'uk' || stored === 'en') return stored;
    } catch (e) {}
    var wanted = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || ''];
    for (var i = 0; i < wanted.length; i++) {
      var l = String(wanted[i]).toLowerCase();
      if (l.indexOf('uk') === 0 || l.indexOf('ru') === 0) return 'uk';
    }
    return 'en';
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function readCode() {
    var raw = location.hash.replace(/^#/, '');
    try {
      raw = decodeURIComponent(raw);
    } catch (e) {}
    return raw.trim();
  }

  function show(state) {
    ['invite', 'pc', 'empty', 'legacy'].forEach(function (id) {
      byId(id).hidden = id !== state;
    });
    byId('card').setAttribute('data-state', state);
  }

  function render() {
    var code = readCode();
    var downloadHref = byId('get').getAttribute('href');
    if (PH2.test(code)) {
      byId('open').addEventListener('click', function (ev) {
        ev.preventDefault();
        openInApp(code, downloadHref);
      });
      byId('code').textContent = code;
      show('invite');
    } else if (PH1.test(code)) {
      show('legacy');
    } else if (looksLikePcEnvelope(code)) {
      byId('pc-code').textContent = code;
      show('pc');
    } else {
      show('empty');
    }
  }

  function translate(current) {
    if (current !== 'uk') return;
    document.documentElement.lang = 'uk';
    document.title = UK.title;
    Object.keys(UK).forEach(function (id) {
      var el = byId(id);
      if (el) el.textContent = UK[id];
    });
  }

  function wireCopy(button, codeEl, current) {
    var label = button.textContent;
    button.addEventListener('click', function () {
      var code = codeEl.textContent;
      var done = function () {
        button.textContent = COPIED[current];
        setTimeout(function () {
          button.textContent = label;
        }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(code).then(done, function () {
          selectCode(codeEl);
        });
      } else {
        selectCode(codeEl);
      }
    });
  }

  function selectCode(codeEl) {
    var range = document.createRange();
    range.selectNodeContents(codeEl);
    var selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  }

  var current = lang();
  translate(current);
  wireCopy(byId('copy'), byId('code'), current);
  wireCopy(byId('pc-copy'), byId('pc-code'), current);
  render();
  window.addEventListener('hashchange', render);
})();
