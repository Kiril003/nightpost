import type { BillData, CardMarks, MeetupData } from './meetup';
import type { CarrierManifest } from '../services/carrier/seal';
/**
 * PHANTOM OS — Next-Gen Messenger Types & Contracts
 * Complete types for Chats, Messages, Rich Widgets, Multi-Sphere Personas,
 * власне сховище вузла і транспорт P2P / WebRTC.
 */

/**
 * Точка «я тут» із кадру geo:point. `atMs` — час ВИМІРУ на вузлі відправника,
 * не час доставки: на ньому тримається чесний вік (див. messengerGeo.ts).
 */
export interface GeoPoint {
  lat: number;
  lon: number;
  atMs: number;
  accuracyM?: number;
  label?: string;
}

/** Consent-based live location session. Updates are signed by a session id and expire. */
export interface LiveLocation {
  sessionId: string;
  point: GeoPoint;
  startedAtMs: number;
  expiresAtMs: number;
  revoked?: boolean;
}

/** Пара широта/довгота, як їх кладе міст: [lat, lon]. */
export interface LatLon {
  lat: number;
  lon: number;
}

/**
 * Маршрут, надісланий з мапи й РОЗІБРАНИЙ мостом.
 *
 * Формат дроту (`PH-RTE:`) живе в Kotlin і тільки там: міст читає його тими
 * самими `PeerLive.decodeRoute` / `PeerPayload.decodePlace`, що й нативний
 * екран, і кладе пласкі поля просто в лист. Веб їх лише малює — другий
 * розбирач розійшовся б із першим, і розходження побачив би власник.
 *
 * `profile` — назва з `RouteProfile` (FOOT/CAR/BIKE/TRANSIT); порожній рядок
 * законний і означає «не сказали». Числа — слова відправника, і картка
 * підписує їх як його слова.
 */
export interface SharedRoute {
  profile: string;
  distanceM: number;
  durationS: number;
  /** Строк листа (12 год у PeerLive). Немає — про вік не судимо. */
  expiresAtMs?: number;
  waypoints: LatLon[];
  /** Проріджена ламана. Порожня — картка без силуету, а не вигадана лінія. */
  shape: LatLon[];
}

/** Разове місце з розмови (`PH-LOC:`), розібране мостом. */
export interface SharedPlace {
  lat: number;
  lon: number;
  accuracyM?: number;
}

/**
 * Кадр, який хост упізнав за родом, але не зміг прочитати.
 *
 * Речення пише хост: він єдиний знає, що саме не зійшлося. Веб його показує
 * дослівно й нічого не домальовує.
 */
export interface BrokenFrame {
  /** Який рід рядок удавав: route | place | … */
  of: string;
  reason: string;
}

/**
 * Останній лист розмови, коли це кадр меша — пласкі поля РЯДКА РОЗМОВИ.
 *
 * Реєстр не має завантаженої стрічки: доки її не відкрили, чисел взяти нема
 * звідки. Тому міст кладе їх просто в рядок розмови (`last_route_distance_m`
 * тощо), а `last_snippet` для службових родів шле null — сире тіло в переліку
 * було другою половиною тієї самої вади 04.09.
 *
 * Полів може не бути: тоді рядок каже рід словом і не вигадує кілометрів.
 */
export interface ChatLastCard {
  kind: 'route' | 'place' | 'card';
  title?: string;
  total?: number;
  currency?: string;
  profile?: string;
  distanceM?: number;
  durationS?: number;
  lat?: number;
  lon?: number;
}

export type MessageType =
  | 'text'
  | 'voice'
  | 'video-note'
  | 'sticker'
  | 'location'
  /** Разова точка «я тут» — вимір із пристрою, не картка місця. */
  | 'geo:point'
  | 'geo:live'
  /** Маршрут, місце й нечитаний кадр — розібрані мостом (WebMessengerKinds). */
  | 'route'
  | 'place'
  | 'broken'
  | 'poll'
  | 'event'
  | 'split-bill'
  | 'image'
  | 'code'
  | 'table'
  | 'chart'
  | 'task-list'
  | 'multi-quote'
  | 'file'
  | 'system'
  | 'canvas-doc'
  | 'widget:canvas'
  | 'widget:kanban'
  | 'widget:voting'
  | 'widget:raci'
  | 'widget:timeline'
  | 'widget:code-runner'
  | 'widget:family-checklist'
  | 'widget:family-expense'
  | 'widget:study-flashcards'
  | 'snippet:async'
  | 'embed:mermaid'
  | 'embed:diff'
  | 'embed:svg'
  | 'webhook:event'
  | 'transit-arrivals'
  | 'widget:transit-arrivals'
  /** Зустріч і рахунок (збірка 18, `docs/scene/MEETUP_CONTRACT.md`): тіло — `meetupData` / `billData`. */
  | 'widget:meetup'
  | 'widget:bill'
  | 'widget:person'
  | 'widget:day'
  | 'widget:glance'
  | 'widget:money'
  | 'widget:gift';

/** 'all' — без кола. */
export type ChatCircle = 'all' | 'personal' | 'close' | 'family' | 'friends' | 'social' | 'work' | 'study' | 'travel' | 'communities' | 'saved';

export interface SmartFolder {
  id: string;
  name: string;
  emoji: string;
  color?: string; // Hex color or Tailwind accent
  vibe?: string; // e.g. "Focus Mode", "Deep Work", "Chill & Relax"
  isMuted?: boolean;
  isArchived?: boolean;
  chatIds: string[];
  isBuiltIn?: boolean;
  filterRules?: {
    includeCircles?: ChatCircle[];
    unreadOnly?: boolean;
    types?: ChatType[];
    keywords?: string[];
  };
}

export interface LocationDossier {
  title?: string;
  category?: string;
  address?: string;
  vibe?: string;
  bestHours?: string;
  crowdLevel?: string;
  atmosphere?: string[];
  transitTips?: string;
  recommendations?: string;
}

