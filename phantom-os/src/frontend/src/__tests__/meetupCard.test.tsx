/**
 * Картка зустрічі в стрічці (W2): хто іде, хто коли буде, «Лишити», «Я на місці».
 *
 * Стан кожної людини — лише з позначок `card_marks`, які згорнув міст; дотик
 * шле позначку `POST /messenger/messages/{id}/marks {value, on}` і нічого не
 * малює «собі» без мосту.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { en } from '../i18n/messages/parts/l4';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { MessageEmbeds } from '../components/messenger/feed/MessageEmbeds';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { etaValue } from '../services/meetup/marks';
import { kindLine } from '../components/messenger/chatListFormat';
import type { Message } from '../types/messenger';
import { AT, ME, MEETUP, OLENA, PETRO, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];
let markAnswer: (c: Call) => { data: unknown } | { status: number; code?: string } | undefined;

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

/** Картка, що читає свій лист зі стору — як у стрічці. */
const Live: React.FC<{ id: string }> = ({ id }) => {
  const m = useMessengerStore((s) => s.chats[0]?.messages.find((x) => x.id === id));
  return m ? <MeetupCard msg={m} meId={ME} /> : null;
};

/** Картка, розгорнута дотиком по рядку людей: видно кожного і всі дії. */
const show = () => {
  render(<Live id="row-mu" />);
  const people = screen.queryByTestId('meetup-people');
  if (people) fireEvent.click(people);
};

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 2 * 3600_000));
  markAnswer = () => undefined;
  calls = installBridge((c) => markAnswer(c));
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

const marks = {
  'rsvp:yes': [OLENA, ME],
  [etaValue(new Date(2026, 9, 9, 19, 10).getTime())]: [OLENA],
  'rsvp:no': [PETRO],
};

describe('картка зустрічі читає позначки', () => {
  it('«Олена · 19:10», Петро не йде, рахунок тих, хто іде', () => {
    seed(meetupMsg({ cardMarks: marks }));
    show();
    const card = screen.getByTestId('card-meetup');
    expect(within(card).getByTestId('card-title').textContent).toBe('Cafe X');
    expect(within(card).getByTestId('card-status').textContent).toContain('19:00');
    expect(within(card).getByTestId('meetup-going').textContent).toContain('2 з 3 ідуть');
    expect(screen.getByTestId(`meetup-person-${OLENA}`).textContent).toContain('Олена · 19:10');
    expect(screen.getByTestId(`meetup-person-${PETRO}`).textContent).toContain('не йде');
    expect(screen.getByTestId(`meetup-person-${ME}`).textContent).toContain('Ви');
  });

  it('без позначок — ніхто ще не відповів, а не «усі ідуть»', () => {
    seed(meetupMsg());
    show();
    expect(screen.getByTestId('meetup-going').textContent).toContain('0 з 3 ідуть');
    expect(screen.getByTestId(`meetup-person-${OLENA}`).textContent).toContain('ще без відповіді');
  });

  it('оцінка закладу — з картки; без оцінок — чесне «ще ніхто не оцінив»', () => {
    seed(meetupMsg({ meetupData: { ...MEETUP, venue: { ...MEETUP.venue, rating: null } } }));
    show();
    expect(screen.getByTestId('meetup-venue').textContent).toContain('ще ніхто не оцінив');
    cleanup();
    seed(meetupMsg());
    show();
    expect(screen.getByTestId('meetup-venue').textContent).toContain('4.5 · 2 оцінки');
  });

  it('криве тіло — «не вдалося прочитати», а не напівкартка', () => {
    seed(meetupMsg({ meetupData: { v: 1, id: 'x' } as never }));
    show();
    expect(screen.getByTestId('card-unreadable').textContent).toContain('Не вдалося прочитати');
  });
});

