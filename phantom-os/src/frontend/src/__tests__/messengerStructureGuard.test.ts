/**
 * Сторож структури месенджера (04.10): двері без дверей не повертаються.
 *
 * Що було на b629fd08 (APK web_sha 5a103b96): чанк `rootModals-*.js` важив
 * 237 744 Б і віз ActionHubModal («Стаття витрат»), P2PNetworkModal («Ручний
 * SDP»), SmartFolderModal («Smart Workspace»), ScheduleMessageModal («Швидкі
 * пресети»), групу «Доказові розмови» і шухляду «Керування чергою…» — жодна з
 * модалок не мала дверей. `MessengerLayout-*.js` віз CanvasSplitView з бейджем
 * «Wasm Live Code», хоч телефон документа не показує ніколи.
 *
 * Два ока: що ЕКСПОРТУЄ rootModals (джерело), і що ЛЕЖИТЬ у dist-phone
 * (артефакт). Свіжість артефакту — тим самим правилом, що й у
 * phoneBundle.test.ts: каталог новіший за src/ і конфіг, інакше збираємо тут
 * тією ж командою, що й людина.
 */
import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const OUT = join(ROOT, 'dist-phone');
const SRC = join(ROOT, 'src');

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
};

const newestMtime = (dir: string): number => walk(dir).reduce((max, f) => Math.max(max, statSync(f).mtimeMs), 0);

const ensureFreshBuild = (): void => {
  const config = statSync(join(ROOT, 'vite.config.ts')).mtimeMs;
  const fresh = existsSync(OUT) && newestMtime(OUT) > Math.max(newestMtime(SRC), config);
  if (fresh) return;
  execFileSync('npm', ['run', 'build:phone'], {
    cwd: ROOT,
    stdio: 'pipe',
    timeout: 280_000,
    env: { ...process.env, NODE_ENV: 'production' },
  });
};

/** Слова, за якими впізнається кожна віднята річ, — і де вона жила. */
const GONE: Array<{ text: string; what: string }> = [
  { text: 'Стаття витрат', what: 'ActionHubModal' },
  { text: 'Ручний SDP', what: 'P2PNetworkModal' },
  { text: 'Smart Workspace', what: 'SmartFolderModal' },
  { text: 'Швидкі пресети', what: 'ScheduleMessageModal' },
  { text: 'Доказові розмови', what: 'PhoneSettingsSheet: група прибирання за назвою' },
  { text: 'Керування чергою', what: 'стара шухляда відкладених' },
  { text: 'Wasm Live Code', what: 'CanvasSplitView: бейдж, що нічого не виконував' },
  // Ключ сховища документа — є лише в CanvasSplitView: його немає — немає й документа.
  { text: 'phantom_canvas_v3_', what: 'CanvasSplitView у телефонному бандлі' },
];

describe('rootModals: лише модалки з дверима', () => {
  it('не експортує ActionHubModal, P2PNetworkModal, SmartFolderModal і ScheduleMessageModal', async () => {
    const m = await import('../components/messenger/modals/rootModals');
    const names = Object.keys(m);
    // Сам модуль прочитано: живі модалки на місці.
    expect(names).toContain('ScheduledMessagesDrawer');
    expect(names).toContain('ForwardMessageModal');
    for (const gone of ['ActionHubModal', 'P2PNetworkModal', 'SmartFolderModal', 'ScheduleMessageModal']) {
      expect(names, gone).not.toContain(gone);
    }
  });
});

describe('dist-phone: віднятого в APK немає', () => {
  it('жодного слова віднятих модалок, «Доказових розмов» і документа', () => {
    ensureFreshBuild();
    const files = walk(OUT).filter((f) => f.endsWith('.js') || f.endsWith('.html'));
    expect(files.some((f) => /MessengerLayout-[\w-]+\.js$/.test(f))).toBe(true);
    const root = files.find((f) => /rootModals-[\w-]+\.js$/.test(f));
    expect(root, 'чанк rootModals є').toBeTruthy();
    // Розмір чанка — у звіт (до: 237 744 Б у APK web_sha 5a103b96).
    console.info(`[guard] ${relative(OUT, root!)}: ${statSync(root!).size} B`);
    expect(statSync(root!).size).toBeLessThan(237_744);

    const hits: string[] = [];
    for (const f of files) {
      const body = readFileSync(f, 'utf8');
      for (const g of GONE) if (body.includes(g.text)) hits.push(`${relative(OUT, f)}: «${g.text}» (${g.what})`);
    }
    expect(hits).toEqual([]);
  });

  /*
   * Відкладене на телефоні (власник 20.10): форма є, і лист кладеться на
   * апарат (`/messenger/scheduled`), де його шле будильник, а не вкладка.
   */
  it('відкладене на телефоні: форма і апаратний список, а не вкладка', () => {
    ensureFreshBuild();
    const js = walk(OUT).filter((f) => f.endsWith('.js'));
    const where = (needle: string) =>
      js.filter((f) => readFileSync(f, 'utf8').includes(needle)).map((f) => relative(OUT, f));
    // Слова чипів живуть у каталозі i18n, що їде завжди; форму видає лише її власна мітка.
    expect(where('scheduled-create').length).toBeGreaterThan(0);
    expect(where('/messenger/scheduled').length).toBeGreaterThan(0);
    expect(where('Нічого не заплановано').length).toBeGreaterThan(0);
    expect(where('Надіслати зараз').length).toBeGreaterThan(0);
  });
});
