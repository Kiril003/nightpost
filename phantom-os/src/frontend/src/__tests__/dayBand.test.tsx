import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { buildDay } from '../components/messenger/today/agenda';
import { DayBand } from '../components/messenger/today/DayBand';
import { DaySheetHost, useDayView } from '../components/messenger/today/DaySheetHost';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { localDay } from '../components/messenger/ScheduledMessagesDrawer';
import { dayApi, type DayItem } from '../services/dayApi';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, ScheduledMessage, UserProfile } from '../types/messenger';

const at = (h: number, m = 0) => {
  const d = new Date();
  d.setHours(h, m, 0, 0);
  return d.getTime();
};
const hhmm = (h: number, m = 0) => `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'привіт',
    lastAt: new Date(at(10)).toISOString(),
    ...extra,
  }) as unknown as Chat;

const letter = (id: string, chatId: string, chatTitle: string, date: string, time: string): ScheduledMessage =>
  ({
    id,
    chatId,
    chatTitle,
    scheduledTime: `${date} ${time}`,
    scheduledDate: date,
    scheduledExactTime: time,
    createdAt: new Date().toISOString(),
    type: 'text',
    text: 'лист',
  }) as unknown as ScheduledMessage;

const reminder = (id: string, h: number, overdue = false): DayItem => ({
  id,
  kind: 'reminder',
  title: 'Подзвонити мамі',
  atMs: at(h),
  endMs: null,
  allDay: false,
  peerId: null,
  overdue,
});

const today = localDay(new Date());
const tomorrow = localDay(new Date(Date.now() + 86_400_000));

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('buildDay: today from real stores', () => {
  const chats = [
    chat('close1', 'Мама', { circle: 'close', unreadCount: 2 }),
    chat('work1', 'Робота', { circle: 'work', unreadCount: 5 }),
    chat('muted', 'Тиха', { circle: 'close', unreadCount: 3, muted: true }),
    chat('arch', 'Архів', { circle: 'personal', unreadCount: 1, archived: true }),
    chat('ring', 'Олена', { circle: 'friends', circles: ['personal', 'friends'], unreadCount: 1, lastAt: new Date(at(11)).toISOString() }),
    chat('read', 'Прочитано', { circle: 'close', unreadCount: 0 }),
  ];
  const scheduled = [letter('s1', 'close1', 'Мама', today, hhmm(15)), letter('s2', 'close1', 'Мама', tomorrow, hhmm(9))];

  it('timed first (server + scheduled today), then unread from close rings; no work, muted, archived or tomorrow', () => {
    const view = buildDay({ server: [reminder('r1', 9, true)], chats, meId: 'me', scheduled, nowMs: at(12) });
    expect(view.items.map((i) => i.id)).toEqual(['r1', 'scheduled:s1', 'unread:ring', 'unread:close1']);
    expect(view.items.find((i) => i.id === 'unread:close1')).toMatchObject({ kind: 'unread', chatId: 'close1', count: 2 });
    expect(view.items.find((i) => i.id === 'scheduled:s1')).toMatchObject({ kind: 'scheduled', atMs: at(15), overdue: false });
    expect(view.top.map((i) => i.id)).toEqual(['r1', 'scheduled:s1', 'unread:ring']);
  });

  it('a letter sent more than a minute ago leaves; a failed one stays marked failed', () => {
    const sent = letter('sent', 'c1', 'Мама', today, hhmm(11, 57));
    const failed = { ...letter('bad', 'c1', 'Мама', today, hhmm(11, 0)), failure: 'нема звʼязку' } as ScheduledMessage;
    const view = buildDay({ server: [], chats: [], meId: 'me', scheduled: [sent, failed], nowMs: at(12) });
    expect(view.items.map((i) => i.id)).toEqual(['scheduled:bad']);
    expect(view.items[0]).toMatchObject({ failed: true, overdue: false });
  });

  it('unread only from today, never archived', () => {
    const yesterday = new Date(at(10) - 86_400_000).toISOString();
    const chats2 = [
      chat('old', 'Вчора', { circle: 'close', unreadCount: 4, lastAt: yesterday }),
      chat('arch2', 'Архів', { circle: 'close', unreadCount: 1, isArchived: true } as Partial<Chat>),
      chat('new', 'Сьогодні', { circle: 'close', unreadCount: 1 }),
    ];
    const view = buildDay({ server: [], chats: chats2, meId: 'me', scheduled: [], nowMs: at(12) });
    expect(view.items.map((i) => i.id)).toEqual(['unread:new']);
  });

  it('top: overdue first, then upcoming, then the rest; one chat takes one slot', () => {
    const past: DayItem = { ...reminder('p8', 8), kind: 'promise', title: 'Книга' };
    const event: DayItem = { ...reminder('e18', 18), kind: 'event', title: 'Кіно', endMs: at(20) };
    const view = buildDay({
      server: [past, reminder('r11', 11, true), event],
      chats: [chat('c1', 'Мама', { circle: 'close', unreadCount: 2 })],
      meId: 'me',
      scheduled: [letter('s1', 'c1', 'Мама', today, hhmm(15))],
      nowMs: at(12),
    }, 4);
    expect(view.top.map((i) => i.id)).toEqual(['r11', 'scheduled:s1', 'e18', 'p8']);
  });

  it('nothing real → nothing shown', () => {
    const view = buildDay({ server: [], chats: [chat('a', 'А')], meId: 'me', scheduled: [], nowMs: at(12) });
    expect(view.items).toEqual([]);
    expect(view.top).toEqual([]);
  });
});

describe('DayBand states', () => {
  const noop = () => undefined;

  it('empty with calendar denied: one calendar entry «Календар», never «Вільно»', () => {
    const ask = vi.fn();
    render(<DayBand top={[]} total={0} status="ready" calendar="denied" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={ask} />);
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.textContent).not.toContain('Вільно');
    expect(within(band).queryByRole('button', { name: 'Показати календар' })).toBeNull();
    fireEvent.click(within(band).getByRole('button', { name: 'Календар' }));
    fireEvent.click(document.querySelector('[data-testid="calendar-sheet"] [data-day-show-calendar]') as HTMLElement);
    // Зріз 5: системний запит — лише після аркуша з підписами.
    expect(ask).not.toHaveBeenCalled();
    fireEvent.click(document.querySelector('[data-calendar-ask-allow]') as HTMLElement);
    expect(ask).toHaveBeenCalled();
  });

  it('empty with calendar granted: «Вільно», no dead action', () => {
    render(<DayBand top={[]} total={0} status="ready" calendar="granted" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />);
    const empty = document.querySelector('[data-day-empty]') as HTMLElement;
    expect(empty.textContent).toContain('Сьогодні вільно');
    expect(within(empty).queryByRole('button')).toBeNull();
  });

  it.each(['granted', 'denied', 'unknown'] as const)('stale + 0 items (calendar %s): only «Не оновлено»', (calendar) => {
    render(<DayBand top={[]} total={0} status="stale" calendar={calendar} nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />);
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.querySelector('[data-day-stale]')?.textContent).toBe('Не оновлено');
    expect(band.querySelector('[data-day-empty]')).toBeNull();
    expect(band.textContent).not.toContain('Вільно');
    expect(within(band).queryByRole('button', { name: 'Показати календар' })).toBeNull();
  });

  it('calendar unknown: no «Вільно», no calendar action', () => {
    render(<DayBand top={[]} total={0} status="ready" calendar="unknown" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />);
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.querySelector('[data-day-empty]')).toBeNull();
    expect(band.textContent).not.toContain('Вільно');
  });

  it('failed letter row says «Не надіслано»', () => {
    const item: DayItem = { ...reminder('scheduled:bad', 11), kind: 'scheduled', title: 'Мама', failed: true, chatId: 'c1' };
    render(<DayBand top={[item]} total={1} status="ready" calendar="granted" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />);
    expect(document.querySelector('[data-day-failed]')?.textContent).toBe('Не надіслано');
  });

  it('loading: busy, no «Вільно» yet; stale: tag next to the date', () => {
    const { rerender } = render(
      <DayBand top={[]} total={0} status="loading" calendar="granted" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />,
    );
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.getAttribute('aria-busy')).toBe('true');
    expect(band.querySelector('[data-day-empty]')).toBeNull();
    const item = reminder('r1', 9, true);
    rerender(<DayBand top={[item]} total={4} status="stale" calendar="granted" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />);
    expect(band.querySelector('[data-day-stale]')?.textContent).toBe('Не оновлено');
    expect(within(band).getByRole('button', { name: /Сьогодні · ще 4/ })).toBeTruthy();
    expect(band.querySelector('[data-day-top="reminder"] [data-day-when]')?.textContent).toBe('09:00');
    expect(band.querySelector('[data-day-overdue]')?.textContent).toBe('Прострочено');
  });
});

describe('Day is reachable from the Розмови header (phone)', () => {
  const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
  const mount = (chats: Chat[], onSelectChat = vi.fn()) =>
    render(
      <PhoneChatList chats={chats} currentUser={USER} onSelectChat={onSelectChat} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );

  beforeEach(() => {
    useMessengerStore.setState({ typingStatus: {}, drafts: {}, scheduledMessages: [], isScheduledDrawerOpen: false } as never);
    useFolders.setState({ folders: [] } as never);
  });

  it('calendar button opens the band; a scheduled letter opens the scheduled drawer; unread stays in the list, not in the day; date opens the full list', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [], calendar: 'granted' });
    useMessengerStore.setState({ scheduledMessages: [letter('s1', 'c2', 'Друг', today, '23:59')] } as never);
    const onSelectChat = vi.fn();
    const { container } = mount([chat('c1', 'Мама', { circle: 'close', unreadCount: 2 }), chat('c2', 'Друг')], onSelectChat);
    const sheet = () => container.querySelector('[data-top-sheet]') as HTMLElement;
    expect(container.querySelector('[data-day-band]')).toBeNull();

    const open = container.querySelector('[data-today-bar] [data-today-open]') as HTMLElement;
    expect(open.getAttribute('aria-label')).toBe('Сьогодні');
    fireEvent.click(open);
    expect(sheet().getAttribute('data-sheet-state')).toBe('open');
    await waitFor(() => expect(container.querySelector('[data-day-band]')?.getAttribute('data-day-status')).toBe('ready'));
    const band = container.querySelector('[data-day-band]') as HTMLElement;
    expect([...band.querySelectorAll('[data-day-top]')].map((n) => n.getAttribute('data-day-top'))).toEqual(['scheduled']);
    expect(band.textContent).not.toContain('Мама');

    fireEvent.click(band.querySelector('[data-day-top="scheduled"] button') as HTMLElement);
    expect(useMessengerStore.getState().isScheduledDrawerOpen).toBe(true);
    expect(sheet().getAttribute('data-sheet-state')).toBe('compact');

    fireEvent.click(open);
    await waitFor(() => expect(container.querySelector('[data-day-band] [data-today-date]')).toBeTruthy());
    await act(async () => {
      fireEvent.click(container.querySelector('[data-day-band] [data-today-date]') as HTMLElement);
    });
    const full = screen.getByTestId('day-sheet');
    expect([...full.querySelectorAll('[data-day-row]')].map((n) => n.getAttribute('data-day-row'))).toEqual(['scheduled']);
  });

  it('opening the band re-reads scheduled letters from the device', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [], calendar: 'granted' });
    const refreshScheduled = vi.fn(async () => undefined);
    useMessengerStore.setState({ refreshScheduled } as never);
    const { container } = mount([chat('c1', 'Мама')]);
    expect(refreshScheduled).not.toHaveBeenCalled();
    fireEvent.click(container.querySelector('[data-today-open]') as HTMLElement);
    await waitFor(() => expect(refreshScheduled).toHaveBeenCalledTimes(1));
  });

  it('search or selection folds the band; the profile lives in the title, no chip beside it', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [], calendar: 'granted' });
    const { container } = mount([chat('c1', 'Мама'), chat('c2', 'Друг')]);
    expect(container.querySelector('[data-profile-slot]')).toBeNull();
    fireEvent.click(container.querySelector('[data-today-open]') as HTMLElement);
    expect(container.querySelector('[data-top-sheet]')?.getAttribute('data-sheet-state')).toBe('open');
    fireEvent.click(container.querySelector('[data-kit="ask-field"][data-search-pill]') as HTMLElement);
    await waitFor(() => expect(container.querySelector('[data-search-box]')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Скасувати' }));
    expect(container.querySelector('[data-top-sheet]')?.getAttribute('data-sheet-state')).toBe('compact');
    expect(container.querySelector('[data-day-band]')).toBeNull();
  });

  it('node down: band says «Не оновлено» and still lists local items', async () => {
    vi.spyOn(dayApi, 'today').mockRejectedValue(new Error('offline'));
    const { container } = mount([chat('c1', 'Мама', { circle: 'personal', unreadCount: 1 })]);
    fireEvent.click(container.querySelector('[data-today-open]') as HTMLElement);
    await waitFor(() => expect(container.querySelector('[data-day-stale]')).toBeTruthy());
    expect(container.querySelector('[data-day-top="unread"]')).toBeTruthy();
  });

  it('first fetch fails with 0 items: «Не оновлено» only — no «Вільно», no «Показати календар»', async () => {
    vi.spyOn(dayApi, 'today').mockRejectedValue(new Error('offline'));
    const { container } = mount([chat('c1', 'Мама')]);
    fireEvent.click(container.querySelector('[data-today-open]') as HTMLElement);
    await waitFor(() => expect(container.querySelector('[data-day-stale]')).toBeTruthy());
    const band = container.querySelector('[data-day-band]') as HTMLElement;
    expect(band.textContent).not.toContain('Вільно');
    expect(within(band).queryByRole('button', { name: 'Показати календар' })).toBeNull();
  });
});

describe('DaySheetHost: one refresh per action', () => {
  it('removing a reminder reloads the day once, not twice', async () => {
    const today = vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [reminder('r1', 9)], calendar: 'granted' });
    const remove = vi.spyOn(dayApi, 'removeReminder').mockResolvedValue(undefined as never);
    const Host = () => {
      const data = useDayView(true, [], at(12));
      return <DaySheetHost open day={data} nowMs={at(12)} onClose={() => undefined} onOpenChat={() => undefined} />;
    };
    render(<Host />);
    await waitFor(() => expect(document.querySelector('[data-day-next-done], [data-day-row="reminder"]')).toBeTruthy());
    const before = today.mock.calls.length;
    await act(async () => {
      fireEvent.click(document.querySelector('[data-day-next-done]') ?? screen.getByRole('button', { name: 'Видалити' }));
    });
    await waitFor(() => expect(remove).toHaveBeenCalledWith('r1'));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(today.mock.calls.length - before).toBe(1);
  });
});

describe('W4 d-today: Сьогодні · ще N, картка найближчого, порожній день', () => {
  it('найближче нагадування стає карткою з «Відкласти» і «Зроблено», решта — рядками з позначкою виду', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [reminder('r1', 18), reminder('r2', 20)], calendar: 'granted' });
    const Host = () => {
      const data = useDayView(true, [], at(12));
      return <DaySheetHost open day={data} nowMs={at(12)} onClose={() => undefined} onOpenChat={() => undefined} />;
    };
    render(<Host />);
    await waitFor(() => expect(document.querySelector('[data-day-next]')).toBeTruthy());
    expect(document.querySelector('[data-day-caption]')!.textContent).toBe('Сьогодні · ще 2');
    expect(document.querySelector('[data-day-next-snooze]')).toBeTruthy();
    expect(document.querySelectorAll('[data-day-row="reminder"]').length).toBe(1);
    expect(document.querySelector('[data-day-bar]')).toBeTruthy();
  });

  it('порожній день: «Сьогодні вільно» без вигаданих пунктів', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [], calendar: 'granted' });
    const Host = () => {
      const data = useDayView(true, [], at(12));
      return <DaySheetHost open day={data} nowMs={at(12)} onClose={() => undefined} onOpenChat={() => undefined} />;
    };
    render(<Host />);
    await waitFor(() => expect(document.querySelector('[data-day-free]')).toBeTruthy());
    expect(document.querySelector('[data-day-next]')).toBeNull();
  });
});

describe('W4 d-create: нове нагадування з аркуша Дня', () => {
  it('«Нове нагадування» → текст і час → зберігає через dayApi.createReminder і оновлює день', async () => {
    const today = vi.spyOn(dayApi, 'today').mockResolvedValue({ items: [], calendar: 'granted' });
    const create = vi.spyOn(dayApi, 'createReminder').mockResolvedValue(undefined as never);
    const Host = () => {
      const data = useDayView(true, [], at(12));
      return <DaySheetHost open day={data} nowMs={at(12)} onClose={() => undefined} onOpenChat={() => undefined} />;
    };
    render(<Host />);
    await waitFor(() => expect(document.querySelector('[data-day-create]')).toBeTruthy());
    fireEvent.click(document.querySelector('[data-day-create]')!);
    const save = (await screen.findByTestId('create-reminder-sheet')).querySelector('[data-create-save]') as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(document.querySelector('[data-create-text]')!, { target: { value: 'Забрати посилку' } });
    expect(save.disabled).toBe(false);
    // «Коли» — спільні вікна, без сирого datetime-local і без чіпів зі сталими годинами.
    expect(document.querySelector('[data-testid="create-reminder-sheet"] input[type="datetime-local"]')).toBeNull();
    expect(document.querySelector('[data-create-when="h1"]')).toBeNull();
    fireEvent.click(document.querySelector('[data-create-when]')!);
    fireEvent.click(screen.getByTestId('when-quick-inHour'));
    expect(document.querySelector('[data-create-when-value]')!.textContent).toBe('Сьогодні, 13:00');
    const before = today.mock.calls.length;
    await act(async () => {
      fireEvent.click(document.querySelector('[data-create-save]')!);
    });
    await waitFor(() => expect(create).toHaveBeenCalledWith('Забрати посилку', at(12) + 3_600_000));
    await waitFor(() => expect(today.mock.calls.length).toBeGreaterThan(before));
  });
});

describe('W4 d-today у шапці розмов: «Зараз», «Далі сьогодні», обіцянки з розмов', () => {
  const noop = () => undefined;
  const unread: DayItem = { id: 'unread:c1', kind: 'unread', title: 'Оксана', atMs: at(10), endMs: null, allDay: true, peerId: null, overdue: false, chatId: 'c1', count: 2 };
  const promise: DayItem = { id: 'p1', kind: 'promise', title: 'Скинути макет', atMs: at(9), endMs: null, allDay: true, peerId: 'peer_b', overdue: false, chatId: 'c9', from: 'Богдан' };

  it('найближче — картка з великим часом, «через N хв» і однією бурштиновою дією; решта — рядками без мітки в колонці часу', () => {
    const r1 = reminder('r1', 18);
    const onDone = vi.fn();
    const onSnooze = vi.fn();
    render(
      <DayBand top={[r1, reminder('r2', 20), unread]} items={[r1, reminder('r2', 20), unread]} total={3} status="ready" calendar="granted" nowMs={at(17, 30)}
        onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} onDone={onDone} onSnooze={onSnooze} />,
    );
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.querySelector('[data-day-caption]')?.textContent).toBe('Сьогодні · ще 2');
    const focal = band.querySelector('[data-day-focal]') as HTMLElement;
    expect(focal.querySelector('[data-day-when]')?.textContent).toBe('18:00');
    expect(focal.textContent).toContain('Через 30 хв');
    expect(band.querySelectorAll('.bg-accent').length).toBe(1);
    fireEvent.click(focal.querySelector('[data-day-focal-snooze]') as HTMLElement);
    fireEvent.click(focal.querySelector('[data-day-focal-done]') as HTMLElement);
    expect(onSnooze).toHaveBeenCalledWith(r1);
    expect(onDone).toHaveBeenCalledWith(r1);
    expect(band.querySelector('[data-day-band-next]')?.textContent).toBe('Далі: 20:00 Подзвонити мамі');
    expect(band.querySelector('[data-day-later], [data-day-band-reminders], [data-day-top="unread"]')).toBeNull();
  });

  it('обіцянка з розмови — рядок «Далі», що відкриває аркуш Дня; кружок «Зроблено» живе в аркуші', () => {
    const onOpenAll = vi.fn();
    render(
      <DayBand top={[unread]} items={[promise, unread]} total={2} status="ready" calendar="granted" nowMs={at(12)}
        onOpenAll={onOpenAll} onOpenItem={noop} onAskCalendar={noop} onDone={vi.fn()} />,
    );
    const next = document.querySelector('[data-day-band-next]') as HTMLElement;
    expect(next.textContent).toBe('Далі: Скинути макет');
    expect(document.querySelector('[data-day-promises]')).toBeNull();
    fireEvent.click(next);
    expect(onOpenAll).toHaveBeenCalledTimes(1);
  });

  it('порожній день: значок, «Сьогодні вільно», «Нагадування» відкриває нове нагадування — без вигаданої «Події»', () => {
    render(
      <DayBand top={[]} items={[]} total={0} status="ready" calendar="granted" nowMs={at(12)}
        onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} onCreateReminder={noop} />,
    );
    const empty = document.querySelector('[data-day-empty]') as HTMLElement;
    expect(empty.textContent).toContain('Сьогодні вільно');
    expect(empty.textContent).not.toContain('Подія');
    fireEvent.click(within(empty).getByRole('button', { name: 'Нагадування' }));
    expect(screen.getByTestId('create-reminder-sheet')).toBeTruthy();
  });

  it('обіцянка з сервера знаходить розмову з тією людиною', () => {
    const c = chat('c9', 'Богдан', { peerNodeId: 'peer_b' } as Partial<Chat>);
    const server: DayItem = { ...promise, chatId: undefined, from: undefined };
    const { items } = buildDay({ server: [server], chats: [c], meId: 'me', scheduled: [], nowMs: at(12) });
    expect(items[0]).toMatchObject({ chatId: 'c9', from: 'Богдан' });
  });
});
