import { test, expect, type Page } from '@playwright/test';

/**
 * ПРИЛАД лагу стрічки. Не «здається гладко» — числа, зняті в справжньому
 * Chromium на справжньому телефонному бандлі (`dist-phone`, порт 4174).
 *
 * Власник, дослівно: «неприємно повністю месенджером зараз користуватися,
 * дуже незграбний і лаганий». Цей файл каже, ДЕ саме, і скільки це коштує
 * у мілісекундах. Без нього будь-яке «пришвидшили» — гадка.
 *
 * Чотири виміри, кожен — окремий тест, кожен друкує рядок `PERF …` у stdout:
 *
 *   1. DOM       — скільки вузлів тримає стрічка на 600 листах.
 *                  Пряма підлога всього іншого: браузер не може бути
 *                  швидким на дереві, якого не мусив будувати.
 *   2. MOUNT     — від кліку в розмову до першої намальованої бульбашки.
 *   3. SCROLL    — довгі кадри під час прокрутки (бюджет кадру 16,7 мс).
 *   4. RERENDER  — скільки коштує ОДНА зміна стану ChatArea. Це головне
 *                  число: `hoveredMessageId` / `swipeOffset` живуть у тому
 *                  самому компоненті, що малює список, тож один рух пальця
 *                  перемальовує ВСІ 600 листів.
 *
 * Стелі внизу — храповик. Число може лише падати; якщо виріс — хтось
 * повернув борг, і тест мусить це сказати вголос, а не промовчати.
 *
 * Запуск:
 *   npm run build:phone
 *   PHANTOM_WEB_BASE=/webmsgr/ npx playwright test e2e/phone-feed-perf.spec.ts --project=phone
 */

const W = 393;
const H = 851;
const BASE = process.env.PHANTOM_WEB_BASE ?? '/';

/**
 * Довжина розмови, на якій міряємо. Реальна розмова в Telegram — тисячі.
 *
 * `PERF_N=6` дає контрольний прогін на короткій розмові: він відділяє
 * сталу вартість кадру від тієї, що росте з довжиною стрічки. Без цього
 * контролю не можна сказати, чи лікуємо ми список, чи щось інше.
 */
const N = Number(process.env.PERF_N ?? 600);

/**
 * Стелі, зняті приладом нижче. Правило: опускай тим самим комітом, яким
 * поліпшив, інакше храповик не тримає здобуте.
 *
 * Знято на 600 листах ДО і ПІСЛЯ вікна стрічки — тим самим приладом, на тій
 * самій машині, на телефонному бандлі:
 *
 *              було           стало
 *   DOM        5479 вузлів    1099 вузлів
 *   бульбашок  600            120
 *   RERENDER   19,8-23,6 мс   2,6-7,4 мс   (розкиди не перетинаються)
 *   MOUNT      424-656 мс     361-829 мс   ЗМІНИ НЕ ДОВЕДЕНО
 *
 * MOUNT тут навмисно лишений без висновку. Його розкиди перекриваються,
 * тобто прилад не відрізняє вікно від шуму: у число входять і запит до
 * моста, і старт застосунку, і крок опитування локатора в Playwright.
 * Одного щасливого заміру (191 мс) було б досить, щоб збрехати про
 * «утричі швидше відкриття» — тому поруч стоять п'ять.
 *
 * На 50 листах RERENDER не змінився (2,6-3,5 проти 2,8 мс) — і не мусив:
 * вікно на 120 листів там не ховає нічого.
 */
const CEILING = {
  /** Вузлів DOM у стрічці на 600 листах. */
  domNodes: 1200,
  /** Довгих задач (>50 мс) за прокрутку на 12 кроків. */
  longFrames: 2,
  /** Найдовша заблокована задача під прокрутку, мс. */
  worstScrollFrame: 80,
  /** Одна зміна стану ChatArea, мс. Понад 16,7 — людина бачить ривок. */
  rerenderMs: 10,
};

test.use({
  viewport: { width: W, height: H },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 2.75,
  locale: 'uk-UA',
});

