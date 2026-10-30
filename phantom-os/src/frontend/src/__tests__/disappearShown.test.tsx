/**
 * Власник 20.10: строк зникнення «нікому не відображає». Активний строк видно
 * в шапці розмови, а в групі пункт не зникає мовчки — він каже, чому його нема.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
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

afterEach(cleanup);

const ME = { id: 'me', name: 'Max', avatar: '' } as unknown as UserProfile;
const dm = { id: 'peer_ok', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'a1b2' } as unknown as Chat;
const group = { ...dm, id: 'g', type: 'group', peerNodeId: 'g1', members: [{ id: 'o', name: 'O' }, { id: 'me', name: 'Max' }] } as unknown as Chat;
const noop = () => {};

const head = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header currentChat={c} currentUser={ME} onOpenSettings={noop} onOpenGroupDetails={noop} isSoundEnabled
        onToggleSound={noop} onToggleSearch={noop} isSearching={false} onBack={noop} />
    </MemoryRouter>,
  );

describe('строк зникнення видно', () => {
  it('шапка розмови показує активний строк', () => {
    head({ ...dm, disappearSeconds: 86_400 } as Chat);
    const sign = screen.getByTestId('header-timer');
    expect(sign.textContent).toBe('1 день');
    expect(sign.getAttribute('aria-label')).toBe('Зникаючі повідомлення: 1 день');
  });

  it('без строку шапка нічого не вигадує', () => {
    head({ ...dm, disappearSeconds: 0 } as Chat);
    expect(screen.queryByTestId('header-timer')).toBeNull();
  });

  it('у групі пункт лишається і чесно каже, що він лише для розмов удвох', () => {
    render(<PhoneChatMenu chat={group} onClose={noop} onOpenCard={noop} onOpenGroupDetails={noop} />);
    const row = screen.getByTestId('timer-door-group');
    expect(row.textContent).toBe('Зникаючі повідомленняЛише в розмовах удвох');
    expect(row.getAttribute('aria-disabled')).toBe('true');
    expect(row.querySelector('button')).toBeNull();
  });

  it('у розмові вдвох рядка «лише вдвох» немає', () => {
    render(<PhoneChatMenu chat={dm} onClose={noop} onOpenCard={noop} onOpenGroupDetails={noop} />);
    expect(screen.queryByTestId('timer-door-group')).toBeNull();
    expect(screen.getByText('Зникаючі повідомлення')).toBeTruthy();
  });
});
