import { chromium } from '/home/kyrylo/phantom_ai/phantom-mono/phantom-os/src/frontend/node_modules/playwright/index.mjs';
const [base, out, routesArg] = process.argv.slice(2);
const routes = routesArg.split(',');
const browser = await chromium.launch({ args: ['--no-sandbox'] });
for (const [w, h] of [[1440, 900], [390, 844]]) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, reducedMotion: 'reduce', deviceScaleFactor: 1 });
  for (const r of routes) {
    const page = await ctx.newPage();
    const errs = [];
    page.on('console', (m) => { if (m.type() === 'error') errs.push('CON ' + m.text().slice(0, 160)); });
    page.on('pageerror', (e) => errs.push('JS ' + String(e).slice(0, 160)));
    page.on('requestfailed', (q) => errs.push('REQ ' + q.url().slice(0, 120) + ' ' + (q.failure()?.errorText ?? '')));
    page.on('response', (s) => { if (s.status() >= 400) errs.push('HTTP ' + s.status() + ' ' + s.url().slice(0, 120)); });
    try { await page.goto(base + r, { waitUntil: 'networkidle', timeout: 45000 }); } catch (e) { errs.push('NAV ' + String(e).slice(0, 100)); }
    await page.evaluate(async () => { for (let y = 0; y < document.body.scrollHeight; y += 600) { window.scrollTo(0, y); await new Promise((f) => setTimeout(f, 60)); } window.scrollTo(0, 0); });
    await page.waitForTimeout(500);
    const hscroll = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    const slug = r === '/' ? 'home' : r.slice(1).replace(/\//g, '-');
    await page.screenshot({ path: `${out}/${slug}-${w}.png`, fullPage: true });
    console.log(`${r} @${w}${hscroll ? ' HSCROLL' : ''}${errs.length ? '\n  ' + errs.join('\n  ') : ' ok'}`);
    await page.close();
  }
  await ctx.close();
}
await browser.close();
