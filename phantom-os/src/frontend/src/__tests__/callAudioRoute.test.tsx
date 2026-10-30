/**
 * Куди йде звук: телефон, динамік, навушники (P0-5, 08.10).
 *
 * Картка питає телефон `GET /messenger/call/audio-route` і перемикає
 * `POST /messenger/call/audio-route {route}`. Старий APK цих маршрутів не
 * знає (404) — тоді лишається те, що було до 08.10: одна кнопка «Звук», що
 * глушить співрозмовника. Падати чи вдавати перемикання вона не має права.
 *
 * Контракт моста (для B0 / CALL-N):
 *   GET  → { routes: [{ id, kind: 'earpiece'|'speaker'|'bluetooth'|'wired', name? }], current: id|null }
 *   POST { route: id } → той самий вигляд після перемикання
 *
 * Червоніє на коді до 08.10: вибору маршруту не було зовсім.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';
import { parseRoutes } from '../components/messenger/call/audioRoute';

type Reply = unknown | { status: number };
let getReply: Reply;
let postReply: Reply;
const calls: Array<[string, string, unknown]> = [];

const fail = (status: number) => {
  const err = new Error(`HTTP ${status}`) as Error & { status: number };
  err.status = status;
  return err;
};

/** Скільки перших GET міст відкине 504 (зайнятий), перш ніж відповісти. */
let getBusyFor = 0;
/**
 * `bridge` — запит іде СПРАВЖНІМ `request` крізь `window.PhantomBridge`, як у
 * WebView APK: відповідь — конверт моста, а не вигадана помилка.
 */
let via: 'fake' | 'bridge' = 'fake';

vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return {
    ...real,
    request: vi.fn(async (method: string, path: string, body?: unknown) => {
      if (via === 'bridge') return real.request(method, path, body);
      calls.push([method, path, body]);
      if (path === '/messenger/call/audio-route') {
        if (method === 'GET' && getBusyFor > 0) {
          getBusyFor -= 1;
          throw fail(504);
        }
        const reply = method === 'GET' ? getReply : postReply;
        if (reply && typeof reply === 'object' && 'status' in (reply as object)) throw fail((reply as { status: number }).status);
        return reply;
      }
      throw fail(404);
    }),
  };
});

/**
 * Міст, у якого B0 відкрив `GET /messenger/call/audio-route`, але POST
 * лишився сигнальним (WebMessengerBridge.kt:543 → handleCallSignal → 400
 * `no_call_id`, :3409) — саме так поводиться сьогоднішній APK з будь-яким
 * POST під `/call/`, якого немає в CALL_ROOM_VERBS.
 */
const halfB0 = (method: string, path: string, body: string | null): string => {
  calls.push([method, path, body ? JSON.parse(body) : undefined]);
  if (path === '/messenger/call/audio-route' && method === 'GET') {
    return JSON.stringify({ ok: true, status: 200, data: ROUTES });
  }
  if (method === 'POST' && path.startsWith('/messenger/call/')) {
    const callId = body ? ((JSON.parse(body) as { call_id?: string }).call_id ?? '') : '';
    if (!callId) return JSON.stringify({ ok: false, status: 400, code: 'no_call_id', detail: 'missing call_id' });
  }
  return JSON.stringify({ ok: false, status: 404, code: 'not_found', detail: `${method} ${path}` });
};

let snap: CallSnapshot;
const toggleSpeaker = vi.fn();
vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: () => () => undefined,
      getSnapshot: () => snap,
      toggleMic: vi.fn(),
      toggleSpeaker: () => toggleSpeaker(),
      toggleCamera: vi.fn(),
      flipCamera: vi.fn(),
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dropLeg: vi.fn(),
    },
  };
});

const ROUTES = {
  routes: [
    { id: 'earpiece', kind: 'earpiece' },
    { id: 'speaker', kind: 'speaker' },
    { id: 'bt:1', kind: 'bluetooth', name: 'Навушники Олі' },
  ],
  current: 'earpiece',
};

const BASE: CallSnapshot = {
  resuming: false,
  state: 'active',
  callId: 'c1',
  peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
  media: 'audio',
  outgoing: true,
  micOn: true,
  cameraOn: false,
  hasCamera: false,
  localStream: null,
  remoteStream: null,
  startedAt: Date.now() - 5_000,
  stats: null,
  endedReason: null,
  stall: null,
  audioLevel: 'crystal',
  ladderPinned: false,
  videoDropped: false,
  radio: null,
  radioReason: null,
  linkNote: null,
  legs: [],
  roomMax: null,
  roomCrowded: false,
  speakerOn: true,
  served: null,
  weakSide: null,
};

const flush = async () => {
  await act(async () => {
    for (let i = 0; i < 5; i += 1) await Promise.resolve();
  });
};

/**
 * Справжній вигляд рідної сторони з cloud/m18-calln (CallAudioRoute.kt,
 * `describe()` / `choose()`): GET — список ВИДІВ рядками, POST — лише
 * `{ ok, current }`, без списку. Без розуміння цього вигляду картка після
 * злиття CALL-N + B0 не показала б вибір ніколи.
 */
