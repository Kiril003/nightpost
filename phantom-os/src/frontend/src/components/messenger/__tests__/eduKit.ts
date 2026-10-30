import { vi } from 'vitest';
import { useMessengerStore } from '../../../stores/messengerStore';
import { composeEdu, type EduEvent } from '../../../services/edu/eduWire';
import { useEduNav } from '../../../services/edu/eduNav';
import type { Chat, Message } from '../../../types/messenger';

export const ME = 'me';
export const TEACHER = 'teach';
export const GROUP = 'g9b';

export const STUDENTS = [
  { id: 'st1', name: 'Аліна Коваль' },
  { id: 'st2', name: 'Богдан Мельник' },
  { id: 'st3', name: 'Вероніка Шевчук' },
];

let n = 0;
export const msg = (from: string, text: string, isSelf = false): Message =>
  ({
    id: `m${++n}`,
    senderId: from,
    senderName: from === TEACHER ? 'Олена Ткач' : (STUDENTS.find((s) => s.id === from)?.name ?? 'Я'),
    senderAvatar: '',
    timestamp: '10:00',
    type: 'text',
    text,
    isSelf,
    status: 'sent',
  }) as Message;

export const evMsg = (from: string, ev: EduEvent, lines: string[] = ['x'], body = '', isSelf = false): Message =>
  ({ ...msg(from, composeEdu([lines[0], body].filter(Boolean), ev), isSelf), sentAt: new Date(ev.at).toISOString() });

export const classEv = (at = 1): EduEvent => ({ k: 'class', n: '9-Б', s: 'Фізика', at });

export function seed(role: 'teacher' | 'student', messages: Message[] = [], withMembers = true): Chat {
  const members = [
    { id: TEACHER, name: 'Олена Ткач', handle: '', avatar: '', role: 'member' },
    ...STUDENTS.map((s) => ({ id: s.id, name: s.name, handle: '', avatar: '', role: 'member' })),
    { id: ME, name: 'Я', handle: '', avatar: '', role: 'member' },
  ] as unknown as Chat['members'];
  const chat = {
    id: GROUP,
    title: '9-Б Фізика',
    avatar: '',
    type: 'group',
    circle: 'all',
    unreadCount: 0,
    messages,
    members: withMembers ? members : [],
  } as unknown as Chat;
  const me = useMessengerStore.getState().currentUser;
  useMessengerStore.setState({ currentUser: { ...me, id: role === 'teacher' ? TEACHER : ME, name: role === 'teacher' ? 'Олена Ткач' : 'Я', avatar: '' }, chats: [chat] });
  return chat;
}

/** sendMessage, що кладе лист у розмову, як зробив би вузол. */
export function fakeSend(): ReturnType<typeof vi.fn> {
  const fn = vi.fn((text: string, opts?: { to?: string }) => {
    const me = useMessengerStore.getState().currentUser.id;
    useMessengerStore.setState((s) => ({
      chats: s.chats.map((c) => (c.id === (opts?.to ?? GROUP) ? { ...c, messages: [...c.messages, msg(me, text, true)] } : c)),
    }));
  });
  useMessengerStore.setState({ sendMessage: fn as never });
  return fn;
}

export const resetEdu = (): void => useEduNav.getState().close();