export interface LocationData {
  id?: string;
  name: string;
  category: string;
  address: string;
  distance: string;
  walkingTime: string;
  coords: { lat: number; lng: number };
  hours?: string;
  dossier?: LocationDossier;
}

export interface VoiceData {
  duration: number; // in seconds
  waveform: number[];
  audioUrl?: string;
  storageKey?: string; // ключ у сховищі вузла
  transcript?: string;
  summary?: string;
  media?: SecureMedia;
}

export interface VideoNoteData {
  duration: number; // in seconds
  videoUrl?: string;
  thumbnailUrl?: string;
  media?: SecureMedia;
}

export interface PollOption {
  id: string;
  text: string;
  votes: number;
  voters: string[];
}

export interface PollData {
  id?: string;
  question: string;
  options: PollOption[];
  totalVotes: number;
  closed?: boolean;
  allowMultiple?: boolean;
  userVotedOptionId?: string;
}

export interface EventAttendee {
  id: string;
  name: string;
  avatar: string;
  status: 'going' | 'maybe' | 'declined' | 'invited';
}

export interface EventData {
  id?: string;
  title: string;
  description?: string;
  date: string;
  time: string;
  endTime?: string;
  location: string;
  attendees: string[];
  attendeeDetails?: EventAttendee[];
  calendarType?: string;
  meetLink?: string;
  sourceMessageText?: string;
  maxCapacity?: number;
}

export interface SplitBillParticipant {
  id: string;
  name: string;
  avatar: string;
  share: number;
  paid: boolean;
}

export interface SplitBillData {
  id?: string;
  title: string;
  totalAmount: number;
  currency: string;
  participants: SplitBillParticipant[];
  paidCount: number;
}

export interface TableColumn {
  key: string;
  label: string;
  type?: 'text' | 'number' | 'badge' | 'date';
}

export interface TableData {
  /** Сталий ключ картки: за ним нова версія заступає попередню. */
  id?: string;
  title: string;
  description?: string;
  columns: TableColumn[];
  rows: Record<string, any>[];
  summaryRow?: Record<string, any>;
}

export interface ChartKey {
  key: string;
  label: string;
  color: string;
}

export interface ChartData {
  title: string;
  type: 'bar' | 'line' | 'area';
  data: Record<string, any>[];
  keys: ChartKey[];
  takeaway?: string;
}

export interface TaskItem {
  id: string;
  title: string;
  completed: boolean;
  assigneeName?: string;
  assigneeAvatar?: string;
  dueDate?: string;
}

export interface TaskListData {
  title: string;
  tasks: TaskItem[];
}

export interface QuotedMessageItem {
  id: string;
  senderName: string;
  senderAvatar?: string;
  timestamp: string;
  text: string;
}

export interface MultiQuoteData {
  title?: string;
  quotes: QuotedMessageItem[];
  synthesis?: {
    keyPoints: string[];
    conclusion: string;
    actionItem?: string;
  };
  commentary?: string;
}

export interface FileData {
  name: string;
  size: string;
  extension: string;
  url?: string;
  storageKey?: string; // ключ у сховищі вузла
  downloadCount?: number;
}

export interface CodeData {
  language: string;
  code: string;
  title?: string;
}

export interface ImageData {
  url: string;
  storageKey?: string; // ключ у сховищі вузла
  caption?: string;
}

/**
 * Вкладення, ключ до якого приїхав у тілі повідомлення.
 *
 * Байти лежать окремим блобом на вузлі й самі по собі нічого не варті:
 * відкрити їх можна лише з цим описом, а він їде запечатаним між вузлами.
 */
export interface SecureMedia {
  /** Імʼя, яке дала людина. Їде в тілі повідомлення — обидва вузли його бачать. */
  name: string;
  /** Розмір ВІДКРИТОГО файла, байтів. */
  size: number;
  mime: string;
  /** Відбиток ШИФРОТЕКСТУ. Звіряємо до розшифрування. */
  sha256: string;
  blobId: string;
  keyHex: string;
  nonceHex: string;
  /**
   * Розміри зображення/відео в пікселях — такі, як людина його бачить (після
   * EXIF-повороту й зменшення). На дроті — ключі `width`/`height` тіла листа.
   * Немає — бульбашка резервує 4:3 (docs/messenger/MEDIA_DIMENSIONS.md).
   */
  /** Мікромініатюра (data:image, ≤ 1.8 КБ) з тіла листа — розмите «проявлення». */
  thumb?: string;
  width?: number;
  height?: number;
  /** Carrier v2: опис шматків і секрет; тоді keyHex/nonceHex порожні, а blobId — id передачі. */
  carrier?: CarrierManifest;
}

/**
 * Дорога ВЛАСНОЇ позначки. Її немає в чужих — чужа приїхала, отже доїхала.
 *
 *  - `sending`   — кадр щойно віддано в сітку, відповіді ще немає;
 *  - `queued`    — співрозмовник офлайн, кадр лежить у черзі й поїде сам;
 *  - `failed`    — не поїхав, і сам не поїде: потрібен новий дотик;
 *  - `delivered` — доїхав.
 *
 * `delivered` довго тут не значилось: вважалось, що відсутність поля і є
 * «доїхало». Але слово карбує міст через `state()`, і на DELIVERED він каже
 * саме його — тобто кожна доставлена позначка падала в гілку «інакше» й
 * малювалась червоним «Не поїхало». Вигляд той самий, що в відсутнього
 * поля; слово є тому, що воно Є НА ДРОТІ.
 */
export type ReactionRoad = 'sending' | 'queued' | 'failed' | 'delivered';

export interface Reaction {
  emoji: string;
  count: number;
  /** Ідентифікатори вузлів. Людині їх не показують: для цього є `names`. */
  users: string[];
  /**
   * Імена тих самих авторів, у тому самому порядку — від вузла.
   *
   * До 18.09 підказка під смайлом малювала `users.join(', ')`, тобто ключі
   * вузлів дослівно: на питання «хто поставив» екран відповідав
   * `operator_me, 8f3ac1d0`. Вигадати імена у вкладці нічим — їх знає лише
   * той бік, тож порожній масив означає «вузол не назвав», а не «ніхто».
   */
  names?: string[];
  /**
   * Дорога МОЄЇ позначки, коли вона ще не доїхала. `undefined` — доїхала
   * (або позначка не моя).
   */
  mine?: ReactionRoad;
}

