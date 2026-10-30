/**
 * Двері дзвінка відчиняє сам рушій (P0-5, 08.10).
 *
 * Телефон кладе кадр дзвінка у `window.__phantomCallSignal`. До 08.10 ці
 * двері ставив `MessengerRoot` у своєму ефекті — рушій про них не знав, і
 * телефон не мав як дізнатись, що вони вже стоять: кадри, що приїхали до
 * першого рендера, губились (холодний старт від сповіщення). Тепер
 * `attach()` ставить двері сам і ОДИН раз каже телефону
 * `POST /messenger/call/ready` — після цього `PeerCallHub` віддає те, що
 * тримав. Старий APK цього маршруту не знає (404) — це не помилка.
 *
 * Червоніє на коді до 08.10: `attach()` не чіпав `window` і нічого не слав.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const calls: Array<[string, string]> = [];
let readyReply: 'ok' | '404' | '504' = 'ok';
/** Скільки перших «готовий» міст не прийме (504 — зайнятий), перш ніж прийняти. */
let busyFor = 0;

/**
 * Відповідь «моста». Окремою функцією, а не тілом vi.fn: після
 * restoreAllMocks vitest 2 віддає з getMockImplementation() undefined, і
 * обгортка в тесті нижче мусить кликати саме це, а не її.
 */
const respond = async (method: string, path: string): Promise<unknown> => {
  calls.push([method, path]);
  if (path === '/messenger/call/ready') {
    if (readyReply === '404') {
      const err = new Error('Not found') as Error & { status: number };
      err.status = 404;
      throw err;
    }
    if (readyReply === '504' || busyFor > 0) {
      busyFor -= 1;
      const err = new Error('Телефон не відповів') as Error & { status: number; code: string };
      err.status = 504;
      err.code = 'timeout';
      throw err;
    }
    return { ok: true };
  }
  if (path.startsWith('/messenger/ice')) return { iceServers: [], turn: false, ttl: 0 };
  if (path.startsWith('/messenger/call/room')) throw new Error('no bridge');
  return { delivered: true, call_id: 'x', detail: '' };
};

/**
 * `bridge` — запит іде СПРАВЖНІМ `request` крізь `window.PhantomBridge`, як у
 * WebView APK, і відповідь — конверт моста, а не вигадана помилка.
 */
let via: 'fake' | 'bridge' = 'fake';

vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return {
    ...real,
    request: vi.fn((method: string, path: string, body?: unknown) =>
      via === 'bridge' ? real.request(method, path, body) : respond(method, path),
    ),
  };
});

/**
 * Що відповідає СЬОГОДНІШНІЙ APK (WebMessengerBridge.kt): будь-який
 * `POST /messenger/call/<x>`, якого немає в CALL_ROOM_VERBS (:543), іде в
 * handleCallSignal, а той без call_id відповідає 400 `no_call_id` (:3409).
 * Не 404 — саме так старий телефон каже «не знаю такого».
 */
const todayApk = (method: string, path: string, body: string | null): string => {
  calls.push([method, path]);
  if (method === 'POST' && path.startsWith('/messenger/call/')) {
    const verb = path.split('/')[3];
    if (!['room', 'leave', 'drop'].includes(verb)) {
      if (!body) return JSON.stringify({ ok: false, status: 400, code: 'bad_request', detail: 'empty body' });
      const callId = (JSON.parse(body) as { call_id?: string }).call_id ?? '';
      if (!callId) return JSON.stringify({ ok: false, status: 400, code: 'no_call_id', detail: 'missing call_id' });
    }
  }
  return JSON.stringify({ ok: false, status: 404, code: 'not_found', detail: `${method} ${path}` });
};

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => undefined, send: () => undefined },
}));

type Sink = (data: unknown) => void;
const sink = (): Sink | undefined =>
  (window as unknown as { __phantomCallSignal?: Sink }).__phantomCallSignal;
const readyPosts = () => calls.filter(([m, p]) => m === 'POST' && p === '/messenger/call/ready').length;

