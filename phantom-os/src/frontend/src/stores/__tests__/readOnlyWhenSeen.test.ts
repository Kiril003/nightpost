/**
 * DL-2 (ніч 09.10): відправник бачив «прочитано», поки телефон адресата
 * лежав на робочому столі. Лист у відкриту розмову розчитувався одразу,
 * хоча застосунок був у фоні, а WebView казав `visible`.
 */
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { useMessengerStore } from '../messengerStore';
import { messengerApi } from '../../services/messengerApi';
import { FOREGROUND_SINK } from '../../services/appForeground';

const push = (seen: boolean) => (window as unknown as Record<string, (v: boolean) => void>)[FOREGROUND_SINK](seen);
const row = (id: string, seq: number) =>
  ({ id, client_id: id, seq, conversation_id: 'chat_a', kind: 'text', body: 'hi', author_id: 'nina', author_name: 'nina', sent_at: '2026-10-09T07:33:26Z', edited_at: null, deleted_at: null }) as never;

describe('DL-2: прочитано лише тоді, коли людина бачить месенджер', () => {
  let markRead: MockInstance<typeof messengerApi.markRead>;
  beforeEach(() => {
    push(true);
    markRead = vi.spyOn(messengerApi, 'markRead').mockResolvedValue({} as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'oleh', avatar: '' } as never,
      chats: [{ id: 'chat_a', title: 'nina', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_a',
    });
  });
  afterEach(() => {
    push(true);
    vi.restoreAllMocks();
  });

  it('застосунок спереду — лист у відкриту розмову розчитано одразу (контроль)', () => {
    useMessengerStore.getState().applyLiveRow(row('m1', 5));
    expect(markRead).toHaveBeenCalledWith('chat_a', 5);
  });

  it('застосунок у фоні — галочки «прочитано» немає, доки людина не повернулась', () => {
    push(false);
    useMessengerStore.getState().applyLiveRow(row('m2', 6));
    useMessengerStore.getState().applyLiveRow(row('m3', 7));
    expect(markRead).not.toHaveBeenCalled();

    push(true);
    expect(markRead).toHaveBeenCalledTimes(1);
    expect(markRead).toHaveBeenCalledWith('chat_a', 7);
  });

  it('повернулась, але розмову вже закрито — прочитаного немає', () => {
    push(false);
    useMessengerStore.getState().applyLiveRow(row('m4', 8));
    useMessengerStore.setState({ activeChatId: null as never });
    push(true);
    expect(markRead).not.toHaveBeenCalled();
  });
});
