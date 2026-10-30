import type { FieldSpec, PrimitiveSpec, Registry } from './registry.ts';

type Json = Record<string, any>;
type Ctx = {
  registry: Registry;
  blockData: Json;
  records: Record<string, Record<string, FieldSpec>>;
  itemIds: Set<string>;
  pendingRefs: Array<[string, FieldSpec, string]>;
};
type Prim = (val: any, fspec: FieldSpec, pspec: any, ctx: Ctx, at: string) => any;

export type Verdict =
  | {
      ok: true;
      page: Json;
      visibility: string;
      name: string | null;
      location: { address: string; lat: number; lon: number } | null;
      token: string | null;
      menu_version: string | null;
      wallet: string | null;
    }
  | { ok: false; refused: string; at: string };

class Refused extends Error {
  reason: string;
  at: string;
  constructor(reason: string, at = '') {
    super(reason);
    this.reason = reason;
    this.at = at;
  }
}

const MAX_ITEMS = 200;
const MAX_INVITE = 256;
const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const WS = '\\t\\n\\v\\f\\r\\x1c-\\x1f \\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000';

const ID_RE = /^[A-Za-z0-9_-]{1,36}$/;
const VENUE_REF_RE = /^[A-Za-z0-9_-]{1,64}$/;
const BLOB_RE = /^[A-Za-z0-9_-]{8,64}$/;
const CONTACT_RE = /^[A-Za-z0-9_+/=-]{16,128}$/;
const HHMM_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const DATE_RE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
const DATETIME_RE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T([01]\d|2[0-3]):[0-5]\d$/;
const PHONE_RE = /^\+[1-9]\d{6,14}$/;
const EMAIL_RE = new RegExp(`^[^${WS}@]+@[^${WS}@]+\\.[^${WS}@]+$`);
const MD_LINK_RE = /\[[^\]]*\]\(([^)]+)\)/g;
const PUBKEY_RE = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const CTRL_RE = /^[\p{Cc}\p{Cf}]$/u;
const B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

const has = (o: object, k: string): boolean => Object.prototype.hasOwnProperty.call(o, k);
const isDict = (v: unknown): v is Json => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const isNum = (v: unknown): v is number => typeof v === 'number';
const len = (s: string): number => [...s].length;
const keys = (o: Json): string[] => Object.keys(o);
const sameKeys = (o: Json, want: string[]): boolean => {
  const k = keys(o);
  return k.length === want.length && want.every((w) => has(o, w));
};
const extraKeys = (o: Json, allowed: string[]): string[] => keys(o).filter((k) => !allowed.includes(k)).sort(cmp);
const missingKeys = (o: Json, req: string[]): string[] => req.filter((k) => !has(o, k)).sort(cmp);
const hasCtrl = (s: string): boolean => [...s].some((c) => CTRL_RE.test(c));
const cmp = (a: string, b: string): number => {
  const x = [...a].map((c) => c.codePointAt(0)!);
  const y = [...b].map((c) => c.codePointAt(0)!);
  for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i];
  return x.length - y.length;
};

const pyStrip = (s: string): string => s.replace(new RegExp(`^[${WS}]+|[${WS}]+$`, 'g'), '');

function refusalFor(f: FieldSpec, p: any, def: string): string {
  return f.refusal || p.refusal || def;
}

function cleanStr(value: unknown, max: number, at: string, multiline: boolean, refusal: string): string {
  if (typeof value !== 'string') throw new Refused(refusal, at);
  const text = pyStrip(value);
  const bad = [...text].some((c) => CTRL_RE.test(c) && !(multiline && c === '\n'));
  if (!text || len(text) > max || bad) throw new Refused(refusal, at);
  return text;
}

