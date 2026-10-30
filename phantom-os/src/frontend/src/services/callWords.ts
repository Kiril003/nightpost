/**
 * Слова дзвінка для людини. Рушій рахує причини мовою інженера (вузол,
 * TURN, кандидати, рація) — це правда для журналу, але не для екрана:
 * власник 26.09 — «людині треба показувати щось гарне а не цю нісенітницю».
 * Тут кожна причина стає одним коротким людським реченням; технічний рядок
 * рушія на екран не потрапляє ніколи, навіть невідомий.
 */

import type { CallMedia, CallSnapshot, CallStall } from './callEngine';
import { tNow } from '../components/chat/tNow';
import { REASON as R } from './callReasons';


const noAccess = (media: CallMedia) =>
  media === 'video' ? tNow('callWords.noAccessVideo') : tNow('callWords.noAccessAudio');
const notFound = (media: CallMedia) =>
  media === 'video' ? tNow('callWords.notFoundVideo') : tNow('callWords.notFoundAudio');

/** Рядок під іменем, поки дзвінок не почався або вже скінчився. */
export function callStateLine(
  s: Pick<CallSnapshot, 'state' | 'media' | 'stall' | 'endedReason'> & { resuming?: boolean },
): string {
  if (s.state === 'ended') return endedLine(s.endedReason, s.media);
  if (s.resuming) return tNow('callWords.resuming');
  if (s.stall) return stallLine(s.stall);
  if (s.state === 'ringing') return tNow(s.media === 'video' ? 'callWords.incomingVideo' : 'callWords.incomingAudio');
  if (s.state === 'calling') return tNow('callWords.calling');
  return tNow('callWords.connecting');
}

/** Дзвінок стоїть довше, ніж годиться. Причина — одним словом людини. */
export function stallLine(stall: CallStall): string {
  return stall.kind === 'no-answer' ? `${tNow('callWords.noAnswer')}…` : tNow('callWords.slow');
}

/**
 * Чим скінчилось. Розпізнаємо відомі причини рушія; усе інше — англійська
 * помилка браузера, відмова вузла, TURN — однаково означає для людини одне:
 * не вдалося, спробуй ще.
 */
export function endedLine(reason: string | null | undefined, media: CallMedia = 'audio'): string {
  const r = (reason ?? '').trim();
  if (!r) return tNow('callWords.ended');
  // Дописка «сигнал не доїхав» — службова; саме завершення від неї не змінюється.
  const head = r.split(R.detailSep)[0].toLowerCase();
  if (head === R.finished || head === R.peerHungUp) return tNow('callWords.ended');
  if (head === R.declined) return tNow('callWords.declined');
  if (head === R.callsOff) return tNow('callWords.callsOff');
  if (head === R.peerDeclined) return tNow('callWords.busy');
  if (head === R.allLeft) return tNow('callWords.allLeft');
  if (head === R.linkDropped) return tNow('callWords.dropped');
  if (head.startsWith(R.noAnswerHead)) return tNow('callWords.noAnswer');
  if (head.includes(R.noAccessMark) || head.includes('notallowed')) return noAccess(media);
  if (head.includes(R.notFoundMark) || head.includes('notfound')) return notFound(media);
  if (head.includes(R.busyMark)) return tNow('callWords.micBusy');
  if (head.startsWith(R.micNoSound)) return tNow('callWords.micBusy');
  return tNow('callWords.failed');
}

/**
 * Звістка про канал посеред розмови. Рація — це «слабкий звʼязок»: голос іде,
 * але з запізненням, і більше людині знати нічого не треба.
 */
export function linkLine(
  s: Pick<CallSnapshot, 'radio' | 'radioReason' | 'linkNote' | 'videoDropped'> &
    Partial<Pick<CallSnapshot, 'weakSide' | 'peer'>>,
): string | null {
  if (s.radio || s.radioReason) return tNow('callWords.weak');
  if (s.linkNote === R.channelRestored) return tNow('callWords.restored');
  if (s.weakSide === 'me') return tNow('callWords.weakMe');
  if (s.weakSide === 'them') {
    const name = s.peer?.displayName?.trim() ?? '';
    return name ? tNow('callWords.weakThem', { name }) : tNow('callWords.weak');
  }
  if (s.weakSide === 'path') return tNow('callWords.weak');
  if (s.linkNote) return tNow('callWords.weak');
  if (s.videoDropped) return tNow('callWords.videoPaused');
  return null;
}

/** Кімната майже повна — сказати заздалегідь, простим числом. */
export const roomFullLine = (max: number): string => tNow('callWords.roomFull', { max });

/**
 * Технічні слова, яких людина не має бачити на екрані дзвінка. Латиниця
 * будь-якої довжини від трьох літер — теж: у рядках дзвінка англійській
 * немає місця, і так ловиться будь-яка помилка браузера як є.
 */
export const CALL_JARGON =
  /[A-Za-z]{3,}|вузл|вузол|дорог|ретранслятор|раці[яї]|кандидат|доріжк|сигнал|відбит|звір|ключ|кбіт|кодек|шифру|мереж|сервер|порт\b/i;