export interface QuotedMessageSnippet {
  id: string;
  senderName: string;
  senderAvatar?: string;
  text: string;
}

export interface ChannelLine {
  channelId: string;
  to: string[] | null;
  replyTo?: MessageReplyInfo;
}

export interface MessageReplyInfo {
  id: string;
  senderName: string;
  text: string;
  type?: MessageType;
  quoteSelectedText?: string;
  quotes?: QuotedMessageSnippet[];
  /** Відповідь на власний лист — смуга каже «Відповідь собі». */
  isSelf?: boolean;
}

export interface ThinkingStage {
  stage: 'perceive' | 'recall' | 'reason' | 'act' | 'write' | 'done';
  label: string;
  active: boolean;
  thoughtLog?: string[];
  toolName?: string;
  durationMs?: number;
}

export interface CanvasBlock {
  id: string;
  type: 'heading' | 'text' | 'decision' | 'action-item' | 'code' | 'checklist';
  content: string;
  authorId?: string;
  authorName?: string;
  updatedAt: string;
  checked?: boolean;
}

export interface CanvasDocument {
  id: string;
  threadId: string;
  conversationId: string;
  title: string;
  blocks: CanvasBlock[];
  rawMarkdown: string;
  decisionsCount: number;
  openQuestionsCount: number;
  lastUpdated: string;
  updatedBy: string;
}

export interface KanbanCard {
  id: string;
  title: string;
  description?: string;
  assignee?: string;
  priority?: 'low' | 'med' | 'high' | 'urgent';
  tags?: string[];
  /** Останній дотик: коли й хто переніс пункт (злиття версій картки). */
  at?: number;
  by?: string;
}

export interface KanbanColumn {
  id: string;
  title: string;
  color?: string;
  items: KanbanCard[];
}

export interface KanbanData {
  id: string;
  title: string;
  columns: KanbanColumn[];
  /** Порядок пунктів, як їх склав автор: рядок не стрибає, коли пункт міняє стовпчик. */
  order?: string[];
}

export interface VotingOption {
  id: string;
  text: string;
  votes: number;
  /** В анонімному опитуванні не їде. */
  voters?: string[];
}

/** Дріт картки Дня везе лише дату; пункти веб бере з Дня живими. */
export interface DayCardData {
  v: 1;
  day: string;
  at: number;
}

/** Знімок «що між вами» на час питання: останні слова, мої обіцянки, перекази. */
export interface GlanceCardData {
  v: 1;
  peer: string;
  name: string;
  at: number;
  last: { text: string; at: number; mine: boolean } | null;
  promises: { what: string; due: number | null }[];
  money: { coin: string; sent: string; got: string }[];
  test: boolean;
}

/** Знімок грошей від помічника: баланс, перекази за тиждень і відкриті частки рахунків. */
export interface MoneyCardData {
  v: 1;
  at: number;
  wallet: boolean;
  balances: { coin: string; amount: string }[];
  week: { peer: string; name: string; coin: string; amount: string }[];
  splits: { chat?: string; peer: string; name: string; coin: string; amount: string; owesMe: boolean }[];
  test: boolean;
}

/** Подарунок (`widget:gift`): рід із каталогу й короткий підпис; читати через `giftOf`. */
export interface GiftData {
  v: 1;
  id: string;
  gift: string;
  note?: string;
}

export interface PersonData {
  v: 1;
  id: string;
  name: string;
  /** Вузол людини — за ним отримувач шукає її серед своїх. */
  peerId: string;
}

export interface VotingData {
  id: string;
  question: string;
  options: VotingOption[];
  deadline?: string;
  isClosed?: boolean;
  totalVotes: number;
  winningOptionId?: string;
  allowMultiple?: boolean;
  anonymous?: boolean;
  /** Вікторина: правильний варіант. */
  correctOptionId?: string;
  /** Анонімне: лише вибір того, хто надіслав цю версію. */
  pick?: string[];
}

export interface RACIRow {
  id: string;
  task: string;
  r: string; // Responsible
  a: string; // Accountable
  c: string; // Consulted
  i: string; // Informed
}

export interface RACIData {
  id: string;
  title: string;
  roles: string[];
  rows: RACIRow[];
}

export interface CodeRunnerData {
  id: string;
  title?: string;
  language: 'javascript' | 'typescript' | 'python' | 'shell';
  code: string;
  lastOutput?: string;
  status?: 'idle' | 'running' | 'success' | 'error';
}

export interface MermaidData {
  code: string;
  title?: string;
  caption?: string;
}

export interface CodeDiffData {
  filename: string;
  oldCode: string;
  newCode: string;
  language?: string;
}

export interface SvgPreviewData {
  svgContent: string;
  title?: string;
  viewBox?: string;
}

export interface WebhookEventData {
  source: 'github' | 'gitlab' | 'gitea' | 'ci' | 'sentry' | 'custom';
  eventType: string;
  repository?: string;
  sender?: string;
  title: string;
  description?: string;
  url?: string;
  status?: 'success' | 'failure' | 'pending' | 'neutral';
  commitHash?: string;
  timestamp: string;
}

export interface TransitDepartureItem {
  line: string;
  mode: string;
  mode_display?: string;
  direction?: string;
  minutes_until: number;
  real_time: boolean;
  status?: 'realtime' | 'scheduled';
  departure_iso?: string;
  stop_name?: string;
  color?: string;
}

export interface TransitArrivalsData {
  city?: string;
  stop_name: string;
  lat?: number;
  lon?: number;
  radius_m?: number;
  departures: TransitDepartureItem[];
  last_updated?: string;
}

