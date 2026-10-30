/**
 * Відкладений лист — справжній від початку до кінця (03.10).
 *
 * Що було (перевірено на b629fd08):
 *   - диспетчер кореня надсилав, лише коли `scheduledTime === "ГГ:ХХ"`, а
 *     шухляда писала туди «Сьогодні о 18:00» чи «2026-10-03 о 18:00» — тож
 *     НІЧОГО створеного нею не надсилалось ніколи;
 *   - `addScheduledMessage(timeStr, text)` губив вибрану розмову й писав
 *     активну;
 *   - «Надіслати зараз» ішло `sendMessage(text)`, тобто в ВІДКРИТУ розмову:
 *     лист для Оксани, поки відкритий Тарас, діставався Тарасові;
 *   - правка була `() => {}`.
 *
 * Тепер час — це дата й година (`scheduledDate` + `scheduledExactTime`),
 * диспетчер шле, щойно час настав, і завжди в розмову самого листа.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { ScheduledMessagesDrawer } from '../components/messenger/ScheduledMessagesDrawer';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi, type NodeConversation, type NodeMessage } from '../services/messengerApi';
import type { Chat, ScheduledMessage } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const conv = (id: string, title: string, peer: string): NodeConversation => ({
  id,
  title,
  kind: 'dm',
  circle: 'friends',
  handle: null,
  avatar: null,
  pinned: false,
  archived: false,
  created_at: '2026-10-01T10:00:00',
  updated_at: '2026-10-01T10:00:00',
  peer_node_id: peer,
});

const OKSANA = conv('conv_oksana', 'Оксана', 'a1b2c3d4e5f60718');
const TARAS = conv('conv_taras', 'Тарас', 'b1b2c3d4e5f60718');

const chat = (c: NodeConversation): Chat =>
  ({ id: c.id, title: c.title, type: 'dm', circle: 'friends', peerNodeId: c.peer_node_id, messages: [], unreadCount: 0 }) as unknown as Chat;

const sched = (over: Partial<ScheduledMessage> = {}): ScheduledMessage => ({
  id: 's1',
  chatId: OKSANA.id,
  chatTitle: 'Оксана',
  text: 'Не забудь посилку до 20:00',
  type: 'text',
  scheduledTime: '2026-10-03 18:00',
  scheduledDate: '2026-10-03',
  scheduledExactTime: '18:00',
  createdAt: '2026-10-03T12:00:00.000Z',
  ...over,
});

const appended = (): Array<[string, Partial<NodeMessage>]> =>
  (messengerApi.appendMessage as unknown as { mock: { calls: Array<[string, Partial<NodeMessage>]> } }).mock.calls;

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async (cid, body) =>
    ({ id: `row_${cid}`, conversation_id: cid, ...body, delivery: 'sent' }) as unknown as NodeMessage,
  );
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('диспетчер: лист іде, щойно настав його час', () => {
  it('лист, чий час настав, надсилається з кореня месенджера — у свою розмову, хоч відкрита інша', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date(2026, 9, 3, 18, 0, 20));
    // Відкрита розмова — Тарас (перша в ростері), лист — Оксані.
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([TARAS, OKSANA]);
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false, scheduledMessages: [sched()] });

    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await screen.findByTestId('talk-root');
    expect(useMessengerStore.getState().activeChatId).toBe(TARAS.id);

    await act(async () => {
      vi.advanceTimersByTime(10_000);
    });

    const sent = appended().filter(([, b]) => b.body === 'Не забудь посилку до 20:00');
    expect(sent.map(([cid]) => cid)).toEqual([OKSANA.id]);
    expect(useMessengerStore.getState().scheduledMessages).toEqual([]);
  });

  it('пропущений, поки Nightpost був закритий, сам не їде — людина вирішує', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 3, 23, 0, 0));
    useMessengerStore.setState({ chats: [chat(OKSANA)], activeChatId: OKSANA.id, scheduledMessages: [sched()] });

    useMessengerStore.getState().sendDueScheduled(Date.now());

    expect(appended()).toEqual([]);
    expect(useMessengerStore.getState().scheduledMessages).toHaveLength(1);
  });

  it('«Надіслати зараз» іде в розмову листа, а не в відкриту — і не забирає чужу відповідь', () => {
    const reply = { id: 'm9', senderName: 'Тарас', text: 'о 20:30?', type: 'text' as const };
    useMessengerStore.setState({
      chats: [chat(TARAS), chat(OKSANA)],
      activeChatId: TARAS.id,
      replyingTo: reply,
      scheduledMessages: [sched()],
    });

    useMessengerStore.getState().sendScheduledNow('s1');

    expect(appended().map(([cid]) => cid)).toEqual([OKSANA.id]);
    expect(appended()[0][1].reply_to_id ?? null).toBeNull();
    const s = useMessengerStore.getState();
    expect(s.replyingTo).toEqual(reply);
    expect(s.chats.find((c) => c.id === OKSANA.id)!.messages.map((m) => m.text)).toEqual(['Не забудь посилку до 20:00']);
    expect(s.chats.find((c) => c.id === TARAS.id)!.messages).toEqual([]);
    expect(s.scheduledMessages).toEqual([]);
  });

  it('новий відкладений пам’ятає вибрану розмову, день і годину', () => {
    useMessengerStore.setState({ chats: [chat(TARAS), chat(OKSANA)], activeChatId: TARAS.id, scheduledMessages: [] });

    useMessengerStore.getState().addScheduledMessage({ chatId: OKSANA.id, text: 'Привіт', date: '2026-10-04', time: '09:00' });

    const [s] = useMessengerStore.getState().scheduledMessages;
    expect(s.chatId).toBe(OKSANA.id);
    expect(s.chatTitle).toBe('Оксана');
    expect(s.scheduledDate).toBe('2026-10-04');
    expect(s.scheduledExactTime).toBe('09:00');
  });
});

describe('шухляда відкладених — мовою людей', () => {
  const base = {
    isOpen: true,
    onClose: () => {},
    onSendNow: () => {},
    onCancel: () => {},
  };

  it('порожня каже «Нічого не заплановано», без лічильників і «черги»', () => {
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId="" chats={[]} />);
    expect(screen.getByText('Нічого не заплановано')).toBeTruthy();
    const text = document.body.textContent ?? '';
    for (const gone of ['Керування чергою', 'Власний календар/час', 'Автоматичне фонове надсилання', ' з 0']) {
      expect(text).not.toContain(gone);
    }
  });

  it('на компʼютері — швидкий вибір дає справжні день і годину, і чесно сказано, коли лист іде', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 3, 12, 0, 0));
    const onCreate = vi.fn();
    render(
      <ScheduledMessagesDrawer
        {...base}
        scheduledMessages={[]}
        currentChatId={OKSANA.id}
        chats={[chat(TARAS), chat(OKSANA)]}
        onCreate={onCreate}
      />,
    );
    expect(document.body.textContent).toContain('поки Nightpost відкритий на цьому компʼютері');
    fireEvent.change(screen.getByLabelText('Текст листа'), { target: { value: 'Добре, буду о 19:40' } });
    fireEvent.click(screen.getByRole('button', { name: /Завтра зранку/ }));
    expect(onCreate).toHaveBeenCalledWith({ chatId: OKSANA.id, text: 'Добре, буду о 19:40', date: '2026-10-04', time: '09:00' });
  });

  it('рядок листа відкриває меню: «Надіслати зараз» і «Видалити», без правки, що нічого не робила', () => {
    const onSendNow = vi.fn();
    const onCancel = vi.fn();
    render(
      <ScheduledMessagesDrawer
        {...base}
        onSendNow={onSendNow}
        onCancel={onCancel}
        scheduledMessages={[sched()]}
        currentChatId={TARAS.id}
        chats={[chat(TARAS), chat(OKSANA)]}
      />,
    );
    expect(screen.getByText('Не забудь посилку до 20:00')).toBeTruthy();
    expect(screen.queryByTitle('Редагувати вміст або час')).toBeNull();
    fireEvent.click(screen.getByTestId('scheduled-row'));
    fireEvent.click(screen.getByRole('button', { name: 'Надіслати зараз' }));
    expect(onSendNow).toHaveBeenCalledWith('s1');
    fireEvent.click(screen.getByTestId('scheduled-row'));
    fireEvent.click(screen.getByRole('button', { name: 'Видалити' }));
    expect(onCancel).toHaveBeenCalledWith('s1');
  });
});
