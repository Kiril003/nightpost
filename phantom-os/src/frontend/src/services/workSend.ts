import { useMemo } from 'react';
import { useMessengerStore } from '../stores/messengerStore';
import { currentDateTag, tNow } from '../components/chat/tNow';
import type { MessageKey } from '../i18n';
import { newId, reduceWork, taskLine, withTag, type TaskStatus, type WorkShift, type WorkState } from './workEvents';

export function useWorkState(chatId: string | null | undefined): WorkState & { meId: string } {
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const me = useMessengerStore((s) => s.currentUser);
  return useMemo(() => ({ ...reduceWork(chat, me.id, me.name), meId: me.id }), [chat, me.id, me.name]);
}

const say = (chatId: string, text: string) => useMessengerStore.getState().sendMessage(text, { to: chatId });

export const dueLabel = (due: number | null, withClock = true): string =>
  due === null
    ? ''
    : new Date(due).toLocaleString(currentDateTag(), {
        weekday: 'short',
        day: 'numeric',
        month: 'short',
        ...(withClock ? { hour: '2-digit', minute: '2-digit' } : {}),
      });

export function sendTask(chatId: string, t: { title: string; to: string; toName: string; due: number | null; src?: string }): string {
  const id = newId('t');
  say(chatId, withTag(taskLine(t.title, t.toName, dueLabel(t.due)), 'task', { id, to: t.to, due: t.due === null ? '' : String(t.due), src: t.src ?? '' }));
  return id;
}

export function sendTaskStatus(chatId: string, task: { id: string; title: string }, s: TaskStatus): void {
  say(chatId, withTag(`↻ ${task.title} → ${tNow(`work.st.${s}` as MessageKey)}`, 'task-status', { id: task.id, s }));
}

const shiftLine = (s: Pick<WorkShift, 'day' | 'from' | 'to' | 'role'>, name: string) =>
  `${name || tNow('work.openShift')} · ${new Date(`${s.day}T00:00`).toLocaleDateString(currentDateTag(), { weekday: 'short', day: 'numeric' })} · ${s.from}–${s.to}${s.role ? ` · ${s.role}` : ''}`;

export function sendShift(chatId: string, s: Omit<WorkShift, 'id'> & { id?: string }, name: string): void {
  const id = s.id ?? newId('s');
  say(chatId, withTag(shiftLine(s, name), 'shift', { id, who: s.who || '-', day: s.day, from: s.from, to: s.to, role: s.role }));
}

export function sendTake(chatId: string, s: WorkShift, name: string): void {
  say(chatId, withTag(`${tNow('work.take')}: ${shiftLine(s, name)}`, 'take', { id: s.id, day: s.day, from: s.from, to: s.to, role: s.role ?? '' }));
}

export function sendSwap(chatId: string, a: WorkShift, b: WorkShift, toName: string): string {
  const id = newId('w');
  say(chatId, withTag(`⇄ ${tNow('work.swapWith', { name: toName })}: ${a.day} ${a.from}–${a.to} ⇄ ${b.day} ${b.from}–${b.to}`, 'swap', { id, a: a.id, b: b.id, to: b.who }));
  return id;
}

export function sendSwapAck(chatId: string, id: string, s: 'accepted' | 'declined' | 'approved'): void {
  say(chatId, withTag(`⇄ ${tNow(`work.swap.${s}` as MessageKey)}`, 'swap-ack', { id, s }));
}
