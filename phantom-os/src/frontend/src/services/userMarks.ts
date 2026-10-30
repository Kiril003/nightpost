import type { Message } from '../types/messenger';

export const PINS_KEY = 'np.pins1to1.v1';
export const VOTES_KEY = 'np.pollVotes.v1';

type Votes = Record<string, string[]>;

function readJson<T>(key: string, ok: (v: unknown) => v is T, empty: T): T {
  try {
    const raw = localStorage.getItem(key);
    const v: unknown = raw ? JSON.parse(raw) : null;
    return ok(v) ? v : empty;
  } catch {
    return empty;
  }
}

const isIds = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === 'string');
const isVotes = (v: unknown): v is Votes =>
  !!v && typeof v === 'object' && !Array.isArray(v) && Object.values(v as object).every(isIds);

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* private window: marks live until the tab closes */
  }
}

let pins = new Set(readJson(PINS_KEY, isIds, []));
let votes: Votes = readJson(VOTES_KEY, isVotes, {});

function myVoteIn(m: Message, me: string): string[] | null {
  if (m.pollData) return m.pollData.options.filter((o) => (o.voters ?? []).includes(me)).map((o) => String(o.id));
  const d = m.votingData;
  if (!d) return null;
  if (d.anonymous) return Array.isArray(d.pick) ? d.pick.map(String) : [];
  return (d.options ?? []).filter((o) => (o.voters ?? []).includes(me)).map((o) => String(o.id));
}

function withVote(m: Message, me: string, ids: string[]): Message {
  const add = <O extends { id: string | number; votes?: number; voters?: string[] }>(o: O): O =>
    ids.includes(String(o.id)) ? { ...o, votes: (o.votes ?? 0) + 1, voters: [...(o.voters ?? []), me] } : o;
  if (m.pollData) {
    const options = m.pollData.options.map(add);
    return { ...m, pollData: { ...m.pollData, options, totalVotes: options.reduce((s, o) => s + o.votes, 0) } };
  }
  const d = m.votingData!;
  if (d.anonymous) {
    const options = (d.options ?? []).map((o) => (ids.includes(String(o.id)) ? { ...o, votes: (o.votes ?? 0) + 1 } : o));
    return { ...m, votingData: { ...d, options, pick: ids, totalVotes: (d.totalVotes ?? 0) + ids.length } };
  }
  const options = (d.options ?? []).map(add);
  return { ...m, votingData: { ...d, options, totalVotes: (d.totalVotes ?? 0) + ids.length } };
}

export const userMarks = {
  pinned: (messageId: string): boolean => pins.has(messageId),

  pin(messageId: string, on: boolean): void {
    if (pins.has(messageId) === on) return;
    pins = new Set(pins);
    if (on) pins.add(messageId);
    else pins.delete(messageId);
    write(PINS_KEY, [...pins]);
  },

  vote(messageId: string, optionIds: string[]): void {
    const next = { ...votes };
    if (optionIds.length) next[messageId] = optionIds;
    else delete next[messageId];
    votes = next;
    write(VOTES_KEY, votes);
  },

  /** A reread from the node carries neither my local pin nor my local vote. */
  apply(messages: Message[], me: string): Message[] {
    if (pins.size === 0 && Object.keys(votes).length === 0) return messages;
    let changed = false;
    const out = messages.map((m) => {
      let next = m;
      if (!m.isPinned && pins.has(m.id)) next = { ...next, isPinned: true };
      const ids = votes[m.id];
      if (ids && me && myVoteIn(next, me)?.length === 0) next = withVote(next, me, ids);
      if (next !== m) changed = true;
      return next;
    });
    return changed ? out : messages;
  },

  /** First run after the split: lift pins and my votes out of the chat cache once. */
  adoptFromCache(chats: Array<{ messages: Message[] }> | null | undefined, me: string): void {
    try {
      if (localStorage.getItem(PINS_KEY) !== null || localStorage.getItem(VOTES_KEY) !== null) return;
    } catch {
      return;
    }
    const all = (chats ?? []).flatMap((c) => c.messages ?? []);
    pins = new Set(all.filter((m) => m.isPinned).map((m) => m.id));
    votes = {};
    for (const m of all) {
      const ids = me ? myVoteIn(m, me) : null;
      if (ids && ids.length) votes[m.id] = ids;
    }
    write(PINS_KEY, [...pins]);
    write(VOTES_KEY, votes);
  },

  /** Tests only. */
  reload(): void {
    pins = new Set(readJson(PINS_KEY, isIds, []));
    votes = readJson(VOTES_KEY, isVotes, {});
  },
};
