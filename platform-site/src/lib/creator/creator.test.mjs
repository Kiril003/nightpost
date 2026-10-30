import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { D_COLOR, D_PERSON, D_RADIUS, LOOK, LOOKS, lookVars } from './looks.ts';
import { arrange, holes, SIGNATURE_KINDS } from './arrange.ts';
import { SAMPLES } from './samples.ts';
import { creatorCopy } from './format.ts';
import { pickWho, next, back, previewHref, startState, WHO, WHO_SAMPLE } from './start.ts';
import { REGISTRY } from '../blocks/registry.ts';
import { validatePageV2 } from '../blocks/validate.ts';
import { buildCopy } from '../../i18n/build.ts';
import { editorCopy } from '../../i18n/editor.ts';

const root = new URL('../../../', import.meta.url);
const tokens = JSON.parse(readFileSync(new URL('../docs/design/system/tokens.json', root), 'utf8'));

async function loadRenderer() {
  const out = await build({
    stdin: {
      contents: `
        import { createElement } from 'react';
        import { renderToStaticMarkup } from 'react-dom/server';
        import CreatorPage, { RENDERERS } from './src/components/creator/CreatorPage.tsx';
        export const render = (props) => renderToStaticMarkup(createElement(CreatorPage, props));
        export { RENDERERS };`,
      resolveDir: fileURLToPath(root),
      loader: 'tsx',
    },
    bundle: true,
    packages: 'external',
    write: false,
    format: 'esm',
    platform: 'node',
    jsx: 'automatic',
    loader: { '.css': 'empty', '.woff2': 'empty' },
    define: { 'import.meta.env': '{}' },
    logLevel: 'silent',
  });
  const dir = new URL('node_modules/.cache/creator-test/', root);
  mkdirSync(dir, { recursive: true });
  const file = new URL(`render-${process.pid}.mjs`, dir);
  writeFileSync(file, out.outputFiles[0].text);
  return import(file.href);
}

const UK = [0x0404, 0x0406, 0x0407, 0x0454, 0x0456, 0x0457, 0x0490, 0x0491, 0x0436, 0x0449];

