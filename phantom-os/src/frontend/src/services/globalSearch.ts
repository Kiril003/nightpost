/**
 * Видача пошуку по всіх розмовах: «Люди» і «Листи».
 *
 * Два джерела листів, одна форма рядка:
 *  - **вузол** (`GET /messenger/search` у мості телефона) — шукає по ВСЬОМУ
 *    журналу на апараті, зокрема по розмовах, яких веб ще не відкривав. Листи
 *    не переїжджають у веб: сюди приходять лише знайдені, по рядку на кожен.
 *  - **індекс вебу** ([MessageIndex]) — те, що вже лежить у сторі: щойно
 *    надіслане, ще не перечитане з вузла, і все, коли мосту немає (браузер).
 * Рядок із обох джерел з тим самим id — один рядок.
 */
import { plainText } from './plainText';
import { tNow } from '../components/chat/tNow';
import type { Chat, ChatMember, Message } from '../types/messenger';
import { displayTitle } from '../components/messenger/chatListFormat';
import { CIRCLES, isRealCircle, type RealCircle } from '../components/messenger/todayFormat';
import { foldForSearch, fragmentOf, fragmentOfMessage, type Fragment, type HitField, type IndexedHit } from './messageSearch';
import { hashtagsIn } from './listSignals';
import { isOneToOne } from './sameName';

export interface PersonResult {
  key: string;
  /** Що відкрити дотиком. */
  chatId: string;
  name: string;
  /** Де ще ця людина — для учасника гілки, чия розмова вдвох не збіглась. */
  where?: string;
  /** Позиція збігу в імені. */
  at: number;
  chat?: Chat;
  member?: ChatMember;
  /** @нік, коли людина знайшлась саме за ним. */
  handle?: string;
}

export interface LetterResult {
  chatId: string;
  messageId: string;
  author: string;
  where: string;
  fragment: Fragment;
  sentAt?: string;
}

/** Рядок знахідки з мосту — `WebMessengerSearch.Hit` у companion. */
export interface NodeSearchHit {
  conversation_id: string;
  message_id: string;
  author_name: string;
  field: HitField;
  text: string;
  sent_at: string;
}

export interface NodeSearchReport {
  hits: NodeSearchHit[];
  /** Скільки листів вузол переглянув. */
  scanned: number;
  /** У скількох розмовах. */
  conversations: number;
  /** Знахідок більше, ніж привезено. */
  truncated: boolean;
}

export type SearchRing = RealCircle;

export interface SearchFilters {
  ring: SearchRing | null;
  hasPayment: boolean;
}

export const NO_FILTERS: SearchFilters = {
  ring: null,
  hasPayment: false,
};


/** Чи несе лист картку переказу або рахунок. */
export const isPaymentMessage = (m: Message): boolean => Boolean(m.payment || m.seal);

export const ringOf = (chat: Chat): SearchRing | null => (isRealCircle(chat.circle) ? chat.circle : null);

export const ringsIn = (chats: Chat[]): SearchRing[] => CIRCLES.filter((r) => chats.some((c) => ringOf(c) === r));

export const filterPeople = (people: PersonResult[], chats: Chat[], f: SearchFilters): PersonResult[] => {
  if (f.ring === null && !f.hasPayment) return people;
  if (f.hasPayment) return [];
  const byId = new Map(chats.map((c) => [c.id, c]));
  return people.filter((p) => {
    const chat = byId.get(p.chatId);
    return chat ? ringOf(chat) === f.ring : false;
  });
};

export const filterLetters = (letters: LetterResult[], chats: Chat[], f: SearchFilters): LetterResult[] => {
  if (f.ring === null && !f.hasPayment) return letters;
  const byId = new Map(chats.map((c) => [c.id, c]));
  return letters.filter((l) => {
    const chat = byId.get(l.chatId);
    if (!chat) return false;
    if (f.ring !== null && ringOf(chat) !== f.ring) return false;
    if (f.hasPayment) {
      const msg = chat.messages.find((m) => m.id === l.messageId);
      if (!msg || !isPaymentMessage(msg)) return false;
    }
    return true;
  });
};

/** Рядок розмови кількох (група, простір, канал) — у «Чати», не в «Люди». */
export const isChatResult = (p: PersonResult): boolean => !p.member && !!p.chat && !isOneToOne(p.chat);

/** Місце з `GET /messenger/places` мосту (`WebMessengerPlaces`): заклад або точка мапи телефона. */
export interface PlaceResult {
  key: string;
  name: string;
  /** Позиція збігу в назві; -1 — збіглись ключові слова («аптека» → «Подорожник»). */
  at: number;
  address: string;
  lat: number;
  lon: number;
  distanceM: number | null;
}

export const placesFromWire = (raw: unknown, needle: string): PlaceResult[] => {
  const rows = Array.isArray((raw as { places?: unknown } | null)?.places) ? (raw as { places: unknown[] }).places : [];
  const out: PlaceResult[] = [];
  for (const r of rows) {
    if (!r || typeof r !== 'object') continue;
    const o = r as Record<string, unknown>;
    const name = typeof o.name === 'string' ? o.name.trim() : '';
    const lat = typeof o.lat === 'number' && Number.isFinite(o.lat) ? o.lat : null;
    const lon = typeof o.lon === 'number' && Number.isFinite(o.lon) ? o.lon : null;
    if (!name || lat === null || lon === null) continue;
    const id = typeof o.id === 'string' && o.id.trim() ? o.id.trim() : null;
    const d = typeof o.distance_m === 'number' && Number.isFinite(o.distance_m) && o.distance_m >= 0 ? Math.round(o.distance_m) : null;
    out.push({
      key: id ? `v:${id}` : `p:${lat.toFixed(5)},${lon.toFixed(5)}:${name}`,
      name,
      at: needle ? foldForSearch(name).indexOf(needle) : -1,
      address: typeof o.address === 'string' ? o.address.trim() : '',
      lat,
      lon,
      distanceM: d,
    });
  }
  return out;
};

