/**
 * Стрічка месенджера з вузла. Джерело правди — база вузла, а не памʼять вкладки.
 */

import { forwardOfRow, quoteOfRow } from './wireCarry';
import { clockLabel } from '../utils/clockFormat';
import { normalizeAssistantRow } from './assistantChat';
import { isMicroThumb } from './imagePrep';
import { ApiError, BASE, request } from './api';
import { chatArchive } from './chatArchive';
import { chatKeep } from './chatKeep';
import { chatMarkUnread } from './chatMarkUnread';
import { chatPins } from './chatPins';
import type { Chat, ChatCircle, ChatLastCard, ChatPlan, GroupSettings, Message, Reaction, SecureMedia } from '../types/messenger';
import { parseGeoPoint, parseLiveLocation } from './messengerGeo';
import { brokenFromRow, placeFromRow, routeFromRow } from './peerWire';
import { cardFromRow, isCardKind } from './composerCards';
import { readToken } from './tokenStore';
import type { NodeSearchReport } from './globalSearch';
import { phonePort } from './phonePort';
import { readPayment, readSeal } from './solanaFields';
import { parseCardMarks } from '../types/meetup';
import { isCarrierManifest } from './carrier/seal';
import { tNow } from '../components/chat/tNow';

/**
 * Міст у меш телефона, або null у звичайному браузері.
 *
 * `request()` в api.ts має свою таку ж перевірку — тут вона повторена, бо
 * байти через `request()` не ходять: він возить лише JSON.
 */
export function phoneBridge(): { call: (m: string, p: string, b: string | null) => string } | null {
  const b = (globalThis as {
    PhantomBridge?: { call: (m: string, p: string, b: string | null) => string };
  }).PhantomBridge;
  return b && typeof b.call === 'function' ? b : null;
}

/**
 * base64 без розгортання масиву в аргументи: `String.fromCharCode(...bytes)`
 * на восьми мегабайтах кладе стек, і кладе його саме на великому файлі —
 * тобто рівно там, де воно найпотрібніше.
 */
