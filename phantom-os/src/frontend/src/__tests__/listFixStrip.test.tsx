/**
 * list-fix A → moments-x (20-moments MX-1): смуга моментів — ряд історій зверху
 * «Моментів», над чатами її більше немає. Лише люди зі свіжими моментами, свій
 * кружок першим («+», коли своїх немає), непобачене — бурштинове кільце,
 * побачене — сильна лінія. Смуги людей-адресатів зверху немає.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
const host = vi.hoisted(() => ({ compose: vi.fn(() => true) }));
vi.mock('../components/messenger/MomentsStrip', async (orig) => ({
  ...(await orig<typeof import('../components/messenger/MomentsStrip')>()),
  composeMomentInHost: host.compose,
}));
const composed = () => host.compose.mock.calls.length;
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMoments, type Moment } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const NOW = Date.now();

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    peerNodeId: `p_${id}`,
    messages: [],
    unreadCount: 0,
    lastSnippet: 'привіт',
    lastAt: new Date(NOW - 60_000).toISOString(),
    ...extra,
  }) as unknown as Chat;

const moment = (id: string, fromId: string, extra: Partial<Moment> = {}): Moment => ({
  id,
  fromId,
  fromName: fromId,
  mine: false,
  atMs: NOW - 600_000,
  editedMs: 0,
  untilMs: NOW + 3_600_000,
  keep: false,
  reach: 'contacts',
  circles: [],
  text: 'привіт',
  seen: false,
  toCount: 0,
  media: [],
  viewers: [],
  reactions: {},
  myReaction: '',
  ...extra,
});

const CHATS = [
  chat('chat_saved_notes', 'Збережене', { type: 'saved', circle: 'saved', peerNodeId: undefined } as Partial<Chat>),
  chat('olia', 'Оля Коваль'),
  chat('yana', 'Яна Ліщук'),
  chat('lake', 'На озеро', { type: 'group', peerNodeId: undefined } as Partial<Chat>),
];

const mount = () =>
  render(<PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);

const openPane = (root: HTMLElement) => {
  act(() => {
    fireEvent.click(root.querySelector('[data-pane-tab="moments"]')!);
  });
};

const realRefresh = useMoments.getState().refresh;
const realSeen = useMoments.getState().markSeen;
const setMoments = (list: Moment[] | null) => act(() => useMoments.setState({ list } as never));

beforeEach(() => {
  localStorage.clear();
  useSettingsStore.setState({ language: 'uk' } as never);
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useMoments.setState({ refresh: async () => undefined, markSeen: async () => undefined, list: null } as never);
});

afterEach(() => {
  cleanup();
  useMoments.setState({ list: null, refresh: realRefresh, markSeen: realSeen } as never);
});

describe('list-fix A: смуга моментів — ряд історій «Моментів»', () => {
  it('у «Чатах» смуги немає; у «Моментах» — першою над стрічкою; свій «+» першим, далі непобачені, побачені', () => {
    const { container } = mount();
    setMoments([moment('m2', 'p_yana', { seen: true }), moment('m1', 'p_olia')]);
    expect(container.querySelector('[data-list-moments]')).toBeNull();
    openPane(container);
    const scroll = container.querySelector('[data-moments-scroll]') as HTMLElement;
    const strip = scroll.firstElementChild as HTMLElement;
    expect(strip.matches('[data-list-moments]')).toBe(true);
    expect(strip.compareDocumentPosition(scroll.querySelector('[data-moments-feed]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    const tiles = [...strip.querySelectorAll('[data-list-moment]')];
    expect(tiles.map((el) => el.getAttribute('data-list-moment'))).toEqual(['me', 'p_olia', 'p_yana']);
    expect(tiles[0].hasAttribute('data-moment-own-add')).toBe(true);
    expect(tiles[1].lastElementChild!.textContent).toBe('Оля');
    expect(tiles[1].getAttribute('aria-label')).toContain('Оля Коваль');
  });

  it('непобачене — кільце чорнилом (бурштин лишається «+»), побачене — тонка лінія; без тіні, градієнта, сяйва', () => {
    const { container } = mount();
    setMoments([moment('m1', 'p_olia'), moment('m2', 'p_yana', { seen: true })]);
    openPane(container);
    const strip = container.querySelector('[data-list-moments]') as HTMLElement;
    const ring = (who: string) => strip.querySelector(`[data-list-moment="${who}"] [data-moment-ring]`) as HTMLElement;
    expect(ring('p_olia').className).toContain('border-ink');
    expect(ring('p_olia').className).toContain('border-2');
    expect(ring('p_olia').getAttribute('data-moment-ring')).toBe('unseen');
    expect(ring('p_yana').className).toContain('border-line-strong');
    expect(ring('p_yana').className).not.toContain('border-2');
    expect(strip.innerHTML).not.toContain('accent');
    expect(strip.innerHTML).not.toMatch(/shadow|gradient|blur|glow/);
  });

  it('гортається сама й тримає data-own-swipe — гортач сторінок її не забирає', () => {
    const { container } = mount();
    setMoments([moment('m1', 'p_olia')]);
    openPane(container);
    const strip = container.querySelector('[data-list-moments]') as HTMLElement;
    expect(strip.hasAttribute('data-own-swipe')).toBe(true);
    expect(strip.className).toContain('overflow-x-auto');
  });

  it('лише свіжі: залишений у профілі старший за добу не дає кружка; свої — кільце і малий «+», що відкриває новий момент', () => {
    const { container } = mount();
    setMoments([
      moment('old', 'p_yana', { keep: true, untilMs: 0, atMs: NOW - 3 * 86_400_000 }),
      moment('mine', 'me', { mine: true, seen: true }),
    ]);
    openPane(container);
    const strip = container.querySelector('[data-list-moments]') as HTMLElement;
    expect([...strip.querySelectorAll('[data-list-moment]')].map((el) => el.getAttribute('data-list-moment'))).toEqual(['me']);
    expect(strip.querySelector('[data-moment-own-add]')).toBeNull();
    expect(strip.querySelector('[data-list-moment="me"] [data-moment-ring]')).toBeTruthy();
    const plus = strip.querySelector('[data-moment-own-plus]') as HTMLElement;
    expect(plus.className).toContain('bg-ink');
    expect(plus.closest('[data-list-moment]')).toBeNull();
    const before = composed();
    fireEvent.click(plus);
    expect(composed()).toBe(before + 1);
  });

  it('нікого з моментами — у «Моментах» чесний порожній стан без підпису в смузі; «Збережене» й групи в смузі не бувають', () => {
    const { container } = mount();
    setMoments([]);
    openPane(container);
    expect(container.querySelector('[data-list-moments-empty]')).toBeNull();
    expect(container.querySelector('[data-feed-add-people]')).not.toBeNull();
    setMoments([moment('m1', 'p_olia')]);
    const ids = [...container.querySelectorAll('[data-list-moment]')].map((el) => el.getAttribute('data-list-moment'));
    expect(ids).toContain('p_olia');
    expect(ids).not.toContain('chat_saved_notes');
    expect(ids).not.toContain('lake');
  });

  it('смуги людей-адресатів зверху більше немає', () => {
    const { container } = mount();
    setMoments([moment('m1', 'p_olia')]);
    expect(container.querySelector('[data-recipient-strip]')).toBeNull();
  });

  it('дотик по кружку відкриває MomentViewer з першим непобаченим цієї людини', () => {
    const { container } = mount();
    setMoments([moment('a1', 'p_olia', { seen: true, atMs: NOW - 900_000 }), moment('a2', 'p_olia', { atMs: NOW - 300_000 }), moment('b1', 'p_yana')]);
    openPane(container);
    act(() => {
      fireEvent.click(container.querySelector('[data-list-moment="p_olia"]')!);
    });
    expect(document.querySelector('[data-moment-viewer="a2"]')).toBeTruthy();
  });
});
