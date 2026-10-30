/**
 * LOOK msg-groups 4 — посилання-запрошення й заявки на вигляд Telegram.
 *
 * Червоніти вміє: поверніть `r.peer.slice(0, 8)` у GroupJoinRequests — падає
 * «заявка без імені — без сирого id, з аватаркою»; поверніть чотири кнопки в ряд
 * у GroupInviteLinkRow — падає «копіювати й поділитися — дві головні, решта тихі».
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, within, fireEvent, waitFor } from '@testing-library/react';

import { GroupSettingsPanel } from '../components/messenger/GroupSettingsPanel';
import { groupLinksApi, groupRequestsApi } from '../services/groupLinks';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const GID = 'g00000000001';
const LINK = `https://phantom-os.dev/g/#${GID}.Abcdefgh23456789.2.0JTQstGW0YA.PH2code`;
const BASE = { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true, settings: true, invite: true };

const chat = (): Chat =>
  ({
    id: GID,
    title: 'Двір',
    avatar: '',
    type: 'group',
    circle: 'all',
    unreadCount: 0,
    messages: [],
    groupSettings: { kind: 'group', slow: 0, hist: true, ask: true, link: '', talk: true, sign: false },
    powers: BASE,
  }) as Chat;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('look: invite link + join requests', () => {
  it('копіювати й поділитися — дві головні, решта тихі; посилання в один рядок', async () => {
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: LINK, ready: true });
    vi.spyOn(groupRequestsApi, 'list').mockResolvedValue({ may: true, ask: true, requests: [] });
    render(<GroupSettingsPanel chat={chat()} />);
    const url = await screen.findByTestId('group-invite-link-url');
    expect(url.className).toContain('truncate');
    expect(url.className).not.toContain('break-all');
    const main = screen.getByTestId('group-invite-link-main');
    expect(within(main).getByTestId('group-invite-link-copy')).toBeTruthy();
    expect(within(main).getByTestId('group-invite-link-share')).toBeTruthy();
    expect(within(main).queryByTestId('group-invite-link-renew')).toBeNull();
    expect(screen.getByTestId('group-invite-link-renew').className).not.toContain('bg-');
    expect(screen.getByTestId('group-invite-link-revoke').className).toContain('text-danger');
  });

  it('заявка без імені — не рядок з адресою, а «Без профілю · N» у кінці з «Відхилити всі»', async () => {
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: '', ready: true });
    vi.spyOn(groupRequestsApi, 'list').mockResolvedValue({ may: true, ask: true, requests: [{ peer: 'dddd4444dddd4444', name: '', at: 2 }] });
    const decide = vi.spyOn(groupRequestsApi, 'decide').mockResolvedValue({ may: true, ask: true, requests: [] });
    render(<GroupSettingsPanel chat={chat()} page="requests" />);
    const bare = await screen.findByTestId('group-requests-bare');
    expect(bare.textContent).toContain('Без профілю · 1');
    expect(bare.textContent).not.toContain('dddd4444');
    expect(screen.queryByTestId('group-request-dddd4444dddd4444')).toBeNull();
    fireEvent.click(screen.getByTestId('group-requests-bare-toggle'));
    expect(screen.getByTestId('group-request-dddd4444dddd4444').textContent).not.toMatch(/тому/);
    fireEvent.click(screen.getByTestId('group-requests-decline-all'));
    await waitFor(() => expect(decide).toHaveBeenCalledWith(GID, 'dddd4444dddd4444', false));
  });

  it('заявка — людина: фото й імʼя з її розмови, «У ваших людях», спільні групи, тап відкриває профіль', async () => {
    useMessengerStore.setState({
      chats: [
        { id: 'dm-ok', title: 'Оксана Мельник', avatar: 'data:image/png;base64,AAAA', type: 'dm', peerNodeId: 'cccc3333cccc3333', circle: 'all', unreadCount: 0, messages: [] },
        { id: 'g-yard', title: 'Сусіди', avatar: '', type: 'group', circle: 'all', unreadCount: 0, messages: [], members: [{ id: 'cccc3333cccc3333', name: 'Оксана', handle: '', avatar: '', role: 'member' }] },
      ] as unknown as Chat[],
    });
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: '', ready: true });
    vi.spyOn(groupRequestsApi, 'list').mockResolvedValue({ may: true, ask: true, requests: [{ peer: 'cccc3333cccc3333', name: '', at: Date.now() - 5 * 60000 }] });
    const open = vi.fn();
    render(<GroupSettingsPanel chat={chat()} page="requests" onOpenPerson={open} />);
    const row = await screen.findByTestId('group-request-cccc3333cccc3333');
    expect(row.textContent).toContain('Оксана Мельник');
    expect(row.textContent).toContain('У ваших людях');
    expect(row.textContent).toContain('Заявка 5 хв тому');
    expect(row.textContent).toContain('Спільні групи: Сусіди');
    expect(row.querySelector('img')).toBeTruthy();
    fireEvent.click(screen.getByTestId('group-request-open-cccc3333cccc3333'));
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ id: 'cccc3333cccc3333', name: 'Оксана Мельник' }));
  });

  it('налаштування без стіни чипів: пауза — у «Дозволи учасників»; каналу її немає', async () => {
    vi.spyOn(groupLinksApi, 'get').mockResolvedValue({ may: true, link: '', ready: true });
    vi.spyOn(groupRequestsApi, 'list').mockResolvedValue({ may: true, ask: false, requests: [] });
    const { unmount } = render(<GroupSettingsPanel chat={chat()} />);
    expect(screen.queryByTestId('slow-30')).toBeNull();
    fireEvent.click(screen.getByTestId('group-settings-perms'));
    expect(screen.getByText('Пауза між повідомленнями')).toBeTruthy();
    expect(screen.getByTestId('slow-0').getAttribute('aria-checked')).toBe('true');
    unmount();
    const ch = { ...chat(), groupSettings: { ...chat().groupSettings!, kind: 'channel' } } as Chat;
    render(<GroupSettingsPanel chat={ch} />);
    expect(screen.queryByTestId('group-settings-perms')).toBeNull();
    expect(screen.queryByTestId('slow-30')).toBeNull();
  });
});
