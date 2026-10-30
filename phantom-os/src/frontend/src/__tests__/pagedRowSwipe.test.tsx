import { readFileSync } from 'node:fs';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { CIRCLES } from '../components/messenger/todayFormat';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useMoments, type Moment } from '../services/moments';
import type { Chat, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const setLocale = (l: 'uk' | 'en' | 'cs') => useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: l } });

afterEach(() => {
  cleanup();
  setLocale('uk');
});

const noop = () => {};
const realMomentsRefresh = useMoments.getState().refresh;
const freshMoment = (id: string, fromId: string): Moment => ({
  id, fromId, fromName: fromId, mine: false, atMs: Date.now() - 60_000, editedMs: 0, untilMs: Date.now() + 3_600_000,
  keep: false, reach: 'contacts', circles: [], text: 'привіт', seen: false, toCount: 0, media: [], viewers: [], reactions: {}, myReaction: '',
});
const USER = { id: 'me', name: 'Олег', avatar: '' } as unknown as UserProfile;

const makeChat = (patch: Partial<Chat> & Pick<Chat, 'id' | 'title'>): Chat =>
  ({
    avatar: '',
    type: 'direct',
    unreadCount: 0,
    messages: [],
    lastSnippet: 'Привіт',
    lastAt: '2026-10-14T10:00:00Z',
    ...patch,
  }) as Chat;

const buildPagedChats = (): Chat[] => [
  ...Array.from({ length: 6 }, (_, i) =>
    makeChat({
      id: `chat-${i}`,
      title: `Людина ${i + 1}`,
      unreadCount: i === 0 ? 2 : 0,
    }),
  ),
  makeChat({
    id: 'chat-circle',
    title: 'Колега',
    circle: CIRCLES[0],
  }),
];

const show = (chats: Chat[] = buildPagedChats()) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

const swipeHorizontal = (el: Element, dir: 1 | -1, from = 200) => {
  fireEvent.touchStart(el, { touches: [{ clientX: from, clientY: 300 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: from + dir * 20, clientY: 300 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: from + dir * 200, clientY: 300 }] });
  fireEvent.touchEnd(el);
};

