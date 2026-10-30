import { test, expect, type Page } from '@playwright/test';

/**
 * Скільки екрана телефон витрачає, ПЕРШ НІЖ людина побачить перший рядок вмісту.
 *
 * Власник відкрив месенджер на телефоні й сказав, що все над «Просторами» —
 * громіздке, неохайне й незручне. Тут це число, а не смак.
 *
 * ГЕОМЕТРІЯ — апарата, не смаку. Samsung A52 (SM-A525F), 1080×2400, density 420:
 *   DSF        = 420 / 160            = 2.625
 *   ширина     = 1080 / 2.625         = 411 CSS px
 *   верх вікна = 88 device px         — `statusBarsPadding()` рідної оболонки
 *   низ вікна  = 2156 device px       — над рідною смугою `bottomChromeInsets()`
 *   висота     = (2156 - 88) / 2.625  = 788 CSS px
 * Тому device_y = 88 + css_y * 2.625. Стеля стоїть у DEVICE px — у тих самих
 * одиницях, у яких скаржився власник, а не в зручних для верстки CSS.
 *
 * ЧОМУ СТАН — ПОРОЖНІЙ СПИСОК. Скарга знята на свіжому апараті, де розмов ще
 * немає: саме там хром найдорожчий, бо під ним нічого. Міст віддає порожнє
 * `/messenger/conversations` — це стан першого запуску, а не вигадка.
 *
 * ДВА ЧИСЛА, бо це дві різні вади:
 *   chrome — низ останньої смуги хрому (структура: шапка, пошук, стрічка, двері);
 *   first  — верх першого рядка тексту (додає власний відступ порожнього стану).
 *
 * СТЕЛЯ — ХРАПОВИК: числа можуть лише падати. Виміряно 05.09 на бандлі
 * dist-phone з гілки `feat/messenger-core`.
 *   ДО:    chrome 725 device px (30.2% екрана), first 838 device px (34.9%)
 *   ПІСЛЯ: chrome  88 device px (це вже сама смуга стану ОС, веб не бере
 *          нічого), first 260 device px (10.8%) — списку дісталось 1901
 *          device px замість 1263.
 * Ці числа відтворюють вимір на самому апараті (сесія-сусід зняла дампом
 * дерева доступності: стрічка 418-547 проти 416-552 тут, «Простори» 586-715
 * проти 589-715, перший рядок 836 проти 838) — тобто браузер тут не «схоже
 * на телефон», а звірений із ним прилад.
 *
 * Запуск проти телефонного бандла:
 *   npm run build:phone
 *   npx vite preview --outDir dist-phone --base /webmsgr/ --port 4174
 *   PHANTOM_WEB_BASE=/webmsgr/ npx playwright test e2e/phone-chrome.spec.ts --project=phone
 */

const DSF = 2.625;
const TOP = 88;
const W = Math.round(1080 / DSF);
const H = Math.round((2156 - TOP) / DSF);
const BASE = process.env.PHANTOM_WEB_BASE ?? '/';

/**
 * Опустити ТИМ САМИМ комітом, що зменшує хром. Рости не може — з одним
 * винятком, зафіксованим тут. 06.09: 88 → 214, 260 → 398. Смуга списку
 * (48 CSS px) стоїть і на порожньому списку, бо тепер вона — двері назовні.
 * Рідне коло «вийти» хоста лежало під нижнім краєм WebView, доки той не
 * забрав собі всю висоту (HideBottomChrome); після цього коло лягло на
 * «Нова» у списку і на «+» композера — два керування в одній точці, і
 * саме те, у що власник не влучав пальцем (кадри 01/02, A52). Один рядок
 * хрому з дверима дешевший за двері поверх кнопки.
 *
 * 17.09: `first` 398 → 298, `chrome` лишається 214. Храповик тримав, і саме
 * він показав регрес: 94b753d4 (07.09) додав ДРУГУ, телефонну копію смуги
 * шести чипів категорій під `flex md:hidden`, і хром став 340 при стелі 214.
 * Смуга займала рівно 48 CSS px = 126 device px — точна різниця 340 − 214.
 * Прибрано; обидва числа повернулись, `first` навіть нижче за попередню
 * стелю, бо разом зі смугою пішов і її нижній бордюр.
 */