function httpsUrl(url: unknown, max: number, at: string, refusal: string): string {
  if (typeof url !== 'string' || len(url) > max || hasCtrl(url)) throw new Refused(refusal, at);
  if (!url.startsWith('https://')) throw new Refused(refusal, at);
  const rest = url.slice(8);
  const netloc = rest.split(/[/?#]/, 1)[0];
  let credentials = false;
  if (netloc.includes('@')) {
    const userinfo = netloc.slice(0, netloc.lastIndexOf('@'));
    const colon = userinfo.indexOf(':');
    credentials = Boolean(colon < 0 ? userinfo : userinfo.slice(0, colon)) || (colon >= 0 && Boolean(userinfo.slice(colon + 1)));
  }
  if (!netloc || credentials || url.includes(' ')) throw new Refused(refusal, at);
  return url;
}

function daysFromCivil(y: number, m: number, d: number): number {
  const yy = m <= 2 ? y - 1 : y;
  const era = Math.floor(yy / 400);
  const yoe = yy - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

function calendarDate(s: unknown, at: string, refusal: string): number {
  if (typeof s !== 'string' || !DATE_RE.test(s)) throw new Refused(refusal, at);
  const y = Number(s.slice(0, 4));
  const m = Number(s.slice(5, 7));
  const d = Number(s.slice(8, 10));
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  const dim = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1];
  if (y < 1 || d > dim) throw new Refused(refusal, at);
  return daysFromCivil(y, m, d);
}

type Dec = { n: bigint; s: number };

function dec(raw: string | number): Dec {
  const m = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(String(raw));
  if (!m) throw new Error('decimal');
  const frac = m[3] ?? '';
  let n = BigInt((m[2] || '0') + frac);
  let s = frac.length - Number(m[4] ?? 0);
  if (s < 0) {
    n *= 10n ** BigInt(-s);
    s = 0;
  }
  return { n: m[1] === '-' ? -n : n, s };
}

function align(a: Dec, b: Dec): [bigint, bigint] {
  const s = Math.max(a.s, b.s);
  return [a.n * 10n ** BigInt(s - a.s), b.n * 10n ** BigInt(s - b.s)];
}

const decCmp = (a: Dec, b: Dec): number => {
  const [x, y] = align(a, b);
  return x < y ? -1 : x > y ? 1 : 0;
};

const text: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_text');
  const max = Number(f.max ?? p.max ?? 200);
  if (ref === 'bad_item_id') {
    if (typeof val !== 'string' || !ID_RE.test(val)) throw new Refused('bad_item_id', at);
    return val;
  }
  return cleanStr(val, max, at, false, ref);
};

const richtext: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_text');
  const max = Number(f.max ?? p.max ?? 4000);
  const t = cleanStr(val, max, at, true, ref);
  for (const m of t.matchAll(MD_LINK_RE)) httpsUrl(pyStrip(m[1]), 300, at, ref);
  return t;
};

const number: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_number');
  let num: Dec;
  if (typeof val === 'boolean') throw new Refused(ref, at);
  if (f.int) {
    if (!isInt(val)) throw new Refused(ref, at);
    num = dec(val);
  } else if (isInt(val)) {
    num = dec(val);
  } else if (typeof val === 'string') {
    if (!/^-?(0|[1-9]\d*)(\.\d+)?$/.test(val)) throw new Refused(ref, at);
    num = dec(val);
  } else if (isNum(val)) {
    num = dec(val);
  } else throw new Refused(ref, at);
  const min = f.min ?? p.min;
  const max = f.max ?? p.max;
  const step = f.step ?? p.step;
  if (min != null && decCmp(num, dec(min)) < 0) throw new Refused(ref, at);
  if (max != null && decCmp(num, dec(max)) > 0) throw new Refused(ref, at);
  if (step != null) {
    const base = min != null ? dec(min) : dec(0);
    const [x, y] = align({ n: num.n, s: num.s }, base);
    const [d, st] = align({ n: x - y, s: Math.max(num.s, base.s) }, dec(step));
    if (d % st !== 0n) throw new Refused(ref, at);
  }
  return val;
};

const percent: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_percent');
  if (typeof val === 'boolean' || !isInt(val) || val < 0 || val > 100) throw new Refused(ref, at);
  const step = Number(f.step ?? p.step ?? 1);
  if (![1, 5].includes(step) || val % step !== 0) throw new Refused(ref, at);
  return val;
};

function amountStr(amount: unknown, decimals: number, at: string, refusal: string): string {
  const re = new RegExp(`^(0|[1-9]\\d{0,8})(\\.\\d{1,${decimals}})?$`);
  if (typeof amount !== 'string' || !re.test(amount) || !/[1-9]/.test(amount)) throw new Refused(refusal, at);
  return amount;
}

const money: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_price');
  const dmap: Record<string, number> = p.decimals ?? { USDC: 6, SOL: 9, EURC: 6 };
  if (f.token === '$token') {
    const parent = ctx.blockData.token;
    if (typeof parent !== 'string' || !has(dmap, parent)) throw new Refused('bad_token', at);
    if (typeof val === 'string') return amountStr(val, dmap[parent], at, ref);
    if (isDict(val)) {
      if (!sameKeys(val, ['amount', 'token'])) {
        throw new Refused(extraKeys(val, ['amount', 'token']).length ? 'unknown_field' : 'missing_field', at);
      }
      if (val.token !== parent) throw new Refused('mixed_tokens', `${at}.token`);
      amountStr(val.amount, dmap[parent], `${at}.amount`, ref);
      return { amount: val.amount, token: val.token };
    }
    throw new Refused(ref, at);
  }
  if (!isDict(val)) throw new Refused(ref, at);
  const extra = extraKeys(val, ['amount', 'token']);
  if (extra.length) throw new Refused('unknown_field', `${at}.${extra[0]}`);
  const missing = missingKeys(val, ['amount', 'token']);
  if (missing.length) throw new Refused('missing_field', `${at}.${missing[0]}`);
  const tok = val.token;
  if (typeof tok !== 'string' || !has(dmap, tok)) throw new Refused('bad_token', `${at}.token`);
  const amt = amountStr(val.amount, dmap[tok], `${at}.amount`, ref);
  return { amount: amt, token: tok };
};

const token: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_token');
  const allowed = ctx.registry.vocab.token ?? ['USDC', 'SOL', 'EURC'];
  if (typeof val !== 'string' || !allowed.includes(val)) throw new Refused(ref, at);
  return val;
};

