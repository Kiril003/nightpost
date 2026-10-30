import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return { ...real, deleteCircle: vi.fn(async () => true) };
});

import { messengerApi } from '../services/messengerApi';
import { useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { peerProfileEdits } from '../components/messenger/peerProfileEdits';
import type { Chat } from '../types/messenger';

const chat = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title: 'Olena', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], ...extra }) as unknown as Chat;
const find = (id: string) => useMessengerStore.getState().chats.find((c) => c.id === id);
const src = (p: string) => readFileSync(resolve(__dirname, '..', p), 'utf8');

beforeEach(() => {
  useCirclesStore.setState({ circles: [], maxCustom: 8 });
});

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] } as never);
});

describe('people-identity S1', () => {
  it('S1-7: verifying a changed key lifts the hold over the composer', () => {
    useMessengerStore.setState({
      chats: [chat('c1', { contactKeyHold: true, contactKeyChanged: true, contactKeyChangedAt: '2026-10-19', contactVerified: false })],
    } as never);
    peerProfileEdits('c1').onVerified(true);
    expect(find('c1')).toMatchObject({ contactVerified: true, contactKeyHold: false, contactKeyChanged: false });
    expect(find('c1')?.contactKeyChangedAt).toBeUndefined();
  });

  it('S1-7: the profile opened from a message gets the same edits as the header', () => {
    expect(src('components/messenger/UserProfileModal.tsx')).toMatch(/\.\.\.\(peerChat \? peerProfileEdits\(peerChat\.id\) : \{\}\)/);
  });

  it('S1-3: a contact is never matched by display name alone', () => {
    expect(src('components/messenger/UserProfileModal.tsx')).not.toMatch(/c\.display_name === targetUser\.name/);
  });

  it('S1-4: "write" from a profile opens the dm with the contact, not an empty one', async () => {
    const spy = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({ id: 'n1', title: 'Olena', kind: 'dm', circle: 'friends' } as never);
    await useMessengerStore.getState().createDirectMessage('Olena', 'friends', '', 'contact-7');
    expect(spy.mock.calls[0][0]).toMatchObject({ contact_id: 'contact-7' });
    expect(src('components/messenger/MessengerRoot.tsx')).toMatch(/createDirectMessage\(target\.name, 'friends', target\.avatar, target\.contactId\)\s*\.catch/);
  });

  it('S1-10: moving a chat to a circle updates its circles list too', async () => {
    useMessengerStore.setState({ chats: [chat('c1', { circle: 'close', circles: ['close'] })] } as never);
    vi.spyOn(messengerApi, 'setCircle').mockResolvedValue({ id: 'c1', circle: 'work' } as never);
    expect(await useMessengerStore.getState().setChatCircle('c1', 'work')).toBe(true);
    expect(find('c1')).toMatchObject({ circle: 'work', circles: ['work'] });
  });

  it('S1-12: deleting a custom circle takes it off every chat', async () => {
    useCirclesStore.setState({ circles: [{ id: 'u:trip', name: 'Trip' }] as never, maxCustom: 8 });
    useMessengerStore.setState({
      chats: [chat('c1', { circle: 'u:trip' as never, circles: ['u:trip'] }), chat('c2', { circle: 'work', circles: ['work', 'u:trip'] })],
    } as never);
    expect(await useCirclesStore.getState().remove('u:trip')).toBe(true);
    expect(find('c1')).toMatchObject({ circle: 'all', circles: [] });
    expect(find('c2')).toMatchObject({ circle: 'work', circles: ['work'] });
  });
});
