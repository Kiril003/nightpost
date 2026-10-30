// Кадри решти екранів телефона (390×844) з підставним мостом.
//   node _screens_shots.mjs <outDir> <tag> [port] [only]
// Сервер: `VITE_TARGET=phone npx vite --port <port>` (база /webmsgr/).
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = process.argv[2] || '/tmp/shots';
const TAG = process.argv[3] || 'before';
const PORT = process.argv[4] || '18950';
const ONLY = process.argv[5] || '';

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
const msg = (cid, id, self, body, min, who = 'Оксана', peer = 'p1') => ({
  id, conversation_id: cid, client_id: `cl_${id}`, seq: Number(id.replace(/\D/g, '')),
  author_id: self ? 'me' : peer, author_name: self ? 'Я' : who, kind: 'text', body, ciphertext: null,
  transport: 'direct', reply_to_id: null, sent_at: ago(min), edited_at: null, deleted_at: null,
  delivery: self ? 'sent' : 'received',
});
const MSGS = {
  d1: [
    msg('d1', 'm1', false, 'Сиджу в кав’ярні навпроти пошти', 30),
    msg('d1', 'm2', false, 'Можеш забрати мою посилку до 20:00?', 29),
    msg('d1', 'm3', true, 'Заберу, скинь номер', 20),
    msg('d1', 'm4', false, 'Номер 2045 1188 3302, скажеш на касі, що від Мельник', 12),
    msg('d1', 'm5', true, 'Добре, буду о 19:40', 3),
  ],
  g1: [
    msg('g1', 'g1', false, 'Хто сьогодні чергує біля генератора?', 90, 'Марта', 'p6'),
    msg('g1', 'g2', true, 'Я до восьмої', 80),
    msg('g1', 'g3', false, 'Світло дали, генератор вимкнули', 54, 'Марта', 'p6'),
  ],
};
const contact = (id, peer, name, ready = true) => ({
  id, peer_node_id: peer, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '',
  verified: true, session_ready: ready, created_at: ago(9000),
});
const CONTACTS = [
  contact('c1', 'p1', 'Оксана'), contact('c2', 'p2', 'Тарас'), contact('c3', 'p3', 'Мама'),
  contact('c4', 'p4', 'Андрій'), contact('c5', 'p5', 'Софія'), contact('c6', 'p6', 'Марта'),
  contact('c7', 'p7', 'Дмитро', false),
];
const member = (id, name, role = 'member', extra = {}) => ({
  id, name, in_roster: true, verified: true, session_ready: true, is_me: false, role,
  may_remove: false, may_grant: false, may_own: false, ...extra,
});
const MEMBERS = {
  id: 'g1', title: 'Двір на Костянтинівській', authoritative: false, standing: 'in', my_role: 'admin', owner_id: 'p6',
  max_members: 32, addable: [{ id: 'p5', name: 'Софія' }],
  powers: { add: true, remove: true, rename: true, card: true, grant: true, transfer: false, pin: true },
  members: [
    member('me', 'Кирило', 'admin', { is_me: true }),
    member('p6', 'Марта', 'owner'),
    member('p1', 'Оксана', 'mod', { may_remove: true, may_grant: true }),
    member('p2', 'Тарас', 'member', { may_remove: true, may_grant: true }),
    member('p3', 'Мама', 'member', { may_remove: true, may_grant: true }),
    member('p4', 'Андрій', 'member', { session_ready: false, may_remove: true, may_grant: true }),
  ],
};

const bridgeInit = ({ CONVS, MSGS, CONTACTS, MEMBERS }) => {
  try { localStorage.clear(); } catch { /* */ }
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  window.PhantomBridge = {
    shareText: () => true,
    call: (m, path) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity')
        return ok({ node_id: 'me', link: 'phantom://invite/PH2:AbCdEfGh123:a1b2c3', compact: 'PH2:AbCdEfGh123:a1b2c3', bundle: { name: 'Кирило' } });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok(CONTACTS);
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm) return ok(MSGS[mm[1]] || []);
      if (/^\/messenger\/conversations\/g1\/members$/.test(q)) return ok(MEMBERS);
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
const open = async () => {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('pageerror', e.message.slice(0, 200)));
  await page.addInitScript(bridgeInit, { CONVS, MSGS, CONTACTS, MEMBERS });
  await page.goto(`http://127.0.0.1:${PORT}/webmsgr/index.html`);
  await page.getByLabel('Створити новий діалог').waitFor({ timeout: 60000 });
  await page.waitForTimeout(800);
  await page.evaluate(() => document.fonts.ready);
  return page;
};
const shot = async (p, screen, name) => {
  mkdirSync(`${OUT}/${screen}`, { recursive: true });
  await p.waitForTimeout(500);
  await p.screenshot({ path: `${OUT}/${screen}/${TAG}_${name}.png` });
};
const tryDo = async (label, fn) => {
  try { await fn(); } catch (e) { console.log(`[${label}]`, String(e.message).split('\n')[0]); }
};
const want = (s) => !ONLY || ONLY.split(',').includes(s);

const openChat = async (p, name) => {
  await p.locator('[data-chat-row], li, button').filter({ hasText: name }).first().click();
  await p.waitForTimeout(1200);
};
const longPress = async (p, text) => {
  await p.getByText(text, { exact: true }).first().click({ button: 'right' });
  await p.waitForTimeout(600);
};