const time: Prim = (val, f, p, _ctx, at) => {
  if (typeof val !== 'string' || !HHMM_RE.test(val)) throw new Refused(refusalFor(f, p, 'bad_time'), at);
  return val;
};

const timerange: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_timerange');
  if (!isDict(val) || !sameKeys(val, ['open', 'close'])) throw new Refused(ref, at);
  const { open, close } = val;
  if (!(typeof open === 'string' && HHMM_RE.test(open) && typeof close === 'string' && HHMM_RE.test(close))) {
    throw new Refused(ref, at);
  }
  return { open, close };
};

const duration: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_duration');
  const min = Number(f.min ?? p.min ?? 5);
  const max = Number(f.max ?? p.max ?? 1440);
  const step = Number(f.step ?? p.step ?? 5);
  if (typeof val === 'boolean' || !isInt(val) || val < min || val > max || val % step !== 0) throw new Refused(ref, at);
  return val;
};

const date: Prim = (val, f, p, _ctx, at) => {
  calendarDate(val, at, refusalFor(f, p, 'bad_date'));
  return val;
};

const daterange: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_daterange');
  if (!isDict(val) || !sameKeys(val, ['from', 'to'])) throw new Refused(ref, at);
  const from = calendarDate(val.from, `${at}.from`, ref);
  const to = calendarDate(val.to, `${at}.to`, ref);
  const maxDays = Number(f.max_days ?? p.max_days ?? 366);
  const delta = to - from;
  if (delta < 0 || delta > maxDays) throw new Refused(ref, at);
  return { from: val.from, to: val.to };
};

const datetime: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_datetime');
  if (typeof val !== 'string' || !DATETIME_RE.test(val)) throw new Refused(ref, at);
  calendarDate(val.slice(0, 10), at, ref);
  return val;
};

const dayset: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_dayset');
  const vocab = ctx.registry.vocab.day ?? DAYS;
  if (!Array.isArray(val) || val.length < 1 || val.length > 7) throw new Refused(ref, at);
  if (new Set(val).size !== val.length || val.some((d) => typeof d !== 'string' || !vocab.includes(d))) {
    throw new Refused(ref, at);
  }
  return [...val];
};

const recurrence: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_recurrence');
  if (!isDict(val)) throw new Refused(ref, at);
  if (extraKeys(val, ['freq', 'days', 'nth', 'until', 'count', 'skip']).length || !has(val, 'freq')) {
    throw new Refused(ref, at);
  }
  const freq = val.freq;
  if (freq !== 'weekly' && freq !== 'monthly') throw new Refused(ref, at);
  const maxOcc = Number(p.max_occurrences ?? 52);
  const out: Json = { freq };
  if (has(val, 'days')) out.days = dayset(val.days, {} as FieldSpec, ctx.registry.primitives.dayset, ctx, `${at}.days`);
  if (has(val, 'nth')) {
    if (typeof val.nth === 'boolean' || !isInt(val.nth) || ![1, 2, 3, 4, -1].includes(val.nth)) {
      throw new Refused(ref, `${at}.nth`);
    }
    out.nth = val.nth;
  }
  if (freq === 'weekly' && !has(out, 'days')) throw new Refused(ref, at);
  if (freq === 'monthly' && !has(out, 'nth') && !has(out, 'days')) throw new Refused(ref, at);
  if (has(val, 'until')) {
    calendarDate(val.until, `${at}.until`, ref);
    out.until = val.until;
  }
  if (has(val, 'count')) {
    const c = val.count;
    if (typeof c === 'boolean' || !isInt(c) || c < 1 || c > maxOcc) throw new Refused(ref, `${at}.count`);
    out.count = c;
  }
  if (!has(out, 'until') && !has(out, 'count')) throw new Refused(ref, at);
  if (has(val, 'skip')) {
    const skips = val.skip;
    if (!Array.isArray(skips) || skips.length > maxOcc) throw new Refused(ref, `${at}.skip`);
    const clean: string[] = [];
    skips.forEach((s, idx) => {
      const days = calendarDate(s, `${at}.skip[${idx}]`, ref);
      if (has(out, 'until') && s > out.until) throw new Refused(ref, `${at}.skip[${idx}]`);
      if (has(out, 'days')) {
        const wday = DAYS[(((days + 3) % 7) + 7) % 7];
        if (!out.days.includes(wday)) throw new Refused(ref, `${at}.skip[${idx}]`);
      }
      clean.push(s);
    });
    out.skip = clean;
  }
  return out;
};