export interface TimelineMilestone {
  id: string;
  title: string;
  description?: string;
  assignee?: string;
  status: 'pending' | 'in_progress' | 'completed' | 'blocked';
  progress: number; // 0..100
  dueDate?: string;
  tags?: string[];
  at?: number;
  by?: string;
}

export interface TimelineData {
  id: string;
  title: string;
  milestones: TimelineMilestone[];
  startDate?: string;
  targetDate?: string;
}

export interface AsyncSnippetTranscript {
  timestamp: string;
  timeSeconds: number;
  text: string;
}

export interface AsyncSnippetData {
  id: string;
  title: string;
  durationSeconds: number;
  videoUrl?: string;
  audioUrl?: string;
  thumbnailUrl?: string;
  transcripts: AsyncSnippetTranscript[];
  summary?: string;
  authorName: string;
}

export interface WorkspaceFileVersion {
  version: string;
  updatedAt: string;
  updatedBy: string;
  sizeBytes: number;
  changeNote?: string;
  url: string;
  sha256?: string;
}

export interface WorkspaceDriveFile {
  id: string;
  name: string;
  category: 'code' | 'image' | 'document' | 'archive' | 'media' | 'canvas';
  sizeBytes: number;
  updatedAt: string;
  updatedBy: string;
  currentVersion: string;
  versions: WorkspaceFileVersion[];
  url: string;
  tags?: string[];
  isLocked?: boolean;
}

/* `HuddleParticipant` і `HuddleRoom` прибрано 05.09 разом зі студією Team
   Huddle (TeamHuddleBar/TeamHuddleStudio). Багатосторонніх дзвінків у
   портфелі немає — тип кімнати описував поверхню, якої не існує. */

export interface FamilyChecklistItem {
  id: string;
  text: string;
  category: string;
  done: boolean;
  addedBy: string;
  doneBy?: string;
  at?: number;
}

export interface FamilyChecklistData {
  id: string;
  title: string;
  items: FamilyChecklistItem[];
}

export interface FamilyExpenseItem {
  id: string;
  title: string;
  amountUAH: number;
  category: string;
  paidBy: string;
}

export interface FamilyExpenseData {
  id: string;
  title: string;
  currency: string;
  items: FamilyExpenseItem[];
}

export interface StudyFlashcardItem {
  id: string;
  question: string;
  answer: string;
  formulaLatex?: string;
  reviewed: boolean;
}

export interface StudyFlashcardsData {
  id: string;
  topic: string;
  cards: StudyFlashcardItem[];
}

export interface StickerData {
  id: string;
  packId: string;
  url: string;
  emojiAlt?: string;
  title?: string;
}

/** Мережа Solana, як її називає міст (`Cluster.wire`). */
export type SolanaCluster = 'devnet' | 'mainnet';

/**
 * Стан переказу, яким його бачить МЕРЕЖА (`PaymentStatus.wire`): міст дописує
 * його при кожній віддачі рядка. Незнане слово з дроту стає `unknown`.
 */
export type SolanaPaymentStatus = 'pending' | 'confirmed' | 'failed' | 'mismatch' | 'unknown';

export interface MessagePayment {
  /** Десятковий рядок, як його склав телефон; без округлень. */
  amount: string;
  token: 'SOL' | 'USDC' | 'EURC' | 'SKR';
  cluster: SolanaCluster;
  /** Адреса отримувача, base58. */
  to: string;
  /** Підпис транзакції = її id у мережі. */
  signature: string;
  /** Купюра «готівки без інтернету», а не звичайний переказ. */
  offlineCash: boolean;
  /** Знак «Seeker перевірено» на купюрі: підпис сервера, строк і особу відправника телефон звірив без мережі. */
  seekerVerified: boolean;
  /** Рівень стейку SKR із того самого перевіреного знака; null — не стейкає або знака немає. */
  seekerSkrTier: 'staker' | '1k' | '10k' | null;
  /** Готовий рядок знака мовою телефона («Seeker перевірено · стейкає ≥10 тис. SKR»); null — знака немає. */
  seekerLabel: string | null;
  status: SolanaPaymentStatus;
  /** Чи це адреса МОГО активного гаманця; null — гаманця на цьому апараті немає. */
  toMine: boolean | null;
  /**
   * Id рахунку з `PaymentFrame` v2 (`;ref`): цей переказ — частка рахунку.
   * null — переказ v1, ні до чого не привʼязаний; такий частку НЕ оплачує.
   */
  ref?: string | null;
}

export interface MessageSeal {
  /** SHA-256 файла, 64 hex. Сам файл у мережу не йде. */
  sha256: string;
  signature: string;
  cluster: SolanaCluster;
  status: 'pending' | 'confirmed' | 'failed' | 'mismatch' | 'unknown';
  /** Секунди Unix — час блоку; лише при confirmed і лише якщо мережа його дала. */
  blockTime: number | null;
}