const CEILING = { chrome: 214, first: 298 };

const toDevice = (cssY: number) => Math.round(TOP + cssY * DSF);

test.use({
  viewport: { width: W, height: H },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: DSF,
  locale: 'uk-UA',
});

// ── Міст телефона: ті самі шляхи, що в APK; розмов ще немає. ───────────────
const bridgeInit = () => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (_m: string, path: string) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') {
        return ok({
          node_id: 'abc123',
          bundle: { name: 'Kyrylo' },
          compact: 'PH2:ZTc2:483778',
          link: 'phantom://invite/PH2:ZTc2:483778',
        });
      }
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok([]);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      return nf;
    },
  };
};

const boot = async (page: Page) => {
  await page.addInitScript(bridgeInit);
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);
};

/**
 * Хром — це все, що лежить НАД смугою списку, а не перелік знайомих класів:
 * список сам себе називає, тож правка може переставляти смуги як завгодно,
 * і вимір лишається чинним.
 */
const measure = () => {
  const list = document.querySelector('[data-phantom-list]');
  const chrome = list ? list.getBoundingClientRect().top : NaN;

  // Перший рядок ВМІСТУ: найвищий видимий текстовий вузол усередині списку.
  let first = NaN;
  if (list) {
    const texts = Array.from(list.querySelectorAll('*')).filter((el) => {
      if (el.children.length > 0) return false;
      if (!(el.textContent || '').trim()) return false;
      const r = el.getBoundingClientRect();
      return r.height > 0 && r.width > 0;
    });
    if (texts.length) first = Math.min(...texts.map((el) => el.getBoundingClientRect().top));
  }
  return { chrome, first };
};

test('порожній список: хром телефона не росте', async ({ page }) => {
  await boot(page);

  const { chrome, first } = await page.evaluate(measure);

  expect(
    Number.isFinite(chrome),
    'смуги списку немає — розмітка втратила [data-phantom-list], і вимір хрому осліп',
  ).toBe(true);
  expect(
    Number.isFinite(first),
    'у списку немає жодного видимого рядка — порожній стан замовк',
  ).toBe(true);

  const got = { chrome: toDevice(chrome), first: toDevice(first) };

  for (const key of ['chrome', 'first'] as const) {
    const what =
      key === 'chrome'
        ? 'хром над списком'
        : 'до першого рядка тексту';
    expect(
      got[key],
      `${what}: ${got[key]} device px (${((got[key] / 2400) * 100).toFixed(1)}% екрана), ` +
        `стеля ${CEILING[key]}. Якщо стало менше — опусти стелю до ${got[key]} ТИМ САМИМ комітом, ` +
        'інакше храповик не тримає здобуте.',
    ).toBeLessThanOrEqual(CEILING[key]);
  }

  // Шапки на чистому апараті не мусить бути ВЗАГАЛІ: перше, що бачить людина,
  // — сам порожній стан, а не хром над ним.
  await expect(
    page.getByText('PHANTOM', { exact: true }),
    'бренд повернувся в шапку телефона — це напис для нас, не для людини',
  ).toHaveCount(0);
});

/**
 * Черга — єдине, чого людина не дізнається більше нізвідки, і до цієї правки
 * її на телефоні не було видно взагалі: `queuedCount` опитувався кожні 15 с і
 * малювався лише всередині `hidden md:block`. Сторож падає, якщо речення знову
 * поїде на ПК — тобто якщо телефон знову замовкне про недоставлені листи.
 */
test('черга: телефон каже, що листи ще не пішли', async ({ page }) => {
  await page.addInitScript(() => {
    const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
    const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
      call: (_m: string, path: string) => {
        const q = path.split('?')[0];
        if (q === '/messenger/identity') {
          return ok({ node_id: 'abc123', bundle: { name: 'Kyrylo' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
        }
        if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok([]);
        if (q === '/messenger/contacts') return ok([]);
        if (q === '/messenger/queue/status') return ok({ queued: 3 });
        if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
        return nf;
      },
    };
  });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);

  await expect(
    page.getByText('3\u00A0повідомлення чекають звʼязку'),
    'черга є, а телефон про неї мовчить — знову «доставку видно лише на ПК»',
  ).toBeVisible();
});
