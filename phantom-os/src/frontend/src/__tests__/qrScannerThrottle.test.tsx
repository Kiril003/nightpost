/**
 * Розбір кадрів не їсть головний потік: не частіше разу на 150 мс, лише
 * центральний квадрат ≤480 px, рідний `BarcodeDetector`, а без нього — jsQR у
 * воркері.
 *
 * Виміряно на A52 (08.10): jsQR розбирав КОЖЕН кадр анімації цілим кадром
 * камери на головному потоці — 60 разів на секунду по 640×480. Сторінка
 * сіпалась, аркуш «піднімався замерзлим», а кнопка «Зупинити камеру»
 * відповідала із запізненням. `BarcodeDetector` у WebView A52 є.
 *
 * Червоніти вміє: поверніть `requestAnimationFrame(tick)` з розбором на
 * кожному кадрі — падає «≤7 на секунду»; поверніть `canvas.width =
 * video.videoWidth` — падає «≤480»; приберіть гілку BarcodeDetector — падає
 * «рідний розпізнавач».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import QRCode from 'qrcode';
import { QrScanner, SCAN_MAX_SIDE } from '../components/messenger/QrScanner';
import { decodeQrFrame } from '../workers/qrDecode.worker';

const CODE = 'PH2:cGVlcnxNYXJ0YQ:48377a';

const restorers: Array<() => void> = [];
const stub = <T extends object>(target: T, key: string, value: unknown) => {
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

/** Одна камера з автофокусом, 1280×720, що вже «грає». */
const liveCamera = () => {
  const track = {
    kind: 'video',
    stop: vi.fn(),
    getSettings: () => ({ deviceId: 'cam-main' }),
    getCapabilities: () => ({ focusMode: ['continuous'] }),
    applyConstraints: vi.fn(async () => {}),
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
};

let drawImage: ReturnType<typeof vi.fn>;
let getContext: ReturnType<typeof vi.fn>;
const fakeCanvas = () => {
  drawImage = vi.fn();
  const ctx = {
    drawImage,
    getImageData: vi.fn((_x: number, _y: number, w: number, h: number) => ({
      width: w,
      height: h,
      data: new Uint8ClampedArray(w * h * 4),
    })),
  };
  getContext = vi.fn(() => ctx);
  stub(HTMLCanvasElement.prototype, 'getContext', getContext);
};

const tick = async (ms: number) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};

beforeEach(() => {
  vi.useFakeTimers();
  liveCamera();
  fakeCanvas();
});
afterEach(() => {
  vi.useRealTimers();
  while (restorers.length) restorers.pop()!();
  vi.restoreAllMocks();
});

describe('рідний BarcodeDetector', () => {
  const nativeDetector = (answer: (n: number) => Array<{ rawValue: string }>) => {
    const detect = vi.fn(async (_source: unknown) => answer(detect.mock.calls.length));
    class FakeDetector {
      static getSupportedFormats = vi.fn(async () => ['qr_code', 'ean_13']);
      constructor(public opts: unknown) {}
      detect = detect;
    }
    stub(globalThis, 'BarcodeDetector', FakeDetector);
    const worker = vi.fn();
    stub(globalThis, 'Worker', worker);
    return { detect, worker };
  };

  it('не частіше 7 розборів на секунду, і кадр — центральний квадрат ≤480 px', async () => {
    const { detect } = nativeDetector(() => []);
    render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await tick(300); // камера встала, розпізнавач приїхав
    const before = detect.mock.calls.length;
    await tick(1000);
    const perSecond = detect.mock.calls.length - before;
    expect(perSecond).toBeLessThanOrEqual(7);
    expect(perSecond).toBeGreaterThanOrEqual(5);
    await tick(2000);
    expect(detect.mock.calls.length - before).toBeLessThanOrEqual(21);

    // drawImage(video, sx, sy, sw, sh, dx, dy, dw, dh): квадрат із центру, зменшений.
    const [, sx, sy, sw, sh, , , dw, dh] = drawImage.mock.calls.at(-1)!;
    expect(sw).toBe(sh);
    expect(sw).toBe(720);
    expect(sx).toBe((1280 - 720) / 2);
    expect(sy).toBe(0);
    expect(dw).toBeLessThanOrEqual(SCAN_MAX_SIDE);
    expect(dh).toBeLessThanOrEqual(SCAN_MAX_SIDE);
    const canvas = document.querySelector('canvas')!;
    expect(canvas.width).toBeLessThanOrEqual(480);
    expect(canvas.height).toBe(canvas.width);
    // Полотно й контекст — один раз, а не на кожен кадр.
    expect(getContext).toHaveBeenCalledTimes(1);
  });

  it('чужий QR у кадрі (меню) — екран каже «не те», камера світить далі; той самий більше не пропонується', async () => {
    // Рецензія WC (08.10): у закладі в кадрі поруч — QR меню чи Wi-Fi. Досі
    // перше ж прочитане гасило камеру, і людина тиснула «Сканувати» знову —
    // і знову влучала в меню.
    const MENU = 'https://cafe.example/menu';
    const { detect } = nativeDetector((n) => (n < 6 ? [{ rawValue: MENU }] : [{ rawValue: MENU }, { rawValue: CODE }]));
    const found = vi.fn((text: string) => (text === CODE ? undefined : false));
    render(<QrScanner onFound={found} />);
    await tick(2500);
    expect(found.mock.calls.map((c) => c[0])).toEqual([MENU, CODE]);
    const calls = detect.mock.calls.length;
    expect(calls).toBeGreaterThanOrEqual(6);
    await tick(1000);
    expect(detect.mock.calls.length).toBe(calls);
  });

  it('рідний розпізнавач знаходить код — onFound із ним, воркера не створено', async () => {
    const { detect, worker } = nativeDetector((n) => (n >= 3 ? [{ rawValue: ` ${CODE} ` }] : []));
    const found = vi.fn();
    render(<QrScanner onFound={found} onCancel={() => {}} />);
    await tick(1500);
    expect(found).toHaveBeenCalledTimes(1);
    expect(found).toHaveBeenCalledWith(CODE);
    expect(detect.mock.calls[0][0]).toBeInstanceOf(HTMLCanvasElement);
    expect(worker).not.toHaveBeenCalled();
    // Знайшли — більше не розбираємо.
    const calls = detect.mock.calls.length;
    await tick(1000);
    expect(detect.mock.calls.length).toBe(calls);
  });
});