const image: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_image');
  if (!isDict(val)) throw new Refused(ref, at);
  if (extraKeys(val, ['blob', 'w', 'h', 'alt', 'focus', 'bytes']).length || missingKeys(val, ['blob', 'w', 'h', 'alt']).length) {
    throw new Refused(ref, at);
  }
  if (typeof val.blob !== 'string' || !BLOB_RE.test(val.blob)) throw new Refused(ref, `${at}.blob`);
  if ([val.w, val.h].some((x) => typeof x === 'boolean' || !isInt(x) || x < 1 || x > 16384)) throw new Refused(ref, at);
  const alt = val.alt;
  if (typeof alt !== 'string' || len(alt) > Number(p.max_alt ?? 120)) throw new Refused(ref, `${at}.alt`);
  if (hasCtrl(alt)) throw new Refused(ref, `${at}.alt`);
  const out: Json = { blob: val.blob, w: val.w, h: val.h, alt: pyStrip(alt) };
  if (has(val, 'focus')) {
    const foc = val.focus;
    if (!isDict(foc) || !sameKeys(foc, ['x', 'y'])) throw new Refused(ref, `${at}.focus`);
    for (const k of ['x', 'y']) {
      if (typeof foc[k] === 'boolean' || !isNum(foc[k]) || foc[k] < 0 || foc[k] > 1) throw new Refused(ref, `${at}.focus.${k}`);
    }
    out.focus = { x: foc.x, y: foc.y };
  }
  if (has(val, 'bytes')) {
    const b = val.bytes;
    if (typeof b === 'boolean' || !isInt(b) || b < 1 || b > Number(p.max_bytes ?? 8_388_608)) throw new Refused(ref, `${at}.bytes`);
    out.bytes = b;
  }
  return out;
};

const gallery: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_gallery');
  const min = Number(f.min ?? p.min ?? 1);
  const max = Number(f.max ?? p.max ?? 24);
  if (!Array.isArray(val) || val.length < min || val.length > max) throw new Refused(ref, at);
  const img = ctx.registry.primitives.image;
  return val.map((item, i) => image(item, {} as FieldSpec, img, ctx, `${at}[${i}]`));
};

const video: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_video');
  if (!isDict(val)) throw new Refused(ref, at);
  if (extraKeys(val, ['blob', 'poster', 'secs', 'bytes']).length || missingKeys(val, ['blob', 'poster', 'secs']).length) {
    throw new Refused(ref, at);
  }
  if (typeof val.blob !== 'string' || !BLOB_RE.test(val.blob)) throw new Refused(ref, `${at}.blob`);
  const secs = val.secs;
  if (typeof secs === 'boolean' || !isNum(secs) || !(secs > 0 && secs <= Number(p.max_secs ?? 60))) {
    throw new Refused(ref, `${at}.secs`);
  }
  const poster = image(val.poster, { refusal: ref } as FieldSpec, ctx.registry.primitives.image, ctx, `${at}.poster`);
  const out: Json = { blob: val.blob, poster, secs };
  if (has(val, 'bytes')) {
    const b = val.bytes;
    if (typeof b === 'boolean' || !isInt(b) || b < 1 || b > Number(p.max_bytes ?? 41_943_040)) throw new Refused(ref, `${at}.bytes`);
    out.bytes = b;
  }
  return out;
};

const audio: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_audio');
  if (!isDict(val) || !sameKeys(val, ['blob', 'secs', 'title'])) throw new Refused(ref, at);
  if (typeof val.blob !== 'string' || !BLOB_RE.test(val.blob)) throw new Refused(ref, `${at}.blob`);
  const secs = val.secs;
  if (typeof secs === 'boolean' || !isNum(secs) || !(secs > 0 && secs <= Number(p.max_secs ?? 600))) {
    throw new Refused(ref, `${at}.secs`);
  }
  const title = cleanStr(val.title, Number(p.max_title ?? 80), `${at}.title`, false, ref);
  return { blob: val.blob, secs, title };
};

const file: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_file');
  if (!isDict(val) || !sameKeys(val, ['blob', 'name', 'mime', 'bytes'])) throw new Refused(ref, at);
  if (typeof val.blob !== 'string' || !BLOB_RE.test(val.blob)) throw new Refused(ref, `${at}.blob`);
  if (val.mime !== (p.mime ?? 'application/pdf')) throw new Refused(ref, `${at}.mime`);
  const b = val.bytes;
  if (typeof b === 'boolean' || !isInt(b) || b < 1 || b > Number(p.max_bytes ?? 10_485_760)) throw new Refused(ref, `${at}.bytes`);
  const name = cleanStr(val.name, Number(p.max_name ?? 120), `${at}.name`, false, ref);
  return { blob: val.blob, name, mime: val.mime, bytes: b };
};

const link: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_link');
  if (!isDict(val) || !has(val, 'url') || extraKeys(val, ['url', 'label']).length) throw new Refused(ref, at);
  const out: Json = { url: httpsUrl(val.url, Number(p.max_url ?? 300), `${at}.url`, ref) };
  if (has(val, 'label')) out.label = cleanStr(val.label, Number(p.max_label ?? 80), `${at}.label`, false, ref);
  return out;
};

const phone: Prim = (val, f, p, _ctx, at) => {
  if (typeof val !== 'string' || !PHONE_RE.test(val)) throw new Refused(refusalFor(f, p, 'bad_phone'), at);
  return val;
};

const email: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_email');
  const max = Number(f.max ?? p.max ?? 120);
  if (typeof val !== 'string' || len(val) > max || !EMAIL_RE.test(val)) throw new Refused(ref, at);
  if (hasCtrl(val)) throw new Refused(ref, at);
  return val;
};

