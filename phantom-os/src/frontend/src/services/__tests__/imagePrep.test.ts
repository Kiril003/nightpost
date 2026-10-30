import { afterEach, describe, expect, it, vi } from 'vitest';
import { fitWithin, prepareImage, targetType, MAX_EDGE, THUMB_EDGE, type ImagePrepDeps } from '../imagePrep';

const file = (name: string, type: string, size: number) => new File([new Uint8Array(size)], name, { type });

const fakeDeps = (w: number, h: number, encodedSize = 1000) => {
  const close = vi.fn();
  const deps: ImagePrepDeps = {
    decode: vi.fn(async () => ({ width: w, height: h, source: 'bmp', close })),
    encode: vi.fn(async (_s, ew, eh, type) => new Blob([new Uint8Array(ew === THUMB_EDGE || eh === THUMB_EDGE ? 50 : encodedSize)], { type })),
  };
  return { deps, close };
};

describe('fitWithin', () => {
  it('вписує довшу сторону і не збільшує', () => {
    expect(fitWithin(4000, 3000, 2560)).toEqual({ width: 2560, height: 1920 });
    expect(fitWithin(3000, 4000, 320)).toEqual({ width: 240, height: 320 });
    expect(fitWithin(800, 600, 2560)).toEqual({ width: 800, height: 600 });
  });
});

describe('prepareImage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('велике фото → 2560 по довшій стороні, JPEG 0.85, розміри зменшеного, мініатюра 320', async () => {
    const { deps, close } = fakeDeps(4000, 3000);
    const out = await prepareImage(file('IMG_1.HEIC.jpg', 'image/jpeg', 5_000_000), deps);
    expect(out.width).toBe(2560);
    expect(out.height).toBe(1920);
    expect(out.file.type).toBe('image/jpeg');
    expect(out.file.size).toBe(1000);
    expect(out.file.name).toMatch(/\.jpg$/);
    expect(deps.encode).toHaveBeenCalledWith('bmp', MAX_EDGE, 1920, 'image/jpeg', 0.85);
    expect(out.thumbWidth).toBe(320);
    expect(out.thumbHeight).toBe(240);
    expect(out.thumb?.size).toBe(50);
    expect(close).toHaveBeenCalled();
  });

  it('мале фото не перекодовується, але розміри й мініатюра є', async () => {
    const { deps } = fakeDeps(1200, 900);
    const f = file('a.jpg', 'image/jpeg', 300_000);
    const out = await prepareImage(f, deps);
    expect(out.file).toBe(f);
    expect(out.width).toBe(1200);
    expect(out.height).toBe(900);
    expect(out.thumb).toBeTruthy();
  });

  it('GIF не зменшується ніколи (анімація), SVG не розкодовується взагалі', async () => {
    const { deps } = fakeDeps(5000, 5000);
    const gif = file('a.gif', 'image/gif', 9_000_000);
    expect((await prepareImage(gif, deps)).file).toBe(gif);
    const svg = file('a.svg', 'image/svg+xml', 1000);
    const out = await prepareImage(svg, deps);
    expect(out).toEqual({ file: svg });
    expect(deps.decode).toHaveBeenCalledTimes(1);
  });

  it('перекодоване вийшло більшим за оригінал — їде оригінал з оригінальними розмірами', async () => {
    const { deps } = fakeDeps(4000, 3000, 9_000_000);
    const f = file('a.png', 'image/png', 2_000_000);
    const out = await prepareImage(f, deps);
    expect(out.file).toBe(f);
    expect(out.width).toBe(4000);
  });

  it('PNG/WebP → WebP (прозорість), решта → JPEG', () => {
    expect(targetType(file('a.png', 'image/png', 1))).toBe('image/webp');
    expect(targetType(file('a.webp', 'image/webp', 1))).toBe('image/webp');
    expect(targetType(file('a.heic', 'image/heic', 1))).toBe('image/jpeg');
  });

  it('браузер не розкодував — оригінал без розмірів, не помилка', async () => {
    const deps: ImagePrepDeps = { decode: async () => { throw new Error('ні'); }, encode: vi.fn() };
    const f = file('a.jpg', 'image/jpeg', 10);
    expect(await prepareImage(f, deps)).toEqual({ file: f });
  });

  it('розкодування в браузері просить EXIF-поворот (imageOrientation: from-image)', async () => {
    const cib = vi.fn(async () => ({ width: 10, height: 20, close: vi.fn() }));
    vi.stubGlobal('createImageBitmap', cib);
    vi.stubGlobal('OffscreenCanvas', class {
      getContext() { return { drawImage: () => undefined }; }
      convertToBlob() { return Promise.resolve(new Blob([new Uint8Array(5)], { type: 'image/jpeg' })); }
    });
    const out = await prepareImage(file('a.jpg', 'image/jpeg', 100));
    expect(cib).toHaveBeenCalledWith(expect.any(File), { imageOrientation: 'from-image' });
    expect(out.width).toBe(10);
    expect(out.height).toBe(20);
  });
});