describe('без BarcodeDetector — jsQR у воркері, не на головному потоці', () => {
  const fakeWorker = (answer: (n: number) => string | null) => {
    const created: string[] = [];
    const posted: Array<{ id: number; width: number; height: number; data: ArrayBuffer }> = [];
    class FakeWorker {
      onmessage: ((e: { data: unknown }) => void) | null = null;
      onerror: ((e: unknown) => void) | null = null;
      constructor(url: URL | string) {
        created.push(String(url));
      }
      postMessage(msg: { id: number; width: number; height: number; data: ArrayBuffer }) {
        posted.push(msg);
        const text = answer(posted.length);
        setTimeout(() => this.onmessage?.({ data: { id: msg.id, text } }), 10);
      }
      terminate = vi.fn();
    }
    stub(globalThis, 'BarcodeDetector', undefined);
    stub(globalThis, 'Worker', FakeWorker);
    return { created, posted };
  };

  it('кадри йдуть у воркер ≤7 разів на секунду, кожен ≤480 px', async () => {
    const w = fakeWorker(() => null);
    render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await tick(300);
    const before = w.posted.length;
    await tick(1000);
    const perSecond = w.posted.length - before;
    expect(perSecond).toBeLessThanOrEqual(7);
    expect(perSecond).toBeGreaterThanOrEqual(5);
    expect(w.created).toHaveLength(1);
    expect(w.created[0]).toMatch(/qrDecode\.worker/);
    for (const m of w.posted) {
      expect(m.width).toBeLessThanOrEqual(SCAN_MAX_SIDE);
      expect(m.height).toBe(m.width);
      expect(m.data.byteLength).toBe(m.width * m.height * 4);
    }
  });

  it('воркер упізнав — onFound', async () => {
    fakeWorker((n) => (n >= 2 ? CODE : null));
    const found = vi.fn();
    render(<QrScanner onFound={found} onCancel={() => {}} />);
    await tick(1000);
    expect(found).toHaveBeenCalledWith(CODE);
  });
});

describe('воркер справді читає QR', () => {
  it('справжній QR, намальований у пікселі, розбирається в текст', () => {
    vi.useRealTimers();
    const qr = QRCode.create(CODE, { errorCorrectionLevel: 'L' });
    const modules = qr.modules;
    const scale = 6;
    const quiet = 4;
    const side = (modules.size + quiet * 2) * scale;
    const data = new Uint8ClampedArray(side * side * 4).fill(255);
    for (let y = 0; y < modules.size; y += 1) {
      for (let x = 0; x < modules.size; x += 1) {
        if (!modules.get(y, x)) continue;
        for (let dy = 0; dy < scale; dy += 1) {
          for (let dx = 0; dx < scale; dx += 1) {
            const px = ((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx;
            data[px * 4] = 0;
            data[px * 4 + 1] = 0;
            data[px * 4 + 2] = 0;
          }
        }
      }
    }
    expect(decodeQrFrame({ id: 7, width: side, height: side, data: data.buffer })).toEqual({ id: 7, text: CODE });
    expect(decodeQrFrame({ id: 8, width: 8, height: 8, data: new Uint8ClampedArray(256).buffer })).toEqual({ id: 8, text: null });
  });
});
