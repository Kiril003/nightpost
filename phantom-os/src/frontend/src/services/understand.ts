/**
 * Що в листі можна зробити одним дотиком: час, номер посилки, телефон,
 * адреса, сума, питання. Лише правила над самим текстом — без мережі й без
 * моделі, тож працює на кожному листі безкоштовно. Відносні дні («завтра»,
 * «в неділю») рахуються від моменту, коли лист написали, а не від «зараз».
 */

export interface TimeFinding {
  kind: 'time';
  at: Date;
  /** Годину названо — інакше відомий лише день. */
  hasClock: boolean;
  /** «до 20:00» — межа, а не зустріч. */
  deadline: boolean;
}

export interface ParcelFinding {
  kind: 'parcel';
  number: string;
}

export interface PhoneFinding {
  kind: 'phone';
  number: string;
}

export interface PlaceFinding {
  kind: 'place';
  text: string;
}

export interface MoneyFinding {
  kind: 'money';
  amount: number;
  currency: 'UAH' | 'USD' | 'EUR';
}

export interface AskFinding {
  kind: 'ask';
  /** Питання «коли/де/що…» — на нього не відповісти «так» чи «ні». */
  open: boolean;
  /** Прохання без знака питання: «скинь номер». */
  request: boolean;
}

export type Finding = TimeFinding | ParcelFinding | PhoneFinding | PlaceFinding | MoneyFinding | AskFinding;

const B = '(?<![\\p{L}\\d])';
const E = '(?![\\p{L}\\d])';

const WEEKDAYS: Array<[RegExp, number]> = [
  [/^(понеділок|понеділка|monday)$/iu, 1],
  [/^(вівторок|вівторка|tuesday)$/iu, 2],
  [/^(середу|середа|wednesday)$/iu, 3],
  [/^(четвер|четверга|thursday)$/iu, 4],
  [/^(п['ʼ’]?ятницю|п['ʼ’]?ятниця|friday)$/iu, 5],
  [/^(суботу|субота|saturday)$/iu, 6],
  [/^(неділю|неділя|sunday)$/iu, 0],
];

const MONTHS = [
  'січня', 'лютого', 'березня', 'квітня', 'травня', 'червня',
  'липня', 'серпня', 'вересня', 'жовтня', 'листопада', 'грудня',
];

const DAY_WORDS = new RegExp(
  `${B}(сьогодні|завтра|післязавтра|today|tomorrow|(?:в|у|во|on|this|next)\\s+(понеділок|вівторок|середу|четвер|п['ʼ’]?ятницю|суботу|неділю|monday|tuesday|wednesday|thursday|friday|saturday|sunday))${E}`,
  'iu',
);
const DATE_NUM = new RegExp(`${B}(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?${E}`, 'u');
const DATE_WORD = new RegExp(`${B}(\\d{1,2})\\s+(${MONTHS.join('|')})${E}`, 'iu');

/** «о 19:40», «до 20:00», «об 11», «в 7 вечора», «at 7pm», голе «19:40». */
const CLOCK = new RegExp(
  `${B}(?:(о|об|на|до|в|у|к|at|by|until|till)\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s*(год(?:ині|ина)?\\.?))?(?:\\s*(ранку|зранку|вечора|дня|ночі|am|pm|a\\.m\\.|p\\.m\\.))?${E}`,
  'giu',
);

const startOfDay = (d: Date): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d: Date, n: number): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

function dayOf(text: string, sent: Date): Date | null {
  const w = DAY_WORDS.exec(text);
  if (w) {
    const word = w[1].toLowerCase();
    if (word === 'сьогодні' || word === 'today') return startOfDay(sent);
    if (word === 'завтра' || word === 'tomorrow') return addDays(sent, 1);
    if (word === 'післязавтра') return addDays(sent, 2);
    const name = (w[2] || '').toLowerCase();
    const hit = WEEKDAYS.find(([re]) => re.test(name));
    if (hit) {
      let delta = (hit[1] - sent.getDay() + 7) % 7;
      if (delta === 0) delta = 7;
      return addDays(sent, delta);
    }
  }
  const byWord = DATE_WORD.exec(text);
  if (byWord) return dateFrom(+byWord[1], MONTHS.indexOf(byWord[2].toLowerCase()), null, sent);
  const byNum = DATE_NUM.exec(text);
  if (byNum && !/(^|\s)(о|об)\s*$/iu.test(text.slice(0, byNum.index))) {
    return dateFrom(+byNum[1], +byNum[2] - 1, byNum[3] ? +byNum[3] : null, sent);
  }
  return null;
}

function dateFrom(day: number, month: number, year: number | null, sent: Date): Date | null {
  if (month < 0 || month > 11 || day < 1 || day > 31) return null;
  const y = year == null ? sent.getFullYear() : year < 100 ? 2000 + year : year;
  const d = new Date(y, month, day);
  if (d.getMonth() !== month) return null;
  if (year == null && d < startOfDay(sent)) return new Date(y + 1, month, day);
  return d;
}

