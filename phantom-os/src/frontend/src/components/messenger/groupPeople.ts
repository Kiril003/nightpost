import type { Chat } from '../../types/messenger';
import { displayTitle, isUnnamed } from './chatListFormat';

/** Справжнє імʼя людини, а не шматок адреси: «dddd4444», «747a·a842», «PH2:…» — не імена. */
export const isRealName = (name: string | null | undefined, peer = ''): boolean => {
  const n = (name || '').trim();
  if (!n) return false;
  if (peer && n.length >= 4 && peer.toLowerCase().startsWith(n.toLowerCase().replace(/[·\s]/g, ''))) return false;
  if (/^[0-9a-f·:\-\s]{6,}$/i.test(n) || /^PH\d?:/i.test(n)) return false;
  const shown = displayTitle(n);
  return !isUnnamed(shown) && shown.trim().length > 0;
};

export interface PeerFace {
  name: string;
  avatar: string;
  handle: string;
  known: boolean;
}

const dmOf = (chats: Chat[], peer: string): Chat | undefined =>
  chats.find((c) => (c.type === 'dm' || c.type === 'direct') && (c.peerNodeId === peer || c.id === peer));

/** Обличчя людини з того, що вже є на телефоні: її розмова зі мною, склад моїх груп. */
export const peerFace = (chats: Chat[], peer: string, given = ''): PeerFace => {
  const dm = dmOf(chats, peer);
  const seen = chats.flatMap((c) => c.members ?? []).find((m) => m.id === peer);
  const dmName = dm && isRealName(dm.title, peer) ? displayTitle(dm.title) : '';
  const name = dmName || (isRealName(given, peer) ? displayTitle(given) : '') || (seen && isRealName(seen.name, peer) ? displayTitle(seen.name) : '');
  return {
    name,
    avatar: dm?.avatar || seen?.avatar || '',
    handle: (seen?.handle || '').replace(/^@/, ''),
    known: !!dm,
  };
};

/** Мої групи, де ця людина теж є (за складом, який телефон уже знає). */
export const mutualGroups = (chats: Chat[], peer: string, except: string): string[] =>
  chats
    .filter((c) => c.type === 'group' && c.id !== except && (c.members ?? []).some((m) => m.id === peer))
    .map((c) => displayTitle(c.title));
