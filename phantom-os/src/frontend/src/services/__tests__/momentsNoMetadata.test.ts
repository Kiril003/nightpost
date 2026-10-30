/** Досяжність: фото Моменту доїжджає до вузла без GPS (розшифровуємо те, що пішло в uploadFile). */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../api', async (orig) => ({ ...(await orig<typeof import('../api')>()), request: bridge.request }));

import { useMoments } from '../moments';
import { messengerApi } from '../messengerApi';
import { SECRET, gpsJpeg, has, jpegMarkers, openSealed, polyfillBlobArrayBuffer } from './metadataFixtures';

describe('Момент їде без метаданих', () => {
  beforeAll(polyfillBlobArrayBuffer);
  afterEach(() => vi.restoreAllMocks());

  it('фото з GPS: на вузлі ні GPS, ні моделі, ні часу', async () => {
    const uploads: Blob[] = [];
    vi.spyOn(messengerApi, 'uploadFile').mockImplementation((async (_c: string, b: Blob) => {
      uploads.push(b);
      return { blob_id: 'm'.repeat(32), sha256: 'x' };
    }) as never);
    let wire: Record<string, unknown> = {};
    bridge.request.mockImplementation(async (_m: string, _p: string, body: { media: Record<string, unknown>[] }) => {
      wire = body.media[0];
      return {
        id: 'mo1', from_id: 'me', from_name: '', mine: true, at_ms: Date.now(), until_ms: Date.now() + 86_400_000,
        keep: false, audience: { kind: 'contacts' }, text: '', seen: false, to_count: 0, media: [{ ...wire, state: 'ready' }],
      };
    });
    await useMoments.getState().post(new File([gpsJpeg() as BlobPart], 'IMG_0002.jpg', { type: 'image/jpeg' }), '', { kind: 'contacts' });
    expect(uploads).toHaveLength(1);
    const plain = await openSealed(uploads[0], String(wire.key_hex), String(wire.nonce_hex));
    for (const s of SECRET) expect(has(plain, s), s).toBe(false);
    expect(jpegMarkers(plain)).toEqual([0xe0, 0xdb, 0xc0, 0xc4, 0xc4, 0xda, 0xd9]);
    expect(wire.size).toBe(plain.length);
  });
});