function b64Of(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  const step = 0x8000;
  let s = '';
  for (let i = 0; i < bytes.length; i += step) {
    s += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return btoa(s);
}

/** Розмова «Збережене» — справжня розмова вузла з цим id, закріплена першою. */
export const SAVED_NOTES_ID = 'chat_saved_notes';

export interface SavedNoteImport {
  client_id: string;
  kind: string;
  body: string | null;
  sent_at: string | null;
  reply_to_id: string | null;
}

/** Відкладений лист з апарата (`/messenger/scheduled`). */
export interface NodeScheduledLetter {
  id: string;
  conversation_id: string;
  text: string;
  due_at_ms: number;
  created_at_ms: number;
  quiet: boolean;
  /** Чому не пішов; null — ще чекає свого часу. */
  failure: string | null;
  /** Старі збірки апарата цих полів не знають. */
  repeat?: string;
  when_online?: boolean;
}

export interface NodeConversation {
  id: string;
  title: string;
  kind: string;
  circle: string;
  circles?: string[];
  handle: string | null;
  avatar: string | null;
  pinned: boolean;
  archived: boolean;
  created_at: string;
  updated_at: string;
  contact_id?: string | null;
  is_demo?: boolean;
  /** Мій власний пристрій (спарений ПК чи позначений власником). */
  mine?: boolean;
  /** null — розмова ні з ким, тож і звіряти нема кого. */
  contact_verified?: boolean | null;
  /** Ключ співрозмовника змінився після звірки — розмова мусить сказати це до наступного листа. */
  contact_key_changed?: boolean;
  contact_key_changed_at?: string | null;
  /** Звірений контакт змінив ключ — листи чекають на «Продовжити». */
  contact_key_hold?: boolean;
  disappear_s?: number | null;
  peer_node_id?: string | null;
  unread_count?: number;
  last_kind?: string | null;
  /**
   * Для службових родів міст шле null, а не сире тіло: рядок переліку — це
   * ДРУГЕ місце, де власник побачив протокол 04.09.
   */
  last_snippet?: string | null;
  // ── пласкі поля останнього кадру: числа для переліку без стрічки ──
  last_route_profile?: string | null;
  last_route_distance_m?: number | null;
  last_route_duration_s?: number | null;
  last_place_lat?: number | null;
  last_place_lon?: number | null;
  last_card_title?: string | null;
  last_card_total?: number | null;
  last_card_currency?: string | null;
  last_author?: string | null;
  last_at?: string | null;
  /** Спокійний потік: останній власний лист, останній чужий, листів за 30 днів. */
  last_self_at?: string | null;
  last_peer_at?: string | null;
  letters_30d?: number | null;
  /** Опис гілки. Їде дротом актом `about`, у заголовку його немає. */
  description?: string | null;
  standing?: 'in' | 'left' | 'removed' | null;
  dropped?: Array<{ id: string; name: string | null; reason: string }>;
  /** Налаштування гілки (`set` на дроті): kind, slow, hist, ask, link, talk, sign. */
  settings?: Record<string, string> | null;
  /**
   * Закріплений рядок гілки. `id: ''` при наявному `author_id` означає, що
   * закріплене Є, але самого рядка цей телефон не має — закріпили до того, як
   * рядок сюди приїхав, або його стерли. Це не «нічого не закріплено».
   */
  pinned_message?: {
    id: string;
    author_id: string;
    author: string;
    at: string;
    text: string | null;
  } | null;
  live_room?: {
    call_id: string;
    started_at: string;
    faces: string[];
    mine?: boolean;
  } | null;
  /** K2: the plan of a plan group (WebMessengerPlanRow), ISO times. */
  plan?: {
    meetup_id: string;
    venue: string | null;
    organizer: string;
    at: string | null;
    expires_at: string | null;
    kept_by: string[];
    cancelled: boolean;
  } | null;
}

export interface NodeMessage {
  id: string;
  conversation_id: string;
  client_id: string;
  seq: number;
  author_id: string;
  author_name: string;
  kind: string;
  body: string | null;
  ciphertext: string | null;
  transport: string | null;
  reply_to_id?: string | null;
  /** `mn` with the letter: member ids the author named with @. Absent on older letters. */
  mentions?: string[] | null;
  /** 1:1 on the phone: the pin both sides see (`PH-PIN:`). Absent when the peer can't carry it. */
  pinned?: boolean;
  /** Пересланий лист: чий він (ім'я й id автора); немає — свій. */
  fw?: { name: string; id?: string } | null;
  /** Обраний шматок цитати, ≤ 200 знаків; лише при `reply_to_id`. */
  qt?: string | null;
  channel_id?: string | null;
  /** Питання до PHANTOM, що поїхало Наміром до мого ПК — id з журналу «Мої прохання». */
  intent_id?: string | null;
  pay_draft?: { peer_id: string; token: string; amount: string; sent: boolean } | null;
  commitment_card?: {
    kind: 'choose' | 'due' | 'saved' | 'list';
    commitment_id?: string | null;
    pending_id?: string | null;
    person: string;
    peer_id?: string | null;
    what: string;
    due_ms?: number | null;
    candidates?: Array<{ peer_id: string; name: string; hint?: string | null }> | null;
    state: 'open' | 'done' | 'dropped';
  } | null;
  /** Рядок роду `timer` (строк зникнення): секунди. ПК кладе їх у тіло, міст — полем. */
  timer_s?: number | null;
  /** Картка посилання, яку склав і зашифровано надіслав ВІДПРАВНИК (PeerPreview). */
  link_preview?: { url: string; title: string; description?: string; site?: string; image?: string | null } | null;
  sent_at: string;
  edited_at: string | null;
  deleted_at: string | null;
  /** local | queued | sent — див. routes_messenger.py */
  delivery?: string;
  delivery_state?: string;
  /** Питання без відповіді: причина ярликом (міст, мовою застосунку); лише при delivery_state=failed. */
  fail_reason?: string | null;
  /**
   * stored | queued | sent | missing — стан ПЕРЕВЕЗЕННЯ вкладення.
   * Кадр із ключем міг доїхати, а байти — ні; тоді галочка «надіслано» на
   * бульбашці означала б фото, якого в людини немає.
   */
  attachment_state?: string | null;
  /** local | stored | acked — `WebMessengerKinds.relayState` у companion. */
  relay_state?: string | null;
  /**
   * Скільки копій цього рядка вже дійшло і скільки їх усього.
   *
   * Один сказаний рядок у групі — це N окремих підписаних листів, у кожного
   * своя доля. Міст кладе ці два числа в КОЖЕН груповий рядок, і саме їх
   * наявність відрізняє груповий рядок від рядка розмови вдвох
   * (`WebMessengerBridge.groupMessage`). Для розмови вдвох їх немає — доля
   * там одна, і число «1 з 1» було б шумом.
   */
  fan_delivered?: number;
  /** Скільки учасників гілки прочитали власний рядок. Немає — невідомо. */
  fan_read?: number;
  /** Коли співрозмовник прочитав власний рядок. Немає поля — невідомо (приватність вимкнено). */
  read_at?: string | null;
  /**
   * Переказ / купюра і відбиток файла (`WebMessengerKinds.solana`). `kind`
   * лишається 'text', а `body` — готове речення: запас для старого бандла.
   * Форму перевіряє `services/solanaFields.ts`.
   */
  payment?: unknown;
  seal?: unknown;
  fan_total?: number;
  /**
   * Що саме зробили з гілкою: add | remove | leave | rename | grant |
   * transfer | pin | unpin | about | face (`WebMessengerGroups.ACT`).
   *
   * Міст кладе це поле в кожен рядок-акт із самого початку, і веб його не
   * читав ЖОДНОГО разу. `body` такого рядка — вже готове речення з ресурсів
   * телефона, тож журнал гілки не треба вигадувати: він приїхав і лежав.
   */
  system_act?: string | null;
  system_act_target?: string | null;
  /**
   * Смайли під рядком, згорнуті з ЖУРНАЛУ позначок (`PeerMark.foldAll`).
   *
   * Міст кладе це поле в кожен рядок із 13.09, і веб не читав його ЖОДНОГО
   * разу: `messageFromNode` про нього не знав, а `loadMessagesForChat`
   * заступає `c.messages` цілком тим, що дав міст. Тобто власний смайл жив до
   * першого ж `__phantomMeshChanged`, а чужий не зʼявлявся ніколи — при тому
   * що на дроті він був і в журналі лежав.
   *
   * `mine` є лише в моїй позначці і лише доки вона не доїхала.
   */
  reactions?: Array<{
    emoji: string;
    count: number;
    users?: string[];
    names?: string[];
    mine?: string | null;
  }>;

  // ── кадри меша, розібрані мостом (WebMessengerKinds.kt, коміт 61b3cc84) ──
  //
  // Поля пласкі й лежать просто на листі, як attachment_state; `body` для
  // цих родів ПОРОЖНЄ навмисно: доки веб малював тіло, він малював протокол.
  /** kind='route' */
  route_profile?: string | null;
  route_distance_m?: number | null;
  route_duration_s?: number | null;
  route_expires_at_ms?: number | null;
  route_waypoints?: unknown;
  route_shape?: unknown;
  /** kind='place'; accuracy = null означає «не сказали», а не нуль. */
  place_lat?: number | null;
  place_lon?: number | null;
  place_accuracy_m?: number | null;
  /** kind='broken'; речення пише хост — він єдиний знає, що не зійшлося. */
  broken_kind?: string | null;
  broken_reason?: string | null;
}

export interface NodeBlob {
  blob_id: string;
  size: number;
  sha256: string;
  /** stored | queued | sent | missing */
  state: string;
}

export interface NodeIdentity {
  node_id: string;
  bundle: Record<string, unknown>;
  /** Стислий ключ — те, що йде в QR. */
  compact: string;
  /** phantom://invite/… — телефон відкриває його сам, без вставляння. */
  link?: string;
  /** Відбиток власного ключа — 24 цифри; телефон віддає, вузол може й не. */
  fingerprint?: string;
  fingerprint_pretty?: string;
  /**
   * Адреса з боку моста телефона: `host:port`, за якою телефон слухає;
   * null — не слухає, лист піде через сховок; поля немає — стара збірка хоста.
   * Вузол ПК цього не віддає: там адресу вгадують з origin, і людина її править.
   * Веб на телефоні адресу не складає — лише показує цю.
   */
  address?: string | null;
  /** Міст телефона: адреса приватна (RFC1918, CGNAT, loopback, link-local, ULA, .local) — ззовні недосяжна. Поля немає — стара збірка хоста. */
  address_is_local?: boolean;
  /**
   * Стеля ОДНОГО вкладення (шифротекст, байти), яку ЦЕЙ вузол готовий узяти.
   * ПК віддає його завжди (те саме число, яким `blobs.BLOB_LIMIT_BYTES`
   * рахує 413 — джерело одне). Поля немає — стара збірка хоста АБО телефон,
   * чий міст ще не оголошує `PeerFile.MAX_BYTES` цим полем: контракт описано
   * в `docs/MEDIA_LIMIT_HANDSHAKE.md`. Відсутність — не помилка, а «ще не
   * домовились»; `messengerMedia.applyMediaLimitFromNode` тоді лишає чинну
   * межу, як і раніше.
   */
  max_attachment_bytes?: number;
}

export type Visibility = 'all' | 'people' | 'nobody';
export type VisibilityKey = 'last_seen' | 'photo' | 'calls';
export type PrivacyState = { read_receipts: boolean } & Partial<Record<VisibilityKey, Visibility>>;

export interface QuietHours {
  enabled: boolean;
  /** «23:00» — місцевий час телефона. */
  from: string;
  to: string;
  /** peer_node_id людей, чиї листи й дзвінки лунають і вночі. */
  except: string[];
}

export interface NodeContact {
  id: string;
  peer_node_id: string;
  display_name: string;
  peer_address: string | null;
  safety_number: string;
  safety_number_pretty: string;
  /** Відбиток ключа співрозмовника — половина числа безпеки. */
  fingerprint?: string;
  fingerprint_pretty?: string;
  verified: boolean;
  /** Число знято камерою з екрана співрозмовника, не зі слів. */
  verified_by_camera?: boolean;
  /** Ключ під цим іменем змінився після останньої звірки — вона більше не діє. */
  key_changed?: boolean;
  key_changed_at?: string | null;
  session_ready: boolean;
  created_at: string;

  // ── профіль, що приїхав кадром PH-PRF ────────────────────────────────
  //
  // Їде в ТОМУ САМОМУ рядку, що й імʼя: рядок, у якому імʼя нове, а
  // аватар ще старий, — це два різні моменти часу, показані як один.
  //
  // Стан ОКРЕМИМ полем, бо порожній рядок не вміє розповісти правду:
  // «ще не приїхало» (відправник на старій збірці або кадр у дорозі) і
  // «людина стерла це в себе» на екрані мусять виглядати по-різному.
  // `unreadable` — четвертий стан: байти приїхали, але це не WebP і не
  // JPEG, тобто не те, що дріт дозволив везти.

  /** Найбільший застосований rev. 0 — жодного кадру ще не було. */
  profile_rev?: number;
  /** Коли кадр приїхав. null — не приїжджав. */
  profile_at?: string | null;
  avatar_state?: WireFieldState | 'unreadable';
  /** Готова `data:`-адреса з родом, упізнаним за байтами. */
  avatar_thumb?: string | null;
  /** Фото до 512 px адресою телефона (`/webmsgr/_faces/…?v=rev`); null — фото немає. */
  photo?: string | null;
  avatar_bytes?: number;
  /** Банер: опис приїхав кадром профілю, фото — кадрами PH-PBN. */
  banner_state?: WireFieldState;
  banner?: PeerBannerRow | null;
  status_emoji_state?: WireFieldState;
  status_emoji?: string | null;
  status_text_state?: WireFieldState;
  status_text?: string | null;
  bio_state?: WireFieldState;
  bio?: string | null;
  /** Коли людину востаннє чули будь-яким кадром (і зі сховка). Не «у мережі». */
  heard_at?: string | null;
  /** Коли чули саме в цій мережі — тобто поруч. */
  lan_seen_at?: string | null;
  /** Цей телефон не приймає від людини жодного кадру. Знає лише цей пристрій. */
  blocked?: boolean;
}

/** `GET /messenger/pulse` — див. `WebMessengerPulse.kt`. */
export interface NodePulse {
  sky: { oblast: string; since_at: string | null; feed_silent: boolean } | null;
  near: Array<{
    peer_id: string;
    name: string;
    near: 'same' | 'near' | 'far' | null;
    precision: 'district' | 'quarter' | 'exact' | null;
    at: string;
  }>;
  /** `narrow` — вузька дорога (PeerLinkMeter): текст іде першим; `narrow_word` — фраза телефона. */
  link: { online: boolean | null; direct: boolean; narrow?: boolean; narrow_word?: string | null };
}

/** До 6 фото в порядку власника; `url` null — фото ще в дорозі. Фокус 0..255. */
export interface PeerBannerRow {
  video_still: boolean;
  items: Array<{ url: string | null; fx: number; fy: number }>;
  /** Петля банера (до 8 МБ): телефон тягне її на прохання; `ready` — уже складена тут. */
  video?: { blob_id: string; size: number; ready: boolean } | null;
}

/** Три стани поля профілю на дроті — див. `profileWireCodec`. */
export type WireFieldState = 'absent' | 'cleared' | 'set';

/** Рядок `GET /messenger/profiles` — те саме, що в контакті, плюс імʼя. */
export interface NodeCarrierRecord {
  transfer_id: string;
  size: number;
  n: number;
  received: number[];
  count: number;
}

export interface NodePeerProfile extends Pick<
  NodeContact,
  'peer_node_id' | 'display_name' | 'profile_rev' | 'profile_at'
  | 'avatar_state' | 'avatar_thumb' | 'avatar_bytes' | 'photo' | 'banner_state' | 'banner'
  | 'status_emoji_state' | 'status_emoji'
  | 'status_text_state' | 'status_text'
  | 'bio_state' | 'bio'
  | 'heard_at' | 'lan_seen_at'
> {
  peer_node_id: string;
  display_name: string;
  /** Carrier C2: рівень і стеля вкладення, які людина оголосила в кадрі профілю; немає — не оголошувала. */
  carrier?: number;
  max_attachment_bytes?: number;
}

/**
 * Що покласти у власний кадр профілю.
 *
 * `undefined` — поля в кадрі НЕ БУДЕ («ще не приїхало» для приймача);
 * `null` — тег нульової довжини («я стер це в себе»). Саме цим двом
 * значенням і відповідають два різні написи на чужому екрані, тож
 * підміняти одне одним не можна ніде по дорозі.
 *
 * `avatar` — base64 САМИХ БАЙТІВ, без `data:`-шапки: рід приймач упізнає
 * за байтами, і слово відправника про рід на дроті не їде взагалі.
 */
export interface PublishProfileInput {
  /** Останній відомий вебу rev. Лічильник веде телефон — це лише підлога. */
  rev?: number;
  avatar?: string | null;
  /** Фото до 512 px, base64 байтів. Нова мініатюра без фото прибирає старе фото. */
  photo?: string | null;
  status_emoji?: string | null;
  status_text?: string | null;
  bio?: string | null;
}

export interface PublishProfileReport {
  rev: number;
  frame_bytes: number;
  sent: string[];
  sent_count: number;
  skipped: Array<{ peer_node_id: string; reason: 'no_key' | 'refused' | 'blocked' }>;
  skipped_count: number;
  /** Своє фото адресою телефона — те, що показати на «Я». */
  avatar?: string | null;
}

/**
 * Одне імʼя зі складу групи.
 *
 * `in_roster` — єдине, що цей пристрій справді може перевірити: склад їде в
 * заголовку кожного листа, тож назвати можна й того, чиї листи тут ніколи не
 * відкриються. Тому поруч з іменем стоїть твердження про ТВІЙ список, а не
 * про світ.
 */
export interface NodeGroupMember {
  id: string;
  name: string;
  in_roster: boolean;
  verified: boolean;
  /** Є ключ — отже копія для цієї людини взагалі може запечататись. */
  session_ready: boolean;
  is_me: boolean;
  /**
   * Щабель на дроті: owner | admin | mod | member. Обчислений вузлом з того
   * самого журналу, яким приймач судить акти, — не вигаданий тут.
   */
  role: 'owner' | 'admin' | 'mod' | 'member';
  /**
   * Чи може оператор зробити це саме ЦІЙ людині. Поімённо, бо право залежить
   * від обох щаблів: адмін не чіпає адміна.
   */
  may_remove: boolean;
  may_grant: boolean;
  may_own: boolean;
  rights?: string[];
  may_rights?: boolean;
}

export interface NodeGroupMembers {
  id: string;
  title: string;
  members: NodeGroupMember[];
  /** Кого ще можна додати — з твого списку, і лише з ключем. */
  addable: Array<{ id: string; name: string }>;
  max_members: number;
  /**
   * Міст завжди шле `false`, і це не заглушка.
   *
   * Склад — слово того, хто вас додав (заголовок його листа), а не факт про
   * групу. Показувати його як остаточний було б підвищенням засвідченого до
   * авторитетного. Див. `memory/phantom-group-membership-truth.md`.
   */
  authoritative: boolean;
  /** in | left | removed — чи можна ще писати сюди. */
  standing: string;
  my_role: 'owner' | 'admin' | 'mod' | 'member';
  owner_id: string;
  /** Що САМЕ цей пристрій застосує зі свого журналу. */
  powers: {
    add: boolean;
    remove: boolean;
    rename: boolean;
    card: boolean;
    grant: boolean;
    transfer: boolean;
    pin: boolean;
    invite?: boolean;
    settings?: boolean;
  };
}

/** Доля ОДНІЄЇ копії групового листа — в однієї людини. */
export interface NodeFanCopy {
  peer_id: string;
  name: string;
  in_roster: boolean;
  state: string;
}

/**
 * Кому дійшло — поіменно, і лише про власний рядок.
 *
 * Про чужу репліку цей телефон знає одну копію — свою — і про решту віяра не
 * знає нічого; там міст відповідає 409 `not_mine`, і це мовчання чесніше за
 * число, зібране з одного свідчення.
 */
export interface NodeFanOut {
  message_id: string;
  delivered: number;
  waiting: number;
  failed: number;
  total: number;
  to: NodeFanCopy[];
}

export interface Road {
  id: 'direct' | 'relay' | 'mailbox' | 'blobs';
  title: string;
  configured: boolean;
  live: boolean | null;
  state: string;
  detail: string;
  howto: string;
}

export interface RoadsReport {
  roads: Road[];
  configured: number;
  total: number;
}

export interface OwnPairState {
  state: 'idle' | 'working' | 'done' | 'failed';
  reason?: 'bad_input' | 'expired' | 'not_found' | 'unreachable' | 'not_proven';
}

export interface TodayTabs {
  order: string[];
  hidden: string[];
}

export interface ContactRequest {
  id: string;
  direction: 'in' | 'out';
  peer_node_id: string;
  name: string;
  text: string;
  created_at: string;
  delivery: 'sent' | 'queued';
}
export interface ContactRequests {
  incoming: ContactRequest[];
  outgoing: ContactRequest[];
}

export interface StaffLine {
  mine: boolean;
  text: string;
  at: number;
}
export interface StaffThread {
  id: string;
  client: string;
  lines: StaffLine[];
}
export interface StaffPassBody {
  business: string;
  rev: number;
  threads: StaffThread[];
}
export interface StaffPassRow extends StaffPassBody {
  from: string;
}

export const messengerApi = {
  identity: () => request<NodeIdentity>('GET', '/messenger/identity'),

  phoneInvite: (name?: string) =>
    request<{ code: string; link: string; peer_id: string; name: string }>(
      'GET',
      '/messenger/phone/invite' + (name ? `?name=${encodeURIComponent(name)}` : ''),
    ),

  /** Прочитати вголос голосом телефона (WebView не має speechSynthesis). */
  speak: (text: string) => request<{ speaking: boolean }>('POST', '/messenger/tts/speak', { text }),
  stopSpeaking: () => request<{ speaking: boolean }>('POST', '/messenger/tts/stop'),

  /** Звіти про прочитання: вимкнені — не шлемо своїх і не бачимо чужих. */
  privacy: () => request<PrivacyState>('GET', '/messenger/privacy'),
  setPrivacy: (readReceipts: boolean) =>
    request<PrivacyState>('PATCH', '/messenger/privacy', { read_receipts: readReceipts }),
  setVisibility: (patch: Partial<Record<VisibilityKey, Visibility>>) =>
    request<PrivacyState>('PATCH', '/messenger/privacy', patch),

  /** Тиша вночі: у ці години телефон не дзвонить, крім людей із `except`. */
  quiet: () => request<QuietHours>('GET', '/messenger/quiet'),
  setQuiet: (patch: Partial<QuietHours>) => request<QuietHours>('PATCH', '/messenger/quiet', patch),

  /** Покласти файл у галерею чи «Завантаження» телефона. `b64` — відкриті байти. */
  saveToPhone: (name: string, mime: string, b64: string) =>
    request<{ saved: boolean; where: string; name: string }>('POST', '/messenger/save', { name, mime, b64 }),

  /** Імʼя цього пристрою: з ним іде кожне нове запрошення. */
  renameSelf: (name: string) => request<NodeIdentity>('PATCH', '/messenger/identity', { name }),

  roads: () => request<RoadsReport>('GET', '/messenger/roads'),

  listContacts: () => request<NodeContact[]>('GET', '/messenger/contacts'),

  addContact: (
    display_name: string,
    key: { bundle?: Record<string, unknown>; compact?: string },
    peer_address?: string | null,
  ) =>
    request<NodeContact>('POST', '/messenger/contacts', {
      display_name,
      bundle: key.bundle ?? null,
      compact: key.compact ?? null,
      peer_address: peer_address || null,
    }),

  /** Запити в контакти: вхідні й вихідні, що чекають. */
  listRequests: () => request<ContactRequests>('GET', '/messenger/requests'),
  sendRequest: (contactId: string, text?: string) =>
    request<ContactRequest>('POST', '/messenger/requests', { contact_id: contactId, text: text || null }),
  acceptRequest: (id: string) =>
    request<{ conversation_id: string; contact_id: string }>('POST', `/messenger/requests/${encodeURIComponent(id)}/accept`).then((r) => {
      if (r?.conversation_id) chatKeep.add(r.conversation_id);
      return r;
    }),
  declineRequest: (id: string) => request<unknown>('POST', `/messenger/requests/${encodeURIComponent(id)}/decline`),
  withdrawRequest: (id: string) => request<unknown>('POST', `/messenger/requests/${encodeURIComponent(id)}/withdraw`),

  verifyContact: (id: string) =>
    request<NodeContact>('POST', `/messenger/contacts/${id}/verify`),
  /** «Продовжити» після зміни ключа: пауза знімається, тривога (не звірено) лишається. */
  continueAfterKeyChange: (peerNodeId: string) =>
    request<NodeContact>('POST', `/messenger/contacts/${encodeURIComponent(peerNodeId)}/continue`),

  /** Кого цей телефон не слухає. Людині з того боку нічого не шлеться. */
  listBlocked: () => request<{ blocked: string[] }>('GET', '/messenger/blocked'),

  blockPeer: (peerNodeId: string) =>
    request<{ blocked: boolean }>('POST', `/messenger/contacts/${encodeURIComponent(peerNodeId)}/block`),

  /** Скарга: телефон блокує людину й відкриває лист у підтримку з ідентифікаторами, без тексту листування. */
  reportPeer: (peerNodeId: string, messageId?: string) =>
    request<{ blocked: boolean; mailed: boolean; address: string }>(
      'POST',
      `/messenger/contacts/${encodeURIComponent(peerNodeId)}/report`,
      { message_id: messageId ?? null },
    ),

  unblockPeer: (peerNodeId: string) =>
    request<{ blocked: boolean }>('DELETE', `/messenger/contacts/${encodeURIComponent(peerNodeId)}/block`),

  /** Попросити в людини петлю її банера; байти складе телефон і віддасть через `fileUrl`. */
  pullBannerVideo: (peerNodeId: string) =>
    request<{ state: 'none' | 'ready' | 'asked' }>(
      'POST',
      `/messenger/contacts/${encodeURIComponent(peerNodeId)}/banner-video`,
    ),

  /** Профілі, які НАМ прислали. Одна картина на всі поверхні, не по одній. */
  peerProfiles: () => request<NodePeerProfile[]>('GET', '/messenger/profiles'),

  staffPush: (to: string, pass: StaffPassBody) => request<{ sent: boolean }>('POST', '/messenger/staff/push', { to, pass }),
  staffInbox: () => request<{ passes: StaffPassRow[] }>('GET', '/messenger/staff'),

  /** Жива смуга над списком: небо, люди, що показують місце, звʼязок. Лише телефон. */
  pulse: () => request<NodePulse>('GET', '/messenger/pulse'),

  /** Мапа телефона — на людині, що показує місце. Координати у веб не їдуть. */
  focusPeerOnMap: (peerId: string) =>
    request<{ opened: boolean }>('POST', `/messenger/pulse/where/${peerId}`),

  /**
   * Яка розмова на екрані (null — жодна): глушник сповіщень на телефоні.
   *
   * Двері є лише в мосту телефона. ПК-вузол їх не має, і веб на ПК стукав
   * у них на кожен вибір розмови — 405 у консолі щоразу. Без моста глушити
   * нічого: шторку сповіщень ПК-вузол не малює.
   */
  markVisible: (conversationId: string | null) =>
    phoneBridge()
      ? request<{ visible: string | null }>('POST', '/messenger/visible', { conversation_id: conversationId })
      : Promise.resolve({ visible: null }),

  chromeHidden: (hidden: boolean) =>
    phoneBridge()
      ? request<{ hidden: boolean }>('POST', '/messenger/chrome', { hidden })
      : Promise.resolve({ hidden: false }),

  /**
   * Свій профіль — УСІМ контактам із ключем, одним кадром на кожного.
   *
   * Не «в поточну розмову»: профіль розмові не належить (контракт,
   * `docs/PROFILE_WIRE_CONTRACT.md`), і людина, з якою сьогодні не
   * переписувались, мусить побачити нове фото так само.
   *
   * Шлях `profiles/publish`, а не `profile`: `POST /messenger/profile`
   * у мості вже зайнято — там зберігається імʼя й аватар ЦЬОГО пристрою
   * на диск. Два різні вчинки — два різні шляхи.
   */
  publishProfile: (input: PublishProfileInput) =>
    request<PublishProfileReport>('POST', '/messenger/profiles/publish', input),

  markRead: (conversationId: string, seq: number) =>
    request<{ unread_count: number }>(
      'PATCH', `/messenger/conversations/${conversationId}/read`, { seq },
    ),

  renameConversation: (conversationId: string, title: string) =>
    request<NodeConversation>('PATCH', `/messenger/conversations/${conversationId}`, { title }),

  /** Коло людини чи групи; 'all' знімає його. Лише телефон. */
  setCircle: (conversationId: string, circle: ChatCircle) =>
    request<NodeConversation>('PATCH', `/messenger/conversations/${conversationId}/circle`, { circle }),

  /** Порядок і сховані сторінки «Сьогодні» — на телефоні, під своєю особою. */
  todayTabs: () => request<TodayTabs>('GET', '/messenger/today/tabs'),
  saveTodayTabs: (tabs: TodayTabs) => request<TodayTabs>('PUT', '/messenger/today/tabs', tabs),

  /** Розмова зникає з ЦЬОГО вузла; копію співрозмовника вузол не чіпає. */
  deleteConversation: async (conversationId: string) => {
    const said = await request<{ deleted: boolean }>('DELETE', `/messenger/conversations/${conversationId}`);
    chatKeep.drop(conversationId);
    chatPins.set(conversationId, false);
    chatArchive.set(conversationId, false);
    chatMarkUnread.clear(conversationId);
    return said;
  },

  // `leave()` тут більше немає. Маршруту `POST /messenger/exit` міст не несе
  // свідомо — вікном володіє андроїд, — тож єдиним наслідком виклику було
  // повідомлення «вийти можна системною кнопкою «назад»». Кнопку знято
  // (Sidebar), а разом із нею й виклик: інакше в бандлі лишився б рядок дороги,
  // якої не існує, і дзеркало контракту стерегло б порожнечу.

  /**
   * Нотатки «Збереженого», що лежали лише у сховищі вебу, переїжджають на
   * вузол. Повтор нічого не дублює: вузол упізнає лист за `client_id`.
   */
  importSavedNotes: (messages: SavedNoteImport[]) =>
    request<{ imported: number; skipped: number; refused: number }>(
      'POST', `/messenger/conversations/${SAVED_NOTES_ID}/import`, { messages },
    ),

  /** Стирає листи розмови на цьому вузлі разом із вкладеннями на диску. */
  clearConversation: async (conversationId: string) => {
    const said = await request<{ cleared: number; blobs: number }>(
      'POST', `/messenger/conversations/${conversationId}/clear`,
    );
    chatKeep.add(conversationId);
    return said;
  },

  /**
   * Видаляє повідомлення. forEveryone шле службовий кадр співрозмовнику —
   * тією ж наскрізною дорогою, що й текст.
   *
   * `frame` у відповіді каже правду про долю кадру: 'sent' — вузол адресата
   * його взяв, 'queued' — чекає в черзі (адресат офлайн і видалить пізніше),
   * 'local' — везти нікуди, розмова ні з ким.
   */
  deleteMessage: (conversationId: string, messageId: string, forEveryone: boolean = false) =>
    request<{ deleted: boolean; for_everyone: boolean; blobs: number; frame?: string }>(
      'DELETE',
      `/messenger/conversations/${conversationId}/messages/${messageId}`
        + `?for_everyone=${forEveryone ? 'true' : 'false'}`,
    ),

  editMessage: (conversationId: string, messageId: string, body: string) =>
    request<NodeMessage>('PATCH', `/messenger/conversations/${conversationId}/messages/${messageId}`, { body }),

  messageHistory: (conversationId: string, messageId: string) =>
    request<{ versions: Array<{ text: string; at: string }> }>(
      'GET',
      `/messenger/conversations/${conversationId}/messages/${messageId}/history`,
    ),

  /**
   * Смайл під рядком — У СІТКУ. Це те, чого тут не було.
   *
   * До 18.09 `messengerStore.toggleReaction` міняв масив у памʼяті вкладки й
   * кликав `messengerNetworkEngine.sendReaction`, у якого дві гілки і жодна не
   * веде в меш телефона: `p2p` шле кадр у браузерну WebRTC-сітку, якої в
   * застосунку немає, друга — у `this.ws`, сокет до чужого брокера. Ручка в
   * мосту (`WebMessengerBridge.react`, POST …/reactions) була написана 13.09 і
   * не викликалась із веба НІ РАЗУ. Тобто співрозмовник не бачив смайла
   * ніколи, а сам смайл зникав із першим оновленням стрічки.
   *
   * Напрям дотику («поставити» чи «зняти») тут НЕ передається навмисно: його
   * вирішує міст тим самим згортачем, яким малює результат
   * ([PeerMark.holds]). Вкладка не джерело правди про журнал, і її здогад
   * розійшовся б із журналом рівно тоді, коли поле застаріло на один кадр.
   *
   * Повертає САМ РЯДОК, уже зі згорнутими позначками — тобто правду замість
   * оптимістичного здогаду. Відмова приїжджає `ApiError` із реченням вузла:
   * немає ключа співрозмовника, немає такого листа, немає особи.
   */
  react: (conversationId: string, messageId: string, emoji: string) =>
    request<NodeMessage>(
      'POST',
      `/messenger/conversations/${conversationId}/messages/${messageId}/reactions`,
      { emoji },
    ),

  /** Скільки листів чекають на звʼязок — головний козир черги, зроблений видимим. */
  queueStatus: () => request<{ queued: number }>('GET', '/messenger/queue/status'),

  listConversations: () => request<NodeConversation[]>('GET', '/messenger/conversations'),

  /**
   * Лише названі розмови — на дельту телефона. `gone` — ті, яких уже нема.
   * Лише телефон: ПК-вузол цього шляху не знає.
   */
  conversationsByIds: (ids: string[]) =>
    request<{ rows: NodeConversation[]; gone: string[] }>(
      'GET',
      `/messenger/conversations?ids=${encodeURIComponent(ids.join(','))}`,
    ),

  /** Первинний список. Вузол сам вирішує, створювати чи віддати наявне. */
  bootstrap: (conversations: Array<{
    title: string;
    kind?: string;
    circle?: string;
    handle?: string | null;
    avatar?: string | null;
    is_demo?: boolean;
    messages?: Array<{
      client_id: string;
      author_id: string;
      author_name: string;
      kind?: string;
      body?: string | null;
    }>;
  }>) => request<NodeConversation[]>('POST', '/messenger/bootstrap', { conversations }),

  createConversation: (body: {
    title: string;
    kind?: string;
    circle?: string;
    handle?: string | null;
    avatar?: string | null;
    /** Без нього розмова ні з ким: лист нікуди не поїде. */
    contact_id?: string | null;
    /**
     * Кого беремо в групу. Без цього поля міст відповідає 422 `no_members`:
     * група живе своїми листами, а назва без людей — це ніщо, яке зникне з
     * першим перезапуском (`WebMessengerBridge.createGroup`).
     */
    member_ids?: string[];
    description?: string | null;
    topic?: string;
  }) =>
    request<NodeConversation>('POST', '/messenger/conversations', body).then((c) => {
      if (c?.id) chatKeep.add(c.id);
      return c;
    }),

  // ── групи ──────────────────────────────────────────────────────────────

  /** Склад групи + кого ще можна додати. Засвідчений, не авторитетний. */
  groupMembers: (conversationId: string) =>
    request<NodeGroupMembers>('GET', `/messenger/conversations/${conversationId}/members`),

  addGroupMember: (conversationId: string, peerId: string) =>
    request<NodeGroupMembers>(
      'POST', `/messenger/conversations/${conversationId}/members`, { peer_id: peerId },
    ),

  removeGroupMember: (conversationId: string, peerId: string) =>
    request<NodeGroupMembers>(
      'DELETE', `/messenger/conversations/${conversationId}/members/${peerId}`,
    ),

  /**
   * Вийти з групи. `told` — скільком це вже сказано, а не «готово»: копії
   * можуть лежати в черзі, і відповідь про це не бреше.
   */
  leaveGroup: (conversationId: string) =>
    request<{ left: boolean; told: number }>(
      'POST', `/messenger/conversations/${conversationId}/leave`,
    ),

  /**
   * Підвищити або понизити. Порожній `role` — понизити до учасника; `owner`
   * міст читає як передачу гілки, бо на дроті це різні дієслова й у другого є
   * наслідок для того, хто його каже.
   *
   * 403 `no_right_*` — це НЕ помилка мережі: приймач відмовив, і причина
   * названа. Показувати її як «не вдалось» означало б сховати єдине, що тут
   * варто знати.
   */
  setGroupRole: (conversationId: string, peerId: string, role: '' | 'owner' | 'admin' | 'mod' | 'member') =>
    request<NodeGroupMembers>(
      'PATCH', `/messenger/conversations/${conversationId}/members/${peerId}/role`, { role },
    ),

  /**
   * Обличчя гілки — тим самим маршрутом, що й назва, але БЕЗ неї.
   *
   * Міст читає `title` і `avatar` окремо (`renameGroup`): картинка сама по
   * собі не мусить нести назву, інакше поміняти обличчя було б неможливо, не
   * перейменувавши гілку всім. До 16.09 обличчя нікуди не їхало — лежало на
   * диску того, хто його поставив; тепер їде актом `Face`.
   */
  setGroupFace: (conversationId: string, avatar: string) =>
    request<NodeConversation>('PATCH', `/messenger/conversations/${conversationId}`, { avatar }),

  /** Опис гілки. Порожній рядок — стерти: це значення, а не «нічого». */
  describeGroup: (conversationId: string, about: string) =>
    request<NodeConversation>('PATCH', `/messenger/conversations/${conversationId}/about`, { about }),

  /** Налаштування гілки: кожен ключ стає окремим актом `set`. */
  setGroupSettings: (conversationId: string, patch: Record<string, string>) =>
    request<NodeConversation>('PATCH', `/messenger/conversations/${conversationId}/settings`, patch),

  /** Права адміна чи модератора. Лише власник. */
  setMemberRights: (conversationId: string, peerId: string, rights: string[]) =>
    request<NodeGroupMembers>(
      'PATCH', `/messenger/conversations/${conversationId}/members/${peerId}/rights`, { rights },
    ),

  /** 1:1: закріпити чи зняти для обох (`PH-PIN:`). Повертає рядок із правдою журналу. */
  pinDirectMessage: (conversationId: string, messageId: string, on: boolean) =>
    request<NodeMessage>(
      'POST', `/messenger/conversations/${conversationId}/messages/${messageId}/pin`, { on },
    ),

  /** Закріпити рядок. Порожній `message_id` — зняти закріплення. */
  pinGroupMessage: (conversationId: string, messageId: string) =>
    request<NodeConversation>(
      'POST', `/messenger/conversations/${conversationId}/pin`, { message_id: messageId },
    ),

  /** Доля кожної копії власного групового рядка. Про чужий — 409 `not_mine`. */
  messageDelivery: (conversationId: string, messageId: string) =>
    request<NodeFanOut>(
      'GET', `/messenger/conversations/${conversationId}/messages/${messageId}/delivery`,
    ),

  /**
   * Пошук по ВСІХ листах на вузлі — лише телефон (`WebMessengerSearch`).
   * Листи не переїжджають у веб: приходять тільки знайдені рядки.
   */
  searchMessages: (query: string, limit = 100) =>
    request<NodeSearchReport>(
      'GET',
      `/messenger/search?q=${encodeURIComponent(query)}&limit=${limit}`,
    ),

  /** Місця поруч за назвою — лише телефон (`WebMessengerPlaces`); 409 `no_fix` — телефон не знає, де ви. */
  searchPlaces: (query: string) =>
    request<{ places?: unknown[] }>('GET', `/messenger/places?q=${encodeURIComponent(query)}`),

  listMessages: (conversationId: string, afterSeq = 0) =>
    request<NodeMessage[]>(
      'GET',
      `/messenger/conversations/${conversationId}/messages?after_seq=${afterSeq}`,
    ),

  /**
   * Що змінилось у розмові з останньої повної видачі. `full` — телефон опори
   * не має, і розмову треба взяти цілком.
   */
  messagesChanged: (conversationId: string) =>
    request<{ upsert?: NodeMessage[]; remove?: string[]; full?: boolean }>(
      'GET',
      `/messenger/conversations/${conversationId}/messages?changed=1`,
    ),

  /** Відкладені листи телефона: лежать на апараті й ідуть будильником, а не вкладкою. */
  listScheduled: () => request<NodeScheduledLetter[]>('GET', '/messenger/scheduled'),
  createScheduled: (body: { conversation_id: string; text: string; due_at_ms: number; quiet?: boolean; repeat?: string; when_online?: boolean }) =>
    request<NodeScheduledLetter>('POST', '/messenger/scheduled', body),
  /** Перенести, змінити текст або повтор; відсутнє поле — не чіпати. */
  updateScheduled: (id: string, body: { text?: string; due_at_ms?: number; repeat?: string; when_online?: boolean }) =>
    request<NodeScheduledLetter>('PATCH', `/messenger/scheduled/${id}`, body),
  cancelScheduled: (id: string) => request<NodeScheduledLetter>('DELETE', `/messenger/scheduled/${id}`),
  sendScheduledNow: (id: string) => request<NodeScheduledLetter>('POST', `/messenger/scheduled/${id}/send`),

  /** Строк зникнення розмови: 0 · 86400 · 604800 · 2592000 секунд. Автор — для службового рядка ПК. */
  setTimer: (conversationId: string, seconds: number, author?: { author_id: string; author_name: string }) =>
    request<{ disappear_s: number }>('POST', `/messenger/conversations/${conversationId}/timer`, { seconds, ...(author ?? {}) }),

  appendMessage: (
    conversationId: string,
    body: {
      client_id: string;
      author_id: string;
      author_name: string;
      kind?: string;
      body?: string | null;
      transport?: string | null;
      reply_to_id?: string | null;
      fw?: { name: string; id: string };
      qt?: string;
      /** «Без звуку»: шторка адресата кладе лист мовчки. */
      quiet?: boolean;
      /** Хто названий через @ у гілці: їхні ідентифікатори їдуть у листі (`mn`). */
      mentions?: string[];
      /** Канал простору і, для замкненого, його читачі — єдині адресати. */
      channel_id?: string;
      to?: string[];
    },
  ) => request<NodeMessage>('POST', `/messenger/conversations/${conversationId}/messages`, body),

  /** Події простору з журналу гілки; `actor` — підписант листа. Лише телефон. */
  spaceEvents: (spaceId: string) =>
    request<{ events: Array<{ actor: string; at: number; event: string }>; members: string[] }>(
      'GET', `/messenger/spaces/${encodeURIComponent(spaceId)}/events`,
    ),

  postSpaceEvent: (spaceId: string, event: unknown) =>
    request<{ sent: boolean }>('POST', `/messenger/spaces/${encodeURIComponent(spaceId)}/events`, { event }),

  /** Пробуємо проштовхнути чергу зараз; повертає, скільки доставлено. */
  flushQueue: () =>
    request<{ delivered: number; blobs?: number }>('POST', '/messenger/queue/flush'),

  /**
   * Кладе ВЖЕ зашифрований браузером файл — на власний вузол або, всередині
   * телефона, у власний каталог застосунку.
   *
   * До вузла — XHR, а не fetch, з однієї причини: тільки він каже, скільки
   * байтів справді пішло. Смуга, намальована таймером, була б вигадкою — а тут
   * її показують людині, яка чекає на своє фото.
   */
  uploadFile: (
    conversationId: string,
    ciphertext: Blob,
    onProgress?: (percent: number) => void,
  ) => {
    // Усередині телефона вузла немає: байти лягають у власний каталог
    // застосунку, а везе їх меш — тими самими шматками, підписом і чергою,
    // що й слова.
    //
    // Смуга тут доходить до 100 одразу, і це не вигадка: на телефоні
    // «завантаження» — це запис на диск, і він справді закінчився. Дорога
    // між телефонами показується окремо, через attachment_state, бо це
    // окрема подія й закінчується вона в інший час.
    const port = phonePort();
    if (port) {
      // Байти — `ArrayBuffer`-ом через порт, без base64 і без JSON.
      return ciphertext.arrayBuffer().then(async (buf) => {
        const res = (await port.canSendBytes())
          ? await port.sendBytes(conversationId, buf)
          : await port.request(
              'POST',
              '/messenger/files',
              JSON.stringify({ conversation_id: conversationId, data_b64: b64Of(buf) }),
            );
        if (!res.ok) throw new ApiError(res.status, res.code ?? 'UNKNOWN', res.detail ?? 'Unknown error');
        onProgress?.(100);
        return res.data as NodeBlob;
      });
    }
    const bridge = phoneBridge();
    if (bridge) {
      return ciphertext.arrayBuffer().then((buf) => {
        const raw = bridge.call(
          'POST',
          '/messenger/files',
          JSON.stringify({ conversation_id: conversationId, data_b64: b64Of(buf) }),
        );
        const res = JSON.parse(raw) as {
          ok: boolean;
          status: number;
          data?: NodeBlob;
          code?: string;
          detail?: string;
        };
        if (!res.ok) throw new ApiError(res.status, res.code ?? 'UNKNOWN', res.detail ?? 'Unknown error');
        onProgress?.(100);
        return res.data as NodeBlob;
      });
    }
    return new Promise<NodeBlob>((resolve, reject) => {
      const form = new FormData();
      form.append('conversation_id', conversationId);
      form.append('blob', ciphertext, 'blob');

      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/messenger/files/upload`);
      const token = readToken();
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      xhr.withCredentials = true;

      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && onProgress) {
          onProgress(Math.round((e.loaded / e.total) * 100));
        }
      };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.(100);
          resolve(JSON.parse(xhr.responseText) as NodeBlob);
        } else {
          reject(new Error(tNow('msgApi.uploadRejected', { status: xhr.status })));
        }
      };
      xhr.onerror = () => reject(new Error(tNow('msgApi.linkLost')));
      xhr.send(form);
    });
  },

  /** Carrier v2: запис передачі на власному вузлі (розмір, n, теги; секрету там нема). */
  carrierCreate: (size: number, n: number, tags: string[]) =>
    request<NodeCarrierRecord>('POST', '/messenger/files/carrier', { size, n, tags }),

  carrierDropChunk: (transferId: string, i: number) =>
    request<NodeCarrierRecord>('DELETE', `/messenger/files/carrier/${encodeURIComponent(transferId)}/chunks/${i}`),

  carrierPutChunk: async (transferId: string, i: number, bytes: Uint8Array): Promise<NodeCarrierRecord> => {
    const token = readToken();
    const res = await fetch(`${BASE}/messenger/files/carrier/${encodeURIComponent(transferId)}/chunks/${i}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      credentials: 'include',
      body: bytes as BodyInit,
    });
    if (!res.ok) throw new ApiError(res.status, 'UNKNOWN', tNow('msgApi.chunkRejected', { i, status: res.status }));
    return (await res.json()) as NodeCarrierRecord;
  },

  carrierGetChunk: async (transferId: string, i: number): Promise<Uint8Array> => {
    const token = readToken();
    const res = await fetch(`${BASE}/messenger/files/carrier/${encodeURIComponent(transferId)}/chunks/${i}`, {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      credentials: 'include',
    });
    if (!res.ok) throw new ApiError(res.status, 'UNKNOWN', tNow('msgApi.chunkMissing', { i, status: res.status }));
    return new Uint8Array(await res.arrayBuffer());
  },

  /** Сирий шифротекст вкладення. Розшифровує браузер — див. messengerMedia.ts. */
  fileUrl: (blobId: string) =>
    phoneBridge() ? `/webmsgr/_files/${blobId}` : `${BASE}/messenger/files/${blobId}`,

  /** stored | queued | parked | sent | missing — стан ПЕРЕВЕЗЕННЯ, не вмісту. */
  blobStatus: (blobId: string) =>
    request<NodeBlob>('GET', `/messenger/files/${blobId}/status`),

  /**
   * «Запитати ще раз»: спершу вузол шукає байти в хмарі, а якщо їх там немає —
   * просить вузол відправника надіслати блоб знову.
   */
  requestBlob: (blobId: string) =>
    request<NodeBlob>('POST', `/messenger/files/${blobId}/request`),

  /** Отримує список користувачів для швидкого пошуку та звʼязку за ніком (@username) */
  listDirectoryUsers: (query?: string) =>
    request<
      Array<{
        id: string;
        username: string;
        display_name: string;
        role: string;
        avatar?: string;
        is_online: boolean;
        /** Коротка форма ключа (PH2): показуємо поруч з іменем — це та людина. */
        compact?: string;
      }>
    >('GET', `/messenger/directory/users${query ? `?query=${encodeURIComponent(query)}` : ''}`),

  /** Розпочинає реальну бесіду з користувачем за його ніком (@username) */
  startChatByUsername: (username: string, circle: string = 'friends') =>
    request<NodeConversation>('POST', '/messenger/directory/start-chat', { username, circle }).then((c) => {
      if (c?.id) chatKeep.add(c.id);
      return c;
    }),

  /**
   * МОЯ згода бути в довіднику. Три ручки, які апарат уже вміє, а веб досі не
   * питав: `WebMessengerDirectory.route` віддає `me` з 12.09, і жоден рядок
   * фронтенду його не викликав — написане, але не викликане.
   *
   * Підписує ЗАВЖДИ апарат, не браузер: приватний ключ лежить там, і саме
   * тому веб тут нічого не шифрує й не підписує — він лише передає намір.
   * Там, де моста немає (браузер на столі проти вузла), ці ручки дають
   * відмову, і екран мусить сказати правду, а не малювати кнопку.
   */
  directoryMe: () =>
    request<{ handle: string | null; published: boolean; until?: string }>(
      'GET',
      '/messenger/directory/me',
    ),

  directoryLookup: (handle: string) =>
    request<{ handle: string; available: boolean; taken?: boolean; mine?: boolean }>(
      'GET',
      `/messenger/directory/lookup?handle=${encodeURIComponent(handle)}`,
    ),

  directoryPublish: (handle: string) =>
    request<{ handle: string; until?: string }>('POST', '/messenger/directory/me', { handle }),

  directoryWithdraw: () => request<unknown>('DELETE', '/messenger/directory/me'),

  /**
   * «Додати свій пристрій» на телефоні: скан QR з екрана ПК іде в міст, і той
   * спарюється у фоні тією самою дорогою, що й рідний екран (PcPairing).
   */
  pairOwnDevice: (qr: string) => request<OwnPairState>('POST', '/messenger/pair', { qr }),

  ownPairState: () => request<OwnPairState>('GET', '/messenger/pair'),

  /** «Це мій пристрій» — ставить лише власник; дріт цього не міняє. */
  markMine: (peerId: string, mine: boolean) =>
    request<NodeConversation>('POST', '/messenger/mine', { peer_id: peerId, mine }),
};

const timeLabel = (iso: string): string => {
  const d = new Date(iso.endsWith('Z') ? iso : `${iso}Z`);
  return Number.isNaN(d.getTime())
    ? ''
    : clockLabel(d);
};

const RICH_FIELD: Record<string, string> = {
  table: 'tableData', chart: 'chartData', 'task-list': 'taskListData',
  poll: 'pollData', event: 'eventData', 'split-bill': 'splitBillData',
  code: 'codeData', image: 'imageData', file: 'fileData', voice: 'voiceData',
  location: 'locationData', 'multi-quote': 'multiQuoteData',
};

/** Опис вкладення з тіла кадру. Імена полів на дроті — як у backend. */
function mediaFromBody(raw: Record<string, unknown>): SecureMedia {
  return {
    name: String(raw.name ?? tNow('msgApi.attachment')),
    size: Number(raw.size ?? 0),
    mime: String(raw.mime ?? 'application/octet-stream'),
    sha256: String(raw.sha256 ?? ''),
    blobId: String(raw.blob_id ?? ''),
    keyHex: String(raw.key_hex ?? ''),
    nonceHex: String(raw.nonce_hex ?? ''),
    ...dimsOf(raw),
    ...(isMicroThumb(raw.thumb) ? { thumb: raw.thumb } : {}),
    ...(isCarrierManifest(raw.carrier) ? { carrier: raw.carrier } : {}),
  };
}

/**
 * Розміри з тіла листа (`width`/`height`, цілі > 0). Будь-що інше — немає
 * розмірів, а не нуль: нульова сторона дала б бульбашку-щілину.
 */
export function dimsOf(raw: Record<string, unknown>): { width?: number; height?: number } {
  const w = Number(raw.width);
  const h = Number(raw.height);
  if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0 || w > 65535 || h > 65535) return {};
  return { width: Math.round(w), height: Math.round(h) };
}

