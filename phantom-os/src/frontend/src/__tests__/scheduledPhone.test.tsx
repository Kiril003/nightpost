/**
 * Власник 20.10: «Запланувати» на телефоні не було. На телефоні відкладений
 * лист кладеться на апарат (`/messenger/scheduled`) і йде будильником, а
 * вкладка сама нічого не шле — інакше лист пішов би двічі.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useMessengerStore, scheduledFromDevice } from '../stores/messengerStore';
import { messengerApi, type NodeScheduledLetter } from '../services/messengerApi';
import { ScheduledMessagesDrawer } from '../components/messenger/ScheduledMessagesDrawer';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import type { Chat } from '../types/messenger';

const dm = { id: 'peer_ok', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'a1b2' } as unknown as Chat;
const due = new Date(2030, 0, 2, 9, 0).getTime();
const letter = (over: Partial<NodeScheduledLetter> = {}): NodeScheduledLetter => ({
  id: 'L1', conversation_id: 'peer_ok', text: 'завтра о 9', due_at_ms: due, created_at_ms: due - 3_600_000, quiet: false, failure: null, ...over,
});
const noop = () => {};

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
  useMessengerStore.setState({ chats: [dm] as never, scheduledMessages: [], activeChatId: 'peer_ok' });
});
afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  cleanup();
  vi.restoreAllMocks();
});

describe('відкладене на телефоні', () => {
  it('новий лист іде на апарат із миттю відправки, а список приходить звідти', async () => {
    const create = vi.spyOn(messengerApi, 'createScheduled').mockResolvedValue(letter());
    vi.spyOn(messengerApi, 'listScheduled').mockResolvedValue([letter()]);
    useMessengerStore.getState().addScheduledMessage({ chatId: 'peer_ok', text: ' завтра о 9 ', date: '2030-01-02', time: '09:00' });
    expect(create).toHaveBeenCalledWith({ conversation_id: 'peer_ok', text: 'завтра о 9', due_at_ms: due });
    await waitFor(() => expect(useMessengerStore.getState().scheduledMessages).toHaveLength(1));
    const s = useMessengerStore.getState().scheduledMessages[0];
    expect([s.chatTitle, s.scheduledDate, s.scheduledExactTime]).toEqual(['Оксана', '2030-01-02', '09:00']);
  });

  it('вкладка сама не шле — це робить будильник апарата', () => {
    const append = vi.spyOn(messengerApi, 'appendMessage');
    useMessengerStore.setState({ scheduledMessages: [scheduledFromDevice(letter({ due_at_ms: Date.now() - 1_000 }), [dm])] });
    useMessengerStore.getState().sendDueScheduled(Date.now());
    expect(append).not.toHaveBeenCalled();
  });

  it('«Надіслати зараз» і «Скасувати» просять апарат', async () => {
    const now = vi.spyOn(messengerApi, 'sendScheduledNow').mockResolvedValue(letter());
    const cancel = vi.spyOn(messengerApi, 'cancelScheduled').mockResolvedValue(letter({ id: 'L2' }));
    useMessengerStore.setState({ scheduledMessages: [scheduledFromDevice(letter(), [dm]), scheduledFromDevice(letter({ id: 'L2' }), [dm])] });
    useMessengerStore.getState().sendScheduledNow('L1');
    useMessengerStore.getState().cancelScheduledMessage('L2');
    expect(now).toHaveBeenCalledWith('L1');
    expect(cancel).toHaveBeenCalledWith('L2');
    await waitFor(() => expect(useMessengerStore.getState().scheduledMessages).toEqual([]));
  });

  it('лист, який апарат не зміг надіслати, каже це його ж словами', () => {
    const s = scheduledFromDevice(letter({ failure: 'контакт заблоковано' }), [dm]);
    render(<ScheduledMessagesDrawer isOpen onClose={noop} scheduledMessages={[s]} currentChatId="peer_ok" chats={[dm]} onSendNow={noop} onCancel={noop} />);
    expect(screen.getByText('Не надіслано')).toBeTruthy();
    expect(screen.getByTestId('scheduled-failure').textContent).toBe('контакт заблоковано');
  });

  it('у «Ще» розмови є «Заплановані» з лічильником, і вони відкривають список з апарата', async () => {
    const list = vi.spyOn(messengerApi, 'listScheduled').mockResolvedValue([letter()]);
    useMessengerStore.setState({ scheduledMessages: [scheduledFromDevice(letter(), [dm])], isScheduledDrawerOpen: false });
    const onClose = vi.fn();
    render(<PhoneChatMenu chat={dm} onClose={onClose} onOpenCard={noop} onOpenGroupDetails={noop} />);
    fireEvent.click(screen.getByText('Заплановані'));
    expect(onClose).toHaveBeenCalled();
    expect(useMessengerStore.getState().isScheduledDrawerOpen).toBe(true);
    await waitFor(() => expect(list).toHaveBeenCalled());
  });
});
