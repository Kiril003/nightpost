/**
 * Перегляд фото — галерея розмови: свайп гортає, свайп донизу закриває,
 * «Зберегти» на телефоні йде в міст (у WebView `<a download>` мертвий).
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { MediaLightboxModal } from '../components/messenger/MediaLightboxModal';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';

const photo = (i: number): Message =>
  ({ id: `p${i}`, senderId: 'peer', senderName: 'Оксана', senderAvatar: '', timestamp: '2026-09-25T10:00:00Z', type: 'image', mediaUrl: `blob:photo-${i}` }) as Message;

beforeEach(() => {
  const chat = { id: 'c1', title: 'Оксана', type: 'dm', avatar: '', unreadCount: 0, messages: [photo(1), photo(2), photo(3)] } as unknown as Chat;
  useMessengerStore.setState({ chats: [chat], activeChatId: 'c1' });
});
afterEach(() => {
  cleanup();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

const swipe = (el: Element, dx: number, dy: number) => {
  fireEvent.touchStart(el, { touches: [{ clientX: 200, clientY: 300 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: 200 + dx, clientY: 300 + dy }] });
  fireEvent.touchEnd(el);
};

describe('галерея фото', () => {
  it('відкривається на тому фото, яке торкнули, і гортається свайпом', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-2" />);
    expect(screen.getByText(/2 з 3/)).toBeTruthy();
    const stage = screen.getByAltText('phantom-p2.jpg').closest('div')!.parentElement!;
    swipe(stage, -120, 4);
    expect(screen.getByText(/3 з 3/)).toBeTruthy();
    swipe(screen.getByAltText('phantom-p3.jpg').closest('div')!.parentElement!, 120, 2);
    expect(screen.getByText(/2 з 3/)).toBeTruthy();
  });

  it('свайп донизу закриває', () => {
    const close = vi.fn();
    render(<MediaLightboxModal isOpen onClose={close} mediaUrl="blob:photo-1" />);
    swipe(screen.getByAltText('phantom-p1.jpg').closest('div')!.parentElement!, 5, 180);
    expect(close).toHaveBeenCalled();
  });

  it('фото поза розмовою — одне, без лічильника', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="data:image/png;base64,AA" mediaTitle="Аватар" />);
    expect(screen.queryByText(/ з /)).toBeNull();
  });

  it('на телефоні «Зберегти» йде в міст, а не в мертве <a download>', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer, headers: new Headers() }));
    const save = vi.spyOn(messengerApi, 'saveToPhone').mockResolvedValue({ saved: true, where: 'Pictures/Nightpost', name: 'a.jpg' });
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    fireEvent.click(screen.getByLabelText('Зберегти на пристрій'));
    await waitFor(() => expect(save).toHaveBeenCalledWith('phantom-p1.jpg', 'image/jpeg', 'AQID'));
    vi.unstubAllGlobals();
  });
});

describe('чому фото не поїхало — словами', () => {
  it('NotReadableError браузера — українською, з порадою', async () => {
    const { uploadFailureWord } = await import('../stores/messengerStore');
    const err = new DOMException('The requested file could not be read, typically due to permission problems', 'NotReadableError');
    expect(uploadFailureWord(err)).toBe('телефон не дав прочитати файл — оберіть його ще раз');
    expect(uploadFailureWord(new Error('Network down'))).toBe('вивантаження вкладення не вдалося');
    expect(uploadFailureWord(new Error('вузол відмовив'))).toBe('вузол відмовив');
  });
});
