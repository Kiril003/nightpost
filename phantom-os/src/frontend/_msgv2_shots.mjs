// Кадри месенджера v2 (390 px, День і Ніч, UK). Прилад, не тест.
//   node _msgv2_shots.mjs <outDir> <prefix> [step]
// Сервер: npx vite --config _vite_shots.config.mjs --mode phone --port 4174 --strictPort
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = process.argv[2] || '/tmp/shots';
const PREFIX = process.argv[3] || 'x';
const STEP = process.argv[4] || 'all';
const BASE = process.env.SHOT_BASE || 'http://127.0.0.1:4174/webmsgr/';
const W = 390;
const H = 844;

mkdirSync(OUT, { recursive: true });

const ago = (m) => new Date(Date.now() - m * 60000).toISOString();

const conv = (id, title, kind, extra = {}) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false,
  archived: false, created_at: ago(60 * 24 * 30), updated_at: ago(10), contact_id: `ct_${id}`, is_demo: false,
  contact_verified: true, peer_node_id: kind === 'dm' ? `peer_${id}` : null, unread_count: 0, last_kind: 'text',
  last_snippet: 'Останнє повідомлення', last_author: title, last_at: ago(12),
  last_self_at: ago(60), last_peer_at: ago(12), ...extra,
});

const msg = (id, cid, self, body, extra = {}) => ({
  id, conversation_id: cid, client_id: `cl_${id}`, seq: Number(id.replace(/\D/g, '')),
  author_id: self ? 'me' : extra.author_id || `peer_${cid}`, author_name: self ? 'Я' : extra.author_name || 'Оксана',
  kind: 'text', body, ciphertext: null, transport: 'direct', reply_to_id: null,
  sent_at: extra.sent_at || ago(30), edited_at: null, deleted_at: null,
  delivery: self ? 'read' : 'received', ...extra,
});

const LIST = [
  conv('chat_saved_notes', 'Збережене', 'saved', { circle: 'saved', contact_id: null, last_snippet: 'Квитки на суботу', last_author: 'Я', last_at: ago(300), contact_verified: null }),
  conv('np', 'Nightpost', 'phantom', { circle: 'all', contact_id: null, last_snippet: 'Нагадаю о 18:00 про ключі', last_author: 'Nightpost', last_at: ago(95), contact_verified: null }),
  conv('c1', 'Оксана', 'dm', { circle: 'close', last_snippet: 'Так, чекаю біля входу', unread_count: 2, last_at: ago(4) }),
  conv('c2', 'Двір', 'group', { circle: 'all', last_snippet: 'Збори о шостій біля під\'їзду', last_author: 'Петро', last_at: ago(38), unread_count: 5, contact_id: null }),
  conv('c3', 'Богдан Наливайко', 'dm', { circle: 'work', last_snippet: 'Скинув макет, глянь до вечора', last_at: ago(140), last_author: 'Я' }),
  conv('c4', 'Новини будинку', 'group', { circle: 'all', last_snippet: 'Воду вимкнуть у четвер з 9 до 13', last_author: 'Новини будинку', last_at: ago(400), muted: true, unread_count: 3, contact_id: null, settings: { kind: 'channel', slow: '0', hist: '1', ask: '0', link: '', talk: '1', sign: '0' } }),
  conv('c5', 'Марта', 'dm', { circle: 'friends', last_snippet: 'Фото', last_kind: 'image', last_at: ago(1500) }),
  conv('c6', 'Corner Roastery', 'dm', { circle: 'social', last_snippet: 'Ваш столик на 19:30 підтверджено', last_at: ago(2900) }),
  conv('c7', 'Андрій', 'dm', { circle: 'friends', last_snippet: 'Добре, до суботи', last_at: ago(4400) }),
  conv('r1', 'Ігор Ткаченко', 'dm', { circle: 'all', contact_id: null, contact_verified: false, last_snippet: 'Добрий день, я щодо оголошення', unread_count: 1, last_self_at: null, last_at: ago(70) }),
  conv('a1', 'Курс англійської', 'group', { circle: 'all', archived: true, last_snippet: 'Домашка на п\'ятницю', last_author: 'Ірина', last_at: ago(9000), contact_id: null }),
  conv('a2', 'Сусід Віктор', 'dm', { archived: true, circle: 'social', last_snippet: 'Дякую!', last_at: ago(20000) }),
];

