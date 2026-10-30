import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { factDiff, rememberFacts, seenFacts } from '../services/factDiff';
import { useMessengerStore } from '../stores/messengerStore';
import { AT, ME, installBridge, meetupMsg, minutesOf, removeBridge } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 3 * 3600_000));
  installBridge(() => undefined);
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X', messages: [], unreadCount: 0 } as never],
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
  });
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

const moved = (to: number) => meetupMsg({ cardMarks: { [`at:${minutesOf(to)}`]: [ME] } });

describe('what changed since I last looked', () => {
  it('pure diff and snapshot', () => {
    expect(factDiff(null, { at: AT })).toBeNull();
    expect(factDiff({ at: AT }, { at: AT })).toBeNull();
    expect(factDiff({ at: AT }, { at: AT + 3600_000 })).toEqual({ atFrom: AT, atTo: AT + 3600_000 });
    rememberFacts('mu-x', { at: AT });
    expect(seenFacts('mu-x')).toEqual({ at: AT });
  });

  it('plan whose time moved: seen at 19:00, moved to 20:00 → one delta line, gone next time', () => {
    const first = render(<MeetupCard msg={meetupMsg()} meId={ME} />);
    expect(screen.queryByTestId('meetup-changed')).toBeNull();
    first.unmount();

    const second = render(<MeetupCard msg={moved(AT + 3600_000)} meId={ME} />);
    expect(screen.getByTestId('meetup-changed').textContent).toBe('Час змінено: 19:00 → 20:00');
    second.unmount();

    render(<MeetupCard msg={moved(AT + 3600_000)} meId={ME} />);
    expect(screen.queryByTestId('meetup-changed')).toBeNull();
  });

  it('a first look at an already moved card shows no invented change', () => {
    render(<MeetupCard msg={moved(AT + 3600_000)} meId={ME} />);
    expect(screen.queryByTestId('meetup-changed')).toBeNull();
  });
});
