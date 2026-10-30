/**
 * ПОГЛЯД v1: приймач каже, скільки пікселів він справді покаже, відправник
 * кодує рівно це (біблія, 03_media_engine.md, §4.5).
 *
 * Що було: `callVideo.ts` слав 2,5 Мбіт/с на 60 fps із `maintain-framerate`
 * будь-куди — ~1,1 ГБ на годину на ногу, навіть коли плитка в приймача
 * 320 px або її не видно зовсім.
 *
 * Що тепер:
 * 1. Приймач оголошує кадр `view` каналом дзвінка (`RTCDataChannel
 *    "phantom-view"` — він їде всередині DTLS тієї самої доріжки, вузол його
 *    не бачить): фізичні пікселі плитки, чи її видно, екран, data saver,
 *    бюджет МБ/год, батарею. Дебаунс 250 мс і гістерезис ±15% на зміну
 *    розміру; видимість — негайно; серцебиття — раз на 10 с.
 * 2. Відправник переводить кадр у параметри енкодера ([planFor]) і камеру
 *    (`applyConstraints`) — див. `capVideoSender` у `callVideo.ts`.
 * 3. Відправник відповідає кадром `serve`: що він справді шле, і скільки це
 *    МБ/год за `getStats().bytesSent` — вимір, не оцінка ([RateMeter]).
 *
 * У дроті — JSON (CBOR у застосунку немає), ~150 байт.
 * Чого v1 НЕ вміє (і не вдає): тепло апарата (у вебі немає джерела), SR,
 * mesh `max(need)` — ноги кімнати лишаються на типовому плані.
 */

/* ── кадри ─────────────────────────────────────────────────────────────── */

import { currentNet, effectiveCall, loadCallPrefs } from './callPrefs';

export const VIEW_CHANNEL = 'phantom-view';

/** Одна плитка, що показує відправника: фізичні пікселі й видимість. */
export interface ViewTile {
  w: number;
  h: number;
  vis: boolean;
}

export interface ViewMsg {
  t: 'view';
  v: 1;
  seq: number;
  tiles: ViewTile[];
  /** Екран приймача у фізичних пікселях. 0 — невідомо. */
  screen: { w: number; h: number };
  saveData: boolean;
  /** Бюджет розмови, якщо людина його ставила. */
  budgetMBh: number | null;
  /** Батарея квантована до 10% — повне число зайве і є відбитком. */
  batt: { pct: number; chg: boolean } | null;
  fg: 'visible' | 'hidden';
}

/** Те, що приймач читає зі свого екрана, — без службових полів кадру. */
export type ViewState = Omit<ViewMsg, 't' | 'v' | 'seq'>;

export interface ServeMsg {
  t: 'serve';
  v: 1;
  seq: number;
  active: boolean;
  /** Висота кадру, яку відправник ціляє (коротка сторона). */
  h: number;
  fps: number;
  /** Стеля бітрейту відео, кбіт/с. */
  kbps: number;
  /** Виміряно з getStats().bytesSent за останні секунди. null — ще не міряли. */
  mbh: number | null;
  /** Втрати на виході у %, з remote-inbound-rtp. null — ще не міряли. */
  up?: number | null;
}

const MAX_FRAME_CHARS = 4096;
const MAX_TILES = 16;
const MAX_PX = 8192;
const MAX_BUDGET_MBH = 100_000;

const asObject = (raw: unknown): Record<string, unknown> | null => {
  let value = raw;
  if (typeof raw === 'string') {
    if (raw.length > MAX_FRAME_CHARS) return null;
    try {
      value = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
};

const finite = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x);
const clamp = (x: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, x));
const px = (x: unknown): number | null => (finite(x) ? Math.round(clamp(x, 0, MAX_PX)) : null);
const seqOf = (x: unknown): number => (finite(x) ? Math.round(clamp(x, 0, 2 ** 31 - 1)) : 0);

