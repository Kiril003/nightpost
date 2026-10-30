import { test, expect, type Page } from '@playwright/test';

/**
 * Головна дія аркуша досяжна на 360×640 з textZoom 130% і з клавіатурою (326 px).
 * textZoom WebView множить розмір шрифту, а не коробки: тут так само, скриптом.
 */
const BASE = process.env.PHANTOM_WEB_BASE ?? '/';
const KEYBOARD_H = 326;

test.use({ viewport: { width: 360, height: 640 }, isMobile: true, hasTouch: true, locale: 'uk-UA' });

const now = new Date().toISOString();
const CONVS = [
  { id: 'c1', title: 'Марта', kind: 'direct', circle: 'friends', handle: null, avatar: null, pinned: false, archived: false, created_at: now, updated_at: now, contact_id: null, is_demo: false, contact_verified: true, peer_node_id: 'peer_c1', unread_count: 0, last_kind: 'text', last_snippet: 'Привіт', last_author: 'Марта', last_at: now },
];

const bridgeInit = ({ CONVS }: { CONVS: unknown[] }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (_m: string, path: string) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') return ok({ node_id: 'abc123', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      return nf;
    },
  };
};

/** Android textZoom 130%: шрифти ×1.3, коробки ті самі. */
const zoomText = () => {
  const done = new WeakSet<Element>();
  const apply = (root: ParentNode) => {
    for (const el of Array.from(root.querySelectorAll('*'))) {
      if (done.has(el)) continue;
      done.add(el);
      const cs = getComputedStyle(el);
      const fs = parseFloat(cs.fontSize);
      if (!fs) continue;
      (el as HTMLElement).style.fontSize = `${fs * 1.3}px`;
      const lh = parseFloat(cs.lineHeight);
      if (lh) (el as HTMLElement).style.lineHeight = `${lh * 1.3}px`;
    }
  };
  const start = () => {
    apply(document);
    new MutationObserver(() => apply(document)).observe(document.body, { childList: true, subtree: true });
  };
  if (document.body) start();
  else addEventListener('DOMContentLoaded', start);
};

/** Остання акцентна кнопка діалогу: у вікні, не перекрита, не за краєм області прокрутки. */
const reach = (page: Page) =>
  page.evaluate(() => {
    const dlg = document.querySelector('[role="dialog"]');
    if (!dlg) return { skipped: true as const };
    const btns = Array.from(dlg.querySelectorAll<HTMLElement>('button')).filter((b) => /bg-accent\b/.test(b.className) && !b.hasAttribute('disabled'));
    const b = btns[btns.length - 1];
    if (!b) return { skipped: true as const };
    const r = b.getBoundingClientRect();
    const inView = r.top >= -0.5 && r.bottom <= innerHeight + 0.5 && r.left >= -0.5 && r.right <= innerWidth + 0.5;
    const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    const top = !!hit && (hit === b || b.contains(hit));
    let clipped = false;
    for (let p = b.parentElement; p && p !== document.body; p = p.parentElement) {
      const cs = getComputedStyle(p);
      if (cs.overflowY === 'visible') continue;
      const pr = p.getBoundingClientRect();
      if (r.top < pr.top - 0.5 || r.bottom > pr.bottom + 0.5) clipped = true;
    }
    return { skipped: false as const, inView, top, clipped, label: (b.textContent ?? '').trim().slice(0, 30) };
  });

const SHEETS: [string, string][] = [
  ['new-chat', '[title="Новий діалог"], [aria-label="Створити новий діалог"]'],
  ['settings', '[title="Налаштування застосунку"], [aria-label="Налаштування"]'],
];

for (const [name, trigger] of SHEETS) {
  for (const keyboard of [false, true]) {
    test(`${name}${keyboard ? ' + клавіатура' : ''}: головна дія досяжна`, async ({ page }) => {
      await page.addInitScript(bridgeInit, { CONVS });
      await page.addInitScript(zoomText);
      await page.goto(BASE, { waitUntil: 'load' });
      await page.waitForTimeout(2500);
      const t = page.locator(trigger).first();
      test.skip((await t.count()) === 0, `немає входу ${name}`);
      await t.click({ force: true });
      await page.waitForTimeout(700);
      if (keyboard) {
        await page.locator('[role="dialog"] input, [role="dialog"] textarea').first().focus({ timeout: 1500 }).catch(() => undefined);
        await page.setViewportSize({ width: 360, height: KEYBOARD_H });
        await page.waitForTimeout(300);
      }
      const r = await reach(page);
      test.skip(r.skipped, 'у діалозі немає акцентної кнопки');
      if (!r.skipped) {
        expect(r.inView, `«${r.label}» поза екраном`).toBe(true);
        expect(r.top, `«${r.label}» перекрита`).toBe(true);
        expect(r.clipped, `«${r.label}» за краєм області прокрутки`).toBe(false);
      }
    });
  }
}
