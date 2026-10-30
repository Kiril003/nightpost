import { test, expect, type Page } from '@playwright/test';

/**
 * Хребет на телефоні: СПИСОК → ПРОСТІР → ТОПІК → ГІЛКА, і назад.
 *
 * Власник, дослівно: «гілки (яких я, до речі, як і топіків, не наблюдаю у
 * застосунку)». Виміряно 03.09 до правки: зі списку до просторів дверей 0,
 * з листа до гілки дверей 0 — обидві сутності були написані в сторі й не
 * викликані жодним кадром. Цей файл — сторож ланцюга: він падає не тоді,
 * коли щось негарно, а тоді, коли до рівня знову не дійти пальцем.
 *
 * Запуск: npm run build && npx playwright test e2e/phone-spine.spec.ts
 */

const W = 393;
const H = 852;
const BASE = process.env.PHANTOM_WEB_BASE ?? '/';

test.use({
  viewport: { width: W, height: H },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 2.75,
  locale: 'uk-UA',
});

// ── Міст телефона: ті самі шляхи, що в APK. relay|ice|call|files|directory → 404.
const now = new Date().toISOString();
const conv = (id: string, title: string, kind: string) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false, archived: false,
  created_at: now, updated_at: now, contact_id: null, is_demo: false, contact_verified: true,
  peer_node_id: `peer_${id}`, unread_count: 0, last_kind: 'text', last_snippet: 'Останнє повідомлення',
  last_author: title, last_at: now,
});
const CONVS = [conv('c1', 'Марта', 'direct'), conv('c2', 'Група двору', 'group')];
const msg = (i: number, cid: string, self: boolean, body: string) => ({
  id: `m${cid}_${i}`, conversation_id: cid, client_id: `cl${cid}_${i}`, seq: i,
  author_id: self ? 'abc123' : `peer_${cid}`, author_name: self ? 'Кирило' : 'Марта',
  kind: 'text', body, ciphertext: null, transport: 'direct', reply_to_id: null,
  sent_at: new Date(Date.now() - (10 - i) * 60000).toISOString(), edited_at: null, deleted_at: null, delivery: 'sent',
});
const MSGS: Record<string, unknown[]> = {
  c1: [msg(1, 'c1', false, 'Привіт! Ти вже на місці?'), msg(2, 'c1', true, 'Так, чекаю біля входу.')],
  c2: [msg(1, 'c2', false, 'Збори о шостій')],
};

const bridgeInit = ({ CONVS, MSGS }: { CONVS: unknown[]; MSGS: Record<string, unknown[]> }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (m: string, path: string, body: string | null) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') return ok({ node_id: 'abc123', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778', link: 'phantom://invite/PH2:ZTc2:483778' });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm) {
        if (m === 'GET') return ok(MSGS[mm[1]] || []);
        return ok({ ...JSON.parse(body || '{}'), id: `new_${Date.now()}`, seq: 99, sent_at: new Date().toISOString(), conversation_id: mm[1], delivery: 'queued' });
      }
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

const boot = async (page: Page) => {
  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);
};

/** Дрібні цілі й вилізле за 393px — той самий вимір, що в phone-fitness. */
const fitness = (W: number) =>
  ({
    small: Array.from(
      document.querySelectorAll('button, a[href], [role="button"], input, select, textarea, [role="tab"], [role="menuitem"]'),
    )
      .filter((el) => {
        const cs = getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden' || cs.pointerEvents === 'none') return false;
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) return false;
        return r.width < 47.5 || r.height < 47.5;
      })
      .map((el) => {
        const r = el.getBoundingClientRect();
        return `${el.tagName.toLowerCase()} ${Math.round(r.width)}×${Math.round(r.height)} «${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 32)}»`;
      }),
    scrollWidth: document.documentElement.scrollWidth,
    maxRight: Math.max(
      0,
      ...Array.from(document.body.querySelectorAll('*'))
        .filter((el) => {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) return false;
          for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
            if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= W + 0.5) return false;
          }
          return true;
        })
        .map((el) => Math.round(el.getBoundingClientRect().right)),
    ),
  });

test('ланцюг: гілка відкривається з листа на весь екран і закривається в розмову', async ({ page }) => {
  await boot(page);

  await page.locator('text=Марта').first().click({ force: true });
  await page.waitForTimeout(1200);

  const bubble = page.locator('text=Привіт! Ти вже на місці?').first();
  await expect(bubble).toBeVisible();
  await bubble.dispatchEvent('contextmenu', { clientX: 180, clientY: 300 });
  await page.waitForTimeout(700);

  const threadDoor = page.getByRole('button', { name: 'Гілка', exact: true });
  await expect(threadDoor, 'з листа до гілки дверей нема').toBeVisible();
  await threadDoor.click();

  const thread = page.locator('[data-testid="thread-view"]');
  await expect(thread).toBeVisible();
  // Гілка — на весь екран телефона, а не колонка збоку.
  const box = await thread.boundingBox();
  expect(box?.width).toBeGreaterThanOrEqual(W - 1);
  await expect(thread).toContainText('Привіт! Ти вже на місці?');
  await expect(thread).toContainText('У гілці ще нема відповідей');

  const f = await page.evaluate(fitness, W);
  expect(f.small, `У гілці дрібні цілі: ${f.small.join(', ')}`).toEqual([]);
  expect(f.scrollWidth, 'гілка гортається вбік').toBeLessThanOrEqual(W);
  expect(f.maxRight, 'щось у гілці вилазить за правий край').toBeLessThanOrEqual(W);

  // Написане в гілці йде в розмову відповіддю на корінь — окремого сховища нема.
  await page.fill('[aria-label="Відповісти в гілці"]', 'Буду за пʼять хвилин');
  await page.locator('[aria-label="Надіслати в гілку"]').click();
  await page.waitForTimeout(800);
  await expect(thread).toContainText('Буду за пʼять хвилин');

  await page.locator('[aria-label="Назад у розмову"]').click();
  await page.waitForTimeout(400);
  await expect(thread).toHaveCount(0);
  await expect(page.locator('[title="Записати голосове повідомлення"]')).toBeVisible();
});

