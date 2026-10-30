/**
 * Слова зникаючих повідомлень — однакові для рядка в стрічці й для меню.
 */
import { tNow } from '../components/chat/tNow';

export const DISAPPEAR_CHOICES: Array<{ seconds: number; label: string }> = [
  { seconds: 0, get label() { return tNow('disappear.off'); } },
  { seconds: 86_400, get label() { return tNow('disappear.day'); } },
  { seconds: 604_800, get label() { return tNow('disappear.week'); } },
  { seconds: 2_592_000, get label() { return tNow('disappear.month'); } },
];

export const durationWord = (seconds: number): string =>
  DISAPPEAR_CHOICES.find((c) => c.seconds === seconds)?.label ?? tNow('disappear.days', { n: Math.round(seconds / 86_400) });

/** Службовий рядок у стрічці: хто поставив і який строк. */
export const timerLine = (seconds: number, mine: boolean, name: string): string => {
  const who = mine ? tNow('disappear.you') : name || tNow('disappear.peer');
  return seconds > 0
    ? tNow('disappear.on', { who, span: durationWord(seconds) })
    : tNow('disappear.offLine', { who });
};
