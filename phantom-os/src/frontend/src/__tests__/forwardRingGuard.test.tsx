import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { ForwardMessageModal } from '../components/messenger/ForwardMessageModal';
import { skipKey } from '../services/ringGuard';
import type { Chat, ChatCircle, Message } from '../types/messenger';

const msg = (extra: Partial<Message> = {}): Message =>
  ({
    id: 'm1',
    chatId: 'c-close',
    senderId: 'node_8f3ac1d0e4b7',
    senderName: 'Оксана',
    text: 'Дзвони 0991234567 о 19:00',
    type: 'text',
    timestamp: '19:40',
    sentAt: '2026-09-27T16:40:00Z',
    isSelf: true,
    status: 'sent',
    transport: 'direct',
    ...extra,
  }) as Message;

const makeChat = (id: string, title: string, circle: ChatCircle): Chat =>
  ({
    id,
    title,
    type: 'direct',
    circle,
    circles: [circle],
    messages: [],
    unreadCount: 0,
    pinned: false,
  }) as unknown as Chat;

beforeEach(() => {
  try {
    window.localStorage?.removeItem(skipKey);
  } catch {
    /* ignore */
  }
});

afterEach(() => cleanup());

/** Вибрати адресата й натиснути «Надіслати · 1». */
const pick = (name: string) => {
  fireEvent.click(screen.getByText(name));
  fireEvent.click(screen.getByText(/^(Надіслати|Send) · 1$/));
};

describe('ForwardMessageModal cross-ring guard', () => {
  it('shows forward-ring-guard when forwarding sensitive text from close to work', () => {
    const onConfirm = vi.fn();
    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByTestId('forward-ring-guard')).toBeTruthy();
    expect(screen.getByTestId('forward-ring-guard').textContent).toContain('▢▢▢');
  });

  it('primary hides addresses and numbers; "Send as is" forwards the original', () => {
    const onConfirm = vi.fn();
    const { unmount } = render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    fireEvent.click(screen.getByText(/Сховати адреси й номери|Hide addresses and numbers/));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('target', { redact: true });

    unmount();
    onConfirm.mockClear();

    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    fireEvent.click(screen.getByText(/^(Надіслати як є|Send as is)$/));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('target');
  });

  it('returns to the chat list and calls nothing on Cancel', () => {
    const onConfirm = vi.fn();
    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    expect(screen.getByTestId('forward-ring-guard')).toBeTruthy();

    fireEvent.click(screen.getByText(/Скасувати|Cancel/));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.queryByTestId('forward-ring-guard')).toBeNull();
    expect(screen.getByTestId('forward-message')).toBeTruthy();
    expect(document.querySelector('[data-pick-row="target"]')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('forwards at once from work to close without showing guard', () => {
    const onConfirm = vi.fn();
    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-work', 'Команда', 'work'), makeChat('target', 'Оксана', 'close')]}
        currentChatId="c-work"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Оксана');
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('target');
    expect(screen.queryByTestId('forward-ring-guard')).toBeNull();
  });

  it('forwards at once from close to work when text is plain "ok"', () => {
    const onConfirm = vi.fn();
    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg({ text: 'ok' })]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('target');
    expect(screen.queryByTestId('forward-ring-guard')).toBeNull();
  });

  it('remembers skip for the pair and forwards at once the next time', () => {
    const onConfirm = vi.fn();
    const { unmount } = render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    fireEvent.click(screen.getByText(/Більше не питати для цієї пари|Don't ask for this pair/));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('target');

    unmount();
    onConfirm.mockClear();

    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={[makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')]}
        currentChatId="c-close"
        messagesToForward={[msg()]}
        onConfirmForward={onConfirm}
      />,
    );

    pick('Команда');
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('target');
    expect(screen.queryByTestId('forward-ring-guard')).toBeNull();
  });

  it('reopening after closing on the guard step shows the chat list again', () => {
    const props = {
      onClose: () => {},
      chats: [makeChat('c-close', 'Оксана', 'close'), makeChat('target', 'Команда', 'work')],
      currentChatId: 'c-close',
      messagesToForward: [msg()],
      onConfirmForward: vi.fn(),
    };
    const { rerender } = render(<ForwardMessageModal isOpen {...props} />);
    pick('Команда');
    expect(screen.getByTestId('forward-ring-guard')).toBeTruthy();
    rerender(<ForwardMessageModal isOpen={false} {...props} />);
    rerender(<ForwardMessageModal isOpen {...props} />);
    expect(screen.queryByTestId('forward-ring-guard')).toBeNull();
    expect(screen.getByText('Команда')).toBeTruthy();
  });
});
