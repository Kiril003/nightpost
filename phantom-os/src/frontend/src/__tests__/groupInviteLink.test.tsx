/**
 * P06 — посилання-запрошення в гілку.
 *
 * Червоніти вміє: приберіть перевірку `powers.invite` у GroupInviteLinkRow —
 * падає «без права запрошувати рядка немає»; приберіть рядок із
 * GroupSettingsPanel — падає «з правом рядок є»; приберіть перехоплення
 * в FormattedMessageText — падає «тап по посиланню відчиняє аркуш вступу».
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

import { GroupSettingsPanel } from '../components/messenger/GroupSettingsPanel';
import { GroupJoinDoor } from '../components/messenger/GroupJoinDoor';
import { FormattedMessageText } from '../components/messenger/FormattedMessageText';
import { groupLinksApi, isGroupLink } from '../services/groupLinks';
import type { Chat } from '../types/messenger';

const LINK = 'https://phantom-os.dev/g/#g00000000001.Abcdefgh23456789.2.0JTQstGW0YA.PH2code';

const BASE = { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true };

const chat = (extra: { settings: boolean; invite: boolean }): Chat =>
  ({
    id: 'g00000000001',
    title: 'Двір',
    avatar: '',
    type: 'group',
    circle: 'all',
    unreadCount: 0,
    messages: [],
    groupSettings: { kind: 'group', slow: 0, hist: false, ask: false, link: '', talk: true, sign: false },
    powers: { ...BASE, ...extra },
  }) as Chat;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('group invite link', () => {
  it('без права запрошувати рядка немає', () => {
    const get = vi.spyOn(groupLinksApi, 'get');
    render(<GroupSettingsPanel chat={chat({ settings: true, invite: false })} />);
    expect(screen.getByTestId('group-settings')).toBeTruthy();
    expect(screen.queryByTestId('group-invite-link')).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });

  it('з правом рядок є: створити, показати, відкликати', async () => {
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: '', ready: true });
    const renew = vi.spyOn(groupLinksApi, 'renew').mockResolvedValue({ may: true, link: LINK, ready: true });
    const revoke = vi.spyOn(groupLinksApi, 'revoke').mockResolvedValue({ may: true, link: '', ready: true });
    render(<GroupSettingsPanel chat={chat({ settings: true, invite: true })} />);
    expect(screen.getByTestId('group-invite-link')).toBeTruthy();
    const create = await screen.findByTestId('group-invite-link-create');
    await waitFor(() => expect((create as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(create);
    expect(await screen.findByTestId('group-invite-link-url')).toHaveProperty('textContent', LINK);
    expect(renew).toHaveBeenCalledWith('g00000000001');
    fireEvent.click(screen.getByTestId('group-invite-link-revoke'));
    await screen.findByTestId('group-invite-link-create');
    expect(revoke).toHaveBeenCalledWith('g00000000001');
  });

  it('тап по посиланню відчиняє аркуш вступу, відкликане — чесна відмова', async () => {
    vi.spyOn(groupLinksApi, 'open').mockResolvedValue({ group_id: 'g00000000001', name: 'Двір', count: 2, sharer: 'Кирило', stage: 'none' });
    const join = vi
      .spyOn(groupLinksApi, 'join')
      .mockResolvedValue({ group_id: 'g00000000001', name: 'Двір', count: 2, sharer: 'Кирило', stage: 'refused', why: 'gone' });
    render(
      <>
        <GroupJoinDoor />
        <FormattedMessageText text={`ось ${LINK}`} />
      </>,
    );
    expect(isGroupLink(LINK)).toBe(true);
    expect(isGroupLink(LINK.replace('/g/', '/i/'))).toBe(false);
    fireEvent.click(screen.getByText(LINK));
    expect((await screen.findByTestId('group-join-name')).textContent).toBe('Двір');
    fireEvent.click(screen.getByTestId('group-join-go'));
    expect(await screen.findByTestId('group-join-refused')).toBeTruthy();
    expect(join).toHaveBeenCalledWith(LINK);
  });
});
