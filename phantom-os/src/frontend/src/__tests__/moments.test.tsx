/** Моменти v2: смуга на Today, перегляд з правкою/видаленням/«хто бачив»/смайлами, створення з аудиторією й строком, сторінка «Моменти». */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';

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
const api = vi.hoisted(() => ({ uploadFile: vi.fn(), appendMessage: vi.fn(), pulse: vi.fn(), peerProfiles: vi.fn() }));
vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return { ...real, messengerApi: { ...real.messengerApi, ...api } };
});
vi.mock('../services/imagePrep', async (orig) => {
  const real = await orig<typeof import('../services/imagePrep')>();
  return { ...real, prepareImage: vi.fn(async (f: File) => ({ file: f, width: 1080, height: 1440 })) };
});

import { MomentComposer, MomentsStrip, momentCircles } from '../components/messenger/MomentsStrip';
import { MomentsFeed } from '../components/messenger/MomentsFeed';
import { flushRemoval, undoRemoval } from '../services/momentsUndo';
import { useArchive } from '../services/momentsArchive';
import { heldIds } from '../services/moments';
import { momentHighlights, readMoments, stripOrder, useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { usePhoneLayerStore } from '../stores/phoneLayerStore';
import { MomentViewer } from '../components/messenger/MomentViewer';
import { readMoments as parse } from '../services/moments';
import type { Chat } from '../types/messenger';

const NOW = Date.now();
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'hi', lastAt: new Date(NOW).toISOString(), peerNodeId: id, ...extra,
  }) as unknown as Chat;
const CHATS = [chat('olena', 'Olena'), chat('mum', 'Mum'), chat('taras', 'Taras')];

const photo = (blob: string, extra: Record<string, unknown> = {}) => ({ name: 'p.jpg', size: 10, mime: 'image/jpeg', sha256: 'aa', blob_id: blob, key_hex: 'bb', nonce_hex: 'cc', state: 'ready', ...extra });
const row = (id: string, from: string, minAgo: number, extra: Record<string, unknown> = {}) => ({
  id, from_id: from, from_name: '', mine: from === 'me', at_ms: NOW - minAgo * 60_000, until_ms: NOW - minAgo * 60_000 + 86_400_000,
  keep: false, audience: { kind: 'contacts' }, text: '', seen: false, to_count: 0, media: [photo(id)],
  ...extra,
});

let moments: unknown[] = [];
let posted: unknown = null;
let prefs: unknown = { audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [] };
const calls: [string, string, unknown?][] = [];
const route = (method: string, path: string, body?: unknown) => {
  calls.push([method, path, body]);
  if (path === '/messenger/moments' && method === 'GET') return Promise.resolve(moments);
  if (path === '/messenger/moments/prefs') return Promise.resolve(prefs);
  if (path === '/messenger/moments' && method === 'POST') {
    posted = body;
    const b = body as { text: string; media: unknown[] };
    return Promise.resolve(row('n'.repeat(32), 'me', 0, { mine: true, seen: true, to_count: 3, text: b.text, media: b.media.length ? [photo('n'.repeat(32))] : [] }));
  }
  if (method === 'PATCH') {
    const id = path.split('/').pop() as string;
    const b = body as { text?: string; audience?: unknown; to?: string[] };
    if (b.audience) {
      if (b.to && b.to.length === 0) return Promise.reject(Object.assign(new Error('no_one'), { code: 'no_one' }));
      const cur = (moments as Record<string, unknown>[]).find((m) => m.id === id) ?? {};
      return Promise.resolve(row(id, 'me', 1, { ...cur, mine: true, seen: true, audience: b.audience, to: b.to ?? [], to_count: (b.to ?? []).length || 3 }));
    }
    return Promise.resolve(row(id, 'me', 1, { mine: true, seen: true, text: b.text, edited_ms: NOW }));
  }
  if (method === 'DELETE') return Promise.resolve({ deleted: true });
  if (/^\/messenger\/moments\/[^/]+\/(seen|react)$/.test(path)) return Promise.resolve({});
  if (path.startsWith('/messenger/moments/mute/')) return Promise.resolve({ muted: true });
  if (path.startsWith('/messenger/moments/highlights/')) return Promise.resolve([row('k', 'me', 60 * 30, { mine: true, keep: true, until_ms: 0, text: 'summer' })]);
  return Promise.reject(new Error('no bridge'));
};

const renderList = (chats = CHATS, onSelectChat = vi.fn()) => {
  return render(<MomentsStrip chats={chats} onOpenChat={onSelectChat} variant="band" />);
};

beforeEach(() => {
  undoRemoval();
  heldIds.clear();
  useArchive.setState({ items: [] });
  moments = [];
  posted = null;
  calls.length = 0;
  prefs = { audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [] };
  useMoments.setState({ list: null, prefs: null });
  bridge.request.mockReset().mockImplementation(route);
  media.loadMedia.mockReset().mockImplementation(async (m: { blobId: string }) => `blob:${m.blobId}`);
  media.encryptForUpload.mockReset().mockResolvedValue({ ciphertext: new Blob(['x']), keyHex: 'k'.repeat(64), nonceHex: 'n'.repeat(24), sha256: 's'.repeat(64) });
  media.rememberUploadedMedia.mockReset();
  media.forgetMedia.mockReset();
  api.uploadFile.mockReset().mockResolvedValue({ blob_id: 'n'.repeat(32), size: 1, sha256: 's'.repeat(64), state: 'stored' });
  api.appendMessage.mockReset().mockResolvedValue({});
  api.pulse.mockReset().mockRejectedValue(new Error('no bridge'));
  api.peerProfiles.mockReset().mockRejectedValue(new Error('no bridge'));
});
afterEach(() => cleanup());

