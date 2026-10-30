import type { Message } from '../../types/messenger';

export interface PayNote {
  chatId: string;
  ref: string;
  note: string;
  at: number;
}

const PAY_NOTES_KEY = 'phantom_pay_notes';
const MAX_NOTES = 50;
const TTL_MS = 24 * 3600_000;
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

const memo = new Map<string, PayNote>();

export function groupPayRef(): string {
  const bytes = new Uint8Array(12);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  let out = 'gp-';
  for (let i = 0; i < bytes.length; i++) {
    out += ALPHABET[bytes[i] % ALPHABET.length];
  }
  return out;
}

function isPayNote(v: unknown): v is PayNote {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
  const o = v as Record<string, unknown>;
  return (
    typeof o.chatId === 'string' &&
    typeof o.ref === 'string' &&
    typeof o.note === 'string' &&
    typeof o.at === 'number' &&
    Number.isFinite(o.at)
  );
}

function readStored(): PayNote[] | null {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(PAY_NOTES_KEY) || '[]');
    if (!Array.isArray(raw)) return [];
    return raw.filter(isPayNote);
  } catch {
    return null;
  }
}

function writeStored(list: PayNote[]): boolean {
  try {
    localStorage.setItem(PAY_NOTES_KEY, JSON.stringify(list.slice(-MAX_NOTES)));
    return true;
  } catch {
    return false;
  }
}

export function pendingPayNotes(): PayNote[] {
  const stored = readStored();
  if (stored !== null) {
    const byRef = new Map<string, PayNote>();
    for (const n of stored) byRef.set(n.ref, n);
    for (const [k, n] of memo) byRef.set(k, n);
    return Array.from(byRef.values()).slice(-MAX_NOTES);
  }
  return Array.from(memo.values()).slice(-MAX_NOTES);
}

export function holdPayNote(n: PayNote): void {
  const current = pendingPayNotes().filter((x) => x.ref !== n.ref);
  const next = [...current, n].slice(-MAX_NOTES);
  memo.set(n.ref, n);
  if (memo.size > MAX_NOTES) {
    const firstKey = memo.keys().next().value;
    if (firstKey !== undefined) memo.delete(firstKey);
  }
  if (writeStored(next)) {
    memo.delete(n.ref);
  }
}

export function dropPayNote(ref: string): void {
  memo.delete(ref);
  const stored = readStored();
  if (stored !== null) {
    writeStored(stored.filter((x) => x.ref !== ref));
  }
}

export function judgePayNotes(
  notes: readonly PayNote[],
  chatId: string,
  messages: readonly Message[],
  meId: string,
  now: number,
): { post: PayNote[]; drop: PayNote[] } {
  const post: PayNote[] = [];
  const drop: PayNote[] = [];

  for (const note of notes) {
    if (note.chatId !== chatId) continue;
    const match = messages.find(
      (m) => !m.isDeleted && m.senderId === meId && m.payment && m.payment.ref === note.ref,
    );
    if (match?.payment) {
      if (match.payment.status === 'confirmed') {
        post.push(note);
      } else if (match.payment.status === 'failed') {
        drop.push(note);
      }
    } else if (now - note.at > TTL_MS) {
      drop.push(note);
    }
  }

  return { post, drop };
}
