/**
 * Контроль, що відгукується і не робить нічого — найдорожчий клас дефектів
 * у цьому продукті. Тут по одному тесту на кожен випадок, який знайшли
 * пальцем на пристрої.
 *
 * 1. OPERATOR не мав кейса у StateSurface. Це не тільки плитка «Агент»:
 *    ядро само шле цей стан у WS, коли стартує передній план агента
 *    (agent/kernel/runtime.py:738, :878). Плашка ставала «Оператор»,
 *    а під нею лишалась головна.
 * 2. «Створити профіль» вів на /onboarding — маршрут під автентифікацією,
 *    а бачить той екран лише неавтентифікований. Клік = коло.
 * 3. /analytics існував без жодних дверей: рядок трапляється в усьому
 *    фронтенді один раз — у самому визначенні маршруту.
 *
 * ─── Правка 04.09 ────────────────────────────────────────────────────────
 * Група 1 описувала світ, якого продукт більше не має. StateSurface тоді
 * розводила сім станів по семи екранах, і дірка в тому switch лишала
 * OPERATOR без поверхні. Відтоді фронт звівся до месенджера:
 * `StateSurface` (app/App.tsx) безумовно повертає <MessengerLayout />, а
 * стан лише фарбує акцент і кладеться в `data-state` на <body>
 * (app/StateTransitionController.tsx). Жодного switch — отже, жодної дірки.
 *
 * Тести групи 1 переписані під цю правду, і вони сильніші за попередні:
 * замість «OPERATOR теж має екран» вони тримають «ЖОДЕН стан не лишає
 * поверхню порожньою», перебираючи всі сім значень SystemState. Саме це і
 * є та властивість, заради якої писався початковий тест; якщо колись
 * повернуть розведення станів по екранах, вони почервоніють на першому ж
 * стані, який забудуть.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SystemState } from '@shared/types';

const navigateSpy = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<object>('react-router-dom');
  return { ...actual, useNavigate: () => navigateSpy };
});

vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<object>('framer-motion');
  return {
    ...actual,
    AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    motion: new Proxy(
      {},
      {
        get: (_t, key) => (props: Record<string, unknown>) => {
          const { children, ...rest } = props as { children?: React.ReactNode };
          const drop = new Set([
            'whileTap', 'whileHover', 'initial', 'animate', 'exit',
            'transition', 'layout', 'layoutId', 'variants', 'drag',
          ]);
          const cleaned: Record<string, unknown> = {};
          for (const [k, v] of Object.entries(rest)) if (!drop.has(k)) cleaned[k] = v;
          const Tag = key as unknown as keyof JSX.IntrinsicElements;
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          return <Tag {...(cleaned as any)}>{children}</Tag>;
        },
      },
    ),
  };
});

// Єдина поверхня, яку сьогодні малює StateSurface. Підміняємо її, бо
// справжній месенджер тягне за собою мережевий меш і сховища — тут
// перевіряється вибір поверхні, а не її вміст.
vi.mock('../layouts/MessengerLayout', () => ({
  default: () => <div data-testid="surface-messenger">МЕСЕНДЖЕР</div>,
}));

import { StateSurface } from '../app/App';
import { useSystemStore } from '../stores/systemStore';

const renderSurface = () =>
  render(
    <MemoryRouter>
      <Suspense fallback={<div>…</div>}>
        <StateSurface />
      </Suspense>
    </MemoryRouter>,
  );

describe('дефект 1 — OPERATOR був станом без екрана', () => {
  beforeEach(() => {
    useSystemStore.setState({ state: SystemState.SHADOW });
  });

  it('малює поверхню, коли ядро ввело систему в OPERATOR', async () => {
    useSystemStore.setState({ state: SystemState.OPERATOR });
    const { container } = renderSurface();
    expect(await screen.findByTestId('surface-messenger')).toBeTruthy();
    // Саме це й був дефект: плашка мінялась, а під нею лишалась порожнеча.
    expect(container.textContent).toContain('МЕСЕНДЖЕР');
  });

  it('жоден стан не лишає поверхню порожньою', async () => {
    // Дірка в старому switch і була дефектом. Перебір усіх станів ловить
    // будь-яку нову дірку, а не лише ту, що вже знайшли пальцем.
    for (const state of Object.values(SystemState)) {
      useSystemStore.setState({ state });
      const { unmount } = renderSurface();
      expect(await screen.findByTestId('surface-messenger')).toBeTruthy();
      unmount();
    }
  });

  it('лишає SHADOW на тій самій поверхні, що й решту станів', async () => {
    renderSurface();
    expect(await screen.findByTestId('surface-messenger')).toBeTruthy();
  });

  it('більше не має скорочень, що ставлять стан без екрана', () => {
    const keys = Object.keys(useSystemStore.getState());
    // goOperator ставив OPERATOR, який нічим не малювався; goDream/goFocus/
    // goDialogue/goGhost не мали викликів і не вели на «/».
    for (const dead of ['goOperator', 'goDream', 'goFocus', 'goDialogue', 'goGhost']) {
      expect(keys).not.toContain(dead);
    }
    expect(keys).toContain('goShadow');
    expect(keys).toContain('goSentinel');
  });
});

describe('дефект 2 — «Створити профіль» вів по колу', () => {
  it('екран «немає профілів» не пропонує неавтентифікований маршрут', async () => {
    vi.resetModules();
    vi.doMock('../services/api', () => ({
      authApi: { picker: vi.fn().mockResolvedValue([]) },
      ApiError: class ApiError extends Error { status = 0; },
    }));
    const { default: LoginScreen } = await import('../components/auth/LoginScreen');
    const { container } = render(
      <MemoryRouter>
        <LoginScreen />
      </MemoryRouter>,
    );

    await screen.findByText('Жодного профілю не видно');
    expect(container.querySelector('a[href="/onboarding"]')).toBeNull();
    expect(container.querySelector('[href*="onboarding"]')).toBeNull();
    expect(screen.queryByText('Створити профіль')).toBeNull();
  });

  it('натомість називає справжнє джерело PIN і дає повтор', async () => {
    vi.resetModules();
    vi.doMock('../services/api', () => ({
      authApi: { picker: vi.fn().mockResolvedValue([]) },
      ApiError: class ApiError extends Error { status = 0; },
    }));
    const { default: LoginScreen } = await import('../components/auth/LoginScreen');
    render(
      <MemoryRouter>
        <LoginScreen />
      </MemoryRouter>,
    );

    await screen.findByText('Жодного профілю не видно');
    expect(screen.getByText(/bootstrap_pin/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Перевірити ще раз/ })).toBeTruthy();
  });
});

/**
 * ─── Правка 04.09, друга ─────────────────────────────────────────────────
 *
 * Тут стояли два зелені тести: «плитка «Аналітика» веде на /analytics» і
 * «плитка «Агент» веде на /foundry». Обидва перевіряли ФАКТ ВИКЛИКУ
 * `navigate`, а не те, що на тому боці щось є. А там не було нічого:
 * маршрутів `/analytics` і `/foundry` у застосунку не існує (`app/App.tsx`
 * оголошує рівно `/`, `/messenger`, `/chat`), і `path="*"` малює той самий
 * месенджер. Тобто тести доводили, що двері відчиняються, стоячи спиною до
 * того, що за ними стіна — рівно той сторож, якого ми весь день виполюємо.
 *
 * Плитки прибрано (рішення штабу: не робити три екрани за десять днів до
 * бети). Замість двох тестів про виклик — один про досяжність: ЖОДНА плитка
 * не сміє вести на маршрут, якого немає. Мутація, що має червоніти: додати
 * плитці `navigate('/nowhere')` або прибрати шлях зі списку оголошених.
 */
