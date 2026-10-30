/**
 * UX-аудит №3 (09.10): у чернетці зустрічі (головна сцена) дата — сире поле
 * «mm/dd/yyyy», а в «Хто» — «Вузол 4d839df7», «bd6e·d7af» і три Марти.
 * Тепер день і час дотиком, а люди — лише з людським іменем, кожна раз.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { typedVenue } from '../types/meetup';
import { MeetupDraftSheet, realInvitees } from '../components/messenger/meetup/MeetupDraftSheet';
import { useMessengerStore } from '../stores/messengerStore';
import { AT, ME, installBridge, removeBridge, type Call } from './meetupFixtures';
import { nextStep, pickWhen, stepNow } from './whenPick';

const toWho = () => {
  fireEvent.click(screen.getByTestId('when-quick-tonight'));
  nextStep();
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const contact = (id: string, name: string) => ({
  id, peer_node_id: id, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '', verified: false,
});

const ROWS = [
  contact('4d839df7aa', 'Вузол 4d839df7'),
  contact('bd6ed7af00', 'bd6e·d7af'),
  contact('marta-1', 'Марта'),
  contact('marta-2', 'Марта'),
  contact('marta-3', 'марта · 9b10·41af'),
  contact('nina', 'nina'),
];

function answer(c: Call) {
  if (c.p === '/messenger/contacts') return { data: ROWS };
  if (c.p.startsWith('/messenger/where/')) return { status: 404 };
  if (c.p === '/messenger/venues/near') return { data: { venues: [] } };
  return undefined;
}
const flush = () => act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 4 * 3600_000)); // 9 жовтня, 15:00
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [{ id: 'c2', title: 'Марта', peerNodeId: 'marta-2', type: 'dm', messages: [], unreadCount: 0 }] as never,
    activeChatId: '',
  });
  installBridge(answer);
});
afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

describe('люди для зустрічі — справжні й без повторів', () => {
  it('машинні імена прибрано, Марта одна — та, з ким є розмова', () => {
    const out = realInvitees(ROWS.map((r) => ({ id: r.id, name: r.display_name })), new Set(['marta-2']));
    expect(out).toEqual([{ id: 'marta-2', name: 'Марта' }, { id: 'nina', name: 'nina' }]);
  });

  it('у шторці — лише Марта й nina', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: typedVenue('Park') }} />);
    await flush();
    toWho();
    const names = screen.getAllByTestId(/^draft-person-/).map((b) => b.getAttribute('aria-label'));
    expect(names).toEqual(['nina', 'Марта'].sort((a, b) => a!.localeCompare(b!, 'uk')));
    expect(screen.getByTestId('meetup-draft').textContent).not.toMatch(/Вузол|bd6e·d7af/);
  });

  it('«Хто» — кілька людей: квадрат із галочкою, а не круг перемикача', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: typedVenue('Park') }} />);
    await flush();
    toWho();
    const rows = screen.getAllByTestId(/^draft-person-/);
    for (const r of rows) {
      expect(r.getAttribute('role')).toBe('checkbox');
      expect(r.querySelector('[data-shape="square"]')).not.toBeNull();
      expect(r.querySelector('.rounded-full[aria-hidden]')).toBeNull();
    }
    fireEvent.click(rows[0]);
    fireEvent.click(rows[1]);
    expect(rows.filter((r) => r.getAttribute('aria-checked') === 'true')).toHaveLength(2);
  });
});

describe('коли — дотиком, без сирого поля дати', () => {
  it('немає datetime-local і смужки сталих годин; день і час — у вікнах «Коли»', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: typedVenue('Park') }} />);
    await flush();
    const sheet = screen.getByTestId('meetup-draft');
    expect(sheet.querySelector('input[type="datetime-local"],input[type="date"],input[type="time"]')).toBeNull();
    expect(within(sheet).queryByTestId('draft-time-08:00')).toBeNull();
    expect(within(sheet).queryByTestId('draft-day-0')).toBeNull();
    expect(within(sheet).getByTestId('when-quick')).toBeTruthy();
    pickWhen(null, '2026-10-10', '20', '30');
    expect(within(sheet).getByTestId('draft-at').getAttribute('data-value')).toBe('2026-10-10T20:30');
    expect(within(sheet).getByTestId('draft-slot-0').textContent).toContain('20:30');
  });

  it('назад у «Коли» — одне вікно; минулий день не обрати', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: typedVenue('Park') }} />);
    await flush();
    fireEvent.click(screen.getByTestId('when-pick'));
    expect((document.querySelector('[data-day="2026-10-08"]') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getAllByRole('button', { name: 'Назад' })[0]);
    expect(screen.getByTestId('when-quick')).toBeTruthy();
    expect(stepNow()).toBe('Крок 1 з 4 · Коли');
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    fireEvent.click(screen.getByTestId('draft-slot-0'));
    fireEvent.click(screen.getAllByRole('button', { name: 'Назад' })[0]);
    expect(stepNow()).toBe('Крок 1 з 4 · Коли');
    expect(screen.getByTestId('draft-slot-0')).toBeTruthy();
  });

  it('тривалість обрана — мʼяка заливка й чорнило, не бурштин', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: typedVenue('Park') }} />);
    await flush();
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    const on = screen.getByTestId('draft-duration-120');
    expect(on.getAttribute('aria-checked')).toBe('true');
    expect(on.className).toContain('bg-accent-soft');
    expect(on.className).toContain('text-ink');
    expect(on.className).not.toMatch(/\b(bg-accent|text-accent|border-accent)\b(?!-)/);
  });
});
