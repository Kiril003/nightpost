/**
 * Чесні слова про прохання (розд. 07 §2.3): ЯКОЮ смугою пішло, ДЕ воно зараз
 * і ЩО відповів ПК. Заборонено «Завантаження…» без смуги, «надіслано» без
 * квитанції і зелене «тривог немає», коли джерело мовчить.
 */
import { INTENT_STATUS, type IntentAnswer, type IntentRow } from './intentsApi';
import { tNow } from '../components/chat/tNow';
import type { MessageKey } from '../i18n';

const laneWord = (lane: string): string => (lane === 'relay' ? tNow('intent.laneRelay') : lane === 'sms' ? 'SMS' : lane);

/** Області — тими самими ідентифікаторами, що в PeerIntent.OBLAST_ORDER. */
const OBLAST_IDS = new Set([
  'cherkasy', 'chernihiv', 'chernivtsi', 'crimea', 'dnipro', 'donetsk', 'ivano-frankivsk', 'kharkiv', 'kherson',
  'khmelnytskyi', 'kirovohrad', 'kyiv-city', 'kyiv', 'luhansk', 'lviv', 'mykolaiv', 'odesa', 'poltava', 'rivne',
  'sevastopol', 'sumy', 'ternopil', 'vinnytsia', 'volyn', 'zakarpattia', 'zaporizhzhia', 'zhytomyr',
]);

export const oblastWord = (id: string): string => (OBLAST_IDS.has(id) ? tNow(`oblast.${id}` as MessageKey) : id);

const lanesOf = (row: IntentRow): string =>
  Array.from(new Set(row.tries.map((t) => laneWord(t.lane)))).join(tNow('intent.laneAnd'));

/** Рядок стану під проханням: «іде через ПК удома · SMS». */
export function intentLine(row: IntentRow): string {
  const lanes = lanesOf(row);
  switch (row.phase) {
    case 'SENT':
      return lanes ? tNow('intent.sentVia', { lanes }) : tNow('intent.sentWait');
    case 'AT_PC':
      return tNow('intent.atPc');
    case 'EXPIRED':
      return tNow('intent.expired');
    case 'ANSWERED': {
      const a = row.answer;
      if (!a) return tNow('intent.answered');
      const via = tNow(a.lane === 'sms' ? 'intent.viaSms' : 'intent.viaRelay');
      const more = a.rung < 2 && row.relay_allowed ? tNow('intent.moreByRelay') : '';
      return tNow('intent.answeredBy', { via, more });
    }
    default:
      return '';
  }
}

const ageWord = (s: number): string => (s >= 255 ? tNow('intent.ageOld') : tNow('intent.ageSec', { s }));

/** Текст відповіді ПК — або чесна причина, чому її немає. */
export function answerText(a: IntentAnswer): string {
  switch (a.status) {
    case INTENT_STATUS.OK:
      break;
    case INTENT_STATUS.SOURCE_SILENT:
      return tNow('intent.silent');
    case INTENT_STATUS.NOT_FOUND:
      return tNow('intent.notFound');
    case INTENT_STATUS.REFUSED:
      return tNow('intent.refused');
    case INTENT_STATUS.TIMEOUT:
      return tNow('intent.timeout');
    case INTENT_STATUS.UNSUPPORTED:
      return tNow('intent.unsupported');
    case INTENT_STATUS.EXPIRED:
      return tNow('intent.expiredAtPc');
    default:
      return tNow('intent.failed');
  }
  if (a.alerts) {
    const { oblasts, mine, since_min, age_s } = a.alerts;
    const where = oblasts.length ? tNow('intent.alertIn', { list: oblasts.map(oblastWord).join(', ') }) : tNow('intent.alertNone');
    const my = mine
      ? since_min != null ? tNow('intent.mineYesSince', { m: since_min }) : tNow('intent.mineYes')
      : tNow('intent.mineNo');
    return `${where}${my} · ${ageWord(age_s)}`;
  }
  if (a.siren) return sirenText(a.siren);
  if (a.presence) {
    const m = a.presence.minutes_ago;
    return m == null ? tNow('intent.presenceNone') : tNow('intent.presenceAgo', { m });
  }
  return a.text ?? '';
}

/**
 * «Сирена-маяк» словами (розд. 07 §10): лише перевірене підписом, і лише свіже —
 * як «зараз». Старий знімок — правда про тоді й так і названий.
 */
export function sirenText(s: NonNullable<IntentAnswer['siren']>): string {
  if (!s.verified) return tNow('intent.sirenUnsigned');
  const age = s.age_min ?? 0;
  const where = s.oblasts.length ? tNow('intent.alertIn', { list: s.oblasts.map(oblastWord).join(', ') }) : tNow('intent.alertNone');
  if (!s.fresh) return tNow('intent.sirenStale', { age, where });
  return tNow('intent.sirenFresh', { where, age });
}