test('на телефоні не рендериться те, чого міст не везе, і десктопні панелі', async ({ page }) => {
  await boot(page);
  await page.locator('text=Марта').first().click({ force: true });
  await page.waitForTimeout(1200);

  // Дзвінки: міст віддає 404 на relay|ice|call — кнопок нема взагалі.
  await expect(page.locator('[data-call-start]')).toHaveCount(0);
  await expect(page.locator('[aria-label="Аудіодзвінок"]')).toHaveCount(0);
  await expect(page.locator('[aria-label="Відеодзвінок"]')).toHaveCount(0);

  // Меню листа: «До Canvas» веде у спліт на дві колонки, якого на 393px нема.
  const bubble = page.locator('text=Привіт! Ти вже на місці?').first();
  await bubble.dispatchEvent('contextmenu', { clientX: 180, clientY: 300 });
  await page.waitForTimeout(700);
  await expect(page.getByRole('button', { name: 'До Canvas' })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // Меню розмови: сімнадцять десктопних панелей Work OS тут не потрібні.
  await page.locator('[title="Більше дій"]').first().click();
  await page.waitForTimeout(700);
  for (const dead of [
    'Сховище Workspace Drive',
    'Контекстні ролі (Scopes)',
    'Вхідні Webhook-хаби (CI/CD)',
    'Shared Terminal & Runner',
    'Project Memory Graph',
    'Реляційна база (Data Grid)',
    'CRDT Time Machine (Снапшоти)',
    'Physical Computing, GIS & WebGPU',
  ]) {
    await expect(page.getByRole('menu').getByText(dead, { exact: true })).toHaveCount(0);
  }
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // Каталог «всі користувачі»: сервера з чужими контактами нема за побудовою.
  await page.locator('[aria-label="Назад"]').first().click();
  await page.waitForTimeout(500);
  await page.locator('[aria-label="Створити новий діалог"]').click();
  await page.waitForTimeout(700);
  await expect(page.getByText('Користувачі вузла', { exact: false })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Мережа' })).toHaveCount(0);
  // Групи міст не везе. Вкладки нема, і на її місці — речення, а не тиша.
  await expect(page.getByRole('button', { name: 'Група' })).toHaveCount(0);
  await expect(page.getByText('Групи на телефоні ще не їдуть')).toBeVisible();
});

/**
 * Відмова довідника на склі: 503 з причиною, а не намальована розмова.
 *
 * До правки «Написати» на цю саму відмову закривало модалку й відкривало
 * розмову з @marta — з зеленою крапкою й без ключа. Тут перевірено обидва
 * боки: речення моста стоїть у модалці, а модалка нікуди не поділась.
 */
const closedDirectoryBridge = ({ CONVS, MSGS }: { CONVS: unknown[]; MSGS: Record<string, unknown[]> }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (m: string, path: string, body: string | null) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') return ok({ node_id: 'abc123', bundle: { name: 'Кирило' }, compact: 'PH2:ZTc2:483778' });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      // Людина в довіднику є…
      if (q === '/messenger/directory/users') {
        return ok([{ id: 'u1', username: 'marta', display_name: 'Марта', compact: 'PH2:ZTc2:483778' }]);
      }
      // …а відкрити розмову довідник відмовився, і сказав чому.
      if (q === '/messenger/directory/start-chat') {
        return JSON.stringify({
          ok: false, status: 503, code: 'directory_closed',
          detail: 'Довідник ще не відкрито на цьому сервері',
        });
      }
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm && m === 'GET') return ok(MSGS[mm[1]] || []);
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

test('довідник відмовив — речення моста в модалці, і жодної нової розмови', async ({ page }) => {
  await page.addInitScript(closedDirectoryBridge, { CONVS, MSGS });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);

  await page.locator('[aria-label="Створити новий діалог"]').click();
  await page.waitForTimeout(500);
  // exact: інакше сюди ж влучає кнопка «Створити новий діалог» у шапці.
  await page.getByRole('button', { name: 'Діалог', exact: true }).click();
  await page.fill('input[placeholder="@ім’я"]', '@marta');
  await page.getByRole('button', { name: 'Знайти' }).click();
  await expect(page.getByRole('button', { name: 'Написати' })).toBeVisible();

  await page.getByRole('button', { name: 'Написати' }).click();
  await page.waitForTimeout(600);

  // Слово моста — дослівно.
  await expect(page.getByText('Довідник ще не відкрито на цьому сервері')).toBeVisible();
  // Модалка на місці: раніше вона закривалась і відкривала вигадану розмову.
  await expect(page.getByRole('button', { name: 'Знайти' })).toBeVisible();
  // І в списку не зʼявилось рядка «Marta» — саме так звався вигаданий чат:
  // латиницею, з великої, бо його ім'я бралося з набраного рядка.
  await expect(page.getByText('Marta', { exact: true })).toHaveCount(0);
});
