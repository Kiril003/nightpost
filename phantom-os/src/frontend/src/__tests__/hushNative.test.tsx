import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { chatHush, hushPort } from '../services/chatHush';
import { useMessengerStore } from '../stores/messengerStore';
import { HushExceptions } from '../components/messenger/SettingsRoot';
import type { Chat } from '../types/messenger';

const chat = (id: string, title: string): Chat =>
  ({ id, title, type: 'dm', avatar: '', unreadCount: 0, messages: [] }) as unknown as Chat;

describe('P01: на телефоні тиша розмови живе в застосунку, не у вебі', () => {
  beforeEach(() => {
    chatHush.reset();
    useMessengerStore.setState({ chats: [chat('marta', 'Марта'), chat('dim', 'Дім')] });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    chatHush.reset();
  });

  it('вимкнений звук іде мостом у застосунок, увімкнений — знімається там же', async () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(true);
    const put = vi.spyOn(hushPort, 'put').mockResolvedValue({});
    const drop = vi.spyOn(hushPort, 'drop').mockResolvedValue({});
    chatHush.setFor('marta', 5_000_000_000_000);
    chatHush.set('dim', true);
    chatHush.set('marta', false);
    expect(put.mock.calls).toEqual([
      ['marta', 5_000_000_000_000, 'Марта'],
      ['dim', null, 'Дім'],
    ]);
    expect(drop.mock.calls).toEqual([['marta']]);
  });

  it('застосунок — джерело правди: веб бере його перелік і забуває свій', async () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(true);
    vi.spyOn(hushPort, 'put').mockResolvedValue({});
    chatHush.set('stale', true);
    vi.spyOn(hushPort, 'load').mockResolvedValue({ chats: { dim: null, marta: 5_000_000_000_000 } });
    await chatHush.pull();
    expect(chatHush.has('stale')).toBe(false);
    expect(chatHush.has('dim')).toBe(true);
    expect(chatHush.until('marta')).toBe(5_000_000_000_000);
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'dim')?.muted).toBe(true);
  });

  it('звук зняли в застосунку (Налаштування › Сповіщення) — розмова тут же не «без звуку»', async () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(true);
    vi.spyOn(hushPort, 'put').mockResolvedValue({});
    chatHush.set('dim', true);
    useMessengerStore.setState({ chats: [chat('marta', 'Марта'), { ...chat('dim', 'Дім'), muted: true }] });
    vi.spyOn(hushPort, 'load').mockResolvedValue({ chats: {} });
    (window as unknown as { __phantomHushChanged?: () => void }).__phantomHushChanged?.();
    await vi.waitFor(() => expect(chatHush.has('dim')).toBe(false));
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'dim')?.muted).toBe(false);
  });

  it('застосунок відмовив — екран не бреше «без звуку»', async () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(true);
    vi.spyOn(hushPort, 'put').mockRejectedValue(new Error('no'));
    vi.spyOn(hushPort, 'load').mockResolvedValue({ chats: {} });
    chatHush.set('marta', true);
    await vi.waitFor(() => expect(chatHush.has('marta')).toBe(false));
  });

  it('Налаштування › Сповіщення › Винятки: перелік, «до коли» і зняття одним дотиком', async () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(false);
    render(<HushExceptions />);
    expect(screen.getByTestId('settings-hush-none').textContent).toContain('Розмов без звуку немає');
    act(() => {
      chatHush.set('dim', true);
      chatHush.setFor('marta', Date.now() + 3_600_000);
    });
    const rows = screen.getAllByTestId('settings-hush-row');
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringMatching(/Марта.*Без звуку до/),
      expect.stringMatching(/Дім.*Без звуку/),
    ]);
    fireEvent.click(screen.getAllByTestId('settings-hush-unmute')[0]);
    expect(chatHush.has('marta')).toBe(false);
    expect(screen.getAllByTestId('settings-hush-row')).toHaveLength(1);
  });
});
