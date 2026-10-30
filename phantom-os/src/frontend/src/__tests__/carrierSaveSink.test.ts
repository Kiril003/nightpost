import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/stripMetadata', () => ({ stripMetadata: async (f: File) => f }));
vi.mock('../components/messenger/phoneShell', () => ({ isPhoneShell: () => false }));

import { ApiError } from '../services/api';
import {
  CARRIER_CHUNK,
  CARRIER_OPEN_MAX,
  ChunkTampered,
  chunkCount,
  keysOf,
  sealChunk,
  type CarrierManifest,
} from '../services/carrier/seal';
import { pickFileSink, saveCarrierTo, type CarrierSink } from '../services/carrier/sink';
import { messengerApi } from '../services/messengerApi';
import { MediaNotArrived, MediaTampered, saveCarrierMedia } from '../services/messengerMedia';
import type { SecureMedia } from '../types/messenger';

describe('Carrier streaming save sink (fix 6)', () => {
  const zeroChunk = new Uint8Array(CARRIER_CHUNK);

  const buildManifest = (size = CARRIER_OPEN_MAX + 1): CarrierManifest => ({
    v: 2,
    secret_hex: 'a'.repeat(64),
    size,
    chunk: CARRIER_CHUNK,
    n: chunkCount(size),
    mime: 'application/octet-stream',
    name: 'big.bin',
  });

  const makeLazySealer = async (m: CarrierManifest, flipChunk?: number) => {
    const keys = await keysOf(m);
    return async (i: number): Promise<Uint8Array> => {
      const offset = i * m.chunk;
      const len = Math.min(m.chunk, m.size - offset);
      const plain = len === m.chunk ? zeroChunk : new Uint8Array(len);
      const sealed = await sealChunk(keys, plain, i, m.n);
      if (i === flipChunk) {
        const copy = new Uint8Array(sealed);
        copy[0] ^= 0xff;
        return copy;
      }
      return sealed;
    };
  };

  const makeCountingSink = () => {
    let totalBytes = 0;
    let writes = 0;
    const close = vi.fn(async () => undefined);
    const abort = vi.fn(async () => undefined);
    const sink: CarrierSink = {
      write: async (part: Uint8Array) => {
        totalBytes += part.byteLength;
        writes += 1;
      },
      close,
      abort,
    };
    return { sink, getTotalBytes: () => totalBytes, getWrites: () => writes, close, abort };
  };

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it(
    'streams a file larger than CARRIER_OPEN_MAX to sink and closes once',
    async () => {
      const m = buildManifest();
      const fetchChunk = await makeLazySealer(m);
      const { sink, getTotalBytes, getWrites, close, abort } = makeCountingSink();

      await saveCarrierTo(m, fetchChunk, sink);

      expect(getTotalBytes()).toBe(m.size);
      expect(getWrites()).toBe(m.n);
      expect(close).toHaveBeenCalledTimes(1);
      expect(abort).not.toHaveBeenCalled();
    },
    30_000,
  );

  it(
    'rejects with ChunkTampered and aborts sink when chunk 2 has a flipped byte',
    async () => {
      const m = buildManifest();
      const fetchChunk = await makeLazySealer(m, 2);
      const { sink, close, abort } = makeCountingSink();

      await expect(saveCarrierTo(m, fetchChunk, sink)).rejects.toBeInstanceOf(ChunkTampered);

      expect(abort).toHaveBeenCalledTimes(1);
      expect(close).not.toHaveBeenCalled();
    },
    30_000,
  );

  it('aborts sink and rethrows when sink.close() rejects', async () => {
    const m = buildManifest(CARRIER_CHUNK);
    const fetchChunk = await makeLazySealer(m);
    const { sink, close, abort } = makeCountingSink();
    close.mockRejectedValueOnce(new Error('disk full on close'));

    await expect(saveCarrierTo(m, fetchChunk, sink)).rejects.toThrow('disk full on close');
    expect(close).toHaveBeenCalledTimes(1);
    expect(abort).toHaveBeenCalledTimes(1);
  });

  it(
    'saves via saveCarrierMedia without RangeError and maps 404 to MediaNotArrived',
    async () => {
      const m = buildManifest();
      const sealer = await makeLazySealer(m);
      const spy = vi.spyOn(messengerApi, 'carrierGetChunk').mockImplementation(async (_id, i) => sealer(i));
      const { sink, getTotalBytes, close } = makeCountingSink();
      const media: SecureMedia = {
        name: m.name,
        size: m.size,
        mime: 'application/octet-stream',
        sha256: '',
        blobId: 'transfer_big',
        keyHex: '',
        nonceHex: '',
        carrier: m,
      };

      await expect(saveCarrierMedia(media, sink)).resolves.toBeUndefined();
      expect(getTotalBytes()).toBe(m.size);
      expect(close).toHaveBeenCalledTimes(1);
      expect(spy).toHaveBeenCalledTimes(m.n);

      spy.mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', 'missing'));
      const second = makeCountingSink();
      await expect(saveCarrierMedia(media, second.sink)).rejects.toBeInstanceOf(MediaNotArrived);
      expect(second.abort).toHaveBeenCalledTimes(1);

      const tamperedSealer = await makeLazySealer(m, 0);
      spy.mockImplementationOnce(async (_id, i) => tamperedSealer(i));
      const third = makeCountingSink();
      await expect(saveCarrierMedia(media, third.sink)).rejects.toBeInstanceOf(MediaTampered);
    },
    30_000,
  );

  it('returns null from pickFileSink when showSaveFilePicker is absent', async () => {
    const res = await pickFileSink('a', 'x');
    expect(res).toBeNull();
  });
});
