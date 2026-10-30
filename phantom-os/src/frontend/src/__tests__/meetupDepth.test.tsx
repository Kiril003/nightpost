/**
 * Зустріч W4 (збірка 19): «може», лік на картці, хто вже на місці, мітка на мапі.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, PETRO, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

const flush = () =>
  act(async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  });

function seed(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
  });
}

const Live: React.FC<{ id: string }> = ({ id }) => {
  const m = useMessengerStore((s) => s.chats[0]?.messages.find((x) => x.id === id));
  return m ? <MeetupCard msg={m} meId={ME} /> : null;
};

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 3 * 3600_000));
  calls = installBridge(() => undefined);
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('«Може» і лік на картці', () => {
  it('статус каже, скільки іде і скільки може; рядок людини — «може»', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA], 'rsvp:maybe': [PETRO] } }));
    render(<Live id="row-mu" />);
    expect(screen.getByTestId('meetup-going').textContent).toContain('1 з 3 іде · 1 може');
    fireEvent.click(screen.getByTestId('meetup-people'));
    expect(screen.getByTestId(`meetup-person-${PETRO}`).textContent).toContain('може');
  });

  it('«Може» без відповіді — є одразу; дотик знімає «Іду» і ставить rsvp:maybe', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live id="row-mu" />);
    fireEvent.click(screen.getByTestId('meetup-people'));
    fireEvent.click(screen.getByTestId('meetup-rsvp-maybe'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([
      { value: 'rsvp:yes', on: false },
      { value: 'rsvp:maybe', on: true },
    ]);
  });

  it('згорнута картка без моєї відповіді має три відповіді', () => {
    seed(meetupMsg());
    render(<Live id="row-mu" />);
    for (const id of ['meetup-rsvp-yes', 'meetup-rsvp-maybe', 'meetup-rsvp-no']) expect(screen.getByTestId(id)).toBeTruthy();
  });

  it('хто вже на місці — видно в статусі всім, без розгортання', () => {
    vi.setSystemTime(new Date(AT + 10 * 60_000));
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA, PETRO], 'here:1': [OLENA] } }));
    render(<Live id="row-mu" />);
    expect(screen.getByTestId('meetup-going').textContent).toContain('1 на місці');
  });
});