const GROUP_MSGS = [
  msg('g1', 'c2', false, 'Хто завтра на збори?', { author_id: 'p1', author_name: 'Петро', sent_at: ago(80) }),
  msg('g2', 'c2', false, 'Я буду, візьму список', { author_id: 'p2', author_name: 'Олена', sent_at: ago(70) }),
  msg('g3', 'c2', true, 'Буду о шостій', { sent_at: ago(60) }),
  msg('g5', 'c2', false, 'Треба скинутись на нові лампи в під\'їзді', { author_id: 'p1', author_name: 'Петро', sent_at: ago(55) }),
  msg('g6', 'c2', false, 'Я замовлю, скиньте по 50', { author_id: 'p2', author_name: 'Олена', sent_at: ago(50) }),
  msg('g7', 'c2', false, 'І ще домовитись про прибирання двору в суботу', { author_id: 'p1', author_name: 'Петро', sent_at: ago(45) }),
  msg('g4', 'c2', false, 'Збори о шостій біля під\'їзду', { author_id: 'p1', author_name: 'Петро', sent_at: ago(38) }),
];

const SCENES = {
  empty: { convs: [], msgs: {}, members: {} },
  list: {
    convs: LIST,
    msgs: {
      c1: [
        msg('m1', 'c1', false, 'Привіт! Ти вже на місці?', { sent_at: ago(90) }),
        msg('m2', 'c1', true, 'Так, чекаю біля входу.', { sent_at: ago(88) }),
        msg('m3', 'c1', false, 'Виходимо о шостій від старого мосту, беремо два ліхтарі й термос. Якщо дощ — переносимо на суботу.', { sent_at: ago(40) }),
        msg('m4', 'c1', true, 'Добре, беру ліхтар', { sent_at: ago(30) }),
        msg('m5', 'c1', false, 'Так, чекаю біля входу', { sent_at: ago(4) }),
      ],
      c2: GROUP_MSGS,
      c4: [
        msg('n1', 'c4', false, 'Ліфт у другому під\'їзді знову працює. Дякуємо всім, хто скинувся на ремонт.', { author_id: 'own', author_name: 'Новини будинку', sent_at: ago(2000) }),
        msg('n2', 'c4', false, 'Воду вимкнуть у четвер з 9 до 13. Наберіть запас з вечора.', { author_id: 'own', author_name: 'Новини будинку', sent_at: ago(400) }),
      ],
    },
  },
};

