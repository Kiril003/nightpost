/**
 * ПОГЛЯД v1 (біблія 03, §4.5): приймач оголошує плитку, відправник кодує
 * рівно це і відповідає виміряним МБ/год.
 *
 * Червоніє на коді до 02.10: модуля `callView` не було, а `capVideoSender`
 * ставив 2,5 Мбіт/с, 60 fps і `maintain-framerate` будь-якій плитці.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_PLAN,
  RateMeter,
  ViewAdvertiser,
  mbhLabel,
  parseServe,
  parseView,
  planFor,
  sentBytesOf,
  viewChange,
} from '../services/callView';
import type { ViewMsg, ViewState } from '../services/callView';
import { capVideoSender } from '../services/callVideo';
import { OpusLadder } from '../services/callLadder';

const state = (patch: Partial<ViewState> = {}): ViewState => ({
  tiles: [{ w: 1080, h: 2340, vis: true }],
  screen: { w: 1080, h: 2340 },
  saveData: false,
  budgetMBh: null,
  batt: null,
  fg: 'visible',
  ...patch,
});
const view = (patch: Partial<ViewState> = {}): ViewMsg => ({ t: 'view', v: 1, seq: 1, ...state(patch) });

/* ── відображення: плитка → план ─────────────────────────────────────── */

