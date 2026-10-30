import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Придатність до пальця. Власник, дослівно: «натиснути не можу а дещо випирає».
 *
 * Веб малювався для миші. На 393dp половина кнопок менша за палець, і поки це
 * так, решта роботи не має ваги: у простір, топік і гілку треба ще влучити.
 *
 * Стеля — храповик: борг може лише спадати. Число знято виміром, не з голови.
 */

const ROOT = path.resolve(__dirname, '../../components/messenger');

/** 44px — підлога дотику. Tailwind: h-11. Усе, що нижче, ловимо. */
const SMALL = /\b[wh]-([1-9]|10)\b/;
/** Явно заданий мінімум або щедрий внутрішній відступ рятують дрібний розмір. */
const RESCUED = /min-h-\[(4[4-9]|[5-9]\d)px\]|min-h-1[12]|min-w-\[4[4-9]px\]|p-[3-9]|py-[3-9]|touch-floor/;

/**
 * Лише відкривальний тег кнопки. У JSX `onClick={() => …}` містить '>', тож
 * наївне `<button[^>]*>` обривається ДО className: перші дві редакції цього
 * виміру дали 1 і 0 замість 457. Третя брала вікно в 700 символів — і читала
 * класи іконки всередині кнопки як класи кнопки: `w-4 h-4` на <svg> робив
 * кнопку «дрібною», а touch-floor, механічно поставлений на ту ж іконку,
 * «рятував» її. Прилад говорив про себе. Тег закривається першим '>', що не
 * є частиною '=>'.
 */
const OPEN_TAG = /<button\b(?:[^>=]|=(?!>)|=>)*>/g;

/**
 * Коментар усередині тега — не розмітка, і сторож не має його читати.
 *
 * Виміряно 18.09, на собі: правку кнопки я пояснив коментарем ПРЯМО в
 * відкривальному тезі, і слово `touch-floor` із того пояснення збіглося з
 * [[RESCUED]]. Кнопка з `min-h-[36px]` стала «врятованою» власним
 * коментарем — борг повернули руками, а сторож лишився зелений. Це те саме
 * [[phantom-guard-satisfied-by-its-own-comment]], і за кампанію сталось
 * двічі, отже лікувати треба прилад, а не звичку.
 *
 * Тому текст тега чиститься від коментарів ПЕРЕД вимірюванням: і SMALL, і
 * RESCUED бачать лише те, що справді їде в DOM.
 */
const stripComments = (tag: string): string =>
  tag.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');

const smallButtons = (src: string): number => {
  let n = 0;
  for (const m of src.matchAll(OPEN_TAG)) {
    const tag = stripComments(m[0]);
    if (SMALL.test(tag) && !RESCUED.test(tag)) n += 1;
  }
  return n;
};

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.tsx')) out.push(p);
  }
  return out;
};

/** 457 → 300 — підлога дотику в шести найгарячіших файлах; 300 → 21 — чесне вікно
 *  (класи кнопки, не її іконки) плюс підлога 48 на телефоні через CSS;
 *  21 → 20 (04.09) — видалено ZeroTraceAirGapModal.tsx, який ніс рівно одну
 *  дрібну ціль. Модалку прибрано не за розмір кнопки, а за вигаданий підпис
 *  Ed25519 з Math.random(); менший борг тут — побічний прибуток, і храповик
 *  зажадав записати його тим самим комітом.
 *  20 → 18 (05.09) — прибрано TeamHuddleBar (дві цілі `p-1` у смужці «Huddle»).
 *  Смужку знято не за розмір кнопки, а за те, що за нею не стояло нічого:
 *  нуль мережевих викликів на 9 КБ коду і «Huddle (0)» з порожнього масиву.
 *  18 → 16 (07.09) — захищено підлогою дотику кнопки профілю та вибору аватара
 *  в UserProfileModal.
 *  16 → 0 (07.09) — повна ліквідація боргу дотику: CallOverlay PiP, SettingsModal,
 *  ReactionPickerModal, AmbientContextModal, AutomationPipelineModal, FolderIconPickerModal,
 *  InteractiveVisualization3DModal, NeuroErgonomicsModal, ResourceGovernanceModal,
 *  SmartFolderModal переведені на touch-floor (≥44px).
 *  Опускати можна, піднімати ні. Справжні ворота — e2e/phone-fitness.spec.ts,
 *  цей вимір лише дешевий і швидкий. */
const CEILING = 0;

describe('придатність до пальця', () => {
  it('вимір бачить дрібну кнопку навіть із стрілковою функцією поруч', () => {
    const sample = `
      <button
        onClick={() => doThing()}
        className="w-8 h-8 rounded-full"
      >
        <X />
      </button>`;
    expect(smallButtons(sample)).toBe(1);
  });

  it('вимір не читає класи іконки як класи кнопки', () => {
    const sample = `
      <button onClick={() => doThing()} className="w-full h-12 px-3">
        <Icon className="w-4 h-4" />
      </button>`;
    expect(smallButtons(sample)).toBe(0);
  });

  /**
   * Сторож не рятується коментарем. Вимір 18.09: слово-порятунок у
   * коментарі всередині тега давало 0 замість 1, тобто борг повертався
   * мовчки. Цей випадок і є доказ, що сторож не сліпий.
   */
  it('слово-порятунок у коментарі НЕ рятує дрібну кнопку', () => {
    const sample = `
      <button
        onClick={() => doThing()}
        /* тут колись стояв touch-floor, і min-h-[44px] теж */
        className="w-9 h-9 min-h-[36px] rounded-xl"
      >x</button>`;
    expect(smallButtons(sample)).toBe(1);
  });

  it('вимір не чіпає кнопку з підлогою дотику', () => {
    const ok = `
      <button
        onClick={() => doThing()}
        className="w-8 h-8 min-h-[44px] min-w-[44px]"
      >x</button>`;
    expect(smallButtons(ok)).toBe(0);
  });

  it('дрібних дотикових цілей не більшає', () => {
    const files = walk(ROOT);
    expect(files.length).toBeGreaterThan(50);
    const total = files.reduce((n, f) => n + smallButtons(fs.readFileSync(f, 'utf8')), 0);

    expect(
      total,
      total > CEILING
        ? `Дрібних кнопок стало ${total} проти стелі ${CEILING}. Новий десктопний ` +
          'компонент повернув борг: на 393dp у таку ціль не влучити пальцем.'
        : `Дрібних кнопок ${total}, стеля ${CEILING} — опусти стелю до ${total} тим самим комітом, ` +
          'інакше ратчет не тримає здобуте.',
    ).toBe(CEILING);
  });
});
