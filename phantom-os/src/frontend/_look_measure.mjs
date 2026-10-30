// Геометрія бульбашок і контраст тексту на ширині A52. Прилад, не тест.
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
const msg = (i, self, body) => ({ id: `m${i}`, conversation_id: 'c1', client_id: `cl${i}`, seq: i,
  author_id: self ? 'me' : 'peer_c1', author_name: self ? 'Я' : 'Оксана', kind: 'text', body,
  ciphertext: null, transport: 'direct', reply_to_id: null, sent_at: ago(60 - i),
  edited_at: null, deleted_at: null, delivery: self ? 'sent' : 'received' });

const MSGS = [msg(1, false, 'Привіт! Ти вже на місці?'), msg(2, true, 'Так, чекаю біля входу.'), msg(3, true, 'Добре')];

const bridgeInit = ({ conv, MSGS }) => {
  const ok = (d) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }
  window.PhantomBridge = { call: (m, path) => {
    const q = path.split('?')[0];
    if (q === '/messenger/identity') return ok({ node_id: 'me', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778' });
    if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok([conv]);
    if (q === '/messenger/contacts') return ok([]);
    if (q === '/messenger/queue/status') return ok({ queued: 0 });
    if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
    if (/^\/messenger\/conversations\/[^/]+\/messages$/.test(q) && m === 'GET') return ok(MSGS);
    if (/\/read$/.test(q)) return ok({});
    return nf;
  } };
};

// Контраст WCAG: колір тексту проти першого непрозорого предка.
const probe = () => {
  const lum = (c) => {
    const [r, g, b] = c.map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const parse = (s) => (s.match(/[\d.]+/g) || []).slice(0, 4).map(Number);
  const bgOf = (el) => {
    let n = el;
    while (n && n !== document.documentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c.length >= 3 && (c[3] === undefined || c[3] > 0.9)) return c.slice(0, 3);
      n = n.parentElement;
    }
    return [12, 17, 13];
  };
  const contrast = (el) => {
    const fg = parse(getComputedStyle(el).color).slice(0, 3);
    const a = lum(fg) + 0.05, b = lum(bgOf(el)) + 0.05;
    return Number((Math.max(a, b) / Math.min(a, b)).toFixed(2));
  };

  const out = { bubbles: [], meta: [], lowContrast: [] };
  document.querySelectorAll('.msg-bubble-fluid').forEach((b) => {
    const r = b.getBoundingClientRect();
    const meta = b.querySelector('.msg-meta-row');
    const tf = b.querySelector('.touch-floor');
    out.bubbles.push({
      text: (b.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 30),
      self: b.classList.contains('msg-bubble-self'),
      h: Math.round(r.height), w: Math.round(r.width),
      metaH: meta ? Math.round(meta.getBoundingClientRect().height) : null,
      touchFloorH: tf ? Math.round(tf.getBoundingClientRect().height) : null,
      border: getComputedStyle(b).borderTopWidth + ' ' + getComputedStyle(b).borderTopColor,
      shadow: getComputedStyle(b).boxShadow.slice(0, 40),
    });
    if (meta) out.meta.push({ color: getComputedStyle(meta).color, contrast: contrast(meta) });
  });

  // Кожен видимий текстовий листок: де контраст нижче 4.5.
  Array.from(document.querySelectorAll('*')).forEach((el) => {
    if (el.children.length || !(el.textContent || '').trim()) return;
    const r = el.getBoundingClientRect();
    if (r.height < 1 || r.width < 1) return;
    const c = contrast(el);
    if (c < 4.5) out.lowContrast.push({ t: el.textContent.trim().slice(0, 32), c, color: getComputedStyle(el).color });
  });
  return out;
};

const run = async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, isMobile: true,
    hasTouch: true, deviceScaleFactor: DSF, locale: 'uk-UA' });
  const page = await ctx.newPage();
  await page.addInitScript(bridgeInit, { conv, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);
  await page.getByText('Оксана').first().click();
  await page.waitForTimeout(1200);
  console.log(JSON.stringify(await page.evaluate(probe), null, 1));
  await browser.close();
};
run().catch((e) => { console.error(e); process.exit(1); });
