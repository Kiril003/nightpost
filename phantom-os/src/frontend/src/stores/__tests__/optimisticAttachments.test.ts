/**
 * Вкладення: бульбашка одразу, частка вивантаженого, той самий лист на місці,
 * по три вивантаження одночасно, розміри фото в тілі листа.
 *
 * Червоне на попередньому сторі: там бульбашка зʼявлялась лише ПІСЛЯ того, як
 * байти доїхали до вузла, `uploadProgress` не існувало, пачка їхала по
 * одному, а тіло листа не знало розмірів.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../services/imagePrep', async (orig) => ({
  ...(await orig<typeof import('../../services/imagePrep')>()),
  prepareImage: vi.fn(async (file: File) => ({ file })),
}));

import { useMessengerStore } from '../messengerStore';
import { messengerApi } from '../../services/messengerApi';
import { cachedMediaUrl } from '../../services/messengerMedia';
import { prepareImage } from '../../services/imagePrep';

const attach = (name = 'photo.png', type = 'image/png') => {
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const f = new File([bytes], name, { type });
  if (typeof f.arrayBuffer !== 'function') Object.defineProperty(f, 'arrayBuffer', { value: async () => bytes.buffer });
  return f;
};
const flush = async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
};
/** Шифрування (WebCrypto) під навантаженням повільне — чекаємо стану, а не тіків. */
const until = (fn: () => void) => vi.waitFor(fn, { timeout: 5000, interval: 5 });
const msgs = () => useMessengerStore.getState().chats[0].messages;

type Upload = { resolve: (v: unknown) => void; reject: (e: unknown) => void; progress?: (p: number) => void };

/** Шифрування під навантаженням міняє порядок викликів uploadFile — файл вивантаження впізнаємо за станом бульбашки. */
const uploadOf = (list: Upload[], name: string) => {
  const hit = list.find((u, i) => {
    u.progress?.(10 + 5 * i);
    return msgs().find((m) => m.fileName === name)?.uploadProgress === (10 + 5 * i) / 100;
  });
  if (!hit) throw new Error(`немає вивантаження для ${name}`);
  return hit;
};

