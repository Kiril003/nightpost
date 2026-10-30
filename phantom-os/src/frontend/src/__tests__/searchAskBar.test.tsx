/**
 * Одна капсула над списком розмов (збірка 18, власник 09.10: «подвійна лінія
 * вводу ріже око»).
 *
 * Було: поле «Спитати Nightpost» і під ним капсула пошуку — два поля вводу
 * поспіль. Стало: одна капсула «Пошук або спитати Nightpost» з мікрофоном
 * усередині; у пошуку з непорожнім запитом першим рядком стоїть «Спитати
 * Nightpost: «…»», і дотик по ньому шле запит у закріплену розмову з Nightpost
 * тією ж дорогою, що й композер (`setActiveChat` + `sendMessage`).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('chat_phantom', 'Nightpost', { type: 'phantom', pinned: true }),
  chat('c1', 'Оксана', { unreadCount: 1 }),
];

const navigate = vi.fn((_to: string, _args?: string) => true);
const send = vi.fn();

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
    call: () => JSON.stringify({ ok: true, status: 200, data: {} }),
    navigate,
  };
  navigate.mockClear();
  send.mockClear();
  useMessengerStore.setState({ chats: CHATS, activeChatId: '', sendMessage: send } as never);
});

afterEach(() => {
  cleanup();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

const mount = () =>
  render(
    <PhoneChatList
      chats={CHATS}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      ask
    />,
  );

const header = (root: HTMLElement) => root.querySelector('[data-testid="chat-list"] > header') as HTMLElement;

describe('одна капсула пошуку й питання над списком', () => {
  it('у шапці одне поле D «Пошук або спитати Nightpost» з мікрофоном; дотик відкриває пошук', () => {
    const { container } = mount();
    const head = header(container);
    expect(screen.queryByTestId('ask-nightpost')).toBeNull();
    const pills = head.querySelectorAll('[data-search-pill]');
    expect(pills.length).toBe(1);
    expect(within(pills[0] as HTMLElement).getByRole('searchbox').getAttribute('aria-label')).toBe('Пошук або спитати Nightpost');
    expect(pills[0].querySelector('[data-testid="search-ask-voice"]')).toBeTruthy();
    fireEvent.click(pills[0]);
    const box = screen.getByRole('searchbox').closest('[data-search-box]') as HTMLElement;
    expect(box.querySelector('[data-testid="search-ask-voice"]')).toBeTruthy();
  });

  it('відкрите поле: коротка підказка «Пошук», щоб не обрізалась біля «Скасувати»; повна назва лишається для читача екрана', () => {
    const { container } = mount();
    fireEvent.click(container.querySelector('[data-search-pill]') as HTMLElement);
    const box = screen.getByRole('searchbox');
    expect(box.getAttribute('placeholder')).toBe('Пошук');
    expect(box.getAttribute('aria-label')).toBe('Пошук або спитати Nightpost');
  });

  it('мікрофон у полі пошуку відкриває живу розмову з Nightpost', () => {
    const { container } = mount();
    fireEvent.click(container.querySelector('[data-search-pill]') as HTMLElement);
    fireEvent.click(screen.getByTestId('search-ask-voice'));
    expect(navigate).toHaveBeenCalledWith('call-phantom', '{}');
  });

  it('у пошуку з запитом перший рядок — «Спитати Nightpost: «…»», і дотик шле запит у розмову з Nightpost', () => {
    const { container } = mount();
    fireEvent.click(container.querySelector('[data-search-pill]') as HTMLElement);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'plan friday' } });

    const list = container.querySelector('[data-phantom-list]') as HTMLElement;
    const first = list.querySelector('button') as HTMLElement;
    expect(first.getAttribute('data-testid')).toBe('search-ask');
    expect(first.textContent).toContain('Спитати Nightpost: «plan friday»');
    expect(first.querySelector('[data-phantom-mark]')).toBeTruthy();

    fireEvent.click(first);
    expect(useMessengerStore.getState().activeChatId).toBe('chat_phantom');
    expect(send).toHaveBeenCalledWith('plan friday');
    expect(container.querySelector('[data-search-box]')).toBeNull();
    expect(container.querySelectorAll('[data-search-pill]').length).toBe(1);
  });

  it('розмови з Nightpost ще немає — питання не губиться мовчки: пошук лишається, людині кажуть чому', () => {
    useMessengerStore.setState({ chats: [CHATS[1]] } as never);
    const { container } = mount();
    fireEvent.click(container.querySelector('[data-search-pill]') as HTMLElement);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'plan friday' } });
    fireEvent.click(screen.getByTestId('search-ask'));
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByRole('searchbox')).toBeTruthy();
    expect(screen.getByTestId('ask-note').textContent).toBe('Розмова з Nightpost ще не готова — спробуйте за мить.');
  });
});
