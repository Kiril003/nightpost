import { test, expect, type Page, type CDPSession } from '@playwright/test';

/**
 * ПРИЛАД «один вхідний лист у довгій розмові».
 *
 * Власник: «у сотні разів повільніше за Telegram». Найчастіша подія в
 * месенджері — не прокрутка і не наведення, а ПРИХІД листа у відкриту
 * розмову. Тут вона міряється саме так, як трапляється на телефоні:
 * міст кладе рядок у журнал і смикає веб — дельтою (`__phantomMeshDelta`,
 * новий телефон) або повним перечитуванням (`__phantomMeshChanged`, запас).
 *
 * ЩО міряється — два числа на п'ятнадцять листів:
 *   - `робота`  — Script + Layout + RecalcStyle головної нитки, знятий CDP
 *                 `Performance.getMetrics` до і після пачки, поділений на
 *                 кількість листів. Це ціна листа для процесора, незалежна від
 *                 того, скільки ми чекали.
 *   - `до кадру` — від виклику моста до кадру, в якому бульбашка вже в DOM
 *                 (MutationObserver + rAF). Включає вікно згладжування
 *                 дельти (50 мс) — воно стале і в «робота» не входить.
 *
 * Процесор пригальмовано вчетверо (`Emulation.setCPUThrottlingRate`): так
 * десктопний Chromium приблизно стає A52.
 *
 * Запуск (бандл телефона мусить бути зібраний):
 *   npm run build:phone
 *   PHANTOM_WEB_BASE=/webmsgr/ npx playwright test e2e/phone-feed-inbound-perf.spec.ts --project=phone
 */

const W = 393;
const H = 851;
const BASE = process.env.PHANTOM_WEB_BASE ?? '/';
const N = Number(process.env.PERF_N ?? 600);
const THROTTLE = Number(process.env.PERF_THROTTLE ?? 4);
const ROUNDS = 15;

/** Стеля роботи на один вхідний лист при 4× гальмі, мс. */
const CEILING_WORK_MS = 50;

test.use({
  viewport: { width: W, height: H },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 2.75,
  locale: 'uk-UA',
});

const now = new Date().toISOString();

const CONVS = [
  {
    id: 'c1', title: 'Марта', kind: 'direct', circle: 'friends', handle: null,
    avatar: null, pinned: false, archived: false, created_at: now, updated_at: now,
    contact_id: null, is_demo: false, contact_verified: true, peer_node_id: 'peer_c1',
    unread_count: 0, last_kind: 'text', last_snippet: 'Останнє повідомлення',
    last_author: 'Марта', last_at: now,
  },
];

const BODIES = [
  'Привіт! Ти вже на місці?',
  'Так, чекаю біля входу.',
  'Іду, пʼять хвилин',
  'Ок',
  'Домовились 👍',
  'Довгий рядок тексту, щоб у бульбашці справді був перенос слів і браузер рахував розклад, а не брав його з кешу однакових вузлів.',
  'https://example.org/дуже/довге/посилання/яке/не/має/розтягувати/сторінку',
  'Ще один абзац, трохи інакший за довжиною від попереднього, аби висоти рядків не збіглися.',
];

const row = (i: number, total: number) => ({
  id: `mc1_${i}`,
  conversation_id: 'c1',
  client_id: `clc1_${i}`,
  seq: i,
  author_id: i % 2 === 0 ? 'abc123' : 'peer_c1',
  author_name: i % 2 === 0 ? 'Кирило' : 'Марта',
  kind: 'text',
  body: `${i + 1}. ${BODIES[i % BODIES.length]}`,
  ciphertext: null,
  transport: i % 2 === 0 ? 'p2p' : 'direct',
  reply_to_id: null,
  sent_at: new Date(Date.now() - (total - i) * 60000).toISOString(),
  edited_at: null,
  deleted_at: null,
  delivery: 'sent',
});

const MSGS = Array.from({ length: N }, (_, i) => row(i, N));

/**
 * Міст із ЖИВИМ журналом: `window.__inbound(text)` кладе новий чужий рядок,
 * і наступні GET (повний і `?changed=1`) його бачать — як справжній телефон.
 */
