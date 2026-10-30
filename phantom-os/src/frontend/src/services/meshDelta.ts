/**
 * Дельти меша телефона (MESSENGER_V2 §2.2, 1Б).
 *
 * Доти телефон на кожну зміну журналу кликав `__phantomMeshChanged`, і веб
 * перечитував увесь список і всю відкриту розмову. Тепер телефон каже, ЯКІ
 * розмови зачеплено (`{convs, lines}`, згладжено 50 мс), а веб дочитує лише
 * їх: рядки списку — `conversationsByIds`, відкриту розмову — різницею
 * `messagesChanged`. Повне перечитування лишається запасом на випадок, коли
 * телефон опори не має або дочитати не вдалося.
 */
import { tNow } from '../components/chat/tNow';
import type { Message } from '../types/messenger';
import type { NodeConversation, NodeMessage } from './messengerApi';
import { quoteOfRow } from './wireCarry';

export interface MeshDelta {
  convs: string[];
  lines?: Record<string, string[]>;
}

export const MESH_DELTA_WINDOW_MS = 50;

export interface MeshDeltaDeps {
  activeChatId: () => string | null;
  conversationsByIds: (ids: string[]) => Promise<{ rows: NodeConversation[]; gone: string[] }>;
  messagesChanged: (chatId: string) => Promise<{ upsert?: NodeMessage[]; remove?: string[]; full?: boolean }>;
  mergeConversations: (rows: NodeConversation[], gone?: string[]) => void;
  mergeMessages: (chatId: string, upsert: NodeMessage[], remove: string[]) => void;
  reloadChat: (chatId: string) => Promise<void> | void;
  refreshAll: () => Promise<void> | void;
  /** Закрита розмова, у якій щось змінилось: її хвіст може вже не бути правдою. */
  staleTail?: (chatId: string, lineIds?: string[]) => void;
}

/**
 * Приймач дельт: усе, що прийшло за вікно, — одним дочитуванням, і не
 * паралельно з попереднім (інакше старша відповідь могла б лягти поверх новішої).
 */
export function createMeshDeltaSink(deps: MeshDeltaDeps, windowMs = MESH_DELTA_WINDOW_MS) {
  const pending = new Set<string>();
  const pendingLines = new Map<string, Set<string> | null>();
  let timer: ReturnType<typeof setTimeout> | null = null;
  let running: Promise<void> = Promise.resolve();

  const flush = async () => {
    timer = null;
    const convs = [...pending];
    const lines = new Map(pendingLines);
    pending.clear();
    pendingLines.clear();
    if (!convs.length) return;
    const active = deps.activeChatId();
    for (const c of convs) {
      if (c === active) continue;
      const ids = lines.get(c);
      deps.staleTail?.(c, ids ? [...ids] : undefined);
    }
    const jobs: Array<Promise<unknown>> = [
      deps
        .conversationsByIds(convs)
        .then((r) => deps.mergeConversations(r.rows, r.gone))
        .catch(() => deps.refreshAll()),
    ];
    if (active && convs.includes(active)) {
      jobs.push(
        deps
          .messagesChanged(active)
          // Не різниця (опори нема або шлях її не вміє, як «Збережене») — цілком.
          .then((d) =>
            d && !d.full && (Array.isArray(d.upsert) || Array.isArray(d.remove))
              ? deps.mergeMessages(active, d.upsert ?? [], d.remove ?? [])
              : deps.reloadChat(active),
          )
          .catch(() => deps.reloadChat(active)),
      );
    }
    await Promise.all(jobs);
  };

  return (delta: MeshDelta) => {
    if (!delta || !Array.isArray(delta.convs)) return;
    delta.convs.forEach((c) => {
      if (typeof c !== 'string') return;
      pending.add(c);
      const ids = delta.lines?.[c];
      const had = pendingLines.get(c);
      if (!Array.isArray(ids) || had === null) pendingLines.set(c, null);
      else pendingLines.set(c, new Set([...(had ?? []), ...ids.filter((x) => typeof x === 'string')]));
    });
    if (timer || !pending.size) return;
    timer = setTimeout(() => {
      running = running.then(flush, flush);
    }, windowMs);
  };
}

/**
 * Злити змінені рядки в стрічку: наявні — на своєму місці (місцеве
 * «закріплено» переживає), нові — в кінець за `seq`, стерті — геть.
 * Оптимістичну бульбашку з тим самим `client_id` замінює рядок телефона.
 */
export function mergeRows(
  current: Message[],
  upsert: NodeMessage[],
  remove: string[],
  toMessage: (row: NodeMessage) => Message,
  names?: { self: string; peer: string },
): Message[] {
  if (!upsert.length && !remove.length) return current;
  const gone = new Set(remove);
  const byId = new Map<string, Message>();
  const clientIds = new Map<string, Message>();
  const sorted = [...upsert].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
  for (const r of sorted) {
    const msg = toMessage(r);
    byId.set(r.id, msg);
    if (r.client_id) clientIds.set(r.client_id, msg);
  }
  const placed = new Set<string>();
  const out: Message[] = [];
  for (const m of current) {
    if (gone.has(m.id)) continue;
    const fresh = byId.get(m.id) ?? clientIds.get(m.id);
    if (fresh) {
      const merged = {
        ...fresh,
        ...(m.isPinned && !fresh.pinShared ? { isPinned: true } : {}),
        ...(m.replyTo && !fresh.replyTo ? { replyTo: m.replyTo } : {}),
      };
      out.push(merged);
      placed.add(fresh.id);
      continue;
    }
    out.push(m);
  }
  for (const r of sorted) if (!placed.has(r.id)) out.push(byId.get(r.id)!);
  // Відповіді на лист, що вже в стрічці, — з самої стрічки, а не «поза стрічкою».
  const all = new Map(out.map((m) => [m.id, m]));
  return out.map((m) => {
    const row = sorted.find((r) => r.id === m.id);
    const target = row?.reply_to_id;
    if (!target || m.replyTo) return m;
    const quote = quoteOfRow(row);
    const orig = all.get(target);
    if (!orig) {
      return { ...m, replyTo: { id: target, senderName: '', text: tNow('feed.replyOutside'), ...(quote ? { quoteSelectedText: quote } : {}) } };
    }
    return {
      ...m,
      replyTo: {
        id: orig.id,
        senderName: orig.isSelf ? (names?.self ?? (orig.senderName || tNow('chatList.you'))) : (orig.senderName || names?.peer || ''),
        text: orig.text ?? '',
        type: orig.type,
        ...(quote ? { quoteSelectedText: quote } : {}),
      },
    };
  });
}
