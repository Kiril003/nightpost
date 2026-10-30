import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { chatHush, hushPort } from '../services/chatHush';
import { nativeHushRules, startHushRulesSync } from '../services/hushRulesSync';
import type { Chat } from '../types/messenger';

const dm = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title: id, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], ...extra }) as unknown as Chat;
const group = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title: id, type: 'group', circle: 'all', avatar: '', unreadCount: 0, messages: [], ...extra }) as unknown as Chat;

function source(chats: Chat[], hydrated: boolean) {
  let state = { chats, hydrated };
  const subs = new Set<() => void>();
  return {
    getState: () => state,
    subscribe: (fn: () => void) => {
      subs.add(fn);
      return () => subs.delete(fn);
    },
    set: (next: Partial<typeof state>) => {
      state = { ...state, ...next };
      subs.forEach((fn) => fn());
    },
  };
}

beforeEach(() => chatHush.reset());
afterEach(() => {
  vi.restoreAllMocks();
  chatHush.reset();
});

describe('Q1: правила тиші доходять до застосунку', () => {
  it('кожна розмова з правилом — під ключем, яким її бачить шторка; години розмови важать більше за коло', () => {
    chatHush.setRule('ring', 'work', {});
    chatHush.setRule('chat', 'g1', { from: '22:00', to: '07:00', ask: true });
    chatHush.setRule('ring', 'friends', { from: '23:00', to: '08:00' });
    chatHush.setRule('chat', 'g2', { from: '21:00', to: '06:00' });
    const out = nativeHushRules([
      dm('c-oksana', { peerNodeId: 'node-oksana', circle: 'work' } as Partial<Chat>),
      group('g1'),
      group('g2', { circle: 'friends' }),
      dm('c-free', { peerNodeId: 'node-free' } as Partial<Chat>),
      dm('chat_saved_notes', { type: 'saved' } as unknown as Partial<Chat>),
    ]);
    expect(out).toEqual({
      'node-oksana': {},
      g1: { from: '22:00', to: '07:00', ask: true },
      g2: { from: '21:00', to: '06:00' },
    });
  });

  it('на телефоні шле правила після першого списку, повторно — лише коли щось змінилось', () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(true);
    const put = vi.spyOn(hushPort, 'putRules').mockResolvedValue({});
    const src = source([], false);
    const stop = startHushRulesSync(src);
    expect(put).not.toHaveBeenCalled();

    src.set({ chats: [group('g1')], hydrated: true });
    expect(put.mock.calls).toEqual([[{}]]);
    src.set({ chats: [group('g1')] });
    expect(put).toHaveBeenCalledTimes(1);

    chatHush.setRule('chat', 'g1', {});
    expect(put.mock.calls[1]).toEqual([{ g1: {} }]);
    stop();
    chatHush.setRule('chat', 'g1', null);
    expect(put).toHaveBeenCalledTimes(2);
  });

  it('поза телефоном мовчить', () => {
    vi.spyOn(hushPort, 'on').mockReturnValue(false);
    const put = vi.spyOn(hushPort, 'putRules').mockResolvedValue({});
    const stop = startHushRulesSync(source([group('g1')], true));
    chatHush.setRule('chat', 'g1', {});
    stop();
    expect(put).not.toHaveBeenCalled();
  });
});