/**
 * Єдине місце, де стан вузла стає галочкою.
 *
 * Раніше правило жило у трьох місцях (розбір стрічки, відповідь на надсилання,
 * ехо по WebSocket) і в кожному розходилось — саме звідти бралося вікно, коли
 * лист у черзі виглядав доставленим.
 *
 * Галочка означає рівно одне: вузол-адресат узяв кадр. Вкладення важить
 * більше за кадр: поки байти не в людини ('queued' — у нас, 'parked' — у
 * хмарі, 'missing' — загублені), лист не надісланий, хоч би що казав
 * delivery_state.
 */
export function deliveryStatus(
  deliveryState?: string | null,
  attachmentState?: string | null,
): Message['status'] {
  if (attachmentState && attachmentState !== 'sent' && attachmentState !== 'stored') {
    return 'queued';
  }
  if (deliveryState === 'queued') return 'queued';
  if (deliveryState === 'failed') return 'failed';
  if (deliveryState === 'sending') return 'sending';
  return 'sent';
}

export function messageFromNode(row: NodeMessage, selfId: string, peerNodeId?: string): Message {
  // Груповий рядок упізнається за числами віяра: міст кладе `fan_total` лише
  // туди, і саме тому ознака тут — поле дроту, а не здогад про розмову.
  const isGroupRow = typeof row.fan_total === 'number';
  // Показова стрічка везе складний вміст як JSON — розбираємо його тут, щоб
  // таблиці, графіки й реакції жили тим самим шляхом, що й звичайний текст.
  let rich: Record<string, unknown> = {};
  // Рід, яким рядок віддається стрічці, коли він НЕ той, що назвав вузол.
  // Одна причина на сьогодні: картка, вміст якої не прочитався. Показати її
  // власним родом означало б намалювати рамку з назвою й порожнечею всередині.
  let kindOverride: string | null = null;
  if (row.kind === 'timer') {
    // Службовий рядок: «повідомлення зникають через 1 день». Не бульбашка.
    const seconds = Number(row.timer_s ?? row.body);
    rich = { timerSeconds: Number.isFinite(seconds) && seconds >= 0 ? seconds : 0 };
    kindOverride = 'system';
  } else if (row.kind === 'geo:point') {
    // Точка розбирається окремо: у неї свої числа і свій час виміру, і
    // непрочитане тіло краще лишити порожнім, ніж намалювати пів-точки.
    const point = parseGeoPoint(row.body);
    rich = point ? { geoPoint: point } : {};
  } else if (row.kind === 'geo:live') {
    const liveLocation = parseLiveLocation(row.body);
    rich = liveLocation ? { liveLocation } : {};
  } else if (row.kind === 'route' || row.kind === 'place' || row.kind === 'broken') {
    // Маршрут, місце й нечитаний кадр міст віддає РОЗІБРАНИМИ, пласкими
    // полями листа. Тіла в них немає — і саме тому їх більше нема чим
    // намалювати наосліп. Неповні поля не стають половиною картки: вони
    // стають чесним «не вдалося прочитати».
    const flat = row as unknown as Record<string, unknown>;
    if (row.kind === 'route') {
      const route = routeFromRow(flat);
      rich = route ? { route } : { broken: brokenFromRow(flat, 'route') };
    } else if (row.kind === 'place') {
      const place = placeFromRow(flat);
      rich = place ? { place } : { broken: brokenFromRow(flat, 'place') };
    } else {
      // Підпис під вкладенням, яке відкривається лише на телефоні, — слова
      // людини, і вони не губляться разом із байтами.
      const caption = typeof flat.text === 'string' ? flat.text.trim() : '';
      rich = { broken: brokenFromRow(flat), ...(caption ? { text: caption } : {}) };
    }
  } else if (isCardKind(row.kind)) {
    // Картка композера. Тіло приходить ДВОМА шляхами і жоден не можна
    // пропустити: міст телефона віддає його вже розібраним, окремим полем
    // поруч із `kind` (`cardInto` у `WebMessengerBridge`, `body` при цьому
    // порожній), а вузол-хост — тілом. Без цієї гілки рядок повертався з
    // `type: 'widget:kanban'` і БЕЗ `kanbanData`: стрічка малює віджет лише
    // коли бачить своє поле, тож картка ставала порожньою бульбашкою з
    // годинником — доїхала й не видно.
    //
    // Не розібралось — `broken`, а не рід із порожнечею: рамка з написом
    // «Kanban» і нічим усередині є виглядом спроможності без спроможності.
    const card = cardFromRow(row.kind, row.body, row as unknown as Record<string, unknown>);
    if (card) {
      rich = card as Record<string, unknown>;
    } else {
      rich = { broken: { of: row.kind, reason: tNow('msgApi.cardUnreadable') } };
      kindOverride = 'broken';
    }
  } else if (row.kind !== 'text' && row.body) {
    try {
      const parsed = JSON.parse(row.body);
      // Вкладення з наскрізним ключем упізнається за самим описом, а не за
      // типом: показова стрічка теж возить kind='image', але з готовим url.
      if (parsed && parsed.blob_id && (parsed.key_hex || isCarrierManifest(parsed.carrier))) {
        // caption — необовʼязковий: тіла, надіслані до нього, читаються тим
        // самим розбором і просто лишаються без підпису.
        const caption = typeof parsed.caption === 'string' ? parsed.caption.trim() : '';
        const batchId = typeof parsed.batch_id === 'string' ? parsed.batch_id : '';
        const batchIndex = Number(parsed.batch_index);
        const batchTotal = Number(parsed.batch_total);
        const mediaBatch = batchId && Number.isInteger(batchIndex) && Number.isInteger(batchTotal) && batchIndex >= 0 && batchTotal > 1 && batchIndex < batchTotal
          ? { id: batchId, index: batchIndex, total: batchTotal }
          : undefined;
        const media = mediaFromBody(parsed);
        // Розмова з асистентом везе голосове й кружок родом 'file' — власник
        // 25.09 бачив «video_note_….webm» з кнопкою завантаження замість
        // плеєра. Рід беремо з імені, яке ставить сам записувач.
        const fileName = typeof parsed.name === 'string' ? parsed.name.toLowerCase() : '';
        const declared = parsed.kind === 'voice' || parsed.kind === 'video-note' ? parsed.kind : null;
        const kind = declared && (row.kind === 'file' || row.kind === declared)
          ? declared
          : row.kind === 'file' && /^video[_-]note/.test(fileName)
          ? 'video-note'
          : row.kind === 'file' && /^voice/.test(fileName)
            ? 'voice'
            : row.kind;
        if (kind !== row.kind) kindOverride = kind;
        const voiceData = kind === 'voice' ? {
          duration: Number(parsed.duration ?? 0),
          waveform: Array.isArray(parsed.waveform) ? parsed.waveform.filter((v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)) : [],
          transcript: typeof parsed.transcript === 'string' ? parsed.transcript : undefined,
          media,
        } : undefined;
        const videoNoteData = kind === 'video-note' ? {
          duration: Number(parsed.duration ?? 0),
          media,
        } : undefined;
        rich = { media, ...(voiceData ? { voiceData } : {}), ...(videoNoteData ? { videoNoteData } : {}), ...(mediaBatch ? { mediaBatch } : {}), ...(caption ? { text: caption } : {}) };
      } else if (parsed && typeof parsed === 'object' && 'carrier' in parsed) {
        rich = { broken: { of: row.kind, reason: tNow('mediaC6.carrierUnreadable') } };
        kindOverride = 'broken';
      } else {
        const field = RICH_FIELD[row.kind];
        rich = field && parsed && !parsed.__msg ? { [field]: parsed } : parsed.__msg || {};
      }
    } catch {
      rich = {};
    }
  }
  return {
    ...rich,
    id: row.id,
    senderId: row.author_id || (row as any).senderId,
    senderName: row.author_name || (row as any).senderName,
    senderAvatar: (row as any).senderAvatar || (row as any).author_avatar || '',
    timestamp: timeLabel(row.sent_at),
    sentAt: row.sent_at,
    type: ((kindOverride ?? row.kind) as Message['type']) || 'text',
    text: row.kind === 'text' ? row.body ?? undefined : (rich.text as string | undefined),
    ...(linkPreviewOf(row) ? { linkPreview: linkPreviewOf(row) } : {}),
    ...(Array.isArray(row.mentions) ? { mentions: row.mentions.filter((x) => typeof x === 'string') } : {}),
    ...(typeof row.pinned === 'boolean' ? { isPinned: row.pinned, pinShared: true } : {}),
    isSelf: peerNodeId ? row.author_id !== peerNodeId : row.author_id === selfId,
    // Стан доставки — з бази вузла, тож галочки переживають перезавантаження.
    //
    // У групі співрозмовника немає, тож `peerNodeId` там порожній і галочка
    // не малювалась узагалі. Ознака групового рядка — числа віяра: міст кладе
    // їх лише туди. Стан згорнутого рядка міст уже склав як НАЙМЕНШ
    // просунутий з усіх копій, тож «надіслано», поки одна лежить у черзі,
    // звідси не візьметься.
    // Вузол ПК не кладе чисел віяра, але стан групового рядка складає сам;
    // 'local' — лист нікуди не йшов (Збережене, помічник), галочки немає.
    status:
      (peerNodeId
        ? row.author_id !== peerNodeId
        : row.author_id === selfId && (isGroupRow || (!!row.delivery_state && row.delivery_state !== 'local')))
        ? deliveryStatus(row.delivery_state, row.attachment_state)
        : undefined,
    // Лише про власні рядки: чужу репліку цей телефон бачить однією копією —
    // своєю, — тож числа під нею були б зібрані з одного свідчення.
    fanDelivered:
      isGroupRow && row.author_id === selfId ? row.fan_delivered : undefined,
    fanTotal: isGroupRow && row.author_id === selfId ? row.fan_total : undefined,
    fanRead: isGroupRow && row.author_id === selfId ? row.fan_read : undefined,
    readAt: row.read_at ?? undefined,
    // Підстава під підписом бульбашки: «очікує передачі» проти «у дорозі
    // через хмару» — різні речі, і крапка одного кольору їх не розрізняє.
    attachmentState: row.attachment_state ?? undefined,
    relayState: row.relay_state ?? undefined,
    intentId: row.intent_id ?? undefined,
    ...(typeof row.fail_reason === 'string' && row.fail_reason ? { failReason: row.fail_reason } : {}),
    ...(row.pay_draft?.peer_id
      ? { payDraft: { peerId: row.pay_draft.peer_id, token: row.pay_draft.token, amount: row.pay_draft.amount ?? '', sent: Boolean(row.pay_draft.sent) } }
      : {}),
    ...(row.commitment_card
      ? {
          commitmentCard: {
            kind: row.commitment_card.kind,
            ...(row.commitment_card.commitment_id ? { commitmentId: row.commitment_card.commitment_id } : {}),
            ...(row.commitment_card.pending_id ? { pendingId: row.commitment_card.pending_id } : {}),
            person: row.commitment_card.person ?? '',
            ...(row.commitment_card.peer_id ? { peerId: row.commitment_card.peer_id } : {}),
            what: row.commitment_card.what ?? '',
            ...(typeof row.commitment_card.due_ms === 'number' ? { dueMs: row.commitment_card.due_ms } : {}),
            ...(Array.isArray(row.commitment_card.candidates)
              ? {
                  candidates: row.commitment_card.candidates.map((c) => ({
                    peerId: c.peer_id,
                    name: c.name,
                    ...(c.hint ? { hint: c.hint } : {}),
                  })),
                }
              : {}),
            state: row.commitment_card.state ?? 'open',
          },
        }
      : {}),
    isDeleted: Boolean(row.deleted_at),
    isEdited: Boolean(row.edited_at),
    transport: (row.transport as Message['transport']) ?? undefined,
    systemAct: (row.system_act as Message['systemAct']) ?? undefined,
    systemActTarget: row.system_act_target ?? undefined,
    reactions: reactionsFromNode(row.reactions),
    payment: readPayment(row.payment),
    seal: readSeal(row.seal),
    // Позначки стану на картці зустрічі чи рахунку (`PH-CST:`), згорнуті мостом.
    cardMarks: parseCardMarks((row as unknown as { card_marks?: unknown }).card_marks),
    ...(row.channel_id ? { channelId: row.channel_id } : {}),
    ...(forwardOfRow(row) ? { forwardFrom: forwardOfRow(row) } : {}),
  };
}