describe('дотики шлють позначки в міст', () => {
  it('«Іду» після «Не йду»: спершу знімає старе, потім ставить нове', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:no': [ME] } }));
    show();
    fireEvent.click(screen.getByTestId('meetup-rsvp-yes'));
    await flush();
    const sent = calls.filter((c) => c.p === '/messenger/messages/row-mu/marks');
    expect(sent.map((c) => [c.m, c.b])).toEqual([
      ['POST', { value: 'rsvp:no', on: false }],
      ['POST', { value: 'rsvp:yes', on: true }],
    ]);
    expect(screen.getByTestId(`meetup-person-${ME}`).textContent).toContain('іде');
  });

  it('міст без шляху позначок (старий APK) — чесна відмова, стан не міняється', async () => {
    markAnswer = (c) =>
      c.p.endsWith('/marks') ? { status: 404, code: 'not_found', detail: `немає такого шляху: POST ${c.p}` } : undefined;
    seed(meetupMsg());
    show();
    fireEvent.click(screen.getByTestId('meetup-rsvp-yes'));
    await flush();
    expect(screen.getByTestId(`meetup-person-${ME}`).textContent).not.toContain('іде');
    expect(screen.getByTestId('meetup-error').textContent).toContain('ще не вміє');
  });

  it('час прибуття з вибору — позначка eta:<хвилини>', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    show();
    fireEvent.click(screen.getByTestId('meetup-eta'));
    fireEvent.click(screen.getByTestId('meet-share-eta'));
    fireEvent.click(screen.getByTestId('eta-option-10'));
    await flush();
    const sent = calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b);
    expect(sent).toEqual([{ value: etaValue(AT + 10 * 60_000), on: true }]);
    expect(screen.getByTestId(`meetup-person-${ME}`).textContent).toContain('19:10');
  });

  it('після «Іду» пропонує одне нагадування за 30 хв — у будильник телефона', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    show();
    fireEvent.click(screen.getByTestId('meetup-remind'));
    await flush();
    const r = calls.find((c) => c.p === '/messenger/reminders');
    expect(r?.b.at_ms).toBe(AT - 30 * 60_000);
    expect(r?.b.text).toContain('Cafe X');
  });
});

