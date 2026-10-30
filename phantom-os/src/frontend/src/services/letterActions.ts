/**
 * Одна готова дія з листа — те, що людина зробила б руками одразу після
 * прочитання. Знайдене бере `understand`; тут лише вибір НАЙВАЖЛИВІШОГО, слова
 * підказки й саме виконання. Закрита чи виконана підказка більше не
 * зʼявляється — ні під листом, ні в «Чекають на вас».
 */
import { currentLocale, tNow } from '../components/chat/tNow';
import { translate, type Locale, type MessageKey } from '../i18n';
import { useSyncExternalStore } from 'react';
import { sentMoment, understand, type Finding } from './understand';
import { addReminder } from './letterReminders';
import { copyText } from '../utils/messengerInvite';
import { useUIStore } from '../stores/uiStore';
import { plainText } from './plainText';

export type LetterAction =
  | { kind: 'remind'; at: Date; when: string }
  | { kind: 'copy-parcel'; value: string }
  | { kind: 'copy-phone'; value: string };

/** Скільки до межі чи зустрічі нагадати: «до 20:00» → о 19:40. */
const LEAD_MS = 20 * 60_000;

const WEEKDAY_KEYS = ['letter.wd0', 'letter.wd1', 'letter.wd2', 'letter.wd3', 'letter.wd4', 'letter.wd5', 'letter.wd6'] as const;

const pad = (n: number) => String(n).padStart(2, '0');
const clock = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const dayStart = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();

/** «о 19:40», «завтра о 09:40», «в неділю зранку», «02.10 о 18:00». */
export function whenWords(at: Date, hasClock: boolean, now: Date, lang: Locale = currentLocale()): string {
  const tr = (key: MessageKey, vars?: Record<string, string | number>) => translate(key, lang, vars);
  const days = Math.round((dayStart(at) - dayStart(now)) / 86_400_000);
  const day = days === 0 ? '' : days === 1 ? tr('letter.tomorrow') : days < 7 ? tr(WEEKDAY_KEYS[at.getDay()]) : `${pad(at.getDate())}.${pad(at.getMonth() + 1)}`;
  if (!hasClock) return days === 0 ? tr('letter.todayMorning') : tr('letter.dayMorning', { day });
  return day ? tr('letter.dayAt', { day, clock: clock(at) }) : tr('letter.at', { clock: clock(at) });
}

/** Згрупувати цифри так, як людина їх диктує: 2045 1188 3302. */
export function groupDigits(value: string): string {
  if (!/^\d+$/.test(value)) return value;
  return value.replace(/(\d{4})(?=\d)/g, '$1 ');
}

export function phoneWords(e164: string): string {
  const m = /^\+380(\d{2})(\d{3})(\d{2})(\d{2})$/.exec(e164);
  return m ? `0${m[1]} ${m[2]} ${m[3]} ${m[4]}` : e164;
}

function remindFrom(f: Extract<Finding, { kind: 'time' }>, now: Date): LetterAction | null {
  if (!f.hasClock) {
    const morning = new Date(f.at.getFullYear(), f.at.getMonth(), f.at.getDate(), 9, 0);
    if (morning.getTime() <= now.getTime() + 60_000) return null;
    return { kind: 'remind', at: morning, when: whenWords(morning, false, now) };
  }
  if (f.at.getTime() <= now.getTime() + 60_000) return null;
  const early = new Date(f.at.getTime() - LEAD_MS);
  const at = early.getTime() > now.getTime() + 60_000 ? early : f.at;
  return { kind: 'remind', at, when: whenWords(at, true, now) };
}

/** Найкорисніша дія з листа або нічого. Час, що вже минув, — не дія. */
export function actionFor(text: string | null | undefined, sentAt: Date, now: Date = new Date()): LetterAction | null {
  for (const f of understand(text, sentAt)) {
    if (f.kind === 'time') {
      const a = remindFrom(f, now);
      if (a) return a;
    } else if (f.kind === 'parcel') {
      return { kind: 'copy-parcel', value: f.number };
    } else if (f.kind === 'phone') {
      return { kind: 'copy-phone', value: f.number };
    }
  }
  return null;
}

/** Слова підказки під листом і кнопки. */
export function hintWords(a: LetterAction): { say: string; button: string; card: string } {
  switch (a.kind) {
    case 'remind':
      return { say: tNow('letter.remindAsk', { when: a.when }), button: tNow('letter.yes'), card: tNow('letter.remindCard', { when: a.when }) };
    case 'copy-parcel':
      return { say: tNow('letter.parcelSay', { n: groupDigits(a.value) }), button: tNow('letter.copy'), card: tNow('letter.parcelCard') };
    case 'copy-phone':
      return { say: tNow('letter.phoneSay', { n: phoneWords(a.value) }), button: tNow('letter.copy'), card: tNow('letter.phoneCard') };
  }
}