describe('planFor: плитка приймача → параметри енкодера', () => {
  /**
   * 08.10, P0-5: типово — 720p30 зі стелею 2,0 Мбіт/с (було 1,2: на весь
   * екран телефона 720p при 1,2 Мбіт/с — помітне «мило» в русі).
   */
  it('старий клієнт без ПОГЛЯДУ — 720p30 / 2,0 Мбіт/с, а не 2,5 Мбіт/с / 60', () => {
    const plan = planFor(null);
    expect(plan).toEqual(DEFAULT_PLAN);
    expect(plan.active).toBe(true);
    expect(plan.height).toBe(720);
    expect(plan.maxBitrate).toBe(2_000_000);
    expect(plan.maxFramerate).toBe(30);
    // Навіть верх драбини не дає 1080 тому, хто ПОГЛЯДУ не шле: ніхто не просив.
    expect(planFor(null, { topTier: true })).toEqual(DEFAULT_PLAN);
  });

  it('мініатюра 320×180 фізичних px — найнижча сходинка, ~100 кбіт/с', () => {
    const plan = planFor(view({ tiles: [{ w: 320, h: 180, vis: true }] }));
    expect(plan).toMatchObject({ active: true, height: 360, maxFramerate: 15, maxBitrate: 100_000 });
  });

  it('плитка 600×338 — 360p24, а не 1080p', () => {
    const plan = planFor(view({ tiles: [{ w: 600, h: 338, vis: true }] }));
    expect(plan.height).toBe(360);
    expect(plan.maxFramerate).toBe(24);
    expect(plan.maxBitrate).toBeLessThanOrEqual(250_000);
  });

  /**
   * 1080p30 до 3,5 Мбіт/с — лише коли приймач справді показує 1080 px І
   * драбина на верхній сходинці (канал тримає). Інакше — 720p30 / 2,0.
   * Червоніє на коді до 08.10: там 1080 давалось будь-якому каналу за 2,0.
   */
  it('весь екран 1080×2340 на верхній сходинці — 1080p30 / 3,5 Мбіт/с', () => {
    const plan = planFor(view(), { topTier: true });
    expect(plan.height).toBe(1080);
    expect(plan.maxFramerate).toBe(30);
    expect(plan.maxBitrate).toBe(3_500_000);
    // Телефонний екран не просить більше за 1080, навіть коли камера вміє 4K.
    expect(planFor(view(), { topTier: true, uhdMax: 2160 }).height).toBe(1080);
  });

  it('4K-екран приймача: 2160p лише з камерою 4K; камера 1440 — 1440p; без «Найкраще» — 1080p', () => {
    const big = view({ tiles: [{ w: 3840, h: 2160, vis: true }], screen: { w: 3840, h: 2160 } });
    expect(planFor(big, { topTier: true, uhdMax: 2160 })).toMatchObject({ height: 2160, maxBitrate: 16_000_000 });
    expect(planFor(big, { topTier: true, uhdMax: 1440 })).toMatchObject({ height: 1440, maxBitrate: 8_000_000 });
    expect(planFor(big, { topTier: true })).toMatchObject({ height: 1080, maxBitrate: 3_500_000 });
    expect(planFor(big, { uhdMax: 2160 }).height).toBe(720);
  });

  it('весь екран, але драбина не нагорі — 720p30 / 2,0 Мбіт/с', () => {
    const plan = planFor(view());
    expect(plan).toMatchObject({ active: true, height: 720, maxFramerate: 30, maxBitrate: 2_000_000 });
    expect(planFor(view(), { topTier: false }).height).toBe(720);
  });

  it('плитка, більша за екран, обрізається екраном', () => {
    const plan = planFor(view({ tiles: [{ w: 4000, h: 4000, vis: true }], screen: { w: 720, h: 1600 } }));
    expect(plan.height).toBe(720);
  });

  it('плитку сховано — відео стоїть (active=false)', () => {
    expect(planFor(view({ tiles: [{ w: 1080, h: 2340, vis: false }] })).active).toBe(false);
    expect(planFor(view({ fg: 'hidden' })).active).toBe(false);
    expect(planFor(view({ tiles: [] })).active).toBe(false);
    expect(planFor(view({ tiles: [{ w: 0, h: 0, vis: true }] })).active).toBe(false);
  });

  it('кілька плиток — кодуємо під найбільшу видиму', () => {
    const plan = planFor(view({ tiles: [{ w: 300, h: 300, vis: true }, { w: 1080, h: 1080, vis: false }, { w: 700, h: 560, vis: true }] }));
    // Найбільша видима — 700×560, коротка сторона 560 → 540p.
    expect(plan.height).toBe(540);
  });

  it('data saver — сходинкою нижче і не більше 15 fps', () => {
    const plain = planFor(view({ tiles: [{ w: 720, h: 720, vis: true }] }));
    const saver = planFor(view({ tiles: [{ w: 720, h: 720, vis: true }], saveData: true }));
    expect(saver.height).toBeLessThan(plain.height);
    expect(saver.maxFramerate).toBeLessThanOrEqual(15);
  });

  it('батарея < 15% без зарядки — сходинкою нижче; на зарядці — ні', () => {
    const plain = planFor(view());
    expect(planFor(view({ batt: { pct: 10, chg: false } })).height).toBeLessThan(plain.height);
    expect(planFor(view({ batt: { pct: 10, chg: true } })).height).toBe(plain.height);
  });

  it('бюджет 50 МБ/год — стеля бітрейту вкладається в бюджет разом зі звуком', () => {
    const plan = planFor(view({ budgetMBh: 50 }));
    // 50 МБ/год ≈ 111 кбіт/с усього; 40 лишаємо звуку.
    expect(plan.maxBitrate).toBeLessThanOrEqual(72_000);
    expect(plan.maxBitrate).toBeGreaterThanOrEqual(50_000);
    expect(plan.active).toBe(true);
  });
});

/* ── гістерезис і каденція ───────────────────────────────────────────── */

describe('viewChange: коли приймач шле кадр', () => {
  it('±15% по стороні — шум, не кадр', () => {
    const sent = state({ tiles: [{ w: 1000, h: 1000, vis: true }] });
    expect(viewChange(sent, state({ tiles: [{ w: 1100, h: 900, vis: true }] }))).toBe('skip');
    expect(viewChange(sent, state({ tiles: [{ w: 1200, h: 1000, vis: true }] }))).toBe('debounce');
    expect(viewChange(sent, state({ tiles: [{ w: 1000, h: 800, vis: true }] }))).toBe('debounce');
  });

  it('видимість, сторінка, data saver, бюджет — негайно', () => {
    const sent = state();
    expect(viewChange(null, sent)).toBe('now');
    expect(viewChange(sent, state({ tiles: [{ w: 1080, h: 2340, vis: false }] }))).toBe('now');
    expect(viewChange(sent, state({ fg: 'hidden' }))).toBe('now');
    expect(viewChange(sent, state({ saveData: true }))).toBe('now');
    expect(viewChange(sent, state({ budgetMBh: 100 }))).toBe('now');
    expect(viewChange(sent, state({ batt: { pct: 10, chg: false } }))).toBe('now');
    expect(viewChange(sent, state({ batt: { pct: 80, chg: false } }))).toBe('skip');
  });
});

