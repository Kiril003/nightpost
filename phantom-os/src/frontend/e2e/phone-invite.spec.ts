import { test, expect, type Page } from '@playwright/test';

/**
 * «Ваше запрошення» на телефонному стенді: адреса — з моста, не з origin.
 *
 * Дефект зі скла 04.09 02:02 (A52, aee02764): поле «Адреса вашого вузла»
 * показувало https://phantom.local — вигаданий origin перехоплювача WebView —
 * з підписом «Підставили ту, за якою ви самі відкрили цю сторінку», а рядок
 * PH2 поруч ніс 158.196.227.74. Веб брав location.origin за адресу вузла;
 * хост уже робив це правильно: compact/link/address у /messenger/identity.
 *
 * Стенд подає справжній dist-phone з http://127.0.0.1:4174 — origin тут
 * відомий і не збігається з жодною адресою в заглушці моста. Візьме картка
 * origin знову — на екрані стане «4174», і сторож його назве. Це вимір на
 * поданому бандлі, а не на коді: у phone-збірці гілка ПК вирізана за
 * __PHONE_BUILD__, і саме це тут доводиться.
 *
 * Червоніти вміє (мутація 04.09): поверніть `selfAddressGuess()` у phone-гілку
 * InviteCard — падають усі три випадки.
 *
 * Запуск: npm run e2e:phone
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

const COMPACT = 'PH2:ZTc2:483778';
const LINK = `phantom://invite/${COMPACT}`;
const ADDRESS = '203.0.113.7:8443';

/**
 * Міст телефона: ті самі шляхи, що в APK. Ідентичність — рівно те, що
 * передали; `address: undefined` означає «поля у відповіді немає».
 */
const bridgeInit = ({ identity }: { identity: Record<string, unknown> }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }
  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (_m: string, path: string) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') return ok(identity);
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok([]);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      return nf;
    },
  };
};

const identity = (extra: Record<string, unknown>) => ({
  node_id: 'abc123',
  bundle: { name: 'PHANTOM-A52', host: '', port: 0 },
  compact: COMPACT,
  link: LINK,
  fingerprint: '123456789012345678901234',
  ...extra,
});

/** Відкриває «Створити новий діалог» — на телефоні картка запрошення там перша. */
const openInvite = async (page: Page, extra: Record<string, unknown>) => {
  await page.addInitScript(bridgeInit, { identity: identity(extra) });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(1500);
  await page.locator('[aria-label="Створити новий діалог"]').first().click({ force: true });
  const card = page.locator('[data-testid="invite-card"]');
  await expect(card).toBeVisible();
  await expect(card.locator('[data-testid="invite-string"]')).toBeVisible();
  return card;
};

/** Origin стенда — те, чого на екрані бути не може, і phantom.local — теж. */
const assertNoOrigin = async (page: Page) => {
  const host = new URL(page.url()).host;
  const text = await page.evaluate(() => document.body.innerText);
  expect(host, 'стенд без хоста — вимір ні про що').toMatch(/:4174$/);
  expect(text, `origin стенда ${host} на екрані — картка знову бере location.origin`).not.toContain(host);
  expect(text).not.toMatch(/localhost:4174/);
  expect(text).not.toMatch(/phantom\.local/);
  expect(text, 'підпис ПК про origin їде в телефон').not.toMatch(/Підставили ту, за якою ви самі відкрили/);
};

test('адреса є → на екран не виходить (лише в рядку); рядок — link моста', async ({ page }) => {
  const card = await openInvite(page, { address: ADDRESS });
  await expect(card.locator('[data-testid="invite-address"]')).toHaveCount(0);
  await expect(card).not.toContainText(ADDRESS);
  await expect(card.locator('[data-testid="invite-reach"]')).toBeVisible();
  await expect(card.locator('[data-testid="invite-string"]')).toHaveValue(LINK);
  await expect(card.locator('[data-testid="invite-name"]')).toHaveText('PHANTOM-A52');
  await assertNoOrigin(page);
});

test('address: null → «Прямої дороги до телефона немає…»', async ({ page }) => {
  const card = await openInvite(page, { address: null });
  await expect(card.locator('[data-testid="invite-reach"]')).toContainText('Прямої дороги до телефона немає');
  await expect(card.locator('[data-testid="invite-address"]')).toHaveCount(0);
  await expect(card.locator('[data-testid="invite-string"]')).toHaveValue(LINK);
  await assertNoOrigin(page);
});

test('поля address немає (стара збірка хоста) → те саме речення', async ({ page }) => {
  const card = await openInvite(page, {});
  await expect(card.locator('[data-testid="invite-reach"]')).toContainText('Прямої дороги');
  await expect(card.locator('[data-testid="invite-address"]')).toHaveCount(0);
  await expect(card.locator('[data-testid="invite-string"]')).toHaveValue(LINK);
  await assertNoOrigin(page);
});
