/**
 * «Друкує…» між двома телефонами — дзеркало `WebMessengerTyping` у Kotlin.
 *
 * Кадр живе лише в дорозі: не доїхав — нічого, тому й помилку мосту ковтаємо.
 */

import { request } from './api';
import { phoneBridge } from './messengerApi';
import type { Chat } from '../types/messenger';
import { tNow } from '../components/chat/tNow';

export const TYPING_EVERY_MS = 3_000;
export const TYPING_IDLE_MS = 5_000;
export const TYPING_SHOWN_MS = 6_000;

const PEOPLE: ReadonlyArray<Chat['type']> = ['dm', 'direct', 'p2p', 'group'];

export const typingReaches = (chat: Pick<Chat, 'type'> | undefined): boolean =>
  Boolean(chat && phoneBridge() && PEOPLE.includes(chat.type));

export const sendTyping = (chatId: string, on: boolean): void => {
  request('POST', `/messenger/conversations/${encodeURIComponent(chatId)}/typing`, { on }).catch(() => {});
};

export interface TypingSignal {
  input: (chatId: string) => void;
  stop: () => void;
}

export function createTypingSignal(send: (chatId: string, on: boolean) => void, now = () => Date.now()): TypingSignal {
  let chat: string | null = null;
  let lastAt = 0;
  let idle: ReturnType<typeof setTimeout> | null = null;

  const stop = () => {
    if (idle) clearTimeout(idle);
    idle = null;
    if (chat === null) return;
    const was = chat;
    chat = null;
    send(was, false);
  };

  const input = (chatId: string) => {
    if (chat !== null && chat !== chatId) stop();
    const t = now();
    if (chat === null || t - lastAt >= TYPING_EVERY_MS) {
      chat = chatId;
      lastAt = t;
      send(chatId, true);
    }
    if (idle) clearTimeout(idle);
    idle = setTimeout(stop, TYPING_IDLE_MS);
  };

  return { input, stop };
}

/** Чужий «друкує…»: вмикає рядок і гасить його сам, якщо «перестав» не доїхав. */
export function createTypingShown(setStatus: (chatId: string, status: string | null) => void) {
  const timers = new Map<string, ReturnType<typeof setTimeout>>();
  return (chatId: string, name: string, on: boolean) => {
    const prev = timers.get(chatId);
    if (prev) clearTimeout(prev);
    timers.delete(chatId);
    if (!on) {
      setStatus(chatId, null);
      return;
    }
    setStatus(chatId, tNow('typing.named', { name }));
    timers.set(
      chatId,
      setTimeout(() => {
        timers.delete(chatId);
        setStatus(chatId, null);
      }, TYPING_SHOWN_MS),
    );
  };
}
