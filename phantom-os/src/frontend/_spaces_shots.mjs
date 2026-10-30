// Кадри просторів v2 (390×844, День і Ніч) з підставним мостом і журналом простору.
//   node _spaces_shots.mjs <outDir> [port]
// Сервер: `VITE_TARGET=phone npx vite --port <port>` (база /webmsgr/).
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = process.argv[2] || '/tmp/spaces-shots';
const PORT = process.argv[3] || '18951';
mkdirSync(OUT, { recursive: true });

const ago = (m) => new Date(Date.now() - m * 60000).toISOString();
const conv = (id, title, kind, peer, unread, snip, author, min, settings = null) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false, archived: false,
  created_at: ago(9000), updated_at: ago(min), contact_id: null, is_demo: false, contact_verified: true,
  peer_node_id: peer, unread_count: unread, last_kind: 'text', last_snippet: snip, last_author: author, last_at: ago(min), settings,
});
const CONVS = [
  conv('sv1', 'Vertical', 'group', null, 3, 'Хто має зайву каску?', 'Кай', 4, { kind: 'space' }),
  conv('d1', 'Оксана', 'direct', 'p1', 0, 'До зустрічі', 'Оксана', 30),
  conv('sv2', 'Сусіди Липки', 'group', null, 0, 'Воду дадуть о 18:00', 'Марта', 90, { kind: 'space' }),
];
const contact = (id, peer, name, ready = true) => ({
  id, peer_node_id: peer, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '',
  verified: true, session_ready: ready, created_at: ago(9000),
});
const CONTACTS = [contact('c1', 'p1', 'Оксана'), contact('c2', 'p2', 'Тарас'), contact('c3', 'p3', 'Кай'), contact('c4', 'p4', 'Яна'), contact('c5', 'p5', 'Дмитро', false)];

// Журнали так, як їх віддав би міст: актор — підписант, тіло — подія.
const T = Date.now() - 86400000;
const log = (rows) => rows.map((body, i) => ({ actor: 'me', at: T + i, event: JSON.stringify({ id: `e${i}`, lamport: i + 1, at: T + i, body }) }));
const role = { id: 'r1', name: 'Учасник', color: 0, rank: 10, perms: ['read', 'write', 'media', 'links', 'thread.create'], until: null };
const SPACES = {
  sv1: log([
    { t: 'space.create', name: 'Vertical', icon: null, template: 'club' },
    { t: 'role.put', role },
    { t: 'space.edit', defaultRoleId: 'r1' },
    { t: 'category.put', category: { id: 'ci', name: 'Інфо', order: 0, lockedTo: [] } },
    { t: 'category.put', category: { id: 'ct', name: 'Розмови', order: 1, lockedTo: [] } },
    { t: 'channel.put', channel: { id: 'a1', categoryId: 'ci', type: 'announce', name: 'Оголошення', order: 0, overrides: [] } },
    { t: 'channel.put', channel: { id: 'a2', categoryId: 'ci', type: 'announce', name: 'Правила', order: 1, overrides: [] } },
    { t: 'channel.put', channel: { id: 'c1', categoryId: 'ct', type: 'chat', name: 'Загальна', order: 0, overrides: [] } },
    { t: 'channel.put', channel: { id: 'f1', categoryId: 'ct', type: 'forum', name: 'Спорядження', order: 1, overrides: [] } },
    { t: 'channel.put', channel: { id: 'v1', categoryId: 'ct', type: 'voice', name: 'Зал', order: 2, overrides: [] } },
    ...['p1', 'p2', 'p3', 'p4'].map((m) => ({ t: 'member.add', memberId: m, roleIds: ['r1'] })),
  ]),
  sv2: log([
    { t: 'space.create', name: 'Сусіди Липки', icon: null, template: 'home' },
    { t: 'role.put', role },
    { t: 'space.edit', defaultRoleId: 'r1' },
    { t: 'category.put', category: { id: 'ct', name: 'Розмови', order: 0, lockedTo: [] } },
    { t: 'channel.put', channel: { id: 'c1', categoryId: 'ct', type: 'chat', name: 'Загальна', order: 0, overrides: [] } },
    { t: 'member.add', memberId: 'p2', roleIds: ['r1'] },
  ]),
};
const msg = (cid, id, body, min, who, peer, channel) => ({
  id, conversation_id: cid, client_id: `cl_${id}`, seq: Number(id.replace(/\D/g, '')),
  author_id: peer, author_name: who, kind: 'text', body, ciphertext: null, channel_id: channel,
  transport: 'direct', reply_to_id: null, sent_at: ago(min), edited_at: null, deleted_at: null, delivery: 'received',
});
const MSGS = {
  sv1: [
    msg('sv1', 'm1', 'Виїзд у суботу: список', 300, 'Марек', 'p2', 'a1'),
    msg('sv1', 'm2', 'Хто має зайву каску?', 4, 'Кай', 'p3', 'c1'),
  ],
};

