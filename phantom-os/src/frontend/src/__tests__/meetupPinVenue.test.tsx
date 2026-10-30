/**
 * K5: «Зустрітися тут» із закладу Nightpost на мапі несе id закладу
 * (`{pin:{lat,lon,label,venue}}`). Чернетка бере сторінку закладу — картка
 * їде із закладом (id, версія меню), а не з голою міткою. Без сторінки — та
 * сама мітка з точкою, як і раніше.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MeetupDraftSheet } from '../components/messenger/meetup/MeetupDraftSheet';
import { useMessengerStore } from '../stores/messengerStore';
import { installNativeMeetupDraft, openMeetupAsk, parkMeetupDraft, useMeetupAsk } from '../services/nativeMeetupDraft';
import { AT, ME, OLENA, installBridge, removeBridge, type Answer, type Call } from './meetupFixtures';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const contact = (id: string, name: string) => ({
  id, peer_node_id: id, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '', verified: false,
});
const VID = '0123456789abcdef';
const PAGE = {
  id: VID,
  name: 'Cafe X',
  location: { address: '1 St', lat: 49.836, lon: 18.283 },
  menu_version: 'aaaaaaaaaaaaaaaa',
  rating: { mean: 4.5, count: 2, sum: 9 },
  page: { blocks: [] },
};

let calls: Call[];
let page: () => Answer;
let off: () => void;
let prev: ReturnType<typeof useMessengerStore.getState>;
const flush = () => act(async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); });

const Host: React.FC = () => {
  const { ask, n } = useMeetupAsk();
  return ask?.kind === 'meetup' ? <MeetupDraftSheet key={n} open onClose={() => {}} seed={ask} /> : null;
};
const openFromMap = (pin: Record<string, unknown>) =>
  act(() => {
    (window as unknown as { __phantomOpenMeetupDraft: (j: string) => boolean }).__phantomOpenMeetupDraft(JSON.stringify({ pin }));
  });

async function invitedVenue() {
  const send = vi.spyOn(useMessengerStore.getState(), 'sendCard');
  useMessengerStore.setState({ sendCard: send as never });
  send.mockResolvedValue(null as never);
  fireEvent.click(screen.getByText(/Запросити|Invite/));
  await flush();
  return (send.mock.calls[0]?.[1] as { venue: { id: string; lat: number; lon: number; name: string; menuVersion: unknown } }).venue;
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 4 * 3600_000));
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [{ id: 'grp', type: 'group', title: 'Friday', messages: [], unreadCount: 0 }] as never,
    activeChatId: 'grp',
  });
  page = () => ({ data: PAGE });
  calls = installBridge((c) => {
    if (c.p === '/messenger/contacts') return { data: [contact(OLENA, 'Олена')] };
    if (c.p.startsWith('/messenger/where/')) return { status: 404 };
    if (c.p === '/messenger/venues/near') return { data: { venues: [] } };
    if (c.p === `/messenger/venues/${VID}`) return page();
    return undefined;
  });
  off = installNativeMeetupDraft();
  parkMeetupDraft({ picked: [OLENA], at: AT, query: '', intoHere: true, chatId: 'grp' });
});

afterEach(() => {
  off();
  cleanup();
  removeBridge();
  openMeetupAsk(null);
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

describe('заклад із мапи доходить до картки зустрічі', () => {
  it('мітка з id закладу — чернетка бере сторінку, картка їде із закладом', async () => {
    render(<Host />);
    openFromMap({ lat: 49.836, lon: 18.283, label: 'Cafe X', venue: VID });
    await flush();
    expect(calls.some((c) => c.m === 'GET' && c.p === `/messenger/venues/${VID}`)).toBe(true);
    expect(screen.getByTestId('draft-venue').textContent).toContain('Cafe X');
    const venue = await invitedVenue();
    expect(venue).toMatchObject({ id: VID, lat: 49.836, lon: 18.283, name: 'Cafe X', menuVersion: 'aaaaaaaaaaaaaaaa' });
  });

  it('сторінка не прийшла — лишається мітка з точкою', async () => {
    page = () => ({ status: 503, code: 'not_enabled' });
    render(<Host />);
    openFromMap({ lat: 49.836, lon: 18.283, label: 'Cafe X', venue: VID });
    await flush();
    const venue = await invitedVenue();
    expect(venue.id.startsWith('pin:')).toBe(true);
    expect(venue).toMatchObject({ lat: 49.836, lon: 18.283, name: 'Cafe X' });
  });

  it('id не з 16 hex — сторінку не питаємо', async () => {
    render(<Host />);
    openFromMap({ lat: 49.836, lon: 18.283, label: 'Cafe X', venue: '../points' });
    await flush();
    expect(calls.some((c) => c.p.startsWith('/messenger/venues/') && c.p !== '/messenger/venues/near')).toBe(false);
    expect(screen.getByTestId('draft-venue').textContent).toContain('Cafe X');
  });
});
