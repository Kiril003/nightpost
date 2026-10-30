/**
 * Особливі стани інфо-вигляду (L4 зріз 4, st-noperm / st-left / st-removed /
 * st-blocked / st-stranger / st-deleted).
 *
 * Червоніти вміє: приберіть `histHidden` у GroupProfileSheet — падає «історія
 * прихована»; поверніть вкладку «Учасники» для `standing: 'left'` — падає
 * «після виходу немає складу»; зробіть кнопку «Написати» в `stranger`
 * бурштиновою (`!tonal`) — падає «незнайомець має тональну головну дію».
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { PersonInfoState } from '../components/messenger/PersonInfoState';
import { messengerApi } from '../services/messengerApi';
import * as messengerMedia from '../services/messengerMedia';
import { indexShared } from '../services/peerShared';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const ROSTER = {
  id: 'g1',
  title: 'Скелі',
  members: [
    { id: 'me', name: 'Я', role: 'member', may_remove: false, may_grant: false, may_own: false, in_roster: true, verified: true, session_ready: true, is_me: true },
    { id: 'p1', name: 'Софія', role: 'owner', may_remove: false, may_grant: false, may_own: false, in_roster: true, verified: true, session_ready: true, is_me: false },
  ],
  addable: [],
  max_members: 32,
  authoritative: false,
  standing: 'in',
  my_role: 'member',
  owner_id: 'p1',
  powers: { add: false, remove: false, rename: false, card: false, grant: false, transfer: false, pin: false, settings: false },
};

const photoMsg = {
  id: 'm1',
  senderId: 'p1',
  senderName: 'Софія',
  senderAvatar: '',
  timestamp: '10:00',
  sentAt: '2026-10-20T10:00:00Z',
  type: 'image',
  text: '',
  media: { name: 'скелі.jpg', size: 1024, mime: 'image/jpeg', sha256: 'ab', blobId: 'b1', keyHex: '00', nonceHex: '00' },
} as unknown as Message;

const leaveAct = {
  id: 'act_leave',
  senderId: 'me',
  senderName: 'Я',
  senderAvatar: '',
  timestamp: '12:00',
  sentAt: '2026-10-22T12:00:00Z',
  type: 'system',
  systemAct: 'leave',
  text: 'Ви вийшли',
  isSelf: true,
} as unknown as Message;

const makeGroup = (over: Partial<Chat> = {}): Chat => ({
  id: 'g1',
  title: 'Скелі',
  avatar: '',
  type: 'group',
  circle: 'friends',
  unreadCount: 0,
  messages: [],
  ...over,
});

const LiveGroup: React.FC<{ onClose?: () => void; onSearch?: () => void }> = ({ onClose = () => {}, onSearch }) => {
  const c = useMessengerStore((s) => s.chats[0]);
  if (!c) return null;
  return <GroupProfileSheet chat={c} onClose={onClose} onSelectMember={() => {}} onTogglePinChat={() => {}} onSearch={onSearch} />;
};

beforeEach(() => {
  vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(ROSTER as never);
  vi.spyOn(messengerMedia, 'cachedMediaUrl').mockReturnValue(undefined);
  vi.spyOn(messengerMedia, 'loadMedia').mockResolvedValue('blob:state-photo');
  useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, id: 'me', name: 'Я' } } as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [], activeChatId: '' });
});

describe('група: прихована історія та вихід/видалення (st-noperm, st-left, st-removed)', () => {
  it('st-noperm: коли hist === false для учасника і медіа немає — вкладка «Медіа» видима без лічильника й пояснює чому', async () => {
    useMessengerStore.setState({
      chats: [
        makeGroup({
          myRole: 'member',
          groupSettings: { kind: 'group', slow: 0, hist: false, ask: false, link: '', talk: true, sign: false },
        }),
      ],
    });
    render(<LiveGroup />);
    await screen.findByText('Софія');

    const mediaTab = screen.getByTestId('info-tab-media');
    expect(mediaTab.textContent).toBe('Медіа');
    fireEvent.click(mediaTab);
    expect(screen.getByTestId('group-hist-hidden').textContent).toContain('Медіа до вашого вступу приховане');
  });

  it('st-left: підпис із датою виходу з журналу, без вкладки «Учасники», лише пошук і видалення чату з підтвердженням', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({ ...ROSTER, standing: 'left' } as never);
    const del = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true } as never);
    const onClose = vi.fn();
    useMessengerStore.setState({
      chats: [makeGroup({ standing: 'left', messages: [photoMsg, leaveAct] })],
      activeChatId: 'g1',
    });
    render(<LiveGroup onClose={onClose} onSearch={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('info-kind').textContent).toBe('Група · ви вийшли 22.10'));
    expect(screen.queryByTestId('info-tab-members')).toBeNull();
    expect(screen.getByTestId('info-tab-media').getAttribute('aria-selected')).toBe('true');

    const acts = within(screen.getByTestId('info-actions')).getAllByRole('button').map((b) => b.getAttribute('data-testid'));
    expect(acts).toEqual(['group-action-search']);

    fireEvent.click(screen.getByTestId('group-row-delete'));
    expect(del).not.toHaveBeenCalled();
    const confirm = screen.getByTestId('group-delete-confirm');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Видалити' }));
    await waitFor(() => expect(del).toHaveBeenCalledWith('g1'));
    expect(useMessengerStore.getState().chats).toHaveLength(0);
    expect(onClose).toHaveBeenCalled();
  });

  it('st-removed: «Група · вас видалили», без керування й виходу в ⋮', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({ ...ROSTER, standing: 'removed' } as never);
    useMessengerStore.setState({ chats: [makeGroup({ standing: 'removed', messages: [photoMsg] })] });
    render(<LiveGroup />);

    await waitFor(() => expect(screen.getByTestId('info-kind').textContent).toBe('Група · вас видалили'));
    fireEvent.click(screen.getByTestId('info-more'));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Закріпити розмову', 'Журнал', 'Поскаржитися на групу']);
  });
});

describe('PersonInfoState: заблоковано, незнайомець із C1, видалений акаунт (st-blocked, st-stranger, st-deleted)', () => {
  it('st-blocked: підпис @handle · заблоковано, головна дія «Розблокувати», вимкнений дзвінок, скарга й видалення чату', async () => {
    const onUnblock = vi.fn();
    const onReport = vi.fn();
    const onDelete = vi.fn().mockResolvedValue(undefined);
    const shared = indexShared([photoMsg], 'me');

    render(
      <PersonInfoState
        state="blocked"
        name="Тарас Гнатюк"
        handle="taras.h"
        peerId="p1"
        shared={shared}
        sharedGroups={[{ id: 'g1', title: 'Скелі', subtitle: 'Група · 6 учасників' }]}
        whoSent={() => 'Тарас'}
        onBack={() => {}}
        onUnblock={onUnblock}
        onReport={onReport}
        onDeleteChat={onDelete}
        onSearch={() => {}}
        onJump={() => {}}
        onOpenImage={() => {}}
      />,
    );

    expect(screen.getByTestId('info-kind').textContent).toBe('@taras.h · заблоковано');
    const unblockBtn = screen.getByTestId('person-state-unblock');
    expect(unblockBtn.className).toContain('bg-accent');
    fireEvent.click(unblockBtn);
    expect(onUnblock).toHaveBeenCalled();

    expect((screen.getByTestId('person-state-call') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByTestId('person-state-report'));
    expect(onReport).toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('person-state-delete'));
    fireEvent.click(within(screen.getByTestId('person-delete-confirm')).getByRole('button', { name: 'Видалити' }));
    await waitFor(() => expect(onDelete).toHaveBeenCalled());
  });

  it('st-stranger: тональна «Написати» (бурштин віддано слоту C1) і вкладка «Спільні групи»', () => {
    const onSelectGroup = vi.fn();
    const shared = indexShared([], 'me');

    render(
      <PersonInfoState
        state="stranger"
        name="Назар Кравець"
        handle="@nazar.k"
        peerId="nazar"
        shared={shared}
        sharedGroups={[{ id: 'g1', title: 'Скелі', subtitle: 'Група · 6 учасників' }]}
        whoSent={() => 'Назар'}
        onBack={() => {}}
        onWrite={() => {}}
        onSearch={() => {}}
        onSelectGroup={onSelectGroup}
        onJump={() => {}}
        onOpenImage={() => {}}
        strangerSlot={<div data-testid="c1-slot">Не з ваших кіл</div>}
      />,
    );

    expect(screen.getByTestId('info-kind').textContent).toBe('@nazar.k');
    const writeBtn = screen.getByTestId('person-state-write');
    expect(writeBtn.className).toContain('bg-raised');
    expect(writeBtn.className).not.toContain('bg-accent');
    expect(screen.getByTestId('c1-slot').textContent).toBe('Не з ваших кіл');

    expect(screen.getByTestId('info-tab-groups').getAttribute('aria-selected')).toBe('true');
    fireEvent.click(screen.getByText('Скелі'));
    expect(onSelectGroup).toHaveBeenCalledWith('g1');
  });

  it('st-deleted: «Видалений акаунт» · «Був у ваших контактах», нейтральний силует, лише пошук і видалення чату, історія медіа збережена', () => {
    const shared = indexShared([photoMsg], 'me');

    render(
      <PersonInfoState
        state="deleted"
        name="Старе Імʼя"
        shared={shared}
        sharedGroups={[{ id: 'g1', title: 'Скелі' }]}
        whoSent={() => 'Хтось'}
        onBack={() => {}}
        onDeleteChat={() => {}}
        onSearch={() => {}}
        onJump={() => {}}
        onOpenImage={() => {}}
      />,
    );

    expect(screen.getByTestId('info-title').textContent).toBe('Видалений акаунт');
    expect(screen.getByTestId('info-kind').textContent).toBe('Був у ваших контактах');
    expect(screen.getByTestId('person-deleted-avatar')).toBeTruthy();
    expect(screen.queryByTestId('person-state-call')).toBeNull();
    expect(screen.queryByTestId('info-tab-groups')).toBeNull();
    expect(screen.getByTestId('info-tab-media').textContent).toContain('1');
  });
});
