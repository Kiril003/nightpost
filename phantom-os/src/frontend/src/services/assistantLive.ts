/**
 * Відповідь помічника, що росте (A5). Хост штовхає кадри в `window.__phantomAssistantLive`
 * (WebMessengerAssistantLive.kt), не частіше ніж раз на 200 мс. Рядок живе лише в памʼяті
 * сторінки: у журнал лягає готовий лист із тим самим id, і він займає місце рядка.
 */
import { create } from 'zustand';
import type { Message } from '../types/messenger';
import { messageFromNode, type NodeMessage } from './messengerApi';
import { useMessengerStore } from '../stores/messengerStore';

export type LivePhase = 'thinking' | 'writing' | 'tool' | 'failed' | 'done';

export interface AssistantLiveEvent {
  chat_id: string;
  message_id: string;
  phase: LivePhase | 'clear';
  text?: string;
  question_id?: string | null;
  reason?: string | null;
}

export interface AssistantLiveLine {
  chatId: string;
  messageId: string;
  phase: LivePhase;
  text: string;
  questionId?: string;
  reason?: string;
  at: number;
}

/** Готовий лист зазвичай приходить за долі секунди; не прийшов — рядок не висить вічно. */
export const DONE_GRACE_MS = 5000;

export const useAssistantLive = create<{ lines: Record<string, AssistantLiveLine> }>(() => ({ lines: {} }));

const drop = (chatId: string, messageId: string) =>
  useAssistantLive.setState((s) => {
    if (s.lines[chatId]?.messageId !== messageId) return s;
    const rest = { ...s.lines };
    delete rest[chatId];
    return { lines: rest };
  });

const markFailed = (chatId: string, questionId: string, reason: string) =>
  useMessengerStore.setState((s) => ({
    chats: s.chats.map((c) =>
      c.id !== chatId
        ? c
        : {
            ...c,
            messages: c.messages.map((m) =>
              m.id === questionId ? { ...m, status: 'failed' as const, failReason: reason || undefined } : m,
            ),
          },
    ),
  }));

export function applyAssistantLive(e: AssistantLiveEvent): void {
  if (!e || !e.chat_id || !e.message_id) return;
  const text = e.text ?? '';
  if (e.phase === 'clear' || (e.phase === 'done' && !text)) {
    drop(e.chat_id, e.message_id);
    return;
  }
  const prev = useAssistantLive.getState().lines[e.chat_id];
  const line: AssistantLiveLine = {
    chatId: e.chat_id,
    messageId: e.message_id,
    phase: e.phase,
    text,
    questionId: e.question_id || undefined,
    reason: e.reason || undefined,
    at: prev?.messageId === e.message_id ? prev.at : Date.now(),
  };
  useAssistantLive.setState((s) => ({ lines: { ...s.lines, [e.chat_id]: line } }));
  if (e.phase === 'failed' && line.questionId) markFailed(e.chat_id, line.questionId, line.reason ?? '');
  if (e.phase === 'done') window.setTimeout(() => drop(e.chat_id, e.message_id), DONE_GRACE_MS);
}

const stored = (messages: Message[], line: AssistantLiveLine) => messages.some((m) => m.id === line.messageId);

/** Стрічка з рядком, що росте; лист із тим самим id уже в журналі — лише лист. */
export function withLiveLine(messages: Message[], line: AssistantLiveLine | undefined, peerId: string, selfId: string): Message[] {
  if (!line || !line.text || stored(messages, line)) return messages;
  const row = {
    id: line.messageId,
    conversation_id: line.chatId,
    client_id: line.messageId,
    seq: 0,
    author_id: peerId,
    author_name: '',
    kind: 'text',
    body: line.text,
    ciphertext: null,
    transport: 'p2p',
    sent_at: new Date(line.at).toISOString(),
    edited_at: null,
    deleted_at: null,
    delivery_state: 'delivered',
  } as unknown as NodeMessage;
  return [...messages, { ...messageFromNode(row, selfId, peerId), live: line.phase }];
}

/** Крапки «Nightpost рахує» — поки слів ще немає або йде інструмент. */
export function liveThinking(line: AssistantLiveLine | undefined, messages: Message[]): boolean {
  if (!line || stored(messages, line)) return false;
  return line.phase === 'thinking' || line.phase === 'tool' || (line.phase === 'writing' && !line.text);
}

if (typeof window !== 'undefined') {
  (window as unknown as { __phantomAssistantLive?: (e: AssistantLiveEvent) => void }).__phantomAssistantLive =
    applyAssistantLive;
}