export interface Message {
  /** Відповідь помічника на ПК: вузол її не зберігає, тож вона живе тут і стоїть за листом `answers`. */
  localReply?: { answers: string };
  id: string;
  /** ID розмови, до якої належить лист (коли читається поза контестом розмови). */
  chatId?: string;
  /** Канал простору, у який сказано рядок (картка msg-x 05). */
  channelId?: string;
  /** Читачі замкненого каналу на мить відправки: повтор везе лист тим самим людям. */
  channelTo?: string[];
  senderId: string;
  senderName: string;
  senderAvatar: string;
  timestamp: string;
  /** Момент відправки з вузла, ISO. Без нього дату групування вигадувати не можна. */
  sentAt?: string;
  type: MessageType;
  text?: string;
  stickerData?: StickerData;
  thinking?: ThinkingStage;
  thoughtTrace?: string[];
  /** Питання до PHANTOM, яке без дороги в інтернет поїхало Наміром до мого ПК (розд. 07). */
  intentId?: string;
  /** Чернетка переказу, яку помічник відкрив із цього листа: «Скласти знову» відкриває той самий екран. */
  payDraft?: { peerId: string; token: string; amount: string; sent: boolean };
  /** Картка обіцянки від помічника (вибір людини, нагадування у строк, збережено). */
  commitmentCard?: CommitmentCardData;
  /** Службовий рядок строку зникнення: секунди (0 — вимкнено). Тип такого листа — 'system'. */
  timerSeconds?: number;
  /** Службовий рядок «ключ безпеки змінився» — складає веб із розмови (services/keyChange). */
  keyChange?: boolean;
  /** Службовий рядок дзвінка — з локального журналу (services/callLog), не з дроту. */
  callLine?: { kind: 'in' | 'out' | 'missed' | 'declined'; media: 'audio' | 'video'; durationSec: number };
  /** Картка посилання від відправника: заголовок, опис, сайт, мініатюра data-URI. */
  linkPreview?: { url: string; title: string; description?: string; site?: string; image?: string };
  aiSummary?: string;
  voiceData?: VoiceData;
  videoNoteData?: VideoNoteData;
  locationData?: LocationData;
  /** Точка з кадру geo:point — координати і час ВИМІРУ. */
  geoPoint?: GeoPoint;
  liveLocation?: LiveLocation;
  /** Маршрут із меша, розібраний мостом (kind='route'). */
  route?: SharedRoute;
  /** Місце з меша, розібране мостом (kind='place'). */
  place?: SharedPlace;
  /** Рід упізнано, читання не вдалося (kind='broken'). Речення — від хоста. */
  broken?: BrokenFrame;
  pollData?: PollData;
  eventData?: EventData;
  splitBillData?: SplitBillData;
  codeData?: CodeData;
  imageData?: ImageData;
  tableData?: TableData;
  chartData?: ChartData;
  taskListData?: TaskListData;
  multiQuoteData?: MultiQuoteData;
  fileData?: FileData;
  canvasData?: CanvasDocument;
  kanbanData?: KanbanData;
  votingData?: VotingData;
  raciData?: RACIData;
  timelineData?: TimelineData;
  familyChecklistData?: FamilyChecklistData;
  familyExpenseData?: FamilyExpenseData;
  studyFlashcardsData?: StudyFlashcardsData;
  asyncSnippetData?: AsyncSnippetData;
  codeRunnerData?: CodeRunnerData;
  mermaidData?: MermaidData;
  codeDiffData?: CodeDiffData;
  svgPreviewData?: SvgPreviewData;
  webhookEventData?: WebhookEventData;
  /** Тіло картки зустрічі (`widget:meetup`) — сире з дроту; читати через `parseMeetupData`. */
  meetupData?: MeetupData;
  /** Тіло картки рахунку (`widget:bill`) — сире з дроту; читати через `parseBillData`. */
  billData?: BillData;
  /** Тіло картки людини (`widget:person`); читати через `personOf`. */
  personData?: PersonData;
  /** Картка Дня від помічника (`widget:day`); читати через `dayCardOf`. */
  dayData?: DayCardData;
  /** Людина одним поглядом від помічника (`widget:glance`); читати через `glanceOf`. */
  glanceData?: GlanceCardData;
  /** Гроші одним поглядом від помічника (`widget:money`); читати через `moneyCardOf`. */
  moneyData?: MoneyCardData;
  /** Подарунок (`widget:gift`); читати через `giftOf`. */
  giftData?: GiftData;
  /**
   * Згорнуті мостом позначки стану `PH-CST:` на цій картці (`card_marks`):
   * значення → автори. Іду / коли буду / що я їв / лишити / я на місці.
   */
  cardMarks?: CardMarks;
  /** Фото або файл із наскрізним ключем — див. SecureMedia. */
  media?: SecureMedia;
  /** Batch metadata keeps a multi-file send ordered without weakening per-file delivery. */
  mediaBatch?: { id: string; index: number; total: number };
  mediaUrl?: string;
  audioUrl?: string;
  fileName?: string;
  fileSize?: number;
  ephemeral?: {
    expiresAt: number;
    durationSeconds: number;
  };
  reactions?: Reaction[];
  replyTo?: MessageReplyInfo;
  /** Member ids the author named with @ (`mn` on the wire); absent on older letters. */
  mentions?: string[];
  forwardFrom?: {
    chatTitle: string;
    senderName: string;
    senderId?: string;
  };
  isPinned?: boolean;
  /** `isPinned` comes from the shared 1:1 pin act, not this tab. */
  pinShared?: boolean;
  isEdited?: boolean;
  isSelf?: boolean;
  /**
   * Надгробок: тіла вже немає ні тут, ні на диску. Сам рядок лишається —
   * прибрати згадку про те, що співрозмовник уже бачив, неможливо, і вдавати
   * протилежне було б брехнею.
   */
  isDeleted?: boolean;
  /**
   * sending — ще не підтверджено вузлом;
   * queued — вузол записав, але до співрозмовника не доїхало;
   * sent — вузол-адресат узяв кадр; failed — не збереглося.
   *
   * «delivered» і «read» тут немає навмисно: вузол таких станів не віддає
   * (delivery_state ∈ local|queued|sent), тож дві галочки означали б стан,
   * якого в системі не буває.
   */
  status?: 'sending' | 'queued' | 'sent' | 'failed';
  /**
   * Частка вивантаженого вкладення 0..1, поки байти їдуть з ЦЬОГО пристрою.
   * Є лише в оптимістичної бульбашки; щойно вузол узяв байти — поля немає.
   * Число — від XHR (справжні байти), не таймер.
   */
  uploadProgress?: number;
  /**
   * Місцевий object URL (мініатюра або сам файл) для миттєвого показу
   * власного вкладення, поки воно вивантажується. На дріт не їде.
   */
  localPreviewUrl?: string;
  /** Local outbox bookkeeping; never sent as user content. */
  retryCount?: number;
  /** Чому питання лишилось без відповіді помічника: ярлик над «Повторити». */
  failReason?: string;
  /** Відповідь помічника, що ще росте (services/assistantLive); у журналі такого листа ще немає. */
  live?: 'thinking' | 'writing' | 'tool' | 'failed' | 'done';
  lastAttemptAtMs?: number;
  /**
   * Стан ПЕРЕВЕЗЕННЯ вкладення: stored | queued | parked | sent | missing |
   * upload_failed. Кадр із ключем міг доїхати, а байти — ні, і тоді бульбашка
   * мусить сказати це словом, а не лише кольором крапки.
   *
   * 'upload_failed' — окремий, ЛОКАЛЬНИЙ стан: браузер не зміг покласти
   * шифротекст на власний вузол, тож лист узагалі НЕ їхав туди — на відміну
   * від решти станів, які вузол сам повідомляє про вже прийнятий лист.
   */
  attachmentState?: string;
  /**
   * Де лежить СВІЙ лист, поки він «queued»: `stored` — сховок узяв копію й
   * назвав строк; `local` — лист ще тільки на цьому пристрої (сховок не
   * відповів або ще не питали); `acked` — квитанція є. Міст кладе це поле
   * (`WebMessengerKinds.relayState`) з 12.09, і досі його не читав ніхто:
   * під КОЖНИМ queued стояло «лежить у сховку», навіть коли сховок листа
   * не бачив. Окреме поле, а не нове значення status — див. закритий
   * словник `deliveryStatus`.
   */
  relayState?: string;
  /**
   * Людське пояснення, ЧОМУ вивантаження вкладення не вдалось. Стоїть лише
   * поруч із attachmentState === 'upload_failed' — саме тут мовчання було б
   * найгіршою вадою: галочка «надіслано» без цього тексту брехала б.
   */
  attachmentError?: string;
  /**
   * Скільки копій групового рядка вже дійшло і скільки їх усього.
   *
   * Стоїть лише під ВЛАСНИМИ рядками: про чужу репліку цей телефон знає одну
   * копію — свою, — і будь-яке число під нею було б зібране з одного
   * свідчення. Там мовчання, а не «1 з ?».
   */
  fanDelivered?: number;
  fanTotal?: number;
  /** Скільки учасників прочитали. undefined — невідомо. */
  fanRead?: number;
  /** Коли співрозмовник прочитав. undefined — невідомо або ще не прочитав. */
  readAt?: string;
  scheduledTime?: string;
  /**
   * Дорога, якою кадр приїхав. 'mailbox' — не прикраса: точка з листа, що
   * чекав у скриньці, застаріла за законом №3, хоч би що казав її час виміру.
   */
  transport?: 'server' | 'p2p' | 'relay' | 'direct' | 'mailbox';
  /**
   * Акт над гілкою, якщо цей рядок — не мова, а дія (`system_act` з мосту).
   *
   * Закритий перелік навмисно НЕ закритий до літералів: новий рід на дроті
   * приїде сюди рядком і мусить лишитись читабельним, а не провалитись у
   * гілку «інакше», яка стверджує щось інше (див. урок про закритий словник).
   */
  systemAct?: string;
  /** Кого стосується груповий акт (`system_act_target` з мосту, напр. доданий учасник у `add`). */
  systemActTarget?: string;
  /**
   * Переказ або купюра «готівки без інтернету» — поле `payment` з мосту
   * (`docs/solana/PAYMENT_CARD_SPEC.md`). Є лише тоді, коли поле доїхало й
   * пройшло перевірку форми; інакше лишається запасне речення `text`.
   */
  payment?: MessagePayment;
  /** Відбиток файла в Solana Memo `phantom-seal:v1` — поле `seal` з мосту. */
  seal?: MessageSeal;
  p2pMeta?: {
    latencyMs?: number;
    peerFingerprint?: string;
    directHops?: number;
    encryptedE2E?: boolean;
  };
}