const bridgeInit = ({ convs, msgs, theme }) => {
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); localStorage.setItem('phantom_archived_chats', JSON.stringify(convs.filter((c) => c.archived).map((c) => c.id))); } catch { /* приватний режим */ }
  window.PhantomBridge = {
    getThemeMode: () => theme,
    setThemeMode: () => true,
    getLanguage: () => 'uk',
    navigate: () => true,
    call: (m, path) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') {
        return ok({ node_id: 'me', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
      }
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(convs);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm && m === 'GET') return ok(msgs[mm[1]] || []);
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

const settle = (page, ms = 700) => page.waitForTimeout(ms);

const run = async () => {
  const browser = await chromium.launch();
  for (const theme of ['day', 'night']) {
    const ctx = await browser.newContext({
      viewport: { width: W, height: H }, isMobile: true, hasTouch: true,
      deviceScaleFactor: 2, locale: 'uk-UA', colorScheme: theme === 'night' ? 'dark' : 'light',
    });
    const open = async (scene) => {
      const page = await ctx.newPage();
      await page.addInitScript(bridgeInit, { ...SCENES[scene], theme });
      await page.goto(BASE, { waitUntil: 'load' });
      await page.waitForSelector('[data-testid="chat-list"]', { timeout: 20000 });
      await settle(page, 1800);
      return page;
    };
    const shot = async (page, name) => {
      await settle(page);
      await page.screenshot({ path: `${OUT}/${name}-${PREFIX}-${theme}.png` });
      console.log(`${name}-${PREFIX}-${theme}.png`);
    };
    const steps = STEP === 'all' ? ['1', '2', '3', '4', '5'] : STEP.split(',');
    if (steps.includes('1')) {
      let p = await open('list');
      await shot(p, '1-list');
      await p.locator('[data-phantom-list]').first().evaluate((el) => { el.scrollTop = el.scrollHeight; });
      await shot(p, '1-list-end');
      await p.locator('[data-phantom-list]').first().evaluate((el) => { el.scrollTop = 0; });
      await settle(p, 400);
      const req = p.locator('[data-folder-row="requests"]');
      if (await req.count()) { await req.click(); await shot(p, '1-requests'); }
      await p.close();
      p = await open('empty');
      await shot(p, '1-empty');
      await p.close();
    }
    if (steps.includes('2')) {
      const p = await open('list');
      await p.locator('[data-chat-row="c1"]').click();
      await settle(p, 1200);
      await shot(p, '2-chat');
      const bubble = p.getByText('Добре, беру ліхтар').first();
      await bubble.click({ button: 'right' }).catch(() => {});
      await settle(p, 900);
      await shot(p, '2-menu');
      await p.close();
    }
    if (steps.includes('3')) {
      let p = await open('list');
      await p.locator('[data-chat-row="c2"]').click();
      await settle(p, 1200);
      await shot(p, '3-group');
      await p.close();
      p = await open('list');
      await p.locator('[data-chat-row="c4"]').click();
      await settle(p, 1200);
      await shot(p, '3-channel');
      await p.close();
    }
    if (steps.includes('4')) {
      let p = await open('list');
      const strip = p.locator('[data-recipient-strip]');
      if (await strip.count()) {
        for (const id of ['c1', 'c2', 'c3']) await p.locator(`[data-strip-pick="${id}"]`).click();
        await p.locator('[data-send-many-text]').fill('Привіт, {імʼя}! Збір у суботу о 10:00');
        await p.locator('[data-send-many-text]').blur();
        await shot(p, '4-strip');
        await p.locator('[data-send-many-go]').click();
        await shot(p, '4-preview');
      } else {
        await shot(p, '4-strip');
      }
      await p.close();
      p = await open('list');
      await p.locator('[data-chat-row="c2"]').click();
      await settle(p, 1200);
      await p.getByText('Хто завтра на збори?').first().click({ button: 'right' });
      await settle(p, 700);
      await p.getByText('Переслати', { exact: true }).first().click();
      await settle(p, 700);
      if (await p.locator('[data-pick-row]').count()) {
        await p.locator('[data-pick-row="c1"]').click();
        await p.locator('[data-pick-row="c3"]').click();
      }
      await shot(p, '4-forward');
      await p.close();
    }
    if (steps.includes('6')) {
      let p = await open('list');
      await p.locator('[data-chat-row="c1"]').click();
      await settle(p, 1400);
      await shot(p, '6-unread-divider');
      await p.locator('.composer-textarea').first().fill(Array.from({ length: 9 }, (_, i) => `рядок ${i + 1}`).join('\n'));
      await shot(p, '6-composer-long');
      await p.close();
      p = await open('list');
      await p.locator('[data-chat-row="c2"]').click();
      await settle(p, 1200);
      await shot(p, '6-group-runs');
      await p.close();
      p = await open('list');
      await p.locator('[data-search-pill]').first().click();
      await settle(p, 600);
      await p.locator('input[type="search"], input').first().fill('ок');
      await shot(p, '6-search');
      await p.close();
    }
    if (steps.includes('5')) {
      const p = await open('list');
      await p.locator('[data-chat-row="c2"]').click();
      await settle(p, 1200);
      await p.getByText('Хто завтра на збори?').first().click({ button: 'right' });
      await settle(p, 700);
      if (!(await p.getByText('Вибрати кілька', { exact: true }).count())) {
        await p.getByText('Ще', { exact: true }).first().click();
        await settle(p, 400);
      }
      await p.getByText('Вибрати кілька', { exact: true }).first().click();
      await settle(p, 600);
      const boxes = p.locator('[data-select-box], button[title="Вибрати повідомлення"]');
      if (await p.locator('[data-sel-by="sender"]').count()) {
        await p.locator('[data-sel-by="sender"]').click();
      } else if ((await boxes.count()) > 3) {
        await boxes.nth(3).click();
        await boxes.nth(5).click();
      }
      await shot(p, '5-select');
      await p.close();
    }
    await ctx.close();
  }
  await browser.close();
};

run().catch((e) => { console.error(e); process.exit(1); });