/**
 * Кадр `view` з дроту. Сміття — null (відправник лишається на тому плані,
 * що мав), числа — у межах: чужий апарат не має права замовити 10⁹ пікселів
 * чи від'ємний бюджет.
 */
export const parseView = (raw: unknown): ViewMsg | null => {
  const o = asObject(raw);
  if (!o || o.t !== 'view' || o.v !== 1 || !Array.isArray(o.tiles)) return null;
  const tiles: ViewTile[] = [];
  for (const item of o.tiles.slice(0, MAX_TILES)) {
    if (!item || typeof item !== 'object') return null;
    const t = item as Record<string, unknown>;
    const w = px(t.w);
    const h = px(t.h);
    if (w === null || h === null || typeof t.vis !== 'boolean') return null;
    tiles.push({ w, h, vis: t.vis });
  }
  const s = (o.screen && typeof o.screen === 'object' ? o.screen : {}) as Record<string, unknown>;
  const budget = finite(o.budgetMBh) && o.budgetMBh > 0 ? clamp(o.budgetMBh, 1, MAX_BUDGET_MBH) : null;
  const b = o.batt && typeof o.batt === 'object' ? (o.batt as Record<string, unknown>) : null;
  const batt =
    b && finite(b.pct)
      ? { pct: Math.round(clamp(b.pct, 0, 100) / 10) * 10, chg: b.chg === true }
      : null;
  return {
    t: 'view',
    v: 1,
    seq: seqOf(o.seq),
    tiles,
    screen: { w: px(s.w) ?? 0, h: px(s.h) ?? 0 },
    saveData: o.saveData === true,
    budgetMBh: budget,
    batt,
    fg: o.fg === 'hidden' ? 'hidden' : 'visible',
  };
};

export const parseServe = (raw: unknown): ServeMsg | null => {
  const o = asObject(raw);
  if (!o || o.t !== 'serve' || o.v !== 1) return null;
  if (typeof o.active !== 'boolean' || !finite(o.h) || !finite(o.fps) || !finite(o.kbps)) return null;
  return {
    t: 'serve',
    v: 1,
    seq: seqOf(o.seq),
    active: o.active,
    h: Math.round(clamp(o.h, 0, 4320)),
    fps: Math.round(clamp(o.fps, 0, 120)),
    kbps: Math.round(clamp(o.kbps, 0, 20_000)),
    mbh: finite(o.mbh) ? clamp(o.mbh, 0, MAX_BUDGET_MBH) : null,
    ...(finite(o.up) ? { up: clamp(o.up, 0, 100) } : {}),
  };
};

export type WeakSide = 'me' | 'them' | 'path';

export interface LinkSides {
  upLossPct: number | null;
  downLossPct: number | null;
  rttMs: number | null;
  peerUpLossPct: number | null;
}

export const WEAK_LOSS_PCT = 8;
export const CLEAN_LOSS_PCT = 2;
export const WEAK_RTT_MS = 400;

const isWeakLoss = (pct: number | null): boolean =>
   finite(pct) && pct >= WEAK_LOSS_PCT;

const isCleanLoss = (pct: number | null): boolean =>
  pct === null || (finite(pct) && pct <= CLEAN_LOSS_PCT);

export const weakSide = (s: LinkSides): WeakSide | null => {
  if (isWeakLoss(s.upLossPct) && isCleanLoss(s.downLossPct)) return 'me';
  if (isWeakLoss(s.peerUpLossPct) && isCleanLoss(s.upLossPct)) return 'them';
  if (isWeakLoss(s.upLossPct) || isWeakLoss(s.downLossPct)) return 'path';
  if (finite(s.rttMs) && s.rttMs >= WEAK_RTT_MS) return 'path';
  return null;
};

/* ── відправник: кадр → план енкодера ──────────────────────────────────── */

export interface VideoPlan {
  active: boolean;
  /** Коротка сторона кадру, яку знімаємо й кодуємо. */
  height: number;
  maxFramerate: number;
  /** біт/с */
  maxBitrate: number;
  why: 'old-client' | 'hidden' | 'tile';
}

