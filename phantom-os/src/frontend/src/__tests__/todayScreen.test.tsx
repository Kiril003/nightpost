/**
 * «Розмови» у вигляді D (R2): дата над великим заголовком, поле пошуку з мікрофоном,
 * смуга кіл з волосиною, рядок 72 з аватаром 52 і вставною волосиною; бурштин — лише
 * прямі неприглушені (не більше трьох): час і лічильник; галочки сірі.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { todayDateLine, todayTimeLabel } from '../components/messenger/todayFormat';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;
const fill = (n: number): Chat[] => Array.from({ length: n }, (_, i) => chat(`f${i}`, `Друг ${i}`));
const mine = (extra: Partial<Message> = {}): Message =>
  ({ id: 'm1', type: 'text', text: 'буду о сьомій', isSelf: true, senderName: 'Кирило', sentAt: new Date().toISOString(), status: 'sent', ...extra }) as unknown as Message;

const navigate = vi.fn((_to: string, _args?: string) => true);
const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { navigate };
  navigate.mockClear();
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
});

afterEach(() => {
  cleanup();
  setLocale('uk');
  vi.useRealTimers();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

const mount = (chats: Chat[], extra: { ask?: boolean; desk?: boolean; queued?: number } = {}) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      ask={extra.ask}
      desk={extra.desk}
      queued={extra.queued}
    />,
  );

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;

describe('шапка «Сьогодні»', () => {
  it('шапка 10 §2: видимий заголовок «Розмови», «Нова» 48×48 без сканера; поле пошуку 48 з мікрофоном — під шапкою', () => {
    const { container } = mount([chat('o', 'Оксана')], { ask: true });
    const head = container.querySelector('[data-testid="chat-list"] > header') as HTMLElement;
    const h1 = head.querySelector('[data-pane-tab="chats"]') as HTMLElement;
    expect(h1.textContent).toBe('Чати');
    expect(h1.className).not.toContain('sr-only');
    expect(head.querySelector('[data-today-date]')).toBeNull();
    const bar = head.querySelector('[data-today-bar]') as HTMLElement;
    expect(bar.className).not.toMatch(/border-b|shadow/);
    expect(bar.querySelector('[data-list-scan]')).toBeNull();
    const compose = bar.querySelector('[data-today-compose]') as HTMLElement;
    expect(compose.getAttribute('aria-label')).toBe('Створити новий діалог');
    expect(compose.className).toContain('w-12 h-12');
    const field = head.querySelector('[data-kit="ask-field"]') as HTMLElement;
    expect(bar.contains(field)).toBe(false);
    expect(field.className).toContain('h-12');
    expect(within(field).getByRole('searchbox', { name: 'Пошук або спитати Nightpost' })).toBeTruthy();
    const mic = field.querySelector('[data-testid="search-ask-voice"]') as HTMLElement;
    expect(mic).toBeTruthy();
    expect(mic.className).toContain('w-12 h-12');
  });

  it('дотик по полю відкриває пошук-або-питання; мікрофон живе в полі', () => {
    const { container } = mount([chat('o', 'Оксана')], { ask: true });
    fireEvent.click(container.querySelector('[data-search-pill]') as HTMLElement);
    expect(container.querySelector('[data-testid="chat-list"]')!.getAttribute('data-search-open')).toBe('true');
    expect(screen.getByRole('searchbox')).toBeTruthy();
    fireEvent.click(screen.getByTestId('search-ask-voice'));
    expect(navigate).toHaveBeenCalledWith('call-phantom', '{}');
  });

  it('на телефоні з оболонкою шапка без сканера (QR — в аркуші «Нова»); «Я» лишається вкладці', () => {
    const { container } = mount([chat('o', 'Оксана')]);
    expect(screen.queryByRole('button', { name: 'Я' })).toBeNull();
    const bar = container.querySelector('[data-today-bar]') as HTMLElement;
    expect(bar.querySelector('[data-list-scan]')).toBeNull();
    expect(bar.querySelectorAll('button:not([data-pane-tab])').length).toBeLessThanOrEqual(2);
  });

  it('без рідної оболонки на телефоні немає ні сканера, ні аватара (L6)', () => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    const { container } = mount([chat('o', 'Оксана')]);
    expect(container.querySelector('[data-list-scan]')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Я' })).toBeNull();
  });

  it('на ПК ні камери, ні аватара: «Я» живе один раз — у слоті острова (17.10)', () => {
    const { container } = mount([chat('o', 'Оксана')], { desk: true });
    expect(container.querySelector('[data-list-scan]')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Я' })).toBeNull();
  });

  it('прокрутка: вниз пошук лишається на місці (згортаються лише чипи), капсула не стає заголовком, без тіні', () => {
    const { container } = mount([chat('o', 'Оксана')]);
    const head = container.querySelector('[data-testid="chat-list"] > header') as HTMLElement;
    const sheet = () => head.querySelector('[data-top-sheet]') as HTMLElement;
    const before = (head.querySelector('[data-today-bar]') as HTMLElement).className;
    expect(sheet().className).not.toContain('border-b');
    const list = container.querySelector('[data-phantom-list]') as HTMLElement;
    Object.defineProperty(list, 'scrollTop', { value: 100, configurable: true });
    fireEvent.scroll(list);
    expect((head.querySelector('[data-today-bar]') as HTMLElement).className).toBe(before);
    expect(sheet().className).not.toContain('-translate-y-full');
    expect(sheet().getAttribute('data-bar-hidden')).toBe('true');
    expect((head.querySelector('[data-pane-tab="chats"]') as HTMLElement).className).not.toContain('sr-only');
    expect(head.innerHTML).not.toContain('shadow');
  });

  it('під шапкою немає рядків стану доставки (10 §6) і відʼємного відступу (-mt-1 ніде)', () => {
    const { container } = mount([chat('o', 'Оксана')], { queued: 2 });
    expect(screen.queryByTestId('queue-line')).toBeNull();
    expect(container.querySelector('.-mt-1')).toBeNull();
    expect(document.querySelector('.-mt-1')).toBeNull();
  });

  it('дата — справжня, мовою людини', () => {
    const thu = new Date(2026, 9, 8, 17, 42);
    expect(todayDateLine(thu, 'en')).toBe('Thursday, 8 October');
    expect(todayDateLine(thu, 'uk')).toBe('Четвер, 8 жовтня');
  });
});

describe('смуга фільтрів', () => {
  const chats = [chat('o', 'Оксана', { unreadCount: 2 }), chat('g', 'Двір', { type: 'group', unreadCount: 4, muted: true }), ...fill(4)];

  it('пʼять сталих місць; вибране — bg-ink text-canvas, решта bg-raised text-ink; без галочок, лічильників і риски', () => {
    mount(chats);
    const strip = document.querySelector('[data-filter-strip]') as HTMLElement;
    expect(strip.className).toMatch(/min-h-12/);
    const pill = (k: string) => document.querySelector(`[data-filter-tab="${k}"] [data-kit-chip-pill]`) as HTMLElement;
    expect(pill('all').className).toContain('text-canvas');
    expect(pill('all').className).toContain('bg-ink');
    expect(pill('friends').className).toContain('text-ink');
    expect(pill('friends').className).toContain('bg-raised');
    expect(pill('friends').className).not.toContain('border-line-strong');
    expect(strip.querySelector('[data-chip-check], [data-tab-count], [data-tab-underline]')).toBeNull();
  });
});

describe('рядок розмови', () => {
  it('72, аватар 52 (група — сквіркл), імʼя 16/600, волосина вставлена після аватара', () => {
    mount([chat('o', 'Оксана', { unreadCount: 1 }), chat('g', 'Двір', { type: 'group' })]);
    expect(row('o').className).toContain('h-[72px]');
    const face = (id: string) => row(id).querySelector('[data-row-avatar] > *') as HTMLElement;
    expect(face('o').className).toContain('w-[52px] h-[52px]');
    expect(face('o').className).toContain('rounded-full');
    expect(face('g').className).toContain('rounded-control');
    const name = row('o').querySelector('[data-row-name]') as HTMLElement;
    expect(name.className).toMatch(/text-headline.*font-semibold/);
    const body = row('o').querySelector('[data-row-body]') as HTMLElement;
    expect(body.className).toContain('border-b border-line');
    expect(body.contains(name)).toBe(true);
    expect(body.contains(face('o'))).toBe(false);
  });

  it('непрочитане пряме: час ink-2, бурштин лише в лічильнику; прочитане: час text-3; превʼю — text-2 15', () => {
    mount([chat('o', 'Оксана', { unreadCount: 1 }), chat('t', 'Тарас')]);
    const time = (id: string) => row(id).querySelector('[data-row-time]') as HTMLElement;
    const pv = (id: string) => row(id).querySelector('[data-testid="chat-preview-line"]') as HTMLElement;
    expect(time('o').className).toContain('text-ink-2');
    expect(time('o').className).not.toContain('accent');
    expect(time('t').className).toContain('text-ink-3');
    expect(time('t').className).not.toContain('accent');
    expect(pv('o').className).toMatch(/text-callout.*text-ink-2/);
    expect(pv('t').className).toMatch(/text-callout.*text-ink-2/);
  });

  it('бурштин — лише прямі неприглушені; група — графіт, приглушене — тихе; понад три — решта графіт', () => {
    mount([
      chat('a', 'Аня', { unreadCount: 1 }),
      chat('b', 'Богдан', { unreadCount: 2 }),
      chat('c', 'Віра', { unreadCount: 3 }),
      chat('d', 'Гліб', { unreadCount: 4 }),
      chat('m', 'Мама', { unreadCount: 5, muted: true }),
      chat('g', 'Двір', { type: 'group', unreadCount: 6 }),
    ]);
    const badge = (id: string, n: string) => within(row(id)).getByText(n);
    const amber = ['a', 'b', 'c', 'd'].filter((id, i) => badge(id, String(i + 1)).className.includes('bg-accent'));
    expect(amber).toHaveLength(3);
    expect(badge('m', '5').className).toContain('bg-line-strong');
    expect(badge('g', '6').className).toContain('bg-ink-2');
    expect(badge('g', '6').className).toContain('text-canvas');
  });

  it('«друкує…» — text-2, не бурштин; «Чернетка:» — text-3', () => {
    mount([chat('o', 'Оксана'), chat('t', 'Тарас')]);
    act(() => {
      useMessengerStore.getState().setTypingStatus('o', 'Оксана');
      useMessengerStore.setState({ drafts: { t: 'ще думаю' } } as never);
    });
    const typing = within(row('o')).getByText('друкує…');
    expect(typing.className).toContain('text-ink-2');
    expect(typing.className).not.toContain('accent');
    const draft = within(row('t')).getByText('Чернетка:');
    expect(draft.className).toContain('text-ink-3');
  });

  it('мій останній лист: ✓ надіслано, ✓✓ прочитано — обидві text-3 біля часу; галочки не бурштинові', () => {
    mount([
      chat('s', 'Софія', { messages: [mine()] }),
      chat('r', 'Роман', { messages: [mine({ readAt: new Date().toISOString() })] }),
      chat('q', 'Іра', { messages: [mine({ status: 'sending' })] }),
      chat('p', 'Петро', { messages: [{ ...mine(), isSelf: false, senderName: 'Петро' } as Message] }),
    ]);
    const tick = (id: string) => row(id).querySelector('[data-row-tick]') as HTMLElement | null;
    expect(tick('s')?.getAttribute('data-row-tick')).toBe('sent');
    expect(tick('s')?.getAttribute('class')).toContain('text-ink-3');
    expect(tick('r')?.getAttribute('data-row-tick')).toBe('read');
    expect(tick('r')?.getAttribute('class')).toContain('text-ink-3');
    expect(tick('r')?.parentElement?.contains(row('r').querySelector('[data-row-time]'))).toBe(true);
    expect(tick('q')).toBeNull();
    expect(tick('p')).toBeNull();
  });

  it('у рядку немає тегу кола: про коло каже фільтр, не рядок', () => {
    mount([chat('g', 'Дизайн', { type: 'group', circle: 'work' }), chat('o', 'Оксана', { circle: 'close' }), ...fill(4)]);
    expect(document.querySelector('[data-row-circle]')).toBeNull();
  });
});

describe('час рядка', () => {
  const now = new Date(2026, 9, 8, 17, 42);
  it('24 год сьогодні, день тижня цього тижня, «3 Oct» — старше', () => {
    expect(todayTimeLabel(new Date(2026, 9, 8, 9, 5).toISOString(), now, 'en')).toBe('09:05');
    expect(todayTimeLabel(new Date(2026, 9, 6, 12, 0).toISOString(), now, 'en')).toBe('Tue');
    expect(todayTimeLabel(new Date(2026, 9, 3, 12, 0).toISOString(), now, 'en')).toBe('3 Oct');
    expect(todayTimeLabel(undefined, now, 'en')).toBe('');
  });
});

