/**
 * Розпізнавач, що впав або замовк, не лишає людину з живою камерою, яка
 * нічого не читає.
 *
 * Дві дірки до цього (рецензія WC, 08.10):
 *  - воркер не завантажився (у телефонному бандлі окремого воркера ще не
 *    було, і як його віддасть `WebMessengerAssets` — не доведено): `onerror`
 *    приходив раніше за перший розбір, коли чекати було нікому, і кожен
 *    наступний розбір чекав вічно — камера жива, читання нема, помилки нема;
 *  - `BarcodeDetector.detect` кидав на кожному кадрі — і сканер так і
 *    крутився, не переходячи ні на що інше.
 *
 * Тепер кожен розбір має стелю ~1 с, а кілька відмов поспіль переводять
 * сканер на jsQR тут же, у сторінці; якщо відмовляє й він — чесне «не вдалося
 * ввімкнути розпізнавання».
 *
 * Червоніти вміє: заберіть стелю розбору — падає «воркер замовк»; заберіть
 * перехід на jsQR — падають «впав воркер» і «detect кидає»; заберіть
 * останню відмову — падає «нічого не працює».
 *
 * Друга рецензія (08.10): рідний `BarcodeDetector` буває сліпим МОВЧКИ —
 * модуль Google Play Services ще вантажиться або його нема, і `detect()`
 * віддає `[]`, а не кидає. Порожня відповідь — не відмова, тож сканер світив
 * камерою вічно, не читаючи нічого. Тепер після ~2 с сліпоти через кадр іде
 * jsQR (у воркері, а без нього — у сторінці). Заберіть помічника — падають
 * усі три «рідний сліпий».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, screen } from '@testing-library/react';
import { QrScanner, SCAN_NATIVE_BLIND_MS } from '../components/messenger/QrScanner';

const CODE = 'PH2:cGVlcnxNYXJ0YQ:48377a';

const jsqr = vi.hoisted(() => ({ fn: vi.fn() }));
vi.mock('jsqr', () => ({ default: jsqr.fn }));

const restorers: Array<() => void> = [];
const stub = (target: object, key: string, value: unknown) => {
  const had = Object.getOwnPropertyDescriptor(target, key);
  Object.defineProperty(target, key, { configurable: true, writable: true, value });
  restorers.push(() => {
    if (had) Object.defineProperty(target, key, had);
    else delete (target as Record<string, unknown>)[key];
  });
};
const stubGetter = (target: object, key: string, get: () => unknown) => {
  const had = Object.getOwnPropertyDescriptor(target, key);
  Object.defineProperty(target, key, { configurable: true, get });
  restorers.push(() => {
    if (had) Object.defineProperty(target, key, had);
    else delete (target as Record<string, unknown>)[key];
  });
};

const liveCamera = () => {
  const track = {
    kind: 'video',
    stop: vi.fn(),
    getSettings: () => ({ deviceId: 'cam-main' }),
    getCapabilities: () => ({ focusMode: ['continuous'] }),
  };
  const stream = { id: 's', getTracks: () => [track], getVideoTracks: () => [track] };
  stub(navigator, 'mediaDevices', {
    getUserMedia: vi.fn(async () => stream),
    enumerateDevices: vi.fn(async () => []),
  });
  stub(HTMLMediaElement.prototype, 'play', vi.fn(async () => {}));
  stubGetter(HTMLMediaElement.prototype, 'readyState', () => 4);
  stubGetter(HTMLVideoElement.prototype, 'videoWidth', () => 1280);
  stubGetter(HTMLVideoElement.prototype, 'videoHeight', () => 720);
  const ctx = {
    drawImage: vi.fn(),
    getImageData: vi.fn((_x: number, _y: number, w: number, h: number) => ({
      width: w,
      height: h,
      data: new Uint8ClampedArray(w * h * 4),
    })),
  };
  stub(HTMLCanvasElement.prototype, 'getContext', vi.fn(() => ctx));
};

const tick = async (ms: number) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};

/** Воркер, що або впав на завантаженні, або мовчки ковтає кадри. */
const deadWorker = (how: 'load-error' | 'silent') => {
  const posted: unknown[] = [];
  const terminated = vi.fn();
  class FakeWorker {
    onmessage: ((e: { data: unknown }) => void) | null = null;
    onerror: ((e: unknown) => void) | null = null;
    constructor() {
      // Скрипт воркера не приїхав: помилка — до першого ж розбору.
      if (how === 'load-error') setTimeout(() => this.onerror?.(new Event('error')), 5);
    }
    postMessage(msg: unknown) {
      posted.push(msg);
    }
    terminate = terminated;
  }
  stub(globalThis, 'BarcodeDetector', undefined);
  stub(globalThis, 'Worker', FakeWorker);
  return { posted, terminated };
};

beforeEach(() => {
  vi.useFakeTimers();
  jsqr.fn.mockReset();
  jsqr.fn.mockImplementation(() => ({ data: CODE }));
  liveCamera();
});
afterEach(() => {
  vi.useRealTimers();
  while (restorers.length) restorers.pop()!();
  vi.restoreAllMocks();
});