// ── Міст телефона: довга розмова, мережі немає. ────────────────────────────
const now = new Date().toISOString();

const CONVS = [
  {
    id: 'c1', title: 'Марта', kind: 'direct', circle: 'friends', handle: null,
    avatar: null, pinned: false, archived: false, created_at: now, updated_at: now,
    contact_id: null, is_demo: false, contact_verified: true, peer_node_id: 'peer_c1',
    unread_count: 0, last_kind: 'text', last_snippet: 'Останнє повідомлення',
    last_author: 'Марта', last_at: now,
  },
];

/**
 * Тіла листів навмисно різні за довжиною: однакові рядки дали б браузеру
 * ідеальний кеш розкладки і збрехали б у наш бік.
 */
const BODIES = [
  'Привіт! Ти вже на місці?',
  'Так, чекаю біля входу.',
  'Іду, пʼять хвилин',
  'Ок',
  'Домовились 👍',
  'Довгий рядок тексту, щоб у бульбашці справді був перенос слів і браузер рахував розклад, а не брав його з кешу однакових вузлів.',
  'https://example.org/дуже/довге/посилання/яке/не/має/розтягувати/сторінку',
  'Ще один абзац, трохи інакший за довжиною від попереднього, аби висоти рядків не збіглися.',
];

const MSGS = Array.from({ length: N }, (_, i) => ({
  id: `mc1_${i}`,
  conversation_id: 'c1',
  client_id: `clc1_${i}`,
  seq: i,
  author_id: i % 2 === 0 ? 'abc123' : 'peer_c1',
  author_name: i % 2 === 0 ? 'Кирило' : 'Марта',
  kind: 'text',
  body: `${i + 1}. ${BODIES[i % BODIES.length]}`,
  ciphertext: null,
  transport: i % 2 === 0 ? 'p2p' : 'direct',
  reply_to_id: null,
  sent_at: new Date(Date.now() - (N - i) * 60000).toISOString(),
  edited_at: null,
  deleted_at: null,
  delivery: 'sent',
}));

