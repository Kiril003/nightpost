import type { UserProfile } from '../types/messenger';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tNow } from '../components/chat/tNow';
import { forgetPeerCaps, rememberPeerCaps } from '../services/carrier/route';
import { sealCarrier } from '../services/carrier/seal';
import {
  chatFromNode,
  messengerApi,
  type NodeConversation,
  type NodeMessage,
  type NodePeerProfile,
} from '../services/messengerApi';
import { applyMediaLimitFromNode } from '../services/messengerMedia';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import type { Message, SecureMedia } from '../types/messenger';

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

function makeConv(
  id: string,
  title: string,
  kind: 'dm' | 'group',
  peerNodeId: string | null,
): NodeConversation {
  return {
    id,
    title,
    kind,
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

describe('carrierForwardRetry — routeForChat gates forwardMessage and plain retrySend', () => {
  let carrierMsg: Message;

  beforeEach(async () => {
    vi.restoreAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:carrier-test');
    URL.revokeObjectURL = vi.fn();
    applyMediaLimitFromNode(1024);
    forgetPeerCaps();

    const { manifest } = await sealCarrier(
      new File([new Uint8Array(4096).fill(3)], 'a.bin', { type: 'application/octet-stream' }),
    );
    const media: SecureMedia = {
      name: 'a.bin',
      size: manifest.size,
      mime: 'application/octet-stream',
      sha256: '',
      blobId: 'tr_1',
      keyHex: '',
      nonceHex: '',
      carrier: manifest,
    };
    carrierMsg = {
      id: 'm_carrier_1',
      senderId: 'u_me',
      senderName: 'Me',
      senderAvatar: '',
      timestamp: '10:00',
      type: 'file',
      media,
      isSelf: true,
      status: 'sent',
    };

    const peerA: NodePeerProfile = {
      peer_node_id: 'peer_a',
      display_name: 'Alice',
      carrier: 1,
    };
    rememberPeerCaps([peerA]);

    useMessengerStore.setState({
      currentUser: {
        id: 'u_me',
        name: 'Me',
        handle: '@me',
        avatar: '',
        status: 'online',
      } as unknown as UserProfile,
      chats: [
        chatFromNode(makeConv('chat_a', 'Alice', 'dm', 'peer_a')),
        chatFromNode(makeConv('chat_b', 'Bob', 'dm', 'peer_b')),
        chatFromNode(makeConv('chat_grp', 'Team', 'group', null)),
        chatFromNode(makeConv('chat_c', 'Carol', 'dm', 'peer_c')),
      ],
      activeChatId: 'chat_a',
      isForwardModalOpen: true,
      activeForwardMessage: carrierMsg,
    });
  });

  it('refuses forwarding a carrier message to a peer without caps', async () => {
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage');
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');

    await useMessengerStore.getState().forwardMessage(carrierMsg, 'chat_b');

    expect(appendSpy).not.toHaveBeenCalled();
    const chatB = useMessengerStore.getState().chats.find((c) => c.id === 'chat_b')!;
    expect(chatB.messages).toHaveLength(0);
    expect(useMessengerStore.getState().isForwardModalOpen).toBe(false);
    expect(useMessengerStore.getState().activeForwardMessage).toBeNull();
    expect(toastSpy).toHaveBeenCalledWith({
      kind: 'error',
      message: tNow('mediaC6.forwardNoCarrier'),
    });
  });

  it('refuses forwarding a carrier message to a group target', async () => {
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage');
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');

    await useMessengerStore.getState().forwardMessage(carrierMsg, 'chat_grp');

    expect(appendSpy).not.toHaveBeenCalled();
    const chatGrp = useMessengerStore.getState().chats.find((c) => c.id === 'chat_grp')!;
    expect(chatGrp.messages).toHaveLength(0);
    expect(useMessengerStore.getState().isForwardModalOpen).toBe(false);
    expect(toastSpy).toHaveBeenCalledWith({
      kind: 'error',
      message: tNow('mediaC6.forwardNoCarrier'),
    });
  });

  it('refuses forwarding a v1 media message of 8 KiB to a peer with max_attachment_bytes: 4096', async () => {
    applyMediaLimitFromNode(65536);
    const peerC: NodePeerProfile = {
      peer_node_id: 'peer_c',
      display_name: 'Carol',
      max_attachment_bytes: 4096,
    };
    rememberPeerCaps([peerC]);

    const appendSpy = vi.spyOn(messengerApi, 'appendMessage');
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');

    const v1Media: SecureMedia = {
      name: 'big.bin',
      size: 8192,
      mime: 'application/octet-stream',
      sha256: 'aa'.repeat(32),
      blobId: 'blob_v1',
      keyHex: 'bb'.repeat(32),
      nonceHex: 'cc'.repeat(12),
    };
    const v1Msg: Message = {
      ...carrierMsg,
      id: 'm_v1_8k',
      media: v1Media,
    };

    await useMessengerStore.getState().forwardMessage(v1Msg, 'chat_c');

    expect(appendSpy).not.toHaveBeenCalled();
    const chatC = useMessengerStore.getState().chats.find((c) => c.id === 'chat_c')!;
    expect(chatC.messages).toHaveLength(0);
    expect(useMessengerStore.getState().isForwardModalOpen).toBe(false);
    expect(toastSpy).toHaveBeenCalledWith({
      kind: 'error',
      message: tNow('carrier.peerTooOld', { name: 'big.bin' }),
    });
  });

  it('forwards a carrier message to a peer with carrier: 1 and keeps carrier in the body', async () => {
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async (chatId, req) =>
      makeNodeRow('srv_fwd_1', chatId, req.client_id, req.kind ?? 'file', req.body ?? null),
    );

    await useMessengerStore.getState().forwardMessage(carrierMsg, 'chat_a');

    expect(appendSpy).toHaveBeenCalledTimes(1);
    expect(appendSpy.mock.calls[0][0]).toBe('chat_a');
    const sentBody = JSON.parse(appendSpy.mock.calls[0][1].body!);
    expect(sentBody.carrier).toEqual(carrierMsg.media!.carrier);
  });

  it('refuses plain retrySend on a failed carrier message when peer caps were forgotten', async () => {
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage');
    const failedCarrier: Message = {
      ...carrierMsg,
      id: 'm_retry_carrier',
      status: 'failed',
    };
    useMessengerStore.setState((s) => ({
      chats: s.chats.map((c) => (c.id === 'chat_a' ? { ...c, messages: [failedCarrier] } : c)),
    }));
    forgetPeerCaps();

    await useMessengerStore.getState().retrySend('m_retry_carrier');

    expect(appendSpy).not.toHaveBeenCalled();
    const msgAfter = useMessengerStore.getState().chats.find((c) => c.id === 'chat_a')!.messages[0];
    expect(msgAfter.status).toBe('failed');
    expect(msgAfter.failReason).toBe(tNow('mediaC6.forwardNoCarrier'));
  });
});