describe('розпізнавач відмовив — сканер не висить', () => {
  it('воркер упав на завантаженні — за кілька кадрів jsQR у сторінці читає код', async () => {
    const w = deadWorker('load-error');
    const found = vi.fn();
    render(<QrScanner onFound={found} />);
    await tick(2000);
    expect(found).toHaveBeenCalledWith(CODE);
    expect(w.terminated).toHaveBeenCalled();
  });

  it('воркер замовк (жодної відповіді, жодної помилки) — кожен розбір має стелю, далі jsQR', async () => {
    const w = deadWorker('silent');
    const found = vi.fn();
    render(<QrScanner onFound={found} />);
    await tick(1500);
    expect(found).not.toHaveBeenCalled(); // ще чекаємо на воркер
    expect(w.posted.length).toBeGreaterThan(0);
    await tick(4000);
    expect(found).toHaveBeenCalledWith(CODE);
  });

  it('BarcodeDetector.detect кидає на кожному кадрі — перехід на jsQR', async () => {
    const detect = vi.fn(async () => {
      throw new DOMException('кадр не віддається', 'InvalidStateError');
    });
    class BrokenDetector {
      static getSupportedFormats = vi.fn(async () => ['qr_code']);
      detect = detect;
    }
    stub(globalThis, 'BarcodeDetector', BrokenDetector);
    stub(globalThis, 'Worker', undefined);
    const found = vi.fn();
    render(<QrScanner onFound={found} />);
    await tick(2000);
    expect(detect).toHaveBeenCalled();
    expect(found).toHaveBeenCalledWith(CODE);
  });

  it('нічого не працює — чесне «не вдалося ввімкнути розпізнавання», камеру відпущено', async () => {
    jsqr.fn.mockImplementation(() => {
      throw new Error('jsQR зламався');
    });
    deadWorker('load-error');
    const unavailable = vi.fn();
    render(<QrScanner onFound={() => {}} onUnavailable={unavailable} />);
    await tick(4000);
    expect(screen.getByText('Не вдалося ввімкнути розпізнавання QR.')).toBeTruthy();
    expect(unavailable).toHaveBeenCalledTimes(1);
    expect(document.querySelector('video')).toBeNull();
  });
});

describe('рідний BarcodeDetector мовчки сліпий (detect віддає [] на кожен кадр)', () => {
  const blindDetector = () => {
    const detect = vi.fn(async () => [] as Array<{ rawValue: string }>);
    class BlindDetector {
      static getSupportedFormats = vi.fn(async () => ['qr_code']);
      detect = detect;
    }
    stub(globalThis, 'BarcodeDetector', BlindDetector);
    return detect;
  };

  /** Воркер, що справді відповідає: jsQR у ньому читає код. */
  const answeringWorker = () => {
    const posted: unknown[] = [];
    class FakeWorker {
      onmessage: ((e: { data: unknown }) => void) | null = null;
      onerror: ((e: unknown) => void) | null = null;
      postMessage(msg: { id: number }) {
        posted.push(msg);
        setTimeout(() => this.onmessage?.({ data: { id: msg.id, text: CODE } }), 10);
      }
      terminate = vi.fn();
    }
    stub(globalThis, 'Worker', FakeWorker);
    return posted;
  };

  it('воркерів нема — після ~2 с сліпоти jsQR у сторінці читає код, а до того рідний працює сам', async () => {
    const detect = blindDetector();
    stub(globalThis, 'Worker', undefined);
    const found = vi.fn();
    render(<QrScanner onFound={found} />);
    await tick(SCAN_NATIVE_BLIND_MS - 300);
    expect(detect.mock.calls.length).toBeGreaterThan(5);
    expect(jsqr.fn).not.toHaveBeenCalled(); // рідному — його шанс
    expect(found).not.toHaveBeenCalled();
    await tick(2000);
    expect(found).toHaveBeenCalledWith(CODE);
    expect(screen.queryByText('Не вдалося ввімкнути розпізнавання QR.')).toBeNull();
  });

  it('воркер є — помічник іде у воркер, не на головний потік', async () => {
    blindDetector();
    const posted = answeringWorker();
    const found = vi.fn();
    render(<QrScanner onFound={found} />);
    await tick(SCAN_NATIVE_BLIND_MS + 1500);
    expect(posted.length).toBeGreaterThan(0);
    expect(found).toHaveBeenCalledWith(CODE);
    expect(jsqr.fn).not.toHaveBeenCalled();
  });

  it('воркер-помічник упав на завантаженні — помічником стає jsQR у сторінці', async () => {
    const w = deadWorker('load-error');
    // deadWorker прибрав рідний — повертаємо сліпого.
    const detect = blindDetector();
    const found = vi.fn();
    render(<QrScanner onFound={found} />);
    await tick(SCAN_NATIVE_BLIND_MS + 3000);
    expect(found).toHaveBeenCalledWith(CODE);
    expect(w.terminated).toHaveBeenCalled();
    expect(detect).toHaveBeenCalled();
  });
});