export { QUOTE_MAX, forwardOfRow, quoteOfRow, quoteForWire } from './wireCarry';

/**
 * Слова дороги, які взагалі бувають. Решта — «не знаю», а не «доїхало».
 *
 * Словник закритий тією ж причиною, що й словник стану листа
 * (`WebMessengerStateVocabularyTest` у companion): гілка «інакше», яка тихо
 * каже «все гаразд», перетворює невідоме слово на неправду. Тут невідоме
 * слово дає `failed` — найгірший із можливих станів, а не найкращий: смайл,
 * про долю якого ми не знаємо нічого, не має права виглядати доставленим.
 *
 * 21.09: `delivered` тут НЕ БУЛО, і саме це власник називав «реакції не
 * працюють». Міст карбує це поле через `state()` (WebMessengerBridge.kt), а
 * той на `PeerMessage.State.DELIVERED` каже рівно `"delivered"`. Слово
 * падало в гілку «інакше» — тобто КОЖНА доїхала позначка малювалась
 * червоним «Не поїхало — торкніться ще раз», і переживала вихід із розмови,
 * бо журнал щоразу казав те саме слово. Вигляд `delivered` у ChatArea був
 * написаний і недосяжний.
 *
 * Словник мусить бути ТОЧНО тим, що вміє сказати `state()`; за цим стежить
 * `WebMessengerReactionReachTest` у companion — дзеркалом по літералах дроту,
 * а не по мініфікованих іменах.
 */
