/**
 * Правка й стирання «для всіх» на телефоні — з 25.09 вони їдуть дротом
 * (PH-EDT / PH-UNS). До цього вікно видалення на телефоні вибору не давало,
 * а правка мовчки лишалась лише у своїй копії.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { DeleteMessageModal } from '../components/messenger/DeleteMessageModal';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { messengerApi, SAVED_NOTES_ID } from '../services/messengerApi';
import type { Chat } from '../types/messenger';

const asPhone = (on: boolean) => {
  const w = window as unknown as { PhantomBridge?: unknown };
  if (on) w.PhantomBridge = {};
  else delete w.PhantomBridge;
};

afterEach(() => {
  cleanup();
  asPhone(false);
  vi.restoreAllMocks();
});

describe('стирання на телефоні', () => {
  it('свій лист — є вибір «В усіх», і він доходить до виклику', () => {
    asPhone(true);
    const confirm = vi.fn();
    render(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={confirm} isSelfMessage messageTextPreview="привіт" />);
    fireEvent.click(screen.getByText('В усіх'));
    fireEvent.click(screen.getByText('Видалити в усіх'));
    expect(confirm).toHaveBeenCalledWith(true);
  });

  it('свій лист за замовчуванням — в усіх', () => {
    asPhone(true);
    const confirm = vi.fn();
    render(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={confirm} isSelfMessage />);
    fireEvent.click(screen.getByText('Видалити в усіх'));
    expect(confirm).toHaveBeenCalledWith(true);
  });

  it('«в себе» лишається вибором і скидається на «в усіх» при новому відкритті', () => {
    asPhone(true);
    const confirm = vi.fn();
    const view = render(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={confirm} isSelfMessage />);
    fireEvent.click(screen.getByText('Тільки в себе'));
    expect(screen.getByText('Видалити в себе')).toBeTruthy();
    view.rerender(<DeleteMessageModal isOpen={false} onClose={() => {}} onConfirmDelete={confirm} isSelfMessage />);
    view.rerender(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={confirm} isSelfMessage />);
    fireEvent.click(screen.getByText('Видалити в усіх'));
    expect(confirm).toHaveBeenLastCalledWith(true);
    expect(confirm).toHaveBeenCalledTimes(1);
  });

  it('група — підписи про групу, не про співрозмовника', () => {
    asPhone(true);
    render(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={vi.fn()} isSelfMessage group />);
    expect(screen.getByText('У групі зникне')).toBeTruthy();
    expect(screen.queryByText('У співрозмовника зникне')).toBeNull();
  });

  it('«Збережене» — без співрозмовника: ні вибору, ні «в усіх»', () => {
    asPhone(true);
    const confirm = vi.fn();
    render(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={confirm} isSelfMessage noOtherSide />);
    expect(screen.queryByText('В усіх')).toBeNull();
    expect(screen.queryByText('Тільки в себе')).toBeNull();
    fireEvent.click(screen.getByText('Видалити'));
    expect(confirm).toHaveBeenCalledWith(false);
  });

  it('чужий лист — вибору немає', () => {
    render(<DeleteMessageModal isOpen onClose={() => {}} onConfirmDelete={vi.fn()} isSelfMessage={false} />);
    expect(screen.queryByText('В усіх')).toBeNull();
  });
});

describe('правка', () => {
  it('вузол відповів frame:local — людині сказано, що в співрозмовника старий текст', async () => {
    const chat = {
      id: 'c1', title: 'Оксана', type: 'dm', avatar: '', unreadCount: 0,
      messages: [{ id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'text', text: 'старе', isSelf: true }],
    } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat] });
    useUIStore.setState({ toasts: [] });
    vi.spyOn(messengerApi, 'editMessage').mockResolvedValue({ body: 'нове', frame: 'local' } as never);
    await useMessengerStore.getState().editMessage('m1', 'нове');
    const toasts = useUIStore.getState().toasts as Array<{ message: string }>;
    expect(toasts.some((t) => t.message.includes('у нього старий текст'))).toBe(true);
  });

  it('frame:sent — без попередження', async () => {
    const chat = {
      id: 'c1', title: 'Оксана', type: 'dm', avatar: '', unreadCount: 0,
      messages: [{ id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'text', text: 'старе', isSelf: true }],
    } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat] });
    useUIStore.setState({ toasts: [] });
    vi.spyOn(messengerApi, 'editMessage').mockResolvedValue({ body: 'нове', frame: 'sent' } as never);
    await useMessengerStore.getState().editMessage('m1', 'нове');
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });

  it('«Збережене»: правка без тосту про співрозмовника', async () => {
    const chat = {
      id: SAVED_NOTES_ID, title: 'Збережене', type: 'saved', avatar: '', unreadCount: 0,
      messages: [{ id: 'n1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'text', text: 'старе', isSelf: true }],
    } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat] });
    useUIStore.setState({ toasts: [] });
    vi.spyOn(messengerApi, 'editMessage').mockResolvedValue({ body: 'нове', frame: 'local' } as never);
    await useMessengerStore.getState().editMessage('n1', 'нове');
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });
});

