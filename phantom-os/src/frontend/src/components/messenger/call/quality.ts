/**
 * Смужки якості дзвінка — з виміру, не з надії (P0-5, 08.10).
 *
 * Вхід — `CallStats` рушія: RTT обраної пари кандидатів і втрати вхідних
 * пакетів за ОСТАННІЙ проміжок getStats (`lossPct`). Вихід — 1…4 смужки й
 * чи йде розмова через ретранслятор. Немає жодного виміру — `null`, і екран
 * смужок не малює: «повна шкала» без виміру була б вигадкою. Пакети за
 * проміжок не приїхали зовсім (`stalled`) — одна смужка, хоч RTT і добрий.
 */

import type { CallStats } from '../../../services/callEngine';

export interface CallQuality {
  /** 1 — ледь тримає, 4 — чисто. */
  bars: 1 | 2 | 3 | 4;
  /** Обрана пара йде через ретранслятор (TURN) з будь-якого боку. */
  relay: boolean;
}

type Bars = CallQuality['bars'];

/**
 * Пороги RTT (мс), туди-назад. До 150 — розмова без відчутної затримки;
 * 300 — люди починають перебивати одне одного; 600 — відповідь «запізнюється».
 */
const byRtt = (ms: number): Bars => (ms < 150 ? 4 : ms < 300 ? 3 : ms < 600 ? 2 : 1);

/**
 * Пороги втрат (%) за проміжок. Opus із FEC ховає ~1 %; до 3 % — чутно
 * зрідка; до 8 % — «булькає»; далі — слова губляться.
 */
const byLoss = (pct: number): Bars => (pct < 1 ? 4 : pct < 3 ? 3 : pct < 8 ? 2 : 1);

const finite = (n: number | null | undefined): n is number => typeof n === 'number' && Number.isFinite(n);

export function readQuality(stats: CallStats | null | undefined): CallQuality | null {
  if (!stats) return null;
  const relay = stats.localCandidate === 'relay' || stats.remoteCandidate === 'relay';
  // Пакети не приходять — розмова стоїть, хоч би який RTT лишився в парі
  // кандидатів (він старий). Одна смужка — правда; чотири — вигадка.
  if (stats.stalled) return { bars: 1, relay };
  const rtt = finite(stats.rttMs) ? byRtt(stats.rttMs) : null;
  const loss = finite(stats.lossPct) ? byLoss(stats.lossPct) : null;
  if (rtt === null && loss === null) return null;
  const bars = Math.min(rtt ?? 4, loss ?? 4) as Bars;
  return { bars, relay };
}