const bridgeInit = ({ CONVS, MSGS, CONTACTS, SPACES, theme }) => {
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* */ }
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  window.PhantomBridge = {
    shareText: () => true,
    getThemeMode: () => theme,
    setThemeMode: () => true,
    call: (m, path) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity')
        return ok({ node_id: 'me', link: 'phantom://invite/PH2:AbCdEfGh123:a1b2c3', compact: 'PH2:AbCdEfGh123:a1b2c3', bundle: { name: 'Кирило' } });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok(CONTACTS);
      const sp = q.match(/^\/messenger\/spaces\/([^/]+)\/events$/);
      if (sp) return ok({ events: SPACES[decodeURIComponent(sp[1])] || [], members: [] });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm) return ok(MSGS[mm[1]] || []);
      if (q.startsWith('/messenger/conversations/')) return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      return ok({});
    },
  };
};

const browser = await chromium.launch();
for (const theme of ['day', 'night']) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, locale: 'uk-UA',
    colorScheme: theme === 'night' ? 'dark' : 'light',
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('pageerror', e.message.slice(0, 200)));
  await page.addInitScript(bridgeInit, { CONVS, MSGS, CONTACTS, SPACES, theme });
  await page.goto(`http://127.0.0.1:${PORT}/webmsgr/index.html`, { timeout: 180000 });
  await page.getByLabel('Створити новий діалог').waitFor({ timeout: 90000 });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(800);
  const shot = async (name) => {
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${OUT}/${name}-390-${theme}.png` });
    console.log(`${name}-390-${theme}.png`);
  };
  // sv-home: дотик по простору в списку розмов.
  await page.locator('button, li, [data-chat-row]').filter({ hasText: 'Vertical' }).first().click();
  await page.getByTestId('space-rooms').waitFor({ timeout: 15000 }).catch(async (e) => {
    await page.screenshot({ path: `${OUT}/debug-${theme}.png` });
    throw e;
  });
  await shot('sv-home');
  // sv-switch: назва ▾ відкриває перемикач.
  await page.getByTestId('space-switch').click();
  await page.getByTestId('space-switch-sheet').waitFor();
  await shot('sv-switch');
  // sv-create: «Створити простір» з перемикача; шаблон і люди вибрані.
  await page.getByTestId('space-create-door').click();
  await page.getByTestId('space-create').waitFor();
  await page.getByTestId('space-name').fill('Хор Липки');
  await page.getByTestId('space-tpl-course').click();
  await page.getByTestId('space-people-row').click();
  await page.getByTestId('space-people').getByText('Яна').click();
  await page.getByTestId('space-people').getByText('Тарас').click();
  await shot('sv-create-people');
  await page.getByTestId('space-people-done').click();
  await page.locator('input').first().blur();
  await shot('sv-create');
  await ctx.close();
}
await browser.close();
