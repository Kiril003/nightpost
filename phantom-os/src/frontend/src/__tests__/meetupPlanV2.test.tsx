/**
 * Зустрічі v2: новий план у чотири кроки (рендери W1–W5) — коли, хто, де, перевірка;
 * кілька часів на голосування, тривалість, кола, місця, +1 — і картка, що їх читає.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';

const wire = vi.hoisted(() => ({ carries: true }));
vi.mock('../services/composerCards', async (orig) => {
  const real = await orig<typeof import('../services/composerCards')>();
  const extra: Record<string, string> = { 'widget:meetup': 'meetupData', 'widget:bill': 'billData' };
  return {
    ...real,
    isCardKind: (k: string) => (k in extra ? wire.carries : real.isCardKind(k)),
    cardFieldOf: (k: string) => (k in extra ? (wire.carries ? extra[k] : undefined) : real.cardFieldOf(k)),
    refuseCard: (k: string, p: string) => (k in extra ? (wire.carries ? null : 'ні') : real.refuseCard(k, p)),
  };
});

import { MeetupDraftSheet } from '../components/messenger/meetup/MeetupDraftSheet';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import { parseMeetupData } from '../types/meetup';
import { readCardMarks } from '../services/meetup/marks';
import { chosenAt } from '../services/meetup/draft';
import type { Message } from '../types/messenger';
import { AT, ME, MEETUP, OLENA, PETRO, VENUE, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';
import { nextStep, pickWhen, stepNow } from './whenPick';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const contact = (id: string, name: string) => ({
  id,
  peer_node_id: id,
  display_name: name,
  peer_address: null,
  safety_number: '',
  safety_number_pretty: '',
  verified: false,
});

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

function answer(c: Call) {
  if (c.p === '/messenger/contacts') return { data: [contact(PETRO, 'Петро'), contact(OLENA, 'Олена')] };
  if (c.p.startsWith('/messenger/where/')) return { status: 404 };
  if (c.p === '/messenger/venues/near') return { data: { venues: [] } };
  if (c.m === 'POST' && c.p === '/messenger/conversations') {
    return { data: { id: 'grp-new', title: c.b.title, kind: 'group', circle: 'friends', created_at: '2026-10-09T15:00:00' } };
  }
  if (c.m === 'POST' && c.p === '/messenger/conversations/grp-new/messages') {
    return { data: { id: 'row-1', conversation_id: 'grp-new', delivery: 'sent', kind: c.b.kind } };
  }
  return undefined;
}

const flush = () =>
  act(async () => {
    for (let i = 0; i < 10; i++) await Promise.resolve();
  });

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 4 * 3600_000));
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [],
    activeChatId: '',
  });
  calls = installBridge(answer);
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

const sentBody = () => JSON.parse(calls.find((c) => c.p === '/messenger/conversations/grp-new/messages')!.b.body);

describe('тіло плану: часи, тривалість, місця, +1', () => {
  it('читається з дроту; криві поля відкинуто, картка лишається', () => {
    const m = parseMeetupData({ ...MEETUP, slots: [AT, AT + 86_400_000], durationMin: 120, spots: 6, plusOne: true });
    expect(m).toMatchObject({ slots: [AT, AT + 86_400_000], durationMin: 120, spots: 6, plusOne: true });
    const bad = parseMeetupData({ ...MEETUP, slots: [AT + 1, AT], durationMin: -5, spots: 1, plusOne: 'yes' });
    expect(bad).not.toBeNull();
    expect(bad!.slots).toBeUndefined();
    expect(bad!.durationMin).toBeUndefined();
    expect(bad!.spots).toBeUndefined();
    expect(bad!.plusOne).toBeUndefined();
  });

  it('з голосуванням час відомий лише після вибору того, хто кличе', () => {
    const m = { ...MEETUP, slots: [AT, AT + 3600_000] };
    expect(chosenAt(m, readCardMarks({ 'slot:1': [OLENA] }).lock)).toBeNull();
    expect(chosenAt(m, readCardMarks({ 'lock:1': [OLENA] }).lock)).toBeNull();
    expect(chosenAt(m, readCardMarks({ 'lock:1': [ME] }).lock)).toBe(AT + 3600_000);
    expect(chosenAt(MEETUP, new Map())).toBe(AT);
  });
});

describe('новий план у чотири кроки: коли → хто → де → перевірка', () => {
  /** Від порожньої чернетки до кроку «Де»: сьогодні ввечері, Олена. */
  const toWhere = () => {
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    nextStep();
    fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
    nextStep();
  };

  it('крок за кроком; «Далі» лише коли крок заповнений; на перевірці одна бурштинова «Запросити»', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{}} />);
    await flush();
    const sheet = screen.getByTestId('meetup-draft');
    expect(stepNow()).toBe('Крок 1 з 4 · Коли');
    const next = () => within(sheet).getByRole('button', { name: /^Далі/ }) as HTMLButtonElement;
    expect(next().disabled).toBe(true);
    expect(within(sheet).getByTestId('when-quick')).toBeTruthy();
    fireEvent.click(within(sheet).getByTestId('when-quick-tonight'));
    expect(within(sheet).getByTestId('draft-at').getAttribute('data-value')).toBe('2026-10-09T21:00');
    nextStep();
    expect(stepNow()).toBe('Крок 2 з 4 · Хто');
    expect(next().disabled).toBe(true);
    fireEvent.click(within(sheet).getByTestId(`draft-person-${OLENA}`));
    expect(next().textContent).toBe('Далі · 1');
    nextStep();
    expect(stepNow()).toBe('Крок 3 з 4 · Де');
    expect(next().disabled).toBe(true);
    fireEvent.change(within(sheet).getByTestId('draft-venue-query'), { target: { value: 'Park' } });
    nextStep();
    expect(stepNow()).toBe('Крок 4 з 4 · Перевірка');
    expect(within(sheet).getByTestId('draft-venue').textContent).toContain('Park');
    expect(within(sheet).getByTestId('draft-row-who').textContent).toContain('Олена');
    const amber = [...sheet.ownerDocument.querySelectorAll('.bg-accent')];
    expect(amber.map((x) => x.textContent)).toEqual(['Запросити']);
    // Рядок перевірки веде назад у свій крок.
    fireEvent.click(within(sheet).getByTestId('draft-row-who'));
    expect(stepNow()).toBe('Крок 2 з 4 · Хто');
  });

  it('«Де вже зустрічались» — місця з попередніх карток цієї групи; дотик веде на перевірку', async () => {
    const old = meetupMsg({ id: 'old', meetupData: { ...MEETUP, venue: { ...VENUE, id: 'mill', name: 'Mill Bistro' } } });
    useMessengerStore.setState({ chats: [{ id: 'grp', type: 'group', title: 'Друзі', messages: [old], unreadCount: 0 } as never] });
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ chatId: 'grp' }} />);
    await flush();
    toWhere();
    fireEvent.click(screen.getByTestId('draft-list-past'));
    expect(screen.getByTestId('draft-list-past').textContent).toBe('Місця групи');
    fireEvent.click(screen.getByTestId('draft-venue-mill'));
    expect(stepNow()).toBe('Крок 4 з 4 · Перевірка');
    expect(screen.getByTestId('draft-venue').textContent).toContain('Mill Bistro');
  });

  it('два часи на голосування, 3 год, до 4 місць, +1 — усе їде в тілі картки', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: VENUE }} />);
    await flush();
    pickWhen(null, '2026-10-09', '19', '00');
    pickWhen(screen.getByTestId('draft-add-slot'), '2026-10-10', '19', '00');
    expect(screen.getAllByTestId(/^draft-slot-\d$/)).toHaveLength(2);
    expect(screen.getByText('Кожен голосує за зручний час, а остаточний обираєте ви.')).toBeTruthy();
    fireEvent.click(screen.getByTestId('draft-duration-180'));
    nextStep();
    fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
    nextStep();
    nextStep();
    expect(screen.queryByTestId('draft-remind')).toBeNull();
    fireEvent.click(screen.getByTestId('draft-spots-plus'));
    fireEvent.click(screen.getByTestId('draft-spots-plus'));
    fireEvent.click(screen.getByTestId('draft-spots-plus'));
    expect(screen.getByTestId('draft-spots-value').textContent).toBe('До 4');
    fireEvent.click(screen.getByTestId('draft-plus-one'));
    fireEvent.click(screen.getByRole('button', { name: 'Запросити' }));
    await flush();
    const body = sentBody();
    expect(body.at).toBe(AT);
    expect(body.slots).toEqual([AT, AT + 86_400_000]);
    expect(body.durationMin).toBe(180);
    expect(body.spots).toBe(4);
    expect(body.plusOne).toBe(true);
    expect(body.expiresAt).toBe(AT + 86_400_000 + 24 * 3600_000);
  });

  it('швидкий вибір рахує від «зараз»: за годину, ввечері 21:00', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: VENUE }} />);
    await flush();
    const now = new Date();
    const inHour = new Date(Math.ceil((now.getTime() + 3600_000) / 300_000) * 300_000);
    const pad = (n: number) => String(n).padStart(2, '0');
    fireEvent.click(screen.getByTestId('when-quick-inHour'));
    expect(screen.getByTestId('draft-at').getAttribute('data-value')).toBe(
      `${inHour.getFullYear()}-${pad(inHour.getMonth() + 1)}-${pad(inHour.getDate())}T${pad(inHour.getHours())}:${pad(inHour.getMinutes())}`,
    );
    fireEvent.click(screen.getByTestId('draft-slot-0'));
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    expect(screen.getByTestId('draft-at').getAttribute('data-value')).toBe('2026-10-09T21:00');
  });

  it('«Хто»: коло — фільтр списку, пошук за іменем, обрані нагорі з хрестиком', async () => {
    useMessengerStore.setState({
      chats: [{ id: 'c-o', type: 'direct', title: 'Олена', peerNodeId: OLENA, circle: 'friends', messages: [], unreadCount: 0 } as never],
    });
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: VENUE }} />);
    await flush();
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    nextStep();
    expect(screen.getByTestId('draft-circle-all').getAttribute('aria-checked')).toBe('true');
    fireEvent.click(screen.getByTestId('draft-circle-friends'));
    expect(screen.getByTestId('draft-circle-friends').className).toContain('bg-accent-soft');
    expect(screen.getAllByTestId(/^draft-person-/).map((b) => b.getAttribute('aria-label'))).toEqual(['Олена']);
    expect(screen.getByTestId(`draft-person-${OLENA}`).textContent).toContain('Друзі');
    fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
    expect(screen.getByTestId('draft-who-summary').textContent).toContain('Олена');
    fireEvent.click(screen.getByTestId('draft-circle-all'));
    fireEvent.change(screen.getByTestId('draft-who-query'), { target: { value: 'пет' } });
    expect(screen.getAllByTestId(/^draft-person-/).map((b) => b.getAttribute('aria-label'))).toEqual(['Петро']);
    fireEvent.change(screen.getByTestId('draft-who-query'), { target: { value: 'зоя' } });
    expect(screen.getByTestId('draft-who-none').textContent).toBe('Нікого не знайдено');
    fireEvent.click(screen.getByRole('button', { name: 'Прибрати Олена' }));
    expect(screen.queryByTestId('draft-who-summary')).toBeNull();
  });

  it('з розмови вдвох — співрозмовник уже обраний, «у цій розмові»', async () => {
    useMessengerStore.setState({
      chats: [{ id: 'c-o', type: 'direct', title: 'Олена', peerNodeId: OLENA, circle: 'friends', messages: [], unreadCount: 0 } as never],
    });
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ chatId: 'c-o' }} />);
    await flush();
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    nextStep();
    expect(screen.getByTestId(`draft-person-${OLENA}`).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByTestId(`draft-person-${OLENA}`).textContent).toContain('Друзі · у цій розмові');
    expect(screen.getByTestId(`draft-person-${PETRO}`).getAttribute('aria-checked')).toBe('false');
  });

  it('«Нагадати» на перевірці — будильник за 30 хв після «Запросити»; вимкнено — без будильника', async () => {
    const run = async (keep: boolean) => {
      render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: VENUE }} />);
      await flush();
      pickWhen(null, '2026-10-09', '19', '00');
      nextStep();
      fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
      nextStep();
      nextStep();
      const sw = screen.getByTestId('draft-remind');
      expect(sw.getAttribute('aria-checked')).toBe('true');
      expect(sw.textContent).toContain('За 30 хв до початку');
      if (!keep) fireEvent.click(sw);
      fireEvent.click(screen.getByRole('button', { name: 'Запросити' }));
      await flush();
      const r = calls.filter((c) => c.m === 'POST' && c.p === '/messenger/reminders');
      cleanup();
      localStorage.clear();
      return r;
    };
    const on = await run(true);
    expect(on).toHaveLength(1);
    expect(on[0].b.at_ms).toBe(AT - 30 * 60_000);
    calls.length = 0;
    expect(await run(false)).toHaveLength(0);
  });
});

