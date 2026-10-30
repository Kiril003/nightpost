// Контраст кожного видимого напису на кожній поверхні телефона.
// Пастка теми: обсидіан перекриває ЛИШЕ перелічені світлі hex-класи, тож новий
// світлий клас удень непомітний, а вночі світиться. Тести цього не бачать —
// видно лише з обчислених стилів.
import { chromium } from '@playwright/test';

const BASE = 'http://127.0.0.1:4174/webmsgr/';
const DSF = 2.625;
const W = Math.round(1080 / DSF);
const H = Math.round((2156 - 88) / DSF);
const now = new Date().toISOString();
const ago = (m) => new Date(Date.now() - m * 60000).toISOString();

const conv = (id, title, kind) => ({ id, title, kind, circle: 'friends', handle: null,
  avatar: null, pinned: false, archived: false, created_at: now, updated_at: now,
  contact_id: null, is_demo: false, contact_verified: true, peer_node_id: `p_${id}`,
  unread_count: 0, last_kind: 'text', last_snippet: 'Добре', last_author: title, last_at: ago(20) });
const msg = (i, self, body) => ({ id: `m${i}`, conversation_id: 'c1', client_id: `c${i}`, seq: i,
  author_id: self ? 'me' : 'p_c1', author_name: self ? 'Я' : 'Оксана', kind: 'text', body,
  ciphertext: null, transport: 'direct', reply_to_id: null, sent_at: ago(60 - i),
  edited_at: null, deleted_at: null, delivery: self ? 'sent' : 'received' });

const SCENES = {
  'порожній список': { convs: [], msgs: [] },
  'список розмов': { convs: [conv('c1', 'Оксана', 'direct'), conv('c2', 'Двір', 'group')], msgs: [] },
  'розмова': { convs: [conv('c1', 'Оксана', 'direct')], msgs: [msg(1, false, 'Привіт!'), msg(2, true, 'Так, чекаю.')] },
  'порожня розмова': { convs: [conv('c1', 'Оксана', 'direct')], msgs: [] },
};

const bridgeInit = ({ convs, msgs }) => {
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }
  window.PhantomBridge = { call: (m, path) => {
    const q = path.split('?')[0];
    if (q === '/messenger/identity') return ok({ node_id: 'me', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778' });
    if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(convs);
    if (q === '/messenger/contacts') return ok([]);
    if (q === '/messenger/queue/status') return ok({ queued: 0 });
    if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
    if (/^\/messenger\/conversations\/[^/]+\/messages$/.test(q) && m === 'GET') return ok(msgs);
    if (/\/read$/.test(q)) return ok({});
    return nf;
  } };
};

const sweep = () => {
  const lum = (c) => { const [r, g, b] = c.map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  const parse = (s) => (s.match(/[\d.]+/g) || []).slice(0, 4).map(Number);
  const bgOf = (el) => { let n = el; while (n && n !== document.documentElement) {
    const c = parse(getComputedStyle(n).backgroundColor);
    if (c.length >= 3 && (c[3] === undefined || c[3] > 0.9)) return c.slice(0, 3); n = n.parentElement; } return [12, 17, 13]; };
  const bad = [];
  Array.from(document.querySelectorAll('*')).forEach((el) => {
    if (el.children.length || !(el.textContent || '').trim()) return;
    const r = el.getBoundingClientRect();
    if (r.height < 1 || r.width < 1) return;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.opacity === '0') return;
    const fg = parse(cs.color).slice(0, 3);
    const a = lum(fg) + 0.05, b = lum(bgOf(el)) + 0.05;
    const ratio = Math.max(a, b) / Math.min(a, b);
    // 3.0 для великого кегля (18px+ або 14px+ жирний), 4.5 для решти.
    const px = parseFloat(cs.fontSize);
    const large = px >= 18 || (px >= 14 && parseInt(cs.fontWeight, 10) >= 600);
    if (ratio < (large ? 3 : 4.5)) bad.push({ t: el.textContent.trim().slice(0, 40), r: Number(ratio.toFixed(2)), px, color: cs.color });
  });
  return bad;
};

const run = async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, isMobile: true,
    hasTouch: true, deviceScaleFactor: DSF, locale: 'uk-UA' });
  let fails = 0;
  for (const [name, scene] of Object.entries(SCENES)) {
    const page = await ctx.newPage();
    await page.addInitScript(bridgeInit, scene);
    await page.goto(BASE, { waitUntil: 'load' });
    await page.waitForTimeout(2200);
    if (name.includes('розмова')) {
      await page.getByText('Оксана').first().click().catch(() => {});
      await page.waitForTimeout(1000);
    }
    const bad = await page.evaluate(sweep);
    fails += bad.length;
    console.log(`${bad.length === 0 ? 'OK  ' : 'НИЗЬКИЙ'} ${name}${bad.length ? '\n' + JSON.stringify(bad, null, 1) : ''}`);
    await page.close();
  }
  await browser.close();
  process.exit(fails ? 1 : 0);
};
run().catch((e) => { console.error(e); process.exit(1); });