interface Rung {
  h: number;
  fps: number;
  kbps: number;
}

/**
 * Драбина відео. Знизу ~100 кбіт/с (360p15 — плитка в сітці), згори 1080p30
 * на весь екран 1:1. Вище за 1080 камеру й не просимо (`grabMedia`).
 *
 * 08.10 (P0-5, власник: «найвища можлива якість»): 720p30 — 2,0 Мбіт/с
 * (було 1,0: на весь екран телефона в русі це «мило»), 1080p30 — 3,5
 * (було 2,0). Але 1080 дається лише на ВЕРХНІЙ сходинці драбини каналу
 * ([planFor], `topTier`). Дзвінок стартує на 720p (D7) і піднімається на
 * верх лише після ≈16 с рівного каналу (втрати ≤2 %, RTT ≤250 мс); перший
 * же поганий відрізок повертає 720p.
 */
export const VIDEO_RUNGS: readonly Rung[] = [
  { h: 360, fps: 15, kbps: 100 },
  { h: 360, fps: 24, kbps: 250 },
  { h: 540, fps: 24, kbps: 500 },
  { h: 720, fps: 30, kbps: 2000 },
  { h: 1080, fps: 30, kbps: 3500 },
  { h: 1440, fps: 30, kbps: 8000 },
  { h: 2160, fps: 30, kbps: 16000 },
];

/** Вище 1080 — лише в режимі «Найкраще», коли камера це вміє, а приймач показує. */
const HD = 1080;

/** Найвища сходинка, яку дістає канал не на самому верху драбини, — 720p. */
const TOP_WITHOUT_HD = VIDEO_RUNGS.findIndex((r) => r.h === 720);

/** Плитка, менша за це (коротка сторона, фізичні px), — найнижча сходинка. */
const TINY_TILE_PX = 240;

/**
 * Співрозмовник ПОГЛЯДУ не шле (старий клієнт): 720p30 / 2,0 Мбіт/с.
 * Не 60 fps — ті були дорожчі без видимої різниці й гріли A52. І не 1080:
 * про такий кадр ніхто не просив.
 */
export const DEFAULT_PLAN: VideoPlan = {
  active: true,
  height: 720,
  maxFramerate: 30,
  maxBitrate: 2_000_000,
  why: 'old-client',
};

export interface PlanOptions {
  /**
   * Драбина каналу на верхній сходинці (`OpusLadder.onTopTier`): лише тоді
   * приймач, що показує 1080 px, отримує 1080p30 / 3,5 Мбіт/с.
   */
  topTier?: boolean;
  /**
   * Коротка сторона, яку камера відправника справді вміє, — лише в режимі
   * «Найкраще». Без неї стеля 1080: 1440/2160 не просимо в камери, що їх не має.
   */
  uhdMax?: number;
}

/** 1 МБ = 10⁶ байт; МБ/год → кбіт/с. */
const mbhToKbps = (mbh: number): number => (mbh * 8_000) / 3600;

/**
 * Кадр приймача → що кодувати. Чиста функція: той самий кадр — той самий
 * план, тож повторний кадр не смикає енкодер.
 */
