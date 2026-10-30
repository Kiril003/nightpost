import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

export type JoinTag = 'close' | 'verified' | 'newKey';

export interface JoinFacts {
  inClose(id: string): boolean;
  verified(id: string): boolean;
  keyChanged(id: string): boolean;
}

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;

export function joinTag(peerId: string, facts: JoinFacts): JoinTag | null {
  if (!peerId) return null;
  if (facts.keyChanged(peerId)) return 'newKey';
  if (facts.inClose(peerId)) return 'close';
  if (facts.verified(peerId)) return 'verified';
  return null;
}

function getChats(): Chat[] {
  const chats = useMessengerStore.getState()?.chats;
  return Array.isArray(chats) ? chats : [];
}

export function findDirectChat(peerId: string, chats: Chat[] = getChats()): Chat | undefined {
  if (!peerId) return undefined;
  return chats.find(
    (chat) => (chat.type === 'direct' || chat.type === 'dm') && chat.peerNodeId === peerId,
  );
}

function findGroupChat(chatId?: string, chats: Chat[] = getChats(), memberId?: string): Chat | undefined {
  if (chatId) {
    return chats.find((chat) => chat.id === chatId && (chat.type === 'group' || chat.type === 'channel'));
  }
  if (memberId) {
    return chats.find(
      (chat) =>
        (chat.type === 'group' || chat.type === 'channel') &&
        Boolean(chat.members?.some((member) => member.id === memberId)),
    );
  }
  return chats.find((chat) => chat.type === 'group' || chat.type === 'channel');
}

// Локальні факти зі стану розмов без мережі: Близьке, звірка учасника та зміна ключа за 30 днів.
export function joinFactsFor(chatId?: string, chats: Chat[] = getChats()): JoinFacts {
  return {
    inClose(id: string): boolean {
      if (!id) return false;
      const dm = findDirectChat(id, chats);
      if (!dm) return false;
      return dm.circle === 'close' || Boolean(dm.circles?.includes('close'));
    },
    verified(id: string): boolean {
      if (!id) return false;
      const group = findGroupChat(chatId, chats, id);
      const groupMember = group?.members?.find((member) => member.id === id);
      if (typeof groupMember?.verified === 'boolean') {
        return groupMember.verified;
      }
      const dm = findDirectChat(id, chats);
      const dmMember = dm?.members?.find((member) => member.id === id);
      return dmMember?.verified === true;
    },
    keyChanged(id: string): boolean {
      if (!id) return false;
      const dm = findDirectChat(id, chats);
      if (!dm?.contactKeyChangedAt) return false;
      const atMs = Date.parse(dm.contactKeyChangedAt);
      if (!Number.isFinite(atMs)) return false;
      const ageMs = Date.now() - atMs;
      return ageMs >= 0 && ageMs <= THIRTY_DAYS_MS;
    },
  };
}

export const localJoinFacts: JoinFacts = {
  inClose(id: string): boolean {
    return joinFactsFor().inClose(id);
  },
  verified(id: string): boolean {
    return joinFactsFor().verified(id);
  },
  keyChanged(id: string): boolean {
    return joinFactsFor().keyChanged(id);
  },
};

export function joinedOf(
  msg: Pick<Message, 'type' | 'systemAct' | 'systemActTarget'> | null | undefined,
  chatId?: string,
  chats: Chat[] = getChats(),
): { id: string; name: string } | null {
  if (!msg || typeof msg !== 'object') return null;
  if (msg.type !== 'system' || msg.systemAct !== 'add') return null;
  const id = typeof msg.systemActTarget === 'string' ? msg.systemActTarget.trim() : '';
  if (!id) return null;

  const group = findGroupChat(chatId, chats, id);
  const memberName = group?.members?.find((member) => member.id === id)?.name?.trim();
  if (memberName) {
    return { id, name: memberName };
  }

  const dmTitle = findDirectChat(id, chats)?.title?.trim();
  if (dmTitle) {
    return { id, name: dmTitle };
  }

  return null;
}
