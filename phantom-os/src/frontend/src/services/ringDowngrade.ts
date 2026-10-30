/** Перевірка та зняття привілеїв після перенесення людини у ширше коло. */
import { CIRCLES, isRealCircle } from '../components/messenger/todayFormat';
import { useMessengerStore } from '../stores/messengerStore';
import type { ChatCircle } from '../types/messenger';
import { chatPins } from './chatPins';
import { messengerApi, phoneBridge } from './messengerApi';
import { useMoments } from './moments';
import { whereApi } from './peerWhere';

export interface RingPeer {
  id: string;
  chatId: string;
  name: string;
}

export type RingRowKey = 'where' | 'moments' | 'pin' | 'quiet';

export interface RingRow {
  key: RingRowKey;
}

const rank = (c: ChatCircle | string): number =>
  c === 'all' || !c ? CIRCLES.length : isRealCircle(c as ChatCircle) ? (CIRCLES as readonly string[]).indexOf(c) : -1;

export function isWider(from: ChatCircle | string, to: ChatCircle | string): boolean {
  const rFrom = rank(from);
  const rTo = rank(to);
  return rFrom >= 0 && rTo >= 0 && rTo > rFrom;
}

export async function reachOf(peer: RingPeer): Promise<RingRow[]> {
  const rows: RingRow[] = [];

  try {
    if (phoneBridge()) {
      const state = await whereApi.get(peer.id);
      if (state.mine !== null) {
        rows.push({ key: 'where' });
      }
    }
  } catch {
    /* недоступний міст не додає рядок */
  }

  try {
    const prefs = await useMoments.getState().loadPrefs();
    if (prefs?.audience.kind === 'people' && prefs.audience.ids.includes(peer.id)) {
      rows.push({ key: 'moments' });
    }
  } catch {
    /* помилка читання налаштувань моментів ігнорується */
  }

  try {
    const serverPinned = useMessengerStore.getState().chats.find((c) => c.id === peer.chatId)?.pinned;
    if (chatPins.apply(peer.chatId, serverPinned)) {
      rows.push({ key: 'pin' });
    }
  } catch {
    /* помилка читання закріплення ігнорується */
  }

  try {
    const q = await messengerApi.quiet();
    if (typeof q?.enabled === 'boolean' && Array.isArray(q.except) && q.except.includes(peer.id)) {
      rows.push({ key: 'quiet' });
    }
  } catch {
    /* помилка читання тихих ночей ігнорується */
  }

  return rows;
}

const notifyWhereChanged = (): void => {
  if (typeof window === 'undefined') return;
  const hook = (window as unknown as { __phantomWhereChanged?: unknown }).__phantomWhereChanged;
  if (typeof hook === 'function') {
    (hook as () => void)();
  } else if (hook && typeof (hook as Iterable<unknown>)[Symbol.iterator] === 'function') {
    for (const fn of hook as Iterable<unknown>) {
      if (typeof fn === 'function') (fn as () => void)();
    }
  }
};

export async function apply(
  peer: RingPeer,
  keys: RingRowKey[],
): Promise<Record<RingRowKey, 'done' | 'failed'>> {
  const outcomes = await Promise.allSettled(
    keys.map(async (key) => {
      switch (key) {
        case 'where': {
          await whereApi.stop(peer.id);
          notifyWhereChanged();
          break;
        }
        case 'moments': {
          await useMoments.getState().revokePeer(peer.id);
          break;
        }
        case 'pin': {
          chatPins.set(peer.chatId, false);
          useMessengerStore.setState((s) => ({
            chats: s.chats.map((c) => (c.id === peer.chatId ? { ...c, pinned: false } : c)),
          }));
          break;
        }
        case 'quiet': {
          const q = await messengerApi.quiet();
          if (typeof q?.enabled !== 'boolean' || !Array.isArray(q.except)) {
            throw new Error('quiet_unavailable');
          }
          await messengerApi.setQuiet({ except: q.except.filter((x) => x !== peer.id) });
          break;
        }
      }
    }),
  );

  const out = {} as Record<RingRowKey, 'done' | 'failed'>;
  keys.forEach((key, idx) => {
    out[key] = outcomes[idx]?.status === 'fulfilled' ? 'done' : 'failed';
  });
  return out;
}