const tiles = () => [...document.querySelectorAll('[data-moment-tile]')].map((e) => e.getAttribute('data-moment-tile'));


describe('дріт моментів', () => {
  it('прострочене й без ключа — геть; текст без фото і «залишене» — живі; порядок: свої, непобачені свіжі, побачені', () => {
    const list = readMoments([
      row('a', 'olena', 30, { seen: true }),
      row('b', 'mum', 10),
      row('c', 'taras', 5),
      row('d', 'me', 60, { mine: true, seen: true }),
      row('old', 'taras', 60 * 25),
      row('kept', 'taras', 60 * 50, { keep: true, until_ms: 0, seen: true }),
      row('txt', 'mum', 2, { media: [], text: 'ранок', seen: true }),
      row('nokey', 'taras', 1, { media: [{ blob_id: 'x' }] }),
    ], NOW);
    expect(list.map((m) => m.id).sort()).toEqual(['a', 'b', 'c', 'd', 'kept', 'txt']);
    expect(stripOrder(list).map((m) => m.id)).toEqual(['d', 'c', 'b', 'txt', 'a', 'kept']);
  });

  it('Highlights профілю — з моста, порожньо без моста', async () => {
    expect((await momentHighlights('me')).map((m) => m.text)).toEqual(['summer']);
    expect(calls.at(-1)?.[1]).toBe('/messenger/moments/highlights/me');
    bridge.request.mockImplementation(() => Promise.reject(new Error('404')));
    expect(await momentHighlights('olena')).toEqual([]);
  });
});

describe('смуга на Today', () => {
  it('смуга шторки: «+» розміром з аватар, далі люди з кільцем — непобачене чорнилом, побачене лінією', async () => {
    moments = [row('a', 'olena', 30, { seen: true }), row('b', 'mum', 10, { media: [], text: 'кава?' }), row('c', 'taras', 5)];
    renderList();
    await waitFor(() => expect(tiles()).toEqual(['c', 'b', 'a']));
    const strip = document.querySelector('[data-moments]') as HTMLElement;
    expect(strip.querySelector('[data-moment-add]')).toBe(strip.querySelector('li button'));
    expect(document.querySelector('[data-moments-pill]')).toBeNull();
    for (const ring of strip.querySelectorAll('[data-moment-add] [data-moment-ring], [data-moment-tile] [data-moment-ring]')) {
      expect(ring.className).toContain('w-[62px] h-[62px]');
    }
    expect(strip.querySelectorAll('[data-moment-ring]')).toHaveLength(4);
    expect(strip.innerHTML).not.toMatch(/h-\[106px\]/);
    expect(document.querySelector('[data-moment-tile="c"] [data-moment-ring]')!.getAttribute('data-ring')).toBe('unseen');
    expect(document.querySelector('[data-moment-tile="a"] [data-moment-ring]')!.getAttribute('data-ring')).toBe('seen');
    expect(document.querySelector('[data-moment-tile="b"] [data-moment-textcard]')).toBeNull();
    expect(strip.textContent).toContain('Olena');
  });

  it('ні в кого нічого — у смузі шторки лише «Додати», без речення «нічого нового»', async () => {
    renderList();
    await waitFor(() => expect(document.querySelector('[data-moments-band]')).not.toBeNull());
    const add = document.querySelector('[data-moment-add]') as HTMLElement;
    expect(add.textContent).toContain('Додати');
    expect(document.querySelector('[data-moments-slim]')).toBeNull();
    expect(document.querySelector('[data-moments-open]')).toBeNull();
    expect(document.querySelector('[data-moments-empty]')).toBeNull();
    expect(document.body.textContent).not.toContain('Нічого нового');
    expect(tiles()).toEqual([]);
    const pill = document.querySelector('[data-moments-pill]') as HTMLElement;
    expect(pill).toBeTruthy();
    expect(pill.querySelector('[data-moment-ring]')).toBeNull();
    expect(add.className).toContain('h-11');
    expect(add.className).toContain('bg-raised');
    expect(add.className).not.toMatch(/bg-accent/);
    fireEvent.click(add);
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });

  it('B3-M: рідний вхід «Додати момент» — __phantomComposeMoment відкриває той самий композер; після зняття смуги його немає', async () => {
    const w = window as unknown as { __phantomComposeMoment?: () => boolean };
    const { unmount } = renderList();
    await waitFor(() => expect(document.querySelector('[data-moments]')).not.toBeNull());
    expect(typeof w.__phantomComposeMoment).toBe('function');
    expect(screen.queryByRole('dialog')).toBeNull();
    let ok = false;
    act(() => {
      ok = w.__phantomComposeMoment!();
    });
    expect(ok).toBe(true);
    const sheet = await screen.findByRole('dialog');
    expect(sheet.hasAttribute('data-moment-composer')).toBe(true);
    unmount();
    expect(w.__phantomComposeMoment).toBeUndefined();
  });

  it('одна плитка на людину: два свої — одне «Ви», перегляд іде від першого непобаченого через усі', async () => {
    moments = [
      row('me1', 'me', 90, { mine: true, seen: true }), row('me2', 'me', 20, { mine: true, seen: true }),
      row('o1', 'olena', 40, { seen: true, text: 'перший' }), row('o2', 'olena', 8, { text: 'другий' }),
    ];
    renderList();
    await waitFor(() => expect(tiles()).toHaveLength(2));
    const labels = [...document.querySelectorAll('[data-moment-tile]')].map((e) => e.textContent);
    expect(labels.filter((l) => l === 'Ви')).toHaveLength(1);
    expect(document.querySelectorAll('[data-moment-tile][data-person="olena"]')).toHaveLength(1);
    fireEvent.click(document.querySelector('[data-moment-tile][data-person="olena"]')!);
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('другий');
  });

  it('свої є, нового від інших нема — смуга без жодного речення', async () => {
    moments = [row('me1', 'me', 20, { mine: true, seen: true })];
    renderList();
    await waitFor(() => expect(tiles()).toHaveLength(1));
    expect(document.querySelector('[data-moments-slim]')).toBeNull();
    expect(document.querySelector('[data-moments-empty]')).toBeNull();
    expect(document.querySelector('[data-moments-open]')).not.toBeNull();
  });

  it('міст моментів не знає — смуги немає зовсім', async () => {
    bridge.request.mockImplementation(() => Promise.reject(new Error('404')));
    renderList();
    await act(async () => {});
    expect(document.querySelector('[data-moments]')).toBeNull();
  });
});

