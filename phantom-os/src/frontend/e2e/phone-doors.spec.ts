import { test, expect, type Page } from '@playwright/test';

/**
 * Мертвих дверей на телефоні немає — доведено натиском, а не читанням коду.
 *
 * Питання штабу було не про вагу, а про скло: у чанках лишилось 32 унікальні
 * імені `onOpen*`, серед них вітринні (onOpenWorkspaceDrive, onOpenTimeMachine,
 * onOpenZeroTrace…). Імʼя пропса в сигнатурі — ще не кнопка: пункт малюється
 * лише коли пропс справді передали. Але «лише коли передали» — це знову
 * читання коду, а натиск, після якого нічого не стається, — найдорожчий клас
 * дефектів у цьому продукті.
 *
 * Тому тут перебираються ВСІ видимі клікабельні елементи на кожній поверхні
 * телефона, кожен натискається на чистій сторінці, і порівнюється відбиток
 * DOM до й після. Не змінилось нічого — двері мертві.
 *
 * Для пунктів МЕНЮ цього замало, і перша версія на цьому й попалась: пункт
 * меню спершу закриває меню, а вже потім робить свою справу. Закриття саме
 * собою міняє екран, тож мертвий пункт виглядав живим — мутація «передати
 * вітринний пропс на телефоні» проходила зеленою. Тому для меню відбиток
 * звіряється не з «до», а з екраном, який лишається, якщо меню просто
 * закрити клавішею Escape. Збіглося з ним — пункт не зробив нічого, крім
 * того, що зачинив за собою двері.
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

// ── Міст телефона: ті самі шляхи, що в APK. ────────────────────────────────
const now = new Date().toISOString();
const conv = (id: string, title: string, kind: string) => ({
  id, title, kind, circle: 'friends', handle: null, avatar: null, pinned: false, archived: false,
  created_at: now, updated_at: now, contact_id: null, is_demo: false, contact_verified: true,
  peer_node_id: `peer_${id}`, unread_count: 0, last_kind: 'text', last_snippet: 'Останнє повідомлення',
  last_author: title, last_at: now,
});
const CONVS = [conv('c1', 'Оксамит', 'direct'), conv('c2', 'Група двору', 'group')];
const msg = (i: number, cid: string, self: boolean, body: string) => ({
  id: `m${cid}_${i}`, conversation_id: cid, client_id: `cl${cid}_${i}`, seq: i,
  author_id: self ? 'abc123' : `peer_${cid}`, author_name: self ? 'Я' : 'Оксамит',
  kind: 'text', body, ciphertext: null, transport: 'direct', reply_to_id: null,
  sent_at: new Date(Date.now() - (10 - i) * 60000).toISOString(),
  edited_at: null, deleted_at: null, delivery: 'sent',
});
const MSGS: Record<string, unknown[]> = {
  c1: [msg(1, 'c1', false, 'Привіт! Ти вже на місці?'), msg(2, 'c1', true, 'Так, чекаю біля входу.')],
  c2: [msg(1, 'c2', false, 'Збори о шостій')],
};

const bridgeInit = ({ CONVS, MSGS }: { CONVS: unknown[]; MSGS: Record<string, unknown[]> }) => {
  const ok = (d: unknown) => JSON.stringify({ ok: true, status: 200, data: d });
  const nf = JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
  // Кожне завантаження — з чистого аркуша.
  //
  // Стор кладе список розмов і активну розмову в localStorage, тож друге
  // завантаження відкривало вже інший екран, ніж перше: меню не відкривалось,
  // і сторож чесно казав «зник» про сім своїх же пунктів. Вимір мусить
  // починатися з того самого місця, інакше він міряє власний слід.
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* приватний режим */ }

  (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
    call: (m: string, path: string, body: string | null) => {
      const q = path.split('?')[0];
      if (q === '/messenger/identity') return ok({ node_id: 'abc123', bundle: { name: 'Я' }, compact: 'PH2:ZTc2:483778' });
      if (q === '/messenger/bootstrap' || q === '/messenger/conversations') return ok(CONVS);
      if (q === '/messenger/contacts') return ok([]);
      if (q === '/messenger/queue/status') return ok({ queued: 0 });
      if (q === '/messenger/roads') return ok({ roads: [], configured: 0, total: 0 });
      const mm = q.match(/^\/messenger\/conversations\/([^/]+)\/messages$/);
      if (mm && m === 'GET') return ok(MSGS[mm[1]] || []);
      if (/\/read$/.test(q)) return ok({});
      return nf;
    },
  };
};

