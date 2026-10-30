import type { PlaceEvent } from '../placesApi';

export function splitEvents<T extends Pick<PlaceEvent, 'event_id' | 'starts' | 'ends' | 'cancelled'>>(
  events: readonly T[],
  nowLocalIso: string,
): { upcoming: T[]; past: T[] } {
  const sorted = [...events].sort((a, b) => {
    if (a.starts !== b.starts) return a.starts < b.starts ? -1 : 1;
    return a.event_id < b.event_id ? -1 : a.event_id > b.event_id ? 1 : 0;
  });
  const upcoming: T[] = [];
  const past: T[] = [];
  for (const ev of sorted) {
    const cutoff = ev.ends ?? ev.starts;
    if (cutoff < nowLocalIso) {
      past.push(ev);
    } else {
      upcoming.push(ev);
    }
  }
  return { upcoming, past };
}

export function capacityInput(text: string): { ok: boolean; value: number | null } {
  const trimmed = text.trim();
  if (!trimmed) return { ok: true, value: null };
  if (!/^[1-9]\d*$/.test(trimmed)) return { ok: false, value: null };
  const n = Number(trimmed);
  if (!Number.isInteger(n) || n < 1 || n > 10000) return { ok: false, value: null };
  return { ok: true, value: n };
}

export function stateBody(
  text: string,
  cancelled: boolean,
): { capacity: number | null; cancelled: boolean } | null {
  const cap = capacityInput(text);
  if (!cap.ok) return null;
  return { capacity: cap.value, cancelled: Boolean(cancelled) };
}

export function fillText(going: number, capacity: number | null): { going: number; of: number | null } {
  return { going, of: capacity };
}
