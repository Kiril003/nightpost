import type { Message } from '../types/messenger';

export type PayGuardReason = 'key-changed' | 'first-payment';

export interface PayGuardChat {
  contactKeyChangedAt?: string;
  messages?: Array<Pick<Message, 'isSelf' | 'status' | 'payment' | 'sentAt'>>;
}

export const HOLD_MS = 2000;

const DAY_MS = 86_400_000;
const KEY_WINDOW_MS = 7 * DAY_MS;

function parseUtcMs(raw: string | undefined): number {
  if (!raw) return NaN;
  const s = raw.trim();
  if (!s) return NaN;
  const norm =
    /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/.test(s) && !/(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)
      ? `${s.replace(' ', 'T')}Z`
      : s;
  return Date.parse(norm);
}

export function payGuard(
  chat: PayGuardChat | undefined,
  nowMs: number,
): { reason: PayGuardReason; daysAgo?: number } | null {
  if (!chat) return null;

  const changedMs = parseUtcMs(chat.contactKeyChangedAt);
  if (Number.isFinite(changedMs)) {
    const diff = nowMs - changedMs;
    if (diff >= 0 && diff <= KEY_WINDOW_MS) {
      return { reason: 'key-changed', daysAgo: Math.floor(diff / DAY_MS) };
    }
  }

  const earlier = (chat.messages ?? []).filter(
    (m) => Boolean(m.isSelf && m.payment && m.status !== 'failed'),
  );
  if (earlier.length === 0) {
    return { reason: 'first-payment' };
  }
  return null;
}