const NATIVE_GET = { routes: ['earpiece', 'speaker', 'bluetooth'], current: 'earpiece' };

describe('parseRoutes: відповідь моста', () => {
  /** Червоніє на 18648cc9: рядки відкидались, і вибору не було. */
  it('рідний вигляд CALL-N: маршрути рядками-видами', () => {
    expect(parseRoutes(NATIVE_GET)).toEqual({
      routes: [
        { id: 'earpiece', kind: 'earpiece' },
        { id: 'speaker', kind: 'speaker' },
        { id: 'bluetooth', kind: 'bluetooth' },
      ],
      current: 'earpiece',
    });
    expect(parseRoutes({ routes: ['teleport', ''], current: null })).toBeNull();
  });

  it('приймає відомі види, відкидає сміття', () => {
    expect(parseRoutes(ROUTES)?.routes.map((r) => r.kind)).toEqual(['earpiece', 'speaker', 'bluetooth']);
    expect(parseRoutes(ROUTES)?.current).toBe('earpiece');
    expect(parseRoutes({ delivered: true })).toBeNull();
    expect(parseRoutes({ routes: [] , current: null })).toBeNull();
    expect(parseRoutes({ routes: [{ id: 'x', kind: 'teleport' }], current: 'x' })).toBeNull();
    expect(parseRoutes(null)).toBeNull();
  });
});

