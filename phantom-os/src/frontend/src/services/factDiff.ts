import { useEffect, useState } from 'react';

const KEY = 'phantom_fact_seen';
const LIMIT = 300;

export interface CardFacts {
  at: number;
}

type Seen = Record<string, CardFacts>;

const load = (): Seen => {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const out: Seen = {};
    for (const [id, v] of Object.entries(parsed as Record<string, unknown>)) {
      const at = (v as { at?: unknown } | null)?.at;
      if (typeof at === 'number' && Number.isFinite(at)) out[id] = { at };
    }
    return out;
  } catch {
    return {};
  }
};

export function seenFacts(id: string): CardFacts | null {
  return load()[id] ?? null;
}

export function rememberFacts(id: string, facts: CardFacts): void {
  const all = load();
  delete all[id];
  all[id] = facts;
  const keys = Object.keys(all);
  for (const k of keys.slice(0, Math.max(0, keys.length - LIMIT))) delete all[k];
  try {
    localStorage.setItem(KEY, JSON.stringify(all));
  } catch {
    /* без сховища різниця живе до закриття */
  }
}

export function factDiff(before: CardFacts | null, now: CardFacts | null): { atFrom: number; atTo: number } | null {
  if (!before || !now || before.at === now.at) return null;
  return { atFrom: before.at, atTo: now.at };
}

/** Що змінилось з того разу, як я бачив картку; знімок оновлюється, тож наступного разу різниці вже немає. */
export function useFactDiff(id: string | null, facts: CardFacts | null) {
  const [before] = useState(() => (id ? seenFacts(id) ?? facts : null));
  const at = facts?.at;
  useEffect(() => {
    if (id && at !== undefined) rememberFacts(id, { at });
  }, [id, at]);
  return factDiff(before, facts);
}