describe('ViewAdvertiser: hello, дебаунс 250 мс, негайна видимість, серцебиття 10 с', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('каденція як у біблії', () => {
    let current = state({ tiles: [{ w: 1000, h: 1000, vis: true }] });
    const sent: ViewMsg[] = [];
    const ad = new ViewAdvertiser({ read: () => current, send: (m) => sent.push(m) });

    ad.start();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ t: 'view', v: 1, seq: 1 });

    // Анімація розкладки: дрібні кроки — нічого.
    current = state({ tiles: [{ w: 1050, h: 1000, vis: true }] });
    ad.poke();
    vi.advanceTimersByTime(300);
    expect(sent).toHaveLength(1);

    // Великий стрибок серією подій — один кадр після тиші в 250 мс.
    current = state({ tiles: [{ w: 600, h: 600, vis: true }] });
    ad.poke();
    vi.advanceTimersByTime(200);
    current = state({ tiles: [{ w: 500, h: 500, vis: true }] });
    ad.poke();
    vi.advanceTimersByTime(200);
    expect(sent).toHaveLength(1);
    vi.advanceTimersByTime(60);
    expect(sent).toHaveLength(2);
    expect(sent[1].tiles[0].w).toBe(500);

    // Сховали — негайно, без дебаунсу.
    current = state({ tiles: [{ w: 500, h: 500, vis: false }] });
    ad.poke();
    expect(sent).toHaveLength(3);
    expect(sent[2].tiles[0].vis).toBe(false);

    // Серцебиття: той самий стан, новий номер.
    vi.advanceTimersByTime(10_000);
    expect(sent).toHaveLength(4);
    expect(sent[3].seq).toBe(4);

    ad.stop();
    vi.advanceTimersByTime(30_000);
    ad.poke();
    expect(sent).toHaveLength(4);
  });
});

/* ── кадр із дроту ───────────────────────────────────────────────────── */

describe('parseView / parseServe: сміття відкидаємо, числа в межах', () => {
  it('сміття — null', () => {
    for (const raw of [
      null,
      42,
      'not json',
      '[]',
      '{}',
      JSON.stringify({ t: 'serve', v: 1, tiles: [] }),
      JSON.stringify({ t: 'view', v: 2, tiles: [] }),
      JSON.stringify({ t: 'view', v: 1, tiles: 'big' }),
      JSON.stringify({ t: 'view', v: 1, tiles: [{ w: 'a', h: 1, vis: true }] }),
      JSON.stringify({ t: 'view', v: 1, tiles: [{ w: 10, h: 10 }] }),
      JSON.stringify({ t: 'view', v: 1, tiles: [null] }),
      { t: 'view', v: 1, tiles: [{ w: Number.NaN, h: 10, vis: true }] },
      'x'.repeat(5000),
    ]) {
      expect(parseView(raw)).toBeNull();
    }
  });

  it('числа обрізаються, батарея квантується, бюджет без нулів і мінусів', () => {
    const msg = parseView(
      JSON.stringify({
        t: 'view',
        v: 1,
        seq: -5,
        tiles: [{ w: 1e9, h: -20, vis: true }],
        screen: { w: 1e7, h: 'x' },
        saveData: 'yes',
        budgetMBh: -3,
        batt: { pct: 37, chg: 1 },
        fg: 'locked',
      }),
    );
    expect(msg).not.toBeNull();
    expect(msg!.seq).toBe(0);
    expect(msg!.tiles[0]).toEqual({ w: 8192, h: 0, vis: true });
    expect(msg!.screen).toEqual({ w: 8192, h: 0 });
    expect(msg!.saveData).toBe(false);
    expect(msg!.budgetMBh).toBeNull();
    expect(msg!.batt).toEqual({ pct: 40, chg: false });
    expect(msg!.fg).toBe('visible');
  });

  it('плиток не більше 16', () => {
    const tiles = Array.from({ length: 40 }, () => ({ w: 10, h: 10, vis: true }));
    expect(parseView({ t: 'view', v: 1, tiles })!.tiles).toHaveLength(16);
  });

  it('кадр, який шле сам приймач, розбирається назад без втрат', () => {
    const msg = view({ budgetMBh: 120, batt: { pct: 50, chg: true }, saveData: true });
    expect(parseView(JSON.stringify(msg))).toEqual(msg);
  });

  it('serve: сміття — null, числа в межах', () => {
    expect(parseServe('{"t":"serve","v":1}')).toBeNull();
    expect(parseServe({ t: 'serve', v: 1, active: 'yes', h: 1, fps: 1, kbps: 1 })).toBeNull();
    expect(parseServe({ t: 'view', v: 1, active: true, h: 1, fps: 1, kbps: 1 })).toBeNull();
    const ok = parseServe({ t: 'serve', v: 1, seq: 3, active: true, h: 99999, fps: 500, kbps: -4, mbh: 'x' });
    expect(ok).toEqual({ t: 'serve', v: 1, seq: 3, active: true, h: 4320, fps: 120, kbps: 0, mbh: null });
  });
});

