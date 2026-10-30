import { test, expect, type Page } from '@playwright/test';

/**
 * Маршрут у розмові на СКЛІ телефона, а не в jsdom.
 *
 * 04.09, збірка dbe6d05d: власник поділився маршрутом з мапи в розмову з живою
 * людиною й побачив сирий `PH-RTE:1788517702898;337;258;FOOT;…`. Модульний
 * сторож (`src/__tests__/peerWire.test.tsx`) доводить розпізнавання, але не
 * доводить ВИГЛЯД: у jsdom немає розкладки, а саме розкладка тут і кусає.
 * Тіло розібраного маршруту з повною ламаною довше за 1500 знаків, і ChatArea
 * вважає такий лист «довгим»: згортає його у вікно 112px і накриває низ
 * градієнтом з кнопкою «Читати далі». Цей файл міряє, що картку видно, що її
 * рядок фактів не накритий нічим і що вона не вилазить за 393px.
 *
 * Запуск: npm run build:phone && npx playwright test --project=phone
 *         e2e/phone-route-card.spec.ts
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

const now = new Date().toISOString();

/** Ламана з 600 точок — стеля кадру (PeerLive.MAX_SHAPE_POINTS). */
const SHAPE = Array.from({ length: 600 }, (_, i) => [49.838 + i * 0.00002, 18.1563 + i * 0.00003]);

/**
 * Те, що віддає міст: ПЛАСКІ поля просто на листі й порожнє тіло
 * (`WebMessengerKinds.kt`, коміт 61b3cc84). Веб їх лише малює.
 */
const ROUTE_FIELDS = {
  route_profile: 'FOOT',
  route_distance_m: 3437,
  route_duration_s: 2580,
  route_expires_at_ms: Date.now() + 6 * 60 * 60_000,
  route_waypoints: [SHAPE[0], SHAPE[599]],
  route_shape: SHAPE,
};

/** Кадр, який власник побачив сирим: міст цього роду ще не знає. */
const RAW_WIRE = 'PH-RTE:1788517702898;337;258;FOOT;s~doHcdymBqKuB;g_eoHccymBZh@GNKTMTo@cAa@s@k@';

/** Керівний кадр: у розмові його немає взагалі — PeerOutbox.isSpoken. */
const CONTROL_WIRE = 'PH-LIV:50.45010,30.52340;12;1788517702898;1788517802898;7';

const CONVS = [
  {
    id: 'c1',
    title: 'Марта',
    kind: 'direct',
    circle: 'friends',
    handle: null,
    avatar: null,
    pinned: false,
    archived: false,
    created_at: now,
    updated_at: now,
    contact_id: null,
    is_demo: false,
    contact_verified: true,
    peer_node_id: 'peer_c1',
    unread_count: 0,
    // Так рядок розмови віддає міст: рід, пласкі числа й НУЛЬ замість тіла.
    last_kind: 'route',
    last_snippet: null,
    last_route_profile: 'FOOT',
    last_route_distance_m: 3437,
    last_route_duration_s: 2580,
    last_author: 'Марта',
    last_at: now,
  },
];

const msg = (i: number, kind: string, body: string, extra: Record<string, unknown> = {}) => ({
  id: `m${i}`,
  conversation_id: 'c1',
  client_id: `cl${i}`,
  seq: i,
  author_id: 'peer_c1',
  author_name: 'Марта',
  kind,
  body,
  ciphertext: null,
  transport: 'direct',
  reply_to_id: null,
  sent_at: new Date(Date.now() - (10 - i) * 60000).toISOString(),
  edited_at: null,
  deleted_at: null,
  delivery: 'sent',
  ...extra,
});

const MSGS: Record<string, unknown[]> = {
  c1: [
    msg(1, 'route', '', ROUTE_FIELDS),
    msg(2, 'text', RAW_WIRE),
    msg(3, 'text', CONTROL_WIRE),
    msg(4, 'text', 'виїжджаю о шостій'),
  ],
};

const bridgeInit = ({ CONVS, MSGS }: { CONVS: unknown[]; MSGS: Record<string, unknown[]> }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (m: string, path: string, body: string | null) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') {
        return ok({
          node_id: 'abc123',
          bundle: { name: 'Кирило' },
          compact: 'PH2:ZTc2:483778',
          link: 'phantom://invite/PH2:ZTc2:483778',
        });
      }
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm) {
        if (m === 'GET') return ok(MSGS[mm[1]] || []);
        return ok({
          ...JSON.parse(body || '{}'),
          id: `new_${Date.now()}`,
          seq: 99,
          sent_at: new Date().toISOString(),
          conversation_id: mm[1],
          delivery: 'queued',
        });
      }
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

const boot = async (page: Page) => {
  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);
};

test('у переліку — «Маршрут · пішки · 3,4 км» з полів рядка, а не тіло кадру', async ({
  page,
}) => {
  await boot(page);
  const shell = await page.locator('body').innerText();
  expect(shell).not.toContain('PH-');
  expect(shell).not.toContain('route_distance_m');
  // Стрічку ще не відкривали: числа прийшли з рядка розмови.
  expect(shell).toContain('Маршрут · пішки · 3,4 км');
  // last_snippet = null не лишає порожнечі під назвою розмови.
  const line = page.locator('[data-testid="chat-preview-line"]').first();
  await expect(line).toBeVisible();
  expect((await line.innerText()).trim().length).toBeGreaterThan(6);
});

test('у розмові — картка з силуетом шляху, і жодного дроту чи полів', async ({ page }) => {
  await boot(page);
  await page.locator('text=Марта').first().click({ force: true });
  await page.waitForTimeout(1200);

  const card = page.locator('[data-testid="peer-route-card"]').first();
  await expect(card).toBeVisible();
  await expect(page.locator('[data-testid="peer-route-spark"]').first()).toBeVisible();

  const said = await page.locator('body').innerText();
  expect(said).not.toContain('PH-RTE');
  expect(said).not.toContain('s~doHcdymBqKuB');
  expect(said).not.toContain('route_distance_m');
  expect(said).not.toContain('PH-LIV');
  // Нерозібраний дріт у другому листі теж став чесним реченням, не текстом.
  expect(said).toContain('нерозібраним');
  // Керівний кадр зник зі стрічки цілком, а слова людини лишились.
  expect(said).toContain('виїжджаю о шостій');
  const bubbles = await page.locator('.msg-bubble-fluid').count();
  expect(bubbles).toBe(3);

  // Рядок фактів мусить бути ВИДИМИМ і не накритим нічим.
  const facts = page.locator('[data-testid="peer-route-facts"]').first();
  await expect(facts).toBeVisible();
  await expect(facts).toContainText('3,4 км · 43 хв · пішки');

  const covered = await facts.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return !(top && (el.contains(top) || top.contains(el)));
  });
  expect(covered).toBe(false);

  // Нічого не вилазить за екран телефона.
  const right = await card.evaluate((el) => Math.round(el.getBoundingClientRect().right));
  expect(right).toBeLessThanOrEqual(W);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(W);
});
