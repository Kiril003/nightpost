import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ChatsProfileTitle } from '../components/messenger/ProfileFan';
import { loadChatProfiles, useChatProfiles } from '../stores/chatProfileStore';
import { registerProfilesOpener, templateDefaults, useProfiles, type Profile, type ProfileKind } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { unreadByProfile } from '../services/profileScope';
import type { Chat } from '../types/messenger';

const P = (id: string, name: string, kind: ProfileKind, uses = 0): Profile => ({ ...templateDefaults(kind, 1), id, name, uses });
const personal = P('personal', 'Особисте', 'personal', 5);
const work = P('work', 'Робота', 'work', 9);
const family = P('family', 'Сімʼя', 'family', 3);

const chat = (id: string, unreadCount: number, extra: Partial<Chat> = {}): Chat =>
  ({ id, title: id, type: 'dm', circle: 'all', avatar: '', unreadCount, pinned: false, messages: [], lastAt: '2026-10-27T10:00:00Z', ...extra }) as unknown as Chat;

const initial = useProfiles.getState();
let prevStore: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  localStorage.clear();
  useChatProfiles.setState(loadChatProfiles());
  useProfiles.setState({ activeId: 'personal', profiles: [personal, work, family], previousId: null });
  useChatProfiles.setState({ map: { mine: 'personal', w1: 'work', w2: 'work', f1: 'family' }, migratedAt: 1 });
  prevStore = useMessengerStore.getState();
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  localStorage.clear();
  useProfiles.setState({ activeId: initial.activeId, profiles: initial.profiles, previousId: null });
  useMessengerStore.setState(prevStore, true);
});

const setChats = (chats: Chat[]) => useMessengerStore.setState({ chats } as never);

describe('unreadByProfile', () => {
  it('рахує лише свої розмови: без тиші, архіву, Збереженого й помічника', () => {
    const counts = unreadByProfile(
      [
        chat('w1', 2),
        chat('w2', 1, { muted: true } as Partial<Chat>),
        chat('f1', 4, { archived: true } as Partial<Chat>),
        chat('mine', 1),
        chat('bot', 7, { type: 'phantom' } as Partial<Chat>),
      ],
      useChatProfiles.getState().map,
      [personal, work, family],
    );
    expect(counts).toEqual({ work: 2, personal: 1 });
  });
});

describe('заголовок «Розмов» — активний профіль', () => {
  it('один профіль — звичайна назва, без перемикача', () => {
    useProfiles.setState({ activeId: 'personal', profiles: [personal], previousId: null });
    render(<h1><ChatsProfileTitle fallback="Розмови" /></h1>);
    expect(screen.getByRole('heading').textContent).toBe('Розмови');
    expect(screen.queryByTestId('chats-profile-title')).toBeNull();
  });

  it('кілька профілів — назва активного з кольоровою крапкою, лічильник інших не бурштиновий', () => {
    setChats([chat('w1', 3), chat('f1', 2), chat('mine', 4)]);
    render(<ChatsProfileTitle fallback="Розмови" />);
    const title = screen.getByTestId('chats-profile-title');
    expect(title.textContent).toContain('Особисте');
    expect(screen.getByTestId('chats-profile-dot').getAttribute('data-accent')).toBe(personal.look?.accent ?? null);
    const badge = screen.getByTestId('chats-profile-elsewhere');
    expect(badge.textContent).toBe('5');
    expect(badge.className).not.toMatch(/accent/);
    expect(title.getAttribute('aria-label')).toContain('Непрочитане в інших профілях');
  });

  it('непрочитане лише у своєму профілі, у тиші чи в помічнику — без лічильника', () => {
    setChats([chat('mine', 3), chat('w1', 2, { muted: true } as Partial<Chat>), chat('bot', 5, { type: 'phantom' } as Partial<Chat>)]);
    render(<ChatsProfileTitle fallback="Розмови" />);
    expect(screen.queryByTestId('chats-profile-elsewhere')).toBeNull();
    expect(screen.getByTestId('chats-profile-title').getAttribute('aria-label')).not.toContain('Непрочитане');
  });

  it('аркуш: обличчя, назва й непрочитане кожного; дотик перемикає профіль', () => {
    setChats([chat('w1', 1), chat('w2', 2)]);
    render(<ChatsProfileTitle fallback="Розмови" />);
    fireEvent.click(screen.getByTestId('chats-profile-title'));
    const pickWork = screen.getByTestId('chats-profile-pick-work');
    expect(pickWork.textContent).toContain('Робота');
    expect(pickWork.textContent).toContain('3 нових');
    expect(screen.getByTestId('chats-profile-pick-personal').getAttribute('aria-current')).toBe('true');
    expect(screen.getByTestId('chats-profile-pick-family').textContent).not.toMatch(/нових/);
    fireEvent.click(pickWork);
    expect(useProfiles.getState().activeId).toBe('work');
    expect(screen.getByTestId('chats-profile-title').getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByTestId('chats-profile-title').textContent).toContain('Робота');
  });

  it('останній рядок «Керувати профілями» веде до керування', () => {
    const opened = vi.fn();
    const off = registerProfilesOpener(opened);
    render(<ChatsProfileTitle fallback="Розмови" />);
    fireEvent.click(screen.getByTestId('chats-profile-title'));
    const manage = screen.getByTestId('chats-profile-manage');
    expect(manage.textContent).toBe('Керувати профілями');
    fireEvent.click(manage);
    expect(opened).toHaveBeenCalledTimes(1);
    off?.();
  });
});
