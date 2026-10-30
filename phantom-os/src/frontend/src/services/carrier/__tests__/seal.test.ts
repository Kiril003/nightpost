/**
 * Carrier C1. Червоне без seal.ts: файл понад 32 МіБ ішов одним AES-GCM
 * через arrayBuffer цілого файла — WebView тримав ≈2× файл і падав.
 */
import { describe, expect, it } from 'vitest';
import {
  CARRIER_CHUNK, CARRIER_OPEN_MAX, ChunkTampered, chunkCount, chunkTag, deriveKeys, isCarrierManifest, keysOf,
  openCarrier, openChunks, sealCarrier, type CarrierManifest, type SealedChunk,
} from '../seal';
import { FRAME, SECRET, gpsMp4, has, readBlob } from '../../__tests__/metadataFixtures';

const SIZE = Math.floor(5.5 * CARRIER_CHUNK);

const patterned = (size: number) => {
  const b = new Uint8Array(size);
  for (let i = 0; i < size; i += 1) b[i] = (i * 31 + (i >> 20)) & 0xff;
  return b;
};

/** Файл, що падає на будь-якому читанні цілим. */
const guarded = (bytes: Uint8Array, name: string, type: string) => {
  const f = new File([bytes as BlobPart], name, { type });
  const seen: number[] = [];
  Object.defineProperty(f, 'arrayBuffer', { value: () => Promise.reject(new Error('whole file read')) });
  const realSlice = f.slice.bind(f);
  Object.defineProperty(f, 'slice', {
    value: (a?: number, b?: number) => {
      const from = a ?? 0;
      const to = Math.min(b ?? f.size, f.size);
      if (from === 0 && to >= f.size && f.size > 16) throw new Error('whole file slice');
      seen.push(to - from);
      return realSlice(a, b);
    },
  });
  return { f, seen };
};

const collect = async (g: AsyncGenerator<SealedChunk>) => {
  const out: SealedChunk[] = [];
  for await (const c of g) out.push(c);
  return out;
};

const byIndex = (chunks: SealedChunk[]) => async (i: number, tag: string) => {
  const c = chunks[i];
  expect(c.tag).toBe(tag);
  return c.bytes;
};

