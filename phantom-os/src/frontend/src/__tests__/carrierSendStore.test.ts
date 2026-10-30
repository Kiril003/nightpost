import type { UserProfile } from '../types/messenger';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetPeerCaps, rememberPeerCaps } from '../services/carrier/route';
import {
  chatFromNode,
  messengerApi,
  type NodeConversation,
  type NodeMessage,
  type NodePeerProfile,
} from '../services/messengerApi';
import {
  applyMediaLimitFromNode,
  cachedMediaUrl,
  loadMedia,
} from '../services/messengerMedia';
import { useMessengerStore } from '../stores/messengerStore';

vi.mock('../services/mediaStorageCache', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  const mocked: Record<string, unknown> = { ...actual };
  for (const [k, v] of Object.entries(actual)) {
    if (typeof v === 'function') {
      mocked[k] = vi.fn().mockResolvedValue(null);
    }
  }
  return mocked;
});

function makeDmConv(id: string, peerNodeId: string): NodeConversation {
  return {
    id,
    title: 'Alice',
    kind: 'dm',
    circle: 'all',
    handle: null,
    avatar: null,
    pinned: false,
    archived: false,
    created_at: '2026-04-01T10:00:00Z',
    updated_at: '2026-04-01T10:00:00Z',
    peer_node_id: peerNodeId,
  };
}

function makeNodeRow(id: string, chatId: string, clientId: string, kind: string, body: string | null): NodeMessage {
  return {
    id,
    conversation_id: chatId,
    client_id: clientId,
    seq: 1,
    author_id: 'u_me',
    author_name: 'Me',
    kind,
    body,
    ciphertext: null,
    transport: 'server',
    sent_at: '2026-04-01T10:00:00Z',
    edited_at: null,
    deleted_at: null,
    delivery_state: 'sent',
  };
}

describe('carrierSendStore — sendAttachment and upload_failed retry remember Carrier v2 media', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:carrier-local');
    URL.revokeObjectURL = vi.fn();
    applyMediaLimitFromNode(1024);
    forgetPeerCaps();
    const peer: NodePeerProfile = {
      peer_node_id: 'peer_a',
      display_name: 'Alice',
      carrier: 1,
    };
    rememberPeerCaps([peer]);
    useMessengerStore.setState({
      currentUser: {
        id: 'u_me',
        name: 'Me',
        handle: '@me',
        avatar: '',
        status: 'online',
      } as unknown as UserProfile,
      chats: [chatFromNode(makeDmConv('chat_a', 'peer_a'))],
      activeChatId: 'chat_a',
    });
  });

  it('remembers the uploaded file for tr_1 on sendAttachment so loadMedia does not call carrierGetChunk', async () => {
    const createSpy = vi.spyOn(messengerApi, 'carrierCreate').mockResolvedValue({
      transfer_id: 'tr_1',
      size: 4096,
      n: 1,
      received: [],
      count: 0,
    });
    vi.spyOn(messengerApi, 'carrierPutChunk').mockResolvedValue({
      transfer_id: 'tr_1',
      size: 4096,
      n: 1,
      received: [0],
      count: 1,
    });
    const getChunkSpy = vi.spyOn(messengerApi, 'carrierGetChunk').mockResolvedValue(new Uint8Array(0));
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async (chatId, req) =>
      makeNodeRow('srv_1', chatId, req.client_id, req.kind ?? 'file', req.body ?? null),
    );

    const file = new File([new Uint8Array(4096).fill(5)], 'a.bin', {
      type: 'application/octet-stream',
    });
    await useMessengerStore.getState().sendAttachment(file);

    expect(createSpy).toHaveBeenCalledTimes(1);
    const createArgs = createSpy.mock.calls[0];
    expect(createArgs).toHaveLength(3);

    const msg = useMessengerStore.getState().chats[0].messages[0];
    expect(msg.media?.carrier).toBeDefined();
    const secretHex = msg.media!.carrier!.secret_hex;
    expect(JSON.stringify(createArgs)).not.toContain(secretHex);

    expect(appendSpy).toHaveBeenCalledTimes(1);
    const body = JSON.parse(appendSpy.mock.calls[0][1].body!);
    expect(body.carrier).toEqual(msg.media!.carrier);
    expect(body.key_hex).toBe('');

    expect(cachedMediaUrl('tr_1')).toBeDefined();
    const url = await loadMedia(msg.media!);
    expect(url).toBe(cachedMediaUrl('tr_1'));
    expect(getChunkSpy).not.toHaveBeenCalled();
  });

  it('remembers the uploaded file under the new transferId when upload_failed retrySend succeeds', async () => {
    let call = 0;
    vi.spyOn(messengerApi, 'carrierCreate').mockImplementation(async (size, n) => {
      call += 1;
      return {
        transfer_id: call === 1 ? 'tr_fail_1' : 'tr_retry_2',
        size,
        n,
        received: [],
        count: 0,
      };
    });
    const putSpy = vi
      .spyOn(messengerApi, 'carrierPutChunk')
      .mockRejectedValueOnce(new Error('chunk drop'))
      .mockResolvedValue({
        transfer_id: 'tr_retry_2',
        size: 4096,
        n: 1,
        received: [0],
        count: 1,
      });
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async (chatId, req) =>
      makeNodeRow('srv_2', chatId, req.client_id, req.kind ?? 'file', req.body ?? null),
    );

    const file = new File([new Uint8Array(4096).fill(8)], 'a.bin', {
      type: 'application/octet-stream',
    });
    await useMessengerStore.getState().sendAttachment(file);

    const failedMsg = useMessengerStore.getState().chats[0].messages[0];
    expect(failedMsg.status).toBe('failed');
    expect(failedMsg.attachmentState).toBe('upload_failed');
    expect(appendSpy).not.toHaveBeenCalled();

    await useMessengerStore.getState().retrySend(failedMsg.id);

    expect(putSpy).toHaveBeenCalledTimes(2);
    expect(appendSpy).toHaveBeenCalledTimes(1);
    const body = JSON.parse(appendSpy.mock.calls[0][1].body!);
    expect(body.carrier).toBeDefined();
    expect(body.blob_id).toBe('tr_retry_2');
    expect(cachedMediaUrl('tr_retry_2')).toBeDefined();
  });
});
