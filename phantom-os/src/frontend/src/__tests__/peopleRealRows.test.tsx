import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import * as circlesApi from '../services/circlesApi';
import { messengerApi, type ContactRequest } from '../services/messengerApi';
import { DEFAULT_CIRCLES, useCirclesStore } from '../stores/circlesStore';
import { useRequestsStore } from '../stores/requestsStore';
import { useMessengerStore } from '../stores/messengerStore';
import { PeopleScreen } from '../components/messenger/PeopleScreen';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import type { NodeContact } from '../services/messengerApi';
import type { Chat } from '../types/messenger';

// Власник 30.10: людина — це людина: фото, імʼя 600, @нік або коло, спільне; тап — профіль; ні «Без імені», ні ключа.
const req = (id: string, name: string, dir: 'in' | 'out' = 'in', text = ''): ContactRequest => ({
  id, direction: dir, peer_node_id: `n_${id}`, name, text, created_at: '2026-10-30T10:00:00Z', delivery: 'sent',
});
const FACE = 'data:image/png;base64,iVBORw0KGgo=';

beforeEach(() => {
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(circlesApi, 'listCirclesWithMeta').mockResolvedValue({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useCirclesStore.setState({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useRequestsStore.setState({ incoming: [], outgoing: [], loaded: false, error: false });
  vi.spyOn(useMessengerStore.getState(), 'refreshConversations').mockResolvedValue();
  const known = { id: 'chat_b', type: 'direct', title: 'Макс', avatar: FACE, peerNodeId: 'n_b', handle: 'max', circles: ['friends'], messages: [] } as unknown as Chat;
  const group = { id: 'g1', type: 'group', title: 'Похід', avatar: '', messages: [], members: [{ id: 'n_a', name: 'Олена', handle: '', avatar: '', role: 'member' }] } as unknown as Chat;
  useMessengerStore.setState({ chats: [known, group] });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const open = async (incoming: ContactRequest[], outgoing: ContactRequest[] = []) => {
  vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming, outgoing });
  render(<PeopleScreen onClose={() => {}} />);
  fireEvent.click(await screen.findByTestId('people-requests-row'));
};

describe('Запити — люди як люди', () => {
  it('імʼя 600, фото знайомої людини, @нік і коло, спільні групи', async () => {
    await open([req('a', 'Олена', 'in', 'привіт'), req('b', 'Макс')]);
    const b = await screen.findByTestId('request-person-b');
    expect(within(b).getByText('Макс').className).toContain('font-semibold');
    expect(b.querySelector('img')?.getAttribute('src')).toBe(FACE);
    expect(b.textContent).toContain('@max');
    expect(b.textContent).toContain('Друзі');
    expect(screen.getByTestId('request-person-a').textContent).toContain('Спільні групи · 1');
  });

  it('без імені — «Людина», не «Без імені» і не ключ', async () => {
    await open([req('x', '')], [req('y', 'Без імені', 'out')]);
    const x = await screen.findByTestId('request-person-x');
    expect(x.textContent).toContain('Людина');
    expect(document.body.textContent).not.toContain('Без імені');
    expect(document.body.textContent).not.toContain('n_x');
  });

  it('тап по людині відкриває її профіль', async () => {
    await open([req('b', 'Макс')]);
    fireEvent.click(await screen.findByTestId('request-person-b'));
    await vi.waitFor(() => expect(document.querySelector('[data-peer-scroll]')).toBeTruthy());
  });
});

const contact = (id: string, name: string): NodeContact => ({
  id, peer_node_id: `n_${id}`, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '',
  verified: true, session_ready: true, created_at: '2026-10-30T10:00:00',
});

describe('Нова розмова — люди як люди', () => {
  it('рядок людини: фото з розмови, @нік і коло; без імені — «Людина»', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => JSON.stringify({ ok: true, status: 200, data: [] }) };
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('b', 'Макс'), contact('z', '')]);
    render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={vi.fn()} />);
    const name = await screen.findByText('Макс');
    const row = name.closest('button') as HTMLElement;
    expect(row.querySelector('img')?.getAttribute('src')).toBe(FACE);
    expect(row.querySelector('[data-person-line]')?.textContent).toBe('@max · Друзі');
    expect(screen.getByText('Людина')).toBeTruthy();
    expect(document.body.textContent).not.toContain('Без імені');
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });
});