function faces(entry) {
  const out = [];
  const src = readFileSync(new URL(entry, root), 'utf8');
  const sheets = [...src.matchAll(/^import '([^']+\.css|@fontsource[^']+)';$/gm)].map((m) => m[1]);
  for (const spec of sheets) {
    const file = spec.startsWith('.')
      ? new URL(spec, new URL(entry, root))
      : new URL(`node_modules/${spec}${spec.endsWith('.css') ? '' : '/index.css'}`, root);
    const css = readFileSync(file, 'utf8');
    for (const [, body] of css.matchAll(/@font-face\s*{([^}]*)}/g)) {
      const family = body.match(/font-family:\s*['"]?([^;'"]+)/)[1].trim();
      const range = body.match(/unicode-range:\s*([^;]+)/)?.[1] ?? '';
      const spans = range.split(',').filter(Boolean).map((r) => {
        const [a, b] = r.trim().replace(/^U\+/, '').split('-');
        return [parseInt(a, 16), parseInt(b ?? a, 16)];
      });
      out.push({ family, spans });
    }
  }
  return out;
}

const firstFamily = (stack) => stack.split(',')[0].trim().replace(/^["']|["']$/g, '');
const covers = (all, family, cp) => all.some((f) => f.family === family && f.spans.some(([a, b]) => cp >= a && cp <= b));

const shownIds = (page) => page.blocks.filter((b) => !b.hidden && !['pay.wallet', 'id.name'].includes(b.kind)).map((b) => b.id);

test('look colours are style D tokens, day and night', () => {
  for (const theme of ['day', 'night']) {
    for (const [k, v] of Object.entries(D_COLOR[theme])) assert.equal(v, tokens.color[theme][k].value, `${theme}.${k}`);
    D_PERSON[theme].forEach((t, i) => {
      assert.equal(t.fill, tokens.person[theme][i].fill);
      assert.equal(t.ink, tokens.person[theme][i].ink);
    });
  }
  for (const [k, v] of Object.entries(D_RADIUS)) if (k !== 'full') assert.equal(v, tokens.radius[k], k);
});

test('every look keeps its own type in Ukrainian: display faces load with Cyrillic from the page entry', () => {
  const ui = faces('src/main.tsx');
  for (const entry of ['src/pages/PublishedPage.tsx', 'src/pages/BuildStart.tsx', 'src/pages/BuildEditor.tsx']) {
    const all = [...faces(entry), ...ui];
    for (const l of LOOKS) {
      const fam = firstFamily(LOOK[l].display);
      assert.ok(covers(all, fam, 0x61), `${entry} ${l}: ${fam} not loaded`);
      for (const cp of UK) assert.ok(covers(all, fam, cp), `${entry} ${l}: ${fam} lacks U+${cp.toString(16).padStart(4, '0')}`);
    }
    const uiFam = firstFamily(lookVars('shop', 'day')['--cx-ui']);
    for (const cp of UK) assert.ok(covers(all, uiFam, cp), `${entry} ui: ${uiFam}`);
  }
});

test('three looks share every variable and differ only in type, chrome and spacing', () => {
  assert.deepEqual([...LOOKS], REGISTRY.vocab.look);
  const keys = (l, t) => Object.keys(lookVars(l, t)).sort();
  for (const l of LOOKS) for (const t of ['day', 'night']) assert.deepEqual(keys(l, t), keys('shop', 'day'));
  assert.equal(new Set(LOOKS.map((l) => LOOK[l].display)).size, 3);
  const radii = new Set([0, ...Object.values(D_RADIUS)]);
  for (const l of LOOKS) {
    assert.ok(radii.has(LOOK[l].cardRadius) && radii.has(LOOK[l].imageRadius), l);
    for (const t of ['day', 'night']) {
      const v = lookVars(l, t);
      assert.equal(v['--cx-accent'], D_COLOR[t].accent);
      assert.equal(v['--cx-text'], D_COLOR[t].text);
    }
  }
});

test('arrange places every visible block exactly once in every look', () => {
  for (const s of SAMPLES) {
    for (const look of LOOKS) {
      const seen = [];
      for (const sec of arrange(s.page, look)) {
        if (sec.type === 'tiles') seen.push(...sec.blocks.map((t) => t.block.id));
        else seen.push(sec.block.id, ...(sec.type === 'block' ? sec.with.map((b) => b.id) : []));
      }
      assert.deepEqual([...seen].sort(), shownIds(s.page).sort(), `${s.handle} ${look}`);
    }
  }
  const alma = SAMPLES.find((s) => s.handle === 'alma-brandt').page;
  const shopTiles = arrange(alma, 'shop').find((x) => x.type === 'tiles').blocks.map((t) => t.block.kind);
  assert.deepEqual(shopTiles, ['deal.drop', 'com.reviews', 'loc.meet_point', 'loc.message', 'pay.qr']);
  const journalDrop = arrange(alma, 'journal').find((x) => x.type === 'block' && x.block.kind === 'deal.drop');
  assert.deepEqual(journalDrop.with.map((b) => b.kind), ['cat.products']);
  const hidden = structuredClone(alma);
  hidden.blocks.find((b) => b.kind === 'com.reviews').hidden = true;
  assert.ok(arrange(hidden, 'studio').every((x) => x.type !== 'block' || x.block.kind !== 'com.reviews'));
});

test('every sample page validates and renders in all three looks, day and night', async () => {
  const { render, RENDERERS } = await loadRenderer();
  for (const kind of SIGNATURE_KINDS) assert.equal(typeof RENDERERS[kind], 'function', kind);
  for (const s of SAMPLES) {
    for (const look of LOOKS) {
      const page = { ...structuredClone(s.page), look };
      const v = validatePageV2(page, REGISTRY, { publish: true });
      assert.equal(v.ok, true, `${s.handle} ${look} ${v.refused} ${v.at}`);
      for (const theme of ['day', 'night']) {
        const html = render({ page, live: s.live, look, theme, lang: s.lang ?? 'en', sample: true });
        assert.match(html, new RegExp(`data-look="${look}" data-theme="${theme}"`));
        const rendered = new Set([...html.matchAll(/data-block="([^"]+)"/g)].flatMap((m) => m[1].split(' ')));
        for (const id of shownIds(page)) assert.ok(rendered.has(id), `${s.handle} ${look} ${theme}: ${id}`);
        const amber = (html.match(/class="cx-btn primary/g) ?? []).length;
        assert.ok(amber <= (look === 'shop' ? 2 : 1), `${s.handle} ${look}: ${amber} amber buttons`);
        assert.doesNotMatch(html, /Phantom|lorem/i);
      }
    }
  }
});

test('a published page with opening hours offers real times; a booking never invents them', async () => {
  const { render } = await loadRenderer();
  const alma = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  alma.blocks.push({ id: 'week-20', kind: 'hrs.week', variant: 'strip', hidden: false, data: { rows: [{ days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], hours: { open: '10:00', close: '18:00' } }] } });
  const html = render({ page: alma, live: { network: 'devnet' }, look: 'studio', theme: 'day', lang: 'en', venueId: 'abcdef0123456789' });
  assert.match(html, /class="cx-time[^"]*"[^>]*>10:00</);
  assert.match(html, /Request [A-Z][a-z]{2} \d+ · \d\d:\d\d/);
  assert.match(html, /The reply comes to your contact/);
  assert.doesNotMatch(html, /No set hours yet|Ask for a time|Confirmed in your chat/);
});

test('a service without a photo shows no picture slot on the public page, but keeps it in the editor preview', async () => {
  const { render } = await loadRenderer();
  const alma = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  for (const it of alma.blocks.find((b) => b.kind === 'cat.services').data.items) delete it.image;
  const svcPart = (html) => html.slice(html.indexOf('cx-svcs'), html.indexOf('cx-booking'));
  const page = render({ page: alma, live: { network: 'devnet' }, look: 'studio', theme: 'day', lang: 'en', venueId: 'abcdef0123456789' });
  assert.doesNotMatch(svcPart(page), /cx-th|cx-img-empty/);
  assert.match(svcPart(page), /cx-svc noimg/);
  const preview = render({ page: alma, live: { network: 'devnet' }, look: 'studio', theme: 'day', lang: 'en', frame: 'preview' });
  assert.match(svcPart(preview), /cx-img-empty/);
});

test('a published page without live data shows honest empty states', async () => {
  const { render } = await loadRenderer();
  const alma = SAMPLES.find((s) => s.handle === 'alma-brandt');
  for (const look of LOOKS) {
    const html = render({ page: alma.page, live: { network: 'devnet' }, look, theme: 'day', lang: 'en' });
    assert.match(html, /No reviews yet/);
    assert.match(html, /No set hours yet/);
    assert.match(html, /Ask for a time/);
    assert.doesNotMatch(html, /No free times yet/);
    assert.doesNotMatch(html, />4\.9<|>14 <|14 of 40 left/);
    assert.match(html, /Test payments/);
  }
});

test('onboarding: who picks a sample and a starting look, preview opens it', () => {
  let s = startState();
  assert.equal(s.step, 1);
  for (const w of WHO) {
    s = pickWho(s, w);
    assert.ok(SAMPLES.some((x) => x.handle === WHO_SAMPLE[w]), w);
    assert.ok(LOOKS.includes(s.look));
  }
  s = next(pickWho(startState(), 'group'));
  assert.equal(s.step, 2);
  assert.equal(previewHref({ ...s, look: 'journal' }, 'night'), '/p/marigold-hours?look=journal&theme=night');
  assert.equal(back(s).step, 1);
  assert.equal(previewHref({ ...pickWho(startState(), 'me'), look: 'studio' }, 'day', 'uk'), '/p/olha-melnyk?look=studio&theme=day');
  assert.equal(previewHref({ ...pickWho(startState(), 'me'), look: 'studio' }, 'day', 'en'), '/p/alma-brandt?look=studio&theme=day');
});

test('a Ukrainian page renders its own words in every look', async () => {
  const { render } = await loadRenderer();
  const uk = SAMPLES.filter((s) => s.lang === 'uk');
  assert.ok(uk.length >= 1);
  for (const s of uk) {
    for (const look of LOOKS) {
      const html = render({ page: s.page, live: s.live, look, theme: 'night', lang: 'uk', sample: true });
      const hero = s.page.blocks.find((b) => b.kind === 'id.hero').data.headline;
      assert.ok(html.includes(hero), `${s.handle} ${look}`);
      assert.doesNotMatch(html, /Sample page|Made on|Reviews</, `${s.handle} ${look} chrome is English`);
    }
  }
});

test('the Builder pages never say the old code name', () => {
  for (const lang of ['en', 'uk']) {
    assert.doesNotMatch(JSON.stringify(buildCopy[lang]), /phantom/i, lang);
    assert.doesNotMatch(JSON.stringify(editorCopy[lang]), /phantom|lorem/i, lang);
  }
  for (const f of ['src/pages/Build.tsx', 'src/pages/BuildStart.tsx', 'src/pages/PublishedPage.tsx', 'src/pages/BuildEditor.tsx', 'src/components/creator/EditorPanels.tsx']) {
    assert.doesNotMatch(readFileSync(new URL(f, root), 'utf8').replace(/^import .*$/gm, ''), /phantom/i, f);
  }
});

test('the page honours what the editor sets: tile size, who can book, where a block shows, uploaded images', async () => {
  const { render } = await loadRenderer();
  const alma = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  const tile = (html, id) => html.match(new RegExp(`<div id="${id}" data-block="${id}"[^>]*class="([^"]*)"`))?.[1] ?? '';

  const sized = structuredClone(alma);
  sized.blocks.find((b) => b.id === 'reviews-3').size = '2x2';
  sized.blocks.find((b) => b.id === 'drop-2').size = '1x1';
  sized.blocks.find((b) => b.id === 'qr-6').size = '2x1';
  const tiles = arrange(sized, 'shop').find((x) => x.type === 'tiles').blocks;
  assert.deepEqual(tiles.map((t) => [t.block.id, t.wide, Boolean(t.tall)]).filter(([id]) => ['reviews-3', 'drop-2', 'qr-6'].includes(id)), [
    ['drop-2', false, false], ['reviews-3', true, true], ['qr-6', true, false],
  ]);
  const html = render({ page: sized, live: {}, look: 'shop', theme: 'day', lang: 'en' });
  assert.match(tile(html, 'reviews-3'), /\bw2\b.*\bh2\b/);
  assert.doesNotMatch(tile(html, 'drop-2'), /\bw2\b/);

  const booking = (page) => render({ page, live: {}, look: 'studio', theme: 'day', lang: 'en' });
  const slots = (p) => p.blocks.find((b) => b.kind === 'book.slots');
  const hidden = structuredClone(alma);
  slots(hidden).data.access = [{ circle: 'close', mode: 'book' }, { circle: 'everyone', mode: 'hidden' }];
  assert.doesNotMatch(booking(hidden), /data-kind="book.slots"/);
  const request = structuredClone(alma);
  slots(request).data.auto = true;
  slots(request).data.access = [{ circle: 'friends', mode: 'book' }, { circle: 'everyone', mode: 'request' }];
  assert.match(booking(request), /data-kind="book.slots"/);
  assert.match(booking(request), /Ask for a time/);
  assert.equal(validatePageV2(request, REGISTRY, { publish: true }).ok, true);

  const offSite = structuredClone(alma);
  offSite.blocks.find((b) => b.id === 'meet-4').on = ['A', 'M'];
  assert.doesNotMatch(render({ page: offSite, live: {}, look: 'shop', theme: 'day', lang: 'en' }), /data-block="meet-4"/);

  const own = structuredClone(alma);
  own.blocks.find((b) => b.kind === 'id.hero').data.image = { blob: 'p0123456789abcdef0123456789abcdef', w: 10, h: 10, alt: 'Cup' };
  assert.match(render({ page: own, live: {}, look: 'journal', theme: 'day', lang: 'en' }), /src="https:\/\/[^"]+\/venue-media\/p0123456789abcdef0123456789abcdef" alt="Cup"/);
});

test('a tile can take a whole row; a lone sized tile keeps its own size instead of stretching', async () => {
  const { render } = await loadRenderer();
  const alma = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  const tile = (html, id) => html.match(new RegExp(`<div id="${id}" data-block="${id}"[^>]*class="([^"]*)"`))?.[1] ?? '';
  assert.deepEqual(REGISTRY.vocab.size, ['1x1', '2x1', '2x2', '3x1']);
  const row = structuredClone(alma);
  row.blocks.find((b) => b.id === 'drop-2').size = '3x1';
  assert.equal(validatePageV2(row, REGISTRY, { publish: true }).ok, true);
  const t = arrange(row, 'shop').find((x) => x.type === 'tiles').blocks.find((b) => b.block.id === 'drop-2');
  assert.deepEqual([t.wide, Boolean(t.tall), Boolean(t.full)], [true, false, true]);
  const html = render({ page: row, live: {}, look: 'shop', theme: 'day', lang: 'en' });
  assert.match(tile(html, 'drop-2'), /\bw3\b/);
  assert.match(html, /class="cx-dropcard s2x1"/);
  const css = readFileSync(new URL('src/styles/creator.css', root), 'utf8');
  assert.doesNotMatch(css, /:only-child\s*\{\s*grid-column:\s*1\s*\/\s*-1/);
});

const text = (html) => html.replace(/<[^>]+>/g, ' ').replace(/&#x27;|&quot;|&amp;/g, ' ');

function dropPage({ size, images = true, tone } = {}) {
  const alma = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  const keep = new Set(['id.name', 'deal.drop', 'cat.products']);
  alma.blocks = alma.blocks.filter((b) => keep.has(b.kind));
  const drop = alma.blocks.find((b) => b.kind === 'deal.drop');
  drop.data.total = 37;
  drop.data.closes = '2026-11-01T18:00';
  delete drop.data.note;
  if (size) drop.size = size;
  if (tone) drop.tone = tone;
  const shop = alma.blocks.find((b) => b.kind === 'cat.products');
  shop.data.items.forEach((p, i) => {
    p.stock = 3;
    p.price = String(20 + i);
    if (!images) delete p.image;
  });
  return { page: alma, drop, shop };
}

test('a drop says its count once, with or without live numbers, in every look and size', async () => {
  const { render } = await loadRenderer();
  for (const look of LOOKS) {
    for (const size of [undefined, '1x1', '2x1', '2x2']) {
      const { page } = dropPage({ size });
      const html = render({ page, live: { network: 'devnet' }, look, theme: 'day', lang: 'en' });
      assert.equal((text(html).match(/\b37\b/g) ?? []).length, 1, `${look} ${size}: ${text(html).match(/.{0,30}\b37\b.{0,30}/g)}`);
    }
  }
});

test('a drop card carries the remaining count, the bar, a product picture, the closing time and the stage', async () => {
  const { render } = await loadRenderer();
  for (const size of ['1x1', '2x1', '2x2']) {
    const { page, drop } = dropPage({ size });
    const live = { network: 'devnet', at: '2026-10-22T09:00', [drop.id]: { left: 14 } };
    const html = render({ page, live, look: 'shop', theme: 'day', lang: 'en' });
    const t = text(html);
    assert.match(t, /\b14\b\s+of 37 left/, size);
    assert.match(t, /Closes Sun 1 Nov · 18:00/, size);
    assert.match(t, /Friends · now/, size);
    assert.match(html, /class="cx-bar"[^>]*><b style="width:62\.\d+%"/, size);
    if (size !== '1x1') assert.match(html, /class="cx-drop-img"[^]*?src="\/creator\/sample-mug-01\.webp"/, size);
  }
  const bare = dropPage({ size: '2x1', images: false });
  const html = render({ page: bare.page, live: { network: 'devnet' }, look: 'shop', theme: 'day', lang: 'en' });
  assert.match(html, /class="cx-drop-img"[^>]*>\s*<div class="cx-img cx-img-none/);
});

test('a product without a picture shows an honest empty tile with its name', async () => {
  const { render } = await loadRenderer();
  const { page, shop } = dropPage({ images: false });
  for (const look of LOOKS) {
    const html = render({ page, live: {}, look, theme: 'night', lang: 'en' });
    for (const p of shop.data.items) {
      assert.match(html, new RegExp(`class="cx-img cx-img-none cx-img-empty[^"]*"[^>]*>(<svg[^]*?</svg>)?<span>${p.name}</span>`), `${look} ${p.name}`);
    }
  }
});

test('a block tone paints its card in every look, day and night', async () => {
  const { render } = await loadRenderer();
  for (const tone of REGISTRY.vocab.tone) {
    for (const look of LOOKS) {
      for (const size of [undefined, '2x1']) {
        const { page, drop } = dropPage({ tone, size });
        page.blocks.find((b) => b.kind === 'cat.products').tone = tone;
        for (const theme of ['day', 'night']) {
          const html = render({ page, live: {}, look, theme, lang: 'en' });
          assert.match(html, new RegExp(`data-block="${drop.id}[^"]*"[^>]*data-tone="${tone}"|data-tone="${tone}"[^>]*data-block="${drop.id}`), `${tone} ${look} ${size} ${theme}`);
        }
      }
    }
  }
  const css = readFileSync(new URL('src/styles/creator.css', root), 'utf8');
  for (const tone of REGISTRY.vocab.tone) assert.match(css, new RegExp(`\\[data-tone='${tone}'\\]`), tone);
});

test('an image focus point becomes object-position wherever the image is drawn', async () => {
  const { render } = await loadRenderer();
  const { page, shop } = dropPage({ size: '2x1' });
  const hero = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page.blocks.find((b) => b.kind === 'id.hero'));
  delete hero.data.primary;
  delete hero.data.secondary;
  hero.data.image.focus = { x: 0.25, y: 0.8 };
  page.blocks.splice(1, 0, hero);
  shop.data.items[0].image.focus = { x: 1, y: 0 };
  assert.equal(validatePageV2(page, REGISTRY).ok, true);
  for (const look of LOOKS) {
    const html = render({ page, live: {}, look, theme: 'day', lang: 'en' });
    assert.match(html, /<img[^>]*style="object-position:25% 80%"[^>]*alt="[^"]*"/, `${look} hero`);
    const mug = [...html.matchAll(/<img[^>]*src="\/creator\/sample-mug-01\.webp"[^>]*>/g)].map((m) => m[0]);
    assert.ok(mug.length >= 1 && mug.every((m) => m.includes('object-position:100% 0%')), `${look} products ${mug}`);
  }
});

test('a visitor can follow a published page; the editor canvas and samples have no follow button', async () => {
  const { render } = await loadRenderer();
  const alma = SAMPLES.find((s) => s.handle === 'alma-brandt');
  const follow = /<a class="cx-btn ghost m[^"]*" href="\/account">.*?Follow<\/a>/;
  for (const look of LOOKS) {
    assert.match(render({ page: alma.page, live: {}, look, theme: 'day', lang: 'en', venueId: 'v-1' }), follow, look);
    assert.doesNotMatch(render({ page: alma.page, live: {}, look, theme: 'day', lang: 'en', venueId: 'v-1', frame: 'preview' }), /Follow/, look);
    assert.doesNotMatch(render({ page: alma.page, live: {}, look, theme: 'day', lang: 'en', venueId: 'v-1', sample: true }), /Follow/, look);
  }
  assert.match(render({ page: alma.page, live: {}, look: 'shop', theme: 'day', lang: 'uk', venueId: 'v-1' }), /Стежити/);
});

test('a preview has no live share button, so the canvas never shares the editor address', async () => {
  const { render } = await loadRenderer();
  const alma = SAMPLES.find((s) => s.handle === 'alma-brandt');
  const share = /<button[^>]*aria-label="Share"/;
  for (const look of LOOKS) {
    assert.match(render({ page: alma.page, live: {}, look, theme: 'day', lang: 'en' }), share, look);
    assert.doesNotMatch(render({ page: alma.page, live: {}, look, theme: 'day', lang: 'en', frame: 'preview' }), share, look);
  }
});

test('tiles pack without holes beside a big tile', async () => {
  const { render } = await loadRenderer();
  const { page } = dropPage({ size: '2x2' });
  const alma = SAMPLES.find((s) => s.handle === 'alma-brandt').page;
  page.blocks.push(...structuredClone(alma.blocks.filter((b) => ['com.reviews', 'loc.meet_point', 'loc.message'].includes(b.kind))));
  page.blocks = [page.blocks[0], ...page.blocks.filter((b) => b.kind !== 'id.name' && b.kind !== 'cat.products'), page.blocks.find((b) => b.kind === 'cat.products')];
  const run = arrange(page, 'shop').find((x) => x.type === 'tiles').blocks;
  const cells = (cols) => run.map((t) => ({ w: t.full ? cols : t.wide ? 2 : 1, h: t.tall ? 2 : 1 }));
  const why = JSON.stringify(run.map((t) => [t.block.kind, t.wide, t.tall, t.full]));
  assert.equal(run.length, 4);
  assert.equal(holes(cells(3), 3), 0, why);
  assert.equal(holes(cells(2), 2), 0, why);
  const three = arrange(structuredClone(alma), 'journal').find((x) => x.type === 'tiles').blocks;
  assert.deepEqual(three.map((t) => [t.wide, !!t.full]), [[false, false], [false, false], [false, false]], 'three small tiles stay one row of three');
  const html = render({ page, look: 'shop', theme: 'day', lang: 'en' });
  assert.match(html, /cx-tile[^"]*\bw3\b[^"]*" data-kind="loc.message"|data-kind="loc.message"[^>]*class="[^"]*\bw3\b/);
});

test('the going count reads right in Ukrainian', () => {
  const g = creatorCopy('uk').going;
  assert.deepEqual([1, 2, 5, 11, 21, 64].map(g), ['1 йде', '2 йдуть', '5 йдуть', '11 йдуть', '21 йде', '64 йдуть']);
});
