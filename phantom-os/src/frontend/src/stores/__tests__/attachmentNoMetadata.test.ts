/**
 * Досяжність: те, що композер передає в sendAttachment, доїжджає до вузла
 * без GPS. Розшифровуємо САМЕ ті байти, що пішли в uploadFile, ключем із
 * тіла листа. Червоне до stripMetadata: мале фото й відео їхали як є.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessengerStore } from '../messengerStore';
import { messengerApi } from '../../services/messengerApi';
import { FRAME, SECRET, gpsJpeg, gpsMp4, has, jpegMarkers, openSealed, orientationOf, polyfillBlobArrayBuffer } from '../../services/__tests__/metadataFixtures';

const sentBytes = async (file: File) => {
  const uploads: Blob[] = [];
  vi.spyOn(messengerApi, 'uploadFile').mockImplementation((async (_c: string, b: Blob) => {
    uploads.push(b);
    return { blob_id: `blob_${uploads.length}`, sha256: 'x' };
  }) as never);
  const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'sent' } as never);
  await useMessengerStore.getState().sendAttachment(file);
  expect(uploads).toHaveLength(1);
  const body = JSON.parse(String((append.mock.calls[0][1] as { body: string }).body));
  const plain = await openSealed(uploads[0], body.key_hex, body.nonce_hex);
  expect(body.size).toBe(plain.length);
  return plain;
};

describe('вкладення їде без метаданих', () => {
  beforeAll(polyfillBlobArrayBuffer);
  beforeEach(() => {
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_a', title: 'Олена', avatar: '', type: 'dm', circle: 'work', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_a',
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it('мале фото з GPS: на вузлі немає ні GPS, ні моделі, ні часу; поворот лишився', async () => {
    const plain = await sentBytes(new File([gpsJpeg(6) as BlobPart], 'IMG_0001.jpg', { type: 'image/jpeg' }));
    for (const s of SECRET) expect(has(plain, s), s).toBe(false);
    expect(has(plain, Uint8Array.from([0x88, 0x25]))).toBe(false);
    expect(jpegMarkers(plain)).toEqual([0xe0, 0xe1, 0xdb, 0xc0, 0xc4, 0xc4, 0xda, 0xd9]);
    expect(orientationOf(plain.subarray(30, 56))).toBe(6);
  });

  it('відео як файл: ©xyz не їде, кадр той самий', async () => {
    const plain = await sentBytes(new File([gpsMp4() as BlobPart], 'VID_0001.mp4', { type: 'video/mp4' }));
    for (const s of SECRET) expect(has(plain, s), s).toBe(false);
    expect(has(plain, FRAME)).toBe(true);
  });
});