const REACTION_ROADS = new Set(['sending', 'queued', 'failed', 'delivered']);

/**
 * Смайли з дроту в мову екрана.
 *
 * `count` не переноситься з дроту, а рахується зі списку авторів: два
 * незалежні лічильники того самого розійшлися б на першому ж кадрі, що
 * приїхав двічі. Міст рахує так само (`reactionsOf`), тож це не друге
 * правило — це відмова везти похідне число.
 *
 * Порожній масив і відсутнє поле — РІЗНІ речі: перше означає «позначок
 * немає», друге — «вузол про них не казав» (старий міст). Тому `undefined`
 * тут не стає `[]`: інакше рядок, про який ми нічого не знаємо, стирав би
 * намальоване.
 */
/**
 * Відповідь із дроту — у цитату над бульбашкою.
 *
 * Міст віддає `reply_to_id` КОЖНОМУ рядкові-відповіді (і власному, і
 * чужому — підписаний `replyToId` доїжджає дротом, 24.09 доведено на A52 в
 * обидва боки), а `messageFromNode` його не читав: цитата жила лише в
 * памʼяті вкладки, що відповідала, і зникала з першим же перечитуванням
 * стрічки. У співрозмовника її не було ніколи.
 *
 * Шукаємо ціль у тій самій стрічці. Коли її там немає (стерли, або вона
 * старша за вікно), цитата ВСЕ ОДНО ставиться — з чесним «лист поза
 * стрічкою»: мовчки загубити, що це відповідь, гірше, ніж сказати, що
 * оригіналу під рукою нема.
 */
