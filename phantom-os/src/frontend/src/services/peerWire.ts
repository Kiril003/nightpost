/**
 * Кадри меша у вебі: розібране мостом малюємо, дріт не показуємо ніколи.
 *
 * 04.09, збірка dbe6d05d: власник поділився маршрутом з мапи в розмову з живою
 * людиною й побачив у бульбашці `PH-RTE:1788517702898;337;258;FOOT;s~doH…`
 * замість картки. Формат оголошено в Kotlin (`core-net/.../peer/PeerLive.kt`),
 * і розбирач там Є — але месенджер, який власник бачить, це веб у WebView, і
 * жодного з семи префіксів у ньому не згадувалось ані разу.
 *
 * РОЗБИРАЧА ДРОТУ ТУТ НЕМАЄ І НЕ БУДЕ. Розбирає міст (`WebMessengerKinds.kt`,
 * коміт 61b3cc84) тими самими `PeerLive.decodeRoute` і
 * `PeerPayload.decodePlace`, які кличе нативний екран, і кладе ПЛАСКІ поля
 * просто в лист: `route_profile`, `route_distance_m`, … — як `attachment_state`
 * і решта полів рядка. `body` для таких листів порожнє навмисно: коли веб не
 * знав роду, він малював тіло — саме так протокол і опинявся на склі.
 *
 * Тож веб знає рівно три речі:
 *
 *  1. **Поля листа** — `kind` route / place / broken. З них складається картка
 *     ([readMessageCard]); жодного розбору рядків.
 *  2. **Заборона за замовчуванням** — будь-яке тіло, що починається з `PH-`,
 *     не показується як текст ([readPeerWire]). Сьогодні це спрацьовує рідко:
 *     міст роди знає, а `body` порожнє. Сторож лишається на випадок, коли
 *     Kotlin додасть восьмий рід, а міст його ще не вміє.
 *  3. **Керівні кадри** ([isControlFrame]) зникають зі стрічки цілком — так
 *     само, як їх викидає з розмови `PeerOutbox.isSpoken` (PeerOutbox.kt:363):
 *     171 рядок base64 замість фотографії — це не «докладніше», це замість.
 */

import type {
  BrokenFrame,
  ChatLastCard,
  LatLon,
  Message,
  SharedPlace,
  SharedRoute,
} from '../types/messenger';
import { currentLocale, tNow } from '../components/chat/tNow';

/** Спільний корінь усіх родів на дроті. Ловить і ті, яких ця збірка не знає. */
export const WIRE_ANY = 'PH-';

export const WIRE_ROUTE = 'PH-RTE:';
export const WIRE_PLACE = 'PH-LOC:';
export const WIRE_FIX = 'PH-LIV:';
export const WIRE_STOP = 'PH-LVX:';
export const WIRE_CHUNK = 'PH-FCH:';
export const WIRE_GROUP = 'PH-GRP:';
export const WIRE_ACT = 'PH-SYS:';
export const WIRE_CARD = 'PH-CRD:';

/** Позначка картки на дроті → її рід у вебі. Дзеркало `PeerCard.Card` (mark → webType). */
const CARD_MARKS: Record<string, string> = {
  cv: 'widget:canvas', kb: 'widget:kanban', vo: 'widget:voting', rc: 'widget:raci', tl: 'widget:timeline',
  cr: 'widget:code-runner', fc: 'widget:family-checklist', fe: 'widget:family-expense', sf: 'widget:study-flashcards',
  as: 'snippet:async', mm: 'embed:mermaid', df: 'embed:diff', sv: 'embed:svg', tb: 'table', mu: 'widget:meetup',
  bl: 'widget:bill', pp: 'widget:person', dy: 'widget:day', gl: 'widget:glance', mn: 'widget:money',
};

/** Рід картки з сирого `PH-CRD:<mark>;…`, або null. */
export function cardTypeOfWire(body?: string | null): string | null {
  if (!isString(body) || !body.startsWith(WIRE_CARD)) return null;
  const end = body.indexOf(';', WIRE_CARD.length);
  return end > 0 ? (CARD_MARKS[body.slice(WIRE_CARD.length, end)] ?? null) : null;
}

/** Кадри, яких людині не показують ніяк — PeerOutbox.isSpoken. */
const CONTROL_PREFIXES = [WIRE_FIX, WIRE_STOP, WIRE_CHUNK, WIRE_GROUP, WIRE_ACT];

/** Що показати замість тіла. `null` — звичайні слова. */
export type PeerWire =
  /** Міст розібрав — малюємо картку. */
  | { kind: 'route'; route: SharedRoute }
  | { kind: 'place'; place: SharedPlace }
  /** Рід упізнано, читання не вдалося. Речення — від хоста. */
  | { kind: 'broken'; broken: BrokenFrame }
  /** Сирий дріт: рід відомий із префікса, подробиці — ні. */
  | { kind: 'raw'; of: 'route' | 'place' }
  /** Керівний кадр: не показується взагалі. */
  | { kind: 'control'; tag: string }
  /** Картка, яку міст переказав сирим дротом: рід відомий, вміст — у стрічці. */
  | { kind: 'card'; type: string }
  /** Рід, якого ця збірка не знає. Теж не текст. */
  | { kind: 'unknown'; tag: string };