interface Clock {
  h: number;
  m: number;
  /** Година без «ранку/вечора», яку можна прочитати і як 7, і як 19. */
  loose: boolean;
  deadline: boolean;
}

function clockOf(text: string): Clock | null {
  CLOCK.lastIndex = 0;
  for (let m = CLOCK.exec(text); m; m = CLOCK.exec(text)) {
    const [whole, prep, hh, mm, hours, part] = m;
    const p = (prep || '').toLowerCase();
    const h = +hh;
    const min = mm ? +mm : 0;
    if (h > 23 || min > 59) continue;
    const colon = mm != null && whole.includes(`${hh}:${mm}`);
    const named = Boolean(hours || part);
    const firm = p === 'о' || p === 'об' || p === 'at';
    if (mm != null && !colon && !firm && !named) continue; // «25.09» — дата
    if (mm == null && !firm && !named) {
      // «до 8» — час, «на 5 хвилин» і «в 2 рази» — ні.
      if (!p) continue;
      const tail = text.slice(m.index + whole.length);
      if (!/^\s*($|[,.!?;)])/u.test(tail)) continue;
    }
    let hour = h;
    const pp = (part || '').toLowerCase();
    if (/вечора|дня|pm|p\.m\./u.test(pp) && hour < 12) hour += 12;
    if (/ранку|зранку|ночі|am|a\.m\./u.test(pp) && hour === 12) hour = 0;
    return { h: hour, m: min, loose: !part && h >= 1 && h <= 11, deadline: /^(до|by|until|till)$/u.test(p) };
  }
  return null;
}

export function findTime(text: string, sentAt: Date): TimeFinding | null {
  const clock = clockOf(text);
  const day = dayOf(text, sentAt);
  if (!clock && !day) return null;
  if (!clock) return { kind: 'time', at: day as Date, hasClock: false, deadline: false };
  const base = day ?? startOfDay(sentAt);
  const at = new Date(base.getFullYear(), base.getMonth(), base.getDate(), clock.h, clock.m);
  if (!day) {
    // Без дня — найближчий такий момент після листа: «о 7» ввечері це 19:00.
    if (at <= sentAt && clock.loose) {
      const later = new Date(at.getTime() + 12 * 3600_000);
      if (later > sentAt) return { kind: 'time', at: later, hasClock: true, deadline: clock.deadline };
    }
    if (at <= sentAt) at.setDate(at.getDate() + 1);
  } else if (clock.loose && clock.h < 6) {
    at.setHours(at.getHours() + 12);
  }
  return { kind: 'time', at, hasClock: true, deadline: clock.deadline };
}

const PARCEL_CONTEXT = /(ттн|накладн|посилк|посилоч|відправлен|трек|номер|нп|нова пошта|укрпошт|tracking|parcel|package)/iu;

export function findPhone(text: string): PhoneFinding | null {
  const re = /(?<![\d+])(\+?\s?3?8?\s?\(?0\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2})(?![\d])/gu;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const digits = m[1].replace(/\D/g, '');
    if (digits.length === 10 && digits.startsWith('0')) return { kind: 'phone', number: `+38${digits}` };
    if (digits.length === 12 && digits.startsWith('380')) return { kind: 'phone', number: `+${digits}` };
  }
  const intl = /(?<![\d])\+(\d[\d\s-]{8,16}\d)(?![\d])/u.exec(text);
  if (intl) {
    const digits = intl[1].replace(/\D/g, '');
    if (digits.length >= 10 && digits.length <= 13) return { kind: 'phone', number: `+${digits}` };
  }
  return null;
}

export function findParcel(text: string): ParcelFinding | null {
  const s10 = /(?<![\p{L}\d])([A-Z]{2}\d{9}[A-Z]{2})(?![\p{L}\d])/u.exec(text);
  if (s10) return { kind: 'parcel', number: s10[1] };
  const re = /(?<![\d+])(\d[\d ]{9,20}\d)(?![\d])/gu;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const raw = m[1];
    if (/ {2}/.test(raw)) continue;
    const digits = raw.replace(/ /g, '');
    if (digits.length < 11 || digits.length > 14) continue;
    if (digits.startsWith('380') || (digits.length === 10 && digits.startsWith('0'))) continue;
    if (digits.length === 14 && /^(1|2|5)/.test(digits)) return { kind: 'parcel', number: digits };
    if (PARCEL_CONTEXT.test(text)) return { kind: 'parcel', number: digits };
  }
  return null;
}

const MONEY = new RegExp(
  `(?:([₴$€])\\s?(\\d[\\d\\s]{0,9}(?:[.,]\\d{1,2})?)|(\\d[\\d\\s]{0,9}(?:[.,]\\d{1,2})?)\\s?(₴|\\$|€|грн\\.?|гривень|гривні|гривня|гривню|uah|доларів|долари|долар|баксів|usd|євро|eur)${E})`,
  'iu',
);