export function withReplies(
  rows: NodeMessage[],
  msgs: Message[],
  names: { self: string; peer: string },
): Message[] {
  const byId = new Map(msgs.map((m) => [m.id, m]));
  return msgs.map((m, i) => {
    const target = rows[i]?.reply_to_id;
    if (!target || m.replyTo) return m;
    const quote = quoteOfRow(rows[i]);
    const orig = byId.get(target);
    if (!orig) {
      return {
        ...m,
        replyTo: { id: target, senderName: '', text: tNow('msgApi.offFeed'), ...(quote ? { quoteSelectedText: quote } : {}) },
      };
    }
    return {
      ...m,
      replyTo: {
        id: orig.id,
        senderName: orig.isSelf ? names.self : orig.senderName || names.peer,
        text: orig.text ?? '',
        type: orig.type,
        ...(quote ? { quoteSelectedText: quote } : {}),
      },
    };
  });
}

export function reactionsFromNode(raw: NodeMessage['reactions']): Reaction[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  return raw
    .filter((r) => r && typeof r.emoji === 'string' && r.emoji.length > 0)
    .map((r) => {
      const users = Array.isArray(r.users) ? r.users.filter((u): u is string => typeof u === 'string') : [];
      const names = Array.isArray(r.names) ? r.names.filter((n): n is string => typeof n === 'string') : [];
      const mine = typeof r.mine === 'string' && REACTION_ROADS.has(r.mine)
        ? (r.mine as Reaction['mine'])
        : r.mine === null || r.mine === undefined
          ? undefined
          : 'failed';
      return {
        emoji: r.emoji,
        count: users.length,
        users,
        ...(names.length === users.length && names.length > 0 ? { names } : {}),
        ...(mine ? { mine } : {}),
      };
    })
    .filter((r) => r.count > 0);
}

