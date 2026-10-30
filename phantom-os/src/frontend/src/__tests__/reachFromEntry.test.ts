/**
 * Сторож досяжності: чи веде до файлу хоч якийсь шлях від точки входу.
 *
 * ─── МЕЖА ТВЕРДЖЕННЯ. ЦЕ ПРО ОДНЕ ДЕРЕВО ─────────────────────────────────
 *
 * Сторож міряє `phantom-os-messenger/src/frontend` — веб-месенджер, що їде
 * в телефоні (`dist-phone`, `ModalHost.phone.tsx`). Тут вхід ОДИН:
 * `main.tsx` -> `app/App.tsx` -> `MessengerLayout` -> `MessengerRoot`, і
 * катч-ол `path="*"` веде туди ж.
 *
 * У СУСІДНЬОМУ ДЕРЕВІ ВІДПОВІДЬ ІНША, І ВОНА ТЕЖ ПРАВИЛЬНА.
 * `phantom-os-beta/src/frontend` — скло ПК. Там коренем маршруту стоїть
 * `DashboardLayout` (App.tsx:206, підвантажений `React.lazy` на :67), а
 * `/map`, `/settings/:categoryId?`, `/polis` — СПРАВЖНІ маршрути; крім
 * того, `components/desk/paneRegistry.tsx:68` тягне `SettingsPanel` через
 * `React.lazy`, тобто спарування телефона там досяжне. Файла
 * `paneRegistry.tsx` у дереві месенджера не існує взагалі.
 *
 * Обидва дерева мають файли з ОДНАКОВИМИ іменами — `SettingsPanel`,
 * `MobilePairing`, `FloatingToolbar`, `StatusBar`, `WhereChip`,
 * `MessengerRoot`. Тому висновок звідси не можна переносити на «веб» чи на
 * «продукт»: він про це дерево. Цю плутанину ми вже пережили один раз —
 * два виміри дали протилежні відповіді, бо міряли різні корені.
 *
 * ─── ЩО ЗАМІРЯНО ТУТ ─────────────────────────────────────────────────────
 *
 * Знайдено 04.09, під час прибирання «дверей у стіну». Виявилось, що самі
 * двері — не головне: **249 із 446 продуктових файлів (56%) цього дерева
 * недосяжні з його `main.tsx`**. Не «мертвий код» у сенсі невикористаних функцій, а
 * цілі крила, які збираються, тестуються зеленим і не існують для людини:
 *
 *   - `layouts/DashboardLayout.tsx` і `layouts/MapLayout.tsx` не імпортує
 *     НІХТО. Разом з ними не монтуються `StatusBar`, `FloatingToolbar`,
 *     `useMapOpenNavigator()` і все дерево `components/map/**` (OmniMap →
 *     HudShell → WhereChip).
 *   - `components/settings/SettingsPanel.tsx` недосяжна, а з нею й
 *     `MobilePairing`: спарування телефона з ПК НЕ існує В ЦЬОМУ ДЕРЕВІ,
 *     хоча компонент написаний і має тести. У склі ПК воно є — див. межу
 *     твердження вище.
 *   - `components/chat/ChatWindow.tsx`, `agent/overlays/AgentVault.tsx`,
 *     `tools/ToolsOverlay.tsx`, `utils/format.ts` — теж поза графом.
 *
 * `app/App.tsx` цього дерева віддає `MessengerLayout` на всі чотири
 * маршрути, і катч-ол теж. Живе тут — месенджер; решта крил лежить поруч і
 * мовчить, і частина з них жива в сусідньому дереві.
 *
 * Ліниві імпорти обхід ураховує (`import(...)` нарівні з `from`): у цьому
 * дереві їх пʼять, і жоден не веде до `DashboardLayout`, `MapLayout` чи
 * `SettingsPanel` — перевірено, бо саме на цьому спіткнулась перша гіпотеза.
 *
 * ЧОМУ ЦЕ ТЕСТ, А НЕ ЗАПИС У ДОКУМЕНТІ. Зелений тест на компоненті, якого
 * ніхто не монтує, доводить, що функція працює, — і нічого не каже про те,
 * чи існує вона для людини. Саме так ми двічі за добу «лагодили» те, чого
 * не видно. Тут міряється інша властивість: чи є шлях від `main.tsx`.
 *
 * ЧОМУ БАЗОВА ЛІНІЯ, А НЕ НУЛЬ. 249 файлів не прибрати одним проходом і не
 * мій мандат вирішувати долю кожного крила. Тому стеля: список не має
 * РОСТИ. Нове осиротіле крило почервоніє тут-таки, а коли крило приберуть
 * або підключать — почервоніє теж і накаже опустити стелю.
 */

import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';

