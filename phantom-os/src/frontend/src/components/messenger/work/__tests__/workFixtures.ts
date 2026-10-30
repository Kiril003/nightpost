import { useMessengerStore } from '../../../../stores/messengerStore';
import { withTag } from '../../../../services/workEvents';
import type { Chat, Message } from '../../../../types/messenger';

export const ME = 'me';
export const MARA = 'mara';
export const IVO = 'ivo';
export const PETRA = 'petra';

export const msg = (id: string, from: string, text: string, self = false): Message =>
  ({ id, senderId: from, senderName: from, senderAvatar: '', timestamp: '18:25', type: 'text', text, isSelf: self }) as Message;

const member = (id: string, role: 'owner' | 'member' = 'member') => ({ id, name: id[0].toUpperCase() + id.slice(1), handle: '', avatar: '', role }) as never;

export const workChat = (messages: Message[], over: Partial<Chat> = {}): Chat =>
  ({
    id: 'w1',
    title: 'Release 2.4',
    avatar: '',
    type: 'group',
    circle: 'work',
    unreadCount: 0,
    messages,
    members: [member(ME), member(MARA), member(IVO), member(PETRA, 'owner')],
    ownerId: PETRA,
    ...over,
  }) as Chat;

export function seed(chat: Chat) {
  const sent: string[] = [];
  useMessengerStore.setState({
    chats: [chat],
    activeChatId: chat.id,
    currentUser: { id: ME, name: 'Me', avatar: '' } as never,
    sendMessage: ((text: string) => {
      sent.push(text);
    }) as never,
  });
  return sent;
}

export const taskMsg = (id: string, title: string, to: string, from = IVO, due = '') =>
  msg(`m_${id}`, from, withTag(`☑ ${title} → ${to}`, 'task', { id, to, due }), from === ME);

export const shiftMsg = (id: string, who: string, day: string, from: string, to: string, role = 'Bar', sender = PETRA) =>
  msg(`m_${id}`, sender, withTag('shift', 'shift', { id, who: who || '-', day, from, to, role }), sender === ME);