/**
 * Числа для рядка переліку — з полів РОЗМОВИ, коли міст їх дав.
 *
 * Немає полів — немає й чисел: рядок скаже «Маршрут» і не домалює кілометрів,
 * яких нам ніхто не називав.
 */
function lastCardFromRow(row: NodeConversation): ChatLastCard | undefined {
  const num = (raw: unknown): number | undefined =>
    typeof raw === 'number' && Number.isFinite(raw) ? raw : undefined;
  if (row.last_kind === 'route') {
    return {
      kind: 'route',
      ...(typeof row.last_route_profile === 'string'
        ? { profile: row.last_route_profile.trim() }
        : {}),
      ...(num(row.last_route_distance_m) !== undefined
        ? { distanceM: num(row.last_route_distance_m) }
        : {}),
      ...(num(row.last_route_duration_s) !== undefined
        ? { durationS: num(row.last_route_duration_s) }
        : {}),
    };
  }
  if (row.last_kind === 'place') {
    const lat = num(row.last_place_lat);
    const lon = num(row.last_place_lon);
    return { kind: 'place', ...(lat !== undefined ? { lat } : {}), ...(lon !== undefined ? { lon } : {}) };
  }
  const title = typeof row.last_card_title === 'string' ? row.last_card_title.trim() : '';
  const total = num(row.last_card_total);
  if (title || total !== undefined) {
    return {
      kind: 'card',
      ...(title ? { title } : {}),
      ...(total !== undefined ? { total } : {}),
      ...(typeof row.last_card_currency === 'string' ? { currency: row.last_card_currency } : {}),
    };
  }
  return undefined;
}

