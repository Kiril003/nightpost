type Json = Record<string, unknown>;

const DEFAULT_TONE = 'amber';

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v));
const has = (o: Json, k: string): boolean => Object.prototype.hasOwnProperty.call(o, k);

export function up(v1: Json): Json {
  const out: Json[] = [];
  const blocks = (v1.blocks ?? []) as Json[];
  blocks.forEach((block, idx) => {
    const t = block.type;
    if (t === 'title') {
      out.push({ id: `name-${idx}`, kind: 'id.name', variant: 'plain', hidden: false, data: { name: block.text } });
    } else if (t === 'about') {
      out.push({ id: `about-${idx}`, kind: 'id.about', variant: 'paragraph', hidden: false, data: { text: block.text } });
    } else if (t === 'address') {
      out.push({
        id: `address-${idx}`,
        kind: 'loc.address',
        variant: 'map_line',
        hidden: false,
        data: { text: block.text, point: { lat: block.lat, lon: block.lon } },
      });
    } else if (t === 'hours') {
      const rows = ((block.rows ?? []) as Json[]).map((r) => ({ days: [r.day], hours: { open: r.open, close: r.close } }));
      out.push({ id: `week-${idx}`, kind: 'hrs.week', variant: 'strip', hidden: false, data: { rows } });
    } else if (t === 'menu') {
      const data: Json = {};
      if (has(block, 'title')) data.title = block.title;
      data.token = block.token;
      data.items = clone(block.items ?? []);
      out.push({ id: `menu-${idx}`, kind: 'cat.menu', variant: 'two_col', hidden: false, data });
    } else if (t === 'event') {
      const data: Json = { title: block.title, starts: block.starts };
      if (has(block, 'ends')) data.ends = block.ends;
      if (has(block, 'note')) data.note = block.note;
      if (has(block, 'price') && has(block, 'token')) data.price = { amount: block.price, token: block.token };
      out.push({ id: block.id, kind: 'evt.single', variant: 'card', hidden: false, data });
    } else if (t === 'wallet') {
      out.push({ id: `wallet-${idx}`, kind: 'pay.wallet', variant: 'address_row', hidden: false, data: { address: block.address } });
    }
  });
  return { v: 2, tone: DEFAULT_TONE, blocks: out };
}

const isDict = (v: unknown): v is Json => typeof v === 'object' && v !== null && !Array.isArray(v);

export function down(v2: Json): Json {
  const out: Json[] = [];
  for (const block of (v2.blocks ?? []) as unknown[]) {
    if (!isDict(block)) continue;
    if (block.hidden) continue;
    if ((has(block, 'audience') ? block.audience : 'public') !== 'public') continue;
    const data = block.data;
    if (!isDict(data)) continue;
    const kind = block.kind;
    if (kind === 'id.name') {
      out.push({ type: 'title', text: data.name });
    } else if (kind === 'id.about') {
      out.push({ type: 'about', text: data.text });
    } else if (kind === 'loc.address') {
      const pt = data.point as Json;
      out.push({ type: 'address', text: data.text, lat: pt.lat, lon: pt.lon });
    } else if (kind === 'hrs.week') {
      const rows: Json[] = [];
      for (const r of (data.rows ?? []) as Json[]) {
        const hrs = r.hours as Json;
        for (const d of (r.days ?? []) as unknown[]) rows.push({ day: d, open: hrs.open, close: hrs.close });
      }
      out.push({ type: 'hours', rows });
    } else if (kind === 'cat.menu') {
      const m: Json = { type: 'menu' };
      if (has(data, 'title')) m.title = data.title;
      m.token = data.token;
      m.items = ((data.items ?? []) as Json[]).map((it) => {
        const price = it.price;
        const clean: Json = { id: it.id, name: it.name, price: isDict(price) ? price.amount : price };
        if (has(it, 'note')) clean.note = it.note;
        return clean;
      });
      out.push(m);
    } else if (kind === 'evt.single') {
      const ev: Json = { type: 'event', id: block.id, title: data.title, starts: data.starts };
      if (has(data, 'ends')) ev.ends = data.ends;
      if (has(data, 'note')) ev.note = data.note;
      if (isDict(data.price)) {
        ev.price = data.price.amount;
        ev.token = data.price.token;
      }
      out.push(ev);
    } else if (kind === 'pay.wallet') {
      out.push({ type: 'wallet', address: data.address });
    }
  }
  return { v: 1, blocks: out };
}
