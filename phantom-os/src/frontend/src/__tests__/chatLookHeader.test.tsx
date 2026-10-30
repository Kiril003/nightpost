/**
 * Conversation header, Nightpost design system 2.1 + mockup 04-conversation:
 * one 56 bar on `bg` with a 0.5 separator, arrow back, avatar 40 (circle for a
 * person, tone square for a group, sheet D), name 17/600, circle tag from the
 * chat's real circle, one status line 13 text-3. Green is money only and amber is never typing.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
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
import { useLiving } from '../services/livingPulse';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const ME = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const chat = (extra: Partial<Chat> = {}): Chat =>
  ({
    id: 'chat_o',
    title: 'Оксана',
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    messages: [],
    peerNodeId: 'c1',
    ...extra,
  }) as unknown as Chat;

const draw = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={c}
        currentUser={ME}
        onOpenSettings={() => {}}
        onOpenGroupDetails={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onToggleSearch={() => {}}
        isSearching={false}
        onBack={() => {}}
      />
    </MemoryRouter>,
  );

beforeEach(() => {
  useLiving.setState({ pulse: null, faces: {} });
  useMessengerStore.setState({ typingStatus: {} } as never);
});
afterEach(cleanup);

describe('conversation header (2.1)', () => {
  it('one 56 bar on bg with a 0.5 separator', () => {
    const { container } = draw(chat());
    const bar = container.querySelector('[data-talk-bar]') as HTMLElement;
    expect(bar).toBeTruthy();
    expect(bar.className).toContain('h-14');
    const header = container.querySelector('header') as HTMLElement;
    expect(header.className).toContain('bg-canvas');
    expect(header.className).toContain('border-b-[0.5px]');
    expect(header.className).toContain('border-line');
  });

  it('back is an arrow, more is the vertical dots; icons are text-1 at 24', () => {
    const { container } = draw(chat());
    const call = screen.getByLabelText('Аудіодзвінок');
    expect(call.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect(call.querySelector('svg')?.getAttribute('class')).toContain('w-6');
    expect(screen.getByLabelText('Назад').querySelector('svg.lucide-arrow-left')).toBeTruthy();
    expect(container.querySelector('[aria-label="Ще"] svg.lucide-ellipsis-vertical')).toBeTruthy();
  });

  it('avatar 40: a person is a circle, a group a tone square (D)', () => {
    const { container } = draw(chat({ type: 'group', title: 'Двір', peerNodeId: undefined }));
    const face = container.querySelector('[data-shared="chat-avatar"] [aria-label]') as HTMLElement;
    expect(face.className).toContain('w-10');
    expect(face.className).toContain('rounded-control');
  });

  it('name 17/600, never bold', () => {
    const { container } = draw(chat());
    const name = container.querySelector('h2') as HTMLElement;
    expect(name.className).toContain('text-title-2');
    expect(name.className).not.toContain('font-bold');
  });

  it('B2: the circle tag sits on the status line, not next to the name', () => {
    draw(chat({ circle: 'friends' }));
    const tag = screen.getByTestId('header-circle');
    expect(tag.closest('[data-talk-status]')).not.toBeNull();
  });

  it('B3: in a group the typing line names who is typing', () => {
    useMessengerStore.setState({ typingStatus: { chat_o: 'Оксана друкує' } } as never);
    draw(chat({ type: 'group' } as never));
    expect(screen.getByTestId('header-typing').textContent).toBe('Оксана друкує');
  });

  it('circle tag only when the chat really sits in a circle', () => {
    const { unmount } = draw(chat({ circle: 'friends' }));
    const tag = screen.getByTestId('header-circle');
    expect(tag.textContent).toMatch(/^Друзі/);
    unmount();
    draw(chat({ circle: 'all' }));
    expect(screen.queryByTestId('header-circle')).toBeNull();
  });

  it('status line 13 text-3; typing is not amber', () => {
    useMessengerStore.setState({ typingStatus: { chat_o: 'Оксана друкує' } } as never);
    const { container } = draw(chat());
    const line = container.querySelector('[data-talk-status]') as HTMLElement;
    expect(line.className).toContain('text-footnote');
    expect(line.className).toContain('text-ink-3');
    expect(screen.getByTestId('header-typing').className).not.toMatch(/accent/);
  });

  it('near is said in words, without green and without a dot on the avatar', () => {
    useLiving.setState({ faces: { c1: { avatar: null, heardAtMs: null, lanSeenAtMs: Date.now() } } });
    const { container } = draw(chat());
    const near = screen.getByTestId('header-presence');
    expect(near.textContent).toBe('поруч');
    expect(container.innerHTML).not.toMatch(/text-success|bg-success|ring-2/);
  });

  it('group status names the people: "Olena, Petro and you"', () => {
    draw(
      chat({
        type: 'group',
        title: 'Friday pho',
        peerNodeId: undefined,
        members: [
          { id: 'o', name: 'Olena' },
          { id: 'p', name: 'Petro' },
          { id: 'me', name: 'Кирило' },
        ] as never,
      }),
    );
    expect(screen.getByTestId('header-plain-line').textContent).toBe('Olena, Petro і ви');
  });
});
