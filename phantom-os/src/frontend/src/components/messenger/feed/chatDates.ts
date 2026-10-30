import type { Message } from '../../../types/messenger';

const asDate = (iso: string): Date => new Date(iso.endsWith('Z') || /[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`);

const localDay = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** Перше повідомлення за день `day` (YYYY-MM-DD, місцевий) або найближче після нього. */
export function firstMessageOnOrAfter(messages: readonly Message[], day: string): Message | undefined {
  let best: { m: Message; t: number } | undefined;
  for (const m of messages) {
    if (!m?.sentAt) continue;
    const d = asDate(m.sentAt);
    if (Number.isNaN(d.getTime()) || localDay(d) < day) continue;
    if (!best || d.getTime() < best.t) best = { m, t: d.getTime() };
  }
  return best?.m;
}

export function dayBounds(messages: readonly Message[]): { min: string; max: string } | null {
  const days = messages
    .filter((m) => m?.sentAt)
    .map((m) => asDate(m.sentAt as string))
    .filter((d) => !Number.isNaN(d.getTime()))
    .map(localDay)
    .sort();
  return days.length ? { min: days[0], max: days[days.length - 1] } : null;
}

/** Текстовий експорт розмови: рядок на повідомлення, без медіа-байтів. */
export function chatToText(title: string, messages: readonly Message[], mediaLabel: string): string {
  const lines = messages
    .filter((m) => m && !m.isDeleted)
    .map((m) => {
      const when = m.sentAt ? asDate(m.sentAt) : null;
      const stamp = when && !Number.isNaN(when.getTime())
        ? `${localDay(when)} ${String(when.getHours()).padStart(2, '0')}:${String(when.getMinutes()).padStart(2, '0')}`
        : m.timestamp;
      const body = m.text?.trim() || (m.type === 'text' ? '' : `[${mediaLabel}: ${m.type}]`);
      return `${stamp} ${m.senderName}: ${body}`;
    });
  return `${title}\n\n${lines.join('\n')}\n`;
}