export interface ScheduledMessage {
  id: string;
  chatId: string;
  chatTitle: string;
  chatAvatar?: string;
  senderId?: string;
  senderName?: string;
  scheduledTime: string; // e.g., "Сьогодні о 18:00", "2026-08-20 о 10:00"
  scheduledDate?: string; // ISO format "2026-08-20"
  scheduledExactTime?: string; // "18:00"
  createdAt: string;
  /** Апарат не зміг надіслати — його ж словами. */
  failure?: string;
  /** Після відправки лишається з наступним часом (daily · weekly · monthly · weekdays). */
  repeat?: 'daily' | 'weekly' | 'monthly' | 'weekdays';
  /** Без часу: іде, щойно людина озветься (лише телефон). */
  whenOnline?: boolean;
  type: MessageType;
  text?: string;
  fileData?: FileData;
  imageData?: ImageData;
  tableData?: TableData;
  chartData?: ChartData;
  pollData?: PollData;
  eventData?: EventData;
  taskListData?: TaskListData;
  splitBillData?: SplitBillData;
  codeData?: CodeData;
}

export type ChatType = 'direct' | 'dm' | 'group' | 'channel' | 'p2p' | 'ai' | 'phantom' | 'call' | 'sms' | 'bridge' | 'saved';
export type ChatCategory = 'all' | 'phantom' | 'calls' | 'sms' | 'bridges' | 'people';

/**
 * Щабель у гілці — рівно той, що на дроті (`PeerGroupRoles.Role.wire`).
 *
 * Чотири, і жодного зайвого: п'ять титулів старої матриці дозволів
 * (Тімлід, Спостерігач) не мали ЖОДНОЇ дії, якою б відрізнялись. Тут кожен
 * щабель відрізняється від сусіднього тим, що приймач йому дозволяє:
 * модератор прибирає учасника й закріплює, адмін перейменовує й міняє
 * обличчя, власник роздає адмінів і передає гілку.
 */
export type GroupRole = 'owner' | 'admin' | 'mod' | 'member';

/** Що дозволено мені в цій гілці — з вузла, не з мого припущення. */
export interface GroupPowers {
  add: boolean;
  remove: boolean;
  rename: boolean;
  card: boolean;
  grant: boolean;
  transfer: boolean;
  pin: boolean;
  /** Посилання-запрошення й заявки. */
  invite?: boolean;
  /** Повільний режим, історія, коментарі. */
  settings?: boolean;
}