const bridgeInit = ({ CONVS, MSGS }: { CONVS: unknown[]; MSGS: Array<Record<string, unknown>> }) => {
  const journal = [...MSGS];
  let fresh: Array<Record<string, unknown>> = [];
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  const w = window as unknown as Record<string, unknown>;
  w.__inbound = (text: string) => {
    const seq = journal.length;
    const r = {
      ...journal[journal.length - 1],
      id: `in_${seq}`,
      client_id: `in_${seq}`,
      seq,
      author_id: 'peer_c1',
      author_name: 'Марта',
      body: text,
      transport: 'direct',
      sent_at: new Date().toISOString(),
    };
    journal.push(r);
    fresh.push(r);
  };
  w.PhantomBridge = {
    call: (m: string, path: string, body: string | null) => {
      const [q, query = ''] = path.split('?');
      if (q === '/messenger/identity')
        return ok({ node_id: 'abc123', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
      if (q === '/messenger/conversations' && query.startsWith('ids=')) return ok({ rows: CONVS, gone: [] });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      if (/^\/messenger\/conversations\/[^/]+\/messages$/.test(q)) {
        if (m === 'GET' && query.includes('changed=1')) {
          const upsert = fresh;
          fresh = [];
          return ok({ upsert, remove: [] });
        }
        if (m === 'GET') {
          const after = Number(/after_seq=(\d+)/.exec(query)?.[1] ?? 0);
          fresh = [];
          return ok(after > 0 ? journal.filter((r) => Number(r.seq) > after) : journal);
        }
        return ok({ ...JSON.parse(body || '{}'), id: `new_${Date.now()}`, seq: 99999, sent_at: new Date().toISOString(), conversation_id: 'c1', delivery: 'queued' });
      }
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

const say = (line: string) => {
  // eslint-disable-next-line no-console
  console.log(`PERF ${line}`);
};

async function openLongChat(page: Page) {
  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.locator('text=Марта').first().waitFor({ timeout: 20_000 });
  await page.locator('text=Марта').first().click({ force: true });
  await page.locator('[data-testid="messages-scroller"]').waitFor({ timeout: 20_000 });
  await page.locator(`text=${N}. `).first().waitFor({ timeout: 20_000 });
  await page.waitForTimeout(800);
}

const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
};

async function busyMs(cdp: CDPSession): Promise<number> {
  const { metrics } = await cdp.send('Performance.getMetrics');
  const get = (n: string) => metrics.find((x) => x.name === n)?.value ?? 0;
  return (get('ScriptDuration') + get('LayoutDuration') + get('RecalcStyleDuration')) * 1000;
}

/**
 * Пачка вхідних листів — ОДНИМ викликом у сторінці. Кожен лист: смикнути міст
 * і дочекатися кадру, в якому бульбашка вже є; між листами — пауза, щоб
 * доробились ефекти після кадру (прокрутка вниз, переміри).
 *
 * Чому одним викликом: кожен `page.evaluate` Playwright серіалізує своїм
 * скриптом у тій самій нитці, і в профілі цей скрипт (`visitNode`) їв
 * ~20 мс на лист — тобто прилад міряв сам себе. Тепер його ціна одна на
 * всю пачку і в «роботу» на лист практично не входить.
 */
async function inboundBatch(page: Page, rounds: number, via: 'delta' | 'full', tag: string): Promise<number[]> {
  return page.evaluate(
    async ({ rounds, via, tag }) => {
      const w = window as unknown as Record<string, (...a: unknown[]) => void>;
      const scroller = document.querySelector('[data-testid="messages-scroller"]')!;
      const walls: number[] = [];
      for (let k = 0; k < rounds; k++) {
        const text = `вхідний ${via} ${tag}${k}.`;
        const wall = await new Promise<number>((resolve) => {
          const t0 = performance.now();
          // Дивимось лише на ДОДАНЕ: читати textContent усієї стрічки на кожну
          // мутацію означало б міряти сам прилад, і тим дорожче, чим більше DOM.
          const mo = new MutationObserver((records) => {
            const hit = records.some((r) =>
              r.type === 'characterData'
                ? (r.target.textContent || '').includes(text)
                : Array.from(r.addedNodes).some((n) => (n.textContent || '').includes(text)),
            );
            if (!hit) return;
            mo.disconnect();
            requestAnimationFrame(() => resolve(performance.now() - t0));
          });
          mo.observe(scroller, { childList: true, subtree: true, characterData: true });
          w.__inbound(text);
          if (via === 'delta') w.__phantomMeshDelta({ convs: ['c1'] });
          else w.__phantomMeshChanged();
        });
        walls.push(wall);
        await new Promise((r) => setTimeout(r, 250));
      }
      return walls;
    },
    { rounds, via, tag },
  );
}

for (const via of ['delta', 'full'] as const) {
  test(`INBOUND (${via}): один вхідний лист у розмові на ${N} листів, CPU ×${THROTTLE}`, async ({ page }) => {
    test.setTimeout(240_000);
    await openLongChat(page);
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Performance.enable');
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: THROTTLE });

    // Прогрів: перші листи платять за холодний код.
    await inboundBatch(page, 2, via, 'warm');

    // PERF_PROFILE=1 — ще й профіль процесора за всі листи: хто саме їсть час.
    if (process.env.PERF_PROFILE) {
      await cdp.send('Profiler.enable');
      await cdp.send('Profiler.start');
    }
    const before = await busyMs(cdp);
    const walls = await inboundBatch(page, ROUNDS, via, 'r');
    const work = ((await busyMs(cdp)) - before) / ROUNDS;
    if (process.env.PERF_PROFILE) {
      const { profile } = await cdp.send('Profiler.stop');
      const self = new Map<string, number>();
      const byId = new Map(profile.nodes.map((n) => [n.id, n]));
      const dt = profile.timeDeltas ?? [];
      (profile.samples ?? []).forEach((id, i) => {
        const n = byId.get(id);
        if (!n) return;
        const f = n.callFrame;
        const name = `${f.functionName || '(anon)'} ${f.url.split('/').pop()}:${f.lineNumber}:${f.columnNumber}`;
        self.set(name, (self.get(name) ?? 0) + (dt[i] ?? 0) / 1000);
      });
      const top = [...self.entries()].filter(([n]) => !n.startsWith('(idle)') && !n.startsWith('(program)')).sort((a, b) => b[1] - a[1]).slice(0, 25);
      say(`PROFILE ${via}:\n` + top.map(([n, ms]) => `  ${ms.toFixed(1)}мс  ${n}`).join('\n'));
    }
    const nodes = await page
      .locator('[data-testid="messages-scroller"]')
      .evaluate((el) => el.querySelectorAll('*').length);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });

    const r1 = (x: number) => Math.round(x * 10) / 10;
    say(
      `INBOUND ${via} N=${N} CPU×${THROTTLE}: робота на лист (середня)=${r1(work)}мс · ` +
        `до кадру медіана=${r1(median(walls))}мс [${walls.map(r1).join(', ')}] · вузлів у стрічці=${nodes}`,
    );

    // Стеля — лише на вимогу (PERF_ENFORCE=1): на спільній машині розкид
    // між прогонами більший за саму різницю, і ворота тут були б лотереєю.
    if (process.env.PERF_ENFORCE === '1') {
      expect(
        work,
        `Один вхідний лист коштує ${r1(work)} мс роботи головної нитки при CPU ×${THROTTLE} ` +
          `проти стелі ${CEILING_WORK_MS} мс.`,
      ).toBeLessThanOrEqual(CEILING_WORK_MS);
    }
  });
}