const isString = (raw: unknown): raw is string => typeof raw === 'string';

const finite = (raw: unknown): number | null =>
  typeof raw === 'number' && Number.isFinite(raw) ? raw : null;

const inRange = (lat: number, lon: number): boolean =>
  lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;

/**
 * `[[широта, довгота], …]` з поля моста. Хибна точка вбиває весь список:
 * пів-ламаної не буває, як і в `PeerLive.decodePath`.
 */
export function pointsFromRow(raw: unknown): LatLon[] | null {
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw)) return null;
  const out: LatLon[] = [];
  for (const item of raw) {
    if (!Array.isArray(item) || item.length < 2) return null;
    const lat = finite(item[0]);
    const lon = finite(item[1]);
    if (lat === null || lon === null || !inRange(lat, lon)) return null;
    out.push({ lat, lon });
  }
  return out;
}

/** Пласкі поля маршруту з рядка вузла. `null` — поля неповні. */
export function routeFromRow(row: Record<string, unknown>): SharedRoute | null {
  const distance = finite(row.route_distance_m);
  const duration = finite(row.route_duration_s);
  if (distance === null || distance < 0) return null;
  if (duration === null || duration < 0) return null;
  const waypoints = pointsFromRow(row.route_waypoints);
  const shape = pointsFromRow(row.route_shape);
  if (!waypoints || !shape) return null;
  const expires = finite(row.route_expires_at_ms);
  return {
    profile: isString(row.route_profile) ? row.route_profile.trim() : '',
    distanceM: distance,
    durationS: duration,
    ...(expires !== null && expires > 0 ? { expiresAtMs: expires } : {}),
    waypoints,
    shape,
  };
}

/** Пласкі поля місця. `accuracy_m` = null означає «не сказали», а не нуль. */
export function placeFromRow(row: Record<string, unknown>): SharedPlace | null {
  const lat = finite(row.place_lat);
  const lon = finite(row.place_lon);
  if (lat === null || lon === null || !inRange(lat, lon)) return null;
  const accuracy = finite(row.place_accuracy_m);
  return {
    lat,
    lon,
    ...(accuracy !== null && accuracy > 0 ? { accuracyM: accuracy } : {}),
  };
}

/** Речення хоста про нечитаний кадр. Своє вигадуємо лише коли його немає. */
export function brokenFromRow(row: Record<string, unknown>, fallbackOf = ''): BrokenFrame {
  const of = isString(row.broken_kind) ? row.broken_kind.trim() : fallbackOf;
  const reason = isString(row.broken_reason) ? row.broken_reason.trim() : '';
  return { of, reason };
}

/**
 * Картка з полів ЛИСТА — головні двері після приходу моста.
 * `null` — цей лист не кадр меша.
 */
export function readMessageCard(msg: Partial<Message> | null | undefined): PeerWire | null {
  if (!msg) return null;
  if (msg.route) return { kind: 'route', route: msg.route };
  if (msg.place) return { kind: 'place', place: msg.place };
  if (msg.broken) return { kind: 'broken', broken: msg.broken };
  if (isNoViewEmpty(msg)) return { kind: 'broken', broken: { of: msg.type as string, reason: 'no_view' } };
  return null;
}

/** Роди, яким телефон не має власного виду: без даних — чесне «не вдалося», не порожня бульбашка. */
const NO_VIEW_KINDS = new Set(['event', 'canvas-doc', 'transit-arrivals', 'widget:transit-arrivals']);

/** Подія, яку є чим показати: назва і день. */
export const hasEventView = (msg: Partial<Message>): boolean =>
  msg.type === 'event' && isString(msg.eventData?.title) && msg.eventData.title.trim() !== '' && isString(msg.eventData.date);

function isNoViewEmpty(msg: Partial<Message>): boolean {
  if (!msg.type || !NO_VIEW_KINDS.has(msg.type) || msg.isDeleted) return false;
  if ((isString(msg.text) && msg.text.trim() !== '') || msg.media) return false;
  return !hasEventView(msg);
}

/** Чи це керівний кадр, якого в розмові немає взагалі. */
export const isControlFrame = (body?: string | null): boolean =>
  isString(body) && CONTROL_PREFIXES.some((tag) => body.startsWith(tag));

/**
 * Заборона за замовчуванням для ТІЛА листа: `PH-…` ніколи не текст.
 *
 * Міст роди route і place вже розбирає, тож сюди тепер потрапляє лише те, чого
 * він ще не вміє. Сторож лишається саме заради цього випадку.
 */
export function readPeerWire(body?: string | null): PeerWire | null {
  if (!isString(body) || !body.startsWith(WIRE_ANY)) return null;
  if (body.startsWith(WIRE_ROUTE)) return { kind: 'raw', of: 'route' };
  if (body.startsWith(WIRE_PLACE)) return { kind: 'raw', of: 'place' };
  const control = CONTROL_PREFIXES.find((tag) => body.startsWith(tag));
  if (control) return { kind: 'control', tag: control };
  const card = cardTypeOfWire(body);
  if (card) return { kind: 'card', type: card };
  return { kind: 'unknown', tag: body.slice(0, body.indexOf(':') + 1) || WIRE_ANY };
}

