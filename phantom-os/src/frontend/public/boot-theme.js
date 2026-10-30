/*
 * Тема й форма заставки — з ПЕРШОГО кадру, до модуля.
 *
 * Заставка «Відкриваю месенджер…» стояла в палітрі Ночі завжди, і вдень на
 * світлому телефоні спалахувала чорним (A52, 02.10). Тема живе на рідному
 * боці (`PhantomBridge.getTheme()`), а міст доступний синхронно ще до того,
 * як приїде модуль. Окремий файл, а не вбудований скрипт: CSP телефона
 * пускає лише `script-src 'self'`.
 *
 * Тема — рівно та, яку ЗАРАЗ малює рідний бік (розвʼязана фаза «Як сонце» чи
 * вибір людини): `getTheme()` — це полярність тієї самої палітри, якою вже
 * намальовано ескіз під WebView. Тож рідний ескіз → #boot → React не
 * перефарбовуються ні разу. Мовчазний чи зламаний міст лишає Ніч із розмітки.
 *
 * Акцент і щільність із «Вигляду» (`getAppearance()`) — теж до модуля, щоб
 * кнопки не спалахували бурштином перед обраним кольором.
 *
 * `data-shell="phone"` — є міст, отже це телефон: заставка малює ескіз
 * домівки (index.html, #boot .sk), а не знак по центру, як на ПК.
 */
(function () {
  var root = document.documentElement;
  try {
    var bridge = window.PhantomBridge;
    if (!bridge) return; /* без мосту (ПК, тест) — лишається розмітка */
    root.setAttribute('data-shell', 'phone');
    var theme = typeof bridge.getTheme === 'function' ? bridge.getTheme() : null;
    root.setAttribute('data-theme', theme === 'day' ? 'day' : 'night');
    var look = typeof bridge.getAppearance === 'function' ? JSON.parse(bridge.getAppearance() || '{}') : {};
    if (/^[a-z]{3,10}$/.test(look.accent || '')) root.setAttribute('data-accent', look.accent);
    if (look.bubbles === 'compact') root.setAttribute('data-bubbles', 'compact');
    if (look.list === 'compact') root.setAttribute('data-list-density', 'compact');
  } catch (e) {
    /* зламаний міст — лишається Ніч із розмітки */
  }
})();
