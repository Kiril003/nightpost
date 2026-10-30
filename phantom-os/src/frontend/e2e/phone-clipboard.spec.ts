import { test, expect, type Page } from '@playwright/test';

/**
 * Буфер обміну на телефоні: у нього не кладуть того, що не працює там, куди
 * його вставлять.
 *
 * Дефект того самого класу, що й адреса в запрошенні: «Копіювати посилання
 * / Deep Link» на простір складав рядок із location.origin — у WebView це
 * вигаданий https://phantom.local перехоплювача, і в буфер лягало посилання,
 * яке поза телефоном не веде нікуди, а «скопійовано» було обіцянкою без
 * адресата. Маршруту phantom://… на простір хост не має (є лише
 * invite/peer/peergroup/msgr), тож дії на телефоні не малюється взагалі.
 *
 * Сторож перехоплює navigator.clipboard.writeText на стенді (dist-phone з
 * http://127.0.0.1:4174 — origin відомий і не збігається ні з чим у заглушці
 * моста), натискає кожен контроль, що обіцяє копіювання, на кожній поверхні
 * телефона, і дивиться, ЩО саме лягло в буфер. Позитивний контроль — картка
 * запрошення: її «Копіювати» мусить покласти рівно link моста; так
 * доведено, що перехоплювач бачить копії, а не мовчить.
 *
 * Червоніти вміє (мутація 04.09): підставте origin у рядок, що копіює
 * InviteCard, — падає на «origin стенда в буфері»; поверніть пункт Deep Link
 * на телефон — падає сторож вмісту (bundlePatterns: clipboard-origin-link).
 *
 * Запуск: npm run e2e:phone
 */

const W = 393;
const H = 852;
const BASE = process.env.PHANTOM_WEB_BASE ?? '/';

test.use({
  viewport: { width: W, height: H },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 2.75,
  locale: 'uk-UA',
});

const COMPACT = 'PH2:ZTc2:483778';
const LINK = `phantom://invite/${COMPACT}`;

const now = new Date().toISOString();
const conv = (id: string, title: string, kind: string) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false, archived: false,
  created_at: now, updated_at: now, contact_id: null, is_demo: false, contact_verified: true,
  peer_node_id: `peer_${id}`, unread_count: 0, last_kind: 'text', last_snippet: 'Останнє повідомлення',
  last_author: title, last_at: now,
});
const CONVS = [conv('c1', 'Оксамит', 'direct')];
const msg = (i: number, cid: string, self: boolean, body: string) => ({
  id: `m${cid}_${i}`, conversation_id: cid, client_id: `cl${cid}_${i}`, seq: i,
  author_id: self ? 'abc123' : `peer_${cid}`, author_name: self ? 'Я' : 'Оксамит',
  kind: 'text', body, ciphertext: null, transport: 'direct', reply_to_id: null,
  sent_at: new Date(Date.now() - (10 - i) * 60000).toISOString(),
  edited_at: null, deleted_at: null, delivery: 'sent',
});
const MSGS: Record<string, unknown[]> = {
  c1: [msg(1, 'c1', false, 'Привіт! Ти вже на місці?'), msg(2, 'c1', true, 'Так, чекаю біля входу.')],
};

/** Міст телефона + перехоплювач буфера: усе, що копіюють, лягає у window.__copied. */
const bridgeInit = ({ CONVS, MSGS, LINK, COMPACT }: {
  CONVS: unknown[]; MSGS: Record<string, unknown[]>; LINK: string; COMPACT: string;
}) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }
  const copied: string[] = [];
  (window as unknown as { __copied: string[] }).__copied = copied;
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: {
      writeText: (t: string) => { copied.push(String(t)); return Promise.resolve(); },
      readText: () => Promise.resolve(''),
    },
  });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (m: string, path: string) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') {
        return ok({ node_id: 'abc123', bundle: { name: 'PHANTOM-A52' }, compact: COMPACT, link: LINK, address: null });
      }
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm && m === 'GET') return ok(MSGS[mm[1]] || []);
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

const boot = async (page: Page) => {
  await page.addInitScript(bridgeInit, { CONVS, MSGS, LINK, COMPACT });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(1500);
};

const copied = (page: Page) =>
  page.evaluate(() => (window as unknown as { __copied: string[] }).__copied.slice());

const tap = async (page: Page, sel: string) => {
  const l = page.locator(sel).first();
  if (await l.count()) await l.click({ force: true }).catch(() => undefined);
  await page.waitForTimeout(500);
};

/** Поверхні телефона, де є що копіювати. Кожна — з чистого завантаження. */
type Surface = { name: string; open: (page: Page) => Promise<void> };
const SURFACES: Surface[] = [
  { name: 'список розмов', open: async () => {} },
  { name: 'розмова', open: async (p) => { await tap(p, 'text=Оксамит'); } },
  {
    name: 'меню «Більше дій»',
    open: async (p) => { await tap(p, 'text=Оксамит'); await tap(p, '[title="Більше дій"]'); },
  },
  {
    name: 'меню листа',
    open: async (p) => {
      await tap(p, 'text=Оксамит');
      const b = p.locator('text=Привіт! Ти вже на місці?').first();
      if (await b.count()) await b.dispatchEvent('contextmenu', { clientX: 180, clientY: 300 });
      await p.waitForTimeout(500);
    },
  },
  { name: 'нова розмова', open: async (p) => { await tap(p, '[aria-label="Створити новий діалог"]'); } },
];

