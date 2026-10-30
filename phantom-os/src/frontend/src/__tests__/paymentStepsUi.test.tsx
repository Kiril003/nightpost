import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SolanaCard } from '../components/messenger/feed/SolanaCard';
import { PayMoneySheet } from '../components/messenger/solana/PayMoneySheet';
import { solanaApi } from '../services/solanaActions';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, MessagePayment } from '../types/messenger';

const transfer = (over: Partial<MessagePayment> = {}): Message => ({
  id: 'm-pay-1',
  senderId: 'me',
  senderName: 'Я',
  senderAvatar: 'Я',
  type: 'text',
  text: '',
  isSelf: true,
  status: 'sent',
  timestamp: '12:30',
  payment: {
    amount: '1.5',
    token: 'USDC',
    cluster: 'devnet',
    to: '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T',
    signature: '5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ',
    offlineCash: false,
    seekerVerified: false,
    seekerSkrTier: null,
    seekerLabel: null,
    status: 'pending',
    toMine: false,
    ...over,
  },
});

const fills = () =>
  Array.from(screen.getByTestId('pay-steps').querySelectorAll<HTMLElement>('[data-notch]'));

const chat: Chat = { id: 'p1', title: 'Оля', avatar: 'О', type: 'direct', circle: 'friends', unreadCount: 0, messages: [] };

describe('paymentSteps UI (motion-r2b-1)', () => {
  const origMatchMedia = window.matchMedia;

  beforeEach(() => {
    useMessengerStore.setState({ chats: [chat] });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    window.matchMedia = origMatchMedia;
  });

  it('card stays on sent with time and stamps the moment the status turns confirmed', () => {
    vi.useFakeTimers();
    const { rerender } = render(<SolanaCard msg={transfer({ status: 'pending' })} />);
    expect(screen.getByTestId('pay-steps').getAttribute('data-state')).toBe('sent');
    expect(screen.getByTestId('pay-steps').getAttribute('aria-valuenow')).toBe('2');
    expect(fills().map((f) => f.dataset.notch)).toEqual(['on', 'on', 'off']);
    expect(fills()[2].style.transform).toBe('scale(0.9)');

    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByTestId('pay-steps').getAttribute('data-state')).toBe('sent');

    rerender(<SolanaCard msg={transfer({ status: 'confirmed' })} />);
    const after = fills();
    expect(screen.getByTestId('pay-steps').getAttribute('data-state')).toBe('stamped');
    expect(after.map((f) => f.dataset.notch)).toEqual(['on', 'on', 'on']);
    expect(after[2].className).toContain('bg-success');
    expect(after[2].style.clipPath).toBe('inset(0 0% 0 0)');
    expect(after.map((f) => f.style.transform)).toEqual(['none', 'none', 'none']);
    expect(after[2].style.transition).toContain('clip-path');
    expect(after[2].style.transition).toContain('transform');
  });

  it('refused card marks the last reached notch and never stamps', () => {
    render(<SolanaCard msg={transfer({ status: 'failed' })} />);
    const f = fills();
    expect(screen.getByTestId('pay-steps').getAttribute('data-state')).toBe('refused');
    expect(f[1].className).toContain('bg-danger');
    expect(f.some((x) => x.className.includes('bg-success'))).toBe(false);
  });

  it('cash notes keep their own line, no steps', () => {
    render(<SolanaCard msg={transfer({ status: 'pending', offlineCash: true })} />);
    expect(screen.queryByTestId('pay-steps')).toBeNull();
  });

  it('sheet shows signing only while the bridge call is open and keeps the draft on rejection', async () => {
    let reject!: (e: { message: string }) => void;
    const pay = vi
      .spyOn(solanaApi, 'pay')
      .mockImplementationOnce(() => new Promise((_, r) => (reject = r)))
      .mockResolvedValueOnce({ opened: true });

    render(<PayMoneySheet peer={{ id: 'p1', name: 'Оля' }} cluster="devnet" onClose={vi.fn()} />);
    expect(screen.queryByTestId('pay-steps')).toBeNull();

    fireEvent.change(screen.getByTestId('solana-amount-input'), { target: { value: '1,5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Переказати 1,5 USDC' }));
    expect(screen.getByTestId('pay-steps').getAttribute('data-state')).toBe('signing');

    await act(async () => {
      reject({ message: 'no_chat' });
    });
    expect(screen.queryByTestId('pay-steps')).toBeNull();
    expect((screen.getByTestId('solana-amount-input') as HTMLInputElement).value).toBe('1,5');

    fireEvent.click(screen.getByRole('button', { name: 'Переказати 1,5 USDC' }));
    await waitFor(() => expect(pay).toHaveBeenCalledTimes(2));
    expect(pay).toHaveBeenLastCalledWith({ peer_id: 'p1', token: 'USDC', amount: '1.5' });
  });

  it('reduced motion swaps states with no transition and no scale', () => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    render(<SolanaCard msg={transfer({ status: 'pending' })} />);
    const f = fills();
    expect(f).toHaveLength(3);
    for (const n of f) {
      expect(n.style.transition).toBe('none');
      expect(n.style.transform).toBe('none');
    }
    expect(f[0].style.clipPath).toBe('inset(0 0% 0 0)');
    expect(f[2].style.clipPath).toBe('inset(0 100% 0 0)');
  });
});