/** Права адміна чи модератора, які роздає власник. */
export type GroupRight = 'info' | 'pin' | 'ban' | 'invite' | 'admins';
export const GROUP_RIGHTS: GroupRight[] = ['info', 'pin', 'ban', 'invite', 'admins'];

/** Налаштування гілки — ті самі ключі, що на дроті (`set`). */
export interface GroupSettings {
  kind: 'group' | 'channel' | 'space';
  /** Повільний режим у секундах; 0 — вимкнено. */
  slow: number;
  /** Історія для нових учасників. */
  hist: boolean;
  /** Вступ за заявкою. */
  ask: boolean;
  /** Чинний ключ посилання-запрошення; порожньо — посилання немає. */
  link: string;
  /** Коментарі в каналі. */
  talk: boolean;
  sign: boolean;
}

export const SLOW_STEPS = [0, 10, 30, 60, 300, 900, 3600] as const;

export interface ChatMember {
  id: string;
  name: string;
  handle: string;
  avatar: string;
  /**
   * Приходить з вузла, обчислений з ТОГО САМОГО журналу, яким приймач судить
   * акти. Ніколи не вигадується тут: комміт 98a90397 прибирав рівно такий
   * бейдж, під яким не стояло нічого.
   */
  role: GroupRole;
  /**
   * Чи може ЦЕЙ оператор прибрати / підвищити / передати гілку саме ЦІЙ
   * людині. Не «чи є в нього право взагалі»: адмін не чіпає адміна, і кнопка
   * над рівним була б кнопкою, що мовчки не робить нічого. Судить вузол тим
   * самим `PeerGroup.refuse`, яким відмовляє приймач.
   */
  mayRemove?: boolean;
  mayGrant?: boolean;
  mayOwn?: boolean;
  /** Права цього адміна/модератора; власник може їх міняти, коли `mayRights`. */
  rights?: GroupRight[];
  mayRights?: boolean;
  customTitle?: string;
  statusText?: string;
  /**
   * Присутність, яку хтось виміряв. Поле необовʼязкове саме тому, що «не
   * знаємо» — це третій стан, а не false: обовʼязковий boolean змушував
   * кожного, хто складає учасника, щось вигадати, і вигадували `true`.
   */
  isOnline?: boolean;
  phone?: string;
  bio?: string;
  /**
   * Ця людина є у ТВОЄМУ списку — єдине про склад, що пристрій може
   * перевірити сам. Решта імен приїхала в заголовку чужого листа.
   */
  inRoster?: boolean;
  /** Число безпеки звірено. Не те саме, що «є у списку». */
  verified?: boolean;
  /** Є ключ — копія для цієї людини взагалі може запечататись. */
  sessionReady?: boolean;
  isMe?: boolean;
}

export interface SharedLinkItem {
  id: string;
  url: string;
  title: string;
  domain: string;
  timestamp: string;
  senderName: string;
}

export interface ActiveDevice {
  id: string;
  name: string;
  type: 'desktop' | 'mobile' | 'web';
  location: string;
  ipAddress: string;
  lastActive: string;
  isCurrent: boolean;
}

export interface UserProfile {
  id: string;
  name: string;
  handle: string;
  avatar: string;
  status: string;
  statusEmoji: string;
  bio: string;
  phone: string;
  locationName: string;
  batteryLevel: number;
  isMutedAlerts: boolean;
  circleRole?: string;
  personalNotes?: string;
  twoFactorEnabled?: boolean;
  storageUsageMb?: {
    media: number;
    files: number;
    voice: number;
    cache: number;
  };
  activeDevices?: ActiveDevice[];
}

export interface GroupPermissions {
  sendMessages: boolean;
  sendMedia: boolean;
  sendStickersAndGifs: boolean;
  sendPolls: boolean;
  embedLinks: boolean;
  addMembers: boolean;
  pinMessages: boolean;
  changeChatInfo: boolean;
}

// `GroupInviteLink` і `PendingJoinRequest` жили тут до 15.09: посилання-
// запрошення й черга заявок на вступ, яких дріт групи не має взагалі
// (`Act.Add`/`Remove`/`Leave`/`Rename` — і все). Кнопка «Створити» в
// GroupDetailsDrawer лишала `code: ''`, заявки нізвідки не наповнювались.
// Прибрано разом з екраном. Дивись docs/GROUP_ROLES_CONTRACT.md.

export interface GroupAuditItem {
  id: string;
  actorName: string;
  actorAvatar: string;
  action: string;
  detail: string;
  timestamp: string;
}

export interface GroupTopic {
  id: string;
  title: string;
  iconEmoji: string;
  color: string;
  messageCount: number;
  lastMessageText?: string;
}

