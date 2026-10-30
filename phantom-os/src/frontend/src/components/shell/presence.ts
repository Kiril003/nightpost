/**
 * Присутність PHANTOM — один стан, який бачить уся оболонка ПК.
 *
 * Не «індикатор підсистеми», а те, як система зараз ПОРУЧ із людиною:
 *   спокій    — нічого не чекає на людину, нічого не робиться;
 *   пильність — хтось чекає відповіді, або ядро саме стало на варту;
 *   робота    — PHANTOM щось робить за людину (агент виконує доручення);
 *   тривога   — ядро на варті й бачить поруч когось, крім власника.
 *
 * Кожен стан виводиться лише з того, що справді прийшло від вузла: стану
 * автомата, знімка контексту, стану агентів і непрочитаних листів. Немає
 * сигналу — спокій, а не вигадана «активність».
 */
import { SystemState } from '@shared/types';
import type { ContextSnapshot } from '@shared/types';
import { tNow, tPlural } from '../chat/tNow';
export type Mood = 'calm' | 'watch' | 'work' | 'alarm';

export interface PresenceInput {
  state: SystemState;
  context: ContextSnapshot | null;
  /** Агент (передній чи фоновий) зараз виконує доручення. */
  agentBusy: boolean;
  /** Скільки людей чекає відповіді (непрочитані особисті розмови). */
  waiting: number;
}

export interface Presence {
  mood: Mood;
  /** Слово під кулею й у смузі. Звичайна мова, з великої літери. */
  word: string;
}

const WORD: Record<Mood, string> = {
  get calm() { return tNow('presence.word.calm'); },
  get watch() { return tNow('presence.word.watch'); },
  get work() { return tNow('presence.word.work'); },
  get alarm() { return tNow('presence.word.alarm'); },
};

export function presenceOf({ state, context, agentBusy, waiting }: PresenceInput): Presence {
  const onGuard = state === SystemState.SENTINEL;
  let mood: Mood = 'calm';
  if (onGuard && context?.presence.other_detected) mood = 'alarm';
  else if (agentBusy) mood = 'work';
  else if (onGuard || waiting > 0) mood = 'watch';
  return { mood, word: WORD[mood] };
}

/** Частина доби за годинником цього комп'ютера — для привітання й фази форми. */
export type DayPart = 'night' | 'dawn' | 'day' | 'dusk';

export function dayPartAt(hour: number): DayPart {
  if (hour >= 5 && hour < 7) return 'dawn';
  if (hour >= 7 && hour < 19) return 'day';
  if (hour >= 19 && hour < 22) return 'dusk';
  return 'night';
}

const GREETING: Record<DayPart, string> = {
  get dawn() { return tNow('presence.greet.dawn'); },
  get day() { return tNow('presence.greet.day'); },
  get dusk() { return tNow('presence.greet.dusk'); },
  get night() { return tNow('presence.greet.night'); },
};

export interface WaitingPerson {
  name: string;
  /** Коли написала людина (ISO), якщо відомо. */
  at?: string;
}

/**
 * Одне речення PHANTOM під кулею: що він помітив зараз. Це не картка й не
 * заглушка — він говорить завжди, але лише про те, що справді є.
 */
export function phantomLine(opts: {
  part: DayPart;
  mood: Mood;
  waiting: WaitingPerson[];
  nextEvent?: { title: string; inMinutes: number } | null;
  /** Як людина сказала себе звати (Знайомство / «Я»). */
  name?: string | null;
}): string {
  const { part, mood, waiting, nextEvent } = opts;
  if (mood === 'alarm') return tNow('presence.stranger');
  if (mood === 'work') return tNow('presence.doingTask');
  const name = opts.name?.trim();
  const greet = name ? `${GREETING[part]}, ${name}` : GREETING[part];
  if (waiting.length === 1) return tNow('presence.oneWaiting', { greet, name: waiting[0].name });
  if (waiting.length > 1) return tNow('presence.manyWaiting', { greet, n: waiting.length, people: tPlural(waiting.length, 'presence.people') });
  if (nextEvent && nextEvent.inMinutes <= 120) {
    return tNow('presence.nextEvent', { greet, when: nextEvent.inMinutes <= 1 ? tNow('presence.now') : tNow('presence.inTime', { time: humanMinutes(nextEvent.inMinutes) }), title: nextEvent.title });
  }
  return tNow('presence.nothing', { greet });
}

export function humanMinutes(min: number): string {
  if (min < 60) return `${min} ${tPlural(min, 'presence.minutes')}`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  const hours = `${h} ${tPlural(h, 'presence.hours')}`;
  return m ? tNow('presence.hm', { hours, m }) : hours;
}

/** Ім'я, яким людина назвалась PHANTOM; інакше — логін. */
export function displayNameOf(user: { username?: string; preferences?: unknown } | null | undefined): string {
  const prefs = (user?.preferences ?? {}) as Record<string, unknown>;
  const name = typeof prefs.display_name === 'string' ? prefs.display_name.trim() : '';
  return name || (user?.username ?? '').trim();
}
