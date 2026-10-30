import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const push = vi.fn((_to: string, _pass: unknown) => Promise.resolve({ sent: true }));
let inbox: unknown[] = [];
vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      staffPush: (to: string, pass: unknown) => push(to, pass),
      staffInbox: () => Promise.resolve({ passes: inbox }),
    },
  };
});

import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { passFor, syncStaffNow, resetStaffSync } from '../services/staffPass';
import { useStaffInbox } from '../stores/staffInboxStore';
import { StaffWork } from '../components/messenger/StaffWork';
import type { Chat, Message } from '../types/messenger';

const msg = (id: string, senderId: string, text: string, type = 'text'): Message =>
  ({ id, senderId, senderName: '', senderAvatar: '', timestamp: '2026-10-29T10:00:00Z', type, text }) as unknown as Message;

const dm = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], ...extra }) as unknown as Chat;

const olenaChat = dm('peer_olena', 'Олена', { handle: 'olena_k', peerNodeId: 'node_olena', circle: 'work' });
const client = dm('peer_client', 'Марко', {
  peerNodeId: 'node_marko',
  handle: 'marko',
  circle: 'friends',
  messages: [msg('m1', 'node_marko', 'Чи є столик на 19:00?'), msg('m2', 'me', 'Так, бронюю'), msg('m3', 'node_marko', '', 'image')],
});
const other = dm('peer_other', 'Сестра', { peerNodeId: 'node_sis', messages: [msg('s1', 'node_sis', 'Привіт')] });
const phoneTitle = dm('peer_phone', '+380 67 123 45 67', { peerNodeId: 'node_ph', messages: [msg('p1', 'node_ph', 'Добрий день')] });

let biz = '';
const chats = [olenaChat, client, other, phoneTitle];
const source = { getState: () => ({ chats, hydrated: true, currentUser: { id: 'me' } }) };

beforeEach(() => {
  localStorage.clear();
  push.mockClear();
  resetStaffSync();
  useProfiles.setState(loadProfiles());
  biz = useProfiles.getState().addFromTemplate('business').id;
  useProfiles.getState().update(biz, { name: 'Зерно' });
  useProfiles.getState().addStaff(biz, 'olena_k', 'staff');
  useChatProfiles.setState({ map: { peer_client: biz, peer_phone: biz, peer_olena: biz } });
});

afterEach(() => cleanup());

describe('S8: пропуск працівника (D7)', () => {
  it('несе лише доручені розмови бізнесу: імʼя клієнта і текст, без вузла, ніка, кола й номера', () => {
    const s = useProfiles.getState();
    s.assignThread(biz, 'peer_client', 'olena_k');
    s.assignThread(biz, 'peer_other', 'olena_k');
    s.assignThread(biz, 'peer_phone', 'olena_k');
    const p = useProfiles.getState().profiles.find((x) => x.id === biz)!;
    const pass = passFor(p, p.staff[0], chats, useChatProfiles.getState().map, useProfiles.getState().profiles, 'me', 7);
    expect(pass.business).toBe('Зерно');
    expect(pass.rev).toBe(7);
    expect(pass.threads.map((t) => t.id)).toEqual(['peer_client', 'peer_phone']);
    expect(pass.threads[0]).toEqual({
      id: 'peer_client',
      client: 'Марко',
      lines: [
        { mine: false, text: 'Чи є столик на 19:00?', at: Date.parse('2026-10-29T10:00:00Z') },
        { mine: true, text: 'Так, бронюю', at: Date.parse('2026-10-29T10:00:00Z') },
      ],
    });
    expect(pass.threads[1].client).toBe('');
    const wire = JSON.stringify(pass);
    for (const leak of ['node_marko', 'marko"', 'friends', '+380', 'Сестра', 'node_olena']) expect(wire).not.toContain(leak);
  });

  it('одна розмова — один працівник', () => {
    useProfiles.getState().addStaff(biz, 'ivan_p', 'staff');
    useProfiles.getState().assignThread(biz, 'peer_client', 'olena_k');
    useProfiles.getState().assignThread(biz, 'peer_client', 'ivan_p');
    const staff = useProfiles.getState().profiles.find((x) => x.id === biz)!.staff;
    expect(staff.find((m) => m.handle === 'olena_k')!.threads).toEqual([]);
    expect(staff.find((m) => m.handle === 'ivan_p')!.threads).toEqual(['peer_client']);
  });

  it('шле пропуск на вузол працівника, не повторює без змін, а відкликання шле порожній', async () => {
    useProfiles.getState().assignThread(biz, 'peer_client', 'olena_k');
    await syncStaffNow(source);
    expect(push).toHaveBeenCalledTimes(1);
    expect(push.mock.calls[0][0]).toBe('node_olena');
    expect((push.mock.calls[0][1] as { threads: unknown[] }).threads).toHaveLength(1);

    await syncStaffNow(source);
    expect(push).toHaveBeenCalledTimes(1);

    useProfiles.getState().revokeStaff(biz, 'olena_k');
    await syncStaffNow(source);
    expect(push).toHaveBeenCalledTimes(2);
    expect(push.mock.calls[1][0]).toBe('node_olena');
    expect(push.mock.calls[1][1]).toMatchObject({ business: 'Зерно', threads: [] });

    resetStaffSync({ keepSent: true });
    await syncStaffNow(source);
    expect(push).toHaveBeenCalledTimes(2);
  });

  it('відкликання доходить і після перезапуску застосунку', async () => {
    useProfiles.getState().assignThread(biz, 'peer_client', 'olena_k');
    await syncStaffNow(source);
    resetStaffSync({ keepSent: true });
    useProfiles.getState().revokeStaff(biz, 'olena_k');
    await syncStaffNow(source);
    expect(push).toHaveBeenLastCalledWith('node_olena', expect.objectContaining({ threads: [] }));
    expect(localStorage.getItem('np.staffsent.v1') ?? '').not.toContain('столик');
  });

  it('працівника без звіреного ніка в контактах не шукає навмання', async () => {
    useProfiles.getState().addStaff(biz, 'nobody_x', 'staff');
    useProfiles.getState().assignThread(biz, 'peer_client', 'nobody_x');
    await syncStaffNow(source);
    expect(push).not.toHaveBeenCalled();
  });
});

describe('S8: телефон працівника', () => {
  const pass = { from: 'node_owner', business: 'Зерно', rev: 3, threads: [{ id: 't1', client: 'Марко', lines: [{ mine: false, text: 'Чи є столик?', at: 1 }] }] };

  beforeEach(() => useStaffInbox.setState({ passes: [] }));

  it('бачить «Зерно · 1 розмова», а після відкликання рядок зникає', async () => {
    inbox = [pass];
    render(<StaffWork />);
    await act(async () => {
      await useStaffInbox.getState().load();
    });
    expect(screen.getByTestId('staff-work-node_owner').textContent).toContain('Зерно');
    expect(screen.getByTestId('staff-work-node_owner').textContent).toContain('1 розмова');
    fireEvent.click(screen.getByTestId('staff-work-node_owner'));
    expect(screen.getByTestId('staff-thread-t1').textContent).toContain('Марко');
    expect(screen.getByTestId('staff-thread-t1').textContent).toContain('Чи є столик?');

    inbox = [];
    await act(async () => {
      await useStaffInbox.getState().load();
    });
    expect(screen.queryByTestId('staff-work-node_owner')).toBeNull();
    expect(screen.queryByTestId('staff-thread-t1')).toBeNull();
  });
});