/**
 * Відбиток стану екрана.
 *
 * Береться текст, кількість вузлів і кількість шарів — саме те, що змінює
 * будь-яка жива дія: відкрити меню, панель, перейти в розмову, перемкнути
 * стан кнопки. Порожня різниця означає, що натиск не зробив нічого.
 */
type Snapshot = { text: string; nodes: number; layers: number };

const snapshot = (): Snapshot => ({
  text: (document.body.innerText || '').replace(/\s+/g, ' ').trim(),
  nodes: document.querySelectorAll('*').length,
  layers: document.querySelectorAll('[role="dialog"], [role="menu"], .fixed').length,
});

/**
 * Видимі клікабельні елементи: стійкий шлях + підпис, яким їх упізнає людина.
 *
 * Шлях, а не порядковий номер. Перша версія цього сторожа клікала за
 * індексом у списку — і збрехала на «Показати число безпеки»: після
 * перезавантаження асинхронний рендер зсував порядок, натиск ішов у сусідній
 * елемент, а сторож звітував про той, чиє імʼя тримав. Вимір розповідав про
 * себе, а не про продукт.
 */
const listControls = () => {
  // Усе всередині: page.evaluate переносить у сторінку тіло функції, а не
  // модуль, тож помічники з файлу там просто не існують.
  const SEL = 'button, a[href], [role="button"], [role="tab"], [role="menuitem"]';

  /**
   * Стійкий шлях, а не порядковий номер.
   *
   * Перша версія цього сторожа клікала за індексом — і збрехала на «Показати
   * число безпеки»: після перезавантаження асинхронний рендер зсував порядок,
   * натиск ішов у сусідній елемент, а звіт називав той, чиє імʼя тримав.
   * Вимір розповідав про себе, а не про продукт.
   */
  const pathOf = (el: Element): string => {
    const parts: string[] = [];
    let node: Element | null = el;
    while (node && node !== document.body) {
      const parent: Element | null = node.parentElement;
      if (!parent) break;
      const same = Array.from(parent.children).filter((c) => c.tagName === node!.tagName);
      parts.unshift(`${node.tagName.toLowerCase()}:nth-of-type(${same.indexOf(node) + 1})`);
      node = parent;
    }
    return `body > ${parts.join(' > ')}`;
  };

  const visible = (el: Element): boolean => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.pointerEvents === 'none') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.top < window.innerHeight && r.bottom > 0;
  };

  return Array.from(document.querySelectorAll(SEL))
    .filter(visible)
    .map((el) => ({
      path: pathOf(el),
      disabled:
        (el as HTMLButtonElement).disabled === true || el.getAttribute('aria-disabled') === 'true',
      label:
        (el.getAttribute('aria-label') || el.getAttribute('title') || (el.textContent || '').trim())
          .replace(/\s+/g, ' ')
          .slice(0, 44) || '(без підпису)',
    }));
};

/**
 * Поверхні телефона. Кожна — це шлях від чистого завантаження, бо після
 * натиску стан уже інший: щоб міряти чесно, кожен натиск робиться на щойно
 * відкритій сторінці.
 */
type Surface = { name: string; open: (page: Page) => Promise<void> };

const tap = async (page: Page, sel: string) => {
  const l = page.locator(sel).first();
  if (await l.count()) await l.click({ force: true }).catch(() => undefined);
  await page.waitForTimeout(500);
};