export const planFor = (view: ViewMsg | null, opts: PlanOptions = {}): VideoPlan => {
  if (!view) return DEFAULT_PLAN;
  const visible = view.fg === 'visible' ? view.tiles.filter((t) => t.vis && t.w > 0 && t.h > 0) : [];
  if (visible.length === 0) {
    // Нікому нічого не видно — енкодер стоїть, звук іде далі.
    const low = VIDEO_RUNGS[0];
    return { active: false, height: low.h, maxFramerate: low.fps, maxBitrate: low.kbps * 1000, why: 'hidden' };
  }
  let need = Math.max(...visible.map((t) => Math.min(t.w, t.h)));
  const screenShort = Math.min(view.screen.w, view.screen.h);
  if (screenShort > 0) need = Math.min(need, screenShort);

  let index: number;
  if (need < TINY_TILE_PX) {
    index = 0;
  } else {
    index = VIDEO_RUNGS.findIndex((r, i) => i > 0 && r.h >= 0.9 * need);
    if (index < 0) index = VIDEO_RUNGS.length - 1;
  }
  // Стеля 720p — ДО знижок за data saver і батарею: ті знижують від того,
  // що канал справді може дати, а не від 1080, якого він не дасть.
  if (!opts.topTier) index = Math.min(index, TOP_WITHOUT_HD);
  const ceiling = Math.max(HD, opts.uhdMax ?? 0);
  while (index > 0 && VIDEO_RUNGS[index].h > ceiling) index -= 1;
  if (view.saveData) index -= 1;
  if (view.batt && view.batt.pct < 15 && !view.batt.chg) index -= 1;
  index = clamp(index, 0, VIDEO_RUNGS.length - 1);

  const rung = VIDEO_RUNGS[index];
  let kbps = rung.kbps;
  let fps = view.saveData ? Math.min(rung.fps, 15) : rung.fps;
  if (view.budgetMBh !== null) {
    // Звук теж їсть бюджет: ~40 кбіт/с лишаємо йому.
    const cap = Math.max(50, Math.floor(mbhToKbps(view.budgetMBh) - 40));
    if (cap < kbps) {
      kbps = cap;
      fps = Math.min(fps, 15);
    }
  }
  return { active: true, height: rung.h, maxFramerate: fps, maxBitrate: kbps * 1000, why: 'tile' };
};

export const samePlan = (a: VideoPlan | null, b: VideoPlan): boolean =>
  !!a &&
  a.active === b.active &&
  a.height === b.height &&
  a.maxFramerate === b.maxFramerate &&
  a.maxBitrate === b.maxBitrate;

/* ── приймач: коли слати ───────────────────────────────────────────────── */

/** ±15% по стороні — менше не варте ключового кадру H.264. */
export const RESIZE_HYSTERESIS = 0.15;
export const VIEW_DEBOUNCE_MS = 250;
export const VIEW_HEARTBEAT_MS = 10_000;

const moved = (a: number, b: number): boolean =>
  a === 0 ? b !== 0 : Math.abs(b - a) / a > RESIZE_HYSTERESIS;

/**
 * Що робити з новим станом щодо останнього надісланого:
 * `now` — видимість, data saver, бюджет, батарея (головна економія, без
 * затримки); `debounce` — розмір змінився більше ніж на 15%; `skip` — шум.
 */
export const viewChange = (sent: ViewState | null, next: ViewState): 'now' | 'debounce' | 'skip' => {
  if (!sent) return 'now';
  if (sent.fg !== next.fg || sent.tiles.length !== next.tiles.length) return 'now';
  if (sent.saveData !== next.saveData || sent.budgetMBh !== next.budgetMBh) return 'now';
  const lowBatt = (s: ViewState) => !!s.batt && s.batt.pct < 15 && !s.batt.chg;
  if (lowBatt(sent) !== lowBatt(next)) return 'now';
  for (let i = 0; i < next.tiles.length; i += 1) {
    if (sent.tiles[i].vis !== next.tiles[i].vis) return 'now';
  }
  for (let i = 0; i < next.tiles.length; i += 1) {
    if (moved(sent.tiles[i].w, next.tiles[i].w) || moved(sent.tiles[i].h, next.tiles[i].h)) {
      return 'debounce';
    }
  }
  return 'skip';
};

export interface AdvertiserDeps {
  read(): ViewState;
  send(msg: ViewMsg): void;
  setTimeout?: (cb: () => void, ms: number) => unknown;
  clearTimeout?: (id: unknown) => void;
  setInterval?: (cb: () => void, ms: number) => unknown;
  clearInterval?: (id: unknown) => void;
}