const openTile = async (id: string) => {
  await waitFor(() => expect(tiles()).toContain(id));
  fireEvent.click(document.querySelector(`[data-moment-tile="${id}"]`)!);
  return screen.findByRole('dialog');
};

describe('перегляд чужого', () => {
  it('фото з іменем і підписом, позначка «бачив», крок вперед; назви чужих кіл не видно', async () => {
    moments = [row('b', 'mum', 10, { text: 'first frost', audience: { kind: 'circles', circles: ['close'] } }), row('c', 'olena', 5)];
    renderList();
    const viewer0 = await openTile('c');
    expect(viewer0.getAttribute('data-moment-viewer')).toBe('c');
    await waitFor(() => expect(bridge.request).toHaveBeenCalledWith('POST', '/messenger/moments/c/seen'));
    fireEvent.click(viewer0.querySelector('[data-moment-next]')!);
    const viewer = screen.getByRole('dialog');
    expect(viewer.getAttribute('data-moment-viewer')).toBe('b');
    expect(viewer.textContent).toContain('first frost');
    expect(viewer.querySelector('[data-moment-audience]')!.textContent).toBe('Для їхніх людей');
    await waitFor(() => expect(viewer.querySelector('[data-moment-photo]')?.getAttribute('src')).toBe('blob:b'));
    fireEvent.click(viewer.querySelector('[data-moment-close]')!);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('текстовий момент — картка на весь екран', async () => {
    moments = [row('t', 'olena', 5, { media: [], text: 'їду на море' })];
    renderList();
    const viewer = await openTile('t');
    expect(viewer.querySelector('[data-moment-textcard]')!.textContent).toBe('їду на море');
    expect(viewer.querySelector('[data-moment-photo]')).toBeNull();
  });

  it('батько, що перемальовується від шарів BACK, не заганяє перегляд у петлю (A52-оболонка)', () => {
    const list = parse([row('c', 'olena', 5)], NOW);
    const Shell = () => {
      usePhoneLayerStore((s) => s.layers);
      return <MomentViewer moments={list} startId="c" nameOf={() => 'Olena'} avatarOf={() => undefined} chatOf={() => 'olena'} onOpenChat={() => {}} onClose={() => {}} />;
    };
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<Shell />);
    expect(Object.keys(usePhoneLayerStore.getState().layers)).toContain('moments:viewer');
    spy.mockRestore();
  });

  it('відповідь іде листом лише цій людині', async () => {
    moments = [row('c', 'olena', 5)];
    renderList();
    const input = (await openTile('c')).querySelector('[data-moment-reply]') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'looks great' } });
    fireEvent.submit(input.closest('form')!);
    await waitFor(() => expect(api.appendMessage).toHaveBeenCalledWith('olena', expect.objectContaining({ body: 'looks great\n[np:moment id=c k=photo]', kind: 'text' })));
    expect(await screen.findByText('Відповідь надіслано · бачить лише Olena')).toBeTruthy();
  });

  it('смайл — один із шести, іде автору, вдруге знімається', async () => {
    moments = [row('c', 'olena', 5)];
    renderList();
    const viewer = await openTile('c');
    fireEvent.click(viewer.querySelector('[data-moment-react-open]')!);
    expect(viewer.querySelectorAll('[data-moment-react]').length).toBe(6);
    fireEvent.click(viewer.querySelector('[data-moment-react="🔥"]')!);
    await waitFor(() => expect(bridge.request).toHaveBeenCalledWith('POST', '/messenger/moments/c/react', { emoji: '🔥' }));
    expect(viewer.querySelector('[data-moment-react-open]')!.textContent).toBe('🔥');
    fireEvent.click(viewer.querySelector('[data-moment-react-open]')!);
    fireEvent.click(viewer.querySelector('[data-moment-react="🔥"]')!);
    await waitFor(() => expect(bridge.request).toHaveBeenCalledWith('POST', '/messenger/moments/c/react', { emoji: '' }));
  });

  it('⋮ → «Не показувати моменти» приглушує людину', async () => {
    moments = [row('c', 'olena', 5)];
    renderList();
    const viewer = await openTile('c');
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    expect(viewer.querySelector('[data-moment-edit]')).toBeNull();
    fireEvent.click(viewer.querySelector('[data-moment-mute]')!);
    await waitFor(() => expect(bridge.request).toHaveBeenCalledWith('POST', '/messenger/moments/mute/olena', { on: true }));
    await waitFor(() => expect(tiles()).toEqual([]));
  });

  it('T30: ⋮ — унизу під пальцем, поруч зі смайлом; угорі лише закрити', async () => {
    moments = [row('c', 'olena', 5)];
    renderList();
    const viewer = await openTile('c');
    const more = viewer.querySelector('[data-moment-more]')!;
    expect(more.parentElement).toBe(viewer.querySelector('[data-moment-react-open]')!.parentElement);
    expect(viewer.querySelector('[data-moment-close]')!.parentElement!.querySelector('[data-moment-more]')).toBeNull();
  });
});