/**
 * Стеля осиротілих файлів, заміряна 04.09 на HEAD 30b3a25.
 *
 * Опускати можна й треба; піднімати — лише разом із рішенням власника про
 * конкретне крило, і тоді в коміті має бути сказано, яке саме.
 *
 * 05.09: +2, ухвала власника «прибрати AI Студію з телефонної збірки».
 * `AISynthesisStudioModal.phone.tsx` і `stores/aiSynthesisStore.phone.ts` —
 * той самий прийом, що й уже прийнятий у стелі `ModalHost.phone.tsx`:
 * модуль, якого статичний граф від `main.tsx` не бачить НІКОЛИ, бо його
 * підставляє резолвер vite (`PHONE_SWAPS`, vite.config.ts) лише під час
 * збірки під телефон. Сирітство тут — доказ, що дверей у справжню студію
 * (і в її вигадані modelUsed/isLocalInference/confidenceScore) з phone-графа
 * більше нема, а не недогляд.
 *
 * 24.09: 251 → 250. Віднімання театру додало одного сироту —
 * `styles/formTokens.ts` (джерело правди форми: його звіряє formTokens.test.ts,
 * а в граф іде `form.css`). Натомість прибрано два справжні сироти
 * месенджера, яких не імпортував ніхто, навіть тести: `InteractiveMapView.tsx`
 * і `LocationDossierModal.tsx`. Стелю опущено, не піднято.
 *
 * 24.09 (віднімання театру ПК): 250 → 246. Сироти-підміни
 * `ModalHost.phone.tsx`, `AISynthesisStudioModal.phone.tsx` і
 * `aiSynthesisStore.phone.ts` пішли разом із тим, що вони підміняли: самі
 * показові модалки й AI-студію видалено, а не сховано (docs/pc-msgr-census.md).
 * Нових сиріт видалення не народило — звірено списком до й після.
 *
 * 04.10 (web-shell-entry): 246 → 15. Справжній лік перед правкою був 119 із
 * 514 (стеля відстала): агент, місії, Поліс, кузня, інструменти, фамільяр,
 * дев'ять розкладок скла ПК, вікна Overlays тощо пішли з дерева. Лишились
 * інструменти мапи (не цього пакета) і файли конфігурації (`dayTheme.ts`,
 * `tailwind-preset.ts`, `maplibreWorker.ts`, `messengerAccent.ts`), які
 * беруть збірка й тести, а не граф імпортів.
 */
const ORPHAN_CEILING = 15;

/** Те, що людина справді бачить У ЦЬОМУ ДЕРЕВІ. Осиротіє — і тести змовчать. */
const MUST_STAY_LIVE = [
  'src/app/App.tsx',
  'src/layouts/MessengerLayout.tsx',
  'src/components/messenger/MessengerRoot.tsx',
  'src/components/messenger/ChatArea.tsx',
  'src/components/messenger/MessageComposer.tsx',
  'src/components/messenger/PhoneSettingsSheet.tsx',
  'src/components/messenger/UserProfileModal.tsx',
  'src/stores/messengerStore.ts',
  'src/services/storagePersistence.ts',
  'src/services/globalP2PMesh.ts',
];

const SRC = resolve('src');
const isTest = (f: string) => /__tests__|\.test\.tsx?$|test-setup/.test(f);

const productFiles = (): string[] => {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (/\.tsx?$/.test(name) && !/\.d\.ts$/.test(name) && !isTest(full)) out.push(full);
    }
  };
  walk(SRC);
  return out;
};

/** Той самий розбір шляхів, що й у збірці: відносні + псевдонім `@shared`. */
const resolveImport = (from: string, spec: string): string | null => {
  if (!spec.startsWith('.') && !spec.startsWith('@shared')) return null;
  const base = spec.startsWith('@shared')
    ? resolve('../shared', spec.slice(8))
    : resolve(dirname(from), spec);
  for (const candidate of [
    base,
    `${base}.tsx`,
    `${base}.ts`,
    join(base, 'index.tsx'),
    join(base, 'index.ts'),
  ]) {
    if (existsSync(candidate) && statSync(candidate).isFile()) return candidate;
  }
  return null;
};

/** Обхід графа імпортів від входу. Динамічний `import()` рахується теж. */
const reachableFromEntry = (): Set<string> => {
  const seen = new Set<string>();
  const stack = [resolve('src/main.tsx')];
  while (stack.length) {
    const file = stack.pop();
    if (!file || seen.has(file)) continue;
    seen.add(file);
    let text: string;
    try {
      text = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    for (const m of text.matchAll(/(?:from\s*|import\s*\(\s*)['"]([^'"]+)['"]/g)) {
      const target = resolveImport(file, m[1]);
      if (target) stack.push(target);
    }
  }
  return seen;
};

const rel = (f: string) => f.replace(`${resolve('.')}/`, '');

describe('досяжність від точки входу (дерево месенджера)', () => {
  it('осиротілих файлів не побільшало', () => {
    const all = productFiles();
    const live = reachableFromEntry();

    expect(all.length, 'у src не знайдено файлів — вимір ні про що').toBeGreaterThan(0);
    expect(live.size, 'граф від main.tsx порожній — вимір ні про що').toBeGreaterThan(1);

    const orphans = all.filter((f) => !live.has(f)).map(rel).sort();
    expect(
      orphans.length,
      `осиротілих крил побільшало (${orphans.length} проти стелі ${ORPHAN_CEILING}) ` +
        'у дереві месенджера. Файл, до якого немає шляху від його main.tsx, ' +
        'збирається й тестується зеленим, але для людини тут його не існує. ' +
        'Про скло ПК (phantom-os-beta) цей вимір не говорить нічого.',
    ).toBeLessThanOrEqual(ORPHAN_CEILING);
  });

  it('живий продукт лишається живим', () => {
    const live = new Set([...reachableFromEntry()].map(rel));
    const orphaned = MUST_STAY_LIVE.filter((f) => !live.has(f));
    expect(
      orphaned,
      'ці файли більше не досяжні з main.tsx дерева месенджера — людина ' +
        `перестала їх бачити: ${orphaned.join(', ')}`,
    ).toEqual([]);
  });
});