if (want('create')) await tryDo('create', async () => {
  const p = await open();
  await p.getByLabel('Створити новий діалог').click();
  await shot(p, 'create', 'doors');
  const after = await p.getByText('Нова група').count();
  await p.getByRole('button', { name: after ? /Нова група/ : /^Група/ }).click();
  await p.waitForTimeout(600);
  await shot(p, 'create', 'group');
  await p.getByRole('checkbox', { name: /Оксана/ }).click();
  await p.getByRole('checkbox', { name: /Тарас/ }).click();
  await p.locator('#new-group-title').fill('Сусіди');
  await p.locator('#new-group-title').blur();
  await shot(p, 'create', 'group_picked');
  await p.getByLabel('Назад').click();
  await p.getByRole('button', { name: after ? /Маю запрошення/ : /^Людина/ }).click();
  await p.waitForTimeout(800);
  await shot(p, 'create', 'person');
  await p.locator('textarea').first().fill('PH2:ZxYwVuTs987:0f9e8d');
  await p.waitForTimeout(600);
  await shot(p, 'create', 'incoming');
  if (after) {
    await p.getByLabel('Назад').click();
    await p.getByRole('button', { name: /^Запросити/ }).click();
    await p.waitForTimeout(1200);
    await shot(p, 'create', 'invite');
    await p.getByLabel('Назад').click();
    await p.getByPlaceholder('Імʼя або @нік').fill('@marta');
    await shot(p, 'create', 'search');
  }
  await p.close();
});

if (want('invite')) await tryDo('invite', async () => {
  const p = await open();
  await p.locator('button[aria-label*="Я"], button[aria-label*="профіль"], button[aria-label*="Налаштування"]').first().click();
  await p.waitForTimeout(900);
  await p.getByText('Показати поруч').first().click();
  await p.waitForTimeout(1200);
  await shot(p, 'invite', 'code');
  await p.close();
});

if (want('group')) await tryDo('group', async () => {
  const p = await open();
  await openChat(p, 'Двір на Костянтинівській');
  await p.getByRole('button', { name: /Двір на Костянтинівській/ }).first().click();
  await p.waitForTimeout(1500);
  await shot(p, 'group', 'profile');
  await p.locator('[data-testid="group-profile"] .overflow-y-auto').first().evaluate((el) => (el.scrollTop = 9999));
  await shot(p, 'group', 'profile_scrolled');
  await tryDo('member-open', async () => {
    await p.getByLabel('Дії над учасником').first().click();
    await shot(p, 'group', 'member_actions');
  });
  await tryDo('manage', async () => {
    await p.getByRole('button', { name: /Керування групою/ }).click();
    await shot(p, 'group', 'manage');
  });
  await p.close();
});

if (want('call')) await tryDo('call', async () => {
  const p = await open();
  await p.evaluate(async () => {
    const { callEngine } = await import('/webmsgr/src/services/callEngine.ts');
    callEngine.patch({
      state: 'active', media: 'audio', peer: { displayName: 'Оксана', peerNodeId: 'p1' },
      startedAt: Date.now() - 134000, micOn: true, speakerOn: true, hasCamera: false, cameraOn: false, legs: [],
    });
  });
  await shot(p, 'call', 'active');
  await p.evaluate(async () => {
    const { callEngine } = await import('/webmsgr/src/services/callEngine.ts');
    callEngine.patch({ state: 'ringing', startedAt: null });
  });
  await shot(p, 'call', 'ringing');
  await p.evaluate(async () => {
    const { callEngine } = await import('/webmsgr/src/services/callEngine.ts');
    callEngine.patch({ state: 'calling', startedAt: null });
  });
  await shot(p, 'call', 'calling');
  await p.close();
});

if (want('menu')) await tryDo('menu', async () => {
  const p = await open();
  await openChat(p, 'Оксана');
  await longPress(p, 'Заберу, скинь номер');
  await shot(p, 'message-sheet', 'menu');
  await tryDo('more', async () => {
    await p.locator('[data-msg-more]').click();
    await shot(p, 'message-sheet', 'menu_more');
  });
  await tryDo('details', async () => {
    await p.getByRole('button', { name: /Подробиці/ }).first().click();
    await shot(p, 'details', 'details');
  });
  await p.close();
});

if (want('delete')) await tryDo('delete', async () => {
  const p = await open();
  await openChat(p, 'Оксана');
  await longPress(p, 'Заберу, скинь номер');
  await p.getByRole('button', { name: /^Видалити/ }).click();
  await shot(p, 'delete', 'self');
  await p.close();
  const q = await open();
  await openChat(q, 'Оксана');
  await longPress(q, 'Можеш забрати мою посилку до 20:00?');
  await q.getByRole('button', { name: /^Видалити/ }).click();
  await shot(q, 'delete', 'theirs');
  await q.close();
});

if (want('forward')) await tryDo('forward', async () => {
  const p = await open();
  await openChat(p, 'Оксана');
  await longPress(p, 'Заберу, скинь номер');
  await p.getByRole('button', { name: /^Переслати/ }).click();
  await shot(p, 'forward', 'forward');
  await p.close();
});

if (want('lightbox')) await tryDo('lightbox', async () => {
  const p = await open();
  await openChat(p, 'Оксана');
  await p.evaluate(() => {
    const c = document.createElement('canvas');
    c.width = 900; c.height = 1200;
    const g = c.getContext('2d');
    const gr = g.createLinearGradient(0, 0, 900, 1200);
    gr.addColorStop(0, '#c77b3a'); gr.addColorStop(0.5, '#6b3d2a'); gr.addColorStop(1, '#1d2433');
    g.fillStyle = gr; g.fillRect(0, 0, 900, 1200);
    g.fillStyle = 'rgba(255,230,190,.8)'; g.beginPath(); g.arc(620, 340, 120, 0, 7); g.fill();
    window.__phantom_messenger_store.getState().openLightbox(c.toDataURL('image/jpeg'), 'Оксана');
  });
  await shot(p, 'lightbox', 'single');
  await p.close();
});

await browser.close();