describe('свій момент', () => {
  const mine = (extra: Record<string, unknown> = {}) =>
    row('d', 'me', 3, { mine: true, seen: true, to_count: 4, text: 'кава', audience: { kind: 'circles', circles: ['close', 'work'] }, ...extra });

  it('рядок аудиторії називає кола й скільки людей', async () => {
    moments = [mine()];
    renderList();
    const viewer = await openTile('d');
    expect(viewer.querySelector('[data-moment-audience]')!.textContent).toMatch(/·\s*4$/);
    expect(viewer.querySelector('[data-moment-audience]')!.textContent).not.toContain('close');
    expect(viewer.querySelector('[data-moment-reply]')).toBeNull();
  });

  it('«Лише ви» і «Усі контакти» — словами', async () => {
    moments = [mine({ audience: { kind: 'me' }, to_count: 0 })];
    renderList();
    expect((await openTile('d')).querySelector('[data-moment-audience]')!.textContent).toBe('Лише ви');
  });

  it('T30: свій ⋮ — у нижньому рядку з «Хто бачив»', async () => {
    moments = [mine()];
    renderList();
    const viewer = await openTile('d');
    expect(viewer.querySelector('[data-moment-more]')!.parentElement).toBe(viewer.querySelector('[data-moment-seenby]')!.parentElement);
  });

  it('⋮ → змінити підпис: PATCH і новий текст на екрані', async () => {
    moments = [mine()];
    renderList();
    const viewer = await openTile('d');
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    fireEvent.click(viewer.querySelector('[data-moment-edit]')!);
    const area = viewer.querySelector('[data-moment-edit-text]') as HTMLTextAreaElement;
    expect(area.value).toBe('кава');
    fireEvent.change(area, { target: { value: 'чай' } });
    fireEvent.click(viewer.querySelector('[data-moment-edit-save]')!);
    await waitFor(() => expect(bridge.request).toHaveBeenCalledWith('PATCH', '/messenger/moments/d', { text: 'чай' }));
    await waitFor(() => expect(viewer.querySelector('[data-moment-caption-label]')!.textContent).toBe('чай'));
    expect(viewer.textContent).toContain('змінено');
  });

  it('⋮ → видалити: момент зникає зі смуги, вузол питає лише після відкату; «Скасувати» повертає', async () => {
    moments = [mine()];
    renderList();
    const viewer = await openTile('d');
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    fireEvent.click(viewer.querySelector('[data-moment-delete]')!);
    await waitFor(() => expect(tiles()).toEqual([]));
    expect(calls.some(([m]) => m === 'DELETE')).toBe(false);
    fireEvent.click(await screen.findByText('Скасувати'));
    await waitFor(() => expect(tiles()).toEqual(['d']));
    expect(calls.some(([m]) => m === 'DELETE')).toBe(false);
  });

  it('⋮ → видалити: після відкату йде DELETE; «В архів» теж знімає момент і лишає слід в архіві', async () => {
    moments = [mine()];
    renderList();
    const viewer = await openTile('d');
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    fireEvent.click(viewer.querySelector('[data-moment-archive]')!);
    await waitFor(() => expect(tiles()).toEqual([]));
    await act(async () => {
      await flushRemoval();
    });
    expect(bridge.request).toHaveBeenCalledWith('DELETE', '/messenger/moments/d');
    expect(useArchive.getState().items.some((x) => x.id === 'd' && x.manual)).toBe(true);
  });

  it('«Хто бачив»: люди, час і смайл; нікого — чесно', async () => {
    moments = [mine({ viewers: [{ id: 'olena', name: 'Olena', at_ms: NOW - 60_000, emoji: '❤️' }], reactions: { '❤️': 1 } })];
    renderList();
    const viewer = await openTile('d');
    const seenBy = viewer.querySelector('[data-moment-seenby]') as HTMLElement;
    expect(seenBy.textContent).toContain('Бачили: 1');
    expect(seenBy.textContent).toContain('❤️');
    fireEvent.click(seenBy);
    const r = viewer.querySelector('[data-moment-viewer-row="olena"]') as HTMLElement;
    expect(r.textContent).toContain('Olena');
    expect(r.textContent).toContain('❤️');
    cleanup();
    useMoments.setState({ list: null });
    moments = [mine()];
    renderList();
    const v2 = await openTile('d');
    fireEvent.click(v2.querySelector('[data-moment-seenby]')!);
    expect(v2.querySelector('[data-moment-viewers-empty]')).not.toBeNull();
  });
});

