import { ApiError, request } from './api';

export interface PhoneReminder {
  id: string;
  text: string;
  atMs: number;
}

interface WireReminder {
  id: string;
  text: string;
  at_ms: number;
}

export async function listPhoneReminders(): Promise<PhoneReminder[]> {
  try {
    const rows = await request<WireReminder[]>('GET', '/messenger/reminders');
    if (!Array.isArray(rows)) return [];
    return rows
      .filter((r) => r && typeof r.id === 'string' && typeof r.at_ms === 'number')
      .map((r) => ({
        id: r.id,
        text: typeof r.text === 'string' ? r.text : '',
        atMs: r.at_ms,
      }))
      .sort((a, b) => a.atMs - b.atMs);
  } catch {
    return [];
  }
}

export async function cancelPhoneReminder(id: string): Promise<boolean> {
  try {
    await request<{ removed: boolean }>('DELETE', `/messenger/reminders/${encodeURIComponent(id)}`);
    return true;
  } catch {
    return false;
  }
}

export async function snoozePhoneReminder(id: string, minutes = 10): Promise<PhoneReminder | null> {
  try {
    const res = await request<{ id: string; at_ms: number }>(
      'POST',
      `/messenger/reminders/${encodeURIComponent(id)}/snooze`,
      { minutes },
    );
    if (!res || typeof res.id !== 'string' || typeof res.at_ms !== 'number') return null;
    return { id: res.id, text: '', atMs: res.at_ms };
  } catch {
    return null;
  }
}

export type DayKind = 'promise' | 'reminder' | 'event' | 'plan' | 'money' | 'scheduled' | 'unread';

export interface DayItem {
  id: string;
  kind: DayKind;
  title: string;
  atMs: number;
  endMs: number | null;
  allDay: boolean;
  peerId: string | null;
  overdue: boolean;
  chatId?: string;
  money?: { due: string; token: string };
  count?: number;
  failed?: boolean;
  from?: string;
}

export interface DayState {
  items: DayItem[];
  calendar: 'granted' | 'denied';
}

interface WireDayItem {
  id?: unknown;
  kind?: unknown;
  title?: unknown;
  at_ms?: unknown;
  end_ms?: unknown;
  all_day?: unknown;
  peer_id?: unknown;
  overdue?: unknown;
}

interface WireDay {
  items?: unknown;
  calendar?: unknown;
}

const isWireDayKind = (k: unknown): k is 'promise' | 'reminder' | 'event' =>
  k === 'promise' || k === 'reminder' || k === 'event';

export const dayApi = {
  async today(): Promise<DayState | null> {
    let raw: WireDay;
    try {
      raw = await request<WireDay>('GET', '/messenger/day');
    } catch (err) {
      if (err instanceof ApiError && (err.status === 404 || err.status === 501)) return null;
      throw err;
    }
    const list = Array.isArray(raw?.items) ? (raw.items as WireDayItem[]) : [];
    const items: DayItem[] = list
      .filter(
        (r): r is WireDayItem & { id: string; kind: 'promise' | 'reminder' | 'event'; at_ms: number } =>
          Boolean(r) && typeof r.id === 'string' && isWireDayKind(r.kind) && typeof r.at_ms === 'number',
      )
      .map((r) => ({
        id: r.id,
        kind: r.kind,
        title: typeof r.title === 'string' ? r.title : '',
        atMs: r.at_ms,
        endMs: typeof r.end_ms === 'number' ? r.end_ms : null,
        allDay: r.all_day === true,
        peerId: typeof r.peer_id === 'string' ? r.peer_id : null,
        overdue: r.overdue === true,
      }));
    return {
      items,
      calendar: raw?.calendar === 'granted' ? 'granted' : 'denied',
    };
  },
  async done(id: string): Promise<void> {
    await request('POST', `/messenger/day/promises/${encodeURIComponent(id)}/done`);
  },
  async drop(id: string): Promise<void> {
    await request('DELETE', `/messenger/day/promises/${encodeURIComponent(id)}`);
  },
  async snooze(id: string, minutes: number): Promise<{ id: string; atMs: number }> {
    const res = await request<{ id: string; at_ms: number }>(
      'POST',
      `/messenger/reminders/${encodeURIComponent(id)}/snooze`,
      { minutes },
    );
    return {
      id: typeof res?.id === 'string' ? res.id : id,
      atMs: typeof res?.at_ms === 'number' ? res.at_ms : 0,
    };
  },
  async createReminder(text: string, atMs: number): Promise<void> {
    await request('POST', '/messenger/reminders', { text: text.trim().slice(0, 500), at_ms: atMs });
  },
  async removeReminder(id: string): Promise<void> {
    await request('DELETE', `/messenger/reminders/${encodeURIComponent(id)}`);
  },
  async askCalendar(): Promise<{ asked: boolean; granted: boolean }> {
    const res = await request<{ asked?: unknown; granted?: unknown }>('POST', '/messenger/day/calendar/ask');
    return { asked: res?.asked === true, granted: res?.granted === true };
  },
};