/**
 * Власний лист: від натискання Enter до кадру, в якому бульбашка вже в
 * стрічці. Бульбашка мусить з'явитися ОДРАЗУ, а дорога (сховище, міст)
 * — після, статусом на ній.
 */
test(`SEND: Enter → бульбашка у розмові на ${N} листів, CPU ×${THROTTLE}`, async ({ page }) => {
  test.setTimeout(240_000);
  await openLongChat(page);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: THROTTLE });
  const input = page.locator('[data-composer-input]');

  const walls: number[] = [];
  for (let k = 0; k <= ROUNDS; k++) {
    const text = `свій лист ${k}${'·'.repeat(k % 4)}.`;
    await input.fill(text);
    await page.evaluate((text) => {
      const w = window as unknown as Record<string, unknown>;
      const scroller = document.querySelector('[data-testid="messages-scroller"]')!;
      w.__sendProbe = new Promise<number>((resolve) => {
        let t0 = 0;
        const onKey = (e: KeyboardEvent) => {
          if (e.key === 'Enter') t0 = performance.now();
        };
        window.addEventListener('keydown', onKey, { capture: true, once: true });
        const mo = new MutationObserver((records) => {
          const hit = records.some((r) =>
            Array.from(r.addedNodes).some((n) => (n.textContent || '').includes(text)),
          );
          if (!hit || !t0) return;
          mo.disconnect();
          requestAnimationFrame(() => resolve(performance.now() - t0));
        });
        mo.observe(scroller, { childList: true, subtree: true });
      });
    }, text);
    await input.press('Enter');
    const wall = (await page.evaluate(() => (window as unknown as { __sendProbe: Promise<number> }).__sendProbe)) as number;
    if (k > 0) walls.push(wall);
    await page.waitForTimeout(250);
  }
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  const r1 = (x: number) => Math.round(x * 10) / 10;
  say(`SEND N=${N} CPU×${THROTTLE}: Enter→кадр медіана=${r1(median(walls))}мс [${walls.map(r1).join(', ')}]`);
  expect(walls.length).toBe(ROUNDS);
});
