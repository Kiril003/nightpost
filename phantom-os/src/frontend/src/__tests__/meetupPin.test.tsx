/**
 * Зустріч W4: місце — міткою на мапі. Чернетка паркується, мапа відкривається
 * для мітки, «Зустрітися тут» повертає точку в ту саму чернетку; точка їде
 * з карткою, а картка відкриває її на мапі.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MeetupDraftSheet } from '../components/messenger/meetup/MeetupDraftSheet';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import { installNativeMeetupDraft, openMeetupAsk, parkMeetupDraft, useMeetupAsk } from '../services/nativeMeetupDraft';
import { hasVenuePage, parseMeetupData, pinVenue, typedVenue } from '../types/meetup';
import { AT, ME, MEETUP, OLENA, VENUE, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';
import { nextStep, pickWhen, stepNow } from './whenPick';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const contact = (id: string, name: string) => ({
  id, peer_node_id: id, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '', verified: false,
});

let calls: Call[];
let navigate: ReturnType<typeof vi.fn>;
let openMap: ReturnType<typeof vi.fn>;
let prev: ReturnType<typeof useMessengerStore.getState>;
const flush = () => act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 4 * 3600_000));
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [], unreadCount: 0 }] as never,
    activeChatId: 'grp',
  });
  calls = installBridge((c) => {
    if (c.p === '/messenger/contacts') return { data: [contact(OLENA, 'Олена')] };
    if (c.p.startsWith('/messenger/where/')) return { status: 404 };
    if (c.p === '/messenger/venues/near') return { status: 503, code: 'not_enabled' };
    return undefined;
  });
  navigate = vi.fn(() => true);
  openMap = vi.fn(() => true);
  Object.assign((globalThis as unknown as { PhantomBridge: object }).PhantomBridge, { navigate, openMap });
});

afterEach(() => {
  cleanup();
  removeBridge();
  openMeetupAsk(null);
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

describe('мітка — справжнє місце з точкою, без сторінки закладу', () => {
  it('картка з міткою читається; точка поза світом — ні', () => {
    const pin = pinVenue(49.83512, 18.28301, 'Біля фонтану');
    expect(parseMeetupData({ ...MEETUP, venue: pin })?.venue).toEqual({ ...pin, wallet: null });
    expect(parseMeetupData({ ...MEETUP, venue: { ...pin, lat: 200 } })).toBeNull();
    expect(hasVenuePage(pin)).toBe(false);
    expect(hasVenuePage(typedVenue('Дім'))).toBe(false);
    expect(hasVenuePage(VENUE)).toBe(true);
  });
});

describe('чернетка → мапа → «Зустрітися тут» → та сама чернетка', () => {
  it('«Мітка на мапі» паркує людей і час і просить мапу в режимі мітки', async () => {
    const onClose = vi.fn();
    render(<MeetupDraftSheet open onClose={onClose} seed={{}} />);
    await flush();
    pickWhen(null, '2026-10-09', '19', '00');
    nextStep();
    fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
    nextStep();
    fireEvent.change(screen.getByTestId('draft-venue-query'), { target: { value: 'Біля фонтану' } });
    nextStep();
    fireEvent.click(screen.getByTestId('draft-venue'));
    expect((screen.getByTestId('draft-venue-query') as HTMLInputElement).value).toBe('Біля фонтану');
    fireEvent.click(screen.getByTestId('draft-pick-map'));
    expect(navigate).toHaveBeenCalledWith('map', JSON.stringify({ pick: 'meetup' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('точка з мапи відкриває чернетку з тими самими людьми й часом і з назвою, яку вписали', async () => {
    const off = installNativeMeetupDraft();
    parkMeetupDraft({ picked: [OLENA], at: AT, query: 'Біля фонтану', intoHere: false, chatId: null });
    const Host: React.FC = () => {
      const { ask, n } = useMeetupAsk();
      return ask?.kind === 'meetup' ? <MeetupDraftSheet key={n} open onClose={() => {}} seed={ask} /> : null;
    };
    render(<Host />);
    act(() => {
      (window as unknown as { __phantomOpenMeetupDraft: (j: string) => boolean }).__phantomOpenMeetupDraft(
        JSON.stringify({ pin: { lat: 49.83512, lon: 18.28301, label: 'Stodolní 1' } }),
      );
    });
    await flush();
    expect(screen.getByTestId('draft-venue').textContent).toContain('Біля фонтану');
    expect(stepNow()).toBe('Крок 4 з 4 · Перевірка');
    expect(screen.getByTestId('draft-row-who').textContent).toContain('Олена');
    expect(screen.getByTestId('draft-at').getAttribute('data-value')).toBe('2026-10-09T19:00');
    off();
  });

  it('без припаркованої чернетки мітка бере підпис адреси з мапи', async () => {
    const off = installNativeMeetupDraft();
    const Host: React.FC = () => {
      const { ask, n } = useMeetupAsk();
      return ask?.kind === 'meetup' ? <MeetupDraftSheet key={n} open onClose={() => {}} seed={ask} /> : null;
    };
    render(<Host />);
    act(() => {
      (window as unknown as { __phantomOpenMeetupDraft: (j: string) => boolean }).__phantomOpenMeetupDraft(
        JSON.stringify({ pin: { lat: 49.8, lon: 18.2, label: 'Stodolní 1' } }),
      );
    });
    await flush();
    expect(screen.getByTestId('draft-venue').textContent).toContain('Stodolní 1');
    off();
  });

  it('«Запросити» з міткою — точка їде в тілі картки', async () => {
    const pin = pinVenue(49.83512, 18.28301, 'Біля фонтану');
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: pin, parked: { picked: [OLENA], at: AT, query: '', intoHere: true, chatId: 'grp' }, chatId: 'grp' }} />);
    await flush();
    const send = vi.spyOn(useMessengerStore.getState(), 'sendCard');
    useMessengerStore.setState({ sendCard: send as never });
    send.mockResolvedValue(null as never);
    fireEvent.click(screen.getByText(/Запросити|Invite/));
    await flush();
    const body = send.mock.calls[0]?.[1] as { venue: { lat: number; lon: number; name: string } };
    expect(body.venue).toMatchObject({ lat: 49.83512, lon: 18.28301, name: 'Біля фонтану' });
  });
});

describe('картка показує місце на мапі', () => {
  const seed = (venue = VENUE) =>
    useMessengerStore.setState({
      chats: [{ id: 'grp', type: 'group', title: 'x', messages: [meetupMsg({ meetupData: { ...MEETUP, venue } })], unreadCount: 0 }] as never,
    });

  it('мітка: «На мапі» відкриває точку з назвою; «Я на місці» — лише позначка, без балів', async () => {
    vi.setSystemTime(new Date(AT));
    const pin = pinVenue(49.83512, 18.28301, 'Біля фонтану');
    seed(pin);
    render(<MeetupCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={ME} />);
    fireEvent.click(screen.getByTestId('meetup-map'));
    expect(openMap).toHaveBeenCalledWith(49.83512, 18.28301, 'Біля фонтану');
    fireEvent.click(screen.getByTestId('meetup-rsvp-yes'));
    await flush();
    expect(screen.queryByTestId('here-consent')).toBeNull();
    expect(calls.some((c) => c.p.includes('/points/'))).toBe(false);
  });

  it('заклад зі сторінкою теж має «На мапі»; вписана назва без точки — ні', () => {
    seed();
    render(<MeetupCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={ME} />);
    fireEvent.click(screen.getByTestId('meetup-map'));
    expect(openMap).toHaveBeenCalledWith(VENUE.lat, VENUE.lon, VENUE.name);
    cleanup();
    seed(typedVenue('Дім'));
    render(<MeetupCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={ME} />);
    expect(screen.queryByTestId('meetup-map')).toBeNull();
  });
});