describe('paged row swipes', () => {
  it('swiping right on an unread row in paged mode reveals read and pin actions without turning the page', () => {
    show();
    const pager = document.querySelector('[data-today-pager]');
    const track = document.querySelector('[data-today-track]');
    expect(pager).not.toBeNull();
    expect(track?.getAttribute('data-active-page')).toBe('all');

    const row = document.querySelector('[data-today-page="all"] [data-chat-row="chat-0"]')!;
    expect(row).not.toBeNull();
    swipeHorizontal(row, 1);

    expect(document.querySelector('[data-swipe-action="read"]')).not.toBeNull();
    expect(document.querySelector('[data-swipe-action="pin"]')).not.toBeNull();
    expect(track?.getAttribute('data-active-page')).toBe('all');
  });

  it('swiping left on a row in paged mode reveals mute and archive actions without turning the page', () => {
    show();
    const track = document.querySelector('[data-today-track]');
    const row = document.querySelector('[data-today-page="all"] [data-chat-row="chat-1"]')!;
    expect(row).not.toBeNull();
    swipeHorizontal(row, -1);

    expect(document.querySelector('[data-swipe-action="mute"]')).not.toBeNull();
    expect(document.querySelector('[data-swipe-action="archive"]')).not.toBeNull();
    expect(track?.getAttribute('data-active-page')).toBe('all');
  });

  it('U0: swiping right on a row of a later tab turns to the previous tab and opens no row actions', () => {
    show();
    const track = document.querySelector('[data-today-track]');
    fireEvent.click(document.querySelector('[data-filter-tab="unread"]')!);
    expect(track?.getAttribute('data-active-page')).toBe('unread');
    const row = document.querySelector('[data-today-page="unread"] [data-chat-row="chat-0"]')!;
    expect(row).not.toBeNull();
    swipeHorizontal(row, 1);
    expect(track?.getAttribute('data-active-page')).toBe('all');
    expect(document.querySelector('[data-swipe-action="read"]')).toBeNull();
  });

  it('U0: swiping left on a row of a later tab still reveals mute and archive', () => {
    show();
    const track = document.querySelector('[data-today-track]');
    fireEvent.click(document.querySelector('[data-filter-tab="unread"]')!);
    const row = document.querySelector('[data-today-page="unread"] [data-chat-row="chat-0"]')!;
    swipeHorizontal(row, -1);
    expect(document.querySelector('[data-swipe-action="archive"]')).not.toBeNull();
    expect(track?.getAttribute('data-active-page')).toBe('unread');
  });

  it('U0: a left swipe that starts in the right-edge strip of a row turns to the next tab', () => {
    show();
    const track = document.querySelector('[data-today-track]');
    const row = document.querySelector('[data-today-page="all"] [data-chat-row="chat-1"]')!;
    swipeHorizontal(row, -1, window.innerWidth - 10);
    expect(track?.getAttribute('data-active-page')).toBe('unread');
    expect(document.querySelector('[data-swipe-action="archive"]')).toBeNull();
  });

  it('swiping left on the pager outside rows turns the page to the next filter', () => {
    show();
    const pager = document.querySelector('[data-today-pager]')!;
    const track = document.querySelector('[data-today-track]');
    swipeHorizontal(pager, -1);

    expect(track?.getAttribute('data-active-page')).toBe('unread');
  });

  it('leaves no stale state after a row swipe so a subsequent pager swipe turns the page and closes the row', () => {
    show();
    const pager = document.querySelector('[data-today-pager]')!;
    const track = document.querySelector('[data-today-track]');
    const row = document.querySelector('[data-today-page="all"] [data-chat-row="chat-1"]')!;

    swipeHorizontal(row, -1);
    expect(document.querySelector('[data-swipe-action="mute"]')).not.toBeNull();
    expect(track?.getAttribute('data-active-page')).toBe('all');

    swipeHorizontal(pager, -1);
    expect(track?.getAttribute('data-active-page')).toBe('unread');
    expect(document.querySelector('[data-swipe-action="mute"]')).toBeNull();
  });
});