/** Каденція кадрів `view`: hello, дебаунс розміру, негайна видимість, серцебиття. */
export class ViewAdvertiser {
  private seq = 0;
  private sent: ViewState | null = null;
  private debounce: unknown = null;
  private heartbeat: unknown = null;
  private readonly d: Required<AdvertiserDeps>;

  constructor(deps: AdvertiserDeps) {
    this.d = {
      setTimeout: (cb, ms) => setTimeout(cb, ms),
      clearTimeout: (id) => clearTimeout(id as ReturnType<typeof setTimeout>),
      setInterval: (cb, ms) => setInterval(cb, ms),
      clearInterval: (id) => clearInterval(id as ReturnType<typeof setInterval>),
      ...deps,
    };
  }

  get running(): boolean {
    return this.heartbeat !== null;
  }

  start(): void {
    if (this.heartbeat !== null) return;
    this.sent = null;
    this.emit();
    this.heartbeat = this.d.setInterval(() => this.emit(), VIEW_HEARTBEAT_MS);
  }

  /** Стан плитки міг змінитись — вирішуємо, слати зараз, пізніше чи ніколи. */
  poke(): void {
    if (this.heartbeat === null) return;
    const verdict = viewChange(this.sent, this.d.read());
    if (verdict === 'now') {
      this.emit();
    } else if (verdict === 'debounce') {
      this.clearDebounce();
      this.debounce = this.d.setTimeout(() => {
        this.debounce = null;
        if (viewChange(this.sent, this.d.read()) !== 'skip') this.emit();
      }, VIEW_DEBOUNCE_MS);
    }
  }

  stop(): void {
    this.clearDebounce();
    if (this.heartbeat !== null) this.d.clearInterval(this.heartbeat);
    this.heartbeat = null;
    this.sent = null;
  }

  private clearDebounce(): void {
    if (this.debounce !== null) this.d.clearTimeout(this.debounce);
    this.debounce = null;
  }

  private emit(): void {
    this.clearDebounce();
    const state = this.d.read();
    this.seq += 1;
    this.sent = state;
    try {
      this.d.send({ t: 'view', v: 1, seq: this.seq, ...state });
    } catch {
      /* канал закрився між подіями — серцебиття спробує ще */
    }
  }
}

/* ── лічильник МБ/год ──────────────────────────────────────────────────── */

const METER_WINDOW_MS = 10_000;

/**
 * МБ/год із лічильника байтів getStats. Тільки вимір: менше двох точок,
 * що розходяться хоч на секунду, — null, а не «0».
 */
export class RateMeter {
  private points: Array<{ at: number; bytes: number }> = [];

  sample(bytes: number, at: number): number | null {
    if (!Number.isFinite(bytes) || bytes < 0) return this.value();
    const last = this.points[this.points.length - 1];
    // Лічильник почався заново (нова доріжка) — стара різниця була б брехнею.
    if (last && (bytes < last.bytes || at <= last.at)) this.points = [];
    this.points.push({ at, bytes });
    while (this.points.length > 2 && at - this.points[1].at >= METER_WINDOW_MS) this.points.shift();
    return this.value();
  }

  value(): number | null {
    const first = this.points[0];
    const last = this.points[this.points.length - 1];
    if (!first || !last || last.at - first.at < 1000) return null;
    return ((last.bytes - first.bytes) / (last.at - first.at)) * 3600_000 / 1e6;
  }

  reset(): void {
    this.points = [];
  }
}

/** Байти, що справді пішли в мережу: корисне навантаження й заголовки RTP. */
export const sentBytesOf = (rows: Iterable<Record<string, unknown>>): number | null => {
  let total: number | null = null;
  for (const row of rows) {
    if (row.type !== 'outbound-rtp') continue;
    const body = row.bytesSent;
    if (typeof body !== 'number') continue;
    const head = typeof row.headerBytesSent === 'number' ? row.headerBytesSent : 0;
    total = (total ?? 0) + body + head;
  }
  return total;
};

import { currentLocale, tNow } from '../components/chat/tNow';