/**
 * Закріплений рядок із дроту в мову екрана.
 *
 * Окрема функція, а не `row.pinned_message ?? null`, бо на дроті ключ
 * `author_id`, а в `Chat` — `authorId`, і мовчазне присвоєння одного другому
 * дало б поле, яке ІСНУЄ і завжди `undefined`: закріплене було б, автора не
 * було б, а підпис «закріпив …» показував би порожнечу.
 */
/** Налаштування з рядка вузла. Немає поля — стара збірка, налаштувань не знає. */
export function settingsFromNode(row: Pick<NodeConversation, 'settings'>): GroupSettings | undefined {
  const s = row.settings;
  if (!s || typeof s !== 'object') return undefined;
  const slow = Number(s.slow);
  return {
    kind: s.kind === 'channel' || s.kind === 'space' ? s.kind : 'group',
    slow: Number.isFinite(slow) && slow > 0 ? slow : 0,
    hist: s.hist !== 'off',
    ask: s.ask === 'on',
    link: typeof s.link === 'string' ? s.link : '',
    talk: s.talk !== 'off',
    sign: s.sign === 'on',
  };
}

export function pinFromNode(row: NodeConversation): Chat['pinnedMessage'] {
  const p = row.pinned_message;
  if (!p) return null;
  return { id: p.id, authorId: p.author_id, author: p.author, at: p.at, text: p.text };
}

export function liveRoomFromNode(row: NodeConversation): Chat['liveRoom'] {
  const r = row.live_room;
  if (!r || !r.call_id || !Array.isArray(r.faces)) return null;
  if (r.faces.length === 0 && !r.mine) return null;
  return { callId: r.call_id, startedAt: r.started_at, faces: r.faces.filter((f) => typeof f === 'string'), mine: r.mine === true };
}

const parsePlanTime = (iso: unknown): number | null => {
  if (typeof iso !== 'string' || !iso) return null;
  const ms = Date.parse(iso);
  return Number.isFinite(ms) ? ms : null;
};

export function planFromNode(row: Pick<NodeConversation, 'plan'>): ChatPlan | null {
  const p = row?.plan;
  if (!p || typeof p !== 'object' || Array.isArray(p)) return null;
  if (typeof p.meetup_id !== 'string' || !p.meetup_id) return null;
  return {
    meetupId: p.meetup_id,
    venue: typeof p.venue === 'string' && p.venue ? p.venue : null,
    organizer: typeof p.organizer === 'string' ? p.organizer : '',
    at: parsePlanTime(p.at),
    expiresAt: parsePlanTime(p.expires_at),
    keptBy: Array.isArray(p.kept_by) ? p.kept_by.filter((x): x is string => typeof x === 'string' && x.length > 0) : [],
    cancelled: p.cancelled === true,
  };
}

/**
 * Картка посилання з рядка вузла. Мініатюра — лише data-URI JPEG: сторінка не
 * тягне нічого з мережі сама, інакше сайт дізнавався б, хто й коли прочитав.
 */
export function linkPreviewOf(row: Pick<NodeMessage, 'link_preview' | 'kind'>): Message['linkPreview'] | undefined {
  const p = row.link_preview;
  if (!p || row.kind !== 'text' || typeof p.url !== 'string' || !p.url.startsWith('https://') || !p.title) return undefined;
  const image = typeof p.image === 'string' && p.image.startsWith('data:image/jpeg;base64,') ? p.image : undefined;
  return {
    url: p.url,
    title: p.title,
    ...(p.description ? { description: p.description } : {}),
    ...(p.site ? { site: p.site } : {}),
    ...(image ? { image } : {}),
  };
}

export function chatFromNode(raw: NodeConversation): Chat {
  // Помічник приходить із мосту з декором і вигаданим листом — див. assistantChat.
  const row = normalizeAssistantRow(raw);
  return {
    contactVerified: row.contact_verified ?? null,
    contactKeyChanged: row.contact_key_changed ?? false,
    contactKeyChangedAt: row.contact_key_changed_at ?? undefined,
    contactKeyHold: row.contact_key_hold === true,
    disappearSeconds: row.disappear_s ?? 0,
    id: row.id,
    title: row.id === SAVED_NOTES_ID ? tNow('chatList.saved') : row.title,
    handle: row.handle ?? undefined,
    mine: row.mine === true,
    addedAt: row.kind === 'dm' ? row.created_at || undefined : undefined,
    avatar: row.avatar ?? '',
    type: (row.kind as Chat['type']) || 'dm',
    circle: (row.circle as Chat['circle']) || 'all',
    circles: Array.isArray(row.circles) ? row.circles.filter((c): c is string => typeof c === 'string') : undefined,
    pinned: row.pinned,
    isDemo: row.is_demo === true,
    peerNodeId: row.peer_node_id ?? undefined,
    unreadCount: row.unread_count ?? 0,
    lastKind: row.last_kind ?? undefined,
    lastCard: lastCardFromRow(row),
    lastSnippet: row.last_snippet ?? undefined,
    lastAuthor: row.last_author ?? undefined,
    lastAt: row.last_at ?? undefined,
    // `undefined` і `null` тут різні: перше — «вузол не каже», друге — «не писали».
    lastSelfAt: row.last_self_at,
    lastPeerAt: row.last_peer_at,
    letters30d: typeof row.letters_30d === 'number' ? row.letters_30d : undefined,
    description: row.description ?? undefined,
    standing: row.standing === 'in' || row.standing === 'left' || row.standing === 'removed' ? row.standing : undefined,
    pinnedMessage: pinFromNode(row),
    groupSettings: settingsFromNode(row),
    liveRoom: liveRoomFromNode(row),
    plan: planFromNode(row),
    messages: [],
  };
}