export function findMoney(text: string): MoneyFinding | null {
  const m = MONEY.exec(text);
  if (!m) return null;
  const sign = (m[1] || m[4] || '').toLowerCase();
  const amount = parseFloat((m[2] || m[3]).replace(/\s/g, '').replace(',', '.'));
  if (!(amount > 0)) return null;
  const currency: MoneyFinding['currency'] = /[$]|долар|бакс|usd/u.test(sign) ? 'USD' : /€|євро|eur/u.test(sign) ? 'EUR' : 'UAH';
  return { kind: 'money', amount, currency };
}

export function findAllMoney(text: string): Array<MoneyFinding & { index: number; raw: string }> {
  const re = new RegExp(MONEY.source, 'giu');
  re.lastIndex = 0;
  const out: Array<MoneyFinding & { index: number; raw: string }> = [];
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const sign = (m[1] || m[4] || '').toLowerCase();
    const amount = parseFloat((m[2] || m[3]).replace(/\s/g, '').replace(',', '.'));
    if (!(amount > 0)) continue;
    const currency: MoneyFinding['currency'] = /[$]|долар|бакс|usd/u.test(sign)
      ? 'USD'
      : /€|євро|eur/u.test(sign)
        ? 'EUR'
        : 'UAH';
    out.push({ kind: 'money', amount, currency, index: m.index, raw: m[0].trimEnd() });
  }
  return out;
}

const STREET =
  /(?<![\p{L}])((?:вул\.|вулиц[яіюе]|просп\.|проспект[іу]?|пр-т|пров\.|провулок|провулку|бульв\.|бульвар[іу]?|пл\.|площ[аіу]|наб\.|набережн[аійу]|шосе)\s*[\p{Lu}\d][\p{L}\d'ʼ’.\- ]{1,40}?(?:,?\s*\d+[\p{L}]?(?:\/\d+)?)?)(?=[,.!?;)]|\s*$|\s+(?:біля|коло|навпроти|о|об|до|в|у)\s)/u;
const BRANCH =
  /(?<![\p{L}])((?:нова пошта|новій пошті|нову пошту|нп|укрпошт[аиіу]|відділенн[яі]|поштомат[іу]?)\s*(?:№|n|#)?\s*\d{1,5})(?!\d)/iu;
const EN_STREET = /(?<![\p{L}\d])(\d{1,5}\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\s+(?:St|Street|Ave|Avenue|Rd|Road|Blvd|Lane|Ln)\.?)(?![\p{L}])/u;

export function findPlace(text: string): PlaceFinding | null {
  const m = STREET.exec(text) || BRANCH.exec(text) || EN_STREET.exec(text);
  if (!m) return null;
  return { kind: 'place', text: m[1].trim().replace(/[,\s]+$/u, '') };
}

const OPEN_WORDS = /(?<![\p{L}])(коли|де|куди|звідки|що|шо|скільки|як|який|яка|яке|які|якого|чому|навіщо|хто|кого|кому|котра|котрій|when|where|what|how|why|who|which)(?![\p{L}])/iu;
const REQUEST_WORDS =
  /(?<![\p{L}])(скинь|скиньте|надішли|надішліть|пришли|можеш|можете|приїдь|приходь|допоможи|допоможіть|подзвони|подзвоніть|передзвони|передзвоніть|зателефонуй|напиши|напишіть|відпиши|підкажи|підкажіть|глянь|гляньте|купи|забери|заберіть|візьми|нагадай|please|send|call me|can you|could you)(?![\p{L}])/iu;

export function findAsk(text: string): AskFinding | null {
  const t = text.trim();
  if (!t) return null;
  const q = /\?[\s!?)»"'.…]*$/u.test(t) || /\?/u.test(t);
  const req = REQUEST_WORDS.test(t);
  if (!q && !req) return null;
  const lastQuestion = q ? (t.match(/[^.!?]*\?/gu) || [t]).pop() || t : t;
  return { kind: 'ask', open: q && OPEN_WORDS.test(lastQuestion), request: !q };
}

/** Усе, що знайшлося в листі, у порядку ваги: час → посилка → телефон → місце → гроші → питання. */
export function understand(text: string | null | undefined, sentAt: Date = new Date()): Finding[] {
  const t = (text || '').trim();
  if (!t) return [];
  const out: Finding[] = [];
  const phone = findPhone(t);
  const parcel = findParcel(phone ? t.replace(/\+?\s?3?8?\s?\(?0\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}/u, ' ') : t);
  // Цифри номера не читаються як час: «2045 1188» — не «о 20:45».
  const bare = t
    .replace(/(?<![\d+])\d[\d ]{9,20}\d(?![\d])/gu, ' ')
    .replace(/\+\d[\d\s()-]{8,18}\d/gu, ' ');
  const time = findTime(bare, sentAt);
  if (time) out.push(time);
  if (parcel) out.push(parcel);
  if (phone) out.push(phone);
  const place = findPlace(t);
  if (place) out.push(place);
  const money = findMoney(bare);
  if (money) out.push(money);
  const ask = findAsk(t);
  if (ask) out.push(ask);
  return out;
}

/** Момент із вузла: ISO без поясу — це UTC (так пише міст). */
export function sentMoment(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const raw = /[zZ]|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : `${iso}Z`;
  const d = new Date(raw);
  return Number.isNaN(d.getTime()) ? null : d;
}