/** «~0,4» / «~12» — для рядка під таймером. */
export const mbhLabel = (mbh: number): string => {
  const raw = mbh < 10 ? (Math.round(mbh * 10) / 10).toString() : String(Math.round(mbh));
  const n = currentLocale() === 'en' ? raw : raw.replace('.', ',');
  return tNow('callView.mbh', { n });
};

/* ── плитка на екрані приймача ─────────────────────────────────────────── */

type TileListener = () => void;

/**
 * Елемент `<video>`, що показує співрозмовника. Картка кладе його сюди, рушій
 * читає стан і слухає зміни. Модуль, а не метод рушія, — щоб картка не
 * тягнула рушій заради одного ref.
 */
class RemoteTile {
  private el: HTMLElement | null = null;
  private size = { w: 0, h: 0 };
  private intersecting = false;
  private forcedHidden = false;
  private listeners = new Set<TileListener>();
  private resize: ResizeObserver | null = null;
  private intersect: IntersectionObserver | null = null;
  private onVisibility = (): void => this.fire();
  private watchingPage = false;

  /** Стрілкою — щоб картка могла віддати її як ref-колбек. */
  set = (el: HTMLElement | null): void => {
    if (el === this.el) return;
    this.detach();
    this.el = el;
    if (el) this.attach(el);
    this.fire();
  };

  setForcedHidden(on: boolean): void {
    const next = Boolean(on);
    if (this.forcedHidden === next) return;
    this.forcedHidden = next;
    this.fire();
  }

  state(): ViewTile {
    const pageVisible = typeof document === 'undefined' || document.visibilityState !== 'hidden';
    if (!this.el) return { w: 0, h: 0, vis: false };
    return { w: this.size.w, h: this.size.h, vis: !this.forcedHidden && this.intersecting && pageVisible };
  }

  subscribe(cb: TileListener): () => void {
    // Сторінку сховали (екран погас, застосунок згорнули) — плитки не видно,
    // навіть коли вона в DOM. Слухаємо це завжди, не лише з плиткою.
    if (!this.watchingPage && typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisibility);
      this.watchingPage = true;
    }
    this.listeners.add(cb);
    return () => this.listeners.delete(cb);
  }

  private fire(): void {
    this.listeners.forEach((cb) => {
      try {
        cb();
      } catch {
        /* підписник упав — плитка не винна */
      }
    });
  }

  private attach(el: HTMLElement): void {
    const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
    const rect = el.getBoundingClientRect?.();
    this.size = rect ? { w: Math.round(rect.width * dpr), h: Math.round(rect.height * dpr) } : { w: 0, h: 0 };
    // Без IntersectionObserver вважаємо плитку видимою, щойно вона в DOM.
    this.intersecting = typeof IntersectionObserver === 'undefined';
    if (typeof ResizeObserver !== 'undefined') {
      this.resize = new ResizeObserver((entries) => {
        const entry = entries[entries.length - 1];
        if (!entry) return;
        const device = (entry as ResizeObserverEntry & {
          devicePixelContentBoxSize?: ReadonlyArray<ResizeObserverSize>;
        }).devicePixelContentBoxSize?.[0];
        const ratio = window.devicePixelRatio || 1;
        this.size = device
          ? { w: Math.round(device.inlineSize), h: Math.round(device.blockSize) }
          : { w: Math.round(entry.contentRect.width * ratio), h: Math.round(entry.contentRect.height * ratio) };
        this.fire();
      });
      try {
        this.resize.observe(el, { box: 'device-pixel-content-box' });
      } catch {
        this.resize.observe(el);
      }
    }
    if (typeof IntersectionObserver !== 'undefined') {
      this.intersect = new IntersectionObserver((entries) => {
        const entry = entries[entries.length - 1];
        if (!entry) return;
        this.intersecting = entry.isIntersecting;
        this.fire();
      });
      this.intersect.observe(el);
    }
  }

  private detach(): void {
    this.resize?.disconnect();
    this.intersect?.disconnect();
    this.resize = null;
    this.intersect = null;
    this.size = { w: 0, h: 0 };
    this.intersecting = false;
  }
}