const SURFACES: Surface[] = [
  { name: 'список розмов', open: async () => {} },
  { name: 'розмова', open: async (p) => { await tap(p, 'text=Оксамит'); } },
  {
    name: 'меню «Більше дій»',
    // Чекаємо, доки меню справді відкрите: інакше перебір міряв би екран
    // із закритим меню й чесно казав «зник» про кожен його пункт.
    open: async (p) => {
      await tap(p, 'text=Оксамит');
      await tap(p, '[title="Більше дій"]');
      await p.getByText('Показати число безпеки').first().waitFor({ state: 'visible', timeout: 5000 });
    },
  },
  {
    name: 'меню вкладень',
    open: async (p) => { await tap(p, 'text=Оксамит'); await tap(p, '[title="Додати вкладення"]'); },
  },
  { name: 'нова розмова', open: async (p) => { await tap(p, '[aria-label="Створити новий діалог"]'); } },
];

/**
 * Контроли, яким дозволено не міняти екран — з причиною на кожен.
 * Причина мусить називати, ЩО саме робить натиск, якщо не малює.
 */
const ALLOWED_SILENT: Array<{ label: RegExp; why: string }> = [
  { label: /Копіювати|Скопіювати/i, why: 'кладе рядок у буфер — екран і не мусить змінюватись' },
  { label: /Поділитися|Надіслати запрошення/i, why: 'віддає рядок системному діалогу апарата, поза DOM сторінки' },
  {
    label: /^(Всі|Чати|P2P Ключ)$/,
    why:
      'уже обраний фільтр або вкладка: повторний натиск підтверджує те саме — ' +
      'сусіди по тому ж рядку («Особисті», «Групи», «Канали») змінюють екран, ' +
      'тож рядок живий, а тиша тут означає «ви вже тут»',
  },
  {
    label: /^(Закрити|Більше дій)$/,
    why:
      'зачинити меню — і є вся його справа. Для пунктів меню відбиток '
      + 'звіряється з «просто закрито», тож у цих двох він збігається за побудовою',
  },
  {
    label: /^Звук (увімкнено|вимкнено)$/,
    why:
      'перемикач: міняє стан і закриває меню, а нову назву показує аж при '
      + 'наступному відкритті — на цьому екрані йому нема чого малювати',
  },
  {
    label: /^(Фото|Файл)$/,
    why:
      'смикає прихований <input type="file"> (composer-photo-input / '
      + 'composer-file-input) — вибірник апарата живе поза DOM сторінки, тож '
      + 'екран і не мусить змінюватись. Самі поля перевірено окремо: вони на місці',
  },
  {
    label: /^(Простір|Створити новий діалог)$/,
    why:
      'дія над порожнім полем: простір без назви й модалка, яка вже відкрита. ' +
      'Кнопка не бреше — вона чекає на ввід, і з уведеною назвою екран змінюється',
  },
];