describe('оптимістичні вкладення', () => {
  let uploads: Upload[];
  beforeEach(() => {
    uploads = [];
    vi.spyOn(messengerApi, 'uploadFile').mockImplementation(((_c: string, _b: Blob, onProgress?: (p: number) => void) =>
      new Promise((resolve, reject) => uploads.push({ resolve, reject, progress: onProgress }))) as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_a', title: 'Олена', avatar: '', type: 'dm', circle: 'work', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_a',
    });
  });
  afterEach(async () => {
    // Невідпущені вивантаження тримали б слоти черги для наступного тесту.
    uploads.forEach((u) => u.reject(new Error('teardown')));
    await flush();
    vi.restoreAllMocks();
  });

  it('бульбашка — одразу, з передоглядом і часткою; потім той самий лист стає справжнім', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'sent' } as never);
    const sending = useMessengerStore.getState().sendAttachment(attach());
    await until(() => expect(uploads).toHaveLength(1));

    expect(msgs()).toHaveLength(1);
    const id = msgs()[0].id;
    expect(msgs()[0].status).toBe('sending');
    expect(msgs()[0].uploadProgress).toBe(0);
    expect(msgs()[0].localPreviewUrl).toBeTruthy();
    expect(msgs()[0].media?.blobId).toBe('');

    uploads[0].progress?.(50);
    expect(msgs()[0].uploadProgress).toBe(0.5);

    uploads[0].resolve({ blob_id: 'blob_1', sha256: 'abc' });
    await sending;
    const done = msgs()[0];
    // Той самий лист, але під id вузла (DL-1): інакше дельта сітки ставила другий.
    expect(done.id).toBe('n1');
    expect(done.uploadProgress).toBeUndefined();
    expect(done.localPreviewUrl).toBeUndefined();
    expect(done.media?.blobId).toBe('blob_1');
    expect(done.status).toBe('sent');
    // Бульбашка не качатиме назад те, що сама щойно вивантажила.
    expect(cachedMediaUrl('blob_1')).toBeTruthy();
    expect(append).toHaveBeenCalledWith('chat_a', expect.objectContaining({ client_id: id }));
  });

  it('вивантаження впало — та сама бульбашка стає failed/upload_failed, частки немає', async () => {
    const sending = useMessengerStore.getState().sendAttachment(attach());
    await until(() => expect(uploads).toHaveLength(1));
    uploads[0].reject(new Error('звʼязок із вузлом обірвався'));
    await sending;
    expect(msgs()).toHaveLength(1);
    expect(msgs()[0].status).toBe('failed');
    expect(msgs()[0].attachmentState).toBe('upload_failed');
    expect(msgs()[0].uploadProgress).toBeUndefined();
  });

  it('пачка з пʼяти: усі бульбашки одразу й по порядку, вивантажень одночасно три, записи — в порядку пачки', async () => {
    const order: string[] = [];
    vi.spyOn(messengerApi, 'appendMessage').mockImplementation((async (_c: string, b: { body: string }) => {
      order.push(JSON.parse(b.body).name);
      return { id: `n_${order.length}`, delivery: 'sent' };
    }) as never);
    const files = [1, 2, 3, 4, 5].map((i) => attach(`p${i}.png`));
    const all = useMessengerStore.getState().sendAttachments(files, { batchId: 'b1', caption: 'відпустка' });
    await until(() => expect(uploads).toHaveLength(3));
    await flush();

    expect(msgs().map((m) => m.fileName)).toEqual(['p1.png', 'p2.png', 'p3.png', 'p4.png', 'p5.png']);
    expect(msgs().map((m) => m.mediaBatch?.index)).toEqual([0, 1, 2, 3, 4]);
    expect(msgs()[0].text).toBe('відпустка');
    expect(uploads).toHaveLength(3);

    // Другий доїхав першим: місце звільнилось — стартує четвертий.
    uploadOf(uploads, 'p2.png').resolve({ blob_id: 'b_2', sha256: 'x' });
    await until(() => expect(uploads).toHaveLength(4));
    expect(order).toEqual([]); // p2 чекає на p1

    uploads.slice().reverse().forEach((u, i) => u.resolve({ blob_id: `b_r${i}`, sha256: 'x' }));
    await until(() => expect(uploads).toHaveLength(5));
    uploads.forEach((u) => u.resolve({ blob_id: 'b_5', sha256: 'x' }));
    expect(await all).toEqual([]);
    expect(order).toEqual(['p1.png', 'p2.png', 'p3.png', 'p4.png', 'p5.png']);
  });

  it('скасування пачки: ще не початі не стартують і бульбашок по собі не лишають', async () => {
    // Кожен лист — свій id від вузла (бульбашка бере його, DL-1).
    let n = 0;
    vi.spyOn(messengerApi, 'appendMessage').mockImplementation((async () => ({ id: `n${++n}`, delivery: 'sent' })) as never);
    let stop = false;
    const files = [1, 2, 3, 4].map((i) => attach(`p${i}.png`));
    const all = useMessengerStore.getState().sendAttachments(files, { cancelled: () => stop });
    await until(() => expect(uploads).toHaveLength(3));
    stop = true;
    uploads.forEach((u, i) => u.resolve({ blob_id: `c_${i}`, sha256: 'x' }));
    const failed = await all;
    expect(failed).toEqual(['«p4.png» скасовано']);
    expect(msgs().map((m) => m.fileName)).toEqual(['p1.png', 'p2.png', 'p3.png']);
  });

  it('фото: зменшене їде на вузол, а width/height — у тілі листа й на бульбашці', async () => {
    const small = attach('IMG.jpg', 'image/jpeg');
    vi.mocked(prepareImage).mockImplementationOnce(async () => ({ file: small, width: 2560, height: 1920, thumb: new Blob([new Uint8Array(3)]), thumbWidth: 320, thumbHeight: 240 }));
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'sent' } as never);
    const sending = useMessengerStore.getState().sendAttachment(attach('IMG.jpg', 'image/jpeg'));
    await until(() => expect(uploads).toHaveLength(1));
    expect(msgs()[0].media?.width).toBe(2560);
    expect(msgs()[0].media?.height).toBe(1920);
    uploads[0].resolve({ blob_id: 'blob_p', sha256: 'abc' });
    await sending;
    const body = JSON.parse((append.mock.calls[0][1] as { body: string }).body);
    expect(body.width).toBe(2560);
    expect(body.height).toBe(1920);
  });
});