/** Видимі контролі, що обіцяють копіювання, — за підписом, яким їх бачить людина. */
const COPY_LABEL = /копі|copy|посилан|link/i;
const copyControls = () => {
  const SEL = 'button, a[href], [role="button"], [role="menuitem"]';
  const visible = (el: Element) => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  return Array.from(document.querySelectorAll(SEL))
    .filter(visible)
    .map((el, i) => ({
      i,
      label: (el.getAttribute('aria-label') || el.getAttribute('title') || (el.textContent || '').trim())
        .replace(/\s+/g, ' ')
        .slice(0, 60),
    }));
};

/** Те, чого в буфері бути не може: origin стенда й origin перехоплювача. */
const forbidden = (host: string) => [host, 'localhost:4174', 'phantom.local'];

test('позитивний контроль: «Копіювати» в запрошенні кладе рівно link моста', async ({ page }) => {
  await boot(page);
  await tap(page, '[aria-label="Створити новий діалог"]');
  const card = page.locator('[data-testid="invite-card"]');
  await expect(card.locator('[data-testid="invite-string"]')).toBeVisible();
  await card.locator('[data-testid="invite-copy"]').click({ force: true });
  await page.waitForTimeout(300);
  const got = await copied(page);
  expect(got, 'перехоплювач буфера нічого не побачив — вимір ні про що').toEqual([LINK]);
  const host = new URL(page.url()).host;
  for (const bad of forbidden(host)) {
    expect(got.join('\n'), `${bad} у буфері — копія бере origin`).not.toContain(bad);
  }
});

test('жоден контроль копіювання на телефоні не кладе в буфер origin чи phantom.local', async ({ page }) => {
  // Заміряно: тіло цього тесту — 42,9 с проти стелі 30 с, і зависання в ньому
  // немає. Він робить ВІСІМ повних `boot()` (шість поверхонь плюс перенатиск на
  // кожен знайдений контроль), кожен по 3,3-5,0 с: самих лише `goto` там 15,2 с,
  // фіксованих снів ще 17,8 с. Тридцять секунд — це стеля, під якою сторож не
  // встигає обійти поверхні, а не ознака вади продукту: зі знятою стелею він
  // проходить зеленим і показує чистий буфер на всіх шести.
  //
  // Тому бюджет, а не «оптимізація» снів: 700 мс стоять запасом на анімацію
  // переходу, і на завантаженій машині коротший сон почне мазати. Той самий
  // хід, що в phone-doors.spec.ts для того самого шаблону перебору.
  test.setTimeout(180_000);

  const log: string[] = [];
  let pressed = 0;
  for (const surface of SURFACES) {
    await boot(page);
    await surface.open(page);
    const controls = (await page.evaluate(copyControls)).filter((c) => COPY_LABEL.test(c.label));
    for (const c of controls) {
      // Кожен натиск — на щойно відкритій поверхні: після попереднього стан уже інший.
      await boot(page);
      await surface.open(page);
      const all = await page.evaluate(copyControls);
      const target = all.find((x) => x.label === c.label);
      if (!target) continue;
      await page.evaluate((i) => {
        const SEL = 'button, a[href], [role="button"], [role="menuitem"]';
        const el = Array.from(document.querySelectorAll(SEL)).filter((e) => {
          const cs = getComputedStyle(e);
          if (cs.display === 'none' || cs.visibility === 'hidden') return false;
          const r = e.getBoundingClientRect();
          return r.width > 0 && r.height > 0;
        })[i] as HTMLElement | undefined;
        el?.click();
      }, target.i);
      await page.waitForTimeout(400);
      pressed += 1;
      for (const text of await copied(page)) log.push(`[${surface.name}] «${c.label}» → ${text.slice(0, 120)}`);
    }
  }
  // Дошка того, що справді лягло в буфер, — для звіту, а не лише для вироку.
  console.log(`натиснуто контролів копіювання: ${pressed}\n${log.join('\n')}`);
  expect(pressed, 'жодного контролю копіювання не знайдено — сторож нічого не міряв').toBeGreaterThan(0);
  const host = new URL(page.url()).host;
  const sins = log.filter((line) => forbidden(host).some((bad) => line.includes(bad)));
  expect(sins, 'у буфер лягло посилання, що поза телефоном не веде нікуди').toEqual([]);
  // Пункт «Deep Link» на простір на телефоні не малюється — ні в меню, ні деінде.
  expect(log.some((line) => /Deep Link|Копіювати посилання/.test(line))).toBe(false);
});