export interface Chat {
  id: string;
  title: string;
  /** null — розмова ні з ким; false — співрозмовника ще не звірено голосом. */
  contactVerified?: boolean | null;
  /** Ключ під цим іменем змінився після звірки; стара звірка не діє. */
  contactKeyChanged?: boolean;
  /** Коли ключ змінився, ISO — місце службового рядка в стрічці. */
  contactKeyChangedAt?: string;
  /** Звірений контакт змінив ключ: листи чекають на «Продовжити» або нову звірку. */
  contactKeyHold?: boolean;
  /** Строк зникнення повідомлень розмови, секунди; 0 або нема — вимкнено. */
  disappearSeconds?: number;
  /** Показова розмова: вміст вигаданий і позначений як вигаданий. */
  isDemo?: boolean;
  peerNodeId?: string;
  lastKind?: string;
  /** Пласкі поля останнього кадру з рядка розмови — числа для переліку. */
  lastCard?: ChatLastCard;
  lastSnippet?: string;
  lastAuthor?: string;
  lastAt?: string;
  /**
   * Спокійний потік (services/calmInbox): коли писала сама людина, коли —
   * інші, і скільки листів за 30 днів. undefined — вузол цього не каже
   * (старий міст, ПК), і тоді список нічого не згортає й не підносить.
   */
  lastSelfAt?: string | null;
  lastPeerAt?: string | null;
  letters30d?: number;
  /** Звірений довідником @нік людини. Незвірену заяву міст не віддає. */
  handle?: string;
  /** Мій власний ПК чи ноутбук — не людина. */
  mine?: boolean;
  /** Коли контакт додано — розрізняє однакові імена. */
  addedAt?: string;
  avatar: string;
  type: ChatType;
  circle: ChatCircle;
  circles?: string[];
  badge?: string;
  isOnline?: boolean;
  lastActive?: string;
  lastSeen?: string;
  pinned?: boolean;
  muted?: boolean;
  archived?: boolean;
  unreadCount: number;
  membersCount?: number;
  customVibe?: string;
  description?: string;
  topic?: string;
  members?: ChatMember[];
  /**
   * Чи є склад групи фактом про світ. Від меша — завжди false.
   *
   * Склад їде в заголовку кожного листа, тобто це слово того, хто вас додав, а
   * не реєстр. Поле необовʼязкове саме тому, що «не питали» — третій стан:
   * розмова вдвох складу не має взагалі.
   */
  membersAuthoritative?: boolean;
  /** Кого ще можна додати — з твого списку і лише з ключем. */
  membersAddable?: Array<{ id: string; name: string }>;
  membersMax?: number;
  /** Мій щабель у цій гілці. Без нього поверхня не знає, що показувати. */
  myRole?: GroupRole;
  /** Хто веде гілку. Засвідчено першим кадром, не доведено підписом. */
  ownerId?: string;
  /**
   * Що САМЕ цей пристрій застосує зі свого журналу. Пристрій, який не чув
   * половини актів, чесно покаже менше, а не вигадає більше.
   */
  powers?: GroupPowers;
  /** Налаштування гілки з вузла; немає — вузол їх не знає (стара збірка). */
  groupSettings?: GroupSettings;
  /**
   * Закріплений рядок. `id: ''` при наявному авторі означає «закріплене є, а
   * самого рядка цей телефон не має» — і це не те саме, що «нічого не
   * закріплено».
   */
  pinnedMessage?: {
    id: string;
    authorId: string;
    author: string;
    at: string;
    text: string | null;
  } | null;
  /** Жива розмова в гілці — зі слів її учасників. Немає — ніхто не говорить. */
  liveRoom?: LiveRoom | null;
  /** K2: the plan this group was made for (row field `plan`); null when it is not a plan group. */
  plan?: ChatPlan | null;
  /**
   * in | left | removed — чи можна ще писати сюди. Після виходу історія
   * лишається на пристрої, і саме тому «пішов» і «стер» — різні стани.
   */
  standing?: 'in' | 'left' | 'removed';
  sharedLinks?: SharedLinkItem[];
  sharedMediaCount?: {
    photos: number;
    files: number;
    links: number;
    audio: number;
    tables: number;
  };
  isPublic?: boolean;
  publicHandle?: string;
  isForum?: boolean;
  topics?: GroupTopic[];
  slowModeSeconds?: number;
  autoDeleteSeconds?: number;
  historyVisibilityForNewMembers?: 'visible' | 'hidden';
  permissions?: GroupPermissions;
  auditLogs?: GroupAuditItem[];
  category?: ChatCategory;
  isE2EE?: boolean;
  callDuration?: string;
  callType?: 'incoming' | 'outgoing' | 'missed' | 'voicemail';
  isHot?: boolean;
  aiSecretarySummary?: string;
  sessionId?: string;
  allowedReactions?: 'all' | 'basic' | 'none';
  draft?: string;
  transportMode?: TransportProtocol;
  messages: Message[];
}

export type TransportProtocol = 'auto' | 'server' | 'p2p' | 'relay';
export type ActiveTransportStatus = 'server-ws' | 'p2p-direct' | 'connecting' | 'fallback-server' | 'relay-node' | 'offline';

export interface P2PPeerSession {
  peerId: string;
  peerName: string;
  avatar?: string;
  connectionState: 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';
  iceState: 'new' | 'checking' | 'connected' | 'completed' | 'failed' | 'disconnected' | 'closed';
  dataChannelState: 'connecting' | 'open' | 'closing' | 'closed';
  rttMs?: number;
  bytesSent?: number;
  bytesReceived?: number;
  packetsLost?: number;
  fingerprint?: string;
  isDirectP2P: boolean;
  connectedAt?: string;
}

export interface NetworkDiagnostics {
  transportMode: TransportProtocol;
  activeStatus: ActiveTransportStatus;
  /** null, доки не було жодного заміру. */
  latencyMs: number | null;
  connectedClientsCount: number;
  p2pPeersCount: number;
  isWebRTCSupported: boolean;
  isWebSocketConnected: boolean;
  bytesTransferred: {
    server: number;
    p2p: number;
  };
  iceRoads: string;
  dataChannelStatus: string;
  relayConnected?: boolean;
  relayNodeId?: string;
}

export interface CommitmentCandidate {
  peerId: string;
  name: string;
  hint?: string | null;
}

export interface CommitmentCardData {
  kind: 'choose' | 'due' | 'saved' | 'list' | 'offer';
  commitmentId?: string;
  pendingId?: string;
  sourceMessageId?: string;
  person: string;
  peerId?: string | null;
  what: string;
  dueMs?: number | null;
  candidates?: CommitmentCandidate[];
  state: 'open' | 'done' | 'dropped';
}

export interface LiveRoom {
  callId: string;
  startedAt: string;
  /** Інші люди в розмові (id вузлів). Себе сюди не кладемо — для цього `mine`. */
  faces: string[];
  mine: boolean;
}

/** K2: a plan group's row (`plan` from the phone, ISO times turned into ms Unix). */
export interface ChatPlan {
  meetupId: string;
  venue: string | null;
  organizer: string;
  at: number | null;
  expiresAt: number | null;
  keptBy: string[];
  cancelled: boolean;
}
