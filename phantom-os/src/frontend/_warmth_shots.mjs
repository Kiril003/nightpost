// Кадри «тепла»: список, розмова, людина, «Я» на 390×844 з підставними даними.
//   node _warmth_shots.mjs <outDir> [port]
// Сервер: python -m http.server <port> з каталогу, де webmsgr → dist-phone.
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = process.argv[2] || '/tmp/shots';
// Фаза доби: night | dawn | day | dusk (data-phase на <html>, полярність у data-theme).
const PHASE = process.argv[4] || 'night';
const PORT = process.argv[3] || '18911';
mkdirSync(OUT, { recursive: true });

const ago = (m) => new Date(Date.now() - m * 60000).toISOString();
const conv = (id, title, kind, peer, unread, snip, author, min) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false, archived: false,
  created_at: ago(9000), updated_at: ago(min), contact_id: null, is_demo: false, contact_verified: true,
  peer_node_id: peer, unread_count: unread, last_kind: 'text', last_snippet: snip, last_author: author, last_at: ago(min),
});
const CONVS = [
  conv('d1', 'Оксана', 'direct', 'p1', 2, 'Можеш забрати мою посилку до 20:00?', 'Оксана', 3),
  conv('d2', 'Тарас', 'direct', 'p2', 0, 'Тоді о 20:30 біля фонтана', 'Тарас', 32),
  conv('g1', 'Двір на Костянтинівській', 'group', null, 14, 'Світло дали, генератор вимкнули', 'Марта', 54),
  conv('d3', 'Мама', 'direct', 'p3', 1, 'Чи приїдете в неділю на обід?', 'Мама', 139),
  conv('d4', 'Андрій', 'direct', 'p4', 0, '12 фото з Карпат', 'Андрій', 1500),
  conv('d5', 'Софія', 'direct', 'p5', 0, 'Дякую! До зустрічі', 'Я', 1600),
];
const msg = (id, self, body, min) => ({
  id, conversation_id: 'd1', client_id: `cl_${id}`, seq: Number(id.replace(/\D/g, '')),
  author_id: self ? 'me' : 'p1', author_name: self ? 'Я' : 'Оксана', kind: 'text', body, ciphertext: null,
  transport: 'direct', reply_to_id: null, sent_at: ago(min), edited_at: null, deleted_at: null,
  delivery: self ? 'sent' : 'received',
});
const MSGS = {
  d1: [
    msg('m1', false, 'Сиджу в кав’ярні навпроти пошти', 30),
    msg('m2', false, 'Можеш забрати мою посилку до 20:00?', 29),
    msg('m3', true, 'Заберу, скинь номер', 20),
    msg('m4', false, 'Номер 2045 1188 3302, скажеш на касі, що від Мельник', 12),
    msg('m5', true, 'Добре, буду о 19:40', 3),
  ],
};

const bridgeInit = ({ CONVS, MSGS }) => {
  try { localStorage.clear(); } catch { /* */ }
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  window.PhantomBridge = {
    call: (m, path) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') return ok({ node_id: 'me', bundle: { name: 'Кирило' } });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm) return ok(MSGS[mm[1]] || []);
      if (q.startsWith('/messenger/conversations/')) return ok([]);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      return ok({});
    },
  };
};

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, locale: 'uk-UA',
});
const open = async () => {
  const page = await ctx.newPage();
  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  await page.goto(`http://127.0.0.1:${PORT}/webmsgr/index.html`);
  await page.waitForTimeout(3000);
  if (PHASE !== 'night') {
    await page.evaluate((ph) => {
      const r = document.documentElement;
      r.dataset.theme = ph === 'dusk' ? 'night' : 'day';
      if (ph === 'day') delete r.dataset.phase;
      else r.dataset.phase = ph;
    }, PHASE);
    await page.waitForTimeout(1200);
  }
  return page;
};

let p = await open();
await p.screenshot({ path: `${OUT}/list.png` });
await p.getByText('Оксана').nth(1).click();
await p.waitForTimeout(1500);
await p.screenshot({ path: `${OUT}/talk.png` });
const who = p.getByRole('button', { name: /Оксана/ }).first();
await who.click({ timeout: 3000 }).catch(() => console.log('no header button'));
await p.waitForTimeout(1200);
await p.screenshot({ path: `${OUT}/person.png` });
await p.close();

p = await open();
const av = p.locator('button[aria-label*="Я"], button[aria-label*="профіль"], button[aria-label*="Налаштування"]').first();
await av.click({ timeout: 3000 }).catch(() => console.log('no me button'));
await p.waitForTimeout(1200);
await p.screenshot({ path: `${OUT}/me.png` });
await browser.close();
