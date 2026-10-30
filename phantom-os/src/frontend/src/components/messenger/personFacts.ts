import type { Chat } from '../../types/messenger';
import type { MessageKey } from '../../i18n';
import { circleName, primaryOf } from '../../stores/circlesStore';
import type { CircleInfo } from '../../services/circlesApi';
import { isUnnamed } from './chatListFormat';

export interface PersonFacts {
  avatar?: string;
  line: string;
  chat?: Chat;
}

type T = (key: MessageKey) => string;

/** Людина в рядку — з того, що телефон уже знає: фото, @нік, коло, спільні групи. Нічого не вигадує. */
export function personFacts(peer: string, chats: readonly Chat[], circles: readonly CircleInfo[], t: T): PersonFacts {
  const chat = peer ? chats.find((c) => c.peerNodeId === peer && c.type !== 'group' && c.type !== 'channel') : undefined;
  const parts: string[] = [];
  if (chat?.handle) parts.push(`@${chat.handle}`);
  const list = chat?.circles ?? [];
  if (list.length) {
    const id = primaryOf(list, circles);
    const info = circles.find((c) => c.id === id);
    if (info) parts.push(circleName(info, t));
  }
  const shared = peer ? chats.filter((c) => c.type === 'group' && c.members?.some((m) => m.id === peer)).length : 0;
  if (shared > 0) parts.push(`${t('peerProfileSheet.sharedGroups')} · ${shared}`);
  return { avatar: chat?.avatar || undefined, line: parts.join(' · '), chat };
}

/** Імʼя людини без «Без імені» і без ключа: чесне «Людина», коли імені нема. */
export function personName(name: string | undefined, t: T): string {
  const n = (name ?? '').trim();
  return !n || isUnnamed(n) ? t('support.preview.targetPerson') : n;
}
