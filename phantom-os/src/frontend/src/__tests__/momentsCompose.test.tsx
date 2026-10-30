/** moments-x 2: кілька фото одним моментом, текст на кольорі, «Близькі» одним дотиком, правка після публікації. */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return { ...real, request: bridge.request };
});
const media = vi.hoisted(() => ({ loadMedia: vi.fn(), encryptForUpload: vi.fn(), rememberUploadedMedia: vi.fn(), forgetMedia: vi.fn() }));
vi.mock('../services/messengerMedia', async (orig) => {
  const real = await orig<typeof import('../services/messengerMedia')>();
  return { ...real, ...media };
});
const api = vi.hoisted(() => ({ uploadFile: vi.fn() }));
vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return { ...real, messengerApi: { ...real.messengerApi, ...api } };
});
vi.mock('../services/imagePrep', async (orig) => {
  const real = await orig<typeof import('../services/imagePrep')>();
  return { ...real, prepareImage: vi.fn(async (f: File) => ({ file: f, width: 1080, height: 1440 })) };
});

import { MomentComposer } from '../components/messenger/MomentsStrip';
import { MomentsFeed } from '../components/messenger/MomentsFeed';
import { momentPeople } from '../components/messenger/MomentViewer';
import { readMoments, useMoments, type Moment } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat => ({
  id,
  title,
  type: 'dm',
  circle: 'all',
  avatar: '',
  unreadCount: 0,
  pinned: false,
  messages: [],
  lastSnippet: 'hi',
  lastAt: new Date().toISOString(),
  peerNodeId: id,
  ...extra,
});
const CHATS = [chat('olena', 'Olena', { circle: 'close' }), chat('mum', 'Mum', { circle: 'close' }), chat('taras', 'Taras', { circle: 'friends' })];

let posted: Record<string, unknown> | null = null;
let patched: Record<string, unknown> | null = null;
let blob = 0;
let served: unknown[] = [];
let oldPhone = false;

