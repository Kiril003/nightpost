import { useMessengerStore } from '../../stores/messengerStore';
import { currentDateTag, tNow } from '../../components/chat/tNow';
import { composeEdu, type EduEvent } from './eduWire';

const uid = (): string => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;

export const eduWhen = (ms: number): string =>
  new Date(ms).toLocaleString(currentDateTag(), { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

const send = (chatId: string, text: string): void => {
  void useMessengerStore.getState().sendMessage(text, { to: chatId, keepDraft: true });
};

export function makeClass(chatId: string, name: string, subject: string): void {
  const ev: EduEvent = { k: 'class', n: name.trim(), s: subject.trim(), at: Date.now() };
  send(chatId, composeEdu([tNow('edu.say.class', { name: ev.n }), ev.s], ev));
}

export function giveAssignment(chatId: string, title: string, due: number | null, note: string): string {
  const ev: EduEvent = { k: 'assign', id: uid(), t: title.trim(), due, note: note.trim(), at: Date.now() };
  send(
    chatId,
    composeEdu([tNow('edu.say.assign', { title: ev.t }), due !== null ? tNow('edu.say.due', { when: eduWhen(due) }) : '', ev.note], ev),
  );
  return ev.id;
}

export function markDone(chatId: string, assignId: string, title: string): void {
  const ev: EduEvent = { k: 'done', a: assignId, t: title, at: Date.now() };
  send(chatId, composeEdu([tNow('edu.say.done', { title })], ev));
}

export function sendReminder(chatId: string, title: string): void {
  send(chatId, tNow('edu.say.remind', { title }));
}

export function saveAttendance(chatId: string, lesson: string, p: string[], l: string[], x: string[], total: number): void {
  const ev: EduEvent = { k: 'attend', id: uid(), lesson: lesson.trim(), p, l, x, at: Date.now() };
  send(chatId, composeEdu([tNow('edu.say.attend', { lesson: ev.lesson, p: p.length + l.length, n: total })], ev));
}

export function postTopic(chatId: string, title: string, body: string): void {
  const ev: EduEvent = { k: 'topic', id: uid(), t: title.trim(), at: Date.now() };
  send(chatId, composeEdu([tNow('edu.say.topic', { title: ev.t }), body.trim()], ev));
}

export function postReply(chatId: string, topicId: string, title: string, text: string): void {
  const ev: EduEvent = { k: 'reply', id: uid(), tp: topicId, at: Date.now() };
  send(chatId, composeEdu([tNow('edu.say.reply', { title }), text.trim()], ev));
}

export function markAnswer(chatId: string, topicId: string, replyId: string, title: string): void {
  const ev: EduEvent = { k: 'answer', tp: topicId, r: replyId, at: Date.now() };
  send(chatId, composeEdu([tNow('edu.say.answer', { title })], ev));
}
