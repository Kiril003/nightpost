import React from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  CARRIER_CHUNK,
  CARRIER_OPEN_MAX,
  ChunkTampered,
  deriveKeys,
  sealChunk,
  type CarrierManifest,
} from '../services/carrier/seal';
import { messengerApi } from '../services/messengerApi';
import { ApiError } from '../services/api';
import {
  MediaNotArrived,
  MediaTampered,
  cachedMediaUrl,
  forgetMedia,
  loadMedia,
} from '../services/messengerMedia';
import { putCachedMediaBlob } from '../services/mediaStorageCache';
import { SecureMediaBubble } from '../components/messenger/SecureMediaBubble';
import { soundFx } from '../utils/messengerSound';
import type { Message, SecureMedia } from '../types/messenger';

vi.mock('../services/mediaStorageCache', () => ({
  getCachedMediaBlob: vi.fn(async () => undefined),
  putCachedMediaBlob: vi.fn(async () => undefined),
  deleteCachedMediaBlob: vi.fn(async () => undefined),
}));

vi.mock('../hooks/useNearViewport', () => ({
  useNearViewport: () => [() => {}, true],
}));

const hexOf = (bytes: Uint8Array): string =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

const readBlobBytes = (blob: Blob): Promise<Uint8Array> => {
  if (typeof blob.arrayBuffer === 'function') {
    return blob.arrayBuffer().then((buf) => new Uint8Array(buf));
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
};

describe('carrier C3 — open chunked files in bubble', () => {
  const size = 2 * CARRIER_CHUNK + 7;
  const n = 3;
  const secret = new Uint8Array(32);
  for (let i = 0; i < secret.length; i += 1) secret[i] = (i * 7 + 3) & 0xff;
  const secretHex = hexOf(secret);

  let plain: Uint8Array;
  let sealedChunks: Uint8Array[];
  let capturedBlob: Blob | undefined;
  let seq = 0;
  let currentBlobId = '';

  beforeAll(async () => {
    plain = new Uint8Array(size);
    for (let i = 0; i < 64; i += 1) {
      plain[i] = (i + 1) & 0xff;
      plain[CARRIER_CHUNK + i] = (i + 101) & 0xff;
    }
    for (let i = 0; i < 7; i += 1) {
      plain[2 * CARRIER_CHUNK + i] = (i + 201) & 0xff;
    }
    const keys = await deriveKeys(secret);
    sealedChunks = [
      await sealChunk(keys, plain.subarray(0, CARRIER_CHUNK), 0, n),
      await sealChunk(keys, plain.subarray(CARRIER_CHUNK, 2 * CARRIER_CHUNK), 1, n),
      await sealChunk(keys, plain.subarray(2 * CARRIER_CHUNK, size), 2, n),
    ];
  });

  beforeEach(() => {
    seq += 1;
    currentBlobId = `tr_test_${seq}`;
    capturedBlob = undefined;
    vi.clearAllMocks();
    vi.spyOn(soundFx, 'playTap').mockImplementation(() => {});
    if (typeof URL.createObjectURL !== 'function') {
      Object.defineProperty(URL, 'createObjectURL', {
        value: () => '',
        configurable: true,
        writable: true,
      });
    }
    if (typeof URL.revokeObjectURL !== 'function') {
      Object.defineProperty(URL, 'revokeObjectURL', {
        value: () => {},
        configurable: true,
        writable: true,
      });
    }
    vi.spyOn(URL, 'createObjectURL').mockImplementation((obj: Blob | MediaSource) => {
      capturedBlob = obj as Blob;
      return 'blob:x';
    });
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  });

  afterEach(() => {
    cleanup();
    if (currentBlobId) forgetMedia(currentBlobId);
    vi.restoreAllMocks();
  });

  const makeManifest = (overrides?: Partial<CarrierManifest>): CarrierManifest => ({
    v: 2,
    size,
    chunk: CARRIER_CHUNK,
    n,
    secret_hex: secretHex,
    name: 'film.bin',
    mime: 'application/octet-stream',
    ...overrides,
  });

  const makeMedia = (blobId = currentBlobId, manifest = makeManifest()): SecureMedia => ({
    name: manifest.name,
    size: manifest.size,
    mime: manifest.mime,
    sha256: '',
    blobId,
    keyHex: '',
    nonceHex: '',
    carrier: manifest,
  });

  const makeMessage = (media: SecureMedia): Message => ({
    id: `msg_${media.blobId}`,
    senderId: 'peer_1',
    senderName: 'Олена',
    senderAvatar: '',
    timestamp: '12:00',
    type: 'file',
    isSelf: false,
    media,
  });

  it('1. opens through the chunk route, never through v1 fetch', async () => {
    const getChunkSpy = vi
      .spyOn(messengerApi, 'carrierGetChunk')
      .mockImplementation(async (_id, i) => sealedChunks[i]);
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    const media = makeMedia();
    await expect(loadMedia(media)).resolves.toBe('blob:x');

    expect(capturedBlob).toBeDefined();
    const openedBytes = await readBlobBytes(capturedBlob!);
    expect(openedBytes.byteLength).toBe(plain.byteLength);
    expect(openedBytes).toEqual(plain);
    expect(getChunkSpy.mock.calls).toEqual([
      [currentBlobId, 0],
      [currentBlobId, 1],
      [currentBlobId, 2],
    ]);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(putCachedMediaBlob).toHaveBeenCalledTimes(1);
    expect(putCachedMediaBlob).toHaveBeenCalledWith(currentBlobId, expect.any(Blob));
  });

  it('2. progress counts the chunks from 0 to n', async () => {
    vi.spyOn(messengerApi, 'carrierGetChunk').mockImplementation(
      async (_id, i) => sealedChunks[i],
    );
    const onProgress = vi.fn();

    await expect(loadMedia(makeMedia(), onProgress)).resolves.toBe('blob:x');
    expect(onProgress.mock.calls).toEqual([
      [0, 3],
      [1, 3],
      [2, 3],
      [3, 3],
    ]);
  });

  it('3. missing chunk maps 404 ApiError to MediaNotArrived without caching', async () => {
    vi.spyOn(messengerApi, 'carrierGetChunk').mockImplementation(async (_id, i) => {
      if (i === 1) throw new ApiError(404, 'UNKNOWN', 'x');
      return sealedChunks[i];
    });

    await expect(loadMedia(makeMedia())).rejects.toBeInstanceOf(MediaNotArrived);
    expect(putCachedMediaBlob).not.toHaveBeenCalled();
    expect(cachedMediaUrl(currentBlobId)).toBeUndefined();
  });

  it('4. tampered chunk maps ChunkTampered to MediaTampered', async () => {
    vi.spyOn(messengerApi, 'carrierGetChunk').mockImplementation(async (_id, i) => {
      if (i === 2) {
        const flipped = new Uint8Array(sealedChunks[2]);
        flipped[0] ^= 0xff;
        return flipped;
      }
      return sealedChunks[i];
    });

    let caught: unknown;
    try {
      await loadMedia(makeMedia());
    } catch (err) {
      caught = err;
    }
    expect(caught).toBeInstanceOf(MediaTampered);
    expect(caught).not.toBeInstanceOf(ChunkTampered);
  });

  it('5. above CARRIER_OPEN_MAX rejects with generic error before any request', async () => {
    const getChunkSpy = vi.spyOn(messengerApi, 'carrierGetChunk');
    const bigSize = CARRIER_OPEN_MAX + 1;
    const bigN = Math.ceil(bigSize / CARRIER_CHUNK);
    const bigMedia = makeMedia(currentBlobId, makeManifest({ size: bigSize, n: bigN }));

    let caught: unknown;
    try {
      await loadMedia(bigMedia);
    } catch (err) {
      caught = err;
    }
    expect(caught).toBeInstanceOf(RangeError);
    expect(caught).not.toBeInstanceOf(MediaNotArrived);
    expect(caught).not.toBeInstanceOf(MediaTampered);
    expect(getChunkSpy).not.toHaveBeenCalled();
  });

  it('6. v1 media without carrier still goes through fetch and never carrierGetChunk', async () => {
    const getChunkSpy = vi.spyOn(messengerApi, 'carrierGetChunk');
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 404 }));

    const v1Media: SecureMedia = {
      name: 'note.txt',
      size: 12,
      mime: 'text/plain',
      sha256: '00'.repeat(32),
      blobId: currentBlobId,
      keyHex: '11'.repeat(32),
      nonceHex: '22'.repeat(12),
    };

    await expect(loadMedia(v1Media)).rejects.toBeInstanceOf(MediaNotArrived);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(getChunkSpy).not.toHaveBeenCalled();
  });

  it('7. bubble shows partMissing on 404 and retries without requestBlob', async () => {
    const requestBlobSpy = vi.spyOn(messengerApi, 'requestBlob');
    let fail = true;
    vi.spyOn(messengerApi, 'carrierGetChunk').mockImplementation(async (_id, i) => {
      if (fail) throw new ApiError(404, 'UNKNOWN', 'missing');
      return sealedChunks[i];
    });

    const media = makeMedia();
    render(React.createElement(SecureMediaBubble, { msg: makeMessage(media), isSelf: false }));

    const waiting = await screen.findByTestId('media-waiting');
    expect(waiting.textContent).toContain('Частина файла ще не доїхала');

    fail = false;
    fireEvent.click(screen.getByRole('button'));

    await screen.findByTestId('media-file');
    expect(requestBlobSpy).not.toHaveBeenCalled();
  });

  it('8. bubble renders opening progress bar while reading and hides it when ready', async () => {
    let releaseChunk1!: () => void;
    const chunk1Gate = new Promise<Uint8Array>((resolve) => {
      releaseChunk1 = () => resolve(sealedChunks[1]);
    });

    vi.spyOn(messengerApi, 'carrierGetChunk').mockImplementation((_id, i) => {
      if (i === 0) return Promise.resolve(sealedChunks[0]);
      if (i === 1) return chunk1Gate;
      return Promise.resolve(sealedChunks[2]);
    });

    const media = makeMedia();
    render(React.createElement(SecureMediaBubble, { msg: makeMessage(media), isSelf: false }));

    await waitFor(() => {
      const bar = screen.getByTestId('media-opening-progress');
      expect(bar.getAttribute('aria-valuenow')).toBe('33');
    });

    releaseChunk1();

    await screen.findByTestId('media-file');
    expect(screen.queryByTestId('media-opening-progress')).toBeNull();
  });
});