describe('створення', () => {
  const open = async () => {
    await waitFor(() => expect(document.querySelector('[data-moment-add]')).not.toBeNull());
    fireEvent.click(document.querySelector('[data-moment-add]')!);
    return screen.findByRole('dialog');
  };
  const pickFile = (sheet: HTMLElement, file: File) => fireEvent.change(sheet.querySelector('[data-moment-input]')!, { target: { files: [file] } });

  it('лише текст — без фото: картка, усі контакти за замовчуванням, 24 години', async () => {
    renderList();
    const sheet = await open();
    expect((sheet.querySelector('[data-moment-share]') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: '  на морі  ' } });
    expect(sheet.querySelector('[data-moment-textcard]')!.textContent).toBe('на морі');
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).toEqual({ media: [], text: 'на морі', keep: false, audience: { kind: 'contacts' } }));
    expect(api.uploadFile).not.toHaveBeenCalled();
  });

  it('фото: шифротекст у каталог моментів, «залишити на профілі»', async () => {
    renderList();
    const sheet = await open();
    pickFile(sheet, new File(['img'], 'p.jpg', { type: 'image/jpeg' }));
    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'bread' } });
    fireEvent.click(sheet.querySelector('[data-testid="keep-profile"]')!);
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(api.uploadFile).toHaveBeenCalledWith('moments', expect.any(Blob));
    expect(posted).toEqual(expect.objectContaining({ text: 'bread', keep: true, media: [expect.objectContaining({ blob_id: 'n'.repeat(32), key_hex: 'k'.repeat(64), mime: 'image/jpeg' })] }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('відео без тривалості чи понад 15 с — чесна відмова, нічого не вивантажено', async () => {
    renderList();
    const sheet = await open();
    pickFile(sheet, new File(['vid'], 'v.mp4', { type: 'video/mp4' }));
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(api.uploadFile).not.toHaveBeenCalled();
    expect(posted).toBeNull();
  });

  it('драбина аудиторії: «Усі» схована без публічного профілю; кола — кілька, лише з людьми', async () => {
    const chats = [chat('olena', 'Olena', { circle: 'personal' }), chat('mum', 'Mum', { circle: 'personal' }), chat('taras', 'Taras', { circle: 'work' })];
    renderList(chats);
    const sheet = await open();
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    expect([...document.querySelectorAll('[role="radiogroup"] [data-testid^="aud-"]')].map((e) => e.getAttribute('data-testid'))).toEqual(['aud-me', 'aud-people', 'aud-circles', 'aud-contacts']);
    fireEvent.click(document.querySelector('[data-testid="aud-circles"]')!);
    expect(document.querySelector('[data-testid="aud-circle-friends"]')).toBeNull();
    fireEvent.click(document.querySelector('[data-testid="aud-circle-personal"]')!);
    fireEvent.click(document.querySelector('[data-testid="aud-circle-work"]')!);
    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'т' } });
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).toEqual(expect.objectContaining({ audience: { kind: 'circles', circles: ['personal', 'work'] }, to: ['olena', 'mum', 'taras'] })));
    expect(momentCircles(chats)).toEqual([{ name: 'personal', ids: ['olena', 'mum'] }, { name: 'work', ids: ['taras'] }]);
  });

  it('памʼятає останню аудиторію: вибрані люди знову вибрані', async () => {
    prefs = { audience: { kind: 'people' }, to: ['mum'], keep: true, everyone: false, muted: [] };
    renderList();
    const sheet = await open();
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-people"]')!.getAttribute('aria-checked')).toBe('true'));
    expect((document.querySelector('[data-moment-person="mum"]') as HTMLInputElement).checked).toBe(true);
    expect(sheet.querySelector('[data-testid="keep-profile"]')!.getAttribute('aria-pressed')).toBe('true');
  });

  it('«Лише я» — можна й без жодного чату; інше без людей — ні', async () => {
    renderList([chat('g', 'Friday pho', { type: 'group', peerNodeId: undefined })]);
    const sheet = await open();
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'нотатка' } });
    await waitFor(() => expect(sheet.querySelector('[data-moment-noone]')).not.toBeNull());
    expect((sheet.querySelector('[data-moment-share]') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(document.querySelector('[data-testid="aud-me"]')!);
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(posted).toEqual(expect.objectContaining({ audience: { kind: 'me' } })));
  });

  it('preset із зустрічі: аудиторія — саме ці люди, памʼять не перебиває', async () => {
    prefs = { audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [] };
    const people = [{ id: 'olena', name: 'Olena', chatId: 'olena' }, { id: 'mum', name: 'Mum', chatId: 'mum' }];
    render(<MomentComposer file={new File(['img'], 'p.jpg', { type: 'image/jpeg' })} people={people} chats={CHATS} onClose={vi.fn()} preset={{ ids: ['mum'] }} />);
    const sheet = await screen.findByRole('dialog');
    await act(async () => {});
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    expect(document.querySelector('[data-testid="aud-people"]')!.getAttribute('aria-checked')).toBe('true');
    expect((document.querySelector('[data-moment-person="mum"]') as HTMLInputElement).checked).toBe(true);
    expect((document.querySelector('[data-moment-person="olena"]') as HTMLInputElement).checked).toBe(false);
  });
});

describe('сторінка «Моменти»', () => {
  beforeEach(() => useMessengerStore.setState({ chats: CHATS } as never));

  it('заголовок смуги відкриває сторінку «Моменти» (одна назва), BACK і стрілка закривають', async () => {
    moments = [row('a', 'olena', 30, { seen: true }), row('b', 'mum', 10)];
    renderList();
    await waitFor(() => expect(tiles()).toEqual(['b', 'a']));
    fireEvent.click(document.querySelector('[data-moments-open]')!);
    const page = await screen.findByRole('dialog');
    expect(page.querySelector('h2')!.textContent).toBe('Моменти');
    expect([...page.querySelectorAll('[data-feed-post]')].map((e) => e.getAttribute('data-feed-post'))).toEqual(['b']);
    fireEvent.click(page.querySelector('[data-feed-older]')!);
    expect([...page.querySelectorAll('[data-feed-post]')].map((e) => e.getAttribute('data-feed-post'))).toEqual(['b', 'a']);
    act(() => usePhoneLayerStore.getState().layers['moments:feed']());
    expect(document.querySelector('[data-moments-page]')).toBeNull();
    fireEvent.click(document.querySelector('[data-moments-open]')!);
    fireEvent.click((await screen.findByRole('dialog')).querySelector('[data-moments-back]')!);
    expect(document.querySelector('[data-moments-page]')).toBeNull();
  });

  it('порожньо — чесний рядок', async () => {
    render(<MomentsFeed />);
    await act(async () => {});
    expect(screen.getByText('Поки порожньо')).toBeTruthy();
    expect(document.querySelector('[data-feed-add-people]')).not.toBeNull();
  });

  it('нове першим, далі свої; побачене — за «Старіші»; дотик відкриває перегляд', async () => {
    moments = [row('a', 'olena', 30, { seen: true }), row('b', 'mum', 10), row('d', 'me', 1, { mine: true, seen: true })];
    render(<MomentsFeed />);
    await waitFor(() => expect([...document.querySelectorAll('[data-feed-post]')].map((e) => e.getAttribute('data-feed-post'))).toEqual(['b', 'd']));
    fireEvent.click(document.querySelector('[data-feed-older]')!);
    expect([...document.querySelectorAll('[data-feed-post]')].map((e) => e.getAttribute('data-feed-post'))).toEqual(['b', 'd', 'a']);
    fireEvent.click(document.querySelector('[data-feed-post="a"] [data-post-open]')!);
    expect((await screen.findByRole('dialog')).textContent).toContain('Olena');
  });
});

