// Геометрія пігулки реакції на ширині A52. Прилад, не тест.
import { chromium } from '@playwright/test';

const BASE = 'http://127.0.0.1:4174/webmsgr/';
const DSF = 2.625;
const W = Math.round(1080 / DSF);
const H = Math.round((2156 - 88) / DSF);

const now = new Date().toISOString();
const ago = (m) => new Date(Date.now() - m * 60000).toISOString();
const conv = { id: 'c1', title: 'Оксана', kind: 'direct', circle: 'friends', handle: null,
  avatar: null, pinned: false, archived: false, created_at: now, updated_at: now,
  contact_id: null, is_demo: false, contact_verified: true, peer_node_id: 'peer_c1',
  unread_count: 0, last_kind: 'text', last_snippet: 'Добре', last_author: 'Я', last_at: ago(30) };
const msg = (i, self, body, reactions) => ({ id: `m${i}`, conversation_id: 'c1', client_id: `cl${i}`, seq: i,
  author_id: self ? 'me' : 'peer_c1', author_name: self ? '' : 'Оксана', kind: 'text', body,
  ciphertext: null, transport: 'direct', reply_to_id: null, sent_at: ago(60 - i),
  edited_at: null, deleted_at: null, delivery_state: self ? 'sent' : 'received',
  reactions: reactions || [] });

const MSGS = [
  msg(1, false, 'Привіт! Ти вже на місці?', [
    { emoji: '👍', count: 1, users: ['me'], names: ['Я'], mine: 'delivered' },
  ]),
  msg(2, true, 'Так, чекаю біля входу.', [
    { emoji: '❤️', count: 2, users: ['peer_c1', 'me'], names: ['Оксана', 'Я'], mine: 'sending' },
    { emoji: '😂', count: 1, users: ['peer_c1'], names: ['Оксана'] },
  ]),
  msg(3, true, 'Добре'),
];

const bridgeInit = ({ conv, MSGS }) => {
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }
  window.__calls = [];
  window.PhantomBridge = { call: (m, path, body) => {
    const q = path.split('?')[0];
    window.__calls.push(`${m} ${q}`);
    if (q === '/messenger/identity') return ok({ node_id: 'me', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778' });
    if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok([conv]);
    if (q === '/messenger/contacts') return ok([]);
    if (q === '/messenger/queue/status') return ok({ queued: 0 });
    if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
    if (/^\/messenger\/conversations\/[^/]+\/messages$/.test(q) && m === 'GET') return ok(MSGS);
    if (/\/reactions$/.test(q)) {
      const em = JSON.parse(body || '{}').emoji;
      const row = { ...MSGS[0], reactions: [{ emoji: em, count: 1, users: ['me'], names: ['Я'], mine: 'queued' }] };
      return ok(row);
    }
    if (/\/read$/.test(q)) return ok({});
    return nf;
  } };
};

const measure = () => {
  const out = { pills: [], bubbles: [], addBtn: null };
  document.querySelectorAll('[aria-label]').forEach((el) => {
    const al = el.getAttribute('aria-label') || '';
    if (/^(👍|❤️|😂),/.test(al)) {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      out.pills.push({ label: al.slice(0, 30), w: Math.round(r.width), h: Math.round(r.height),
        minW: cs.minWidth, minH: cs.minHeight, radius: cs.borderTopLeftRadius });
    }
    if (al === 'Додати реакцію') {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      out.addBtn = { w: Math.round(r.width), h: Math.round(r.height), minW: cs.minWidth, minH: cs.minHeight };
    }
  });
  return out;
};

const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1, isMobile: true, hasTouch: true });
await p.addInitScript(bridgeInit, { conv, MSGS });
await p.goto(BASE, { waitUntil: 'networkidle' });
await p.waitForTimeout(1200);
// у розмову
await p.evaluate(() => window.__phantom_messenger_store.getState().setActiveChat('c1'));
await p.waitForTimeout(2500);
console.log('ВИКЛИКИ', JSON.stringify(await p.evaluate(() => window.__calls)));
console.log('ШИРИНА', W, 'x', H);
console.log(JSON.stringify(await p.evaluate(measure), null, 1));
console.log('RELOAD', JSON.stringify(await p.evaluate(async () => {
  const st = window.__phantom_messenger_store.getState();
  await st.loadMessagesForChat('c1');
  const c = window.__phantom_messenger_store.getState().chats.find(x=>x.id==='c1');
  return { n: c.messages.length, first: c.messages[0], hasR: c.messages.map(m=>!!m.reactions) };
})));
console.log('RAW', JSON.stringify(await p.evaluate(async () => {
  const r = await window.PhantomBridge.call('GET','/messenger/conversations/c1/messages?after_seq=0');
  const d = JSON.parse(r);
  return { keys: Object.keys(d.data[0]), reactions: d.data[0].reactions };
})));
console.log('ARIA', JSON.stringify(await p.evaluate(() => [...document.querySelectorAll('[aria-label]')].map(e=>e.getAttribute('aria-label')).slice(0,60))));
console.log('STORE', JSON.stringify(await p.evaluate(() => {
  const st = window.__phantom_messenger_store.getState();
  const c = st.chats.find(x=>x.id==='c1');
  return { active: st.activeChatId, n:(c?.messages||[]).length, full: (c?.messages||[])[0] };
})));
await p.screenshot({ path: process.env.SHOT || '/tmp/claude-1000/-home-kyrylo-phantom-ai-PHANTOM-OS-BLUEPRINT/6b563409-7a7f-43a6-9542-7263dae6b29d/scratchpad/react-before.png' });
await b.close();