test('на телефоні немає клікабельного елемента, який не робить нічого', async ({ page }) => {
  // Перебір із перезавантаженням на кожен натиск — це хвилини, а не секунди.
  // Тридцять секунд стелі означали б, що сторож не встигає подивитись.
  test.setTimeout(1_800_000);

  await page.addInitScript(bridgeInit, { CONVS, MSGS });
  const boot = async () => {
    await page.goto(BASE, { waitUntil: 'load' });
    await page.waitForTimeout(1800);
  };

  const dead: string[] = [];
  const notMeasured: string[] = [];
  const report: string[] = [];

  // Звузити перебір однією поверхнею: PHANTOM_DOORS_ONLY='меню'. Потрібно,
  // щоб доводити мутацію за півтори хвилини, а не за шість.
  const only = process.env.PHANTOM_DOORS_ONLY;
  const surfaces = only ? SURFACES.filter((s) => s.name.includes(only)) : SURFACES;
  expect(surfaces.length, 'жодної поверхні не обрано — сканувати нема чого').toBeGreaterThan(0);

  for (const surface of surfaces) {
    await boot();
    await surface.open(page);
    const controls = await page.evaluate(listControls);
    report.push(`\n── ${surface.name}: ${controls.length} клікабельних`);

    // Для меню — екран, який лишається від самого лише закриття.
    const isMenu = surface.name.startsWith('меню');
    let closedBaseline: Snapshot | null = null;
    if (isMenu) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(600);
      closedBaseline = await page.evaluate(snapshot);
    }

    for (const ctrl of controls) {
      // Вимкнений контрол не обіцяє дії: він показує, що зараз не можна.
      if (ctrl.disabled) {
        report.push(`   – «${ctrl.label}» вимкнений — обіцянки немає`);
        continue;
      }
      // Кожен натиск — на щойно відкритій сторінці: інакше міряємо наслідок
      // попереднього кліку, а не цього.
      await boot();
      await surface.open(page);
      const before = await page.evaluate(snapshot);
      // Натискаємо саме той елемент, про який звітуємо: шукаємо за шляхом і
      // звіряємо підпис. Не збіглось — не міряємо, а кажемо про це.
      const clicked = await page.evaluate(
        ({ path, label }) => {
          const el = document.querySelector(path) as HTMLElement | null;
          if (!el) return 'зник';
          const got =
            (el.getAttribute('aria-label') || el.getAttribute('title') || (el.textContent || '').trim())
              .replace(/\s+/g, ' ')
              .slice(0, 44) || '(без підпису)';
          if (got !== label) return `інший елемент: «${got}»`;
          el.click();
          return 'ok';
        },
        { path: ctrl.path, label: ctrl.label },
      );
      if (clicked !== 'ok') {
        // Шлях не зійшовся — меню малюється порталом, і після перезавантаження
        // його місце в body інше. Тоді шукаємо той самий елемент за підписом:
        // «не переміряно» лишати не можна, бо саме ці пункти й питав штаб.
        const byLabel = await page.evaluate((label) => {
          const SEL = 'button, a[href], [role="button"], [role="tab"], [role="menuitem"]';
          const hit = Array.from(document.querySelectorAll(SEL)).find((el) => {
            const cs = getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden' || cs.pointerEvents === 'none') return false;
            const r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) return false;
            const got =
              (el.getAttribute('aria-label') || el.getAttribute('title') || (el.textContent || '').trim())
                .replace(/\s+/g, ' ')
                .slice(0, 44) || '(без підпису)';
            return got === label;
          }) as HTMLElement | undefined;
          if (!hit) return false;
          hit.click();
          return true;
        }, ctrl.label);
        if (!byLabel) {
          report.push(`   ? «${ctrl.label}» не переміряно — ${clicked}`);
          notMeasured.push(`${surface.name} → «${ctrl.label}»`);
          continue;
        }
      }
      await page.waitForTimeout(600);
      const after = await page.evaluate(snapshot);

      const differs = (a: Snapshot, b: Snapshot) =>
        a.text !== b.text || a.nodes !== b.nodes || a.layers !== b.layers;
      // Пункт меню мусить дати екран, ІНШИЙ за просто закрите меню.
      const moved = closedBaseline ? differs(after, closedBaseline) : differs(before, after);
      const excused = ALLOWED_SILENT.find((a) => a.label.test(ctrl.label));
      report.push(
        `   ${moved ? '·' : excused ? '~' : '✗'} «${ctrl.label}» ${
          moved ? 'веде в живе' : excused ? `тиха дія: ${excused.why}` : 'НІЧОГО НЕ РОБИТЬ'
        }`,
      );
      if (!moved && !excused) dead.push(`${surface.name} → «${ctrl.label}»`);
    }
  }

  console.log(report.join('\n'));
  // Непереміряне — це не «зелене»: сторож, який мовчки пропускає контрол,
  // зелений про те, чого не бачив. Саме так промазав попередній.
  expect(notMeasured, 'контрол, до якого сторож не дотягнувся').toEqual([]);
  expect(dead, 'клікабельне, після якого на екрані не змінюється нічого').toEqual([]);
});
