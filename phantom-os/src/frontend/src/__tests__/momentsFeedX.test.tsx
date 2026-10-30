/**
 * moments-x · 1 «Стрічка» (20-moments §1–3): «Чати · Моменти» в шапці «Розмов»,
 * ряд історій зверху, далі пости за колами (Близькі першими), каруселі,
 * «Усе переглянуто» і «Старіші». Смуги над чатами більше немає.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return { ...real, request: bridge.request };
});
const media = vi.hoisted(() => ({ loadMedia: vi.fn() }));
vi.mock('../services/messengerMedia', async (orig) => {
  const real = await orig<typeof import('../services/messengerMedia')>();
  return { ...real, ...media };
});

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { MomentsFeed } from '../components/messenger/MomentsFeed';
import { buildFeed, unseenIds } from '../components/messenger/moments/feedModel';
import { readMoments, useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { usePhoneLayerStore } from '../stores/phoneLayerStore';
import { useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const NOW = Date.now();
const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, circle: string): Chat =>
  ({
    id, title, type: 'dm', circle, avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date(NOW - 60_000).toISOString(), peerNodeId: id,
  }) as unknown as Chat;
const CHATS = [chat('olena', 'Олена', 'friends'), chat('mum', 'Мама', 'close'), chat('taras', 'Тарас', 'work')];

const photo = (blob: string) => ({ name: 'p.jpg', size: 10, mime: 'image/jpeg', sha256: 'aa', blob_id: blob, key_hex: 'bb', nonce_hex: 'cc', state: 'ready' });
const row = (id: string, from: string, minAgo: number, extra: Record<string, unknown> = {}) => ({
  id, from_id: from, from_name: '', mine: from === 'me', at_ms: NOW - minAgo * 60_000, until_ms: NOW - minAgo * 60_000 + 86_400_000,
  keep: false, audience: { kind: 'contacts' }, text: '', seen: false, to_count: 0, media: [photo(id)],
  ...extra,
});

let moments: unknown[] = [];
const route = (method: string, path: string) => {
  if (path === '/messenger/moments' && method === 'GET') return Promise.resolve(moments);
  if (path === '/messenger/moments/prefs') return Promise.resolve({ audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [] });
  if (/^\/messenger\/moments\/[^/]+\/(seen|react)$/.test(path)) return Promise.resolve({});
  return Promise.reject(new Error('no bridge'));
};

beforeEach(() => {
  localStorage.clear();
  moments = [];
  useSettingsStore.setState({ language: 'uk' } as never);
  useMessengerStore.setState({ chats: CHATS, typingStatus: {}, drafts: {} } as never);
  useMoments.setState({ list: null, prefs: null });
  bridge.request.mockReset().mockImplementation(route);
  media.loadMedia.mockReset().mockImplementation(async (m: { blobId: string }) => `blob:${m.blobId}`);
});
afterEach(() => cleanup());

const posts = () => [...document.querySelectorAll('[data-feed-post]')].map((e) => e.getAttribute('data-feed-post'));
const ring = (id: string) => ({ mum: 1, olena: 2, taras: 4 })[id] ?? 5;

describe('модель стрічки', () => {
  it('нове — коло за колом (Близькі першими), свої окремо, побачене — у «Старіші»; чипи лише з колами, де є пости', () => {
    const list = readMoments([
      row('f1', 'olena', 5),
      row('c1', 'mum', 30),
      row('c2', 'mum', 3),
      row('w1', 'taras', 1, { seen: true }),
      row('me1', 'me', 2, { mine: true, seen: true }),
      row('x1', 'stranger', 4),
    ], NOW);
    const fresh = unseenIds(list);
    const f = buildFeed(list, ring, { filter: 'all', freshIds: fresh });
    expect(f.fresh.map((s) => [s.circle, s.posts.map((m) => m.id)])).toEqual([['close', ['c2', 'c1']], ['friends', ['f1']], [null, ['x1']]]);
    expect(f.mine.map((m) => m.id)).toEqual(['me1']);
    expect(f.older.map((s) => [s.circle, s.posts.map((m) => m.id)])).toEqual([['work', ['w1']]]);
    expect(f.circles).toEqual(['close', 'friends', 'work']);
    const close = buildFeed(list, ring, { filter: 'close', freshIds: fresh });
    expect(close.fresh.flatMap((s) => s.posts.map((m) => m.id))).toEqual(['c2', 'c1']);
    expect(close.mine).toEqual([]);
  });
});

describe('стрічка «Моменти»', () => {
  it('Близькі першими з лічильником нового; свої — «Ви»; «Усе переглянуто» і «Старіші · 1» відкривають побачене', async () => {
    moments = [row('f1', 'olena', 5), row('c1', 'mum', 30), row('w1', 'taras', 1, { seen: true }), row('me1', 'me', 2, { mine: true, seen: true })];
    render(<MomentsFeed />);
    await waitFor(() => expect(posts()).toEqual(['c1', 'f1', 'me1']));
    const sections = [...document.querySelectorAll('[data-feed-section]')];
    expect(sections.map((s) => s.getAttribute('data-feed-section'))).toEqual(['close', 'friends', 'me']);
    expect(sections[0].querySelector('h3')!.textContent).toBe('Близькі · 1 нове');
    expect(sections[2].querySelector('h3')!.textContent).toBe('Ви');
    expect(document.querySelector('[data-feed-end]')!.textContent).toContain('Усе переглянуто');
    fireEvent.click(document.querySelector('[data-feed-older]')!);
    expect(posts()).toEqual(['c1', 'f1', 'me1', 'w1']);
    expect(document.querySelector('[data-feed-older]')).toBeNull();
  });

  it('чипи кіл фільтрують стрічку; одне коло — без чипів', async () => {
    moments = [row('f1', 'olena', 5), row('c1', 'mum', 30)];
    render(<MomentsFeed />);
    await waitFor(() => expect(posts()).toEqual(['c1', 'f1']));
    expect([...document.querySelectorAll('[data-feed-chip]')].map((e) => e.getAttribute('data-feed-chip'))).toEqual(['all', 'close', 'friends']);
    fireEvent.click(document.querySelector('[data-feed-chip="friends"]')!);
    expect(posts()).toEqual(['f1']);
    cleanup();
    useMoments.setState({ list: null });
    moments = [row('c1', 'mum', 30)];
    render(<MomentsFeed />);
    await waitFor(() => expect(posts()).toEqual(['c1']));
    expect(document.querySelector('[data-feed-chips]')).toBeNull();
  });

  it('кілька фото — карусель: «1/3», три слайди, крапки; дотик по слайду відкриває перегляд', async () => {
    moments = [row('c1', 'mum', 3, { media: [photo('c1'), photo('c1b'), photo('c1c')], text: 'озеро' })];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelectorAll('[data-feed-post="c1"] [data-post-slide]')).toHaveLength(3));
    const post = document.querySelector('[data-feed-post="c1"]')!;
    expect(post.querySelector('[data-post-count]')!.textContent).toBe('1/3');
    expect(post.querySelector('[data-post-dots]')!.children).toHaveLength(3);
    expect(post.querySelector('[data-post-circle]')!.textContent).toBe('Близькі');
    fireEvent.click(post.querySelector('[data-post-slide="1"]')!);
    expect((await screen.findByRole('dialog')).textContent).toContain('Мама');
  });

  it('одне фото — без лічильника; лише текст — велика картка без медіа', async () => {
    moments = [row('c1', 'mum', 3), row('t1', 'olena', 4, { media: [], text: 'кава о 9?' })];
    render(<MomentsFeed />);
    await waitFor(() => expect(posts()).toHaveLength(2));
    expect(document.querySelector('[data-feed-post="c1"] [data-post-count]')).toBeNull();
    const txt = document.querySelector('[data-feed-post="t1"]')!;
    expect(txt.querySelector('[data-post-media]')).toBeNull();
    expect(txt.querySelector('[data-post-caption]')!.className).toContain('text-title-1');
  });
});

describe('«Чати · Моменти» у вкладці «Розмови»', () => {
  const mount = () =>
    render(<PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);

  it('шапка — два заголовки; смуги моментів над чатами немає; крапка — коли є непобачене', async () => {
    moments = [row('c1', 'mum', 3)];
    mount();
    await waitFor(() => expect(document.querySelector('[data-pane-dot]')).not.toBeNull());
    expect([...document.querySelectorAll('[data-pane-tab]')].map((e) => e.textContent)).toEqual(['Чати', 'Моменти']);
    expect(document.querySelector('[data-pane-tab="chats"]')!.getAttribute('aria-selected')).toBe('true');
    expect(document.querySelector('[data-list-moments]')).toBeNull();
  });

  it('«Моменти» — ряд історій зверху і стрічка; «+» бурштиновий; BACK повертає до чатів', async () => {
    moments = [row('c1', 'mum', 3), row('f1', 'olena', 6)];
    mount();
    await waitFor(() => expect(document.querySelector('[data-pane-dot]')).not.toBeNull());
    fireEvent.click(document.querySelector('[data-pane-tab="moments"]')!);
    const home = document.querySelector('[data-moments-home]') as HTMLElement;
    expect(home).not.toBeNull();
    const scroll = home.querySelector('[data-moments-scroll]')!;
    expect(scroll.firstElementChild!.matches('[data-list-moments]')).toBe(true);
    await waitFor(() => expect(posts()).toEqual(['c1', 'f1']));
    expect(home.querySelector('[data-feed-compose]')!.className).toContain('bg-accent');
    expect(document.querySelector('[data-pane-tab="moments"]')!.getAttribute('aria-selected')).toBe('true');
    act(() => usePhoneLayerStore.getState().layers['moments:pane']());
    expect(document.querySelector('[data-moments-home]')).toBeNull();
    expect(document.querySelector('[data-pane-tab="chats"]')!.getAttribute('aria-selected')).toBe('true');
  });
});