describe('вибір маршруту на картці дзвінка', () => {
  beforeEach(() => {
    calls.length = 0;
    getBusyFor = 0;
    via = 'fake';
    delete (window as unknown as Record<string, unknown>).PhantomBridge;
    toggleSpeaker.mockClear();
    getReply = ROUTES;
    postReply = { ...ROUTES, current: 'speaker' };
    snap = { ...BASE };
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    delete (window as unknown as Record<string, unknown>).PhantomBridge;
  });

  it('питає маршрути і перемикає на динамік одним POST', async () => {
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    expect(calls.some(([m, p]) => m === 'GET' && p === '/messenger/call/audio-route')).toBe(true);

    const button = document.querySelector('[data-call-route]') as HTMLElement;
    expect(button).not.toBeNull();
    expect(button.getAttribute('data-call-route')).toBe('earpiece');

    fireEvent.click(button);
    const speaker = screen.getByRole('menuitemradio', { name: 'Динамік' });
    fireEvent.click(speaker);
    await flush();

    const posts = calls.filter(([m, p]) => m === 'POST' && p === '/messenger/call/audio-route');
    expect(posts).toEqual([['POST', '/messenger/call/audio-route', { route: 'speaker' }]]);
    expect((document.querySelector('[data-call-route]') as HTMLElement).getAttribute('data-call-route')).toBe('speaker');
    // Звук співрозмовника від цього не глушиться — це інша дія.
    expect(toggleSpeaker).not.toHaveBeenCalled();
  });

  /**
   * Рідний міст CALL-N: GET — рядки, POST — `{ ok: true, current }`. Вибір
   * зʼявляється, перемикання — один POST, і картка показує те, що апарат
   * назвав поточним, без зайвого перепитування.
   *
   * Червоніє на 18648cc9: рядки → «вибору немає», лишалась лише «Звук».
   */
  it('рідний вигляд CALL-N: вибір є, POST {ok, current} перемикає', async () => {
    getReply = NATIVE_GET;
    postReply = { ok: true, current: 'speaker' };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    const button = document.querySelector('[data-call-route]') as HTMLElement;
    expect(button).not.toBeNull();
    expect(button.getAttribute('data-call-route')).toBe('earpiece');

    fireEvent.click(button);
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Динамік' }));
    await flush();

    expect(calls.filter(([m, p]) => m === 'POST' && p === '/messenger/call/audio-route')).toEqual([
      ['POST', '/messenger/call/audio-route', { route: 'speaker' }],
    ]);
    // GET — на старті і при відкритті меню (могли підʼєднатись навушники);
    // після POST перепитувати не треба: апарат сам назвав новий вихід.
    expect(calls.filter(([m, p]) => m === 'GET' && p === '/messenger/call/audio-route')).toHaveLength(2);
    expect(calls[calls.length - 1][0]).toBe('POST');
    const after = document.querySelector('[data-call-route]') as HTMLButtonElement;
    expect(after.getAttribute('data-call-route')).toBe('speaker');
    expect(after.disabled).toBe(false);
  });

  /**
   * Апарат відмовив (`{ ok: false }`: навушники щойно відʼєднались) — картка
   * не вдає перемикання: лишається попередній вихід, кнопка знову жива, вибір
   * не зникає.
   *
   * Червоніє на 18648cc9: вибору з рідного моста не було зовсім.
   */
  it('рідний вигляд CALL-N: {ok:false} — лишається, що було, кнопка жива', async () => {
    getReply = NATIVE_GET;
    postReply = { ok: false, current: 'earpiece' };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    fireEvent.click(document.querySelector('[data-call-route]') as HTMLElement);
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Бездротові навушники' }));
    await flush();

    const after = document.querySelector('[data-call-route]') as HTMLButtonElement;
    expect(after).not.toBeNull();
    expect(after.getAttribute('data-call-route')).toBe('earpiece');
    expect(after.disabled).toBe(false);
    expect(toggleSpeaker).not.toHaveBeenCalled();
  });

  it('POST дістав 404 — картка не падає і вертається до кнопки «Звук»', async () => {
    postReply = { status: 404 };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    fireEvent.click(document.querySelector('[data-call-route]') as HTMLElement);
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Динамік' }));
    await flush();

    expect(document.querySelector('[data-call-route]')).toBeNull();
    const mute = document.querySelector('[aria-label="Вимкнути звук співрозмовників (S)"]') as HTMLElement;
    expect(mute).not.toBeNull();
    fireEvent.click(mute);
    expect(toggleSpeaker).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[data-call-overlay]')).not.toBeNull();
  });

  it('GET дістав 404 (старий APK) — одразу кнопка «Звук», без вибору', async () => {
    getReply = { status: 404 };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    expect(document.querySelector('[data-call-route]')).toBeNull();
    expect(document.querySelector('[aria-label="Вимкнути звук співрозмовників (S)"]')).not.toBeNull();
    expect(calls.filter(([m]) => m === 'POST')).toEqual([]);
  });
  /**
   * B0 відкрив GET, але POST падає в сигнали дзвінка: справжній конверт 400
   * `no_call_id`. Це «маршруту немає», а не збій: меню, що нічого не
   * перемикає, гірше за стару кнопку «Звук».
   *
   * Червоніє на 37831192: 400 не вважалось відсутністю — дотик мовчки
   * нічого не робив, меню лишалось.
   */
  it('POST дістав справжній 400 no_call_id від моста — назад до кнопки «Звук»', async () => {
    via = 'bridge';
    (window as unknown as Record<string, unknown>).PhantomBridge = { call: halfB0 };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    expect(document.querySelector('[data-call-route]')).not.toBeNull();
    fireEvent.click(document.querySelector('[data-call-route]') as HTMLElement);
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Динамік' }));
    await flush();

    expect(calls.filter(([m, p]) => m === 'POST' && p === '/messenger/call/audio-route')).toHaveLength(1);
    expect(document.querySelector('[data-call-route]')).toBeNull();
    expect(document.querySelector('[aria-label="Вимкнути звук співрозмовників (S)"]')).not.toBeNull();
  });

  /**
   * Перший GET упав на зайнятому мості (504) — це не «вибору немає»: картка
   * перепитує, і щойно міст відповів, вибір маршруту зʼявляється.
   *
   * Червоніє на 37831192: будь-яка відмова першого GET ховала вибір на
   * весь дзвінок, і людині лишалась «Звук», що глушить співрозмовника.
   */
  it('перший GET — 504 (міст зайнятий): картка перепитує, і вибір зʼявляється', async () => {
    vi.useFakeTimers();
    getBusyFor = 2;
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    expect(document.querySelector('[data-call-route]')).toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(calls.filter(([m, p]) => m === 'GET' && p === '/messenger/call/audio-route')).toHaveLength(3);
    expect(document.querySelector('[data-call-route]')).not.toBeNull();
  });

  /**
   * Меню маршрутів не може ховатись під власним відео: панель кнопок — свій
   * шар (stacking context), і її z має бути вищим за віконце SelfView, інакше
   * віконце, перетягнуте в нижній кут, малюється поверх «Телефон»/«Динамік»,
   * а дотик починає тягання.
   *
   * Червоніє на 37831192: панель z-10, віконце z-20.
   */
  it('відкрите меню маршрутів — над своїм відео, не під ним', async () => {
    const cam = { id: 'v', kind: 'video', enabled: true };
    const local = {
      getVideoTracks: () => [cam],
      getAudioTracks: () => [],
      getTracks: () => [cam],
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    } as unknown as MediaStream;
    snap = { ...BASE, media: 'video', hasCamera: true, cameraOn: true, localStream: local };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    await flush();
    fireEvent.click(document.querySelector('[data-call-route]') as HTMLElement);
    const menu = document.querySelector('[data-call-route-menu]') as HTMLElement;
    const self = document.querySelector('[data-call-self]') as HTMLElement;
    expect(menu).not.toBeNull();
    expect(self).not.toBeNull();

    const overlay = document.querySelector('[data-call-overlay]') as HTMLElement;
    const layerOf = (el: HTMLElement): HTMLElement => {
      let cur = el;
      while (cur.parentElement && cur.parentElement !== overlay) cur = cur.parentElement;
      return cur;
    };
    const z = (el: HTMLElement): number => Number(/(?:^|\s)z-(\d+)(?:\s|$)/.exec(el.className)?.[1] ?? 0);
    expect(layerOf(menu)).not.toBe(layerOf(self));
    expect(z(layerOf(menu))).toBeGreaterThan(z(layerOf(self)));
  });
});
