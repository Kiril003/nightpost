/**
 * Налаштування дзвінка досяжні без набору: рядок у «Ще» розмови (телефон і ПК)
 * відкриває аркуш і не кладе жодного дзвінка. Слухавки в шапці дзвонять одразу.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { Header } from '../components/messenger/Header';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import type { Chat, UserProfile } from '../types/messenger';

const ME = { id: 'me', name: 'Max', avatar: '' } as unknown as UserProfile;
const dm = { id: 'chat_o', title: 'Oksana', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' } as unknown as Chat;
const group = { ...dm, id: 'g', type: 'group', peerNodeId: 'g1', members: [{ id: 'o', name: 'O' }, { id: 'me', name: 'Max' }] } as unknown as Chat;

const draw = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header currentChat={c} currentUser={ME} onOpenSettings={() => {}} onOpenGroupDetails={() => {}} isSoundEnabled
        onToggleSound={() => {}} onToggleSearch={() => {}} isSearching={false} onBack={() => {}} />
    </MemoryRouter>,
  );

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('вхід у налаштування дзвінка', () => {
  it('ПК: «Ще» → «Налаштування дзвінка» відкриває аркуш і нікого не набирає', () => {
    const dialled = vi.fn();
    window.addEventListener('phantom:start-call', dialled);
    draw(dm);
    fireEvent.click(screen.getByRole('button', { name: 'Ще' }));
    fireEvent.click(screen.getByRole('button', { name: 'Налаштування дзвінка' }));
    expect(screen.getByRole('dialog', { name: 'Дзвінки' })).toBeTruthy();
    expect(dialled).not.toHaveBeenCalled();
    window.removeEventListener('phantom:start-call', dialled);
  });

  it('ПК: у групі, де слухавки нема, нема й рядка', () => {
    draw(group);
    fireEvent.click(screen.getByRole('button', { name: 'Ще' }));
    expect(screen.queryByRole('button', { name: 'Дзвінки · для всіх розмов' })).toBeNull();
  });

  describe('телефон (є міст)', () => {
    beforeEach(() => {
      (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    });
    afterEach(() => {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    });

    it('шапка: ⋮ → двері → аркуш, без набору', () => {
      const dialled = vi.fn();
      window.addEventListener('phantom:start-call', dialled);
      draw(dm);
      fireEvent.click(screen.getByRole('button', { name: 'Ще' }));
      fireEvent.click(screen.getByText('Дзвінки · для всіх розмов'));
      expect(screen.getByRole('dialog', { name: 'Дзвінки' })).toBeTruthy();
      expect(dialled).not.toHaveBeenCalled();
      window.removeEventListener('phantom:start-call', dialled);
    });

    it('шапка: у групі дверей нема', () => {
      draw(group);
      fireEvent.click(screen.getByRole('button', { name: 'Ще' }));
      expect(screen.queryByText('Для всіх дзвінків')).toBeNull();
    });
  });

  it('аркуш закривається, коли розмова змінилась', () => {
    const view = draw(dm);
    fireEvent.click(screen.getByRole('button', { name: 'Ще' }));
    fireEvent.click(screen.getByRole('button', { name: 'Налаштування дзвінка' }));
    expect(screen.queryByRole('dialog', { name: 'Дзвінки' })).toBeTruthy();
    view.rerender(
      <MemoryRouter>
        <Header currentChat={group} currentUser={ME} onOpenSettings={() => {}} onOpenGroupDetails={() => {}} isSoundEnabled
          onToggleSound={() => {}} onToggleSearch={() => {}} isSearching={false} onBack={() => {}} />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('dialog', { name: 'Дзвінки' })).toBeNull();
  });

  it('телефон: двері в меню розмови закривають меню і відкривають налаштування', () => {
    const onClose = vi.fn();
    const onCallSettings = vi.fn();
    render(<PhoneChatMenu chat={dm} onClose={onClose} onOpenCard={() => {}} onOpenGroupDetails={() => {}} onCallSettings={onCallSettings} />);
    fireEvent.click(screen.getByText('Дзвінки · для всіх розмов'));
    expect(onClose).toHaveBeenCalled();
    expect(onCallSettings).toHaveBeenCalled();
  });

  it('телефон: без дзвінків — без дверей', () => {
    render(<PhoneChatMenu chat={dm} onClose={() => {}} onOpenCard={() => {}} onOpenGroupDetails={() => {}} />);
    expect(screen.queryByText('Для всіх дзвінків')).toBeNull();
  });
});
