import type { MessageKey } from '../i18n/messages/uk';
import type { DayState } from './dayApi';

export type DeclineReplyKind = 'driving' | 'busy' | 'later';

export interface DeclineReply {
  kind: DeclineReplyKind;
  text: string;
}

export function hhmm(ms: number): string {
  const d = new Date(ms);
  const h = d.getHours().toString().padStart(2, '0');
  const m = d.getMinutes().toString().padStart(2, '0');
  return `${h}:${m}`;
}

/** Кінець поточної події в розкладі, якщо вона триває просто зараз. */
export function busyUntil(day: DayState | null, nowMs: number): number | null {
  if (!day) return null;
  let maxEnd: number | null = null;
  for (const item of day.items) {
    if (
      item.kind === 'event' &&
      !item.allDay &&
      item.endMs !== null &&
      item.atMs <= nowMs &&
      nowMs < item.endMs
    ) {
      if (maxEnd === null || item.endMs > maxEnd) {
        maxEnd = item.endMs;
      }
    }
  }
  return maxEnd;
}

export function declineReplies(input: {
  nowMs: number;
  day: DayState | null;
  nav: { arriveAtMs: number } | null;
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
}): DeclineReply[] {
  const { nowMs, day, nav, t } = input;
  const out: DeclineReply[] = [];

  if (nav && nav.arriveAtMs > nowMs) {
    out.push({
      kind: 'driving',
      text: t('declineReplies.driving', { time: hhmm(nav.arriveAtMs) }),
    });
  }

  const until = busyUntil(day, nowMs);
  if (until !== null) {
    out.push({
      kind: 'busy',
      text: t('declineReplies.busy', { time: hhmm(until) }),
    });
  }

  out.push({
    kind: 'later',
    text: t('declineReplies.later'),
  });

  return out;
}

export async function pick(
  reply: DeclineReply,
  send: (text: string) => void | Promise<void>,
): Promise<void> {
  await send(reply.text);
}
