/** Classes kit on a public page: sessions in date order and whether the course takes people (venue_requests._check_enroll). */
export type Session = { id: string; name: string; date: string; time: string; n: number; state: 'done' | 'next' | 'later' };
export type CourseState = 'open' | 'off' | 'over';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const WEEK = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'] as const;

const pad = (n: number) => String(n).padStart(2, '0');
export const localToday = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

export function sessionsOf(data: any, today: string): Session[] {
  const raw: any[] = Array.isArray(data?.sessions) ? data.sessions : [];
  const ok = raw
    .filter((s) => s && typeof s.id === 'string' && typeof s.date === 'string' && DATE.test(s.date) && !Number.isNaN(Date.parse(s.date)))
    .map((s) => ({ id: s.id, name: String(s.name ?? ''), date: s.date, time: typeof s.time === 'string' ? s.time : '' }))
    .sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time));
  let nextSeen = false;
  return ok.map((s, i) => {
    let state: Session['state'] = 'done';
    if (s.date >= today) {
      state = nextSeen ? 'later' : 'next';
      nextSeen = true;
    }
    return { ...s, n: i + 1, state };
  });
}

export function courseState(data: any, today: string): CourseState {
  const seats = data?.seats;
  const s = sessionsOf(data, today);
  if (!(typeof seats === 'number' && Number.isInteger(seats) && seats > 0) || s.length === 0) return 'off';
  return s.some((x) => x.state !== 'done') ? 'open' : 'over';
}

export function weekdaysOf(s: Session[]): (typeof WEEK)[number][] {
  const days = new Set(s.map((x) => new Date(`${x.date}T12:00:00Z`).getUTCDay()));
  return [1, 2, 3, 4, 5, 6, 0].filter((d) => days.has(d)).map((d) => WEEK[d]);
}

export function commonTime(s: Session[]): string | null {
  const t = new Set(s.map((x) => x.time));
  return t.size === 1 ? [...t][0] || null : null;
}