describe('Carrier seal', () => {
  it('5,5 МіБ → 6 шматків, відкривається байт-у-байт, файл цілим не читається', async () => {
    const plain = patterned(SIZE);
    const { f, seen } = guarded(plain, 'big.bin', 'application/octet-stream');
    const { manifest, chunks } = await sealCarrier(f);
    expect(manifest).toMatchObject({ v: 2, size: SIZE, chunk: CARRIER_CHUNK, n: 6, name: 'big.bin' });
    expect(isCarrierManifest(manifest)).toBe(true);
    const sealed = await collect(chunks);
    expect(sealed.map((c) => c.i)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(sealed.map((c) => c.bytes.length)).toEqual([...Array(5).fill(CARRIER_CHUNK + 16), SIZE - 5 * CARRIER_CHUNK + 16]);
    expect(Math.max(...seen)).toBeLessThanOrEqual(CARRIER_CHUNK);
    const back = await readBlob(await openCarrier(manifest, byIndex(sealed)));
    expect(back.length).toBe(SIZE);
    expect(back).toEqual(plain);
  });

  it('маніфест постійного розміру: без списку відбитків і без ключів крім секрету', async () => {
    const { manifest } = await sealCarrier(new File([patterned(3 * CARRIER_CHUNK) as BlobPart], 'a.bin'));
    expect(Object.keys(manifest).sort()).toEqual(['chunk', 'mime', 'n', 'name', 'secret_hex', 'size', 'v']);
  });

  it('шматки 2↔3 переставлено — не відкривається', async () => {
    const { manifest, chunks } = await sealCarrier(new File([patterned(SIZE) as BlobPart], 'a.bin'));
    const sealed = await collect(chunks);
    const swapped = sealed.slice();
    [swapped[2], swapped[3]] = [{ ...sealed[3], tag: sealed[2].tag }, { ...sealed[2], tag: sealed[3].tag }];
    await expect(openCarrier(manifest, byIndex(swapped))).rejects.toMatchObject({ name: 'ChunkTampered', index: 2 });
  });

  it('обрізаний файл (n менше) — останній шматок не відкривається', async () => {
    const { manifest, chunks } = await sealCarrier(new File([patterned(SIZE) as BlobPart], 'a.bin'));
    const sealed = await collect(chunks);
    const cut = { ...manifest, n: 5, size: 5 * CARRIER_CHUNK };
    await expect(openCarrier(cut, byIndex(sealed))).rejects.toBeInstanceOf(ChunkTampered);
  });

  it('один перевернутий байт ламає лише свій індекс', async () => {
    const { manifest, chunks } = await sealCarrier(new File([patterned(SIZE) as BlobPart], 'a.bin'));
    const sealed = await collect(chunks);
    const bad = sealed.map((c) => ({ ...c, bytes: c.bytes.slice() }));
    bad[4].bytes[1000] ^= 0x01;
    const failed: number[] = [];
    await openCarrier(manifest, async (i) => (i === 4 && !failed.includes(4) ? bad[4].bytes : sealed[i].bytes)).catch(
      (e: ChunkTampered) => failed.push(e.index),
    );
    expect(failed).toEqual([4]);
    const ok = await openCarrier(manifest, async (i) => (i === 4 ? sealed[4].bytes : bad[i].bytes));
    expect(ok.size).toBe(SIZE);
  });

  it('однакові шматки шифруються по-різному: nonce свій для кожного індексу', async () => {
    const { chunks } = await sealCarrier(new File([new Uint8Array(2 * CARRIER_CHUNK) as BlobPart], 'z.bin'));
    const [a, b] = await collect(chunks);
    expect(a.bytes.subarray(0, 64)).not.toEqual(b.bytes.subarray(0, 64));
  });

  it('теги однакові на обох кінцях і різні для різних файлів', async () => {
    const a = await sealCarrier(new File([patterned(2 * CARRIER_CHUNK) as BlobPart], 'a.bin'));
    const b = await sealCarrier(new File([patterned(2 * CARRIER_CHUNK) as BlobPart], 'a.bin'));
    const sentA = (await collect(a.chunks)).map((c) => c.tag);
    const recvKeys = await keysOf(a.manifest);
    expect(await Promise.all([0, 1].map((i) => chunkTag(recvKeys, i)))).toEqual(sentA);
    const sentB = (await collect(b.chunks)).map((c) => c.tag);
    expect(sentA[0]).not.toBe(sentB[0]);
    expect(sentA[0]).not.toBe(sentA[1]);
    expect(sentA[0]).toMatch(/^[0-9a-f]{32}$/);
  });

  it('ключі детерміновані від секрету (сумісність із Kotlin-кінцем)', async () => {
    const secret = new Uint8Array(32).map((_, i) => i);
    const k = await deriveKeys(secret);
    expect(Array.from(k.nonceBase).every((x) => x >= 0 && x < 256)).toBe(true);
    expect(await chunkTag(k, 0)).toBe(await chunkTag(await deriveKeys(secret.slice()), 0));
  });

  it('печатається вже чистий файл: GPS із відео не їде, кадр на місці', async () => {
    const src = gpsMp4();
    const { f } = guarded(src, 'VID.mp4', 'video/mp4');
    const { manifest, chunks } = await sealCarrier(f);
    const sealed = await collect(chunks);
    const plain = await readBlob(await openCarrier(manifest, byIndex(sealed)));
    for (const s of SECRET) expect(has(plain, s), s).toBe(false);
    expect(has(plain, FRAME)).toBe(true);
    expect(manifest.mime).toBe('video/mp4');
  });
});

describe('Carrier C2: відкриття шматками і стелі', () => {
  it('openChunks віддає шматки по черзі, не збираючи файл цілим', async () => {
    const plain = patterned(Math.floor(2.5 * CARRIER_CHUNK));
    const { manifest, chunks } = await sealCarrier(new File([plain as BlobPart], 'p.bin'));
    const sealed = await collect(chunks);
    const sizes: number[] = [];
    const got: Uint8Array[] = [];
    for await (const part of openChunks(manifest, byIndex(sealed))) {
      sizes.push(part.length);
      got.push(part);
    }
    expect(sizes).toEqual([CARRIER_CHUNK, CARRIER_CHUNK, plain.length - 2 * CARRIER_CHUNK]);
    expect(await readBlob(new Blob(got as BlobPart[]))).toEqual(plain);
  });

  it('openChunks: шматок коротший за маніфест — ChunkTampered, а не обрізаний файл', async () => {
    const plain = patterned(2 * CARRIER_CHUNK);
    const { manifest, chunks } = await sealCarrier(new File([plain as BlobPart], 'p.bin'));
    const sealed = await collect(chunks);
    const lying: CarrierManifest = { ...manifest, size: 2 * CARRIER_CHUNK - 10 };
    await expect(async () => {
      for await (const _ of openChunks(lying, byIndex(sealed))) void _;
    }).rejects.toBeInstanceOf(ChunkTampered);
  });

  it('openCarrier понад 64 МіБ відмовляє одразу, жодного шматка не тягне', async () => {
    const size = CARRIER_OPEN_MAX + 1;
    const m: CarrierManifest = {
      v: 2, size, chunk: CARRIER_CHUNK, n: chunkCount(size), secret_hex: 'ab'.repeat(32), name: 'x', mime: '',
    };
    let pulled = 0;
    await expect(openCarrier(m, async () => { pulled += 1; return new Uint8Array(); })).rejects.toBeInstanceOf(RangeError);
    expect(pulled).toBe(0);
  });

  it('маніфест більший за власну оголошену стелю — не приймається', async () => {
    const { manifest } = await sealCarrier(new File([patterned(3 * CARRIER_CHUNK) as BlobPart], 'a.bin'));
    expect(isCarrierManifest(manifest, 3 * CARRIER_CHUNK)).toBe(true);
    expect(isCarrierManifest(manifest, 3 * CARRIER_CHUNK - 1)).toBe(false);
  });
});