describe('дефект 3 — двері, за якими стіна', () => {
  beforeEach(() => {
    navigateSpy.mockClear();
  });

  /**
   * Відомі борги: переходи на маршрути, яких НЕМАЄ, що лишились поза
   * Додатками.
   *
   * ВАЖЛИВА ПОПРАВКА 04.09, куплена виміром. МЕЖА: усе нижче — про ЦЕ
   * дерево, `phantom-os-messenger/src/frontend`. У склі ПК
   * (`phantom-os-beta/src/frontend`) ті самі імена файлів живі: коренем
   * маршруту там стоїть `DashboardLayout`, а `/map`, `/settings/:categoryId?`
   * і `/polis` — справжні маршрути. Не переносити висновок на «веб».
   *
   * У ЦЬОМУ дереві всі пʼять живуть у компонентах, ЯКИХ НІХТО НЕ МОНТУЄ, —
   * тобто натиснути їх тут сьогодні не можна взагалі:
   *   FloatingToolbar, StatusBar  <- layouts/DashboardLayout.tsx, який не
   *                                  імпортує жоден файл продукту;
   *   WhereChip                   <- HudShell <- OmniMap <- layouts/MapLayout.tsx,
   *                                  який теж не імпортує ніхто;
   *   useMapOpenNavigator()       <- викликається лише в DashboardLayout.
   * `app/App.tsx` віддає MessengerLayout на всі маршрути й на катч-ол.
   *
   * Тому їх НЕ «направлено в модалку», хоч така правка й напрошувалась:
   * підключити двері всередині крила без входу означало б прибрати ці
   * рядки зі сторожа й отримати зелене там, де для людини не змінилось
   * нічого. Справжня вада тут більша за маршрут — див.
   * `reachFromEntry.test.ts`: 249 із 446 файлів недосяжні з `main.tsx`.
   *
   * Окремо: `/settings/mobile` нікуди направляти В ЦЬОМУ ДЕРЕВІ. Спарування
   * живе в `settings/MobilePairing`, її імпортує лише `settings/SettingsPanel`,
   * і та тут недосяжна; у живій `messenger/SettingsModal` спарування немає.
   * У склі ПК воно досяжне — `desk/paneRegistry.tsx:68` тягне `SettingsPanel`
   * лінивим імпортом. Тобто дорога «підключити телефон» існує, але не звідси.
   *
   * МОЖЛИВІСТЬ, а не борг: коли зʼявиться нерозладжувальний шлях «відкрий
   * нативний екран», агент має вести туди. Виміряно: у хості таке вміє
   * `MainActivity.debugRouteIntent` (`phantom://screen/<маршрут>`), але
   * перший його рядок — `if (!BuildConfig.DEBUG) return false`, тож у
   * релізі він мертвий. Нативна частина при цьому ГОТОВА й не налагоджувальна
   * (`events.routeRequest` -> `PhantomNavGraph`) — бракує лише дії моста,
   * яка б її смикнула. Це і є той, кого підключати.
   *
   * Кожен рядок — справжній дотик у продукті:
   *   /settings         FloatingToolbar.tsx:154 — пункт «Налаштування»
   *   /settings/profile StatusBar.tsx:227       — клік по своєму профілю
   *   /polis            FloatingToolbar.tsx:166 — пункт «Поліс»
   *   /map              useMapOpenNavigator.ts:48 — агент «відкрий мапу»
   *   /settings/mobile  WhereChip.tsx:107 — «підключити телефон» на мапі;
   *                     іронія: посилання додали, щоб полагодити пораду,
   *                     «якою не можна скористатись», і привели в стіну
   *
   * Прибирати їх у цьому проході не мандат: чотири різні поверхні й чотири
   * різні рішення власника. Але новий такий перехід сюди вже не пройде —
   * його спіймає `жоден перехід у продукті...` нижче, а коли борг закриють,
   * почервоніє `відомі борги лишаються названими` й накаже почистити список.
   */
  // 26.09: `/settings` і `/map` стали справжніми маршрутами оболонки ПК
  // (components/shell, docs/pc-census.md) — з боргу прибрано.
  // 04.10: `/settings/profile` (StatusBar) і `/polis` (FloatingToolbar) пішли
  // разом зі своїми поверхнями — у дереві їх більше не кличе ніхто. Разом з
  // ними пішли й Додатки (Overlays): сторож плиток Додатків, що стояв тут,
  // більше не має чого міряти — плиток немає.
  const KNOWN_DEBT = ['/settings/mobile'];

  it('відомі борги лишаються названими: жоден із них ще не маршрут', async () => {
    const { DECLARED_ROUTES } = await import('../app/App');
    // Коли маршрут зʼявиться (або перехід приберуть) — тест почервоніє й
    // накаже почистити KNOWN_DEBT. Борг, що розсмоктується мовчки, знову
    // стає невидимим, а список, який ніхто не чистить, за місяць бреше.
    const declared = new Set<string>(DECLARED_ROUTES);
    const fixed = KNOWN_DEBT.filter((d) => declared.has(d));
    expect(
      fixed,
      `ці маршрути стали справжніми — прибери їх із KNOWN_DEBT: ${fixed.join(', ')}`,
    ).toEqual([]);
  });

  /**
   * Найширший сторож: не рендер, а вихідний текст.
   *
   * Рендер-тест бачить лише те, що змонтувалось, — а переходи ховаються ще
   * й у меню, у шапці, в модалках і в хуках, куди жоден із наших тестів не
   * заходить. Тому цілі навігації рахуються прямо з `src/`: так новий
   * `navigate('/кудись')` у будь-якому файлі впаде тут, навіть якщо той
   * екран ніколи не відкривали в тестах.
   *
   * Скануємо `src/` ЦЬОГО дерева й звіряємо з ЙОГО `DECLARED_ROUTES`. Сусіднє
   * дерево має власний список маршрутів і власного сторожа не має — сказати
   * про нього щось звідси не можна.
   */
  it('жоден перехід у цьому дереві не веде на маршрут, якого немає', async () => {
    const { readFileSync, readdirSync, statSync } = await import('node:fs');
    const { join } = await import('node:path');
    const { DECLARED_ROUTES } = await import('../app/App');

    const walk = (dir: string): string[] =>
      readdirSync(dir).flatMap((name) => {
        const full = join(dir, name);
        if (statSync(full).isDirectory()) {
          return name === '__tests__' || name === 'node_modules' ? [] : walk(full);
        }
        return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [full] : [];
      });

    // Рахуємо лише виконувані переходи: navigate('/x'), <Link to="/x">,
    // href="/x". Рядки в коментарях у ці форми не потрапляють.
    const CALL = /(?:navigate\(|(?:\bto|\bhref)=)['"](\/[^'"]*)['"]/g;
    // Коментар — не перехід. Без цього сторож ловив власне ж пояснення в
    // App.tsx і виглядав би зламаним; зламаного сторожа послаблюють, а не
    // читають.
    const stripComments = (t: string) =>
      t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    const found = new Map<string, string>();
    for (const file of walk(join(process.cwd(), 'src'))) {
      const text = stripComments(readFileSync(file, 'utf8'));
      for (const m of text.matchAll(CALL)) {
        if (!found.has(m[1])) found.set(m[1], file.replace(process.cwd(), ''));
      }
    }

    expect(found.size, 'у src не знайдено жодного переходу — вимір ні про що').toBeGreaterThan(0);

    const declared = new Set<string>(DECLARED_ROUTES);
    const intoTheWall = [...found.entries()]
      .filter(([route]) => !declared.has(route) && !KNOWN_DEBT.includes(route))
      .map(([route, file]) => `${route} (${file})`);

    expect(
      intoTheWall,
      `новий перехід на маршрут, якого немає — катч-ол мовчки поверне месенджер: ${intoTheWall.join(', ')}`,
    ).toEqual([]);
  });
});