function coord(val: unknown, limit: number, at: string, ref: string): number {
  if (typeof val === 'boolean' || !isNum(val)) throw new Refused(ref, at);
  if (!(val >= -limit && val <= limit)) throw new Refused(ref, at);
  return val;
}

const point: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_point');
  if (!isDict(val)) throw new Refused(ref, at);
  const extra = extraKeys(val, ['lat', 'lon']);
  if (extra.length) throw new Refused('unknown_field', `${at}.${extra[0]}`);
  const missing = missingKeys(val, ['lat', 'lon']);
  if (missing.length) throw new Refused('missing_field', `${at}.${missing[0]}`);
  return { lat: coord(val.lat, 90, `${at}.lat`, ref), lon: coord(val.lon, 180, `${at}.lon`, ref) };
};

const route: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_route');
  const min = Number(f.min ?? p.min ?? 2);
  const max = Number(f.max ?? p.max ?? 50);
  if (!Array.isArray(val) || val.length < min || val.length > max) throw new Refused(ref, at);
  const pt = ctx.registry.primitives.point;
  return val.map((x, i) => point(x, {} as FieldSpec, pt, ctx, `${at}[${i}]`));
};

const person: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_person');
  if (!isDict(val) || !has(val, 'contact') || extraKeys(val, ['contact', 'role']).length) throw new Refused(ref, at);
  if (typeof val.contact !== 'string' || !CONTACT_RE.test(val.contact)) throw new Refused(ref, `${at}.contact`);
  const out: Json = { contact: val.contact };
  if (has(val, 'role')) out.role = cleanStr(val.role, Number(p.max_role ?? 40), `${at}.role`, false, ref);
  return out;
};

const choice: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_choice');
  let options = f.options;
  if (options == null && f.vocab != null) options = ctx.registry.vocab[f.vocab] ?? [];
  if (typeof val !== 'string' || !options || !options.length || !options.includes(val)) throw new Refused(ref, at);
  return val;
};

const tagset: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_tagset');
  const allowed = new Set(f.vocab ? (ctx.registry.vocab[f.vocab] ?? []) : []);
  const max = Number(f.max ?? p.max ?? 24);
  if (!Array.isArray(val) || val.length < 1 || val.length > max) throw new Refused(ref, at);
  if (new Set(val).size !== val.length || val.some((t) => typeof t !== 'string' || !allowed.has(t))) {
    throw new Refused(ref, at);
  }
  return [...val];
};

const toggle: Prim = (val, f, p, _ctx, at) => {
  if (typeof val !== 'boolean') throw new Refused(refusalFor(f, p, 'bad_toggle'), at);
  return val;
};

const refPrim: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_ref');
  if (typeof val !== 'string' || !val) throw new Refused(ref, at);
  if ((f.scope ?? 'page') === 'venue') {
    if (!VENUE_REF_RE.test(val)) throw new Refused(ref, at);
    return val;
  }
  const parts = val.split('/');
  if (![1, 2].includes(parts.length) || !parts.every((x) => ID_RE.test(x))) throw new Refused(ref, at);
  if (f.item && parts.length !== 2) throw new Refused(ref, at);
  ctx.pendingRefs.push([val, f, at]);
  return val;
};

const list: Prim = (val, f, p, ctx, at) => {
  const ref = refusalFor(f, p, 'bad_list');
  if (!Array.isArray(val)) throw new Refused(ref, at);
  const min = Number(f.min ?? p.min ?? 1);
  const max = Number(f.max ?? p.max ?? 200);
  if (val.length < min) throw new Refused(ref === 'bad_list' && f.of === 'item' ? 'no_items' : ref, at);
  if (val.length > max) throw new Refused(f.of === 'item' ? 'too_many_items' : ref, at);
  const recSpec = f.of ? ctx.records[f.of] : undefined;
  if (!recSpec || !keys(recSpec).length) throw new Refused(ref, at);
  const recKeys = keys(recSpec);
  const out: Json[] = [];
  const uniqueSeen = new Map<string, Set<unknown>>();
  val.forEach((row, k) => {
    const rat = `${at}[${k}]`;
    if (!isDict(row)) throw new Refused(ref, rat);
    const hours = ref === 'bad_hours';
    const extra = extraKeys(row, recKeys);
    if (extra.length) throw new Refused(hours ? ref : 'unknown_field', hours ? rat : `${rat}.${extra[0]}`);
    const missing = missingKeys(row, recKeys.filter((fn) => recSpec[fn].req)).sort(cmp);
    if (missing.length) throw new Refused(hours ? ref : 'missing_field', hours ? rat : `${rat}.${missing[0]}`);
    const clean: Json = {};
    for (const fn of recKeys) {
      if (!has(row, fn)) continue;
      const fs = recSpec[fn];
      const fat = `${rat}.${fn}`;
      const validated = PRIMS[fs.p](row[fn], fs, ctx.registry.primitives[fs.p], ctx, fat);
      if (fn === 'id') {
        if (typeof validated !== 'string' || !ID_RE.test(validated)) throw new Refused('bad_item_id', fat);
        if (ctx.itemIds.has(validated)) throw new Refused('duplicate_item', fat);
        ctx.itemIds.add(validated);
        if (ctx.itemIds.size > MAX_ITEMS) throw new Refused('too_many_items', rat);
      }
      if (fs.unique) {
        const seen = uniqueSeen.get(fn) ?? new Set<unknown>();
        if (seen.has(validated)) throw new Refused('duplicate_item', fat);
        seen.add(validated);
        uniqueSeen.set(fn, seen);
      }
      clean[fn] = validated;
    }
    out.push(clean);
  });
  return out;
};