describe('горизонтальний жест забирає те, що в ньому гортається (hot-fix list-fix)', () => {
  it('у «Чатах» смуги моментів немає: гортач не ділить жест ні з чим чужим', () => {
    useMoments.setState({ refresh: async () => undefined, list: [freshMoment('m1', 'p_chat-1')] } as never);
    show(buildPagedChats().map((c) => ({ ...c, peerNodeId: `p_${c.id}` }) as Chat));
    expect(document.querySelector('[data-list-moments]')).toBeNull();
    expect(document.querySelector('[data-today-page="all"] [data-own-swipe]')).toBeNull();
    useMoments.setState({ list: null, refresh: realMomentsRefresh } as never);
  });

  it('рядок-тека не має свого жесту — свайп по ній гортає сторінку', () => {
    show([...buildPagedChats(), makeChat({ id: 'old', title: 'Курс', archived: true } as Partial<Chat> & Pick<Chat, 'id' | 'title'>)]);
    const track = document.querySelector('[data-today-track]');
    const folder = document.querySelector('[data-today-page="all"] [data-folder-row="archive"]');
    expect(folder).not.toBeNull();
    swipeHorizontal(folder!, -1);
    expect(track?.getAttribute('data-active-page')).toBe('unread');
  });

  it('смуга з власною прокруткою забирає жест і без позначки', async () => {
    const { ownsHorizontalSwipe } = await import('../utils/ownSwipe');
    const pager = document.createElement('div');
    const strip = document.createElement('div');
    strip.style.overflowX = 'auto';
    Object.defineProperty(strip, 'scrollWidth', { value: 800 });
    Object.defineProperty(strip, 'clientWidth', { value: 390 });
    const tile = document.createElement('button');
    strip.appendChild(tile);
    pager.appendChild(strip);
    expect(ownsHorizontalSwipe(tile, pager)).toBe(true);
    expect(ownsHorizontalSwipe(pager, pager)).toBe(false);
  });

  it('видима сторінка списку тримає вміст на власному шарі, сусідні — ні', () => {
    show();
    expect(document.querySelector('[data-today-page="all"]')?.hasAttribute('data-edge-layer')).toBe(true);
    expect(document.querySelectorAll('[data-edge-layer]').length).toBe(1);
  });
  const holdFrames = () => {
    const queue: FrameRequestCallback[] = [];
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb: FrameRequestCallback) => queue.push(cb));
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {});
    return () => act(() => { queue.splice(0).forEach((cb) => cb(performance.now())); });
  };
  const dragPager = (pager: Element, dx: number) => {
    fireEvent.touchStart(pager, { touches: [{ clientX: 200, clientY: 300 }] });
    fireEvent.touchMove(pager, { touches: [{ clientX: 200 + Math.sign(dx) * 20, clientY: 300 }] });
    fireEvent.touchMove(pager, { touches: [{ clientX: 200 + dx, clientY: 300 }] });
  };

  it('тяга сторінки: кадр пише зсув доріжки й риску вкладки, відпускання все прибирає', () => {
    const flush = holdFrames();
    show();
    const pager = document.querySelector('[data-today-pager]')!;
    const track = document.querySelector<HTMLElement>('[data-today-track]')!;
    dragPager(pager, -100);
    flush();
    expect(track.style.transition).toBe('none');
    expect(track.style.transform).toContain('-100px');
    expect(document.querySelector('[data-tab-drift]')).not.toBeNull();
    fireEvent.touchEnd(pager);
    expect(track.style.transition).toBe('');
    expect(track.getAttribute('data-active-page')).toBe('unread');
    expect(document.querySelector('[data-tab-drift]')).toBeNull();
    vi.restoreAllMocks();
  });

  it('тяга без touchend знімається, щойно сторінку змінив чип', () => {
    const flush = holdFrames();
    show();
    const pager = document.querySelector('[data-today-pager]')!;
    const track = document.querySelector<HTMLElement>('[data-today-track]')!;
    dragPager(pager, -100);
    flush();
    expect(document.querySelector('[data-tab-drift]')).not.toBeNull();
    fireEvent.click(document.querySelector('[data-filter-tab="unread"] button, button[data-filter-tab="unread"], [data-filter-tab="unread"]')!);
    expect(track.getAttribute('data-active-page')).toBe('unread');
    expect(track.style.transition).toBe('');
    expect(track.style.transform).toBe('translateX(calc(-100% + 0px))');
    expect(document.querySelector('[data-tab-drift]')).toBeNull();
    vi.restoreAllMocks();
  });

  it('сторінка списку сама жесту не забирає, навіть якщо вміст ширший', async () => {
    const { ownsHorizontalSwipe } = await import('../utils/ownSwipe');
    const pager = document.createElement('div');
    const page = document.createElement('div');
    page.setAttribute('data-today-page', 'all');
    page.style.overflowX = 'auto';
    Object.defineProperty(page, 'scrollWidth', { value: 800 });
    Object.defineProperty(page, 'clientWidth', { value: 390 });
    const row = document.createElement('div');
    page.appendChild(row);
    pager.appendChild(page);
    expect(ownsHorizontalSwipe(row, pager)).toBe(false);
  });

  it('правило шару для списку є в стилях', () => {
    const css = readFileSync(`${process.cwd()}/src/styles/globals.css`, 'utf8');
    expect(css).toMatch(/\[data-edge-layer\]\s*>\s*:first-child\s*\{\s*will-change:\s*transform/);
  });
});