/* ── лічильник МБ/год ────────────────────────────────────────────────── */

describe('RateMeter: МБ/год із bytesSent', () => {
  it('рахує з різниці лічильника, а не з плану', () => {
    const m = new RateMeter();
    expect(m.sample(0, 0)).toBeNull();
    // 1 МБ за 2 с = 1800 МБ/год.
    expect(m.sample(1_000_000, 2000)).toBeCloseTo(1800, 5);
    // 100 кБ/с рівно = 360 МБ/год, коли вікно сповзло на нові точки.
    const n = new RateMeter();
    let bytes = 0;
    for (let t = 0; t <= 30_000; t += 2000) {
      n.sample(bytes, t);
      bytes += 200_000;
    }
    expect(n.value()).toBeCloseTo(360, 5);
  });

  it('менше секунди виміру — null; лічильник з нуля — нове вікно', () => {
    const m = new RateMeter();
    m.sample(500, 1000);
    expect(m.sample(900, 1500)).toBeNull();
    m.sample(10_000_000, 4000);
    expect(m.value()).not.toBeNull();
    expect(m.sample(100, 6000)).toBeNull();
    m.reset();
    expect(m.value()).toBeNull();
  });

  it('sentBytesOf: лише outbound-rtp, з заголовками', () => {
    const rows = [
      { type: 'outbound-rtp', kind: 'video', bytesSent: 1000, headerBytesSent: 100 },
      { type: 'outbound-rtp', kind: 'audio', bytesSent: 500 },
      { type: 'inbound-rtp', kind: 'video', bytesReceived: 99999, bytesSent: 7777 },
      { type: 'candidate-pair', bytesSent: 123456 },
    ];
    expect(sentBytesOf(rows)).toBe(1600);
    expect(sentBytesOf([{ type: 'inbound-rtp', bytesReceived: 5 }])).toBeNull();
  });

  it('підпис: ~0,4 / ~12 МБ/год', () => {
    expect(mbhLabel(0.43)).toBe('~0,4 МБ/год');
    expect(mbhLabel(12.4)).toBe('~12 МБ/год');
  });
});

/* ── механіка WebRTC: план справді лягає на відправника ──────────────── */