describe('стрічка постів (moments-2 · 1)', () => {
  beforeEach(() => useMessengerStore.setState({ chats: CHATS } as never));

  it('кожен момент — пост: рядок автора, медіа на всю ширину, підпис, дії; не плитка', async () => {
    moments = [row('a', 'olena', 120, { text: 'набережна' }), row('t', 'mum', 10, { media: [], text: 'кава?' })];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelectorAll('[data-feed-post]')).toHaveLength(2));
    const post = document.querySelector('[data-feed-post="a"]')!;
    expect(post.querySelector('[data-post-author]')!.textContent).toContain('Olena');
    expect(post.querySelector('[data-post-time]')!.textContent).toMatch(/2/);
    expect(post.querySelector('[data-post-media] img')).not.toBeNull();
    expect(post.querySelector('[data-post-caption]')!.textContent).toBe('набережна');
    expect(post.querySelector('[data-post-react]')).not.toBeNull();
    expect(document.querySelector('ul.grid')).toBeNull();
    const txt = document.querySelector('[data-feed-post="t"]')!;
    expect(txt.querySelector('[data-post-media]')).toBeNull();
    expect(txt.querySelector('[data-post-caption]')!.textContent).toBe('кава?');
  });

  it('відео грає вбудовано без звуку; дотик на медіа — повний екран', async () => {
    moments = [row('v', 'olena', 5, { media: [photo('v', { mime: 'video/mp4' })] })];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelector('[data-feed-post="v"] video')).not.toBeNull());
    const v = document.querySelector('[data-feed-post="v"] video') as HTMLVideoElement;
    expect(v.muted).toBe(true);
    expect(v.loop).toBe(true);
    fireEvent.click(document.querySelector('[data-feed-post="v"] [data-post-open]')!);
    expect((await screen.findByRole('dialog')).querySelector('[data-moment-video]')).not.toBeNull();
  });

  it('серце ставить реакцію й знімає її; коментарів і лічильників нема', async () => {
    moments = [row('a', 'olena', 5)];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelector('[data-feed-post="a"] [data-post-react]')).not.toBeNull());
    fireEvent.click(document.querySelector('[data-feed-post="a"] [data-post-react]')!);
    await waitFor(() => expect(calls.some(([m, p, b]) => m === 'POST' && p.endsWith('/a/react') && (b as { emoji: string }).emoji === '❤️')).toBe(true));
    await waitFor(() => expect(document.querySelector('[data-feed-post="a"] [data-post-react]')!.getAttribute('aria-pressed')).toBe('true'));
    fireEvent.click(document.querySelector('[data-feed-post="a"] [data-post-react]')!);
    await waitFor(() => expect(calls.filter(([, p]) => p.endsWith('/a/react')).at(-1)?.[2]).toEqual({ emoji: '' }));
    expect(document.querySelector('[data-post-comment]')).toBeNull();
  });
});

describe('поділитися моментом (moments-2 · 3)', () => {
  beforeEach(() => useMessengerStore.setState({ chats: CHATS } as never));

  it('аркуш «Надіслати» шле медіа в обрану розмову без повторного вивантаження', async () => {
    const forward = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({ forwardMessage: forward } as never);
    moments = [row('a', 'olena', 5, { text: 'набережна' })];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelector('[data-feed-post="a"] [data-post-share]')).not.toBeNull());
    fireEvent.click(document.querySelector('[data-feed-post="a"] [data-post-share]')!);
    await screen.findByTestId('moment-share');
    fireEvent.click(document.querySelector('[data-pick-row="mum"]')!);
    fireEvent.click(document.querySelector('[data-share-send]')!);
    await waitFor(() => expect(forward).toHaveBeenCalledTimes(1));
    const [msg, to] = forward.mock.calls[0];
    expect(to).toBe('mum');
    expect(msg.type).toBe('image');
    expect(msg.media.blobId).toBe('a');
    expect(msg.text).toBe('набережна');
    expect(api.uploadFile).not.toHaveBeenCalled();
  });

  it('чужий момент для вужчого кола чи людей — без «Надіслати»; свій і «усім контактам» — з ним', async () => {
    moments = [
      row('c', 'olena', 5, { audience: { kind: 'circles', circles: ['close'] } }),
      row('p', 'mum', 6, { audience: { kind: 'people' } }),
      row('o', 'taras', 7),
      row('mine', 'me', 8, { mine: true, seen: true, audience: { kind: 'people' } }),
    ];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelectorAll('[data-feed-post]')).toHaveLength(4));
    const has = (id: string) => document.querySelector(`[data-feed-post="${id}"] [data-post-share]`) !== null;
    expect([has('c'), has('p'), has('o'), has('mine')]).toEqual([false, false, true, true]);
  });
});

