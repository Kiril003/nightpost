// Кадри всіх поверхонь месенджера на геометрії A52. Не тест — прилад.
//   node _look_shots.mjs <outDir>
// Сервер: npx vite preview --outDir dist-phone --base /webmsgr/ --port 4174
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = process.argv[2] || '/tmp/shots';
const BASE = 'http://127.0.0.1:4174/webmsgr/';
const DSF = 2.625;
const W = Math.round(1080 / DSF);          // 411
const H = Math.round((2156 - 88) / DSF);   // 788

mkdirSync(OUT, { recursive: true });

const now = new Date().toISOString();
const ago = (m) => new Date(Date.now() - m * 60000).toISOString();

const conv = (id, title, kind, extra = {}) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false,
  archived: false, created_at: now, updated_at: now, contact_id: null, is_demo: false,
  contact_verified: true, peer_node_id: `peer_${id}`, unread_count: 0, last_kind: 'text',
  last_snippet: 'Останнє повідомлення', last_author: title, last_at: ago(12), ...extra,
});

const msg = (id, cid, self, body, extra = {}) => ({
  id, conversation_id: cid, client_id: `cl_${id}`, seq: Number(id.replace(/\D/g, '')),
  author_id: self ? 'me' : `peer_${cid}`, author_name: self ? 'Я' : 'Оксана',
  kind: 'text', body, ciphertext: null, transport: 'direct', reply_to_id: null,
  sent_at: extra.sent_at || ago(30), edited_at: null, deleted_at: null,
  delivery: self ? 'sent' : 'received', ...extra,
});

const LONG =
  'Виходимо о шостій від старого мосту, беремо два ліхтарі й термос. Якщо дощ — ' +
  'переносимо на суботу, бо стежка внизу розмокає і спуск стає слизьким навіть у ' +
  'черевиках. Напиши, чи береш із собою собаку, бо тоді йдемо довшою дорогою повз поле.';

const SCENES = {
  empty: { convs: [], msgs: {}, contacts: [], queued: 0 },
  list: {
    convs: [
      conv('c1', 'Оксана', 'direct', { last_snippet: 'Так, чекаю біля входу', unread_count: 2 }),
      conv('c2', 'Двір', 'group', { last_snippet: 'Збори о шостій', last_author: 'Петро', last_at: ago(140) }),
      conv('c3', 'Богдан Наливайко', 'direct', { last_snippet: LONG, last_at: ago(1500) }),
    ],
    msgs: {},
    contacts: [],
    queued: 3,
  },
  thread: {
    convs: [conv('c1', 'Оксана', 'direct')],
    msgs: {
      c1: [
        msg('m1', 'c1', false, 'Привіт! Ти вже на місці?', { sent_at: ago(90) }),
        msg('m2', 'c1', true, 'Так, чекаю біля входу.', { sent_at: ago(88) }),
        msg('m3', 'c1', false, LONG, { sent_at: ago(40) }),
        msg('m4', 'c1', true, 'Добре', { sent_at: ago(30) }),
        msg('m5', 'c1', true, 'Беру ліхтар і термос, собаку лишаю вдома.', { sent_at: ago(29) }),
      ],
    },
    contacts: [],
    queued: 0,
  },
  emptythread: {
    convs: [conv('c1', 'Оксана', 'direct', { last_snippet: null, last_author: null, last_at: null })],
    msgs: { c1: [] },
    contacts: [],
    queued: 0,
  },
};

const bridgeInit = ({ convs, msgs, contacts, queued }) => {
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }
  window.PhantomBridge = {
    call: (m, path) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') {
        return ok({ node_id: 'me', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
      }
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(convs);
      if (q === '/messenger/contacts') return ok(contacts);
      if (q === '/messenger/queue/status') return ok({ queued });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm && m === 'GET') return ok(msgs[mm[1]] || []);
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

const shot = async (page, name) => {
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${OUT}/${name}.png` });
  const txt = await page.evaluate(() => (document.body.innerText || '').replace(/\n{2,}/g, '\n').trim());
  console.log(`\n===== ${name} =====\n${txt.slice(0, 900)}`);
};

const run = async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: W, height: H }, isMobile: true, hasTouch: true,
    deviceScaleFactor: DSF, locale: 'uk-UA',
  });

  const open = async (scene) => {
    const page = await ctx.newPage();
    await page.addInitScript(bridgeInit, SCENES[scene]);
    await page.goto(BASE, { waitUntil: 'load' });
    await page.waitForTimeout(2500);
    return page;
  };

  // 1. Порожній список — свіжий апарат.
  let p = await open('empty');
  await shot(p, '01-list-empty');
  await p.close();

  // 2. Список із розмовами + черга.
  p = await open('list');
  await shot(p, '02-list-full');

  // 3. Пошук без результату — поле за перемикачем у шапці.
  const toggle = p.locator('header button, [class*="border-b"] button').filter({ hasNot: p.locator('img') });
  const n = await toggle.count();
  for (let i = 0; i < n; i++) {
    await toggle.nth(i).click({ timeout: 2000 }).catch(() => {});
    const s = p.getByPlaceholder(/Пошук|пошук/).first();
    if (await s.isVisible().catch(() => false)) {
      await s.fill('щось чого немає');
      await shot(p, '03-search-empty');
      break;
    }
  }
  await p.close();

  // 4. Розмова з повідомленнями.
  p = await open('thread');
  const row = p.getByText('Оксана').first();
  await row.click();
  await shot(p, '04-thread');

  // 5. Композер із текстом.
  const ta = p.locator('textarea').first();
  if (await ta.count()) {
    await ta.click();
    await ta.fill('Виходимо о шостій, беру ліхтар');
    await shot(p, '05-composer-typed');
  } else console.log('!! textarea не знайдено');

  // 6. Меню вкладень.
  for (const nm of [/Вкласти|Додати|Прикріпити|скріпк/i]) {
    const b = p.getByRole('button', { name: nm }).first();
    if (await b.count()) { await b.click(); await shot(p, '06-attach'); break; }
  }
  await p.keyboard.press('Escape');

  // 7. Налаштування розмови — шапка розмови.
  const head = p.locator('header, [data-thread-header]').first();
  if (await head.count()) {
    await head.getByRole('button').last().click().catch(() => {});
    await shot(p, '07-thread-menu');
  }
  await p.close();

  // 8. Розмова БЕЗ повідомлень.
  p = await open('emptythread');
  await p.getByText('Оксана').first().click();
  await shot(p, '08-thread-empty');
  await p.close();

  await browser.close();
};

run().catch((e) => { console.error(e); process.exit(1); });