const bridgeInit = ({ CONVS, MSGS }: { CONVS: unknown[]; MSGS: unknown[] }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (m: string, path: string, body: string | null) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity')
        return ok({ node_id: 'abc123', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      if (/^\/messenger\/conversations\/[^/]+\/messages$/.test(q)) {
        if (m === 'GET') return ok(MSGS);
        return ok({ ...JSON.parse(body || '{}'), id: `new_${Date.now()}`, seq: 9999, sent_at: new Date().toISOString(), conversation_id: 'c1', delivery: 'queued' });
      }
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

/** Друкує рядок так, щоб його було видно в `--reporter=list` і в логу. */
const say = (line: string) => {
  // eslint-disable-next-line no-console
  console.log(`PERF ${line}`);
};

/** Відкриває довгу розмову і чекає, доки стрічка справді намальована. */
async function openLongChat(page: Page) {
  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.locator('text=Марта').first().waitFor({ timeout: 20_000 });
  await page.locator('text=Марта').first().click({ force: true });
  const scroller = page.locator('[data-testid="messages-scroller"]');
  await scroller.waitFor({ timeout: 20_000 });
  // Остання бульбашка — доказ, що дані доїхали, а не просто змонтувався каркас.
  await page.locator(`text=${N}. `).first().waitFor({ timeout: 20_000 });
  await page.waitForTimeout(600);
  return scroller;
}

test('DOM: стрічка на 600 листах не будує дерева, якого не видно', async ({ page }) => {
  test.setTimeout(120_000);
  const scroller = await openLongChat(page);

  const m = await scroller.evaluate((el) => ({
    nodes: el.querySelectorAll('*').length,
    bubbles: el.querySelectorAll('.msg-bubble-fluid').length,
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
  }));

  say(`DOM вузлів=${m.nodes} бульбашок=${m.bubbles} scrollHeight=${m.scrollHeight} екран=${m.clientHeight}`);

  expect(
    m.nodes,
    `Стрічка тримає ${m.nodes} вузлів DOM на ${N} листах проти стелі ${CEILING.domNodes}. ` +
      `На екран влазить ~${Math.round(m.clientHeight / 90)} бульбашок — решта побудована намарне, ` +
      `і кожна зміна стану ChatArea проходить по всіх них.`,
  ).toBeLessThanOrEqual(CEILING.domNodes);
});

test('MOUNT: від кліку в розмову до першої бульбашки', async ({ page }) => {
  test.setTimeout(120_000);
  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.locator('text=Марта').first().waitFor({ timeout: 20_000 });

  const t0 = Date.now();
  await page.locator('text=Марта').first().click({ force: true });
  await page.locator(`text=${N}. `).first().waitFor({ timeout: 20_000 });
  const mountMs = Date.now() - t0;

  say(`MOUNT ${mountMs} мс на ${N} листів`);
  // Стелі тут навмисно немає: число залежить від машини, воно для порівняння
  // ДО/ПІСЛЯ на одній машині, а не для воріт.
  expect(mountMs).toBeGreaterThan(0);
});

test('SCROLL: довгі кадри під час прокрутки стрічки', async ({ page }) => {
  test.setTimeout(120_000);
  const scroller = await openLongChat(page);

  /**
   * ВАЖЛИВО про прилад. Перша його редакція рахувала різницю між кадрами
   * `requestAnimationFrame` і звітувала «32 довгих кадри». То була брехня
   * приладу: rAF у Chromium іде рівно 60 Гц, тож КОЖЕН здоровий кадр —
   * 16,7 мс, і поріг «>16,7» ловив саме здорові кадри, а не ривки.
   *
   * Тут міряється те, що справді є ривком: `longtask` — відрізок, на якому
   * головна нитка заблокована понад 50 мс і браузер фізично не може
   * намалювати кадр. Це не наша оцінка, це вимір самого браузера.
   * TBT (total blocking time) — сума перевищень над 50 мс.
   */
  const r = await scroller.evaluate(async (el) => {
    const tasks: number[] = [];
    const obs = new PerformanceObserver((list) => {
      for (const e of list.getEntries()) tasks.push(e.duration);
    });
    obs.observe({ entryTypes: ['longtask'] });

    const step = el.scrollHeight / 14;
    for (let i = 12; i >= 1; i--) {
      el.scrollTop = step * i;
      await new Promise((res) => setTimeout(res, 120));
    }
    await new Promise((res) => setTimeout(res, 200));
    obs.disconnect();

    return {
      count: tasks.length,
      worst: Math.round(Math.max(0, ...tasks)),
      tbt: Math.round(tasks.reduce((s, d) => s + Math.max(0, d - 50), 0)),
    };
  });

  say(`SCROLL довгих-задач=${r.count} найгірша=${r.worst}мс TBT=${r.tbt}мс`);

  expect(
    r.worst,
    `Найдовша заблокована задача під прокрутку ${r.worst} мс проти стелі ${CEILING.worstScrollFrame}. ` +
      `На цьому відрізку браузер не малює нічого — це і є ривок.`,
  ).toBeLessThanOrEqual(CEILING.worstScrollFrame);

  expect(
    r.count,
    `${r.count} довгих задач (>50 мс) за прокрутку проти стелі ${CEILING.longFrames}.`,
  ).toBeLessThanOrEqual(CEILING.longFrames);
});

test('RERENDER: одна зміна стану ChatArea коштує менше кадру', async ({ page }) => {
  test.setTimeout(120_000);
  const scroller = await openLongChat(page);

  /**
   * Наводимо на бульбашку й знімаємо наведення. `onMouseEnter` пише
   * `hoveredMessageId` — стан ChatArea, тобто React мусить пройти по
   * всьому списку. Це найдешевша зміна стану, яка існує в цьому
   * компоненті: якщо ВОНА коштує кадр, то свайп (60 Гц) коштує все.
   *
   * Міряємо десять разів і беремо медіану — одиничний вимір ловить GC.
   */
  /**
   * ПРО ПРИЛАД — два хибні заходи, обидва варті запису, бо кожен виглядав
   * як вимір і не був ним:
   *
   *  1. Чекання двох `requestAnimationFrame` дало 33,3 мс з розкидом 0,2 мс
   *     на десяти пробах. Такий розкид означає, що мірялось ЧЕКАННЯ (два
   *     кадри по 16,7 мс), а не робота: React міг зайняти і 1 мс, і 30 —
   *     прилад однаково казав 33,3.
   *  2. Час, синхронно проведений у `dispatchEvent`, дав 0,1 мс. Теж не
   *     вимір: `mouseover` — неперервна подія, React 18 планує її
   *     конкурентно й повертає керування ДО того, як зробить роботу. Нуль
   *     тут означає «робота ще попереду», а не «роботи немає».
   *
   * Тому міряє сам Chromium, а не ми: `Performance.getMetrics` через CDP
   * дає накопичені `ScriptDuration`, `LayoutDuration`, `RecalcStyleDuration`
   * усієї сторінки. Різниця до й після пачки наведень — це справжній час,
   * який браузер витратив на перемальовування, разом із роботою, яку React
   * відклав. Ділимо на кількість наведень: ціна одного руху пальця.
   */
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');

  const POKES = 20;

  const rows = await scroller.evaluate((el) => el.querySelectorAll('.msg-bubble-fluid').length);
  expect(rows, 'не знайшли бульбашок для виміру').toBeGreaterThan(10);

  const pokeBatch = (count: number) =>
    scroller.evaluate(async (el, n) => {
      const items = Array.from(el.querySelectorAll('.msg-bubble-fluid'));
      const settle = () => new Promise<void>((res) => setTimeout(res, 40));
      for (let i = 0; i < n; i++) {
        const node = items[(i * 7) % items.length];
        node.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
        await settle();
        node.dispatchEvent(new MouseEvent('mouseout', { bubbles: true }));
        await settle();
      }
    }, count);

  const metrics = async () => {
    const { metrics: m } = await cdp.send('Performance.getMetrics');
    const get = (name: string) => m.find((x) => x.name === name)?.value ?? 0;
    return {
      script: get('ScriptDuration'),
      layout: get('LayoutDuration'),
      style: get('RecalcStyleDuration'),
      nodes: get('Nodes'),
    };
  };

  // Прогрів: перша пачка платить за холодний код React і не рахується.
  await pokeBatch(4);
  await page.waitForTimeout(300);

  const a = await metrics();
  await pokeBatch(POKES);
  await page.waitForTimeout(300);
  const b = await metrics();

  const ms = (x: number) => Math.round(x * 1000 * 10) / 10;
  const perPoke = {
    script: Math.round(((b.script - a.script) * 1000 * 10) / POKES) / 10,
    layout: Math.round(((b.layout - a.layout) * 1000 * 10) / POKES) / 10,
    style: Math.round(((b.style - a.style) * 1000 * 10) / POKES) / 10,
  };
  const total = Math.round((perPoke.script + perPoke.layout + perPoke.style) * 10) / 10;

  say(
    `RERENDER одне наведення=${total}мс ` +
      `(скрипт=${perPoke.script} розклад=${perPoke.layout} стилі=${perPoke.style}) ` +
      `за ${POKES} наведень: скрипт=${ms(b.script - a.script)}мс ` +
      `розклад=${ms(b.layout - a.layout)}мс стилі=${ms(b.style - a.style)}мс ` +
      `вузлів=${b.nodes}`,
  );

  expect(
    total,
    `Одна зміна стану ChatArea коштує браузеру ${total} мс проти стелі ${CEILING.rerenderMs}. ` +
      `Стан списку живе в тому ж компоненті, що малює список, тож кожен рух пальця ` +
      `перемальовує всі ${N} листів. Саме це власник називає «лаганий».`,
  ).toBeLessThanOrEqual(CEILING.rerenderMs);
});