export async function runAction(a: LetterAction, ctx: { who: string; text: string; chatId: string }): Promise<boolean> {
  const toast = useUIStore.getState().toast;
  if (a.kind === 'remind') {
    await addReminder({ at: a.at.getTime(), who: ctx.who, text: ctx.text.slice(0, 160), chatId: ctx.chatId });
    toast({ kind: 'success', message: tNow('letter.willRemind', { when: a.when }) });
    return true;
  }
  const ok = await copyText(a.value);
  toast(ok ? { kind: 'success', message: tNow('letter.copied') } : { kind: 'warn', message: tNow('letter.copyFailed') });
  return ok;
}

// ── Закриті підказки ────────────────────────────────────────────────────────

const DONE_KEY = 'phantom.letterHints.done';
const DONE_CAP = 400;
let done: string[] | null = null;
let version = 0;
const listeners = new Set<() => void>();

function doneList(): string[] {
  if (done) return done;
  try {
    const raw = JSON.parse(localStorage.getItem(DONE_KEY) || '[]');
    done = Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    done = [];
  }
  return done;
}

export function isHintDone(letterId: string): boolean {
  return doneList().includes(letterId);
}

export function markHintDone(letterId: string): void {
  if (isHintDone(letterId)) return;
  done = [...doneList(), letterId].slice(-DONE_CAP);
  try {
    localStorage.setItem(DONE_KEY, JSON.stringify(done));
  } catch {
    /* без сховища — лише до закриття сторінки */
  }
  version++;
  listeners.forEach((l) => l());
}

/** Лише для тестів. */
export function resetHintsDone(): void {
  done = null;
  version++;
  listeners.forEach((l) => l());
}

function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

/** Перемальовує компонент, щойно будь-яку підказку закрили. */
export function useHintsVersion(): number {
  return useSyncExternalStore(subscribe, () => version, () => version);
}

// ── Відповіді одним дотиком ─────────────────────────────────────────────────

const COMING = /(?<![\p{L}])(будеш|будете|прийдеш|прийдете|приїдеш|приїдете|підійдеш|заїдеш|встигнеш|встигнете|зустрін\p{L}*|побачимось|come|be there|make it)(?![\p{L}])/iu;

/**
 * 2–3 короткі відповіді на питання чи прохання, або нічого. На відкрите
 * питання («коли?», «де?») відповіді не знати — тож і кнопок немає.
 */
export function quickReplies(text: string | null | undefined, sentAt: Date, now: Date = new Date()): string[] {
  const found = understand(text, sentAt);
  const ask = found.find((f): f is Extract<Finding, { kind: 'ask' }> => f.kind === 'ask');
  if (!ask || ask.open) return [];
  const latin = !/\p{Script=Cyrillic}/u.test(text || '');
  if (ask.request) return latin ? ['OK', 'Later'] : ['Добре', 'Зараз']; // i18n-data: replies follow the letter's script, not the UI language
  const out = latin ? ['Yes', 'No'] : ['Так', 'Ні']; // i18n-data: replies follow the letter's script, not the UI language
  const time = found.find((f): f is Extract<Finding, { kind: 'time' }> => f.kind === 'time');
  if (!latin && time?.hasClock && time.at.getTime() > now.getTime() && COMING.test(text || '')) {
    out.push(`Буду ${whenWords(time.at, true, now, 'uk')}`); // i18n-data: Cyrillic letter gets a Ukrainian reply
  }
  return out;
}

// ── Який лист отримує підказку ──────────────────────────────────────────────

interface LetterLike {
  id: string;
  senderId?: string;
  type?: string;
  text?: string;
  isSelf?: boolean;
  isDeleted?: boolean;
  sentAt?: string;
  voiceData?: { transcript?: string };
}

/** Що людина прочитала в листі: текст або розшифровка голосового. */
export function letterText(m: LetterLike): string {
  if (m.type === 'voice') return (m.voiceData?.transcript || '').trim();
  if (m.type && m.type !== 'text') return '';
  return plainText(m.text).trim();
}

/** Ключ підказки — той самий у розмові й у списку, де листа може не бути цілком. */
export function hintKey(chatId: string, text: string): string {
  return `${chatId}|${text.replace(/\s+/g, ' ').trim().slice(0, 60)}`;
}

export interface LetterHintPick {
  letterId: string;
  key: string;
  text: string;
  action: LetterAction;
}

/**
 * Підказка лише під ОСТАННІМ чужим листом розмови: старі листи вже
 * прочитані, а підказка під кожним — це шум, не допомога.
 */
export function hintFor(
  chatId: string,
  letters: LetterLike[],
  isMine: (m: LetterLike) => boolean,
  now: Date = new Date(),
): LetterHintPick | null {
  for (let i = letters.length - 1; i >= 0; i--) {
    const m = letters[i];
    if (!m || m.isDeleted || isMine(m)) continue;
    const text = letterText(m);
    if (!text) return null;
    const key = hintKey(chatId, text);
    if (isHintDone(key)) return null;
    const action = actionFor(text, sentMoment(m.sentAt) ?? now, now);
    return action ? { letterId: m.id, key, text, action } : null;
  }
  return null;
}
