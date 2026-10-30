import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../components/messenger/phoneShell', () => ({ isPhoneShell: () => false }));

import { CAPS_QUIET_MS, CAPS_WAIT_MS, chatCapsPending, forgetPeerCaps, routeForChat, routeForChatEnsured } from '../services/carrier/route';
import { messengerApi, type NodePeerProfile } from '../services/messengerApi';
import type { Chat } from '../types/messenger';
import { useMessengerStore } from '../stores/messengerStore';
import { CARRIER_CHUNK } from '../services/carrier/seal';
import { MEDIA_PLAIN_LIMIT_BYTES } from '../services/messengerMedia';

describe('Carrier peer caps on demand (fix 5)', () => {
  const MiB = 1024 * 1024;
  const chat: Pick<Chat, 'type' | 'peerNodeId'> = { type: 'dm', peerNodeId: 'p1' };

  const peerRow: NodePeerProfile = {
    peer_node_id: 'p1',
    display_name: 'A',
    carrier: 1,
    max_attachment_bytes: 200 * MiB,
  };

  beforeEach(() => {
    forgetPeerCaps();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches peerProfiles on demand so a capable peer routes to v2 instead of legacy too_big', async () => {
    const spy = vi.spyOn(messengerApi, 'peerProfiles').mockResolvedValue([peerRow]);

    expect(routeForChat(chat, 40 * MiB, 25 * MiB)).toEqual({ kind: 'too_big', who: 'peer' });

    const route = await routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    expect(route).toEqual({ kind: 'v2' });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('shares one in-flight peerProfiles request across concurrent callers and caches afterwards', async () => {
    let resolveProfiles!: (rows: NodePeerProfile[]) => void;
    const spy = vi.spyOn(messengerApi, 'peerProfiles').mockImplementation(
      () =>
        new Promise<NodePeerProfile[]>((resolve) => {
          resolveProfiles = resolve;
        }),
    );

    const p1 = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    const p2 = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    resolveProfiles([peerRow]);

    const [r1, r2] = await Promise.all([p1, p2]);
    const r3 = await routeForChatEnsured(chat, 40 * MiB, 25 * MiB);

    expect(r1).toEqual({ kind: 'v2' });
    expect(r2).toEqual({ kind: 'v2' });
    expect(r3).toEqual({ kind: 'v2' });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('does not refetch when peer is absent from returned profiles until forgetPeerCaps is called', async () => {
    const spy = vi.spyOn(messengerApi, 'peerProfiles').mockResolvedValue([]);

    await routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    await routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    expect(spy).toHaveBeenCalledTimes(1);

    forgetPeerCaps();
    await routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('ignores in-flight response after forgetPeerCaps and preserves a newer in-flight handle', async () => {
    let resolveFirst!: (rows: NodePeerProfile[]) => void;
    let resolveSecond!: (rows: NodePeerProfile[]) => void;
    const spy = vi
      .spyOn(messengerApi, 'peerProfiles')
      .mockImplementationOnce(
        () =>
          new Promise<NodePeerProfile[]>((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<NodePeerProfile[]>((resolve) => {
            resolveSecond = resolve;
          }),
      );

    const staleCall = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    forgetPeerCaps();

    const freshCall1 = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    resolveFirst([peerRow]);
    await expect(staleCall).resolves.toEqual({ kind: 'too_big', who: 'peer' });
    expect(routeForChat(chat, 40 * MiB, 25 * MiB)).toEqual({ kind: 'too_big', who: 'peer' });

    const freshCall2 = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    expect(spy).toHaveBeenCalledTimes(2);

    resolveSecond([peerRow]);
    await expect(Promise.all([freshCall1, freshCall2])).resolves.toEqual([
      { kind: 'v2' },
      { kind: 'v2' },
    ]);
  });

  it('falls back to legacy route without throwing when peerProfiles rejects', async () => {
    const spy = vi.spyOn(messengerApi, 'peerProfiles').mockRejectedValue(new Error('network error'));

    const route = await routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
    expect(route).toEqual({ kind: 'too_big', who: 'peer' });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('does not fetch profiles for non-1:1 chats', async () => {
    const spy = vi.spyOn(messengerApi, 'peerProfiles').mockResolvedValue([peerRow]);
    const groupChat: Pick<Chat, 'type' | 'peerNodeId'> = { type: 'group', peerNodeId: 'p1' };

    const route = await routeForChatEnsured(groupChat, 10 * MiB, 25 * MiB);
    expect(route).toEqual({ kind: 'v1' });
    expect(spy).not.toHaveBeenCalled();
  });

  it('the real send path reads the peer caps first, so a big file to a capable peer goes by chunks', async () => {
    vi.spyOn(messengerApi, 'peerProfiles').mockResolvedValue([{ ...peerRow, peer_node_id: 'node_olena' }]);
    const got = new Set<number>();
    const create = vi
      .spyOn(messengerApi, 'carrierCreate')
      .mockImplementation((size, n) => Promise.resolve({ transfer_id: 'tr_1', size, n, received: [], count: 0 }));
    vi.spyOn(messengerApi, 'carrierPutChunk').mockImplementation((_t, i) => {
      got.add(i);
      return Promise.resolve({ transfer_id: 'tr_1', size: 0, n: 0, received: [...got], count: got.size });
    });
    const upload = vi.spyOn(messengerApi, 'uploadFile');
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'row_1', delivery: 'sent' } as never);
    useMessengerStore.setState({
      chats: [{ id: 'chat_o', title: 'Олена', avatar: '', type: 'dm', circle: 'work', unreadCount: 0, peerNodeId: 'node_olena', messages: [] }],
      activeChatId: 'chat_o',
    } as never);

    const size = MEDIA_PLAIN_LIMIT_BYTES + CARRIER_CHUNK + 5;
    await useMessengerStore
      .getState()
      .sendAttachment(new File([new Uint8Array(size) as BlobPart], 'big.bin', { type: 'application/octet-stream' }));

    expect(create).toHaveBeenCalledTimes(1);
    expect(upload).not.toHaveBeenCalled();
  });

  it('asks once even for a small file, so a peer with a lower ceiling refuses before upload', async () => {
    const spy = vi.spyOn(messengerApi, 'peerProfiles').mockResolvedValue([{ ...peerRow, max_attachment_bytes: 4 * MiB }]);
    expect(await routeForChatEnsured(chat, 10 * MiB, 25 * MiB)).toEqual({ kind: 'too_big', who: 'peer' });
    expect(await routeForChatEnsured(chat, 2 * MiB, 25 * MiB)).toEqual({ kind: 'v1' });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('stops waiting for a hung profiles request and routes with what it knows', async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(messengerApi, 'peerProfiles').mockImplementation(() => new Promise(() => {}));
      const pending = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
      await vi.advanceTimersByTimeAsync(CAPS_WAIT_MS + 1);
      expect(await pending).toEqual({ kind: 'too_big', who: 'peer' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('after a hung request the next sends do not wait again, and it asks afresh once the pause is over', async () => {
    vi.useFakeTimers();
    try {
      const spy = vi.spyOn(messengerApi, 'peerProfiles').mockImplementation(() => new Promise(() => {}));
      const first = routeForChatEnsured(chat, 40 * MiB, 25 * MiB);
      await vi.advanceTimersByTimeAsync(CAPS_WAIT_MS + 1);
      await first;
      expect(chatCapsPending(chat)).toBe(false);

      let settled = false;
      const second = routeForChatEnsured(chat, 40 * MiB, 25 * MiB).then((r) => {
        settled = true;
        return r;
      });
      await vi.advanceTimersByTimeAsync(10);
      expect(settled).toBe(true);
      expect(await second).toEqual({ kind: 'too_big', who: 'peer' });
      expect(spy).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(CAPS_QUIET_MS);
      spy.mockResolvedValue([peerRow]);
      expect(chatCapsPending(chat)).toBe(true);
      expect(await routeForChatEnsured(chat, 40 * MiB, 25 * MiB)).toEqual({ kind: 'v2' });
      expect(spy).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
