/**
 * K5: «Де» в чернетці зустрічі шукає справжні місця біля телефона — заклади
 * Nightpost і місця з мапи — через `GET /messenger/places`, з точкою. Обране
 * місце їде в картку з lat/lon, а не голою назвою.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MeetupDraftSheet } from '../components/messenger/meetup/MeetupDraftSheet';
import { useMessengerStore } from '../stores/messengerStore';
import { openMeetupAsk } from '../services/nativeMeetupDraft';
import { hasVenuePage, isPinVenue, placeVenue } from '../types/meetup';
import { AT, ME, OLENA, installBridge, removeBridge, type Answer, type Call } from './meetupFixtures';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const contact = (id: string, name: string) => ({
  id, peer_node_id: id, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '', verified: false,
});

const PLACES = {
  places: [
    { id: null, name: 'Kavárna Nová', address: 'Masarykovo nám. 1', lat: 49.8352, lon: 18.2831, kind: 'cafe', rating: null, menu_version: null, distance_m: 30 },
    { id: '0123456789abcdef', name: 'Cafe X', address: '1 St', lat: 49.836, lon: 18.283, kind: 'cafe', rating: { mean: 4.5, count: 2 }, menu_version: null, distance_m: 111 },
  ],
};

let calls: Call[];
let places: (c: Call) => Answer;
let prev: ReturnType<typeof useMessengerStore.getState>;
const flush = () => act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
const placeCalls = () => calls.filter((c) => c.m === 'GET' && c.p.startsWith('/messenger/places'));

function fixAt(lat: number | null, lon = 0) {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: (ok: PositionCallback, fail?: PositionErrorCallback) =>
        lat === null
          ? fail?.({ code: 1, message: 'denied' } as GeolocationPositionError)
          : ok({ coords: { latitude: lat, longitude: lon, accuracy: 20 }, timestamp: Date.now() } as GeolocationPosition),
      watchPosition: () => 0,
      clearWatch: () => {},
    },
  });
}

const parked = { picked: [OLENA], at: AT, query: '', intoHere: true, chatId: 'grp' };
const renderDraft = () => render(<MeetupDraftSheet open onClose={() => {}} seed={{ parked, chatId: 'grp' }} />);
const typeQuery = (q: string) => fireEvent.change(screen.getByTestId('draft-venue-query'), { target: { value: q } });

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 4 * 3600_000));
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [{ id: 'grp', type: 'group', title: 'Friday', messages: [], unreadCount: 0 }] as never,
    activeChatId: 'grp',
  });
  places = () => ({ data: PLACES });
  calls = installBridge((c) => {
    if (c.p === '/messenger/contacts') return { data: [contact(OLENA, 'Олена')] };
    if (c.p.startsWith('/messenger/where/')) return { status: 404 };
    if (c.p === '/messenger/venues/near') return { data: { venues: [] } };
    if (c.p.startsWith('/messenger/places')) return places(c);
    return undefined;
  });
  fixAt(49.83514, 18.28286);
});

afterEach(() => {
  cleanup();
  removeBridge();
  openMeetupAsk(null);
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

describe('placeVenue — місце з мапи з точкою', () => {
  it('має координати, це мітка без сторінки закладу, адреса лишається', () => {
    const v = placeVenue({ lat: 49.8352, lon: 18.2831, name: ' Kavárna Nová ', address: 'Masarykovo nám. 1' });
    expect(v.lat).toBe(49.8352);
    expect(v.lon).toBe(18.2831);
    expect(v.name).toBe('Kavárna Nová');
    expect(v.address).toBe('Masarykovo nám. 1');
    expect(isPinVenue(v)).toBe(true);
    expect(hasVenuePage(v)).toBe(false);
  });
});

describe('«Де» шукає місця біля мене', () => {
  it('«cafe» питає двері місць з моєю точкою й показує і заклад, і місце з мапи', async () => {
    renderDraft();
    await flush();
    typeQuery('cafe');
    await waitFor(() => expect(placeCalls().some((c) => c.p.includes('q=cafe'))).toBe(true), { timeout: 2000 });
    const call = placeCalls().filter((c) => c.p.includes('q=cafe')).pop()!;
    const q = new URLSearchParams(call.p.split('?')[1]);
    expect(q.get('q')).toBe('cafe');
    expect(q.get('lat')).toBe('49.835');
    expect(q.get('lon')).toBe('18.283');
    expect(await screen.findByText('Kavárna Nová', {}, { timeout: 2000 })).toBeTruthy();
    expect(screen.getByText('Cafe X')).toBeTruthy();
  });

  it('обране місце з мапи їде в картку з точкою', async () => {
    renderDraft();
    await flush();
    typeQuery('cafe');
    fireEvent.click(await screen.findByText('Kavárna Nová', {}, { timeout: 2000 }));
    await flush();
    expect(screen.getByTestId('draft-venue').textContent).toContain('Kavárna Nová');
    const send = vi.spyOn(useMessengerStore.getState(), 'sendCard');
    useMessengerStore.setState({ sendCard: send as never });
    send.mockResolvedValue(null as never);
    fireEvent.click(screen.getByText(/Запросити|Invite/));
    await flush();
    const body = send.mock.calls[0]?.[1] as { venue: { lat: number | null; lon: number | null; name: string } };
    expect(body.venue).toMatchObject({ lat: 49.8352, lon: 18.2831, name: 'Kavárna Nová' });
  });

  it('без моєї точки запит іде без координат', async () => {
    fixAt(null);
    renderDraft();
    await flush();
    typeQuery('cafe');
    await waitFor(() => expect(placeCalls().some((c) => c.p.includes('q=cafe'))).toBe(true), { timeout: 2000 });
    const call = placeCalls().filter((c) => c.p.includes('q=cafe')).pop()!;
    expect(call.p).not.toMatch(/[?&](lat|lon)=/);
  });

  it('двері відмовили (немає точки) — чесна відмова, вписана назва лишається', async () => {
    places = () => ({ status: 409, code: 'no_fix', detail: 'no fix' });
    Object.assign((globalThis as unknown as { PhantomBridge: object }).PhantomBridge, { navigate: vi.fn(() => true) });
    renderDraft();
    await flush();
    typeQuery('cafe');
    await waitFor(() => expect(placeCalls().length).toBeGreaterThan(0), { timeout: 2000 });
    await flush();
    expect(screen.getByTestId('draft-venues-refused')).toBeTruthy();
  });
});
