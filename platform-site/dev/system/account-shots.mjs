import { chromium } from '/home/kyrylo/phantom_ai/phantom-mono/phantom-os/src/frontend/node_modules/playwright/index.mjs';
const [base, out] = process.argv.slice(2);
const now = '2026-10-17T00:00:00Z';
const account = { id: 'a1', email: 'olena@example.com', display_name: 'Olena Koval', locale: 'en', role: 'user', created_at: '2026-05-02T10:00:00Z', unread: 0 };
const build = { platform: 'android', filename: 'nightpost-17.apk', size: 48_600_000, version: '0.17.2', build: 17, sha256: 'a'.repeat(64), published_at: '2026-10-14T09:00:00Z' };
const lic = { id: 'l1', tier: 'personal', max_activations: 3, updates_until: '2027-10-14T00:00:00Z', created_at: '2026-10-01T00:00:00Z', revoked: false, devices: [{}] };
const sessions = [
  { id: 's1', created_at: now, expires_at: now, ip: null, via: 'qr', label: 'Chrome on Linux', current: true },
  { id: 's2', created_at: '2026-10-12T08:00:00Z', expires_at: now, ip: null, via: 'email', label: 'Pixel 8', current: false },
];
const venue = { id: 'v1', name: 'Kavarna Dvur', published: true, rev: 3, page: null, rating: { mean: 4.6, count: 12 } };
const sets = { nolicence: { licenses: [], venues: [], policy: 'account' }, licence: { licenses: [lic], venues: [venue], policy: 'licence' } };
const browser = await chromium.launch({ args: ['--no-sandbox'] });
for (const [name, s] of Object.entries(sets)) {
  for (const [w, h] of [[1440, 900], [390, 844]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, reducedMotion: 'reduce' });
    await ctx.addInitScript(() => localStorage.setItem('phantom-session', 'fixture'));
    await ctx.route(/fly\.dev|\/api\/|\/venues/, (route) => {
      const u = route.request().url();
      const json = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
      if (u.endsWith('/api/me')) return json({ account, licenses: s.licenses, staff_unread: 0, downloads: [build], download_policy: s.policy });
      if (u.includes('/api/me/sessions')) return json(sessions);
      if (u.includes('/venues/mine')) return json({ venues: s.venues });
      if (u.includes('/api/me/threads')) return json([]);
      return json({});
    });
    for (const r of ['/account', '/account#devices', '/account#security', '/build']) {
      const page = await ctx.newPage();
      await page.goto(base + r, { waitUntil: 'networkidle', timeout: 45000 }).catch(() => {});
      await page.waitForTimeout(600);
      const hs = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
      const slug = r.slice(1).replace(/[#/]/g, '-');
      await page.screenshot({ path: `${out}/${name}-${slug}-${w}.png`, fullPage: true });
      console.log(`${name} ${r} @${w}${hs ? ' HSCROLL' : ''}`);
      await page.close();
    }
    await ctx.close();
  }
}
await browser.close();