describe('«Хто бачив» (moments-2 · 4)', () => {
  beforeEach(() => useMessengerStore.setState({ chats: CHATS } as never));

  it('лише під своїм постом; екран показує глядачів зі смайлом і часом, порожньо — чесно', async () => {
    moments = [
      row('m1', 'me', 3, { mine: true, seen: true, to_count: 9, text: 'озеро', viewers: [{ id: 'olena', name: 'Olena', at_ms: NOW - 60_000, emoji: '❤️' }, { id: 'mum', name: 'Mum', at_ms: NOW - 30_000 }], reactions: { '❤️': 1 } }),
      row('m2', 'me', 4, { mine: true, seen: true, to_count: 2 }),
      row('o', 'olena', 5),
    ];
    render(<MomentsFeed />);
    await waitFor(() => expect(document.querySelectorAll('[data-feed-post]')).toHaveLength(3));
    expect(document.querySelector('[data-feed-post="o"] [data-post-insights]')).toBeNull();
    fireEvent.click(document.querySelector('[data-feed-post="m1"] [data-post-insights]')!);
    const page = await screen.findByRole('dialog');
    expect(page.querySelector('[data-insight-seen]')!.textContent).toContain('2 з 9');
    expect(page.querySelector('[data-insight-reactions]')!.textContent).toContain('1');
    expect([...page.querySelectorAll('[data-insight-viewer]')].map((e) => e.getAttribute('data-insight-viewer'))).toEqual(['olena', 'mum']);
    expect(page.querySelector('[data-insight-viewer="olena"]')!.textContent).toContain('❤️');
    act(() => usePhoneLayerStore.getState().layers['moments:insights']());
    expect(document.querySelector('[data-moment-insights]')).toBeNull();
    fireEvent.click(document.querySelector('[data-feed-post="m2"] [data-post-insights]')!);
    expect((await screen.findByRole('dialog')).querySelector('[data-insight-empty]')).not.toBeNull();
  });
});

describe('термін ярликом (moments-3 · 1)', () => {
  const compose = async () => {
    renderList();
    await waitFor(() => expect(document.querySelector('[data-moment-add]')).not.toBeNull());
    fireEvent.click(document.querySelector('[data-moment-add]')!);
    return screen.findByRole('dialog');
  };

  it('створення: «24 години» і «Залишити на профілі» — ярлики, без речень про зникнення', async () => {
    const sheet = await compose();
    expect(sheet.querySelector('[data-testid="keep-day"]')!.textContent).toBe('24 години');
    expect(sheet.textContent).not.toContain('Зникне');
    fireEvent.click(sheet.querySelector('[data-testid="keep-profile"]')!);
    expect(sheet.querySelector('[data-testid="keep-profile"]')!.textContent).toBe('Залишити на профілі');
    expect(sheet.textContent).not.toContain('доки не видалите');
  });

  it('перегляд: живий момент — ярлик «24 год» біля часу, без відліку; на профілі — без нього', async () => {
    moments = [row('b', 'mum', 10, { until_ms: NOW + 3 * 3_600_000 })];
    renderList();
    const viewer = await openTile('b');
    expect(viewer.querySelector('[data-moment-ttl]')!.textContent).toBe('24 год');
    expect(viewer.textContent).not.toMatch(/Зникне|лишилось|ще \d/i);
    cleanup();
    useMoments.setState({ list: null });
    moments = [row('k', 'mum', 10, { keep: true, until_ms: 0 })];
    renderList();
    const kept = await openTile('k');
    expect(kept.querySelector('[data-moment-ttl]')).toBeNull();
    expect(kept.querySelector('[data-moment-audience]')!.textContent).toContain('На профілі');
  });
});

describe('переслати і зберегти з перегляду (moments-3 · 2)', () => {
  const forward = vi.fn();
  beforeEach(() => {
    forward.mockReset().mockResolvedValue(undefined);
    useMessengerStore.setState({ chats: CHATS, forwardMessage: forward } as never);
  });
  const menu = async (id: string) => {
    const viewer = await openTile(id);
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    return viewer;
  };

  it('⋮ → «Надіслати»: аркуш над переглядом, медіа йде без повторного вивантаження', async () => {
    moments = [row('a', 'olena', 5, { text: 'набережна' })];
    renderList();
    const viewer = await menu('a');
    fireEvent.click(viewer.querySelector('[data-moment-forward]')!);
    const share = await screen.findByTestId('moment-share');
    expect(share.closest('.z-\\[1400\\]')).not.toBeNull();
    fireEvent.click(document.querySelector('[data-pick-row="mum"]')!);
    fireEvent.click(document.querySelector('[data-share-send]')!);
    await waitFor(() => expect(forward).toHaveBeenCalledTimes(1));
    const [msg, to] = forward.mock.calls[0];
    expect(to).toBe('mum');
    expect(msg.media.blobId).toBe('a');
    expect(msg.text).toBe('набережна');
    expect(api.uploadFile).not.toHaveBeenCalled();
  });

  it('⋮ → «У Збережене»: копія в «Збережене», ярлик-підтвердження; медіа лишається, коли момент зник', async () => {
    moments = [row('a', 'olena', 5)];
    renderList();
    const viewer = await menu('a');
    fireEvent.click(viewer.querySelector('[data-moment-save]')!);
    await waitFor(() => expect(forward).toHaveBeenCalledTimes(1));
    const [msg, to] = forward.mock.calls[0];
    expect(to).toBe('chat_saved_notes');
    expect(msg.media.blobId).toBe('a');
    expect(media.loadMedia).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'a' }));
    await waitFor(() => expect(viewer.querySelector('[data-moment-toast]')!.textContent).toContain('У «Збереженому»'));
    moments = [];
    await act(async () => {
      await useMoments.getState().refresh();
    });
    expect(media.forgetMedia.mock.calls.map((c) => c[0])).not.toContain('a');
  });

  it('дотик у меню ⋮ не вмикає утримання: шапка й прогрес лишаються після «У «Збережене»»', async () => {
    moments = [row('a', 'olena', 5)];
    renderList();
    const viewer = await menu('a');
    const save = viewer.querySelector('[data-moment-save]')!;
    fireEvent.pointerDown(save);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 300));
    });
    fireEvent.pointerUp(save);
    fireEvent.click(save);
    await waitFor(() => expect(forward).toHaveBeenCalledTimes(1));
    expect(viewer.hasAttribute('data-moment-held')).toBe(false);
  });

  it('чужий момент для кола — без «Надіслати» і «У Збережене»; свій вузький — з обома', async () => {
    moments = [row('c', 'olena', 5, { audience: { kind: 'circles', circles: ['close'] } })];
    renderList();
    const theirs = await menu('c');
    expect(theirs.querySelector('[data-moment-forward]')).toBeNull();
    expect(theirs.querySelector('[data-moment-save]')).toBeNull();
    expect(theirs.querySelector('[data-moment-mute]')).not.toBeNull();
    cleanup();
    useMoments.setState({ list: null });
    moments = [row('m', 'me', 3, { mine: true, seen: true, audience: { kind: 'people' } })];
    renderList();
    const own = await menu('m');
    expect(own.querySelector('[data-moment-forward]')).not.toBeNull();
    expect(own.querySelector('[data-moment-save]')).not.toBeNull();
  });
});

