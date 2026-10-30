/**
 * msg-x зріз 4: перегляд моменту — утримання ставить на паузу, вхід/вихід на
 * спільній пружині layer, реакція злітає двійником D1, відповідь іде в 1:1
 * цитатою моменту, яку розмова показує й відкриває.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';

const host = vi.hoisted(() => ({ open: vi.fn(() => true) }));
vi.mock('../components/messenger/MomentsStrip', async (orig) => {
  const real = await orig<typeof import('../components/messenger/MomentsStrip')>();
  return { ...real, openMomentInHost: host.open };
});

import { MomentViewer, MOMENT_EXIT_MS } from '../components/messenger/MomentViewer';
import { REACTIONS, readMoments, useMoments } from '../services/moments';
import { messengerApi } from '../services/messengerApi';
import { parseTag, withTag } from '../services/workEvents';
import { stickerForEmoji } from '../stickers/pack';
import { LAYER_EXIT_MS } from '../feel/motionKit';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message } from '../types/messenger';

const NOW = Date.now();
const raw = (id: string, text: string) => ({
  id, from_id: 'olena', from_name: '', mine: false, at_ms: NOW - 60_000, until_ms: NOW + 86_400_000,
  keep: false, audience: { kind: 'contacts' }, text, seen: true, to_count: 0, media: [],
});
const list = readMoments([raw('c', 'ранок'), raw('d', 'вечір')], NOW);

const mount = () => {
  render(<MomentViewer moments={list} startId="c" nameOf={() => 'Olena'} avatarOf={() => undefined} chatOf={() => 'olena'} onOpenChat={() => {}} onClose={() => {}} />);
  const viewer = () => document.querySelector('[data-moment-viewer]') as HTMLElement;
  return {
    viewer,
    stage: () => viewer().querySelector('[data-moment-stage]') as HTMLElement,
    bar: () => viewer().querySelector('[data-moment-progress]') as HTMLElement,
  };
};

const before = { react: useMoments.getState().react, chats: useMessengerStore.getState().chats };

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  host.open.mockClear();
  useMoments.setState({ react: before.react, list: null });
  useMessengerStore.setState({ chats: before.chats });
});

describe('утримання ставить момент на паузу', () => {
  it('палець тримає — прогрес стоїть; відпустив — той самий момент, без перемикання', () => {
    vi.useFakeTimers();
    const { viewer, stage, bar } = mount();
    fireEvent.pointerDown(stage());
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(viewer().hasAttribute('data-moment-held')).toBe(true);
    expect(bar().style.animationPlayState).toBe('paused');
    fireEvent.pointerUp(stage());
    fireEvent.click(viewer().querySelector('[data-moment-next]')!);
    expect(viewer().getAttribute('data-moment-viewer')).toBe('c');
    expect(viewer().hasAttribute('data-moment-held')).toBe(false);
    expect(bar().style.animationPlayState).toBe('running');
  });

  it('короткий дотик праворуч і далі гортає', () => {
    vi.useFakeTimers();
    const { viewer, stage } = mount();
    fireEvent.pointerDown(stage());
    act(() => {
      vi.advanceTimersByTime(80);
    });
    fireEvent.pointerUp(stage());
    fireEvent.click(viewer().querySelector('[data-moment-next]')!);
    expect(viewer().getAttribute('data-moment-viewer')).toBe('d');
  });
});

describe('вхід і вихід — спільна пружина layer', () => {
  const css = readFileSync(resolve(__dirname, '..', 'styles/messenger.css'), 'utf8');
  const rule = (cls: string) => css.match(new RegExp(`\\n\\.${cls} \\{[^}]*\\}`))?.[0] ?? '';
  it.each(['np-moment-in', 'np-moment-out'])('%s грає var(--ph-spring-layer)', (cls) => {
    expect(rule(cls)).toContain('var(--ph-spring-layer-ms) var(--ph-spring-layer)');
  });
  it('таймер виходу = час осідання пружини', () => {
    expect(MOMENT_EXIT_MS).toBe(LAYER_EXIT_MS);
  });
});

describe('реакція — живий двійник D1', () => {
  it('у кожного смайла реакції є двійник у наборі', () => {
    expect(REACTIONS.filter((e) => !stickerForEmoji(e))).toEqual([]);
  });

  it('поставив 🔥 — двійник раз злітає над моментом; зняв — ні', async () => {
    const react = vi.fn().mockResolvedValue(undefined);
    useMoments.setState({ react });
    const { viewer } = mount();
    fireEvent.click(viewer().querySelector('[data-moment-react-open]')!);
    await act(async () => {
      fireEvent.click(viewer().querySelector('[data-moment-react="🔥"]')!);
    });
    expect(react).toHaveBeenCalledWith('c', '🔥');
    expect(viewer().querySelector('[data-moment-burst]')?.getAttribute('data-moment-burst')).toBe('d1/fire');
  });
});

const olena = { id: 'olena', title: 'Olena', type: 'dm', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: '', lastAt: new Date(NOW).toISOString() } as unknown as Chat;

describe('відповідь іде в розмову цитатою моменту', () => {
  it('лист несе позначку моменту, список показує лише слова', async () => {
    useMessengerStore.setState({ chats: [olena] });
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'm1' } as never);
    const { viewer } = mount();
    const input = viewer().querySelector('[data-moment-reply]') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'гарно' } });
    await act(async () => {
      fireEvent.submit(input.closest('form')!);
    });
    const body = (append.mock.calls[0][1] as { body: string }).body;
    const tag = parseTag(body);
    expect(tag?.kind).toBe('moment');
    expect(tag?.f).toMatchObject({ id: 'c', c: 'ранок', k: 'text' });
    expect(tag?.line).toBe('гарно');
    const chat = useMessengerStore.getState().chats.find((c) => c.id === 'olena')!;
    expect(chat.lastSnippet).toBe('гарно');
    expect(chat.messages.at(-1)?.text).toBe(body);
  });

  const rowOf = (text: string) => {
    const msg = { id: 'r1', senderId: 'me', senderName: 'Я', text, timestamp: '10:00', status: 'sent', type: 'text', senderAvatar: '', isSelf: true } as Message;
    const [row] = buildFeedRows([msg], 'me', new Date(NOW));
    return render(
      <FeedActionsContext.Provider value={new Proxy({}, { get: () => () => {} }) as FeedActions}>
        <MessageRow
          row={row} chatId="olena" currentUserId="me" currentUserName="Я" groupish={false} direct phone
          isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
          isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
        />
      </FeedActionsContext.Provider>,
    );
  };
  const TEXT = withTag('гарно', 'moment', { id: 'c', c: 'ранок', k: 'text' });

  it('бульбашка показує цитату «Момент · ранок» без службового рядка; дотик відкриває момент', () => {
    useMoments.setState({ list });
    const { container } = rowOf(TEXT);
    const q = container.querySelector('[data-testid="msg-moment-quote"]') as HTMLElement;
    expect(q.textContent).toContain('Момент');
    expect(q.textContent).toContain('ранок');
    expect(container.textContent).toContain('гарно');
    expect(container.textContent).not.toContain('[np:');
    fireEvent.click(q);
    expect(host.open).toHaveBeenCalledWith('c');
  });

  it('момент уже зник — чесний підпис і нічого не відкриваємо', () => {
    useMoments.setState({ list: [] });
    const { container } = rowOf(withTag('гарно', 'moment', { id: 'x', c: '', k: 'photo' }));
    const q = container.querySelector('[data-testid="msg-moment-quote"]') as HTMLElement;
    expect(q.textContent).toContain('Фото');
    expect(q.textContent).toContain('Момент уже зник');
    fireEvent.click(q);
    expect(host.open).not.toHaveBeenCalled();
  });
});
