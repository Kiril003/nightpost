import { chromium } from '/home/kyrylo/phantom_ai/phantom-mono/phantom-os/src/frontend/node_modules/playwright/index.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';
const DIR = 'file:///home/kyrylo/phantom_ai/wt-site-a/platform-site/dev/system/';
const OUT = '/home/kyrylo/phantom_ai/showcase-out/system';
const themes = process.argv.slice(2).length ? process.argv.slice(2) : ['night', 'day', 'ember'];
const screens = [
  ['phone-today', 390, 844, false, 'Phone · Today'],
  ['pc-today', 1440, 900, false, 'PC · Today'],
  ['site-home', 1440, 900, true, 'Site · Overview'],
  ['site-talk', 1440, 0, true, 'Site · product page (Talk)'],
  ['site-you', 1440, 1000, true, 'Site · You (/account)'],
  ['site-places-empty', 1440, 900, true, 'Site · Places, empty (/build)'],
  ['site-places-one', 1440, 900, true, 'Site · Places, one place (/build)'],
];
const b = await chromium.launch({ args: ['--no-sandbox', '--allow-file-access-from-files'] });
for (const theme of themes) {
  const dir = `${OUT}/screens/${theme}`; mkdirSync(dir, { recursive: true });
  for (const [name, w, h, site] of screens) {
    const ctx = await b.newContext({ viewport: { width: w, height: h || 900 }, deviceScaleFactor: 2 });
    const p = await ctx.newPage();
    const errs = []; p.on('pageerror', (e) => errs.push(String(e))); p.on('requestfailed', (r) => errs.push('REQ ' + r.url()));
    await p.goto(`${DIR}${name}.html#${site ? 'night' : theme}`, { waitUntil: 'load' });
    await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(300);
    const hs = await p.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    await p.screenshot({ path: `${dir}/${name}.png`, fullPage: !h });
    console.log(theme, name, hs ? 'HSCROLL' : 'ok', errs.join(' | '));
    await ctx.close();
  }
  const fig = (n, label, cls) => `<figure class="${cls}"><figcaption>${label}</figcaption><img src="screens/${theme}/${n}.png"></figure>`;
  const L = Object.fromEntries(screens.map((s) => [s[0], s[4]]));
  const stops = { night: 'Night', day: 'Day', ember: 'Between: Ember (sun 0° to −6°)', golden: 'Between: Golden (sun +6° to 0°)' };
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{font-family:M;src:url('file:///home/kyrylo/phantom_ai/wt-site-a/platform-site/node_modules/@fontsource-variable/manrope/files/manrope-latin-wght-normal.woff2')}
body{margin:0;background:#1a1816;color:#e9e3da;font-family:M,sans-serif;padding:48px;width:3000px}
h1{font-size:40px;margin:0 0 6px} .sub{color:#a59d92;font-size:20px;margin-bottom:24px}
.ramp{display:flex;gap:0;margin:0 0 36px;height:56px;border-radius:12px;overflow:hidden;width:1400px}
.ramp div{flex:1;display:flex;align-items:flex-end;padding:8px 12px;font-size:15px}
.grid{display:grid;gap:40px;grid-template-columns:420px 1fr 1fr;align-items:start}
figure{margin:0} figcaption{font-size:20px;margin:0 0 10px;color:#cfc7bb} img{width:100%;display:block;border-radius:8px;outline:1px solid #3a352f}
.phone img{width:390px}.span2{grid-column:span 2}
</style></head><body><h1>Nightpost system · ${stops[theme]}</h1><div class="sub">Same person (Ana), same data on every surface. Site pages stay Night. Elevation by tone, no shadows.</div>
<div class="ramp"><div style="background:#F4EFE6;color:#1B1611">Day</div><div style="background:#EDE1CE;color:#1E1711">Golden</div><div style="background:#211A15;color:#F6EEE4">Ember</div><div style="background:#0B0A09;color:#F4EFE8">Night</div></div>
<div class="grid">${fig('phone-today', L['phone-today'], 'phone')}${fig('pc-today', L['pc-today'], 'span2')}
<div></div>${fig('site-home', L['site-home'], '')}${fig('site-you', L['site-you'], '')}
<div></div>${fig('site-places-empty', L['site-places-empty'], '')}${fig('site-places-one', L['site-places-one'], '')}
<div></div>${fig('site-talk', L['site-talk'], 'span2')}</div></body></html>`;
  writeFileSync(`${OUT}/sheet-${theme}.html`, html);
  const ctx = await b.newContext({ viewport: { width: 3096, height: 1000 } });
  const p = await ctx.newPage(); await p.goto(`file://${OUT}/sheet-${theme}.html`, { waitUntil: 'load' }); await p.waitForTimeout(500);
  const name = theme === 'ember' ? 'dusk' : theme;
  await p.screenshot({ path: `${OUT}/sheet-${name}.png`, fullPage: true }); await ctx.close();
  console.log('sheet', `${OUT}/sheet-${name}.png`);
}
await b.close();