const row = (b: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({
  id: 'm'.repeat(32),
  from_id: 'me',
  from_name: '',
  mine: true,
  at_ms: Date.now(),
  until_ms: Date.now() + 86_400_000,
  keep: false,
  audience: b.audience ?? { kind: 'contacts' },
  text: b.text ?? '',
  seen: true,
  to_count: 0,
  media: (b.media as unknown[] | undefined) ?? [],
  ...(b.tone !== undefined ? { tone: b.tone } : {}),
  ...extra,
});

beforeEach(() => {
  localStorage.setItem('nightpost.moments.audienceChosen', '1');
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  useMoments.setState({ list: [], prefs: null });
  posted = null;
  patched = null;
  blob = 0;
  served = [];
  oldPhone = false;
  bridge.request.mockReset().mockImplementation((method: string, path: string, body?: unknown) => {
    if (path === '/messenger/moments/prefs') return Promise.resolve({ audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [], ...(oldPhone ? {} : { tones: 6 }) });
    if (method === 'GET' && path === '/messenger/moments') return Promise.resolve(served);
    if (method === 'POST' && path === '/messenger/moments') {
      posted = body as Record<string, unknown>;
      return Promise.resolve(row(posted));
    }
    if (method === 'PATCH') {
      patched = body as Record<string, unknown>;
      return Promise.resolve(row({ text: patched.text ?? 'old', tone: patched.tone, audience: patched.audience }, { edited_ms: Date.now() }));
    }
    return Promise.resolve({});
  });
  media.loadMedia.mockReset().mockResolvedValue('blob:x');
  media.encryptForUpload.mockReset().mockResolvedValue({ ciphertext: new Blob(['x']), keyHex: 'k'.repeat(64), nonceHex: 'n'.repeat(24), sha256: 's'.repeat(64) });
  media.rememberUploadedMedia.mockReset();
  api.uploadFile.mockReset().mockImplementation(async () => ({ blob_id: String(++blob).repeat(32), size: 1, sha256: 's'.repeat(64), state: 'stored' }));
});
afterEach(() => {
  cleanup();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

const compose = (extra: Partial<React.ComponentProps<typeof MomentComposer>> = {}) =>
  render(<MomentComposer file={null} people={momentPeople(CHATS)} chats={CHATS} onClose={vi.fn()} {...extra} />);
const pick = (files: File[]) => fireEvent.change(document.querySelector('[data-moment-input]')!, { target: { files } });
const photo = (n: string) => new File([n], `${n}.jpg`, { type: 'image/jpeg' });
const nums = () => [...document.querySelectorAll('[data-moment-num]')].map((e) => e.textContent);

describe('кілька фото одним моментом', () => {
  it('три фото — каруселлю з номерами, прибрати друге, публікуються два в тому ж порядку', async () => {
    compose();
    expect(document.querySelector('[data-moment-input]')!.hasAttribute('multiple')).toBe(true);
    pick([photo('a'), photo('b'), photo('c')]);
    expect(document.querySelectorAll('[data-moment-carousel] [data-moment-preview]')).toHaveLength(3);
    expect(nums()).toEqual(['1', '2', '3']);
    expect(document.querySelector('[data-moment-add-media]')).not.toBeNull();
    fireEvent.click(document.querySelectorAll('[data-moment-unpick]')[1]);
    expect(nums()).toEqual(['1', '2']);
    fireEvent.click(document.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(api.uploadFile).toHaveBeenCalledTimes(2);
    expect((posted!.media as { blob_id: string }[]).map((m) => m.blob_id)).toEqual(['1'.repeat(32), '2'.repeat(32)]);
    expect(posted!.tone).toBeUndefined();
  });

  it('понад чотири — лишаються чотири, плитки «додати» немає, сказано чому', () => {
    compose();
    pick([photo('a'), photo('b'), photo('c'), photo('d'), photo('e')]);
    expect(document.querySelectorAll('[data-moment-carousel] [data-moment-preview]')).toHaveLength(4);
    expect(document.querySelector('[data-moment-add-media]')).toBeNull();
    expect(screen.getByRole('alert').textContent).toContain('4');
  });
});

describe('текст на кольорі', () => {
  it('тон міняє картку й іде в тіло', async () => {
    compose();
    fireEvent.change(document.querySelector('[data-moment-caption]')!, { target: { value: 'на морі' } });
    await waitFor(() => expect(document.querySelectorAll('[data-moment-tone]')).toHaveLength(7));
    fireEvent.click(document.querySelector('[data-moment-tone="3"]')!);
    expect(document.querySelector('[data-moment-tone="3"]')!.getAttribute('aria-checked')).toBe('true');
    const card = document.querySelector('[data-moment-textcard]') as HTMLElement;
    expect(card.getAttribute('data-tone')).toBe('3');
    expect(card.style.backgroundColor).toContain('--ph-person-2');
    fireEvent.click(document.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toEqual(expect.objectContaining({ media: [], text: 'на морі', tone: 3 }));
  });

  it('з фото — без вибору тону', async () => {
    compose();
    await waitFor(() => expect(document.querySelector('[data-moment-tones]')).not.toBeNull());
    pick([photo('a')]);
    expect(document.querySelector('[data-moment-tones]')).toBeNull();
  });

  it('старий телефон без тонів — кольорів немає, тон не шлеться, текст публікується', async () => {
    oldPhone = true;
    compose();
    await waitFor(() => expect(useMoments.getState().prefs).not.toBeNull());
    fireEvent.change(document.querySelector('[data-moment-caption]')!, { target: { value: 'tea' } });
    expect(document.querySelector('[data-moment-tones]')).toBeNull();
    fireEvent.click(document.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted!.tone).toBeUndefined();
  });

  it('читання: тон 1..6 живий, чужий номер — без тону', () => {
    const base = { id: 'x'.repeat(32), keep: true, text: 'hi', media: [] };
    expect(readMoments([{ ...base, tone: 4 }])[0].tone).toBe(4);
    expect(readMoments([{ ...base, tone: 9 }])[0].tone).toBe(0);
  });
});

describe('«Близькі» одним дотиком', () => {
  it('з «Усі контакти» — один дотик ставить коло Близькі, чип зникає', async () => {
    compose();
    const quick = await waitFor(() => {
      const el = document.querySelector('[data-moment-quick-close]');
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    expect(quick.textContent).toContain('2');
    fireEvent.click(quick);
    expect(document.querySelector('[data-moment-quick-close]')).toBeNull();
    expect(document.querySelector('[data-moment-audience-label]')!.textContent).toMatch(/close/i);
    fireEvent.change(document.querySelector('[data-moment-caption]')!, { target: { value: 'tea' } });
    fireEvent.click(document.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted!.audience).toEqual({ kind: 'circles', circles: ['close'] });
    expect([...(posted!.to as string[])].sort()).toEqual(['mum', 'olena']);
  });
});

const own = (extra: Partial<Moment> = {}): Moment => ({
  id: 'o'.repeat(32),
  fromId: 'me',
  fromName: '',
  mine: true,
  atMs: Date.now() - 60_000,
  editedMs: 0,
  untilMs: Date.now() + 3_600_000,
  keep: false,
  reach: 'contacts',
  circles: [],
  text: 'old',
  tone: 0,
  seen: true,
  toCount: 3,
  media: [],
  viewers: [],
  reactions: {},
  myReaction: '',
  ...extra,
});

describe('правка після публікації', () => {
  it('підпис і тон — одним PATCH, аудиторія не чіпається, строк не показано', async () => {
    const onClose = vi.fn();
    compose({ editing: own(), onClose });
    expect(screen.getByRole('dialog').getAttribute('aria-label')).toBe('Edit moment');
    await waitFor(() => expect(document.querySelector('[data-moment-tone="5"]')).not.toBeNull());
    expect(document.querySelector('[data-testid="keep-day"]')).toBeNull();
    expect(document.querySelector('[data-moment-pick]')).toBeNull();
    expect((document.querySelector('[data-moment-caption]') as HTMLTextAreaElement).value).toBe('old');
    fireEvent.change(document.querySelector('[data-moment-caption]')!, { target: { value: 'new' } });
    fireEvent.click(document.querySelector('[data-moment-tone="5"]')!);
    expect(document.querySelector('[data-moment-share]')!.textContent).toBe('Save');
    fireEvent.click(document.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(patched).toEqual({ text: 'new', tone: 5 });
    expect(posted).toBeNull();
  });

  it('змінена аудиторія їде тим самим PATCH', async () => {
    const onClose = vi.fn();
    compose({ editing: own(), onClose });
    fireEvent.click(document.querySelector('[data-moment-quick-close]')!);
    fireEvent.click(document.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(patched).toEqual(expect.objectContaining({ audience: { kind: 'circles', circles: ['close'] } }));
    expect(patched!.text).toBeUndefined();
  });

  it('стрічка: олівець лише на своїх, відкриває правку, кольоровий текст — кольоровою карткою', async () => {
    useMessengerStore.setState({ chats: CHATS });
    const until = Date.now() + 3_600_000;
    served = [
      { id: 'o'.repeat(32), from_id: 'me', mine: true, at_ms: Date.now() - 60_000, until_ms: until, text: 'old', tone: 2, seen: true, audience: { kind: 'contacts' }, media: [] },
      { id: 't'.repeat(32), from_id: 'olena', mine: false, at_ms: Date.now() - 30_000, until_ms: until, text: 'hello', seen: false, audience: { kind: 'contacts' }, media: [] },
    ];
    useMoments.setState({ list: readMoments(served) });
    render(<MomentsFeed />);
    const mine = document.querySelector(`[data-feed-post="${'o'.repeat(32)}"]`)!;
    const theirs = document.querySelector(`[data-feed-post="${'t'.repeat(32)}"]`)!;
    expect(theirs.querySelector('[data-post-edit]')).toBeNull();
    expect(mine.querySelector('[data-post-tone="2"]')).not.toBeNull();
    fireEvent.click(mine.querySelector('[data-post-edit]')!);
    const dialog = await screen.findByRole('dialog', { name: 'Edit moment' });
    expect(dialog.getAttribute('data-moment-editing')).toBe('true');
    fireEvent.change(dialog.querySelector('[data-moment-caption]')!, { target: { value: 'fixed' } });
    fireEvent.click(dialog.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(patched).toEqual({ text: 'fixed' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit moment' })).toBeNull());
  });
});