describe('attach(): двері дзвінка і «я готовий»', () => {
  let engine: typeof import('../services/callEngine');

  beforeEach(async () => {
    calls.length = 0;
    readyReply = 'ok';
    busyFor = 0;
    via = 'fake';
    delete (window as unknown as Record<string, unknown>).PhantomBridge;
    delete (window as unknown as Record<string, unknown>).__phantomCallSignal;
    vi.resetModules();
    engine = await import('../services/callEngine');
  });

  afterEach(() => {
    engine.callEngine.hangup();
    vi.useRealTimers();
    vi.restoreAllMocks();
    delete (window as unknown as Record<string, unknown>).PhantomBridge;
  });

  it('ставить window.__phantomCallSignal, і кадр через нього доходить до рушія', async () => {
    expect(sink()).toBeUndefined();
    const detach = engine.callEngine.attach();
    expect(typeof sink()).toBe('function');

    sink()!({
      channel: 'call',
      type: 'call:offer',
      data: { kind: 'offer', call_id: 'c_door', sdp: 'v=0', media: 'audio', from_node_id: 'f0e1d2c3b4a59687', display_name: 'Марта' },
    });
    await Promise.resolve();
    await Promise.resolve();
    const s = engine.callEngine.getSnapshot();
    expect(s.state).toBe('ringing');
    expect(s.callId).toBe('c_door');
    expect(s.peer?.displayName).toBe('Марта');
    detach();
  });

  it('POST /messenger/call/ready — рівно один раз, ПІСЛЯ того як двері стоять', async () => {
    let doorAtReady: unknown = 'not-called';
    const api = await import('../services/api');
    (api.request as unknown as { mockImplementation: (f: (...a: unknown[]) => unknown) => void }).mockImplementation(
      (...args: unknown[]) => {
        if (args[1] === '/messenger/call/ready') doorAtReady = typeof sink();
        return respond(args[0] as string, args[1] as string);
      },
    );

    const detach = engine.callEngine.attach();
    await Promise.resolve();
    expect(readyPosts()).toBe(1);
    expect(doorAtReady).toBe('function');

    // Повторне підʼєднання (перемонтування кореня) — без другого «готовий».
    engine.callEngine.attach();
    detach();
    engine.callEngine.attach();
    await Promise.resolve();
    expect(readyPosts()).toBe(1);
  });

  it('старий телефон (404) — без винятку, двері лишаються', async () => {
    readyReply = '404';
    const unhandled = vi.fn();
    process.on('unhandledRejection', unhandled);
    try {
      expect(() => engine.callEngine.attach()).not.toThrow();
      await new Promise((r) => setTimeout(r, 10));
      expect(readyPosts()).toBe(1);
      expect(typeof sink()).toBe('function');
      expect(unhandled).not.toHaveBeenCalled();
    } finally {
      process.off('unhandledRejection', unhandled);
    }
  });
  /**
   * Сьогоднішній APK відповідає на `ready` не 404, а 400 `no_call_id`
   * (маршрут падає в сигнали дзвінка). Це та сама відповідь «не знаю
   * такого»: такий телефон кадрів не тримає, і повторювати нема чого.
   *
   * Червоніє на 37831192: 400 вважалось збоєм, і «готовий» ішов тричі.
   */
  it('справжній конверт сьогоднішнього APK (400 no_call_id) — один POST, без повторів', async () => {
    vi.useFakeTimers();
    via = 'bridge';
    (window as unknown as Record<string, unknown>).PhantomBridge = { call: todayApk };
    engine.callEngine.attach();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(readyPosts()).toBe(1);
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(10);
    expect(readyPosts()).toBe(1);
  });

  /**
   * Холодний старт із вхідного дзвінка: міст зайнятий (504), а кадри, які
   * телефон тримає до «готовий», без нього не приїдуть НІКОЛИ. Три спроби
   * за 4,5 с — замало: «готовий» мусить дійти, щойно міст звільниться.
   *
   * Червоніє на 37831192: після третьої відмови рушій замовкав назавжди.
   */
  it('міст зайнятий довше за три спроби — «готовий» усе одно доходить', async () => {
    vi.useFakeTimers();
    busyFor = 4;
    engine.callEngine.attach();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(readyPosts()).toBe(5);
    // Прийняв — більше не шлемо, хоч скільки чекай.
    await vi.advanceTimersByTimeAsync(300_000);
    expect(readyPosts()).toBe(5);
  });

  /**
   * Повтори вичерпались (міст лежав довго) — сторінка, що повернулась на
   * екран, каже «готовий» знову. Прийнятий «готовий» не повторюється.
   *
   * Червоніє на 37831192: на повернення сторінки рушій не зважав.
   */
  it('сторінка повернулась на екран — неприйнятий «готовий» іде знову, прийнятий — ні', async () => {
    vi.useFakeTimers();
    readyReply = '504';
    engine.callEngine.attach();
    await vi.advanceTimersByTimeAsync(600_000);
    const tried = readyPosts();
    expect(tried).toBeGreaterThanOrEqual(3);
    await vi.advanceTimersByTimeAsync(600_000);
    expect(readyPosts()).toBe(tried);

    readyReply = 'ok';
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(10);
    expect(readyPosts()).toBe(tried + 1);

    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(10);
    expect(readyPosts()).toBe(tried + 1);
  });
});