/** «3,4 км» або «337 м» — метри до кілометра лишаються метрами. */
export function distanceLabel(distanceM: number): string {
  if (!Number.isFinite(distanceM) || distanceM < 0) return '';
  if (distanceM < 1000) return tNow('peerWire.meters', { n: Math.round(distanceM) });
  const km = (distanceM / 1000).toFixed(1);
  return tNow('peerWire.km', { n: currentLocale() === 'en' ? km : km.replace('.', ',') });
}

/**
 * «4 хв», «1 год 12 хв».
 *
 * Хвилини рахуються ВНИЗ і не менш ніж одна — рівно як на нативній картці
 * (`(route.durationS / 60L).coerceAtLeast(1)`, PeerRouteBubble.kt). Дві
 * поверхні, що округлюють по-різному, — це дві різні правди про один лист.
 */
export function durationLabel(durationS: number): string {
  if (!Number.isFinite(durationS) || durationS < 0) return '';
  const minutes = Math.max(1, Math.floor(durationS / 60));
  if (minutes < 60) return tNow('peerWire.min', { n: minutes });
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? tNow('peerWire.hour', { n: hours }) : tNow('peerWire.hourMin', { h: hours, m: rest });
}

/**
 * Профіль руху словом. Назви — з `RouteProfile` (OsrmClient.kt:23-28), бо саме
 * `variant.profile.name` кладе в кадр `MapViewModel.shareRoute`.
 *
 * Незнайомий профіль — порожньо: вигадане слово гірше за відсутнє.
 */
export function profileWord(profile: string): string {
  switch (profile.trim().toUpperCase()) {
    case 'FOOT':
      return tNow('peerWire.foot');
    case 'CAR':
      return tNow('peerWire.car');
    case 'BIKE':
      return tNow('peerWire.bike');
    case 'TRANSIT':
      return tNow('peerWire.transit');
    default:
      return '';
  }
}

export const routeExpired = (route: SharedRoute, nowMs: number): boolean =>
  route.expiresAtMs !== undefined && route.expiresAtMs <= nowMs;

export interface WireLine {
  /** Рядок для реєстру розмов. Порожній — не показувати нічого. */
  line: string;
  /** Керівний кадр: його немає ні в бульбашці, ні в реєстрі. */
  control: boolean;
}

/**
 * Один рядок про останній лист для реєстру розмов. `null` — не наша справа.
 *
 * Три джерела, у порядку довіри:
 *  1. пласкі поля РЯДКА РОЗМОВИ (`last_route_distance_m` тощо) — вони є завжди,
 *     навіть коли стрічку ще не відкривали;
 *  2. поля завантаженого листа, якщо стрічка вже в памʼяті;
 *  3. сам лише рід — тоді слово без чисел.
 *
 * І окремо — заслін на снипет: якщо міст роду ще не знає й прислав сире тіло,
 * протокол у перелік однаково не потрапляє.
 *
 * Чисел, яких нам не дали, ми не вигадуємо: «Маршрут» без кілометрів — це
 * менше, ніж хотілось, але правда.
 */
export function peerWireLine(input: {
  kind?: string | null;
  card?: ChatLastCard | null;
  message?: Partial<Message> | null;
  snippet?: string | null;
}): WireLine | null {
  const { kind, card, message, snippet } = input;

  if (card?.kind === 'route') {
    return { line: routeWords(card.profile ?? '', card.distanceM), control: false };
  }
  if (card?.kind === 'place') return { line: tNow('peerWire.place'), control: false };

  const fromMessage = readMessageCard(message);
  if (fromMessage) return { line: cardLine(fromMessage), control: false };

  if (kind === 'route') return { line: tNow('peerWire.route'), control: false };
  if (kind === 'place') return { line: tNow('peerWire.place'), control: false };
  if (kind === 'broken') return { line: tNow('peerWire.brokenLetter'), control: false };

  const wire = readPeerWire(snippet);
  return wire ? { line: cardLine(wire), control: wire.kind === 'control' } : null;
}

const routeWords = (profile: string, distanceM?: number): string =>
  [tNow('peerWire.route'), profileWord(profile), distanceM !== undefined ? distanceLabel(distanceM) : '']
    .filter(Boolean)
    .join(' · ');

function cardLine(wire: PeerWire): string {
  switch (wire.kind) {
    case 'route':
      return routeWords(wire.route.profile, wire.route.distanceM);
    case 'place':
      return tNow('peerWire.place');
    case 'broken':
      return wire.broken.of === 'place'
        ? tNow('peerWire.brokenPlace')
        : wire.broken.of === 'route'
          ? tNow('peerWire.brokenRoute')
          : tNow('peerWire.brokenLetter');
    case 'raw':
      return wire.of === 'route' ? tNow('peerWire.route') : tNow('peerWire.place');
    case 'control':
      return '';
    case 'card':
      return tNow('peerWire.card');
    default:
      return tNow('peerWire.unknownFormat');
  }
}