describe('аудиторія свого моменту (moments-3 · 3)', () => {
  const RINGED = [chat('olena', 'Olena', { circle: 'personal' }), chat('mum', 'Mum', { circle: 'personal' }), chat('taras', 'Taras', { circle: 'work' })];
  const mine = (extra: Record<string, unknown> = {}) =>
    row('d', 'me', 3, { mine: true, seen: true, to_count: 3, to: ['olena', 'mum', 'taras'], audience: { kind: 'circles', circles: ['personal', 'work'] }, ...extra });
  beforeEach(() => {
    useMessengerStore.setState({ chats: RINGED } as never);
  });
  const audienceSheet = async () => {
    renderList(RINGED);
    const viewer = await openTile('d');
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    fireEvent.click(viewer.querySelector('[data-moment-audience-edit]')!);
    const sheet = await waitFor(() => {
      const el = document.querySelector('div[data-moment-audience]');
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    return { viewer, sheet };
  };

  it('⋮ → «Хто бачить»: той самий вибір кіл з поточною аудиторією; звузити — PATCH з колами й людьми', async () => {
    moments = [mine()];
    const { viewer, sheet } = await audienceSheet();
    expect(sheet.querySelector('[data-testid="aud-circles"]')!.getAttribute('aria-checked')).toBe('true');
    expect(sheet.querySelector('[data-testid="aud-circle-personal"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(sheet.querySelector('[data-testid="aud-circle-work"]')!.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(sheet.querySelector('[data-testid="aud-circle-work"]')!);
    fireEvent.click(sheet.querySelector('[data-moment-audience-done]')!);
    await waitFor(() =>
      expect(bridge.request).toHaveBeenCalledWith('PATCH', '/messenger/moments/d', { audience: { kind: 'circles', circles: ['personal'] }, to: ['olena', 'mum'] }));
    await waitFor(() => expect(document.querySelector('div[data-moment-audience]')).toBeNull());
    await waitFor(() => expect(viewer.querySelector('[data-moment-audience]')!.textContent).toMatch(/·\s*2$/));
  });

  it('вибрані люди: позначені ті, кому пішов; тап за межами — без PATCH', async () => {
    moments = [mine({ audience: { kind: 'people' }, to: ['mum'], to_count: 1 })];
    const { sheet } = await audienceSheet();
    expect(sheet.querySelector('[data-testid="aud-people"]')!.getAttribute('aria-checked')).toBe('true');
    expect((sheet.querySelector('[data-moment-person="mum"]') as HTMLInputElement).checked).toBe(true);
    expect((sheet.querySelector('[data-moment-person="olena"]') as HTMLInputElement).checked).toBe(false);
    fireEvent.click(sheet);
    await waitFor(() => expect(document.querySelector('div[data-moment-audience]')).toBeNull());
    expect(calls.some(([m]) => m === 'PATCH')).toBe(false);
  });

  it('нікого не лишилось — кнопка вимкнена, PATCH не йде', async () => {
    moments = [mine({ audience: { kind: 'people' }, to: ['mum'], to_count: 1 })];
    const { sheet } = await audienceSheet();
    fireEvent.click(sheet.querySelector('[data-moment-person="mum"]')!);
    expect((sheet.querySelector('[data-moment-audience-done]') as HTMLButtonElement).disabled).toBe(true);
    expect(calls.some(([m]) => m === 'PATCH')).toBe(false);
  });

  it('чужий момент — без «Хто бачить»', async () => {
    moments = [row('c', 'olena', 5)];
    renderList(RINGED);
    const viewer = await openTile('c');
    fireEvent.click(viewer.querySelector('[data-moment-more]')!);
    expect(viewer.querySelector('[data-moment-audience-edit]')).toBeNull();
  });

  it('у глядача, що випав з аудиторії: наступна синхронізація прибирає плитку й розшифроване медіа', async () => {
    moments = [row('x', 'olena', 5, { audience: { kind: 'circles', circles: ['close'] } })];
    renderList(RINGED);
    await waitFor(() => expect(tiles()).toContain('x'));
    moments = [];
    await act(async () => {
      await useMoments.getState().refresh();
    });
    await waitFor(() => expect(tiles()).not.toContain('x'));
    expect(media.forgetMedia.mock.calls.map((c) => c[0])).toContain('x');
  });
});