/**
 * Люди й гілки за назвою, плюс учасники гілок за іменем.
 *
 * Учасник, у якого є своя розмова вдвох, показується ОДИН раз — рядком тієї
 * розмови; інакше одна людина стояла б двічі поспіль.
 */
export const searchPeople = (chats: Chat[], needle: string): PersonResult[] => {
  if (!needle) return [];
  const out: PersonResult[] = [];
  const dmPeers = new Set<string>();
  const bare = needle.replace(/^@/, '');
  for (const c of chats) {
    const name = displayTitle(c.title);
    const at = foldForSearch(name).indexOf(needle);
    if (c.peerNodeId) dmPeers.add(c.peerNodeId);
    const handle = (c.handle || '').replace(/^@/, '').toLowerCase();
    if (at >= 0) out.push({ key: `c:${c.id}`, chatId: c.id, name, at, chat: c, handle: handle || undefined });
    else if (bare && handle && foldForSearch(handle).startsWith(bare)) out.push({ key: `c:${c.id}`, chatId: c.id, name, at: -1, chat: c, handle });
  }
  const seen = new Set<string>();
  for (const c of chats) {
    for (const m of c.members ?? []) {
      if (m.isMe || dmPeers.has(m.id) || seen.has(m.id)) continue;
      const at = foldForSearch(m.name).indexOf(needle);
      if (at < 0) continue;
      seen.add(m.id);
      out.push({ key: `m:${m.id}`, chatId: c.id, name: m.name, at, member: m, where: displayTitle(c.title) });
    }
  }
  return out;
};

const authorOf = (m: Message): string => (m.isSelf ? tNow('chatList.you') : m.senderName?.trim() || tNow('messengerRoot.peerFallback'));

const stamp = (s?: string): number => {
  const t = s ? Date.parse(s) : NaN;
  return Number.isFinite(t) ? t : 0;
};

/** Знахідки індексу вебу → рядки видачі (фрагмент — із самого стору). */
export const lettersFromIndex = (hits: IndexedHit[], chats: Chat[], needle: string, meId?: string): LetterResult[] => {
  const byId = new Map(chats.map((c) => [c.id, c]));
  const out: LetterResult[] = [];
  for (const h of hits) {
    const chat = byId.get(h.chatId);
    const msg = chat?.messages.find((m) => m.id === h.messageId);
    if (!chat || !msg) continue;
    const fragment = fragmentOfMessage(msg, needle, meId);
    if (!fragment) continue;
    out.push({
      chatId: chat.id,
      messageId: msg.id,
      author: authorOf(msg),
      where: displayTitle(chat.title),
      fragment,
      sentAt: msg.sentAt,
    });
  }
  return out;
};

/** Знахідки вузла → ті самі рядки. Розмову, якої список не знає, пропускаємо: відкрити її нічим. */
export const lettersFromNode = (hits: NodeSearchHit[], chats: Chat[], needle: string): LetterResult[] => {
  const byId = new Map(chats.map((c) => [c.id, c]));
  const out: LetterResult[] = [];
  for (const h of hits) {
    const chat = byId.get(h.conversation_id);
    if (!chat) continue;
    const fragment = fragmentOf(plainText(h.text), needle, h.field);
    if (!fragment) continue;
    out.push({
      chatId: chat.id,
      messageId: h.message_id,
      author: h.author_name?.trim() || tNow('chatList.you'),
      where: displayTitle(chat.title),
      fragment,
      sentAt: h.sent_at,
    });
  }
  return out;
};

/** Обʼєднати без дублів (перше джерело перемагає) і впорядкувати від найновішого. */
export const mergeLetters = (first: LetterResult[], second: LetterResult[], cap = 100): LetterResult[] => {
  const seen = new Set<string>();
  const all: LetterResult[] = [];
  for (const l of [...first, ...second]) {
    const k = `${l.chatId}\u0000${l.messageId}`;
    if (seen.has(k)) continue;
    seen.add(k);
    all.push(l);
  }
  all.sort((a, b) => stamp(b.sentAt) - stamp(a.sentAt));
  return all.slice(0, cap);
};

/** Запит — рівно один тег: `#світ`, без пробілів і сторонніх знаків. */
const TAG_QUERY = /^#[a-zA-Z0-9_\u0400-\u04FF]+$/;

/**
 * Запит-тег шукає ТЕГ, а не шматок слова.
 *
 * Обидва джерела (індекс вебу й вузол) шукають підрядок, тож `#світ`
 * приносив і `#світло`. Людина, що торкнулась тегу, питала про одну тему —
 * сусідня тема з тим самим початком у видачі видає себе за неї. Тож лишаємо
 * лист, лише коли в показаному шматку тег стоїть цілим словом. Шматок не
 * обрізається з хвоста (fragmentOf), тож тег на місці збігу завжди цілий.
 *
 * Звіряємо ЗГОРНУТЕ з згорнутим — так само, як шукали: `#Світ` і `#світ` —
 * один тег. Запит не тег — видача без змін.
 */
export const onlyExactTag = (letters: LetterResult[], needle: string): LetterResult[] => {
  if (!TAG_QUERY.test(needle)) return letters;
  const want = needle.slice(1);
  return letters.filter((l) => hashtagsIn(l.fragment.text).some((t) => foldForSearch(t) === want));
};
