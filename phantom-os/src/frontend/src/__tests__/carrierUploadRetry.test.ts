import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/stripMetadata', () => ({ stripMetadata: async (f: File) => f }));
vi.mock('../components/messenger/phoneShell', () => ({ isPhoneShell: () => false }));

import { ApiError } from '../services/api';
import { CARRIER_CHUNK, type CarrierManifest } from '../services/carrier/seal';
import { CarrierUploadFailed, sendCarrier } from '../services/carrier/upload';
import { messengerApi, type NodeCarrierRecord } from '../services/messengerApi';

describe('Carrier upload retry and resume (fix 4)', () => {
  const size = 2 * CARRIER_CHUNK + 10;

  const makeFile = () =>
    new File([new Uint8Array(size)], 'archive.bin', { type: 'application/octet-stream' });

  const makeRecord = (received: number[]): NodeCarrierRecord => ({
    transfer_id: 't1',
    size,
    n: 3,
    received: [...received],
    count: received.length,
  });

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(messengerApi, 'carrierDropChunk').mockResolvedValue({ transfer_id: 't1', size, n: 3, received: [], count: 0 });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('retries 503 twice on chunk 0 with exponential backoff and completes to 100%', async () => {
    const createSpy = vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([]));
    const held = new Set<number>();
    let chunk0Attempts = 0;
    const putSpy = vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(async (_id, i) => {
      if (i === 0 && chunk0Attempts < 2) {
        chunk0Attempts += 1;
        throw new ApiError(503, 'UNKNOWN', 'x');
      }
      held.add(i);
      return makeRecord(Array.from(held).sort((a, b) => a - b));
    });
    const sleep = vi.fn(async (_ms: number) => undefined);
    const progress: number[] = [];

    const res = await sendCarrier(makeFile(), (p) => progress.push(p), undefined, { sleep });
    const manifest: CarrierManifest = res.manifest;

    expect(createSpy).toHaveBeenCalledTimes(1);
    expect(manifest.n).toBe(3);
    expect(putSpy).toHaveBeenCalledTimes(5);
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([500, 1000]);
    expect(progress[progress.length - 1]).toBe(100);
  });

  it('retries network TypeError once and resolves', async () => {
    vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([]));
    const held = new Set<number>();
    let failedOnce = false;
    const putSpy = vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(async (_id, i) => {
      if (i === 1 && !failedOnce) {
        failedOnce = true;
        throw new TypeError('Failed to fetch');
      }
      held.add(i);
      return makeRecord(Array.from(held).sort((a, b) => a - b));
    });
    const sleep = vi.fn(async (_ms: number) => undefined);

    const res = await sendCarrier(makeFile(), undefined, undefined, { sleep });

    expect(res.transferId).toBe('t1');
    expect(putSpy).toHaveBeenCalledTimes(4);
    expect(sleep).toHaveBeenCalledTimes(1);
    expect(sleep).toHaveBeenCalledWith(500);
  });

  it('rejects with CarrierUploadFailed after 4 attempts when 503 persists', async () => {
    vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([]));
    const putSpy = vi
      .spyOn(messengerApi, 'carrierPutChunk')
      .mockRejectedValue(new ApiError(503, 'UNKNOWN', 'unavailable'));
    const sleep = vi.fn(async (_ms: number) => undefined);

    await expect(sendCarrier(makeFile(), undefined, undefined, { sleep })).rejects.toMatchObject({
      name: 'CarrierUploadFailed',
      index: 0,
      transferId: 't1',
      message: 'chunk 0 did not arrive',
    });

    expect(putSpy).toHaveBeenCalledTimes(4);
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([500, 1000, 2000]);
  });

  it('does not retry non-retryable 413', async () => {
    vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([]));
    const putSpy = vi
      .spyOn(messengerApi, 'carrierPutChunk')
      .mockRejectedValue(new ApiError(413, 'PAYLOAD_TOO_LARGE', 'too big'));
    const sleep = vi.fn(async (_ms: number) => undefined);

    let caught: unknown;
    try {
      await sendCarrier(makeFile(), undefined, undefined, { sleep });
    } catch (err) {
      caught = err;
    }

    expect(caught).toBeInstanceOf(CarrierUploadFailed);
    expect((caught as CarrierUploadFailed).index).toBe(0);
    expect((caught as CarrierUploadFailed).transferId).toBe('t1');
    expect(putSpy).toHaveBeenCalledTimes(1);
    expect(sleep).not.toHaveBeenCalled();
  });

  it('skips chunks already present in carrierCreate received list and still reaches 100%', async () => {
    vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([0]));
    const held = new Set<number>([0]);
    const putSpy = vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(async (_id, i) => {
      held.add(i);
      return makeRecord(Array.from(held).sort((a, b) => a - b));
    });
    const sleep = vi.fn(async (_ms: number) => undefined);
    const progress: number[] = [];

    await sendCarrier(makeFile(), (p) => progress.push(p), undefined, { sleep });

    expect(putSpy.mock.calls.map((c) => c[1])).toEqual([1, 2]);
    expect(progress).toEqual([33, 67, 100]);
  });

  it('never leaks secret_hex to node requests, error messages, or console logs', async () => {
    vi.spyOn(crypto, 'getRandomValues').mockImplementation(<T extends ArrayBufferView | null>(arr: T): T => {
      if (arr instanceof Uint8Array) arr.fill(0xab);
      return arr;
    });
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const createSpy = vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([]));
    const held = new Set<number>();
    const putSpy = vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(async (_id, i) => {
      held.add(i);
      return makeRecord(Array.from(held).sort((a, b) => a - b));
    });
    const sleep = vi.fn(async (_ms: number) => undefined);

    const { manifest } = await sendCarrier(makeFile(), undefined, undefined, { sleep });
    const secret = manifest.secret_hex;

    expect(secret).toBe('ab'.repeat(32));
    expect(JSON.stringify(createSpy.mock.calls)).not.toContain(secret);
    const putMeta = putSpy.mock.calls.map(([id, idx]) => [id, idx]);
    expect(JSON.stringify(putMeta)).not.toContain(secret);

    putSpy.mockRejectedValueOnce(new ApiError(503, 'UNKNOWN', 'transient'));
    putSpy.mockRejectedValueOnce(new ApiError(413, 'PAYLOAD_TOO_LARGE', 'too big'));
    let failedErr: Error | undefined;
    try {
      await sendCarrier(makeFile(), undefined, undefined, { sleep });
    } catch (err) {
      failedErr = err as Error;
    }

    expect(failedErr).toBeInstanceOf(CarrierUploadFailed);
    expect(JSON.stringify(createSpy.mock.calls)).not.toContain(secret);
    expect(failedErr?.message).not.toContain(secret);
    expect(failedErr?.message).not.toMatch(/[0-9a-f]{32,}/i);
    expect(JSON.stringify(errSpy.mock.calls)).not.toContain(secret);
    expect(JSON.stringify(warnSpy.mock.calls)).not.toContain(secret);
  });

  it('drops the pieces it already left on the node when the transfer fails', async () => {
    vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue(makeRecord([]));
    const held = new Set<number>();
    vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(async (_id, i) => {
      if (i === 2) throw new ApiError(413, 'PAYLOAD_TOO_LARGE', 'too big');
      held.add(i);
      return makeRecord(Array.from(held).sort((a, b) => a - b));
    });
    const dropSpy = vi.spyOn(messengerApi, 'carrierDropChunk').mockResolvedValue(makeRecord([]));

    await expect(sendCarrier(makeFile(), undefined, undefined, { sleep: async () => undefined })).rejects.toBeInstanceOf(
      CarrierUploadFailed,
    );

    expect(dropSpy.mock.calls.map(([id, i]) => [id, i]).sort()).toEqual([
      ['t1', 0],
      ['t1', 1],
    ]);
  });
});
