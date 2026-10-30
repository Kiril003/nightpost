/**
 * Переслати й Подробиці (27.09): аркуші форми без механіки.
 *
 * До цього обидва були старими зеленими модалками з сирими hex: у «Подробицях»
 * стояли «ID: <ключ вузла>», «Тип вмісту: text», лічильник символів і дорога
 * доставки; у «Переслати» під кожною розмовою — службове коло «friends» і
 * «Надіслати →» на кожному рядку.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { ForwardMessageModal } from '../components/messenger/ForwardMessageModal';
import { MessageDetailsModal } from '../components/messenger/MessageDetailsModal';
import type { Chat, Message } from '../types/messenger';

const msg = (extra: Partial<Message> = {}): Message =>
  ({
    id: 'm1',
    chatId: 'd1',
    senderId: 'node_8f3ac1d0e4b7',
    senderName: 'Оксана',
    text: 'Заберу, скинь номер',
    type: 'text',
    timestamp: '19:40',
    sentAt: '2026-09-27T16:40:00Z',
    isSelf: true,
    status: 'sent',
    transport: 'direct',
    ...extra,
  }) as Message;

const chat = (id: string, title: string, type: 'direct' | 'group' = 'direct'): Chat =>
  ({ id, title, type, circle: 'friends', messages: [], unreadCount: 0, pinned: false }) as unknown as Chat;

afterEach(() => cleanup());

describe('Переслати', () => {
  it('розмови кулями, без службового кола; вибір кількох — і переслано кожному', () => {
    const go = vi.fn();
    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[chat('d1', 'Оксана'), chat('d2', 'Тарас'), chat('g1', 'Двір', 'group')]}
        currentChatId="d1"
        messagesToForward={[msg()]}
        onConfirmForward={go}
      />,
    );
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/friends|Надіслати →/);
    expect(screen.queryByText('Оксана')).toBeNull();
    fireEvent.click(screen.getByText('Тарас'));
    expect(go).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Двір'));
    fireEvent.click(screen.getByText(/^(Надіслати|Send) · 2$/));
    expect(go).toHaveBeenCalledWith('d2');
    expect(go).toHaveBeenCalledWith('g1');
  });
});

describe('Подробиці', () => {
  it('коли й чи дійшло — без ID, типу вмісту, лічильників і дороги', () => {
    render(<MessageDetailsModal isOpen message={msg()} onClose={() => {}} />);
    const body = document.body.textContent ?? '';
    expect(body).toContain('Доставлено');
    expect(body).toContain('Надіслано');
    expect(body).not.toMatch(/ID:|node_8f3|Тип вмісту|Символів|Слів|Транспорт|Напряму/);
  });
});

describe('Перегляд фото', () => {
  it('фото на весь екран: шапка лежить на ньому, а не смугою над ним', async () => {
    const { MediaLightboxModal } = await import('../components/messenger/MediaLightboxModal');
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="data:image/png;base64,AA==" mediaTitle="Оксана" />);
    const close = screen.getByLabelText('Закрити');
    expect(close.parentElement!.className).toMatch(/absolute/);
    expect(screen.getByTestId('media-lightbox').className).not.toMatch(/flex-col/);
  });
});

describe('Спільне', () => {
  it('вкладки — пігулки як у списку, без значка на кожній', async () => {
    const { SharedSection } = await import('../components/messenger/SharedSection');
    const empty = { media: [], files: [], voice: [], links: [{ url: 'https://ex.ua/a', host: 'ex.ua', msgId: 'm1', at: '' }], places: [], payments: [], meetups: [] };
    render(<SharedSection shared={empty as never} whoSent={() => 'ви'} onJump={() => {}} onOpenImage={() => {}} />);
    const tab = screen.getByTestId('shared-tab-links');
    expect(tab.querySelector('svg')).toBeNull();
  });
});