function b58len(text: string): number | null {
  let n = 0n;
  for (const c of text) {
    const i = B58.indexOf(c);
    if (i < 0) return null;
    n = n * 58n + BigInt(i);
  }
  const body = n === 0n ? 0 : Math.ceil(n.toString(2).length / 8);
  return text.length - text.replace(/^1+/, '').length + body;
}

const wallet: Prim = (val, f, p, _ctx, at) => {
  const ref = refusalFor(f, p, 'bad_wallet');
  const raw = typeof val === 'string' && val.length >= 32 && val.length <= 44 ? b58len(val) : null;
  if (raw === null || raw !== Number(p.bytes ?? 32)) throw new Refused(ref, at);
  return val;
};

const derived: Prim = (_val, f, p, _ctx, at) => {
  throw new Refused(refusalFor(f, p, 'derived_field'), at);
};

const PRIMS: Record<string, Prim> = {
  text, richtext, number, percent, money, token, time, timerange, duration, date, daterange, datetime,
  dayset, recurrence, image, gallery, video, audio, file, link, phone, email, point, route, person,
  choice, tagset, toggle, ref: refPrim, list, wallet, derived,
};

function pythonJson(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(pythonJson).join(', ')}]`;
  if (isDict(v)) {
    return `{${keys(v).map((k) => `${JSON.stringify(k)}: ${pythonJson(v[k])}`).join(', ')}}`;
  }
  return JSON.stringify(v) ?? 'null';
}

function canonJson(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonJson).join(',')}]`;
  if (isDict(v)) return `{${keys(v).sort(cmp).map((k) => `${JSON.stringify(k)}:${canonJson(v[k])}`).join(',')}}`;
  return JSON.stringify(v) ?? 'null';
}

const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
  0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
  0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
  0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

function sha16(input: string): string {
  const bytes = new TextEncoder().encode(input);
  const total = Math.ceil((bytes.length + 9) / 64) * 64;
  const buf = new Uint8Array(total);
  buf.set(bytes);
  buf[bytes.length] = 0x80;
  const view = new DataView(buf.buffer);
  view.setUint32(total - 8, Math.floor((bytes.length * 8) / 2 ** 32));
  view.setUint32(total - 4, (bytes.length * 8) >>> 0);
  const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const w = new Array<number>(64);
  const rotr = (x: number, n: number): number => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < total; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    [a, b, c, d, e, f, g, hh].forEach((v, i) => { h[i] = (h[i] + v) >>> 0; });
  }
  return h.map((v) => v.toString(16).padStart(8, '0')).join('').slice(0, 16);
}

function ruleOneToken(blocks: Json[], registry: Registry): void {
  const kinds = registry.page_rules.one_token ?? [];
  let pageToken: string | null = null;
  blocks.forEach((b, idx) => {
    if (!kinds.includes(b.kind)) return;
    const tok = b.data.token;
    if (!tok) return;
    if (pageToken !== null && tok !== pageToken) throw new Refused('mixed_tokens', `blocks[${idx}].data.token`);
    pageToken = tok;
  });
}

function ruleResolveRefs(pending: Array<[string, FieldSpec, string]>, byId: Map<string, Json>, itemsByBlock: Map<string, Set<string>>): void {
  for (const [value, f, at] of pending) {
    const parts = value.split('/');
    const target = byId.get(parts[0]);
    if (!target) throw new Refused('bad_ref', at);
    if (f.target && target.kind !== f.target) throw new Refused('bad_ref', at);
    if (parts.length === 2 && !itemsByBlock.get(parts[0])?.has(parts[1])) throw new Refused('bad_ref', at);
  }
}

function rulePublish(active: Json[], registry: Registry): void {
  const rule = registry.page_rules.publish ?? {};
  const registered = new Set(keys(registry.blocks));
  const present = new Set<string>();
  for (const b of active) {
    if (b.kind === 'loc.address') {
      const pt = b.data.point;
      if (!isDict(pt) || !has(pt, 'lat') || !has(pt, 'lon')) continue;
    }
    present.add(b.kind);
  }
  const all = (rule.all ?? []).filter((k) => registered.has(k));
  if (!all.every((k) => present.has(k))) throw new Refused('needs_address', 'blocks');
  const groups = (rule.any ?? []).map((g) => g.filter((k) => registered.has(k))).filter((g) => g.length);
  if (groups.length && !groups.some((g) => g.every((k) => present.has(k)))) throw new Refused('needs_address', 'blocks');
}

