/**
 * P12 — вступ за заявкою.
 *
 * Червоніти вміє: приберіть GroupJoinRequests із GroupSettingsPanel — падає
 * «з правом видно перемикач і заявки»; приберіть гілку `pending` у GroupJoinDoor —
 * падає «той, хто просився, бачить, що заявка на розгляді».
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

import { GroupSettingsPanel } from '../components/messenger/GroupSettingsPanel';
import { GroupJoinDoor } from '../components/messenger/GroupJoinDoor';
import { groupLinksApi, groupRequestsApi, openGroupLink } from '../services/groupLinks';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const GID = 'g00000000001';
const LINK = `https://phantom-os.dev/g/#${GID}.Abcdefgh23456789.2.0JTQstGW0YA.PH2code`;
const BASE = { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true, settings: true };

const chat = (invite: boolean, ask: boolean): Chat =>
  ({
    id: GID,
    title: 'Двір',
    avatar: '',
    type: 'group',
    circle: 'all',
    unreadCount: 0,
    messages: [],
    groupSettings: { kind: 'group', slow: 0, hist: true, ask, link: '', talk: true, sign: false },
    powers: { ...BASE, invite },
  }) as Chat;

const OKSANA = { peer: 'cccc3333cccc3333', name: 'Оксана', at: 1 };
const MARKO = { peer: 'dddd4444dddd4444', name: '', at: 2 };

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('group join requests', () => {
  it('без права запрошувати ні перемикача, ні заявок', () => {
    const list = vi.spyOn(groupRequestsApi, 'list');
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: false, link: '', ready: true });
    render(<GroupSettingsPanel chat={chat(false, true)} />);
    expect(screen.queryByTestId('group-join-requests')).toBeNull();
    expect(list).not.toHaveBeenCalled();
  });

  it('з правом видно перемикач і заявки: прийняти й відхилити', async () => {
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: '', ready: true });
    vi.spyOn(groupRequestsApi, 'list').mockResolvedValue({ may: true, ask: true, requests: [OKSANA, MARKO] });
    const decide = vi
      .spyOn(groupRequestsApi, 'decide')
      .mockResolvedValueOnce({ may: true, ask: true, requests: [MARKO] })
      .mockResolvedValueOnce({ may: true, ask: true, requests: [] });
    render(<GroupSettingsPanel chat={chat(true, true)} />);

    expect(screen.getByTestId('setting-ask').getAttribute('aria-checked')).toBe('true');
    await waitFor(() => expect(screen.getByTestId('group-settings-requests').textContent).toContain('2'));
    fireEvent.click(screen.getByTestId('group-settings-requests'));
    expect((await screen.findByTestId('group-requests-title')).textContent).toBe('Заявки (2)');
    expect(screen.getByTestId(`group-request-${OKSANA.peer}`).textContent).toContain('Оксана');
    expect(screen.queryByTestId(`group-request-${MARKO.peer}`)).toBeNull();
    fireEvent.click(screen.getByTestId('group-requests-bare-toggle'));
    expect(screen.getByTestId(`group-request-${MARKO.peer}`).textContent).not.toContain('dddd4444');

    fireEvent.click(screen.getByTestId(`group-request-accept-${OKSANA.peer}`));
    await waitFor(() => expect(screen.getByTestId('group-requests-title').textContent).toBe('Заявки (1)'));
    expect(decide).toHaveBeenNthCalledWith(1, GID, OKSANA.peer, true);

    fireEvent.click(screen.getByTestId(`group-request-decline-${MARKO.peer}`));
    await waitFor(() => expect(screen.getByTestId('group-requests-title').textContent).toBe('Заявки (0)'));
    expect(decide).toHaveBeenNthCalledWith(2, GID, MARKO.peer, false);
    expect(screen.getByText('Заявок немає')).toBeTruthy();
  });

  it('перемикач «Вступ за заявкою» шле акт ask', async () => {
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: '', ready: true });
    vi.spyOn(groupRequestsApi, 'list').mockResolvedValue({ may: true, ask: false, requests: [] });
    const set = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({ setGroupSettings: set });
    render(<GroupSettingsPanel chat={chat(true, false)} />);
    expect(screen.queryByTestId('group-requests-title')).toBeNull();
    fireEvent.click(screen.getByTestId('setting-ask'));
    await waitFor(() => expect(set).toHaveBeenCalledWith(GID, { ask: 'on' }));
  });

  it('той, хто просився, бачить, що заявка на розгляді, а потім чесну відмову', async () => {
    vi.spyOn(groupLinksApi, 'open').mockResolvedValue({ group_id: GID, name: 'Двір', count: 2, sharer: 'Кирило', stage: 'none' });
    vi.spyOn(groupLinksApi, 'join').mockResolvedValue({ group_id: GID, name: 'Двір', count: 2, sharer: 'Кирило', stage: 'pending' });
    render(<GroupJoinDoor />);
    openGroupLink(LINK);
    fireEvent.click(await screen.findByTestId('group-join-go'));
    expect((await screen.findByTestId('group-join-pending')).textContent).toBe('Заявка на розгляді');
    expect(screen.queryByTestId('group-join-go')).toBeNull();

    vi.spyOn(groupLinksApi, 'open').mockResolvedValue({ group_id: GID, name: 'Двір', count: 2, sharer: 'Кирило', stage: 'refused', why: 'no' });
    openGroupLink(LINK);
    expect((await screen.findByTestId('group-join-refused')).textContent).toBe('Заявку відхилено');
  });
});