describe('тимчасова група: «Лишити»', () => {
  it('показує, коли розмова сховається, і «Лишити» ставить keep:1', async () => {
    seed(meetupMsg());
    show();
    expect(screen.getByTestId('meetup-expiry').textContent).toContain('Сховається');
    expect(screen.getByTestId('meetup-expiry').textContent).not.toContain('Розмова');
    expect(screen.getByTestId('meetup-expiry').querySelector('[data-meetup-expiry-line]')?.className).toContain('truncate');
    fireEvent.click(screen.getByTestId('meetup-keep'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([{ value: 'keep:1', on: true }]);
    expect(screen.getByTestId('meetup-expiry').textContent).toContain('Лишили');
  });

  it('зустріч у звичайній групі (expiresAt null) рядка строку не має', () => {
    seed(meetupMsg({ meetupData: { ...MEETUP, expiresAt: null } }));
    show();
    expect(screen.queryByTestId('meetup-expiry')).toBeNull();
  });
});

describe('«Я на місці» — лише зі згоди', () => {
  it('до часу зустрічі за 2 год кнопки немає', () => {
    vi.setSystemTime(new Date(AT - 3 * 3600_000));
    seed(meetupMsg());
    show();
    expect(screen.queryByTestId('meetup-here')).toBeNull();
  });

  it('«Лише позначка» — нічого не йде на сервер балів', async () => {
    vi.setSystemTime(new Date(AT + 5 * 60_000));
    seed(meetupMsg());
    show();
    fireEvent.click(screen.getByTestId('meetup-here'));
    expect(screen.getByTestId('here-consent').textContent).toContain('що ви були в цьому закладі');
    expect(screen.getByTestId('here-consent').textContent).not.toMatch(/клітинк|сервер/);
    fireEvent.click(screen.getByTestId('here-mark-only'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([{ value: 'here:1', on: true }]);
    expect(calls.some((c) => c.p.startsWith('/messenger/points/'))).toBe(false);
  });

  it('«Так, зарахувати» — позначка і заявка з id зустрічі та закладу', async () => {
    vi.setSystemTime(new Date(AT + 5 * 60_000));
    seed(meetupMsg());
    show();
    fireEvent.click(screen.getByTestId('meetup-here'));
    fireEvent.click(screen.getByTestId('here-consent-yes'));
    await flush();
    const claim = calls.find((c) => c.p === '/messenger/points/checkin');
    expect(claim?.m).toBe('POST');
    expect(claim?.b).toEqual({ meetup_id: 'mu-1', venue_id: 'cafe-x', at_ms: AT });
  });

  it('заклад не зарахував — «Я на місці» лишається, щоб спробувати ще', async () => {
    vi.setSystemTime(new Date(AT + 5 * 60_000));
    markAnswer = (c) => (c.p === '/messenger/points/checkin' ? { status: 409, code: 'not_here' } : undefined);
    seed(meetupMsg());
    show();
    fireEvent.click(screen.getByTestId('meetup-here'));
    fireEvent.click(screen.getByTestId('here-consent-yes'));
    await flush();
    expect(calls.some((c) => c.p === '/messenger/points/checkin')).toBe(true);
    expect(calls.filter((c) => c.p.endsWith('/marks'))).toEqual([]);
    expect(screen.getByTestId('meetup-here')).toBeTruthy();
  });
});

describe('досяжність: рядок мосту → стрічка → картка', () => {
  it('card_marks з рядка лягають у лист, і стрічка малює картку зустрічі', () => {
    const row = {
      id: 'row-mu',
      conversation_id: 'grp',
      client_id: 'c1',
      author_id: OLENA,
      author_name: 'Олена',
      kind: 'text',
      body: 'x',
      sent_at: new Date(AT - 3600_000).toISOString(),
      card_marks: { 'rsvp:yes': [OLENA], ['x'.repeat(80)]: [OLENA], 'keep:1': [3] },
    } as unknown as NodeMessage;
    const m = messageFromNode(row, ME);
    expect(m.cardMarks).toEqual({ 'rsvp:yes': [OLENA] });

    seed(meetupMsg({ cardMarks: m.cardMarks }));
    const rows = buildFeedRows(useMessengerStore.getState().chats[0].messages, ME);
    expect(rows[0].cardView).toBe(true);
    render(
      <FeedActionsContext.Provider value={{} as FeedActions}>
        <MessageEmbeds row={rows[0]} currentUserId={ME} currentUserName="Я" isActiveSearchMatch={false} copied={false} />
      </FeedActionsContext.Provider>,
    );
    fireEvent.click(screen.getByTestId('meetup-people'));
    expect(screen.getByTestId(`meetup-person-${OLENA}`).textContent).toContain('іде');
  });
});

describe('рядок списку розмов', () => {
  it('остання картка — словом «Зустріч» / «Рахунок», а не порожньо', () => {
    expect(kindLine('widget:meetup')).toBe('Зустріч');
    expect(kindLine('widget:bill')).toBe('Рахунок');
  });
});

describe('«Я на місці»: що відповів сервер балів', () => {
  const claimHere = async (answer: unknown) => {
    vi.setSystemTime(new Date(AT + 5 * 60_000));
    markAnswer = (c) => (c.p === '/messenger/points/checkin' ? { data: answer } : undefined);
    seed(meetupMsg());
    show();
    fireEvent.click(screen.getByTestId('meetup-here'));
    fireEvent.click(screen.getByTestId('here-consent-yes'));
    await flush();
    return screen.getByTestId('meetup-note').textContent ?? '';
  };

  it('другий на місці — зустріч зараховано, а не «чекаємо ще когось»', async () => {
    const note = await claimHere({ checked_in: true, awarded: [{ kind: 'meetup.happened' }], waiting_for: 0 });
    expect(note).toContain('Зустріч зараховано');
    expect(note).not.toContain('ще хтось');
  });

  it('перший на місці — сервер чекає ще одного', async () => {
    const note = await claimHere({ checked_in: true, awarded: [], waiting_for: 1 });
    expect(note).toContain('коли на місці відмітиться ще хтось');
  });

  it('зустріч уже зарахована, нових балів немає — так і сказано', async () => {
    const note = await claimHere({ checked_in: true, awarded: [], waiting_for: 0 });
    expect(note).toContain('нових балів');
    expect(note).not.toContain('ще хтось');
  });
});

describe('«Я на місці» — лише у вікні сервера (±2 год)', () => {
  it('через 3 год після початку кнопки вже немає: сервер відмовив би', () => {
    vi.setSystemTime(new Date(AT + 3 * 3600_000));
    seed(meetupMsg());
    show();
    expect(screen.queryByTestId('meetup-here')).toBeNull();
  });

  it('за 1 год 50 хв після початку — ще є', () => {
    vi.setSystemTime(new Date(AT + 110 * 60_000));
    seed(meetupMsg());
    show();
    expect(screen.getByTestId('meetup-here')).toBeTruthy();
  });
});

describe('нагадування — одне на зустріч', () => {
  it('після дотику кнопки немає, видно «Нагадаю о 18:30»; повторне відкриття не ставить другий будильник', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    show();
    const chip = screen.getByTestId('meetup-remind');
    fireEvent.click(chip);
    fireEvent.click(chip);
    await flush();
    expect(calls.filter((c) => c.p === '/messenger/reminders')).toHaveLength(1);
    expect(screen.queryByTestId('meetup-remind')).toBeNull();
    expect(screen.getByTestId('meetup-reminded').textContent).toContain('Нагадаю о 18:30');

    cleanup();
    show();
    expect(screen.queryByTestId('meetup-remind')).toBeNull();
    expect(screen.getByTestId('meetup-reminded').textContent).toContain('Нагадаю о 18:30');
    expect(calls.filter((c) => c.p === '/messenger/reminders')).toHaveLength(1);
  });
});

describe('згода на бали — людськими словами', () => {
  it('англійською — без «cell» і «server»', () => {
    expect(en['meetupCard.consent']).toMatch(/were at this place/);
    expect(en['meetupCard.consent']).not.toMatch(/cell|server/i);
  });
});
