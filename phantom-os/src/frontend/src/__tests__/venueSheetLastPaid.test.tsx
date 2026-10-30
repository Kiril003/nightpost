import { describe, it, expect, afterEach, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { VenueSheet } from '../components/messenger/meetup/VenueSheet';
import { useMessengerStore } from '../stores/messengerStore';
import { ME, OLENA, VENUE, BILL, billMsg } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => { prev = useMessengerStore.getState(); });
afterEach(() => useMessengerStore.setState(prev, true));

const setChats = (messages: ReturnType<typeof billMsg>[]) =>
  useMessengerStore.setState({
    currentUser: { ...prev.currentUser, id: ME },
    chats: [{ ...prev.chats[0], id: 'c1', messages }],
  } as never);

describe('VenueSheet last paid line', () => {
  it('shows who paid last time at a venue with a past bill', () => {
    setChats([billMsg({ sentAt: new Date(2026, 9, 14, 21).toISOString(), billData: { ...BILL, payee: { kind: 'peer', id: OLENA } } })]);
    render(<VenueSheet open onClose={() => {}} venueId="cafe-x" snap={VENUE} />);
    expect(screen.getByTestId('venue-last-paid').textContent).toMatch(/Олена/);
  });
  it('renders nothing without a bill', () => {
    setChats([]);
    render(<VenueSheet open onClose={() => {}} venueId="cafe-x" snap={VENUE} />);
    expect(screen.queryByTestId('venue-last-paid')).toBeNull();
  });
});