const fakeSender = (settings: { width?: number; height?: number } = { width: 1280, height: 720 }) => {
  let params: RTCRtpSendParameters = {
    encodings: [{}],
    transactionId: 't',
    codecs: [],
    headerExtensions: [],
    rtcp: {},
  } as unknown as RTCRtpSendParameters;
  const track = {
    kind: 'video',
    applyConstraints: vi.fn(async (c: MediaTrackConstraints) => {
      const h = (c.height as ConstrainULongRange).ideal ?? 720;
      settings = { width: Math.round((h * 16) / 9), height: h };
    }),
    getSettings: () => settings,
  };
  const sender = {
    track,
    getParameters: () => JSON.parse(JSON.stringify(params)),
    setParameters: vi.fn(async (p: RTCRtpSendParameters) => {
      params = p;
    }),
  };
  return { sender: sender as unknown as RTCRtpSender, track, last: () => params };
};

describe('capVideoSender: план → setParameters і applyConstraints', () => {
  it('без ПОГЛЯДУ — 2,0 Мбіт/с, 30 fps, balanced; ніяких 60 fps', async () => {
    const { sender, last } = fakeSender();
    expect(await capVideoSender(sender)).toBe(true);
    const p = last() as RTCRtpSendParameters & { degradationPreference?: string };
    expect(p.encodings[0].maxBitrate).toBe(2_000_000);
    expect(p.encodings[0].maxFramerate).toBe(30);
    expect(p.encodings[0].active).toBe(true);
    expect(p.degradationPreference).toBe('balanced');
  });

  it('маленька плитка — камера знімає менше, енкодер не масштабує вдруге', async () => {
    const { sender, track, last } = fakeSender();
    await capVideoSender(sender, planFor(view({ tiles: [{ w: 320, h: 180, vis: true }] })));
    expect(track.applyConstraints).toHaveBeenCalledWith(
      expect.objectContaining({ height: { ideal: 360 }, frameRate: { ideal: 15, max: 15 } }),
    );
    expect(last().encodings[0].maxBitrate).toBe(100_000);
    expect(last().encodings[0].scaleResolutionDownBy).toBe(1);
    // Той самий план удруге — камеру не смикаємо.
    await capVideoSender(sender, planFor(view({ tiles: [{ w: 320, h: 180, vis: true }] })));
    expect(track.applyConstraints).toHaveBeenCalledTimes(1);
  });

  it('камера не послухалась — енкодер зменшує кадр сам', async () => {
    const { sender, track, last } = fakeSender();
    track.applyConstraints.mockRejectedValueOnce(new Error('OverconstrainedError'));
    await capVideoSender(sender, planFor(view({ tiles: [{ w: 320, h: 180, vis: true }] })));
    expect(last().encodings[0].scaleResolutionDownBy).toBe(2);
  });

  it('плитку сховано — encodings.active=false', async () => {
    const { sender, last } = fakeSender();
    await capVideoSender(sender, planFor(view({ fg: 'hidden' })));
    expect(last().encodings[0].active).toBe(false);
  });

  it('кілька ніг — камеру не стискаємо під одну', async () => {
    const { sender, track } = fakeSender();
    await capVideoSender(sender, planFor(view({ tiles: [{ w: 320, h: 180, vis: true }] })), false);
    expect(track.applyConstraints).not.toHaveBeenCalled();
  });
});

