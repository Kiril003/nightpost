import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { GlobalSearchResults, type GlobalFindings } from '../components/messenger/GlobalSearch';
import { useMessengerStore } from '../stores/messengerStore';
import { NO_FILTERS, type PersonResult } from '../services/globalSearch';
import type { Chat } from '../types/messenger';

const makeChat = (id: string, name: string, handle: string, overrides?: Partial<Chat>): Chat => ({
  id,
  title: name,
  handle,
  avatar: '',
  type: 'dm',
  unreadCount: 0,
  circle: 'social',
  messages: [],
  members: [{ id, name, handle, avatar: '', role: 'member' as const }],
  ...overrides,
});

const makePerson = (id: string, name: string, handle: string, chatOverrides?: Partial<Chat>): PersonResult => ({
  key: `chat:${id}`,
  chatId: id,
  name,
  handle,
  at: 0,
  chat: makeChat(id, name, handle, chatOverrides),
});

const makeFindings = (people: PersonResult[]): GlobalFindings => ({
  filters: NO_FILTERS,
  setFilters: () => undefined,
  rings: [],
  needle: 'olena',
  people,
  letters: [],
  settled: true,
  network: { kind: 'off' },
});

describe('GlobalSearchResults same-name disambiguation', () => {
  beforeEach(() => {
    useMessengerStore.setState({ chats: [] });
  });

  it('shows ring names from store direct chats when two Olenas have different rings (one in close)', () => {
    const chatA = makeChat('a', 'Olena', 'olena_k', { circle: 'close' });
    const chatB = makeChat('b', 'Olena', 'olena_p', { circle: 'work' });
    useMessengerStore.setState({ chats: [chatA, chatB] });

    const found = makeFindings([
      makePerson('a', 'Olena', 'olena_k'),
      makePerson('b', 'Olena', 'olena_p'),
    ]);
    const { container } = render(
      <GlobalSearchResults
        query="olena"
        found={found}
        onOpenChat={vi.fn()}
        onOpenMessage={vi.fn()}
      />,
    );
    const rows = container.querySelectorAll('[data-search-person]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Olena · Близькі');
    expect(rows[1].textContent).toContain('Olena · Робота');
  });

  it('falls back to @handle without repeating it on the second line when neither has a ring', () => {
    const found = makeFindings([
      makePerson('a', 'Olena', 'olena_k'),
      makePerson('b', 'Olena', '@olena_p'),
    ]);
    const { container } = render(
      <GlobalSearchResults
        query="olena"
        found={found}
        onOpenChat={vi.fn()}
        onOpenMessage={vi.fn()}
      />,
    );
    const rows = container.querySelectorAll('[data-search-person]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Olena · @olena_k');
    expect(rows[1].textContent).toContain('Olena · @olena_p');
    expect(rows[0].textContent?.match(/@olena_k/g)).toHaveLength(1);
    expect(rows[1].textContent?.match(/@olena_p/g)).toHaveLength(1);
  });

  it('does not self-collide when the same person appears in a DM row and a group member row', () => {
    const dmChat = makeChat('dm-olena', 'Olena', 'olena_k', {
      circle: 'close',
      members: [
        { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
        { id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const },
      ],
    });
    useMessengerStore.setState({ chats: [dmChat] });

    const dmRow: PersonResult = {
      key: 'chat:dm-olena',
      chatId: 'dm-olena',
      name: 'Olena',
      handle: 'olena_k',
      at: 0,
      chat: dmChat,
    };
    const memberRow: PersonResult = {
      key: 'member:grp-1:u1',
      chatId: 'grp-1',
      name: 'Olena',
      handle: 'olena_k',
      where: 'Team',
      at: 0,
      member: { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
    };

    const { container } = render(
      <GlobalSearchResults
        query="olena"
        found={makeFindings([dmRow, memberRow])}
        onOpenChat={vi.fn()}
        onOpenMessage={vi.fn()}
      />,
    );
    const rows = container.querySelectorAll('[data-search-person]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).not.toContain(' · ');
    expect(rows[1].textContent).not.toContain(' · ');
  });

  it('shows no suffix when there is only one Olena', () => {
    const chatA = makeChat('a', 'Olena', 'olena_k', { circle: 'close' });
    useMessengerStore.setState({ chats: [chatA] });

    const found = makeFindings([makePerson('a', 'Olena', 'olena_k')]);
    const { container } = render(
      <GlobalSearchResults
        query="olena"
        found={found}
        onOpenChat={vi.fn()}
        onOpenMessage={vi.fn()}
      />,
    );
    const rows = container.querySelectorAll('[data-search-person]');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).not.toContain(' · ');
    expect(rows[0].textContent).toContain('@olena_k');
  });

  it('gives no suffix when two group chats have the same title', () => {
    const groupA = makeChat('grp-a', 'Design', 'design_a', { type: 'group', members: [] });
    const groupB = makeChat('grp-b', 'Design', 'design_b', { type: 'group', members: [] });
    useMessengerStore.setState({ chats: [groupA, groupB] });

    const found = makeFindings([
      {
        key: 'chat:grp-a',
        chatId: 'grp-a',
        name: 'Design',
        handle: 'design_a',
        at: 0,
        chat: groupA,
      },
      {
        key: 'chat:grp-b',
        chatId: 'grp-b',
        name: 'Design',
        handle: 'design_b',
        at: 0,
        chat: groupB,
      },
    ]);

    const { container } = render(
      <GlobalSearchResults
        query="design"
        found={found}
        onOpenChat={vi.fn()}
        onOpenMessage={vi.fn()}
      />,
    );
    const rows = container.querySelectorAll('[data-search-person]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).not.toContain(' · ');
    expect(rows[1].textContent).not.toContain(' · ');
  });

  it('excludes a DM without a resolvable peer from the same-name suffix map', () => {
    const orphanDm = makeChat('dm-orphan', 'Olena', 'olena_orphan', { members: undefined });
    const validDm = makeChat('dm-valid', 'Olena', 'olena_k', {
      members: [{ id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const }],
    });
    useMessengerStore.setState({ chats: [orphanDm, validDm] });

    const found = makeFindings([
      {
        key: 'chat:dm-orphan',
        chatId: 'dm-orphan',
        name: 'Olena',
        handle: 'olena_orphan',
        at: 0,
        chat: orphanDm,
      },
      {
        key: 'chat:dm-valid',
        chatId: 'dm-valid',
        name: 'Olena',
        handle: 'olena_k',
        at: 0,
        chat: validDm,
      },
    ]);

    const { container } = render(
      <GlobalSearchResults
        query="olena"
        found={found}
        onOpenChat={vi.fn()}
        onOpenMessage={vi.fn()}
      />,
    );
    const rows = container.querySelectorAll('[data-search-person]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).not.toContain(' · ');
    expect(rows[1].textContent).not.toContain(' · ');
  });
});