export const remoteTile = new RemoteTile();

/** Екран, data saver і батарея цього апарата — решта кадру `view`. */
let battery: { level: number; charging: boolean } | null = null;
let batteryAsked = false;
let budgetMBh: number | null = null;

/** Бюджет розмови. Поки що лише dev-ручка: екрана для нього ще немає. */
export const setCallBudget = (mbh: number | null): void => {
  budgetMBh = mbh !== null && Number.isFinite(mbh) && mbh > 0 ? mbh : null;
};

/** Батарея й data saver змінюються самі — кадр має це почути. Один раз за життя. */
export const watchDevice = (onChange: () => void): void => {
  if (batteryAsked || typeof navigator === 'undefined') return;
  batteryAsked = true;
  const nav = navigator as Navigator & {
    getBattery?: () => Promise<{ level: number; charging: boolean } & EventTarget>;
    connection?: EventTarget;
  };
  try {
    nav.connection?.addEventListener?.('change', onChange);
  } catch {
    /* немає подій мережі — серцебиття однаково донесе зміну */
  }
  if (typeof nav.getBattery !== 'function') return;
  nav
    .getBattery()
    .then((b) => {
      const read = () => {
        battery = { level: b.level, charging: b.charging };
        onChange();
      };
      read();
      b.addEventListener('levelchange', read);
      b.addEventListener('chargingchange', read);
    })
    .catch(() => undefined);
};

type NetConnection = { type?: string; saveData?: boolean };

const readEffective = () => {
  const conn = typeof navigator !== 'undefined'
    ? (navigator as Navigator & { connection?: NetConnection }).connection
    : undefined;
  return effectiveCall(loadCallPrefs(), currentNet(conn), conn?.saveData === true);
};

/** Своя батарея відправника: менше 15 % без зарядки — стеля 360p, менше 5 % — відео стоїть. */
export const OWN_BATT_LOW_PCT = 15;
export const OWN_BATT_EMPTY_PCT = 5;
export const OWN_BATT_LOW_HEIGHT = 360;

export const ownBatteryCap = (
  b: { level: number; charging: boolean } | null,
): { height: number | null; off: boolean } => {
  if (!b || b.charging) return { height: null, off: false };
  const pct = b.level * 100;
  if (pct < OWN_BATT_EMPTY_PCT) return { height: null, off: true };
  if (pct < OWN_BATT_LOW_PCT) return { height: OWN_BATT_LOW_HEIGHT, off: false };
  return { height: null, off: false };
};

/** Людина сама повернула відео на порожній батареї — до кінця цього дзвінка. */
let keepVideo = false;
export const keepOwnVideo = (on: boolean): void => {
  keepVideo = on;
};

export const ownBatteryOff = (): boolean => !keepVideo && ownBatteryCap(battery).off;

/** Чи людина обрала «Найкраще» для цієї мережі й не стелила відео. */
export const readEffectiveUhd = (): boolean => readEffective().uhd;

export const localVideoCap = (): number | null => {
  const prefs = readEffective().maxHeight;
  const own = ownBatteryCap(battery).height;
  if (prefs === null) return own;
  return own === null ? prefs : Math.min(prefs, own);
};

export const readViewState = (): ViewState => {
  const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
  const scr = typeof screen !== 'undefined' ? screen : null;
  const eff = readEffective();
  return {
    tiles: [remoteTile.state()],
    screen: scr ? { w: Math.round(scr.width * dpr), h: Math.round(scr.height * dpr) } : { w: 0, h: 0 },
    saveData: eff.saveData,
    budgetMBh: budgetMBh ?? eff.budgetMBh,
    batt: battery ? { pct: Math.round(battery.level * 10) * 10, chg: battery.charging } : null,
    fg: typeof document !== 'undefined' && document.visibilityState === 'hidden' ? 'hidden' : 'visible',
  };
};