function seedCard(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
  });
}
const Live: React.FC = () => {
  const m = useMessengerStore((s) => s.chats[0]?.messages[0]);
  return m ? <MeetupCard msg={m} meId={ME} /> : null;
};

describe('картка читає план', () => {
  const VOTE = { ...MEETUP, slots: [AT, AT + 86_400_000] };

  it('голосування: час ще обирають, мій голос — позначка slot:1, той, хто кличе, обирає', async () => {
    seedCard(meetupMsg({ meetupData: VOTE, cardMarks: { 'slot:0': [OLENA] } }));
    render(<Live />);
    expect(screen.getByTestId('card-status').textContent).toContain('Час ще обирають');
    expect(screen.getByTestId('meetup-slot-0').textContent).toContain('1 за');
    expect(screen.queryByTestId('meetup-eta')).toBeNull();
    fireEvent.click(screen.getByTestId('meetup-slot-1'));
    await flush();
    fireEvent.click(screen.getByTestId('meetup-lock-0'));
    await flush();
    const sent = calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b);
    expect(sent).toEqual([
      { value: 'slot:1', on: true },
      { value: 'lock:0', on: true },
    ]);
  });

  it('обрано — голосування сховано, у статусі обраний час і кінець', () => {
    seedCard(meetupMsg({ meetupData: { ...VOTE, durationMin: 120 }, cardMarks: { 'lock:1': [ME] } }));
    render(<Live />);
    expect(screen.queryByTestId('meetup-vote')).toBeNull();
    expect(screen.getByTestId('card-status').textContent).toMatch(/19:00–21:00/);
    expect(screen.getByTestId('card-status').textContent).toMatch(/^Завтра/);
  });

  it('RSVP як у рендері: бурштину в картці нема; моя відповідь — мʼяка заливка + чорнило', () => {
    seedCard(meetupMsg({ meetupData: MEETUP }));
    render(<Live />);
    const rsvp = screen.getByTestId('meetup-rsvp');
    expect(rsvp.querySelectorAll('.bg-accent')).toHaveLength(0);
    cleanup();
    seedCard(meetupMsg({ meetupData: MEETUP, cardMarks: { 'rsvp:maybe': [ME] } }));
    render(<Live />);
    const maybe = screen.getByTestId('meetup-rsvp-maybe');
    expect(maybe.getAttribute('aria-pressed')).toBe('true');
    expect(maybe.className).toContain('bg-accent-soft');
    expect(maybe.className).toContain('text-ink');
    expect(screen.getByTestId('meetup-rsvp-yes').className).not.toContain('bg-accent');
  });

  it('місць немає — «Іду» вимкнено для мене, і це сказано', () => {
    seedCard(meetupMsg({ meetupData: { ...MEETUP, spots: 2 }, cardMarks: { 'rsvp:yes': [OLENA, PETRO] } }));
    render(<Live />);
    expect(screen.getByTestId('meetup-going').textContent).toContain('місць немає');
    expect((screen.getByTestId('meetup-rsvp-yes') as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByTestId('meetup-rsvp-maybe') as HTMLButtonElement).disabled).toBe(false);
  });

  it('+1: іду — дія «+1» ставить plus:1; гість займає місце', async () => {
    seedCard(meetupMsg({ meetupData: { ...MEETUP, spots: 4, plusOne: true }, cardMarks: { 'rsvp:yes': [ME, OLENA], 'plus:1': [OLENA] } }));
    render(<Live />);
    expect(screen.getByTestId('meetup-going').textContent).toContain('вільних місць: 1');
    fireEvent.click(screen.getByTestId('meetup-plus'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([{ value: 'plus:1', on: true }]);
  });
});

describe('назва події', () => {
  it('з дроту: обрізана, порожня відкинута', () => {
    expect(parseMeetupData({ ...MEETUP, title: '  Днюха Олі  ' })!.title).toBe('Днюха Олі');
    expect(parseMeetupData({ ...MEETUP, title: '   ' })!.title).toBeUndefined();
    expect(parseMeetupData({ ...MEETUP, title: 'x'.repeat(200) })!.title).toHaveLength(80);
  });

  it('назва з аркуша їде в тілі картки й у назві групи', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: VENUE }} />);
    await flush();
    pickWhen(null, '2026-10-09', '19', '00');
    nextStep();
    fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
    nextStep();
    nextStep();
    fireEvent.change(screen.getByTestId('draft-title'), { target: { value: 'Днюха Олі' } });
    fireEvent.click(screen.getByRole('button', { name: 'Запросити' }));
    await flush();
    expect(sentBody().title).toBe('Днюха Олі');
    const made = calls.find((c) => c.m === 'POST' && c.p === '/messenger/conversations');
    expect(made!.b.title).toMatch(/^Днюха Олі · \d\d:\d\d$/);
  });

  it('картка: назва вгорі, місце поруч з адресою, дотик веде на мапу', () => {
    seedCard(meetupMsg({ meetupData: { ...MEETUP, title: 'Днюха Олі' } }));
    render(<Live />);
    const card = screen.getByTestId('card-meetup');
    expect(within(card).getByTestId('card-title').textContent).toBe('Днюха Олі');
    expect(within(card).getByTestId('meetup-venue').textContent).toContain(VENUE.name);
  });

  it('без назви — як було: заголовок = місце', () => {
    seedCard(meetupMsg({ meetupData: MEETUP }));
    render(<Live />);
    expect(within(screen.getByTestId('card-meetup')).getByTestId('card-title').textContent).toBe(VENUE.name);
  });
});
