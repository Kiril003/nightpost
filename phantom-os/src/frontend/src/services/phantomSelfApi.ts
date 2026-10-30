/**
 * Що PHANTOM знає про людину. На телефоні — репліка памʼяті (`PhantomSelfRoute.kt`,
 * `core-memory`), на ПК — ledger вузла (`routes_memory_self.py`); форма однакова,
 * синк між ними — docs/memory/SYNC.md.
 */
import { request } from './api';

export type MemoryGroup = 'about' | 'people' | 'places' | 'events' | 'skills' | 'pending';

export interface KnownFact {
  id: string;
  text: string;
  from: 'self' | 'heard' | 'guess';
  /** Звідки факт, людськими словами й мовою вузла. */
  from_label: string;
  skill: boolean;
  /** Запис зі старих розмов, який не вдалось сказати людським реченням. */
  old?: boolean;
  at: string;
  /** Розділ екрана «Памʼять» (старі вузли його не дають — тоді «Про тебе»). */
  group?: MemoryGroup;
  pending?: boolean;
  /** Про кого запис, якщо не про власника. */
  subject?: string;
  source?: 'owner_said' | 'heard' | 'inferred';
  /** Де запис народився. */
  device?: 'phone' | 'pc';
  confidence?: number;
}

/** Чесний стан памʼяті й синку (`GET /messenger/self/memory`). */
export interface MemoryState {
  node: 'phone' | 'pc';
  items: number;
  pending: number;
  budget_tokens: number;
  /** Телефон: false — ключ сховища недоступний, памʼять лише до перезапуску. */
  persistent?: boolean;
  sync: {
    peer: 'pc' | 'phone';
    paired?: boolean;
    last_ok_ms?: number | null;
    last_try_ms?: number | null;
    last_error?: string | null;
    pending_push?: number;
  };
}

export interface MemoryExport {
  format: 'phantom-memory/1';
  exported_at: number;
  count: number;
  items: unknown[];
}

/** «неправда» — зміст заблоковано назавжди; «забудь» — стерто без сліду. */
export type ForgetWhy = 'false' | 'forget';

export interface CommitmentItem {
  id: string;
  text: string;
  person: string;
  person_name: string;
  peer_id: string | null;
  what: string;
  due_ms: number | null;
  created_ms: number;
  source_message_id: string;
  state: 'open' | 'done' | 'dropped';
}

export const phantomSelfApi = {
  facts: () => request<KnownFact[]>('GET', '/messenger/self/facts'),
  forget: (id: string, why: ForgetWhy = 'false') =>
    request<{ forgotten: boolean }>('POST', `/messenger/self/facts/${encodeURIComponent(id)}/forget`, { why }),
  /** Власник сам каже, що памʼятати. Чуже повідомлення — лише з `explicit: true` («запамʼятай це»). */
  remember: (
    text: string,
    opts: {
      origin?: 'owner_statement' | 'peer_message';
      explicit?: boolean;
      chatId?: string;
      messageId?: string;
      message_id?: string;
    } = {},
  ) => {
    const messageId = opts.messageId ?? opts.message_id;
    return request<KnownFact>('POST', '/messenger/self/facts', {
      text,
      origin: opts.origin ?? 'owner_statement',
      explicit: opts.explicit ?? false,
      ...(opts.chatId ? { chat_id: opts.chatId } : {}),
      ...(messageId ? { message_id: messageId } : {}),
    });
  },
  exportAll: () => request<MemoryExport>('GET', '/messenger/self/facts/export'),
  memoryState: () => request<MemoryState>('GET', '/messenger/self/memory'),
  voice: (on: boolean) => request<{ on: boolean; detail: string }>('POST', '/messenger/self/voice', { on }),
  voiceState: () => request<{ on: boolean; detail: string }>('GET', '/messenger/self/voice'),
  /** Разова відповідь розуму поза розмовою; без розуму — ApiError 503 з людським `detail`. */
  ask: (text: string, system?: string) =>
    request<{ answer: string }>('POST', '/messenger/self/ask', system ? { text, system } : { text }),
  commitments: () => request<CommitmentItem[]>('GET', '/messenger/self/commitments'),
  chooseCommitment: (pendingId: string, peerId: string) =>
    request<CommitmentItem>('POST', '/messenger/self/commitments', {
      choose: { pending_id: pendingId, peer_id: peerId },
    }),
  addDirectCommitment: (params: {
    person: string;
    peer_id?: string | null;
    what: string;
    text?: string;
    due_ms?: number | null;
    source_message_id?: string;
  }) => request<CommitmentItem>('POST', '/messenger/self/commitments', params),
  doneCommitment: (id: string) =>
    request<{ id: string; state: 'done' }>('POST', `/messenger/self/commitments/${encodeURIComponent(id)}/done`, {}),
  dropCommitment: (id: string) =>
    request<{ id: string; state: 'dropped' }>('POST', `/messenger/self/commitments/${encodeURIComponent(id)}/drop`, {}),
};