function pageVisibility(page: Json, registry: Registry): [string, string[]] {
  const visibility = has(page, 'visibility') ? page.visibility : 'public';
  if (typeof visibility !== 'string' || !registry.vocab.visibility.includes(visibility)) throw new Refused('bad_choice', 'visibility');
  if (visibility !== 'group') {
    if (has(page, 'invite')) throw new Refused('unknown_field', 'invite');
    return [visibility, []];
  }
  const invite = page.invite;
  if (!Array.isArray(invite) || invite.length < 1 || invite.length > MAX_INVITE) throw new Refused('bad_invite', 'invite');
  invite.forEach((key, j) => {
    if (typeof key !== 'string' || !PUBKEY_RE.test(key) || invite.slice(0, j).includes(key)) throw new Refused('bad_invite', `invite[${j}]`);
  });
  return [visibility, [...invite]];
}

function validateOrThrow(page: unknown, registry: Registry, publish: boolean): Verdict & { ok: true } {
  if (!isDict(page) || !isInt(page.v) || page.v !== 2) throw new Refused('bad_version', 'v');
  const extraTop = extraKeys(page, ['v', 'tone', 'look', 'on', 'visibility', 'invite', 'blocks']);
  if (extraTop.length) throw new Refused('unknown_field', extraTop[0]);
  const [visibility, invite] = pageVisibility(page, registry);
  if (!has(page, 'tone')) throw new Refused('missing_field', 'tone');
  const tones = registry.vocab.tone ?? ['amber', 'sage', 'sea', 'iris', 'rose', 'graphite'];
  if (typeof page.tone !== 'string' || !tones.includes(page.tone)) throw new Refused('bad_choice', 'tone');
  if (has(page, 'look') && (typeof page.look !== 'string' || !(registry.vocab.look ?? []).includes(page.look))) {
    throw new Refused('bad_choice', 'look');
  }
  if (has(page, 'on')) surfaces(page.on, registry.vocab.surface ?? [], 'on');

  const blocks = page.blocks;
  if (!Array.isArray(blocks)) throw new Refused('not_a_list', 'blocks');
  const rules = registry.page_rules;
  if (blocks.length > Number(rules.max_blocks ?? 60)) throw new Refused('too_many_blocks', 'blocks');
  if (new TextEncoder().encode(pythonJson(page)).length > Number(rules.max_bytes ?? 98304)) throw new Refused('too_big', '');

  const audiences = registry.vocab.audience;
  const seenIds = new Set<string>();
  const kindCounts = new Map<string, number>();
  let globalItems = new Set<string>();
  const pending: Array<[string, FieldSpec, string]> = [];
  const byId = new Map<string, Json>();
  const itemsByBlock = new Map<string, Set<string>>();
  const cleanBlocks: Json[] = [];
  const active: Json[] = [];

  blocks.forEach((block, i) => {
    const at = `blocks[${i}]`;
    if (!isDict(block)) throw new Refused('unknown_block', at);
    const extraB = extraKeys(block, ['id', 'kind', 'variant', 'hidden', 'audience', 'size', 'tone', 'on', 'data']);
    if (extraB.length) throw new Refused('unknown_field', `${at}.${extraB[0]}`);
    for (const rk of ['id', 'kind', 'data']) {
      if (!has(block, rk)) throw new Refused(rk === 'kind' ? 'unknown_block' : 'missing_field', rk === 'kind' ? at : `${at}.${rk}`);
    }
    const kind = block.kind;
    if (typeof kind !== 'string' || !has(registry.blocks, kind)) throw new Refused('unknown_block', at);
    const bspec = registry.blocks[kind];

    const bid = block.id;
    if (typeof bid !== 'string' || !ID_RE.test(bid)) throw new Refused('bad_id', `${at}.id`);
    if (seenIds.has(bid)) throw new Refused(kind === 'evt.single' ? 'duplicate_event' : 'duplicate_id', `${at}.id`);
    seenIds.add(bid);

    const count = (kindCounts.get(kind) ?? 0) + 1;
    kindCounts.set(kind, count);
    const maxK = Number(bspec.max);
    if (count > maxK) {
      if (kind === 'evt.single') throw new Refused('too_many_events', at);
      if (maxK === 1) throw new Refused('duplicate_block', at);
      throw new Refused('too_many_of_kind', at);
    }

    const variant = has(block, 'variant') ? block.variant : bspec.variants[0];
    if (typeof variant !== 'string' || !bspec.variants.includes(variant)) throw new Refused('bad_choice', `${at}.variant`);
    const hidden = has(block, 'hidden') ? block.hidden : false;
    if (typeof hidden !== 'boolean') throw new Refused('bad_toggle', `${at}.hidden`);
    const audience = has(block, 'audience') ? block.audience : 'public';
    if (typeof audience !== 'string' || !audiences.includes(audience)) throw new Refused('bad_choice', `${at}.audience`);

    const size = block.size;
    if (has(block, 'size') && (typeof size !== 'string' || !(registry.vocab.size ?? []).includes(size))) {
      throw new Refused('bad_choice', `${at}.size`);
    }
    const btone = block.tone;
    if (has(block, 'tone') && (typeof btone !== 'string' || !tones.includes(btone))) throw new Refused('bad_choice', `${at}.tone`);
    if (has(block, 'on')) surfaces(block.on, bspec.surfaces as string[], `${at}.on`);

    const data = block.data;
    if (!isDict(data)) throw new Refused('unknown_field', `${at}.data`);
    const fspecs = bspec.fields;
    const extraD = extraKeys(data, keys(fspecs));
    if (extraD.length) throw new Refused('unknown_field', `${at}.data.${extraD[0]}`);
    for (const [fn, fs] of Object.entries(fspecs)) {
      if (fs.p === 'derived' && has(data, fn)) throw new Refused('derived_field', `${at}.data.${fn}`);
      if (fs.req && !has(data, fn)) throw new Refused('missing_field', `${at}.data.${fn}`);
    }

    const ctx: Ctx = { registry, blockData: data, records: bspec.records ?? {}, itemIds: new Set(globalItems), pendingRefs: [] };
    const cleanData: Json = {};
    const ordered = keys(fspecs).sort((a, b) => Number(fspecs[a].p !== 'token') - Number(fspecs[b].p !== 'token'));
    for (const fn of ordered) {
      if (!has(data, fn)) continue;
      const fs = fspecs[fn];
      cleanData[fn] = PRIMS[fs.p](data[fn], fs, registry.primitives[fs.p] as PrimitiveSpec, ctx, `${at}.data.${fn}`);
    }
    const orderedData: Json = {};
    for (const fn of keys(fspecs)) if (has(cleanData, fn)) orderedData[fn] = cleanData[fn];

    if (kind === 'evt.single' && has(orderedData, 'ends') && orderedData.ends <= orderedData.starts) {
      throw new Refused('bad_datetime', `${at}.data.ends`);
    }

    const newItems = new Set([...ctx.itemIds].filter((x) => !globalItems.has(x)));
    globalItems = ctx.itemIds;
    itemsByBlock.set(bid, newItems);
    pending.push(...ctx.pendingRefs);

    const inst: Json = { id: bid, kind, variant, hidden };
    if (has(block, 'audience')) inst.audience = audience;
    if (has(block, 'size')) inst.size = size;
    if (has(block, 'tone')) inst.tone = btone;
    if (has(block, 'on')) inst.on = [...(block.on as string[])];
    inst.data = orderedData;
    cleanBlocks.push(inst);
    byId.set(bid, inst);
    if (!hidden && audience === 'public') active.push(inst);
  });

  ruleOneToken(cleanBlocks, registry);
  ruleResolveRefs(pending, byId, itemsByBlock);
  if (publish) rulePublish(active, registry);

  let name: string | null = null;
  let location: { address: string; lat: number; lon: number } | null = null;
  let locationBy: string | null = null;
  let pageToken: string | null = null;
  let walletAddr: string | null = null;
  const menus: Json[] = [];
  const oneToken = rules.one_token ?? [];
  for (const b of active) {
    const d = b.data;
    if (b.kind === 'id.name' && name === null) name = d.name;
    else if ((b.kind === 'loc.address' && locationBy !== b.kind) || (b.kind === 'loc.meet_point' && location === null)) {
      location = { address: d.text, lat: d.point.lat, lon: d.point.lon };
      locationBy = b.kind;
    }
    else if (b.kind === 'pay.wallet' && walletAddr === null) walletAddr = d.address;
    if (oneToken.includes(b.kind) && has(d, 'token') && pageToken === null) pageToken = d.token;
    if (b.kind === 'cat.menu') {
      const m: Json = { type: 'menu', token: d.token, items: d.items };
      if (has(d, 'title')) m.title = d.title;
      menus.push(m);
    }
  }
  const menuVersion = menus.length ? sha16(canonJson(menus)) : null;

  return {
    ok: true,
    page: {
      v: 2,
      tone: page.tone,
      ...(has(page, 'look') ? { look: page.look } : {}),
      ...(has(page, 'on') ? { on: [...(page.on as string[])] } : {}),
      ...(has(page, 'visibility') ? { visibility } : {}),
      ...(invite.length ? { invite } : {}),
      blocks: cleanBlocks,
    },
    visibility,
    name,
    location,
    token: pageToken,
    menu_version: menuVersion,
    wallet: walletAddr,
  };
}

function surfaces(val: unknown, allowed: string[], at: string): void {
  if (!Array.isArray(val) || !val.length || new Set(val).size !== val.length || val.some((v) => typeof v !== 'string' || !allowed.includes(v))) {
    throw new Refused('bad_choice', at);
  }
}

export function validatePageV2(page: unknown, registry: Registry, opts: { publish?: boolean } = {}): Verdict {
  try {
    return validateOrThrow(page, registry, Boolean(opts.publish));
  } catch (e) {
    if (e instanceof Refused) return { ok: false, refused: e.reason, at: e.at };
    throw e;
  }
}
