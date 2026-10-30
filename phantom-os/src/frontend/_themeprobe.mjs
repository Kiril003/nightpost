/**
 * Прилад теми: чи справді веб перефарбовується слідом за `data-theme`.
 *
 * Міряє СПРАВЖНІЙ каскад у справжньому рушії (chromium) на ЗІБРАНОМУ CSS,
 * а не на джерелі: питомість і `!important` існують лише після збірки.
 *
 *   node _themeprobe.mjs <тека з assets>
 */
import { chromium } from '@playwright/test';
import { readFileSync, readdirSync } from 'node:fs';

const dir = process.argv[2];
const css = readdirSync(dir)
  .filter((f) => f.endsWith('.css'))
  .map((f) => readFileSync(`${dir}/${f}`, 'utf8'))
  .join('\n');

/** [опис, розмітка, селектор для читання, властивість] */
const PROBES = [
  ['підкладка сторінки', '', 'body', 'backgroundColor'],
  ['світла поверхня bg-white', '<div id="t" class="bg-white">x</div>', '#t', 'backgroundColor'],
  ['поверхня картки #0E1410', '<div id="t" class="bg-[#0E1410]">x</div>', '#t', 'backgroundColor'],
  ['темне чорнило #21261F', '<div id="t" class="text-[#21261F]">x</div>', '#t', 'color'],
  ['другорядний підпис #64748B', '<div id="t" class="text-[#64748B]">x</div>', '#t', 'color'],
  ['модалка [role=dialog]', '<div id="t" role="dialog">x</div>', '#t', 'backgroundColor'],
  ['поле вводу', '<input id="t" type="text">', '#t', 'backgroundColor'],
  ['чорнило поля вводу', '<input id="t" type="text">', '#t', 'color'],
  ['волосяна межа #2D362F', '<div id="t" class="border-[#2D362F]" style="border-style:solid;border-width:1px">x</div>', '#t', 'borderColor'],
  ['друге чорнило #1E2521', '<div id="t" class="text-[#1E2521]">x</div>', '#t', 'color'],
  ['нічна підкладка #121714', '<div id="t" class="bg-[#121714]">x</div>', '#t', 'backgroundColor'],
  ['нічне чорнило #F8FAF8', '<div id="t" class="text-[#F8FAF8]">x</div>', '#t', 'color'],
];

const browser = await chromium.launch();
const page = await browser.newPage();
const read = async (theme, markup, sel, prop) => {
  await page.setContent(`<style>${css}</style>${markup}`);
  await page.evaluate((t) => {
    document.documentElement.dataset.theme = t;
  }, theme);
  return page.evaluate(([s, p]) => getComputedStyle(document.querySelector(s))[p], [sel, prop]);
};

let stuck = 0;
console.log('що'.padEnd(28), 'ніч'.padEnd(26), 'день');
for (const [name, markup, sel, prop] of PROBES) {
  const night = await read('night', markup, sel, prop);
  const day = await read('day', markup, sel, prop);
  const same = night === day;
  if (same) stuck += 1;
  console.log(name.padEnd(28), night.padEnd(26), day, same ? '  <- НЕ ЗМІНИВСЯ' : '');
}
console.log(`\nне міняються з темою: ${stuck} із ${PROBES.length}`);

/**
 * Друга частина: чи перефарбовує ЩОСЬ перемикач акценту.
 *
 * `amber` атрибута не ставить — це типовий вигляд, база для порівняння.
 */
const ACCENT_PROBES = [
  ['заливка bg-[#F4AF25]', '<div id="t" class="bg-[#F4AF25]">x</div>', 'backgroundColor'],
  ['напис text-[#F4AF25]', '<div id="t" class="text-[#F4AF25]">x</div>', 'color'],
  ['межа border-[#F4AF25]', '<div id="t" class="border-[#F4AF25]" style="border-style:solid;border-width:1px">x</div>', 'borderColor'],
];

for (const theme of ['night', 'day']) {
  console.log(`\nакцент, тема «${theme}»`);
  console.log('  що'.padEnd(26), ['amber', 'sage', 'chestnut'].map((a) => a.padEnd(22)).join(''));
  for (const [name, markup, prop] of ACCENT_PROBES) {
    const values = [];
    for (const accent of ['amber', 'sage', 'chestnut']) {
      await page.setContent(`<style>${css}</style>${markup}`);
      await page.evaluate(
        ([t, a]) => {
          document.documentElement.dataset.theme = t;
          if (a === 'amber') document.documentElement.removeAttribute('data-msg-accent');
          else document.documentElement.setAttribute('data-msg-accent', a);
        },
        [theme, accent],
      );
      values.push(await page.evaluate(([p]) => getComputedStyle(document.getElementById('t'))[p], [prop]));
    }
    const moved = new Set(values).size > 1;
    console.log('  ' + name.padEnd(24), values.map((v) => v.padEnd(22)).join(''), moved ? '' : '<- НЕ ЗМІНИВСЯ');
  }
}
await browser.close();