describe('драбина кладе план ПОГЛЯДУ, а не сталу стелю', () => {
  const ladderWith = (patch: Record<string, unknown>) =>
    new OpusLadder({
      pc: () => null,
      localStream: () => null,
      view: () => ({
        callId: 'c', outgoing: true, media: 'video', hasCamera: true, audioLevel: 'crystal',
        ladderPinned: false, videoDropped: false, stats: null, ...patch,
      } as never),
      patch: () => undefined,
      armNote: () => undefined,
      sendOffer: () => undefined,
    });

  it('верхня сходинка — відео на найкращому звуці; будь-який спуск її знімає', () => {
    const top = { videoCapped: false };
    expect(ladderWith(top).onTopTier()).toBe(true);
    expect(ladderWith({ ...top, audioLevel: 'full' }).onTopTier()).toBe(false);
    expect(ladderWith({ ...top, videoDropped: true }).onTopTier()).toBe(false);
    expect(ladderWith({ ...top, hasCamera: false }).onTopTier()).toBe(false);
    expect(ladderWith({ videoCapped: true }).onTopTier()).toBe(false);
  });

  /**
   * D7: 720p — типово. Знімок без поля `videoCapped` — це стеля, а не її
   * відсутність: 1080 дається лише явним кроком драбини вгору.
   * Червоніє на 37831192: немає поля — «стелі нема», верх з першої секунди.
   */
  it('поля videoCapped немає — це стеля 720p, а не верх', () => {
    expect(ladderWith({}).onTopTier()).toBe(false);
  });

  /**
   * Свіжий дзвінок стоїть на 720p і йде вгору лише після рівного каналу:
   * 8 добрих вимірів поспіль (≈16 с). Цей перший підйом — не «повернення
   * якості»: нічого не відбирали, і звістки «Канал вирівнявся» людина не бачить.
   * Червоніє на 37831192: перший підйом казав «повертаємо якість» над
   * дзвінком, якому нічого не знижували.
   */
  it('з 720p угору — лише після 8 добрих вимірів, і без звістки про «повернення»', async () => {
    let v: Record<string, unknown> = {
      callId: 'c', outgoing: false, media: 'video', hasCamera: true, audioLevel: 'crystal',
      ladderPinned: false, videoDropped: false, videoCapped: true, stats: null, linkNote: null,
    };
    const ladder = new OpusLadder({
      pc: () => null,
      localStream: () => null,
      view: () => v as never,
      patch: (p) => {
        v = { ...v, ...p };
      },
      armNote: () => undefined,
      sendOffer: () => undefined,
    });
    const good = (n: number) => ladder.judgeLink(40, 0, n * 100);
    for (let n = 0; n <= 8; n += 1) {
      expect(v.videoCapped, `вимір ${n}`).toBe(true);
      good(n);
    }
    expect(v.videoCapped).toBe(false);
    expect(v.linkNote ?? null).toBeNull();
    expect(ladder.onTopTier()).toBe(true);
  });

  /**
   * Спершу гине роздільність, потім відео, і лише тоді худне звук: перший
   * крок униз з верху — 1080 → 720 при живому відео. Червоніє на коді до
   * 08.10: там перший же крок знімав відео цілком.
   */
  it('перший крок униз — стеля 720p при живому відео; другий — знімає відео', async () => {
    let v: Record<string, unknown> = {
      callId: 'c', outgoing: false, media: 'video', hasCamera: true, audioLevel: 'crystal',
      ladderPinned: false, videoDropped: false, videoCapped: false, stats: null,
    };
    const ladder = new OpusLadder({
      pc: () => null,
      localStream: () => null,
      view: () => v as never,
      patch: (p) => {
        v = { ...v, ...p };
      },
      armNote: () => undefined,
      sendOffer: () => undefined,
    });
    const bad = (n: number) => ladder.judgeLink(700, n * 20, n * 100);
    bad(0);
    bad(1);
    bad(2);
    expect(v).toMatchObject({ videoCapped: true, videoDropped: false, audioLevel: 'crystal' });
    bad(3);
    bad(4);
    expect(v).toMatchObject({ videoDropped: true, audioLevel: 'crystal' });
  });

  it('applyVideo бере план із руки рушія', async () => {
    const { sender, last } = fakeSender();
    const pc = { getSenders: () => [sender] } as unknown as RTCPeerConnection;
    const plan = planFor(view({ tiles: [{ w: 600, h: 338, vis: true }] }));
    const ladder = new OpusLadder({
      pc: () => pc,
      localStream: () => null,
      view: () => ({
        callId: 'c', outgoing: true, media: 'video', hasCamera: true, audioLevel: 'crystal',
        ladderPinned: false, videoDropped: false, stats: null,
      }),
      patch: () => undefined,
      armNote: () => undefined,
      sendOffer: () => undefined,
      videoPlan: () => ({ plan, constrain: true }),
    });
    expect(await ladder.applyVideo()).toBe(true);
    expect(last().encodings[0].maxBitrate).toBe(plan.maxBitrate);
  });
});
