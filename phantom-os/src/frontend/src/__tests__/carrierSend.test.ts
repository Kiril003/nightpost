/**
 * Carrier C2 у сторі. Червоне без маршруту: файл понад оголошену стелю людини
 * вивантажувався й записувався листом, який її збірка не прийме ніколи.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { forgetPeerCaps, rememberPeerCaps } from '../services/carrier/route';
import { CARRIER_CHUNK, isCarrierManifest } from '../services/carrier/seal';
import { MEDIA_PLAIN_LIMIT_BYTES } from '../services/messengerMedia';
import { messageFromNode, type NodeMessage, type NodePeerProfile } from '../services/messengerApi';

const MB = 1024 * 1024;
const peer = (extra: Partial<NodePeerProfile>) => ({ peer_node_id: 'node_olena', ...extra }) as NodePeerProfile;
const nodeCarrier = (id: string) => {
  const got = new Set<number>();
  return {
    create: (size: number, n: number) => Promise.resolve({ transfer_id: id, size, n, received: [], count: 0 }),
    put: (_t: string, i: number) => {
      got.add(i);
      return Promise.resolve({ transfer_id: id, size: 0, n: 0, received: [...got], count: got.size });
    },
  };
};
const bin = (size: number, name = 'big.bin') => new File([new Uint8Array(size) as BlobPart], name, { type: 'application/octet-stream' });

beforeEach(() => {
  useMessengerStore.setState({
    chats: [{ id: 'chat_o', title: 'Олена', avatar: '', type: 'dm', circle: 'work', unreadCount: 0, peerNodeId: 'node_olena', messages: [] }],
    activeChatId: 'chat_o',
  } as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  forgetPeerCaps();
});

describe('Carrier C2: надсилання', () => {
  it('стеля людини нижча — відмова ДО вивантаження, бульбашки немає', async () => {
    rememberPeerCaps([peer({ max_attachment_bytes: 1 * MB })]);
    const upload = vi.spyOn(messengerApi, 'uploadFile');
    const append = vi.spyOn(messengerApi, 'appendMessage');
    await expect(useMessengerStore.getState().sendAttachment(bin(2 * MB))).rejects.toThrow(/big\.bin/);
    expect(upload).not.toHaveBeenCalled();
    expect(append).not.toHaveBeenCalled();
    expect(useMessengerStore.getState().chats[0].messages).toHaveLength(0);
  });

  it('понад мою v1-стелю, обидва везуть шматки — v2: шматки на вузол, у листі маніфест, без ключа v1', async () => {
    rememberPeerCaps([peer({ carrier: 1, max_attachment_bytes: 2048 * MB })]);
    const size = MEDIA_PLAIN_LIMIT_BYTES + CARRIER_CHUNK + 5;
    const n = Math.ceil(size / CARRIER_CHUNK);
    const node = nodeCarrier('tr_1');
    const create = vi.spyOn(messengerApi, 'carrierCreate').mockImplementation(node.create);
    const put = vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(node.put);
    const upload = vi.spyOn(messengerApi, 'uploadFile');
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'row_1', delivery: 'sent' } as never);

    await useMessengerStore.getState().sendAttachment(bin(size));

    expect(upload).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledWith(size, n, expect.any(Array));
    expect(put).toHaveBeenCalledTimes(n);
    expect(put.mock.calls.map((c) => c[1])).toEqual([...Array(n).keys()]);
    const body = JSON.parse(String(append.mock.calls[0][1].body));
    expect(body.blob_id).toBe('tr_1');
    expect(body.key_hex).toBe('');
    expect(isCarrierManifest(body.carrier)).toBe(true);
    expect(body.carrier.size).toBe(size);
  });

  it('людина шматків не везе — понад мою стелю моя ж відмова, без вивантаження й без «оновись»', async () => {
    rememberPeerCaps([peer({ carrier: 0, max_attachment_bytes: 2048 * MB })]);
    const upload = vi.spyOn(messengerApi, 'uploadFile');
    const create = vi.spyOn(messengerApi, 'carrierCreate');
    const err = await useMessengerStore.getState().sendAttachment(bin(MEDIA_PLAIN_LIMIT_BYTES + 10)).catch((e: Error) => e);
    expect(String(err)).toMatch(/big\.bin/);
    expect(String(err)).not.toMatch(/Nightpost/);
    expect(upload).not.toHaveBeenCalled();
    expect(create).not.toHaveBeenCalled();
  });

  const manifest = { v: 2, size: 9 * MB, chunk: CARRIER_CHUNK, n: 9, secret_hex: 'ab'.repeat(32), name: 'film.mp4', mime: 'video/mp4' };
  const v2Body = (extra: Record<string, unknown> = {}) =>
    JSON.stringify({ name: 'film.mp4', size: 9 * MB, mime: 'video/mp4', sha256: '', blob_id: 'tr_9', key_hex: '', nonce_hex: '', carrier: manifest, caption: 'з моря', ...extra });

  it('отримувач: тіло v2 з порожнім key_hex — вкладення, підпис і маніфест на місці', () => {
    const row = { id: 'r1', conversation_id: 'chat_o', client_id: 'c1', seq: 1, author_id: 'node_olena', author_name: 'Олена', kind: 'file', body: v2Body(), sent_at: '2026-10-18T00:00:00Z' } as unknown as NodeMessage;
    const m = messageFromNode(row, 'me');
    expect(m.media?.blobId).toBe('tr_9');
    expect(m.media?.carrier?.n).toBe(9);
    expect(m.text).toBe('з моря');
  });

  it('пересилання листа з carrier: тіло несе маніфест', async () => {
    rememberPeerCaps([peer({ carrier: 1, max_attachment_bytes: 2048 * MB })]);
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'row_f', delivery: 'sent' } as never);
    const row = { id: 'r1', conversation_id: 'chat_o', client_id: 'c1', seq: 1, author_id: 'node_olena', author_name: 'Олена', kind: 'file', body: v2Body(), sent_at: '2026-10-18T00:00:00Z' } as unknown as NodeMessage;
    await useMessengerStore.getState().forwardMessage(messageFromNode(row, 'me'), 'chat_o');
    const body = JSON.parse(String(append.mock.calls[0][1].body));
    expect(body.carrier).toEqual(manifest);
    expect(body.blob_id).toBe('tr_9');
  });

  it('повтор після невдалого вивантаження великого файла йде шматками, а не v1', async () => {
    rememberPeerCaps([peer({ carrier: 1, max_attachment_bytes: 2048 * MB })]);
    const size = MEDIA_PLAIN_LIMIT_BYTES + CARRIER_CHUNK + 5;
    const node = nodeCarrier('tr_2');
    const create = vi.spyOn(messengerApi, 'carrierCreate').mockRejectedValueOnce(new Error('offline')).mockImplementation(node.create);
    vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation(node.put);
    const upload = vi.spyOn(messengerApi, 'uploadFile');
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'row_2', delivery: 'sent' } as never);
    await useMessengerStore.getState().sendAttachment(bin(size));
    const failed = useMessengerStore.getState().chats[0].messages[0];
    expect(failed.attachmentState).toBe('upload_failed');
    await useMessengerStore.getState().retrySend(failed.id);
    expect(upload).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledTimes(2);
    const body = JSON.parse(String(append.mock.calls[0][1].body));
    expect(body.blob_id).toBe('tr_2');
    expect(isCarrierManifest(body.carrier)).toBe(true);
  });
  it('повтор після падіння меж співрозмовника не вивантажує нічого і каже чому', async () => {
    rememberPeerCaps([peer({ carrier: 1, max_attachment_bytes: 2048 * MB })]);
    const size = MEDIA_PLAIN_LIMIT_BYTES + CARRIER_CHUNK + 5;
    const create = vi.spyOn(messengerApi, 'carrierCreate').mockRejectedValue(new Error('offline'));
    const upload = vi.spyOn(messengerApi, 'uploadFile').mockResolvedValue({ blob_id: 'b_1', sha256: '' } as never);
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'row_3', delivery: 'sent' } as never);
    await useMessengerStore.getState().sendAttachment(bin(size));
    const failed = useMessengerStore.getState().chats[0].messages[0];
    expect(failed.attachmentState).toBe('upload_failed');
    rememberPeerCaps([peer({ max_attachment_bytes: 1 * MB })]);
    await useMessengerStore.getState().retrySend(failed.id);
    expect(create).toHaveBeenCalledTimes(1);
    expect(upload).not.toHaveBeenCalled();
    const after = useMessengerStore.getState().chats[0].messages[0];
    expect(after.attachmentState).toBe('upload_failed');
    expect(after.attachmentError).toContain('big.bin');
  });
});
