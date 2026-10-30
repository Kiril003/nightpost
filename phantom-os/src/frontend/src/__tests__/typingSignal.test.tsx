/**
 * «Друкує…» між двома телефонами: композер кличе міст рідко й чесно гасить,
 * а подія з телефона вмикає рядок і гасить його сама.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';

import { useMessengerStore } from '../stores/messengerStore';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { TYPING_IDLE_MS, TYPING_SHOWN_MS, typingReaches } from '../services/typingSignal';

const props = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'oksana',
};

type Call = { method: string; path: string; body: { on: boolean } | null };

let calls: Call[];

const typingCalls = () => calls.filter((c) => c.path.endsWith('/typing')).map((c) => [c.path, c.body?.on]);

beforeEach(() => {
  vi.useFakeTimers();
  calls = [];
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
    call: (method: string, path: string, body: string | null) => {
      calls.push({ method, path, body: body ? JSON.parse(body) : null });
      return JSON.stringify({ ok: true, status: 200, data: { sent: 1 } });
    },
  };
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

const box = () => screen.getByRole('textbox') as HTMLTextAreaElement;
const type = (v: string) => fireEvent.change(box(), { target: { value: v } });

describe('композер → міст', () => {
  it('раз на ~3 с поки набирає, і «перестав» при відправці', () => {
    render(<MessageComposer {...props} signalTyping />);
    type('п');
    type('пр');
    vi.advanceTimersByTime(1_000);
    type('при');
    expect(typingCalls()).toEqual([['/messenger/conversations/oksana/typing', true]]);

    vi.advanceTimersByTime(2_100);
    type('прив');
    expect(typingCalls()).toHaveLength(2);

    fireEvent.keyDown(box(), { key: 'Enter' });
    expect(typingCalls().at(-1)).toEqual(['/messenger/conversations/oksana/typing', false]);
    expect(typingCalls()).toHaveLength(3);
  });

  it('5 с тиші — «перестав», очищене поле — теж', () => {
    render(<MessageComposer {...props} signalTyping />);
    type('а');
    vi.advanceTimersByTime(TYPING_IDLE_MS);
    expect(typingCalls()).toEqual([
      ['/messenger/conversations/oksana/typing', true],
      ['/messenger/conversations/oksana/typing', false],
    ]);

    type('б');
    type('');
    expect(typingCalls().slice(2)).toEqual([
      ['/messenger/conversations/oksana/typing', true],
      ['/messenger/conversations/oksana/typing', false],
    ]);
    vi.advanceTimersByTime(TYPING_IDLE_MS);
    expect(typingCalls()).toHaveLength(4);
  });

  it('без дозволу розмови мовчить', () => {
    render(<MessageComposer {...props} />);
    type('привіт');
    vi.advanceTimersByTime(TYPING_IDLE_MS);
    expect(typingCalls()).toEqual([]);
  });

  it('лише розмови з людьми й групи, не Nightpost', () => {
    expect(typingReaches({ type: 'dm' })).toBe(true);
    expect(typingReaches({ type: 'group' })).toBe(true);
    expect(typingReaches({ type: 'phantom' })).toBe(false);
    expect(typingReaches({ type: 'ai' })).toBe(false);
    expect(typingReaches({ type: 'saved' })).toBe(false);
    delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
    expect(typingReaches({ type: 'dm' })).toBe(false);
  });
});

describe('телефон → рядок «друкує…»', () => {
  const push = (chatId: string, name: string, on: boolean) =>
    act(() =>
      (window as unknown as { __phantomTyping: (c: string, n: string, o: boolean) => void }).__phantomTyping(chatId, name, on),
    );

  it('вмикає з іменем і гасить подією «перестав»', () => {
    push('oksana', 'Оксана', true);
    expect(useMessengerStore.getState().typingStatus.oksana).toBe('Оксана друкує…');
    push('oksana', 'Оксана', false);
    expect(useMessengerStore.getState().typingStatus.oksana).toBeNull();
  });

  it('гасне сам через 6 с, якщо «перестав» не доїхав', () => {
    push('g1', 'Тарас', true);
    vi.advanceTimersByTime(TYPING_SHOWN_MS - 1);
    push('g1', 'Тарас', true);
    vi.advanceTimersByTime(TYPING_SHOWN_MS - 1);
    expect(useMessengerStore.getState().typingStatus.g1).toBe('Тарас друкує…');
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(useMessengerStore.getState().typingStatus.g1).toBeNull();
  });
});
