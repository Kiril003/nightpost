// Кадри «Я» (390×844) з підставним мостом: повний, порожній і QR на весь екран.
//   node _me_shots.mjs <outDir> [port]
// Сервер: `VITE_TARGET=phone npx vite --config _vite_shots.config.mjs --port <port>` (база /webmsgr/).
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = process.argv[2] || '/tmp/me-shots';
const PORT = process.argv[3] || '18950';
mkdirSync(OUT, { recursive: true });

const ago = (m) => new Date(Date.now() - m * 60000).toISOString();
const conv = (id, title, kind, peer, min) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false, archived: false,
  created_at: ago(9000), updated_at: ago(min), contact_id: null, is_demo: false, contact_verified: true,
  peer_node_id: peer, unread_count: 0, last_kind: 'text', last_snippet: 'Привіт', last_author: 'Я', last_at: ago(min),
});
const FULL = {
  convs: [
    conv('d1', 'Оксана', 'direct', 'p1', 3), conv('d2', 'Тарас', 'direct', 'p2', 30),
    conv('d3', 'Мама', 'direct', 'p3', 120), conv('d4', 'Андрій', 'direct', 'p4', 900),
    conv('g1', 'Двір на Костянтинівській', 'group', null, 50), conv('g2', 'Сімʼя', 'group', null, 400),
  ],
  contacts: [['p1', 'Оксана'], ['p2', 'Тарас'], ['p3', 'Мама'], ['p4', 'Андрій']].map(([peer, name], i) => ({
    id: `c${i}`, peer_node_id: peer, display_name: name, verified: true, session_ready: true, created_at: ago(9000),
  })),
  handle: 'kyrylo_m',
  devices: [
    { id: 'pc1', name: 'Ноутбук', kind: 'pc', online: true },
    { id: 'pc2', name: 'Робочий компʼютер', kind: 'pc', online: false, seen_at: ago(95) },
  ],
  facts: [
    ['Дім — Костянтинівська', 'ви сказали'], ['Кава о 9:00', 'з розмови'],
    ['Алергія на пил', 'ви сказали'], ['Мама — неділі', 'з розмови'], ['Бігає по суботах', 'з розмови'],
  ].map(([text, from_label], i) => ({ id: `f${i}`, text, from: 'self', from_label, skill: false, at: ago(i * 600) })),
  quiet: { enabled: true, from: '23:00', to: '07:00', except: ['p3', 'p1'] },
  watching: { p1: 50 },
};
const EMPTY = { convs: [], contacts: [], handle: null, devices: [], facts: [], quiet: null, watching: {} };

const bridgeInit = (D) => {
  try { localStorage.clear(); } catch { /* */ }
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  const no = () => JSON.stringify({ ok: false, status: 404, data: { detail: 'немає' } });
  window.PhantomBridge = {
    shareText: () => true,
    call: (m, path) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity')
        return ok({ node_id: 'me', link: 'phantom://invite/PH2:AbCdEfGh123:a1b2c3', compact: 'PH2:AbCdEfGh123:a1b2c3', bundle: { name: 'Кирило' } });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(D.convs);
      if (q === '/messenger/contacts') return ok(D.contacts);
      if (q === '/messenger/directory/me') return ok({ handle: D.handle, published: !!D.handle });
      if (q === '/messenger/devices') return ok({ stopped: false, devices: D.devices });
      if (q === '/messenger/self/facts') return ok(D.facts);
      if (q === '/messenger/quiet') return D.quiet ? ok(D.quiet) : no();
      const w = q.match(/^\/messenger\/where\/([^/]+)$/);
      if (w) {
        const min = D.watching[decodeURIComponent(w[1])];
        return ok({ now_ms: Date.now(), eligible: true, why_not: null, theirs: null,
          mine: min ? { precision: 'quarter', until_ms: Date.now() + min * 60000, stand: false } : null });
      }
      if (q.startsWith('/messenger/conversations/')) return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      return ok({});
    },
  };
};

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, locale: 'uk-UA',
});

const openMe = async (data, theme) => {
  const p = await ctx.newPage();
  p.on('pageerror', (e) => console.log('pageerror', e.message.slice(0, 200)));
  await p.addInitScript(bridgeInit, data);
  await p.goto(`http://127.0.0.1:${PORT}/webmsgr/index.html`);
  await p.getByLabel('Створити новий діалог').waitFor({ timeout: 60000 });
  if (theme === 'day') await p.evaluate(() => { document.documentElement.dataset.theme = 'day'; delete document.documentElement.dataset.phase; });
  await p.waitForTimeout(600);
  await p.locator('button[aria-label*="Я"], button[aria-label*="профіль"], button[aria-label*="Налаштування"]').first().click();
  await p.getByTestId('me-screen').waitFor();
  await p.waitForTimeout(1200);
  await p.evaluate(() => document.fonts.ready);
  return p;
};

for (const theme of ['night', 'day']) {
  const p = await openMe(FULL, theme);
  await p.screenshot({ path: `${OUT}/${theme}_top.png` });
  await p.getByTestId('me-screen').evaluate((el) => (el.scrollTop = el.scrollHeight));
  await p.waitForTimeout(400);
  await p.screenshot({ path: `${OUT}/${theme}_bottom.png` });
  await p.getByTestId('me-screen').evaluate((el) => (el.scrollTop = 0));
  await p.getByTestId('me-code').click();
  await p.getByTestId('me-qr-full').waitFor();
  await p.waitForTimeout(600);
  await p.screenshot({ path: `${OUT}/${theme}_qr.png` });
  await p.close();
}
const e = await openMe(EMPTY, 'night');
await e.screenshot({ path: `${OUT}/night_empty.png` });
await e.close();
await browser.close();
