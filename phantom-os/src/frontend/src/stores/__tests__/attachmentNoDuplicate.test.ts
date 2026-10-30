/**
 * DL-1 (ніч 09.10, два телефони): кожне фото, файл і голосове стояло в
 * ВІДПРАВНИКА двічі — одна бульбашка з подвійною галочкою, друга з вічним
 * годинником. Рідний вузол телефона повертає рядок зі СВОЇМ id і
 * `client_id = id` (WebMessengerBridge), тож бульбашка з веб-id `c_…`, яку
 * шлях вкладень не перейменовував (текстовий — перейменовує), не
 * впізнавалась у дельті сітки і лишалась поруч зі справжнім рядком.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../services/imagePrep', async (orig) => ({
  ...(await orig<typeof import('../../services/imagePrep')>()),
  prepareImage: vi.fn(async (file: File) => ({ file })),
}));

import { useMessengerStore } from '../messengerStore';
import { messengerApi } from '../../services/messengerApi';
import { mergeRows } from '../../services/meshDelta';
import type { Message } from '../../types/messenger';

const attach = (name = 'np_photo.jpg', type = 'image/jpeg') => {
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const f = new File([bytes], name, { type });
  if (typeof f.arrayBuffer !== 'function') Object.defineProperty(f, 'arrayBuffer', { value: async () => bytes.buffer });
  return f;
};
const until = (fn: () => void) => vi.waitFor(fn, { timeout: 5000, interval: 5 });
const msgs = () => useMessengerStore.getState().chats[0].messages;

/** Рядок, яким рідний вузол телефона відповідає на лист: власний id, client_id = id. */
const nativeRow = (id: string) => ({ id, client_id: id, seq: 7, kind: 'image', body: '{}', author_id: 'me' });
const asMessage = (r: { id: string }) => ({ id: r.id, isSelf: true, status: 'sent', type: 'image' }) as unknown as Message;
/** Те, що робить дельта сітки (`__phantomMeshDelta` → mergeMessages). */
const applyDelta = (row: ReturnType<typeof nativeRow>) =>
  useMessengerStore.setState((s) => ({
    chats: s.chats.map((c) => ({ ...c, messages: mergeRows(c.messages, [row as never], [], asMessage) })),
  }));

describe('DL-1: вкладення у відправника — один лист, а не два', () => {
  let uploads: Array<(v: unknown) => void>;
  beforeEach(() => {
    uploads = [];
    vi.spyOn(messengerApi, 'uploadFile').mockImplementation((() => new Promise((resolve) => uploads.push(resolve))) as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_a', title: 'oleh', avatar: '', type: 'dm', circle: 'work', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_a',
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it('вузол відповів своїм id — бульбашка бере його, і дельта сітки не додає другої', async () => {
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: '30b3eb8f', delivery: 'sent' } as never);
    const sending = useMessengerStore.getState().sendAttachment(attach());
    await until(() => expect(uploads).toHaveLength(1));
    uploads[0]({ blob_id: 'blob_1', sha256: 'abc' });
    await sending;

    expect(msgs().map((m) => m.id)).toEqual(['30b3eb8f']);
    applyDelta(nativeRow('30b3eb8f'));
    expect(msgs()).toHaveLength(1);
  });

  it('ехо вузла приїхало раніше за відповідь — оптимістична бульбашка зникає, а не стоїть з годинником', async () => {
    let answer: (v: unknown) => void = () => undefined;
    vi.spyOn(messengerApi, 'appendMessage').mockImplementation((() => new Promise((r) => (answer = r))) as never);
    const sending = useMessengerStore.getState().sendAttachment(attach('voice.webm', 'audio/webm'), undefined, undefined, undefined, undefined);
    await until(() => expect(uploads).toHaveLength(1));
    uploads[0]({ blob_id: 'blob_2', sha256: 'abc' });
    await until(() => expect(messengerApi.appendMessage).toHaveBeenCalled());

    applyDelta(nativeRow('7f00aa11'));
    answer({ id: '7f00aa11', delivery: 'sent' });
    await sending;

    expect(msgs().map((m) => m.id)).toEqual(['7f00aa11']);
    expect(msgs().filter((m) => m.status === 'sending' || m.status === 'queued')).toHaveLength(0);
  });
});
