import { plainOrderText } from '../services/business/orders';
import { tNow } from '../components/chat/tNow';
import { clockLabel } from '../utils/clockFormat';
import { keepMessageIdentity } from '../services/messageIdentity';
import { mergeLiveRow, routeMessengerEvent } from '../services/messengerLive';
import { create } from 'zustand';
import { messengerNetworkEngine } from '../services/messengerNetworkEngine';
import { wsClient } from '../services/websocket';
import { chatApi, request } from '../services/api';
import { isAssistantKind } from '../services/assistantChat';
import { createMeshDeltaSink, mergeRows, type MeshDelta } from '../services/meshDelta';
import { messengerApi, chatFromNode, messageFromNode, withReplies, deliveryStatus, pinFromNode, settingsFromNode, phoneBridge, reactionsFromNode, SAVED_NOTES_ID, quoteForWire } from '../services/messengerApi';
import { chatHush } from '../services/chatHush';
import { signalIncoming } from '../services/ringSignals';
import { chatPins } from '../services/chatPins';
import { userMarks } from '../services/userMarks';
import { chatArchive } from '../services/chatArchive';
import { chatKeep } from '../services/chatKeep';
import { selfAdded } from '../services/selfAdded';
import { SELF_AUTHOR, hasLetter } from '../components/messenger/chatListFormat';
import type { NodeConversation, NodeMessage, NodeScheduledLetter } from '../services/messengerApi';
import { sayRefusal } from '../services/nodeRefusal';
import { useUIStore } from './uiStore';
import { useAuthStore } from './authStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from './settingsStore';
import { soundFx } from '../utils/messengerSound';
import { applyReaction, reactionActionFor, type ReactionAction } from '../services/reactionState';
import {
  mediaLimitLabel,
  MEDIA_PLAIN_LIMIT_BYTES,
  applyMediaLimitFromNode,
  encryptForUpload,
  humanSize,
  isRenderableImage,
  rememberUploadedMedia,
} from '../services/messengerMedia';
import { attachmentUploads, QueueCancelled } from '../services/uploadQueue';
import { prepareImage, type PreparedImage } from '../services/imagePrep';
import { stripMetadata } from '../services/stripMetadata';
import { routeForChatEnsured } from '../services/carrier/route';
import { sendCarrier } from '../services/carrier/upload';
import type { CarrierManifest } from '../services/carrier/seal';
import type {
  Chat,
  Message,
  ChatCircle,
  UserProfile,
  ScheduledMessage,
  LocationData,
  MessageReplyInfo,
  ChannelLine,
  SecureMedia,
  GeoPoint,
  LiveLocation,
  Reaction,
  GroupRight,
} from '../types/messenger';
import { GROUP_RIGHTS } from '../types/messenger';
import { geoPointBody, liveLocationBody } from '../services/messengerGeo';
import { cardFieldOf, isAssistantOnlyKind, refuseCard } from '../services/composerCards';
import { useSpaceStore } from './spaceStore';
import {
  initialChats,
  currentUser as defaultUser,
  scheduledMessages as defaultScheduled,
} from '../data/messengerInitialData';
// `conversationalAgent` більше не викликається звідси — див. коментарі нижче.
import { globalP2PMesh } from '../services/globalP2PMesh';
import { importSavedNotesOnce } from '../services/savedNotesImport';
import { storagePersistence, StorageWriteError } from '../services/storagePersistence';
import { flushDrafts, keepDraft, loadDrafts } from '../services/draftVault';
import { useProfiles } from './profilesStore';
import { createTypingShown } from '../services/typingSignal';
import { isFacePhotoUrl, profilePhotosOf } from '../services/peerProfile';
import { appSeen, onAppSeenAgain } from '../services/appForeground';
import { codeWindowEnd, dueCodes, shutCodes } from '../services/codeWindow';
import { modelText } from '../services/assistantInput';
import { nextRepeat } from '../components/messenger/when/whenMath';

const FORWARD_RICH_FIELD: Record<string, keyof Message> = {
  table: 'tableData',
  chart: 'chartData',
  'task-list': 'taskListData',
  poll: 'pollData',
  event: 'eventData',
  'split-bill': 'splitBillData',
  code: 'codeData',
  location: 'locationData',
  'multi-quote': 'multiQuoteData',
};

/**
 * Оригінальні File-обʼєкти листів, чиє вивантаження не вдалось.
 *
 * НЕ в `Message` і НЕ в persisted-стані: `storagePersistence.saveChats`
 * кладе чати в localStorage/IndexedDB через серіалізацію, а File туди не
 * влазить. Мапа живе лише в цій вкладці — переживає ре-рендери, гине з
 * перезавантаженням сторінки. `retrySend` для такого листа читає звідси; якщо
 * запису вже нема (вкладку перезавантажили), кажемо про це прямо, а не
 * крутимо вічний спінер.
 */
const pendingUploadFiles = new Map<string, File>();

export interface MessengerState {
  // Current session & persona
  currentUser: UserProfile;
  profilePhotos: Record<string, string>;
  chats: Chat[];
  activeChatId: string;
  /**
   * Лист, до якого стрибнути, щойно розмова відкриється (пошук по всіх
   * розмовах). Живе в сторі, а не в пропі: стрічка доїжджає з вузла ПІСЛЯ
   * відкриття, і намір мусить дожити до неї.
   */
  pendingJump: { chatId: string; messageId: string } | null;
  activeCircle: ChatCircle;
  scheduledMessages: ScheduledMessage[];
  searchQuery: string;

  // Multi-select & Bulk operations
  multiSelectMode: boolean;
  /** Вибір листів для скарги: до REPORT_PICK_MAX, замість смуги дій — «Далі». */
  reportPick: boolean;
  selectedMessageIds: string[];
  /** Лист, від якого тягнеться проміжок (останній, якого торкнулись). */
  selectionAnchorId: string | null;

  // Modals & Panels State
  isProfileModalOpen: boolean;
  isSettingsModalOpen: boolean;
  isCreateChatModalOpen: boolean;
  /** З яких дверей відкрити «Нову»: 'group' — одразу збір групи. */
  createChatStart: 'group' | 'channel' | 'add' | null;
  isScheduledDrawerOpen: boolean;
  isGroupDetailsOpen: boolean;
  isForwardModalOpen: boolean;
  isMediaLightboxOpen: boolean;
  activeLightboxUrl: string;
  /** Імʼя файла, а не «Медіафайл»: людина мусить бачити, що саме відкрила. */
  activeLightboxTitle: string;
  activeLocationData: LocationData | null;
  activeForwardMessage: Message | null;
  activeForwardMessages: Message[];

  // Drafts & Typing
  drafts: Record<string, string>;
  typingUsers: Record<string, { userId: string; userName: string; timestamp: number }>;
  typingStatus: Record<string, string | null>;
  setTypingStatus: (chatId: string, status: string | null) => void;

  /**
   * Речення про те, що останній запис на диск не вдався, — або `null`.
   *
   * Не тост і не лог: поки воно тут, розмову не можна показувати збереженою.
   * Живе в сторі саме тому, що мусить пережити перемальовування композера —
   * людина має бачити його доти, доки місце не звільнилось.
   */
  storageFailure: string | null;
  clearStorageFailure: () => void;

  // Getters & Selectors
  getActiveChat: () => Chat | undefined;
  getScheduledForActiveChat: () => ScheduledMessage[];

  // Actions
  setActiveChat: (id: string) => void;
  /** Відкрити розмову й прокрутити до листа з підсвіткою. */
  openChatAtMessage: (chatId: string, messageId: string) => void;
  clearPendingJump: () => void;
  setActiveCircle: (circle: ChatCircle) => void;
  setSearchQuery: (query: string) => void;
  setDraft: (chatId: string, text: string) => void;

  updateChat: (chatId: string, updates: Partial<Chat>) => void;
  togglePinChat: (chatId: string) => void;
  toggleMuteChat: (chatId: string) => void;
  toggleArchiveChat: (chatId: string) => void;
  /** Коло з відповіді телефона; false — не змінилось. */
  setChatCircle: (chatId: string, circle: ChatCircle) => Promise<boolean>;
  /**
   * `memberIds` — не необовʼязкова прикраса: без людей міст відмовляє 422
   * `no_members`, бо група живе своїми листами й без адресатів її ніде
   * зберігати. Порожній перелік доїде до відмови й скаже це словом.
   */
  createGroup: (
    title: string,
    circle?: ChatCircle,
    avatar?: string,
    description?: string,
    memberIds?: string[],
    kind?: 'group' | 'channel' | 'space',
  ) => Promise<string>;
  /** Склад групи — з мосту. Своєї уяви про те, хто в групі, веб не має. */
  /**
   * Склад групи — з мосту. Своєї уяви про те, хто в групі, веб не має.
   *
   * Повертає, чи міст відповів. Мовчазне `void` не давало поверхні відрізнити
   * «ще питаємо» від «міст відмовив», і обидва стани малювались однаковим
   * «склад ще не питали» — тобто екран не вмів показати помилку взагалі.
   */
  loadGroupMembers: (chatId: string) => Promise<boolean>;
  /** Додати/прибрати людину в уже наявній групі. Відповідь — новий склад. */
  addGroupMember: (chatId: string, peerId: string) => Promise<void>;
  removeGroupMember: (chatId: string, peerId: string) => Promise<void>;
  setGroupRole: (
    chatId: string,
    peerId: string,
    role: '' | 'owner' | 'admin' | 'mod' | 'member',
  ) => Promise<void>;
  describeGroup: (chatId: string, about: string) => Promise<void>;
  pinGroupMessage: (chatId: string, messageId: string) => Promise<void>;
  /** Налаштування гілки: повільний режим, історія, заявки, посилання, коментарі. */
  setGroupSettings: (chatId: string, patch: Record<string, string>) => Promise<void>;
  /** Права адміна чи модератора. Лише власник. */
  setMemberRights: (chatId: string, peerId: string, rights: string[]) => Promise<void>;
  /** Назва й обличчя гілки — з дротом. `updateChat` міняє лише свою копію. */
  renameGroup: (chatId: string, title: string) => Promise<void>;
  setGroupFace: (chatId: string, avatar: string) => Promise<void>;
  /** Вийти з групи. Повертає, скільком це вже сказано. */
  leaveGroup: (chatId: string) => Promise<number>;
  createDirectMessage: (name: string, circle?: ChatCircle, avatar?: string, contactId?: string) => Promise<string>;
  /** Відкрити або створити локальний простір «Збережене» (Saved Notes) для закладок та особистих нотаток */
  openSavedNotes: () => void;

  /** `to` — інша розмова (надіслати кільком): чернетка й відповідь відкритої не чіпаються. */
  sendMessage: (text: string, opts?: { keepDraft?: boolean; quiet?: boolean; to?: string; mentions?: string[] }) => void;
  /** Рядок у канал простору: адресати — лише його читачі (`to`), null — уся гілка. */
  sendChannelLine: (chatId: string, text: string, line: ChannelLine) => void;
  /**
   * Надсилає фото або файл. Шифрує в браузері, кладе шифротекст на свій вузол,
   * а ключ відправляє в тілі повідомлення — його вузол запечатує кадром до
   * вузла співрозмовника.
   * Відсоток іде з XHR, тож смуга показує справжні байти, а не таймер.
   * caption — текст із композера: їде підписом У ТОМУ Ж повідомленні.
   */
  sendAttachment: (
    file: File,
    onProgress?: (percent: number) => void,
    caption?: string,
    batch?: { id: string; index: number; total: number },
    options?: {
      kind?: 'image' | 'file' | 'voice' | 'video-note';
      voice?: { duration: number; waveform: number[]; transcript?: string };
      videoNote?: { duration: number };
      /** Запис на вузол — лише після цієї обіцянки (порядок пачки). */
      appendAfter?: Promise<void>;
      /** Питають, коли черга вивантажень дійшла до файла: true — не стартує. */
      cancelled?: () => boolean;
      throwOnFail?: boolean;
      /** Фото їде як є, без зменшення; межа вкладення та сама. */
      original?: boolean;
    },
  ) => Promise<void>;
  /**
   * Кілька вкладень разом: бульбашки одразу, вивантаження по три одночасно,
   * записи на вузол у порядку вибору. Повертає речення про те, що не поїхало.
   */
  sendAttachments: (
    files: File[],
    opts?: {
      caption?: string;
      batchId?: string;
      /** Загальний відсоток пачки 0..100. */
      onProgress?: (percent: number) => void;
      cancelled?: () => boolean;
      original?: boolean;
    },
  ) => Promise<string[]>;
  /** Надсилає разову точку «я тут». Час у тілі — час ВИМІРУ, не відправки. */
  sendGeoPoint: (point: GeoPoint) => Promise<void>;
  sendLiveLocation: (location: LiveLocation) => Promise<void>;
  liveShareStart: (peerIds: string[], minutes: number) => Promise<boolean>;
  liveShareStop: (peerIds: string[]) => Promise<void>;
  liveShareStatus: (peerId: string) => Promise<boolean>;
  sendVoiceMessage: (
    duration: number,
    transcript: string,
    audioBlob?: Blob,
    waveform?: number[],
  ) => void;
  sendVideoNote: (videoBlob: Blob, durationS: number) => Promise<void>;
  /**
   * Картка композера — тією самою дорогою, що й слово.
   *
   * Тринадцять карток плюсика ішли [addCustomMessage] → веб-сокет каналом
   * `messenger`, якого на вузлі не існує (єдиний зареєстрований канал — `chat`),
   * а всередині телефона немає й самого сокета. Вони не їхали НІКУДИ, і жодна
   * поверхня про це не казала. Тепер картка йде `appendMessage` і отримує
   * звичайну галочку — або звичайне «не поїхало».
   *
   * Повертає причину відмови словами, або null — лист пішов у транспорт.
   */
  sendCard: (kind: string, data: unknown) => Promise<string | null>;
  addCustomMessage: (message: Message) => void;
  editMessage: (messageId: string, newText: string) => Promise<void>;
  /**
   * forEveryone — службовий кадр поїде співрозмовнику; інакше чистка своя.
   *
   * Повертає застереження словами, або null — вузол зробив те, що просили.
   * Не `void`: телефон «для всіх» НЕ вміє, і про це мусить сказати саме той,
   * хто знає відповідь вузла.
   */
  deleteMessage: (messageId: string, forEveryone?: boolean) => Promise<string | null>;
  togglePinMessage: (messageId: string) => void;
  /**
   * Повертає причину відмови словами, або null — позначка поїхала.
   *
   * Обіцянка `void` тут і була вадою: поверхня казала «Реакцію надіслано»
   * незалежно від того, чи щось вийшло з апарата.
   */
  toggleReaction: (messageId: string, emoji: string) => Promise<string | null>;
  addReaction: (messageId: string, emoji: string) => Promise<string | null>;
  /** `asOwn`: повтор свого вже надісланого фото — без позначки «переслано», підпису й пачки. */
  forwardMessage: (msg: Message, targetChatId: string, opts?: { asOwn?: boolean }) => Promise<void>;
  /** Повторна спроба надіслати лист, що впав або застряг у черзі. */
  retrySend: (messageId: string) => Promise<void>;

  // Interactive message widget mutators
  updateMessage: (messageId: string, updates: Partial<Message>) => void;
  sweepCodeWindows: (nowMs: number) => void;
  updateTableData: (messageId: string, data: any) => void;
  updateTaskListData: (messageId: string, tasks: any) => void;
  votePoll: (messageId: string, optionId: string) => void;
  payBillShare: (messageId: string, payerId: string) => void;

  // Відкладені листи. Час — день і година (`scheduledDate` +
  // `scheduledExactTime`), розмова — та, яку людина обрала, а не відкрита.
  addScheduledMessage: (draft: ScheduledDraftIn) => void;
  /** Новий час або «коли буде в мережі»; повтор — разом із часом. */
  rescheduleScheduledMessage: (id: string, to: { date: string; time: string; repeat?: ScheduledRepeat | '' } | { whenOnline: true }) => void;
  editScheduledText: (id: string, text: string) => void;
  deleteScheduledMessage: (id: string) => void;
  cancelScheduledMessage: (id: string) => void;
  /** Надіслати зараз — у розмову САМОГО листа, хоч би яка була відкрита. */
  sendScheduledNow: (id: string) => void;
  /**
   * Диспетчер: надсилає листи, чий час настав не раніше ніж
   * `SCHEDULE_GRACE_MS` тому. Старіші лишаються в шухляді — їх пропустили,
   * поки PHANTOM був закритий, і вирішує людина, а не таймер.
   */
  sendDueScheduled: (nowMs: number) => void;
  /** Телефон: список відкладених з апарата (там їх шле будильник). */
  refreshScheduled: () => Promise<void>;

  // User Profile & Personas
  updateCurrentUser: (updates: Partial<UserProfile>) => void;

  // Multi-select
  toggleSelectMessage: (messageId: string) => void;
  beginReportPick: (messageId: string) => void;
  clearSelection: () => void;
  setMultiSelectMode: (enabled: boolean) => void;
  /** Замінити вибране цілком (проміжок, «усі від…»). Порожнє — вихід з вибору. */
  selectMessages: (ids: string[]) => void;

  // Modals Setters
  setProfileModalOpen: (open: boolean) => void;
  setSettingsModalOpen: (open: boolean) => void;
  setCreateChatModalOpen: (open: boolean, start?: 'group' | 'channel' | 'add') => void;
  setScheduledDrawerOpen: (open: boolean) => void;
  setGroupDetailsOpen: (open: boolean) => void;
  openLightbox: (url: string, title?: string) => void;
  closeLightbox: () => void;
  openLocationSheet: (data: LocationData) => void;
  closeLocationSheet: () => void;
  openForwardModal: (msg: Message | Message[]) => void;
  closeForwardModal: () => void;

  /** Стрічку ще не забрано з вузла — показувати як «завантаження», не як «порожньо». */
  /** На яке повідомлення відповідаємо і яке редагуємо — стан композера. */
  replyingTo: MessageReplyInfo | null;
  editingMessage: Message | null;
  startReply: (msg: Message, quoteSelectedText?: string) => void;
  cancelReply: () => void;
  startEdit: (msg: Message) => void;
  cancelEdit: () => void;

  hydrated: boolean;
  /** Скільки листів було непрочитано на мить відкриття чату: від цього місця стоїть роздільник. */
  unreadAtOpen: Record<string, number>;
  /** Остання спроба забрати список із вузла впала — список каже це й дає «Повторити». */
  hydrateFailed: boolean;
  /**
   * Перше читання історії розмови: «немає листів» — лише після відповіді вузла.
   * 'read' — історію вже читали успішно; наступні перечитування не блимають.
   */
  historyLoad: Record<string, 'loading' | 'error' | 'read'>;
  /**
   * `openFirst: false` — лише список, без відкриття розмови: так гідратує
   * Головна ПК. Відкриття тягне історію й шле «прочитано», а людина листа
   * ще не бачила (docs/pc-report.md).
   */
  hydrateFromNode: (opts?: { openFirst?: boolean }) => Promise<void>;
  applyNodeMessage: (row: NodeMessage) => void;
  /** Рядок вузла з живої події: злити в розмову без перечитування. */
  applyLiveRow: (row: NodeMessage) => void;
  applyNodeDelete: (row: NodeMessage) => void;
  applyNodeEdit: (row: NodeMessage) => void;
  refreshConversations: () => Promise<void>;
  /**
   * Стрічка розмови з вузла. Перше відкриття (або `full`) — цілком; далі —
   * лише рядки після найбільшого відомого `seq` (`after_seq`), злиті з тим,
   * що вже є.
   */
  loadMessagesForChat: (chatId: string, opts?: { full?: boolean }) => Promise<void>;
}

let hydrationInFlight: Promise<void> | null = null;
/** Номер останнього читання історії розмови: стан ставить лише воно. */
const historyGen = new Map<string, number>();

/**
 * Межа вже прочитаного по розмовах: найбільший `seq` і id того рядка.
 * Живе в модулі, а не в листі — `seq` не частина того, що малює стрічка.
 */
const tailMarks = new Map<string, { seq: number; id: string }>();

/**
 * «Прочитано» — лише коли людина бачить месенджер (DL-2, 09.10): лист, що
 * прийшов у відкриту розмову, поки застосунок у фоні, не читаний. Межа
 * відкладається і йде на вузол, коли людина повертається, а розмова все ще
 * відкрита (`services/appForeground.ts`).
 */
const unseenReads = new Map<string, number>();
function markReadSeen(chatId: string, seq: number | undefined): void {
  if (typeof seq !== 'number') return;
  if (appSeen()) {
    void messengerApi.markRead(chatId, seq);
    return;
  }
  unseenReads.set(chatId, Math.max(seq, unseenReads.get(chatId) ?? 0));
}

/**
 * Чи можна дочитати лише хвіст. Ні, якщо:
 *  - рядка-межі в стрічці вже немає (розмову перебудовано — гідратація,
 *    видалення) — тоді `after_seq` дав би хвіст без тіла;
 *  - є власні листи, чий стан ще зміниться на вузлі (`sending`/`queued`):
 *    галочка «доїхало» приходить ЗМІНОЮ старого рядка, а не новим рядком.
 */
export function canReadTail(chat: Chat, mark: { seq: number; id: string }): boolean {
  if (!chat.messages.some((m) => m.id === mark.id)) return false;
  return !chat.messages.some((m) => m.isSelf && (m.status === 'queued' || m.status === 'sending'));
}

/**
 * Правка, стирання чи галочка міняють СТАРИЙ рядок, і хвіст після межі їх не
 * принесе: закрита розмова, де змінився вже відомий рядок (або невідомо який),
 * наступного разу читається цілком. Нові рядки хвіст бере сам.
 */
export function forgetStaleTail(chat: Chat | undefined, lineIds?: string[]): boolean {
  if (!chat) return false;
  const known = (id: string) => chat.messages.some((m) => m.id === id);
  if (lineIds && !lineIds.some(known)) return false;
  return tailMarks.delete(chat.id);
}

/** Правлений рядок вкладення несе в `body` опис файла, а слова людини — це підпис. */
function editedText(row: NodeMessage, fallback: string): string {
  if (!row.kind || row.kind === 'text') return row.body ?? fallback;
  try {
    const o = JSON.parse(row.body ?? '');
    if (o && typeof o.caption === 'string') return o.caption.trim();
  } catch {
    // не опис файла — лишаємо те, що людина набрала
  }
  return fallback;
}

/** Лише для тестів: забути межі (стор у тестах живе через усі кейси). */
export function __resetTailMarks(): void {
  tailMarks.clear();
}

/**
 * Речення, яке побачить людина, коли запис на диск не вдався.
 *
 * Взірець узятий з `InviteCard`/`CreateChatModal`: називаємо, що саме не
 * сталось і чим це загрожує написаному, і не вигадуємо причини, якої браузер
 * не назвав. Переповнене сховище — єдина причина, яку він називає надійно,
 * тож лише вона й згадується окремо.
 */
export const REPORT_PICK_MAX = 10;

const storageFailureSentence = (err: unknown): string => {
  const quota = err instanceof StorageWriteError && err.isQuota;
  const what =
    err instanceof StorageWriteError && err.what === 'scheduled'
      ? tNow('store.storeScheduled')
      : err instanceof StorageWriteError && err.what === 'userProfile'
        ? tNow('store.storeProfile')
        : tNow('store.storeDefault');
  return quota ? tNow('store.storeQuota', { what }) : tNow('store.storeLost', { what });
};

/**
 * Один шлях для всіх записів на диск.
 *
 * До цього кожен виклик `storagePersistence.save*` стояв голим промісом: сам
 * запис міг не статись, а стан однаково показував розмову збереженою. Тепер
 * запис або тихо вдається, або лишає по собі речення в `storageFailure` —
 * і жоден із двох результатів не мовчить.
 */
const persist = async (
  write: () => Promise<void>,
  set: (partial: Partial<MessengerState>) => void,
): Promise<boolean> => {
  try {
    await write();
    set({ storageFailure: null });
    return true;
  } catch (err) {
    const sentence = storageFailureSentence(err);
    console.warn('[messenger] запис на диск не вдався:', err);
    set({ storageFailure: sentence });
    useUIStore.getState().toast({ kind: 'warn', message: sentence });
    return false;
  }
};

/**
 * Запис усіх розмов на диск — згорнутий. Кожен виклик чекає того самого
 * запису: кілька листів поспіль, підтвердження вузла, правка — один
 * `JSON.stringify` усіх розмов замість одного на кожну подію. Відмова
 * доходить до кожного, хто чекав (`persist` лишає речення в `storageFailure`).
 */
const CHATS_WRITE_COALESCE_MS = 120;
let chatsWriteTimer: ReturnType<typeof setTimeout> | null = null;
let chatsWriteWaiters: Array<(ok: boolean) => void> = [];
const persistChatsSoon = (
  get: () => MessengerState,
  set: (partial: Partial<MessengerState>) => void,
): Promise<boolean> =>
  new Promise((resolve) => {
    chatsWriteWaiters.push(resolve);
    if (chatsWriteTimer) return;
    chatsWriteTimer = setTimeout(() => {
      chatsWriteTimer = null;
      const waiters = chatsWriteWaiters;
      chatsWriteWaiters = [];
      void persist(() => storagePersistence.saveChats(get().chats), set).then((ok) => waiters.forEach((w) => w(ok)));
    }, CHATS_WRITE_COALESCE_MS);
  });

/** Надгробок замість тіла. Саме повідомлення лишається — прибрати можна вміст. */
const asTombstone = (m: Message): Message => ({
  ...m,
  isDeleted: true,
  text: undefined,
  media: undefined,
  status: undefined,
});

/**
 * Превʼю в списку чатів після видалення.
 *
 * Стрічка вже показує надгробок, а список збоку однаково писав «Фото» — тобто
 * обіцяв вкладення, якого немає на жодному з дисків. Превʼю живе тим самим
 * останнім повідомленням, тож і перечитувати його треба звідти.
 */
const withFreshPreview = (chat: Chat): Chat => {
  const last = chat.messages[chat.messages.length - 1];
  if (!last) return { ...chat, lastKind: undefined, lastSnippet: undefined };
  if (!last.isDeleted) return chat;
  return { ...chat, lastKind: 'text', lastSnippet: tNow('store.deletedPreview') };
};

/** Поля розмови, які веде вузол — решта в рядку списку просто не приїжджає. */
const NODE_OWNED: (keyof Chat)[] = [
  'title', 'handle', 'avatar', 'type', 'circle', 'contactVerified', 'contactKeyChanged', 'contactKeyChangedAt', 'contactKeyHold', 'disappearSeconds', 'isDemo',
  'peerNodeId', 'unreadCount', 'lastKind', 'lastSnippet', 'lastAuthor', 'lastAt', 'lastCard',
  'lastSelfAt', 'lastPeerAt', 'letters30d', 'description', 'pinnedMessage', 'standing', 'liveRoom', 'plan', 'mine', 'addedAt',
];

const samePin = (a: Chat['pinnedMessage'], b: Chat['pinnedMessage']): boolean =>
  (a ?? null) === (b ?? null) ||
  (!!a && !!b && a.id === b.id && a.authorId === b.authorId && a.author === b.author && a.at === b.at && (a.text ?? null) === (b.text ?? null));

const sameLastCard = (a: Chat['lastCard'], b: Chat['lastCard']): boolean =>
  (a ?? null) === (b ?? null) ||
  (!!a && !!b && JSON.stringify(a) === JSON.stringify(b));

const sameLive = (a: Chat['liveRoom'], b: Chat['liveRoom']): boolean =>
  (a ?? null) === (b ?? null) ||
  (!!a && !!b && a.callId === b.callId && a.startedAt === b.startedAt && a.mine === b.mine &&
    a.faces.join(',') === b.faces.join(','));

const samePlan = (a: Chat['plan'], b: Chat['plan']): boolean =>
  (a ?? null) === (b ?? null) ||
  (!!a && !!b && a.meetupId === b.meetupId && a.venue === b.venue && a.organizer === b.organizer &&
    a.at === b.at && a.expiresAt === b.expiresAt && a.cancelled === b.cancelled &&
    a.keptBy.join(',') === b.keptBy.join(','));

/**
 * Накладає на відому розмову те, що про неї знає вузол.
 *
 * Стрічка лишається при собі: у списку розмов повідомлень немає, і перечитати
 * їх звідти нічим. Закріплення, тиша й архів теж лишаються місцевими — вузол
 * їх не приймає (PATCH знає лише назву), тож забирати їх звідти означало б
 * відкріплювати розмову на кожному оновленні списку.
 */
/** Телефон: бандл живе у WebView застосунку, який дає міст до мешу. */
const onPhone = (): boolean =>
  typeof window !== 'undefined' && Boolean((window as unknown as { PhantomBridge?: unknown }).PhantomBridge);

/**
 * Розмова з вузла + тиша з цього браузера.
 *
 * Вузол про тишу не знає й знати не буде (PATCH розмови читає лише назву й
 * обличчя), тож без цього кроку приглушена гілка знову дзвеніла б після
 * першого ж F5 — і людина справедливо вирішила б, що кнопка не працює.
 */
/**
 * Чому байти не поїхали — українським реченням. Браузер віддає англійський
 * текст (NotReadableError: «could not be read, typically due to permission…»),
 * і на A52 саме його людина й читала під фото.
 */
export const uploadFailureWord = (err: unknown): string => {
  const name = (err as { name?: string } | null)?.name;
  if (name === 'NotReadableError' || name === 'NotFoundError') {
    return tNow('store.fileUnreadable');
  }
  const msg = (err as { message?: string } | null)?.message;
  if (msg && (/[а-яіїєґ]/i.test(msg) || msg === tNow('store.noBlobId'))) return msg;
  return tNow('store.uploadFailed');
};


/**
 * Відповідь помічника на ПК живе лише в пристрої: вузол знає питання, а не її.
 * Перечитування стрічки з вузла ставить кожну таку відповідь одразу за її питанням.
 * Відповідь, чийого питання на вузлі вже немає (стерте, очищене), зникає разом із ним.
 */
const localReplies = (local: Message[]): Message[] => local.filter((m) => m.localReply?.answers);

const withLocalAssistant = (fromNode: Message[], local: Message[]): Message[] => {
  const replies = localReplies(local);
  if (replies.length === 0) return fromNode;
  const onNode = new Set(fromNode.map((m) => m.id));
  const after = new Map<string, Message[]>();
  for (const r of replies) {
    if (onNode.has(r.id)) continue;
    const q = r.localReply!.answers;
    after.set(q, [...(after.get(q) ?? []), r]);
  }
  return fromNode.flatMap((m) => [m, ...(after.get(m.id) ?? [])]);
};

/** Що з розмови знає лише цей пристрій і що вузол не віддає: сесія помічника й його відповіді. */
const keepLocalOnly = (node: Chat, local: Chat | undefined): Chat => {
  if (!local) return node;
  const replies = localReplies(local.messages);
  if (!local.sessionId && replies.length === 0) return node;
  return {
    ...node,
    ...(local.sessionId ? { sessionId: local.sessionId } : {}),
    ...(replies.length ? { messages: [...node.messages, ...replies] } : {}),
  };
};

const fromNode = (row: NodeConversation): Chat => {
  const node = chatFromNode(row);
  // Закріплене людиною переживає перезавантаження — див. chatPins.
  const pinned = chatPins.apply(node.id, node.pinned);
  const chat = pinned === Boolean(node.pinned) ? node : { ...node, pinned };
  const hushed = chatHush.has(chat.id) ? { ...chat, muted: true } : chat;
  return chatArchive.has(chat.id) ? { ...hushed, archived: true } : hushed;
};

const withNodeFields = (local: Chat, row: NodeConversation, activeChatId: string): Chat => {
  const node = chatFromNode(row);
  // Превʼю могло щойно змінити власне надіслане, якого вузол ще не порахував:
  // тоді свіжіше саме місцеве, і відкочувати його назад нема за що.
  const nodeSawLast = !local.lastAt || (node.lastAt ?? '') >= local.lastAt;
  const merged: Chat = {
    ...local,
    title: node.title,
    handle: node.handle,
    mine: node.mine,
    addedAt: node.addedAt,
    // Порожня обкладинка з вузла — це «не зберігаю», а не «зітри».
    // Помічник обкладинки не має взагалі: збережена в пристрої «⚡» не воскресає.
    avatar: isAssistantKind(row.kind)
      ? ''
      : row.kind === 'group' || row.kind === 'channel'
        ? node.avatar
        : node.avatar || local.avatar,
    type: node.type,
    circle: node.circle,
    circles: Array.isArray(node.circles) ? node.circles : undefined,
    contactVerified: node.contactVerified,
    contactKeyChanged: node.contactKeyChanged,
    contactKeyChangedAt: node.contactKeyChangedAt,
    contactKeyHold: node.contactKeyHold,
    // Строк зникнення ставить і співрозмовник — вузол знає чинний.
    disappearSeconds: node.disappearSeconds,
    isDemo: node.isDemo,
    peerNodeId: node.peerNodeId ?? local.peerNodeId,
    // Відкриту розмову клієнт уже розчитав, а markRead міг ще не доїхати —
    // інакше значок непрочитаного вертався б просто від оновлення списку.
    unreadCount: local.id === activeChatId ? 0 : node.unreadCount,
    // Числа спокійного потоку — лише вузлові: місцевий лист їх не рахує.
    lastSelfAt: node.lastSelfAt,
    lastPeerAt: node.lastPeerAt,
    letters30d: node.letters30d,
    description: node.description,
    standing: node.standing ?? local.standing,
    pinnedMessage: samePin(local.pinnedMessage, node.pinnedMessage) ? local.pinnedMessage : node.pinnedMessage,
    liveRoom: sameLive(local.liveRoom, node.liveRoom) ? local.liveRoom : node.liveRoom,
    plan: samePlan(local.plan, node.plan) ? local.plan : node.plan,
    ...(nodeSawLast
      ? {
          lastKind: node.lastKind,
          lastCard: sameLastCard(local.lastCard, node.lastCard) ? local.lastCard : node.lastCard,
          lastSnippet: node.lastSnippet,
          lastAuthor: node.lastAuthor,
          lastAt: node.lastAt,
        }
      : {}),
  };
  // Незмінене повертаємо тим самим обʼєктом — список не перемальовується дарма.
  return NODE_OWNED.some((k) => merged[k] !== local[k]) ? merged : local;
};

/**
 * Скільки після свого часу відкладений лист ще їде сам. Диспетчер дивиться
 * кожні 10 с, тож хвилин вистачає з запасом; лист, чий час минув давніше
 * (компʼютер спав, PHANTOM був закритий), сам не їде — «буду за пʼять хвилин»,
 * надіслане через три години, гірше за ненадіслане. Його вирішує людина.
 */
export const SCHEDULE_GRACE_MS = 10 * 60_000;

/**
 * Мить відправки — з дня й години листа, за місцевим часом. null — часу не
 * прочитати (запис старого формату «Сьогодні о 18:00»): такий лист чекає
 * людини в шухляді, а не вгадування.
 */
export function scheduledDueMs(s: Pick<ScheduledMessage, 'scheduledDate' | 'scheduledExactTime'>): number | null {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.scheduledDate ?? '');
  const t = /^(\d{1,2}):(\d{2})$/.exec(s.scheduledExactTime ?? '');
  if (!d || !t) return null;
  const at = new Date(Number(d[1]), Number(d[2]) - 1, Number(d[3]), Number(t[1]), Number(t[2]), 0, 0);
  return Number.isNaN(at.getTime()) ? null : at.getTime();
}

/** Телефон: відкладені лежать на апараті й ідуть будильником, а не цією вкладкою. */
export const scheduledOnDevice = (): boolean => __PHONE_BUILD__ || onPhone();

export type ScheduledRepeat = NonNullable<ScheduledMessage['repeat']>;
const SCHED_REPEATS: readonly string[] = ['daily', 'weekly', 'monthly', 'weekdays'];
export interface ScheduledDraftIn {
  chatId: string;
  text: string;
  date: string;
  time: string;
  repeat?: ScheduledRepeat;
  whenOnline?: boolean;
}

const pad2 = (n: number) => String(n).padStart(2, '0');
export const scheduledFromDevice = (l: NodeScheduledLetter, chats: Chat[]): ScheduledMessage => {
  const at = new Date(l.due_at_ms);
  const online = l.when_online === true;
  const date = online ? '' : `${at.getFullYear()}-${pad2(at.getMonth() + 1)}-${pad2(at.getDate())}`;
  const time = online ? '' : `${pad2(at.getHours())}:${pad2(at.getMinutes())}`;
  const chat = chats.find((c) => c.id === l.conversation_id);
  return {
    id: l.id,
    chatId: l.conversation_id,
    chatTitle: chat?.title ?? '',
    chatAvatar: chat?.avatar,
    text: l.text,
    type: 'text',
    scheduledTime: `${date} ${time}`,
    scheduledDate: date,
    scheduledExactTime: time,
    createdAt: new Date(l.created_at_ms).toISOString(),
    ...(l.failure ? { failure: l.failure } : {}),
    ...(l.repeat && SCHED_REPEATS.includes(l.repeat) ? { repeat: l.repeat as ScheduledRepeat } : {}),
    ...(online ? { whenOnline: true } : {}),
  };
};

const scheduleRefused = (err: unknown) =>
  useUIStore.getState().toast({ kind: 'warn', message: err instanceof Error && err.message ? err.message : tNow('scheduledMessagesDrawer.saveFailed') });

let draftOwner = '';
/** Обраний шматок цитати їде дротом лише разом із відповіддю. */
function qtOf(reply: MessageReplyInfo | undefined | null): { qt?: string } {
  const q = reply?.id ? quoteForWire(reply.quoteSelectedText) : undefined;
  return q ? { qt: q } : {};
}

/** Чий пересланий лист: автор оригіналу (а не той, хто переслав посередині). «Як свій» — нічий. */
export function forwardAuthor(msg: Message, asOwn?: boolean): { name: string; id: string } | undefined {
  if (asOwn) return undefined;
  const name = (msg.forwardFrom?.senderName || msg.senderName || '').trim().slice(0, 64);
  if (!name) return undefined;
  const id = (msg.forwardFrom ? msg.forwardFrom.senderId ?? '' : msg.senderId ?? '').slice(0, 128);
  return { name, id };
}

function bootDrafts(): Record<string, string> {
  try {
    draftOwner = useProfiles.getState().activeId;
    return loadDrafts(draftOwner);
  } catch {
    return {};
  }
}

export const useMessengerStore = create<MessengerState>((set, get) => {
  chatHush.nameWith((id) => get()?.chats.find((c) => c.id === id)?.title || undefined);
  if (typeof window !== 'undefined') {
    (window as unknown as { __phantomHushChanged?: () => void }).__phantomHushChanged = () => void chatHush.pull();
  }
  chatHush.onPulled(() => {
    const chats = get()?.chats;
    if (!chats) return;
    let moved = false;
    const next = chats.map((c) => {
      const muted = chatHush.has(c.id);
      if (Boolean(c.muted) === muted) return c;
      moved = true;
      return { ...c, muted };
    });
    if (moved) set({ chats: next });
  });

  /**
   * Слово в КОНКРЕТНУ розмову, не у відкриту. `sendMessage` знає лише активну
   * (і забирає з собою відповідь, чернетку, помічника) — відкладений лист
   * цього не має торкатись: людина в цей час може писати зовсім іншим.
   * Дорога та сама, що й у слова: вузол (`appendMessage`) і його галочка.
   */
  const deliverText = (chatId: string, body: string, line?: ChannelLine): void => {
    const me = get().currentUser;
    const now = new Date();
    const clientId = `c_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
    const msg: Message = {
      id: clientId,
      senderId: me.id,
      senderName: me.name,
      senderAvatar: me.avatar,
      timestamp: clockLabel(now),
      type: 'text',
      text: body,
      isSelf: true,
      status: 'sending',
      transport: 'server',
      ...(line ? { channelId: line.channelId, sentAt: now.toISOString(), ...(line.to ? { channelTo: line.to } : {}), ...(line.replyTo ? { replyTo: line.replyTo } : {}) } : {}),
    };
    const patch = (fn: (m: Message) => Message) =>
      set((s) => ({
        chats: s.chats.map((c) => (c.id === chatId ? { ...c, messages: c.messages.map((m) => (m.id === clientId ? fn(m) : m)) } : c)),
      }));
    soundFx.playSend();
    set((s) => ({
      chats: s.chats.map((c) =>
        c.id === chatId
          ? { ...c, messages: [...c.messages, msg], lastKind: 'text', lastSnippet: plainOrderText(body).slice(0, 90), lastAuthor: SELF_AUTHOR, lastAt: now.toISOString() }
          : c,
      ),
    }));
    void messengerApi
      .appendMessage(chatId, {
        client_id: clientId,
        author_id: me.id,
        author_name: me.name,
        kind: 'text',
        body,
        transport: 'server',
        reply_to_id: line?.replyTo?.id ?? null,
        ...qtOf(line?.replyTo),
        ...(line ? { channel_id: line.channelId, ...(line.to ? { to: line.to } : {}) } : {}),
      })
      .then((row) => {
        const status = deliveryStatus(row.delivery ?? row.delivery_state) || 'sent';
        patch((m) => ({ ...m, id: row.id || clientId, status }));
      })
      .catch((err) => {
        console.warn('[messenger] вузол не взяв відкладений лист:', err);
        patch((m) => ({ ...m, status: 'failed', retryCount: 0, lastAttemptAtMs: Date.now() }));
      });
    void persistChatsSoon(get, set);
  };

  // Connect network engine listeners
  messengerNetworkEngine.onMessage((chatId, msg) => {
    set((state) => {
      const chats = state.chats.map((c) => {
        if (c.id === chatId) {
          // The authenticated WS channel echoes a message to every online
          // device, including the sender. The REST/P2P path may already have
          // inserted the same id locally, so never append the echo twice.
          if (c.messages.some((existing) => existing.id === msg.id)) return c;
          if (msg.senderId !== state.currentUser.id) {
            // Тиша по розмові нарешті щось означає: до 16.09 `muted` писався і
            // не читався НІДЕ, тож дзвіночок лунав і з приглушеної гілки.
            signalIncoming(c, msg, Date.now(), get().currentUser);
          }
          return {
            ...c,
            unreadCount: msg.senderId === state.currentUser.id || c.id === state.activeChatId
              ? 0
              : c.unreadCount + 1,
            messages: [...c.messages, msg],
            lastAt: msg.sentAt || new Date().toISOString(),
            lastAuthor: msg.senderId === state.currentUser.id ? SELF_AUTHOR : msg.senderName,
            lastKind: msg.type,
            lastSnippet: plainOrderText(msg.text) || c.lastSnippet,
          };
        }
        return c;
      });
      return { chats };
    });
  });

  messengerNetworkEngine.onReaction((chatId, messageId, emoji, fromUserId, action = 'add') => {
    set((state) => {
      const chats = state.chats.map((c) => {
        if (c.id !== chatId) return c;
        const messages = c.messages.map((m) => {
          if (m.id !== messageId) return m;
          return { ...m, reactions: applyReaction(m.reactions, emoji, fromUserId, action) };
        });
        return { ...c, messages };
      });
      return { chats };
    });
  });

  messengerNetworkEngine.onMessageAck((ack) => {
    set((state) => ({
      chats: state.chats.map((chat) => chat.id !== ack.chatId ? chat : {
        ...chat,
        messages: chat.messages.map((message) =>
          message.id === ack.clientId
            ? { ...message, status: 'sent', transport: ack.transport }
            : message,
        ),
      }),
    }));
  });

  // Канал вузла ПК. `message:new` доти не слухав ніхто — новий лист
  // зʼявлявся лише з наступним перечитуванням розмови (services/messengerLive.ts).
  wsClient.on('messenger', (event) =>
    routeMessengerEvent(event, {
      onNew: (row) => get().applyLiveRow(row),
      onEdited: (row) => get().applyNodeEdit(row),
      onDeleted: (row) => get().applyNodeDelete(row),
    }),
  );

  // Читаємо тими самими ключами, якими пише `storagePersistence`.
  //
  // Тут стояло `loadSetting('phantom_chats_backup')` — а `loadSetting` додає
  // власний префікс і шукав `phantom_setting_phantom_chats_backup`. Ключа з
  // таким іменем не писав ніхто, тож умова «є збережені чати» не була істинною
  // жодного разу, і кожен запуск підсовував початкову сівбу замість розмов
  // власника. Запис при цьому справно відбувався — просто в інші двері.
  const persistedUser = storagePersistence.getUserProfileSync();
  const persistedChats = storagePersistence.getChatsSync();
  const persistedScheduled = storagePersistence.getScheduledSync();

  const activeUser = persistedUser || defaultUser;
  userMarks.adoptFromCache(persistedChats, activeUser.id);
  const activeChats = persistedChats && persistedChats.length > 0
    ? persistedChats.map((c) => ({ ...c, messages: userMarks.apply(c.messages, activeUser.id) }))
    : initialChats;
  const activeScheduled = persistedScheduled || defaultScheduled;

  return {
    currentUser: activeUser,
    profilePhotos: {},
    chats: activeChats,
    // Порожній рядок, а не запасний ідентифікатор вигаданої розмови.
    // Тут стояло 'chat_aura_design' — коли сівбу прибрали, стор почав
    // вказувати на розмову, якої немає, і перший же читач її імені впав.
    // Запасний варіант, що показує на неіснуюче, гірший за чесну порожнечу.
    // На телефоні (є міст) перший екран — список, а не перша-ліпша розмова:
    // людина, що щойно зайшла, не має опинятись у чужому чаті без свого дотику.
    activeChatId: onPhone() ? '' : activeChats[0]?.id ?? '',
    pendingJump: null,
    activeCircle: 'all',
    scheduledMessages: activeScheduled,
    searchQuery: '',

    multiSelectMode: false,
    reportPick: false,
    selectedMessageIds: [],
    selectionAnchorId: null,

    isProfileModalOpen: false,
    isSettingsModalOpen: false,
    isCreateChatModalOpen: false,
    createChatStart: null,
    isScheduledDrawerOpen: false,
    isGroupDetailsOpen: false,
    isForwardModalOpen: false,
    isMediaLightboxOpen: false,
    activeLightboxUrl: '',
    activeLightboxTitle: '',
    activeLocationData: null,
    activeForwardMessage: null,
    activeForwardMessages: [],

    drafts: bootDrafts(),
    storageFailure: null,
    typingUsers: {},
    typingStatus: {},
    setTypingStatus: (chatId, status) =>
      set((s) => ({
        typingStatus: { ...s.typingStatus, [chatId]: status },
      })),
    replyingTo: null,
    editingMessage: null,
    hydrated: false,
    unreadAtOpen: {},
    hydrateFailed: false,
    historyLoad: {},

    startReply: (msg, quoteSelectedText) => {
      soundFx.playTap();
      set({
        editingMessage: null,
        replyingTo: {
          id: msg.id,
          senderName: msg.senderName,
          text: msg.text || '',
          type: msg.type,
          quoteSelectedText,
          isSelf: msg.isSelf || undefined,
        },
      });
    },
    cancelReply: () => set({ replyingTo: null }),
    startEdit: (msg) => {
      soundFx.playTap();
      set({ replyingTo: null, editingMessage: msg });
    },
    cancelEdit: () => set({ editingMessage: null }),

    // Джерело правди — вузол. Мок-розмови лишаються тільки як перший засів
    // списку: їх історія була вигадана, тож у базу вона не їде.
    hydrateFromNode: async (opts) => {
      const openFirst = opts?.openFirst ?? true;
      // Подвійний монтаж у dev смикав це двічі, обидва виклики бачили порожній
      // список і засівали розмови по другому колу. Тепер рішення ухвалює вузол.
      if (hydrationInFlight) return hydrationInFlight;
      hydrationInFlight = (async () => {
      if (get().hydrateFailed) set({ hydrateFailed: false });
      void chatHush.pull();
      let listed = false;
      try {
        // «Я» — це ключ вузла (телефона чи ПК), а не збережений профіль веба.
        try {
          const me = await messengerApi.identity();
          applyMediaLimitFromNode(me.max_attachment_bytes);
          const name = String((me as { display_name?: unknown }).display_name || (me.bundle as { name?: unknown })?.name || '').trim();
          const own = (me as { avatar?: unknown }).avatar;
          set((s2) => {
            const avatar =
              typeof own === 'string' && (own.startsWith('data:image/') || isFacePhotoUrl(own))
                ? own
                : own === null
                  ? ''
                  : s2.currentUser.avatar;
            return {
              currentUser: {
                ...s2.currentUser,
                id: me.node_id || s2.currentUser.id,
                name: name || s2.currentUser.name,
                avatar,
              },
              profilePhotos: profilePhotosOf((me as { profile_photos?: unknown }).profile_photos),
            };
          });
        } catch { /* ключа ще немає — лишаємо як є, не вигадуємо */ }
        // Показових розмов не засіваємо ніде. Тут жила «вітрина» ПК: розмови
        // з позначкою is_demo і трьома репліками мока, а прибирати їх людина
        // мала в «Я» групою «Доказові розмови». Мок давно порожній, тож сівба
        // засівала ніщо, а група лишалась — тепер немає ні того, ні іншого.
        // Зайву розмову прибирають там, де й будь-яку: дія над розмовою.
        const rows = await messengerApi.bootstrap([]);
        set((s2) => {
          const local = new Map(s2.chats.map((c) => [c.id, c]));
          const chats = rows.map(fromNode).map((c) => keepLocalOnly(c, local.get(c.id)));
          return {
          chats,
          hydrated: true,
          hydrateFailed: false,
          activeChatId: chats.some((c) => c.id === s2.activeChatId)
            ? s2.activeChatId
            : onPhone() || !openFirst ? '' : chats[0]?.id ?? '',
          };
        });
        listed = true;
        const active = get().activeChatId;
        if (active) await get().loadMessagesForChat(active);
      } catch (err) {
        // Вузол недоступний — кажемо про це станом, а не підсовуємо мок як живу стрічку.
        console.warn('[messenger] стрічку з вузла не отримано:', err);
        set({ hydrated: false, hydrateFailed: !listed });
      } finally {
        hydrationInFlight = null;
      }
      })();
      return hydrationInFlight;
    },

    /**
     * Звіряє список із вузлом: дотягує розмови, яких клієнт ще не бачив, і
     * оновлює те, що вузол знає про вже відомі — не чіпаючи завантажені стрічки.
     *
     * Раніше нове імʼя, записане на вузлі, було видно лише після F5: людина
     * вписувала «Марта», вузол уже віддавав «Марта», а в шапці лишався «Вузол
     * 86a15538» — тобто на вигляд її напис просто викидали.
     */
    refreshConversations: async () => {
      try {
        const rows = await messengerApi.listConversations();
        set((s2) => {
          const byId = new Map(rows.map((r) => [r.id, r]));
          const known = new Set(s2.chats.map((c) => c.id));
          const fresh = rows.filter((r) => !known.has(r.id)).map(fromNode);
          const merged = s2.chats.map((c) => {
            const row = byId.get(c.id);
            return row ? withNodeFields(c, row, s2.activeChatId) : c;
          });
          const touched = merged.some((c, i) => c !== s2.chats[i]);
          if (!fresh.length && !touched) return {};
          return { chats: [...fresh, ...merged] };
        });
        // Вузол відповів — рядок «не завантажилися» вже неправда.
        if (get().hydrateFailed) set({ hydrateFailed: false });
      } catch (err) {
        console.warn('[messenger] список розмов не оновився:', err);
      }
    },

    /** Повідомлення, записане вузлом (зокрема з іншого пристрою власника або вкладки). */
    applyNodeMessage: (row: any) => {
      const selfId = get().currentUser.id;
      const selfHandle = (get().currentUser.handle || '').toLowerCase().replace(/^@/, '');
      const convId = row.conversation_id || row.chatId || row.conversationId;
      if (!convId && !row.senderId) return;

      const sHandle = (row.senderHandle || '').toLowerCase().replace(/^@/, '');
      const sId = row.senderId || row.author_id;

      // Визначаємо чи повідомлення дійсно від нас самих
      const isSelf = Boolean(
        (sId && sId === selfId) ||
        (selfHandle && sHandle && sHandle === selfHandle)
      );

      const isDirectMsg = row.senderId !== undefined && (row.text !== undefined || row.type !== undefined || row.body !== undefined);
      const msgObj: Message = isDirectMsg
        ? {
            ...row,
            id: row.id || row.client_id || `msg_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            text: row.text || row.body || '',
            type: row.type || row.kind || 'text',
            senderId: sId,
            senderName: row.senderName || row.author_name || tNow('messengerRoot.peerFallback'),
            senderAvatar: row.senderAvatar || '',
            isSelf,
            status: isSelf ? (row.status || 'sent') : undefined,
          }
        : {
            ...messageFromNode(row, selfId),
            isSelf,
          };

      // Якщо це повідомлення вже десь у стрічці — не дублюємо. Раніше ця
      // перевірка спрацьовувала лише для isSelf: чужий перший лист від
      // нового співрозмовника міг приїхати двома шляхами одночасно (жива
      // доставка і синхронізація вузла) і кожен незалежно бачив «такого
      // чату ще нема» та створював свій — два чати з тим самим msgObj.id.
      // Перевірка тепер бере ВСІ чати, не лише власні репліки.
      if (get().chats.some((c) => c.messages.some((m) => m.id === msgObj.id || (row.client_id && m.id === row.client_id)))) {
        return;
      }

      // Знаходимо цільову розмову: для вхідного DM шукаємо чат з цим відправником
      let targetChat = get().chats.find((c) => {
        const cHandle = (c.handle || '').toLowerCase().replace(/^@/, '');
        const cTitle = (c.title || '').toLowerCase();
        const cPeer = (c.peerNodeId || '').toLowerCase().replace(/^node_/, '');
        const cId = c.id.toLowerCase();

        if (!isSelf && sHandle) {
          if (cHandle === sHandle || cPeer === sHandle || cId === `chat_dm_${sHandle}` || cTitle === sHandle) {
            return true;
          }
        }

        return (
          c.id === convId ||
          (sHandle && (cHandle === sHandle || cPeer === sHandle || cId === `chat_dm_${sHandle}` || cTitle === sHandle)) ||
          (row.senderName && (cTitle === row.senderName.toLowerCase() || cTitle.includes(row.senderName.toLowerCase()) || row.senderName.toLowerCase().includes(cTitle)))
        );
      });

      // Якщо бесіди ще немає в списку чатів отримувача — створюємо її автоматично
      if (!targetChat) {
        const newChatId = `chat_dm_${sHandle || sId || Date.now()}`;
        const newChat: Chat = {
          id: newChatId,
          title: row.senderName || (sHandle ? `@${sHandle}` : tNow('messengerRoot.peerFallback')),
          handle: sHandle ? `@${sHandle}` : undefined,
          avatar: row.senderAvatar || '',
          type: 'dm',
          circle: 'all',
          // «В мережі» тут не з чого взяти: лист міг пролежати в поштовій
          // скриньці добу, а зелена крапка казала б, що людина зараз читає.
          peerNodeId: sId || `node_${sHandle || 'peer'}`,
          unreadCount: 1,
          messages: [msgObj],
          lastKind: msgObj.type || 'text',
          lastSnippet: msgObj.text ? plainOrderText(msgObj.text).slice(0, 90) : undefined,
          lastAuthor: msgObj.senderName || tNow('messengerRoot.peerFallback'),
          lastAt: msgObj.sentAt || new Date().toISOString(),
        };

        if (!isSelf) signalIncoming(newChat, msgObj, Date.now(), get().currentUser);
        set((s) => ({ chats: [newChat, ...s.chats] }));
        return;
      }

      const targetId = targetChat.id;
      const isActive = get().activeChatId === targetId;
      if (isActive && typeof row.seq === 'number') markReadSeen(targetId, row.seq);

      if (!isSelf) signalIncoming(targetChat, msgObj, Date.now(), get().currentUser);

      set((s2) => ({
        chats: s2.chats.map((c) => {
          if (c.id !== targetId) return c;
          c = {
            ...c,
            lastKind: msgObj.type || 'text',
            lastSnippet: msgObj.text ? plainOrderText(msgObj.text).slice(0, 90) : undefined,
            lastAuthor: msgObj.senderName || tNow('messengerRoot.peerFallback'),
            lastAt: msgObj.sentAt || new Date().toISOString(),
          };
          if (c.messages.some((m) => m.id === msgObj.id || (row.client_id && m.id === row.client_id))) {
            return {
              ...c,
              messages: c.messages.map((m) =>
                m.id === (row.client_id || msgObj.id)
                  ? { ...m, id: msgObj.id, status: 'sent' }
                  : m,
              ),
            };
          }
          return {
            ...c,
            unreadCount: c.id === s2.activeChatId ? 0 : c.unreadCount + 1,
            messages: [...c.messages, msgObj],
          };
        }),
      }));
    },

    loadMessagesForChat: async (chatId, opts) => {
      const markHistory = (state: 'loading' | 'error' | 'read') =>
        set((s2) => {
          const now = s2.historyLoad[chatId];
          if (now === state) return s2;
          if (state !== 'read') {
            // Розмову вже читали чи в ній є листи — тихе перечитування нічого поверх не малює.
            if (now === 'read' || s2.chats.find((c) => c.id === chatId)?.messages.length) return s2;
          }
          return { historyLoad: { ...s2.historyLoad, [chatId]: state } };
        });
      const gen = (historyGen.get(chatId) ?? 0) + 1;
      historyGen.set(chatId, gen);
      markHistory('loading');
      let failed = false;
      try {
        // «Збережене» — розмова вузла. Нотатки, що лежали лише в IndexedDB,
        // переїжджають туди один раз; далі стрічку читаємо ТІЛЬКИ з вузла,
        // навіть порожню.
        const savedNotes = chatId === SAVED_NOTES_ID;
        if (savedNotes) await importSavedNotesOnce();
        // ДОЧИТУВАННЯ замість перечитування. Кожне повторне відкриття розмови
        // тягло всі її рядки (до 200) і будувало кожен лист наново, хоча
        // нового там міг бути один. Хвіст безпечний, лише коли нічого з уже
        // відомого не може змінитись мовчки (див. `canReadTail`).
        const before = get().chats.find((c) => c.id === chatId);
        const mark = tailMarks.get(chatId);
        if (!opts?.full && before && mark && canReadTail(before, mark)) {
          const tail = await messengerApi.listMessages(chatId, mark.seq);
          // Межа повернулась з іншим seq: стрічку перенумеровано (рядок ліг
          // раніше межі), і хвіст пропустив би вставлене — читаємо цілком.
          const renumbered = !!tail?.some((r) => r.id === mark.id && (r.seq ?? 0) !== mark.seq);
          if (!renumbered) {
            if (!tail || tail.length === 0) {
              if (before.unreadCount) set((s2) => ({ chats: s2.chats.map((c) => (c.id === chatId ? { ...c, unreadCount: 0 } : c)) }));
              return;
            }
            const selfId = get().currentUser.id;
            set((s2) => ({
              chats: s2.chats.map((c) => {
                if (c.id !== chatId) return c;
                const messages = keepMessageIdentity(
                  c.messages,
                  mergeRows(c.messages, tail, [], (r) => messageFromNode(r, selfId, c.peerNodeId), {
                    self: tNow('chatList.you'),
                    peer: c.title ?? '',
                  }),
                );
                return { ...c, unreadCount: 0, messages };
              }),
            }));
            const lastTail = tail.reduce((a, r) => ((r.seq ?? 0) > (a.seq ?? 0) ? r : a), tail[0]);
            tailMarks.set(chatId, { seq: Math.max(mark.seq, lastTail.seq ?? 0), id: lastTail.id });
            markReadSeen(chatId, lastTail.seq);
            return;
          }
          tailMarks.delete(chatId);
        }
        const rows = await messengerApi.listMessages(chatId);
        if (!savedNotes && rows && rows.length === 0 && before && localReplies(before.messages).length) {
          set((s2) => ({ chats: s2.chats.map((c) => (c.id === chatId ? { ...c, messages: c.messages.filter((m) => !m.localReply) } : c)) }));
        }
        if (savedNotes && rows && rows.length === 0) {
          set((s2) => ({ chats: s2.chats.map((c) => (c.id === chatId ? { ...c, messages: [] } : c)) }));
        }
        if (rows && rows.length > 0) {
          const selfId = get().currentUser.id;
          const chat = get().chats.find((c) => c.id === chatId);
          const peer = chat?.peerNodeId;
          const loadedMsgs = withReplies(
            rows,
            rows.map((r) => messageFromNode(r, selfId, peer)),
            { self: tNow('chatList.you'), peer: chat?.title ?? '' },
          );
          // Ідентифікатори рядків вузла — множиною: пошук `rows.some` для
          // кожного p2p-листа був O(листів × рядків) на кожне перечитування.
          const onNode = new Set<string>();
          for (const r of rows) {
            onNode.add(r.id);
            if (r.client_id) onNode.add(r.client_id);
          }
          set((s2) => {
            let touched = false;
            const chats = s2.chats.map((c) => {
              if (c.id !== chatId) return c;
              // Свої листи, яких вузол ще не бачив (вкладення вивантажується,
              // POST у дорозі, або лист не поїхав і чекає повтору), —
              // переживають перечитування. Інакше оптимістична бульбашка
              // зникала б посеред вивантаження, а «failed» — разом із кнопкою
              // повтору.
              const existingP2P = c.messages.filter((m) =>
                !onNode.has(m.id) &&
                (m.transport === 'p2p' || (m.isSelf && (m.status === 'sending' || m.status === 'failed'))));
              // МІСЦЕВА ПОЗНАЧКА ПЕРЕЖИВАЄ ПЕРЕЧИТУВАННЯ СТРІЧКИ.
              //
              // `isPinned` живе лише в цій вкладці, і це нормально: дроту для
              // «закріпити в себе» немає й не треба — для гілки є справжній
              // акт (`PeerGroup.Act.Pin`). Але стрічка перечитується з вузла
              // ЦІЛКОМ, а кличе це `__phantomMeshChanged` на кожну зміну
              // журналу — тобто закріплене зникало з наступним листом. Те
              // саме мовчазне зникання, що було в реакцій; лише його ніхто не
              // назвав, бо кнопка при цьому справно блимала.
              const pinnedLocally = new Set(c.messages.filter((m) => m.isPinned).map((m) => m.id));
              // Спільне закріплення 1:1 (`pinShared`) приходить правдою журналу.
              const kept = userMarks.apply(pinnedLocally.size === 0
                ? loadedMsgs
                : loadedMsgs.map((m) => (pinnedLocally.has(m.id) && !m.pinShared ? { ...m, isPinned: true } : m)), s2.currentUser.id);
              // Незмінені листи лишаються тими самими обʼєктами — інакше кожне
              // перечитування будило б усі рядки стрічки (`messageIdentity.ts`).
              const messages = keepMessageIdentity(c.messages, [...withLocalAssistant(kept, c.messages), ...existingP2P]);
              if (messages === c.messages && c.unreadCount === 0) return c;
              touched = true;
              return { ...c, unreadCount: 0, messages };
            });
            // Нічого не змінилось — стан не чіпаємо, і жоден підписник не прокидається.
            return touched ? { chats } : s2;
          });
          const last = rows[rows.length - 1];
          if (last) {
            tailMarks.set(chatId, { seq: last.seq ?? 0, id: last.id });
            markReadSeen(chatId, last.seq);
          }
        }
      } catch (err) {
        console.warn('[messenger] історію розмови не отримано:', err);
        failed = true;
      } finally {
        // Відповідь старшого читання не перекриває новішого.
        if (historyGen.get(chatId) === gen) markHistory(failed ? 'error' : 'read');
      }
    },

    // Selectors
    getActiveChat: () => {
      const s = get();
      return s.chats.find((c) => c.id === s.activeChatId);
    },
    getScheduledForActiveChat: () => {
      const s = get();
      return s.scheduledMessages.filter((m) => m.chatId === s.activeChatId);
    },

    // Chat navigation & circle setters
    openChatAtMessage: (chatId, messageId) => {
      set({ pendingJump: { chatId, messageId } });
      get().setActiveChat(chatId);
    },
    clearPendingJump: () => set({ pendingJump: null }),

    setActiveChat: (id) => {
      soundFx.playTap();
      void get().loadMessagesForChat(id);
      // Рядок вузла складу не несе: без цього шапка групи порожня після перезапуску.
      const kind = get().chats.find((c) => c.id === id)?.type;
      if (kind === 'group' || kind === 'channel') void get().loadGroupMembers(id);
      set((state) => ({
        activeChatId: id,
        unreadAtOpen: { ...state.unreadAtOpen, [id]: state.chats.find((c) => c.id === id)?.unreadCount ?? 0 },
        chats: state.chats.map((c) => (c.id === id ? { ...c, unreadCount: 0 } : c)),
      }));
    },
    setActiveCircle: (circle) => set({ activeCircle: circle }),
    setSearchQuery: (query) => set({ searchQuery: query }),
    setDraft: (chatId, text) =>
      set((state) => ({
        drafts: { ...state.drafts, [chatId]: text },
      })),

    updateChat: (chatId, updates) => {
      // Очищена розмова — кінець і розмови з моделлю: інакше та процитувала б стерте.
      const cleared = Array.isArray(updates.messages) && updates.messages.length === 0;
      set((state) => ({
        chats: state.chats.map((c) => (c.id === chatId ? { ...c, ...updates, ...(cleared ? { sessionId: undefined } : {}) } : c)),
      }));
    },

    togglePinChat: (chatId) => {
      soundFx.playTap();
      const pinned = !get().chats.find((c) => c.id === chatId)?.pinned;
      chatPins.set(chatId, pinned);
      set((state) => ({
        chats: state.chats.map((c) => (c.id === chatId ? { ...c, pinned } : c)),
      }));
    },

    toggleMuteChat: (chatId) => {
      soundFx.playTap();
      // Пишемо ще й у сховище: тиша місцева (вузол її не приймає), тож без
      // цього вона зникала б із кожним перезавантаженням вкладки.
      const quiet = !get().chats.find((c) => c.id === chatId)?.muted;
      chatHush.set(chatId, quiet);
      set((state) => ({
        chats: state.chats.map((c) => (c.id === chatId ? { ...c, muted: quiet } : c)),
      }));
    },

    toggleArchiveChat: (chatId) => {
      soundFx.playTap();
      const on = !get().chats.find((c) => c.id === chatId)?.archived;
      chatArchive.set(chatId, on);
      set((state) => ({
        chats: state.chats.map((c) => (c.id === chatId ? { ...c, archived: on } : c)),
      }));
      if (on) {
        useUIStore.getState().toast({
          kind: 'info',
          message: tNow('archiveToast.moved'),
          action: {
            label: tNow('archiveToast.undo'),
            run: () => {
              if (get().chats.find((c) => c.id === chatId)?.archived) get().toggleArchiveChat(chatId);
            },
          },
        });
      }
    },

    setChatCircle: async (chatId, circle) => {
      const row = await messengerApi.setCircle(chatId, circle).catch(() => null);
      if (!row) return false;
      const next = (row.circle as ChatCircle) || 'all';
      const circles = Array.isArray(row.circles) ? row.circles : next === 'all' ? [] : [next];
      set((state) => ({ chats: state.chats.map((c) => (c.id === chatId ? { ...c, circle: next, circles } : c)) }));
      return true;
    },

    createGroup: async (title, circle = 'work', avatar, description, memberIds = [], kind = 'group') => {
      const defaultAvatar = avatar || '';

      // Спершу вузол — і лише потім список.
      //
      // Тут був запасний шлях: при відмові вузла група лишалась у памʼяті
      // вкладки з `console.warn('node offline / demo mode')`, ідентифікатором
      // з годинника (`chat_grp_<мс>`), якого вузол не знає, — тож кожен лист
      // у неї повертався помилкою. Людина бачила створений простір і німоту.
      // Відмова — це відмова: нічого не створюється, речення каже причину.
      const row = await messengerApi
        .createConversation({
          title,
          kind,
          circle,
          avatar: defaultAvatar,
          ...(description ? { description } : {}),
          // Без людей міст відмовляє 422 `no_members` — і правильно робить:
          // група зʼявляється тоді, коли поїхав її ПЕРШИЙ лист, а слати його
          // нікуди. Раніше веб не віз учасників узагалі, тож створення групи
          // не працювало ніде — просто на ПК це було тихіше.
          member_ids: memberIds,
        })
        .catch((e) => {
          throw new Error(
            sayRefusal(e, tNow('store.groupCreate')),
          );
        });
      const newChatId = row.id;
      chatKeep.add(newChatId);

      if (Array.isArray(row.dropped) && row.dropped.length > 0) {
        const names = row.dropped.map((d) => d.name || d.id).filter(Boolean).join(', ');
        useUIStore.getState().toast({
          kind: 'warn',
          message: tNow('store.groupDropped', { names }),
        });
      }

      // Складу тут не вигадуємо — його знає міст.
      //
      // Тут стояли троє: «Олександр (Lead)», «DevOps Node» і «PHANTOM Copilot»,
      // усі з зеленою крапкою «в мережі». Жодного з них не існує: ні ключа, ні
      // вузла, ні згоди — сам список членів був вигаданий разом із рахунком
      // membersCount: 4. Разом із ними пішов і системний лист про «Живий
      // Canvas рішень та Work OS віджети»: обіцянка від імені простору, який
      // щойно народився порожнім.
      //
      // Тепер `members` лишається невизначеним, доки склад не приїде з
      // `/members`: «ще не питали» — це не «нікого», і малювати одного себе
      // там, де міст назве трьох, означало б вигадати число.
      const newChat: Chat = {
        id: newChatId,
        title: row.title || title,
        type: 'group',
        circle: (row.circle && row.circle !== 'all' ? (row.circle as ChatCircle) : circle) || 'all',
        avatar: defaultAvatar,
        description: row.description || description || undefined,
        unreadCount: 0,
        pinned: false,
        muted: false,
        archived: false,
        messages: [],
        lastAt: row.created_at || new Date().toISOString(),
        groupSettings: settingsFromNode(row),
      };

      set((s) => ({
        chats: [newChat, ...s.chats],
        activeChatId: newChatId,
      }));

      // Склад — окремим шляхом: возити його на кожен рядок дорожче за саму
      // розмову, тож міст віддає імена лише коли їх спитали.
      await get().loadGroupMembers(newChatId);

      return newChatId;
    },

    loadGroupMembers: async (chatId) => {
      const row = await messengerApi.groupMembers(chatId).catch(() => null);
      // Відмова — це «не знаємо», а не «нікого»: склад лишається невизначеним,
      // і заголовок скаже саме це. Відповідь без списку (старий міст віддає
      // `{}`) — теж «не знаємо», а не падіння всього екрана.
      if (!row || !Array.isArray(row.members)) return false;
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId
            ? {
                ...c,
                members: row.members.map((m) => ({
                  id: m.id,
                  name: m.name,
                  handle: '',
                  avatar: '',
                  // Щабель приходить З ВУЗЛА, обчислений з того самого
                  // журналу, яким приймач судить акти. До 16.09 тут стояло
                  // жорстке 'member' з чесним коментарем «ієрархія тут була б
                  // намальована нами» — бо на дроті ролей справді не було.
                  // Тепер вони є, і бейдж нарешті означає те, що каже.
                  role: m.role,
                  mayRemove: m.may_remove,
                  mayGrant: m.may_grant,
                  mayOwn: m.may_own,
                  rights: Array.isArray(m.rights) ? (m.rights.filter((r) => (GROUP_RIGHTS as string[]).includes(r)) as GroupRight[]) : undefined,
                  mayRights: m.may_rights === true,
                  inRoster: m.in_roster,
                  verified: m.verified,
                  sessionReady: m.session_ready,
                  isMe: m.is_me,
                })),
                membersCount: row.members.length,
                membersAuthoritative: row.authoritative,
                membersAddable: row.addable,
                membersMax: row.max_members,
                myRole: row.my_role,
                ownerId: row.owner_id,
                powers: row.powers,
                standing: row.standing as Chat['standing'],
              }
            : c,
        ),
      }));
      return true;
    },

    addGroupMember: async (chatId, peerId) => {
      await messengerApi.addGroupMember(chatId, peerId).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupAdd')));
      });
      await get().loadGroupMembers(chatId);
    },

    removeGroupMember: async (chatId, peerId) => {
      await messengerApi.removeGroupMember(chatId, peerId).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupRemove')));
      });
      await get().loadGroupMembers(chatId);
    },

    /**
     * Щабель людини в гілці.
     *
     * Відмова тут — НЕ «не вдалось»: вузол відповідає 403 `no_right_<причина>`
     * і реченням про те, чого саме бракує. `sayRefusal` віддає це речення, і
     * саме воно доходить до людини, бо іншого тут і немає сенсу казати.
     */
    setGroupRole: async (chatId, peerId, role) => {
      await messengerApi.setGroupRole(chatId, peerId, role).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupRank')));
      });
      await get().loadGroupMembers(chatId);
    },

    describeGroup: async (chatId, about) => {
      const row = await messengerApi.describeGroup(chatId, about).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupTopic')));
      });
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId ? { ...c, description: row.description || undefined } : c,
        ),
      }));
    },

    /**
     * Закріпити рядок. Порожній `messageId` знімає закріплення.
     *
     * Вузол адресує закріплене парою «автор + мить», бо id у віяра свій на
     * кожну копію; тут це не видно й не мусить бути видно — веб дає той id,
     * який має, а пару читає вузол зі свого журналу.
     */
    pinGroupMessage: async (chatId, messageId) => {
      const row = await messengerApi.pinGroupMessage(chatId, messageId).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupPin')));
      });
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId ? { ...c, pinnedMessage: pinFromNode(row) } : c,
        ),
      }));
    },

    setGroupSettings: async (chatId, patch) => {
      const row = await messengerApi.setGroupSettings(chatId, patch).catch((e) => {
        throw new Error(sayRefusal(e, tNow('groupsTg.settingsFailed')));
      });
      const next = settingsFromNode(row);
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId && next ? { ...c, groupSettings: next } : c,
        ),
      }));
    },

    setMemberRights: async (chatId, peerId, rights) => {
      await messengerApi.setMemberRights(chatId, peerId, rights).catch((e) => {
        throw new Error(sayRefusal(e, tNow('groupsTg.rightsFailed')));
      });
      await get().loadGroupMembers(chatId);
    },

    /**
     * Перейменувати гілку — з дротом, а не в памʼяті вкладки.
     *
     * `updateChat` поруч міняє лише свою копію: назва, змінена ним, не доїхала
     * б НІКОМУ і зникла б наступним `loadGroupMembers`. Тому перейменування
     * гілки — окрема дія, яка вміє бути відмовленою.
     */
    renameGroup: async (chatId, title) => {
      const row = await messengerApi.renameConversation(chatId, title).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupRename')));
      });
      set((s) => ({
        chats: s.chats.map((c) => (c.id === chatId ? { ...c, title: row.title } : c)),
      }));
    },

    /** Обличчя гілки. Їде актом, тому теж має право й теж уміє бути відмовленим. */
    setGroupFace: async (chatId, avatar) => {
      const row = await messengerApi.setGroupFace(chatId, avatar).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupFace')));
      });
      set((s) => ({
        chats: s.chats.map((c) => (c.id === chatId ? { ...c, avatar: row.avatar ?? '' } : c)),
      }));
    },

    leaveGroup: async (chatId) => {
      const res = await messengerApi.leaveGroup(chatId).catch((e) => {
        throw new Error(sayRefusal(e, tNow('store.groupLeave')));
      });
      // Історія лишається на пристрої: піти й забути — різні дії, і плутати
      // їх нечесно. Тож розмова не зникає, а лише міняє стан.
      set((s) => ({
        chats: s.chats.map((c) => (c.id === chatId ? { ...c, standing: 'left' as const } : c)),
      }));
      return res.told;
    },

    createDirectMessage: async (name, circle = 'friends', avatar, contactId) => {
      const defaultAvatar = avatar || '';

      // Розмову відкриває вузол. Локальний `chat_dm_<мс>` при його відмові
      // був розмовою ні з ким: ні контакту, ні ключа, ні адресата.
      const row = await messengerApi
        .createConversation({
          title: name,
          kind: 'dm',
          circle,
          avatar: defaultAvatar,
          ...(contactId ? { contact_id: contactId } : {}),
        })
        .catch((e) => {
          throw new Error(
            sayRefusal(e, tNow('store.chatOpen')),
          );
        });
      const newChatId = row.id;
      chatKeep.add(newChatId);
      selfAdded.mark(newChatId);

      const newChat: Chat = {
        id: newChatId,
        title: row.title || name,
        type: 'dm',
        circle: (row.circle as ChatCircle) || 'all',
        avatar: defaultAvatar,
        peerNodeId: row.peer_node_id ?? contactId ?? undefined,
        unreadCount: 0,
        pinned: false,
        muted: false,
        archived: false,
        messages: [],
        lastAt: new Date().toISOString(),
      };

      set((s) => ({
        chats: s.chats.some((c) => c.id === newChatId) ? s.chats : [newChat, ...s.chats],
        activeChatId: newChatId,
      }));

      return newChatId;
    },

    /**
     * «Збережене» тримає вузол (id `chat_saved_notes`, першим і закріпленим),
     * тож тут лише відкриваємо його. Колись цей виклик ще й ставив
     * `activeCircle = 'saved'`, який не скидав ніхто: один дотик ховав решту
     * розмов до перезапуску. Коло тут більше не чіпаємо.
     */
    openSavedNotes: () => {
      const existing = get().chats.find((c) => c.id === SAVED_NOTES_ID);
      if (existing) {
        get().setActiveChat(existing.id);
        return;
      }
      // Вузол ще не віддав рядка (старий міст) — перепитуємо список, а не
      // малюємо локальну розмову, до якої вузол не довезе жодного листа.
      void get().refreshConversations().then(() => {
        if (get().chats.some((c) => c.id === SAVED_NOTES_ID)) get().setActiveChat(SAVED_NOTES_ID);
      });
    },

    // Message sending & modification
    sendAttachment: async (file, onProgress, caption, batch, options) => {
      const state = get();
      const chatId = state.activeChatId;
      if (!chatId) return;

      // «Фото» лише для того, що браузер справді намалює. Решта — картка
      // файла з кнопкою «Зберегти»: .psd теж має дістатись людині.
      const kind: 'image' | 'file' | 'voice' | 'video-note' = options?.kind || (isRenderableImage(file.name, file.type) ? 'image' : 'file');

      // Фото готуємо (зменшення до 2560, розміри, мініатюра) — і саме тому
      // межу для фото міряємо ПІСЛЯ: 6-мегабайтне фото камери після
      // зменшення важить ~1 МБ і має право поїхати навіть на телефоні.
      // Велике фото готуємо ДО бульбашки (рідко); звичайне — у черзі.
      let prepared: PreparedImage | null = null;
      if (kind === 'image' && file.size > MEDIA_PLAIN_LIMIT_BYTES) prepared = await prepareImage(file, undefined, { original: options?.original });
      const sizeToCheck = prepared ? prepared.file.size : file.size;

      const route = await routeForChatEnsured(state.chats.find((c) => c.id === chatId), sizeToCheck, MEDIA_PLAIN_LIMIT_BYTES);
      if (route.kind === 'too_big' && route.who === 'peer') {
        throw new Error(tNow('carrier.peerTooOld', { name: file.name }));
      }
      const viaCarrier = route.kind === 'v2';

      // Відмова ДО завантаження і людськими словами: вузол зважує шифротекст,
      // тож межа для відкритого файла на кілька байтів нижча.
      if (!viaCarrier && sizeToCheck > MEDIA_PLAIN_LIMIT_BYTES) {
        // Файл упритул до межі не варто описувати округленим розміром: «25.0 МБ
        // при межі до 25 МБ» читається як суперечність, хоч це правда.
        throw new Error(
          sizeToCheck - MEDIA_PLAIN_LIMIT_BYTES <= 4096
            ? tNow('store.limitEdge', { name: file.name, limit: mediaLimitLabel() })
            : tNow('store.limitOver', { name: file.name, size: humanSize(sizeToCheck), limit: mediaLimitLabel() }),
        );
      }

      // ОПТИМІСТИЧНА БУЛЬБАШКА — одразу, до шифрування й вивантаження.
      // Доти лист зʼявлявся в стрічці лише ПІСЛЯ того, як байти доїхали до
      // вузла: людина тиснула «надіслати» і кілька секунд не бачила нічого.
      // Тепер бульбашка стоїть одразу — з місцевим передоглядом і чесною
      // часткою вивантаженого (`uploadProgress` від XHR), і на тому ж місці
      // (той самий client_id) стає справжнім листом.
      const localPreviewUrl = URL.createObjectURL(file);
      const note = (caption || '').trim();
      const replyTo = (!batch || batch.index === 0) ? (get().replyingTo || undefined) : undefined;
      const clientId = `c_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
      const blank: SecureMedia = {
        name: file.name,
        size: file.size,
        mime: file.type || 'application/octet-stream',
        // Порожні рядки — ЧЕСНО «ще нічого немає», а не вигаданий blobId.
        sha256: '',
        blobId: '',
        keyHex: '',
        nonceHex: '',
        ...(prepared?.width && prepared.height ? { width: prepared.width, height: prepared.height } : {}),
      };
      const optimistic: Message = {
        id: clientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        timestamp: clockLabel(new Date()),
        sentAt: new Date().toISOString(),
        type: kind,
        media: blank,
        mediaUrl: localPreviewUrl,
        localPreviewUrl,
        uploadProgress: 0,
        fileName: file.name,
        fileSize: file.size,
        text: note || undefined,
        mediaBatch: batch,
        ...(replyTo ? { replyTo } : {}),
        ...(kind === 'voice' && options?.voice ? { voiceData: { ...options.voice, media: blank } } : {}),
        ...(kind === 'video-note' ? { videoNoteData: { duration: options?.videoNote?.duration || 0, videoUrl: localPreviewUrl, media: blank } } : {}),
        isSelf: true,
        status: 'sending',
      };

      soundFx.playSend();
      set((s2) => ({
        chats: s2.chats.map((c) =>
          c.id === chatId
            ? {
                ...c,
                messages: [...c.messages, optimistic],
                lastKind: kind,
                lastSnippet: (note || file.name).slice(0, 90),
                lastAuthor: SELF_AUTHOR,
                lastAt: new Date().toISOString(),
              }
            : c,
        ),
        ...(replyTo ? { replyingTo: null } : {}),
      }));

      // Лист міг уже змінити id (живе ехо вузла поставило рядок на його
      // місце) — тоді латки тихо нікого не знаходять, і це правильно.
      const patchWith = (fn: (m: Message) => Message) =>
        set((s2) => ({
          chats: s2.chats.map((c) =>
            c.id === chatId && c.messages.some((m) => m.id === clientId)
              ? { ...c, messages: c.messages.map((m) => (m.id === clientId ? fn(m) : m)) }
              : c,
          ),
        }));
      const patch = (fields: Partial<Message>) => patchWith((m) => ({ ...m, ...fields }));
      /** Нове вкладення — у всіх місцях листа, де воно лежить. */
      const withMedia = (m: Message, media: SecureMedia): Message => ({
        ...m,
        media,
        ...(m.voiceData ? { voiceData: { ...m.voiceData, media } } : {}),
        ...(m.videoNoteData ? { videoNoteData: { ...m.videoNoteData, media } } : {}),
      });

      let uploadOk = false;
      let uploadErrorMessage = '';
      let media: SecureMedia = blank;
      let sent: File = file;
      let thumbUrl: string | undefined;
      try {
        await attachmentUploads.run(
          async () => {
            if (kind === 'image' && !prepared) prepared = await prepareImage(file, undefined, { original: options?.original });
            if (prepared) {
              sent = prepared.file;
              const dims = prepared.width && prepared.height ? { width: prepared.width, height: prepared.height } : {};
              // Мініатюра замість 12-мегапіксельного оригіналу: бульбашка
              // розкодовує 320 px, а не 4000.
              thumbUrl = prepared.thumb ? URL.createObjectURL(prepared.thumb) : undefined;
              media = { ...blank, ...dims, ...(prepared.micro ? { thumb: prepared.micro } : {}) };
              patchWith((m) => ({ ...withMedia(m, media), ...(thumbUrl ? { localPreviewUrl: thumbUrl } : {}) }));
            }
            if (viaCarrier) {
              const dims = media.width && media.height ? { w: media.width, h: media.height } : undefined;
              const c = await sendCarrier(sent, (percent) => {
                onProgress?.(percent);
                patch({ uploadProgress: Math.max(0, Math.min(1, percent / 100)) });
              }, dims);
              media = { ...media, name: sent.name || file.name, size: c.manifest.size, mime: c.manifest.mime || blank.mime, blobId: c.transferId, carrier: c.manifest };
              rememberUploadedMedia(c.transferId, sent);
              uploadOk = true;
              return;
            }
            sent = await stripMetadata(sent);
            const sealed = await encryptForUpload(sent);
            let shown = 0;
            const blob = await messengerApi.uploadFile(chatId, sealed.ciphertext, (percent) => {
              onProgress?.(percent);
              const p = Math.max(0, Math.min(1, percent / 100));
              // Кожен відсоток — це set() на весь стор; досить кроку в 5 %.
              if (p - shown >= 0.05 || (p === 1 && shown < 1)) {
                shown = p;
                patch({ uploadProgress: p });
              }
            });
            if (!blob?.blob_id) throw new Error(tNow('store.noBlobId'));
            media = {
              ...media,
              name: sent.name || file.name,
              size: sent.size,
              mime: sent.type || blank.mime,
              sha256: blob.sha256 || sealed.sha256,
              blobId: blob.blob_id,
              keyHex: sealed.keyHex,
              nonceHex: sealed.nonceHex,
            };
            // Відкритий файл уже тут — бульбашка не качатиме його назад.
            rememberUploadedMedia(media.blobId, sent);
            uploadOk = true;
          },
          { cancelled: options?.cancelled },
        );
      } catch (uploadErr) {
        if (uploadErr instanceof QueueCancelled) {
          // Скасовано ДО того, як байти почали їхати: листа не було ніде,
          // і бульбашки, що вдає надсилання, теж не має бути.
          set((s2) => ({
            chats: s2.chats.map((c) => (c.id === chatId ? { ...c, messages: c.messages.filter((m) => m.id !== clientId) } : c)),
          }));
          URL.revokeObjectURL(localPreviewUrl);
          if (thumbUrl) URL.revokeObjectURL(thumbUrl);
          throw uploadErr;
        }
        uploadErrorMessage = uploadFailureWord(uploadErr);
        console.warn('[messenger] вивантаження байтів вкладення не вдалось — лист НЕ буде позначено надісланим:', uploadErr);
      }

      if (!uploadOk) {
        // Зберігаємо сам файл: «Повторити» має чим вивантажити ще раз, а не
        // лише переслати ті самі (порожні) key_hex/blob_id вузлу вдруге.
        // Сервер НІЧОГО не бачив про цей лист: реєструвати запис, що вказує
        // на неіснуючий блоб, гірше, ніж не реєструвати нічого.
        pendingUploadFiles.set(clientId, sent);
        patch({ status: 'failed', attachmentState: 'upload_failed', attachmentError: uploadErrorMessage, uploadProgress: undefined });
        if (options?.throwOnFail) throw new Error(uploadErrorMessage || 'upload_failed');
        return;
      }

      // Байти на вузлі: бульбашка — уже справжнє вкладення з кешу. Місцевий
      // передогляд звільняємо не одразу: відео/голос можуть ще грати з нього.
      patchWith((m) => ({ ...withMedia(m, media), fileName: media.name, uploadProgress: undefined, localPreviewUrl: undefined }));
      setTimeout(() => {
        URL.revokeObjectURL(localPreviewUrl);
        if (thumbUrl) URL.revokeObjectURL(thumbUrl);
      }, 30_000);

      // На дроті імена полів такі ж, як їх читає backend і вузол-адресат.
      // caption і розміри дописуємо лише коли вони є: старі тіла без них
      // читаються тим самим розбором і нічого не ламають.
      const body = JSON.stringify({
        name: media.name,
        size: media.size,
        mime: media.mime,
        sha256: media.sha256,
        blob_id: media.blobId,
        key_hex: media.keyHex,
        nonce_hex: media.nonceHex,
        ...(media.carrier ? { carrier: media.carrier } : {}),
        ...(media.width && media.height ? { width: media.width, height: media.height } : {}),
            ...(media.thumb ? { thumb: media.thumb } : {}),
        ...(note ? { caption: note } : {}),
        ...(batch ? { batch_id: batch.id, batch_index: batch.index, batch_total: batch.total } : {}),
        ...(kind === 'voice' && options?.voice ? { duration: options.voice.duration, waveform: options.voice.waveform, transcript: options.voice.transcript } : {}),
        ...(kind === 'video-note' && options?.videoNote ? { duration: options.videoNote.duration } : {}),
        // Рід — у самому описі: телефон-адресат бачить лише тіло листа, і без
        // цього голосове лягало б у нього файлом (PeerFile.kindOf).
        ...(kind === 'voice' || kind === 'video-note' ? { kind } : {}),
      });

      // Паралельні вивантаження доїжджають у будь-якому порядку, а вузол
      // нумерує рядки в порядку запису — тож запис чекає на попередній лист
      // тієї самої пачки, і стрічка в іншого лишається в порядку вибору.
      if (options?.appendAfter) await options.appendAfter.catch(() => undefined);

      try {
        const row = await messengerApi.appendMessage(chatId, {
          client_id: clientId,
          author_id: state.currentUser.id,
          author_name: state.currentUser.name,
          kind,
          body,
          reply_to_id: replyTo?.id ?? null,
          ...qtOf(replyTo),
        });
        // Кадр із ключем міг доїхати, а байти застрягнути на нашому вузлі.
        // Тоді в людини немає фото — і галочка «надіслано» була б брехнею.
        // 'parked' сюди теж належить: байти в хмарі — це ще не байти в людини.
        // Справжній id — від вузла, як у текстовому шляху. Вузол телефона
        // ставить у рядок свій id і `client_id = id`, тож бульбашка з веб-id
        // `c_…` інакше не впізнавалась у дельті сітки й стояла поруч зі
        // справжнім рядком із вічним годинником (DL-1, 09.10). Ехо могло
        // приїхати раніше за цю відповідь — тоді оптимістична просто йде.
        const realId = row.id || clientId;
        const status = deliveryStatus(row.delivery ?? row.delivery_state) || 'sent';
        set((s2) => ({
          chats: s2.chats.map((c) => {
            if (c.id !== chatId || !c.messages.some((m) => m.id === clientId)) return c;
            const echoed = realId !== clientId && c.messages.some((m) => m.id === realId);
            return {
              ...c,
              messages: echoed
                ? c.messages.filter((m) => m.id !== clientId)
                : c.messages.map((m) => (m.id === clientId ? { ...m, id: realId, status, attachmentState: 'stored' } : m)),
            };
          }),
        }));
      } catch (err) {
        // Сервер НЕ взяв лист. Позначати його «надісланим» — брехня про
        // дію користувача: людина бачить галочку, йде, і листа не існує.
        console.warn('[messenger] вузол не взяв вкладення:', err);
        patch({ status: 'failed', retryCount: 0, lastAttemptAtMs: Date.now() });
        if (options?.throwOnFail) throw err;
      }
    },

    sendAttachments: async (files, opts) => {
      // Усі бульбашки — одразу й у порядку вибору; вивантаження — по три
      // одночасно (`attachmentUploads`); записи на вузол — по черзі.
      const total = files.length;
      const batchId = opts?.batchId;
      const progress = new Array<number>(total).fill(0);
      const report = () => opts?.onProgress?.(Math.round(progress.reduce((a, b) => a + b, 0) / Math.max(1, total)));
      const failed: string[] = [];
      let gate: Promise<void> = Promise.resolve();
      const jobs = files.map((file, i) => {
        const after = gate;
        const job = get()
          .sendAttachment(
            file,
            (percent) => {
              progress[i] = percent;
              report();
            },
            i === 0 ? opts?.caption : undefined,
            batchId && total > 1 ? { id: batchId, index: i, total } : undefined,
            { appendAfter: after, cancelled: opts?.cancelled, throwOnFail: true, original: opts?.original },
          )
          .then(
            () => {
              progress[i] = 100;
              report();
            },
            (err: unknown) => {
              progress[i] = 100;
              report();
              failed.push(tNow(err instanceof QueueCancelled ? 'store.cancelled' : 'store.notSent', { name: file.name }));
            },
          );
        gate = job;
        return job;
      });
      await Promise.all(jobs);
      return failed;
    },

    sendGeoPoint: async (point) => {
      const state = get();
      const chatId = state.activeChatId;
      if (!chatId) return;

      const replyTo = state.replyingTo || undefined;
      const clientId = `c_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
      const newMsg: Message = {
        id: clientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        // Підпис бульбашки — час виміру, а не час натискання кнопки.
        timestamp: clockLabel(new Date(point.atMs)),
        sentAt: new Date(point.atMs).toISOString(),
        type: 'geo:point',
        geoPoint: point,
        ...(replyTo ? { replyTo } : {}),
        isSelf: true,
        status: 'sending',
      };

      soundFx.playSend();
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId
            ? {
                ...c,
                messages: [...c.messages, newMsg],
                lastKind: 'geo:point',
                lastAuthor: SELF_AUTHOR,
                lastAt: new Date().toISOString(),
              }
            : c,
        ),
        replyingTo: null,
      }));

      const markStatus = (status: Message['status']) =>
        set((s) => ({
          chats: s.chats.map((c) =>
            c.id === chatId
              ? { ...c, messages: c.messages.map((m) => (m.id === clientId ? { ...m, status } : m)) }
              : c,
          ),
        }));

      try {
        const row = await messengerApi.appendMessage(chatId, {
          client_id: clientId,
          author_id: state.currentUser.id,
          author_name: state.currentUser.name,
          kind: 'geo:point',
          body: geoPointBody(point),
          reply_to_id: replyTo?.id ?? null,
          ...qtOf(replyTo),
        });
        markStatus(deliveryStatus(row.delivery ?? row.delivery_state) || 'sent');
      } catch (err) {
        // Сервер НЕ взяв лист. Позначати його «надісланим» — брехня про
        // дію користувача: людина бачить галочку, йде, і листа не існує.
        // Шлях повтору вже робив тут `failed`; решта ставили `sent`.
        console.warn('[messenger] вузол не взяв точку:', err);
        markStatus('failed');
        set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: c.messages.map((m) => m.id === clientId ? { ...m, retryCount: 0, lastAttemptAtMs: Date.now() } : m) } : c) }));
      }
    },

    sendLiveLocation: async (location) => {
      const state = get();
      const chatId = state.activeChatId;
      if (!chatId) return;
      const clientId = `c_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
      const newMsg: Message = {
        id: clientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        timestamp: clockLabel(new Date(location.point.atMs)),
        sentAt: new Date(location.point.atMs).toISOString(),
        type: 'geo:live', liveLocation: location, isSelf: true, status: 'sending',
      };
      set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: [...c.messages, newMsg], lastKind: 'geo:live', lastAuthor: SELF_AUTHOR, lastAt: new Date().toISOString() } : c) }));
      try {
        const row = await messengerApi.appendMessage(chatId, { client_id: clientId, author_id: state.currentUser.id, author_name: state.currentUser.name, kind: 'geo:live', body: liveLocationBody(location) });
        const status = deliveryStatus(row.delivery ?? row.delivery_state) || 'sent';
        set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: c.messages.map((m) => m.id === clientId ? { ...m, status } : m) } : c) }));
      } catch {
        set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: c.messages.map((m) => m.id === clientId ? { ...m, status: 'failed', retryCount: 0, lastAttemptAtMs: Date.now() } : m) } : c) }));
      }
    },

    liveShareStart: async (peerIds, minutes) => {
      let anyStarted = false;
      for (const peerId of peerIds) {
        try {
          const res = await request<{ started?: boolean; reach?: string }>('POST', '/messenger/live/start', {
            peer_id: peerId,
            minutes,
          });
          if (res && res.reach !== 'NO_KEY' && res.started !== false) {
            anyStarted = true;
          }
        } catch {
          // continue
        }
      }
      return anyStarted;
    },

    liveShareStop: async (peerIds) => {
      for (const peerId of peerIds) {
        try {
          await request<{ stopped?: boolean }>('POST', '/messenger/live/stop', { peer_id: peerId });
        } catch {
          // ignore
        }
      }
    },

    liveShareStatus: async (peerId) => {
      if (!peerId) return false;
      try {
        const res = await request<{ sharing_active?: boolean }>(
          'GET',
          `/messenger/live/status?peer_id=${encodeURIComponent(peerId)}`,
        );
        return Boolean(res?.sharing_active);
      } catch {
        return false;
      }
    },

    sendMessage: async (text, opts) => {
      const state = get();
      const away = Boolean(opts?.to && opts.to !== state.activeChatId);
      const chatId = opts?.to || state.activeChatId;
      if (!chatId || !text.trim()) return;

      const activeChat = away ? state.chats.find((c) => c.id === chatId) : state.getActiveChat();
      if (!activeChat) return;
      // На телефоні помічнику відповідає САМ застосунок (AgencySessionManager за
      // мостом): лист іде туди звичайною дорогою, і відповідь приїде листом.
      // Тут стояла друга, власна відповідь веб-сторінки — етапи «Сприймаю
      // контекст… Пригадую памʼять…» і, без ключа в цій вкладці, речення
      // «Помічник зараз не відповідає», хоча застосунок відповідав рядком нижче.
      const isAiChat =
        !__PHONE_BUILD__ && !onPhone() &&
        (activeChat.type === 'phantom' || activeChat.type === 'ai' || chatId === 'chat_phantom_assistant' || text.startsWith('@phantom'));

      const now = new Date();
      const timeFormatted = clockLabel(now);

      // client_id живе довше за спробу відправки: якщо звʼязок обірветься і
      // клієнт повторить запит, вузол упізнає його і не роздвоїть стрічку.
      const clientId = `c_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
      const newMsg: Message = {
        id: clientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        timestamp: timeFormatted,
        type: 'text',
        text: text.trim(),
        isSelf: true,
        status: 'sending',
        replyTo: away ? undefined : state.replyingTo || undefined,
      };

      // ── БУЛЬБАШКА — ПЕРШОЮ, У ТОМУ САМОМУ КАДРІ, ЩО Й ENTER ──────────────
      //
      // Раніше перед появою листа в стрічці стояли синхронні відправки в
      // мертвий рушій мережі, сокет і меш, а після — ДВА `set` (лист і превʼю
      // списку окремо) і `await` повного запису всіх розмов у localStorage
      // ПЕРЕД тим, як лист узагалі вирушав до вузла. Тепер: один `set`
      // (лист, превʼю, чернетка, відповідь) — далі дорога — далі диск,
      // згорнутий з іншими записами й поза критичним шляхом.
      //
      // `messengerNetworkEngine.sendMessage` тут більше не кличеться: рушій
      // ніхто не ініціалізує (`init()` не має викликів), тож він завжди
      // повертав `'server'`, нічого не надсилаючи. Лист везе вузол
      // (`appendMessage`) — це й записується в `transport`.
      const transport = 'server' as const;
      newMsg.transport = transport;
      soundFx.playSend();
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId
            ? {
                ...c,
                messages: [...c.messages, newMsg],
                lastKind: 'text',
                lastSnippet: plainOrderText(text.trim()).slice(0, 90),
                lastAuthor: SELF_AUTHOR,
                lastAt: now.toISOString(),
              }
            : c,
        ),
        ...(away || opts?.keepDraft || useSpaceStore.getState().threadView?.chatId === chatId
          ? {}
          : { drafts: { ...s.drafts, [chatId]: '' } }),
        ...(away ? {} : { replyingTo: null }),
      }));

      wsClient.send({
        channel: 'messenger',
        type: 'message:new',
        data: {
          ...newMsg,
          conversation_id: chatId,
          chatId,
          senderId: state.currentUser.id,
          senderName: state.currentUser.name,
          senderHandle: state.currentUser.handle,
          senderAvatar: state.currentUser.avatar,
          targetChatTitle: activeChat?.title,
          targetChatHandle: activeChat?.handle,
          targetPeerNodeId: activeChat?.peerNodeId,
        },
      });

      // Передаємо повідомлення у глобальний P2P Mesh для віддалених користувачів
      const targetHandle =
        activeChat?.handle ||
        (activeChat?.peerNodeId?.startsWith('node_') ? activeChat.peerNodeId.replace(/^node_/, '') : undefined) ||
        (activeChat?.id?.startsWith('chat_dm_') ? activeChat.id.replace(/^chat_dm_/, '') : undefined) ||
        activeChat?.title;

      if (targetHandle) {
        globalP2PMesh.sendDirectMessage(targetHandle, newMsg, chatId);
      }

      let questionId = clientId;
      const appended = messengerApi
        .appendMessage(chatId, {
          client_id: clientId,
          author_id: state.currentUser.id,
          author_name: state.currentUser.name,
          kind: 'text',
          body: text.trim(),
          transport: transport ?? null,
          reply_to_id: newMsg.replyTo?.id ?? null,
          ...qtOf(newMsg.replyTo),
          ...(opts?.quiet ? { quiet: true } : {}),
          ...(opts?.mentions?.length ? { mentions: opts.mentions } : {}),
        })
        .then((row) => {
          const status = deliveryStatus(row.delivery ?? row.delivery_state) || 'sent';
          questionId = row.id || clientId;
          set((s) => ({
            chats: s.chats.map((c) =>
              c.id === chatId
                ? {
                    ...c,
                    messages: c.messages.map((m) =>
                      m.id === clientId ? { ...m, id: row.id || clientId, status } : m
                    ),
                  }
                : c,
            ),
          }));
        })
        .catch((err) => {
          // Сервер НЕ взяв лист. Позначати його «надісланим» — брехня про
          // дію користувача: людина бачить галочку, йде, і листа не існує.
          // Шлях повтору вже робив тут `failed`; решта ставили `sent`.
          console.warn('[messenger] вузол не взяв лист:', err);
          set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: c.messages.map((m) => m.id === clientId ? { ...m, status: 'failed', retryCount: 0, lastAttemptAtMs: Date.now() } : m) } : c) }));
        });

      // Запис на диск чекаємо: лист, якого не збережено, має лишити по собі
      // речення, а не тишу. Але чекаємо ПІСЛЯ того, як лист уже на екрані й
      // уже в дорозі, і одним записом на кілька листів поспіль.
      await persistChatsSoon(get, set);

      // Опис вкладення, вставлений текстом, несе ключ шифру: модель його не бачить.
      const asked = isAiChat ? modelText(text) : null;
      if (isAiChat && !asked) {
        set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: [...c.messages, { id: `msg_ai_${Date.now()}`, senderId: 'assistant_phantom', senderName: 'Nightpost', senderAvatar: '', timestamp: clockLabel(new Date()), type: 'text' as const, text: tNow('store.assistantTextOnly') }] } : c) }));
      }
      if (isAiChat && asked) {
        const aiMsgId = `msg_ai_${Date.now()}`;
        // Тут стояли ВИГАДАНІ етапи: порожня бульбашка «Сприймаю контекст…»,
        // через 350 мс «Пригадую памʼять…», через 750 мс «Формую відповідь…» —
        // таймерами, без жодної події від моделі. Справжнє, що ми знаємо, —
        // запит у дорозі; це й кажемо рядком «відповідає», як «друкує» в
        // людини, а відповідь лягає листом, щойно вона Є.
        get().setTypingStatus(chatId, tNow('store.assistantTyping'));
        const putReply = async (replyText: string) => {
          await appended.catch(() => undefined);
          const answers = questionId;
          set((s) => ({
            chats: s.chats.map((c) =>
              c.id === chatId
                ? {
                    ...c,
                    messages: [
                      ...c.messages,
                      {
                        id: aiMsgId,
                        senderId: 'assistant_phantom',
                        senderName: 'Nightpost',
                        senderAvatar: '',
                        timestamp: clockLabel(new Date()),
                        type: 'text' as const,
                        text: replyText,
                        localReply: { answers },
                      },
                    ],
                  }
                : c,
            ),
          }));
        };

        try {
          let replyText = '';
          // Сесію сервера беремо зі стану зараз, а не зі знімка на початку: два питання
          // поспіль мусять іти в ту саму розмову моделі, і вона ж повертається після перезапуску.
          const sessionId = get().chats.find((c) => c.id === chatId)?.sessionId;
          try {
            const res = await chatApi.sendMessage({
              content: asked,
              input_method: 'text',
              session_id: sessionId || undefined,
            });
            replyText = res?.message?.content || '';
            const nextSession = res?.session_id;
            if (nextSession && nextSession !== sessionId) {
              set((s) => ({ chats: s.chats.map((c) => (c.id === chatId ? { ...c, sessionId: nextSession } : c)) }));
            }
          } catch (err) {
            console.warn('[messenger] вузол не відповів помічником:', err);
          }

          if (replyText) {
            soundFx.playReceive();
            await putReply(replyText);
          } else {
            // Відповіді немає — так і кажемо коротким рядком поверх, а не листом від імені
            // помічника. Питання — справжній лист (у розмові з людиною — уже доставлений
            // їй), тож «не надіслано» на ньому було б неправдою, а повтор — дублем.
            useUIStore.getState().toast({ kind: 'error', message: tNow('store.assistantSilent') });
          }
          void persistChatsSoon(get, set);
        } finally {
          get().setTypingStatus(chatId, null);
        }
      }
    },

    sendVoiceMessage: async (duration, transcript, audioBlob, waveform) => {
      const state = get();
      const chatId = state.activeChatId;
      const activeChat = state.getActiveChat();
      if (!chatId) return;

      const now = new Date();
      const timeFormatted = clockLabel(now);
      const voiceClientId = `msg_voice_${Date.now()}`;

      if (audioBlob) {
        const file = new File([audioBlob], `voice_${Date.now()}.webm`, { type: audioBlob.type || 'audio/webm' });
        await get().sendAttachment(file, undefined, undefined, undefined, {
          kind: 'voice',
          voice: { duration, waveform: waveform ?? [], transcript },
        });
        return;
      }

      const newMsg: Message = {
        id: voiceClientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        timestamp: timeFormatted,
        type: 'voice',
        isSelf: true,
        voiceData: {
          duration,
          // Огинаюча — рівні СПРАВЖНЬОГО мікрофона, зняті композером під час
          // запису. Тут стояло 32 числа з Math.random(): і той, хто записав,
          // і той, хто отримав, бачили форму звуку, якої ніхто не міряв, — а
          // ChatArea ще й пропонує тицьнути в стовпчик, щоб перейти до того
          // моменту запису. Без мікрофона огинаючої немає, і це порожньо.
          waveform: waveform ?? [],
          transcript,
        },
      };

      soundFx.playSend();
      messengerNetworkEngine.sendMessage(chatId, newMsg);
      if (activeChat?.handle || activeChat?.title) {
        globalP2PMesh.sendDirectMessage(activeChat.handle || activeChat.title, newMsg, chatId);
      }

      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId ? { ...c, messages: [...c.messages, newMsg] } : c
        ),
      }));
    },

    sendVideoNote: async (videoBlob: Blob, durationS: number) => {
      const state = get();
      const chatId = state.activeChatId;
      if (!chatId) return;

      const file = new File([videoBlob], `video_note_${Date.now()}.webm`, {
        type: videoBlob.type || 'video/webm',
      });
      await get().sendAttachment(file, undefined, undefined, undefined, {
        kind: 'video-note',
        videoNote: { duration: durationS },
      });
    },

      // ТУТ ЗАСТОСУНОК ПИСАВ ВІД ІМЕНІ СПІВРОЗМОВНИКА. Прибрано 30.08.2026:
      // вигаданий текст лягав у бесіду з ІМЕНЕМ співрозмовника, його аватаркою
      // і `isSelf: false`. Слова, вкладені в чужі вуста, у власній бесіді цієї
      // людини — гірше за все інше, знайдене в месенджері.

    /**
     *
     * Три речі, яких не робив старий шлях, і кожна з них — окрема мовчазна
     * поразка, яку він приховував:
     *   1) лист доходить до вузла (`appendMessage`), а не в канал, якого немає;
     *   2) відмова видима — і ДО надсилання (рід, якого дріт не везе; тіло, що
     *      не влазить у кадр), і після (вузол не взяв — `failed`, не галочка);
     *   3) `type` і його поле їдуть РАЗОМ: рід без вмісту — це порожня
     *      бульбашка з годинником, тобто картка, яка «доїхала» й не видно.
     */
    sendCard: async (kind, data) => {
      const state = get();
      const chatId = state.activeChatId;
      if (!chatId) return tNow('store.noOpenChat');

      const field = cardFieldOf(kind);
      const payload = JSON.stringify(data ?? {});
      const refusal = refuseCard(kind, payload);
      if (refusal || !field) {
        useUIStore.getState().toast({ kind: 'error', message: refusal ?? tNow('store.cardKindUnknown') });
        return refusal ?? tNow('store.cardKindUnknown');
      }

      const replyTo = state.replyingTo || undefined;
      const clientId = `c_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
      const newMsg = {
        id: clientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        timestamp: clockLabel(new Date()),
        type: kind as Message['type'],
        isSelf: true,
        status: 'sending' as const,
        ...(replyTo ? { replyTo } : {}),
        [field]: data,
      } as Message;

      soundFx.playSend();
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId
            ? {
                ...c,
                messages: [...c.messages, newMsg],
                lastKind: kind,
                lastAuthor: SELF_AUTHOR,
                lastAt: new Date().toISOString(),
              }
            : c,
        ),
        replyingTo: null,
      }));

      const patch = (fields: Partial<Message>) =>
        set((s) => ({
          chats: s.chats.map((c) =>
            c.id === chatId
              ? { ...c, messages: c.messages.map((m) => (m.id === clientId ? { ...m, ...fields } : m)) }
              : c,
          ),
        }));

      try {
        const row = await messengerApi.appendMessage(chatId, {
          client_id: clientId,
          author_id: state.currentUser.id,
          author_name: state.currentUser.name,
          kind,
          body: payload,
          reply_to_id: replyTo?.id ?? null,
          ...qtOf(replyTo),
        });
        patch({ id: row.id || clientId, status: deliveryStatus(row.delivery ?? row.delivery_state) || 'sent' });
        void persistChatsSoon(get, set);
        return null;
      } catch (err) {
        // Вузол НЕ взяв картку. Галочка тут була б брехнею про дію людини —
        // рівно тією, що жила в цьому шляху з самого початку.
        const detail = err instanceof Error ? err.message : '';
        patch({ status: 'failed', retryCount: 0, lastAttemptAtMs: Date.now() });
        const said = detail
          ? tNow('store.cardRefusedDetail', { detail })
          : tNow('store.cardRefused');
        useUIStore.getState().toast({ kind: 'error', message: said });
        return said;
      }
    },

    addCustomMessage: (message) => {
      const state = get();
      const chatId = state.activeChatId;
      if (!chatId) return;

      // ТОЙ САМИЙ ЗАМОК, ЩО НА `sendMessage` ВИЩЕ — але на ЦИХ дверях.
      // 30.08.2026 звідти прибрали лист від імені співрозмовника; сусідня
      // функція лишилась відчиненою, і через неї три поверхні відправляли
      // вигаданого відправника у справжню бесіду: «github-actions[bot]» з
      // заявою про успішну збірку, «Async Stand-up Bot» і «Academy Quiz
      // Master» з опитуванням, у якому вже стояли три голоси.
      //
      // Замок стоїть на ВІДПРАВЦІ, а не на показі: перефразувати текст і
      // лишити передачу більше не можна. У застосунку немає способу
      // відправити лист від чужого імені — це не налаштування.
      // Два способи вкласти слова в чужі вуста, обидва знайдені 05.09:
      //   1) `isSelf: false` + вигаданий відправник;
      //   2) `isSelf: true`, але `senderName` — назва неіснуючого «рушія»
      //      («Escrow Protocol Engine», «AI Chronicler», «Planning Poker
      //      Engine», «LoRa 868MHz Mesh»…), і лист виглядав як звіт системи,
      //      а не як речення людини, що натиснула кнопку.
      const self = state.currentUser;
      const forgedIdentity =
        message.isSelf === false ||
        (message.senderName !== undefined && message.senderName !== self.name);
      if (forgedIdentity) {
        console.error(
          '[messenger] відмовлено: лист від чужого імені не йде у транспорт',
          message.senderName,
        );
        return;
      }

      // ── ДРУГА ВАДА ЦИХ ДВЕРЕЙ, знайдена 16.09 ──────────────────────────
      //
      // Замок вище стеріг, ХТО підписав лист, і не стеріг, чи лист узагалі
      // кудись їде. `messengerNetworkEngine.sendMessage` шле у веб-сокет
      // каналом `messenger`; на вузлі зареєстровано рівно ОДИН канал — `chat`
      // (`hub.on("chat", …)` в `api/routes_chat.py`, єдиний на весь backend).
      // Тіло з невідомим каналом падає в `_handlers.get(channel, [])` →
      // порожній список → цикл не робить нічого. Усередині телефона сокета
      // немає взагалі. Функція при цьому повертала `'server'`.
      //
      // Тож вісімнадцять поверхонь — композер, Canvas, Header, віджети
      // голосування й RACI, Academy, Automation — клали картку у ВЛАСНУ
      // стрічку й нікуди більше. Дріт для них є (`PeerCard`, `PH-CRD:`), і
      // веде до нього одні двері: `sendCard`. Тут, а не в кожного з
      // вісімнадцяти, — інакше наступний виклик знову піде в нікуди.
      const field = cardFieldOf(message.type as string);
      if (field) {
        const carried = (message as unknown as Record<string, unknown>)[field as string];
        void get().sendCard(message.type as string, carried);
        return;
      }

      soundFx.playSend();
      messengerNetworkEngine.sendMessage(chatId, message);

      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId ? { ...c, messages: [...c.messages, message] } : c
        ),
      }));
    },

    editMessage: async (messageId, newText) => {
      const text = newText.trim();
      const chat = get().chats.find((c) => c.messages.some((m) => m.id === messageId));
      const before = chat?.messages.find((m) => m.id === messageId);
      if (!chat || !before || !text) return;
      const patched = (fields: Partial<Message>) =>
        get().chats.map((c) =>
          c.id !== chat.id
            ? c
            : { ...c, messages: c.messages.map((m) => (m.id === messageId ? { ...m, ...fields } : m)) },
        );
      set({ chats: patched({ text, isEdited: true }) });
      try {
        const row = await messengerApi.editMessage(chat.id, messageId, text);
        const settled = patched({ text: editedText(row, text), isEdited: true });
        set({ chats: settled });
        void persist(() => storagePersistence.saveChats(settled), set);
        if ((row as { frame?: string }).frame === 'local' && chat.id !== SAVED_NOTES_ID) {
          useUIStore.getState().toast({
            kind: 'warn',
            message: tNow('store.editLocalOnly'),
          });
        }
      } catch (error) {
        set({ chats: patched({ text: before.text, isEdited: before.isEdited }) });
        const detail = error instanceof Error ? error.message : '';
        useUIStore.getState().toast({
          kind: 'error',
          message: detail
            ? tNow('store.editRefusedDetail', { detail })
            : tNow('store.editRefused'),
        });
      }
    },

    /**
     * Видалення, яке доходить до диска.
     *
     * Дві різні обіцянки, і плутати їх не можна. «Для всіх» лишає надгробок
     * тут і шле службовий кадр співрозмовнику — той видалить у себе, коли
     * кадр доїде (вимкнений вузол видалить пізніше, і це чесно). «Для себе»
     * прибирає рядок і байти вкладення лише з нашого вузла.
     *
     * Стрічку оновлюємо ПІСЛЯ відповіді вузла: прибрати бульбашку одразу
     * означало б показати видалення, якого могло й не статись.
     */
    deleteMessage: async (messageId, forEveryone = false) => {
      soundFx.playTap();
      const chat = get().chats.find((c) => c.messages.some((m) => m.id === messageId));
      if (!chat) return tNow('store.rowGone');

      // Optimistically delete locally
      const nextChats = get().chats.map((c) =>
        c.id !== chat.id
          ? c
          : withFreshPreview({
              ...c,
              messages: forEveryone
                ? c.messages.map((m) => (m.id === messageId ? asTombstone(m) : m))
                : c.messages.filter((m) => m.id !== messageId),
            }),
      );
      set({ chats: nextChats });
      // Видалення, яке не лягло на диск, після перезапуску повернеться —
      // а це рівно та обіцянка, порушення якої найдорожче.
      await persist(() => storagePersistence.saveChats(nextChats), set);

      try {
        const said = await messengerApi.deleteMessage(chat.id, messageId, forEveryone);
        // Вузол каже, чи «для всіх» справді поїхало: без ключа чи дороги до
        // людини кадру немає, і тоді лист стерто лише тут.
        if (forEveryone && said && said.for_everyone === false) {
          return tNow('store.deletedLocalOnly');
        }
        return null;
      } catch (err) {
        console.warn('[messenger] вузол не видалив повідомлення:', err);
        return sayRefusal(err, tNow('store.deleteUnconfirmed'));
      }
    },

    /** Надгробок приїхав від вузла: своє видалення з іншої вкладки або чуже. */
    applyLiveRow: (row) => {
      const st = get();
      const chat = st.chats.find((c) => c.id === row.conversation_id);
      // Розмови, якої ще немає в списку, — дочитати список: рядок розмови
      // (імʼя, ключ, вид) знає лише вузол, вигадувати його тут нічим.
      if (!chat) {
        void st.refreshConversations();
        return;
      }
      const active = st.activeChatId === chat.id;
      const res = mergeLiveRow(chat, row, {
        active,
        toMessage: (r) => messageFromNode(r, st.currentUser.id, chat.peerNodeId),
      });
      if (res.chat === chat) return;
      set((s2) => ({ chats: s2.chats.map((c) => (c.id === chat.id ? res.chat : c)) }));
      if (!res.fresh) return;
      if (active && typeof row.seq === 'number') markReadSeen(chat.id, row.seq);
      const liveMsg =
        res.chat.messages.find((m) => m.id === row.id) ??
        messageFromNode(row, st.currentUser.id, chat.peerNodeId);
      if (res.inbound) signalIncoming(chat, liveMsg, Date.now(), get().currentUser);
    },

    applyNodeDelete: (row) => {
      set((state) => ({
        chats: state.chats.map((c) =>
          c.id !== row.conversation_id
            ? c
            : withFreshPreview({
                ...c,
                messages: c.messages.map((m) =>
                  m.id === row.id || (row.client_id && m.id === row.client_id) ? asTombstone(m) : m
                ),
              }),
        ),
      }));
    },

    applyNodeEdit: (row) => {
      set((state) => ({
        chats: state.chats.map((c) => c.id !== row.conversation_id ? c : {
          ...c,
          messages: c.messages.map((m) =>
            m.id === row.id || (row.client_id && m.id === row.client_id)
              ? { ...m, text: editedText(row, ''), isEdited: true }
              : m,
          ),
        }),
      }));
    },

    togglePinMessage: (messageId) => {
      soundFx.playTap();
      const chat = get().chats.find((c) => c.messages.some((m) => m.id === messageId));
      const target = chat?.messages.find((m) => m.id === messageId);
      const paint = (isPinned: boolean) =>
        set((state) => ({
          chats: state.chats.map((c) => ({
            ...c,
            messages: c.messages.map((m) => (m.id === messageId ? { ...m, isPinned } : m)),
          })),
        }));
      const was = Boolean(target?.isPinned);
      paint(!was);
      // 1:1 на телефоні: закріплене бачать обоє (`PH-PIN:`); правда — з журналу.
      if (chat && target?.pinShared && phoneBridge()) {
        void messengerApi.pinDirectMessage(chat.id, messageId, !was).then(
          (row) => paint(row.pinned === true),
          (e) => {
            paint(was);
            useUIStore.getState().toast({ kind: 'warn', message: sayRefusal(e, tNow('messageActionSheet.pinFailed')) });
          },
        );
        return;
      }
      userMarks.pin(messageId, !was);
      void persistChatsSoon(get, set);
    },

    /**
     * Смайл під рядком — З ДОРОГОЮ, а не в памʼять вкладки.
     *
     * Що тут було зламано, виміряно 18.09:
     *
     *  1. Кадр не виходив із апарата. Єдиним викликом був
     *     `messengerNetworkEngine.sendReaction`, у якого дві гілки і жодна не
     *     веде в меш телефона. Ручка мосту (POST …/reactions) існувала з 13.09
     *     і не кликалась із веба НІ РАЗУ.
     *  2. Власний смайл зникав. Стрічка перечитується з мосту цілком
     *     (`loadMessagesForChat`), а поле `reactions` із неї не читалось —
     *     тобто домальоване в памʼяті жило до першого `__phantomMeshChanged`.
     *  3. Шукався ЛИШЕ активний чат (`c.id !== state.activeChatId`), а
     *     `sendReaction` кликався навіть коли рядка не знайшлось: кадр у
     *     нікуди на повідомлення, якого тут немає.
     *  4. Мовчання виглядало успіхом: метод був `void`, і поверхня казала
     *     «Реакцію надіслано» в усіх випадках.
     *
     * Тепер: домальовуємо одразу, але ПОЗНАЧЕНИМ як «у дорозі», далі питаємо
     * вузол і беремо його правду — або відкочуємо й повертаємо його ж речення.
     * Повертає null, коли позначка поїхала, і рядок причини, коли ні.
     */
    toggleReaction: async (messageId, emoji) => {
      const state = get();
      const self = state.currentUser;
      soundFx.playTap();

      const chat = state.chats.find((c) => c.messages.some((m) => m.id === messageId));
      const target = chat?.messages.find((m) => m.id === messageId);
      if (!chat || !target) {
        // Позначка на рядок, якого в цій вкладці немає, — кадр у нікуди.
        return tNow('store.reactionRowGone');
      }

      const action: ReactionAction = reactionActionFor(target.reactions, emoji, self.id);
      const before = target.reactions;
      const author = { id: self.id, name: self.name };

      const paint = (reactions: Reaction[] | undefined) =>
        set((s) => ({
          chats: s.chats.map((c) => c.id !== chat.id ? c : {
            ...c,
            messages: c.messages.map((m) => (m.id === messageId ? { ...m, reactions } : m)),
          }),
        }));

      // Домальовок — одразу, інакше дотик не має відповіді. Але «у дорозі», а
      // не як доставлене: різниця між цими двома виглядами і є те, чого
      // бракувало.
      paint(applyReaction(before, emoji, author, action, action === 'add' ? 'sending' : undefined));

      // Телефон ходить у міст: там єдина дорога, що справді доїжджає до
      // співрозмовника. Столовий веб лишається на браузерному транспорті —
      // вузол-хост ручки реакцій не має (нуль згадок у src/backend).
      if (phoneBridge()) {
        try {
          const row = await messengerApi.react(chat.id, messageId, emoji);
          // Правда вузла заступає домальовок цілком: журнал знає і напрям
          // дотику, і чиї позначки вже приїхали.
          paint(reactionsFromNode(row.reactions) ?? []);
          return null;
        } catch (e) {
          paint(before);
          return sayRefusal(e, tNow('store.reactionRefused'));
        }
      }

      const road = messengerNetworkEngine.sendReaction(chat.id, messageId, emoji, action);
      if (action === 'remove') return road ? null : tNow('store.reactionRemoveOffline');
      if (!road) {
        // Нікуди не поїхало. Смайл лишається видимим, але червоним: стерти
        // його означало б приховати, що дотик був.
        paint(applyReaction(before, emoji, author, action, 'failed'));
        return tNow('store.reactionOffline');
      }
      // Відкритий канал — кадр уже в співрозмовника. Брокер лише тримає його:
      // квитанції звідти немає, тож «доставлено» тут було б здогадом.
      paint(applyReaction(before, emoji, author, action, road === 'p2p' ? undefined : 'queued'));
      return null;
    },

    addReaction: (messageId, emoji) => get().toggleReaction(messageId, emoji),

    forwardMessage: async (original, targetChatId, opts) => {
      const msg: Message = opts?.asOwn
        ? { ...original, text: undefined, mediaBatch: undefined, sentAt: new Date().toISOString() }
        : original;
      const state = get();
      const targetChat = state.chats.find((c) => c.id === targetChatId);
      const media = msg.media || msg.voiceData?.media || msg.videoNoteData?.media;
      if (media?.carrier) {
        if ((await routeForChatEnsured(targetChat, media.carrier.size, 0)).kind !== 'v2') {
          set({ isForwardModalOpen: false, activeForwardMessage: null, activeForwardMessages: [] });
          useUIStore.getState().toast({ kind: 'error', message: tNow('mediaC6.forwardNoCarrier') });
          return;
        }
      } else if (media) {
        const r = await routeForChatEnsured(targetChat, media.size, MEDIA_PLAIN_LIMIT_BYTES);
        if (r.kind === 'too_big' && r.who === 'peer') {
          set({ isForwardModalOpen: false, activeForwardMessage: null, activeForwardMessages: [] });
          useUIStore.getState().toast({ kind: 'error', message: tNow('carrier.peerTooOld', { name: media.name }) });
          return;
        }
      }

      soundFx.playSend();
      chatKeep.add(targetChatId);
      const targetTitle = targetChat?.title || tNow('store.chatWord');
      const clientId = `fwd_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
      const author = forwardAuthor(msg, opts?.asOwn);
      const forwarded: Message = {
        ...msg,
        id: clientId,
        senderId: state.currentUser.id,
        senderName: state.currentUser.name,
        senderAvatar: state.currentUser.avatar,
        timestamp: clockLabel(new Date()),
        forwardFrom: author
          ? { chatTitle: state.getActiveChat()?.title || tNow('store.forwardChat'), senderName: author.name, ...(author.id ? { senderId: author.id } : {}) }
          : undefined,
        replyTo: undefined,
        reactions: undefined,
        isPinned: undefined,
        pinShared: undefined,
        isEdited: undefined,
        isSelf: true,
        status: 'sending',
      };

      const transport = messengerNetworkEngine.sendMessage(targetChatId, forwarded);
      forwarded.transport = transport;
      set((s) => ({
        chats: s.chats.map((c) =>
          c.id === targetChatId ? { ...c, messages: [...c.messages, forwarded] } : c
        ),
        isForwardModalOpen: false,
        activeForwardMessage: null,
        activeForwardMessages: [],
      }));
      const cardField = cardFieldOf(msg.type) ?? FORWARD_RICH_FIELD[msg.type];
      const cardData = cardField ? (msg as unknown as Record<string, unknown>)[cardField] : undefined;
      const body = media
        ? JSON.stringify({
            name: media.name, size: media.size, mime: media.mime, sha256: media.sha256,
            blob_id: media.blobId, key_hex: media.keyHex, nonce_hex: media.nonceHex, ...(media.carrier ? { carrier: media.carrier } : {}),
            ...(media.width && media.height ? { width: media.width, height: media.height } : {}),
            ...(media.thumb ? { thumb: media.thumb } : {}),
            ...(msg.text ? { caption: msg.text } : {}),
            ...(msg.mediaBatch ? { batch_id: msg.mediaBatch.id, batch_index: msg.mediaBatch.index, batch_total: msg.mediaBatch.total } : {}),
            ...(msg.type === 'voice' && msg.voiceData ? { duration: msg.voiceData.duration, waveform: msg.voiceData.waveform, transcript: msg.voiceData.transcript } : {}),
            ...(msg.type === 'video-note' && msg.videoNoteData ? { duration: msg.videoNoteData.duration } : {}),
            ...(msg.type === 'voice' || msg.type === 'video-note' ? { kind: msg.type } : {}),
          })
        : msg.type === 'geo:point' && msg.geoPoint
          ? geoPointBody(msg.geoPoint)
          : msg.type === 'geo:live' && msg.liveLocation
            ? liveLocationBody(msg.liveLocation)
            : cardField && cardData !== undefined
              ? JSON.stringify(cardData)
              : msg.text || '';

      try {
        const row = await messengerApi.appendMessage(targetChatId, {
          client_id: clientId,
          author_id: state.currentUser.id,
          author_name: state.currentUser.name,
          kind: msg.type,
          body,
          transport,
          reply_to_id: null,
          ...(author ? { fw: author } : {}),
        });
        set((s) => ({
          chats: s.chats.map((c) => c.id !== targetChatId ? c : {
            ...c,
            messages: c.messages.map((m) => m.id === clientId
              ? { ...m, id: row.id || clientId, status: deliveryStatus(row.delivery ?? row.delivery_state) || 'sent' }
              : m),
          }),
        }));
        await persistChatsSoon(get, set);
        if (targetChatId === SAVED_NOTES_ID) {
          useUIStore.getState().toast({
            kind: 'success',
            message: tNow('saveNotes.done'),
            action: { label: tNow('saveNotes.open'), run: () => get().openSavedNotes() },
          });
        } else {
          useUIStore.getState().toast({ kind: 'success', message: tNow('store.forwarded', { title: targetTitle }) });
        }
      } catch (error) {
        console.warn('[messenger] пересилання не вдалось:', error);
        set((s) => ({ chats: s.chats.map((c) => c.id !== targetChatId ? c : {
          ...c,
          messages: c.messages.map((m) => m.id === clientId ? { ...m, status: 'failed' } : m),
        }) }));
        useUIStore.getState().toast({ kind: 'error', message: tNow('store.forwardFailed', { title: targetTitle }) });
      }
    },

    // Лист упав або застряг у черзі — повторюємо той самий client_id, тож вузол
    // упізнає його і не роздвоїть стрічку.
    sendChannelLine: (chatId, text, line) => {
      if (text.trim()) deliverText(chatId, text.trim(), line);
    },

    retrySend: async (messageId) => {
      const state = get();
      const owner = state.chats.find((c) => c.messages.some((m) => m.id === messageId));
      const chatId = owner?.id;
      const msg = owner?.messages.find((m) => m.id === messageId);
      if (!chatId || !msg || msg.status === 'sending') return;

      const markStatus = (status: Message['status']) =>
        set((s) => ({
          chats: s.chats.map((c) =>
            c.id === chatId
              ? { ...c, messages: c.messages.map((m) => (m.id === messageId ? { ...m, status } : m)) }
              : c,
          ),
        }));
      const patchMsg = (fields: Partial<Message>) =>
        set((s) => ({
          chats: s.chats.map((c) =>
            c.id === chatId
              ? { ...c, messages: c.messages.map((m) => (m.id === messageId ? { ...m, ...fields } : m)) }
              : c,
          ),
        }));

      // Байти цього вкладення НІКОЛИ не виїжджали з пристрою (сама вада,
      // задля якої існує ця гілка): звичайний повтор нижче переслав би вузлу
      // ті самі порожні key_hex/blob_id, і той знову не мав би чим їх
      // наповнити. Тут вивантажуємо файл заново — з памʼяті цієї вкладки.
      if (msg.attachmentState === 'upload_failed') {
        const file = pendingUploadFiles.get(messageId);
        const retryCountU = (msg.retryCount || 0) + 1;
        if (!file) {
          // File-обʼєкт не переживає перезавантаження сторінки — сказати про
          // це прямо, а не крутити вічний спінер над листом, якому нема чим
          // вивантажитись.
          patchMsg({ attachmentError: tNow('store.fileGone') });
          return;
        }

        patchMsg({ status: 'sending', attachmentError: undefined, retryCount: retryCountU, lastAttemptAtMs: Date.now() });

        let sealedKeyHex = '';
        let sealedNonceHex = '';
        let blobSha256 = '';
        let blobId = '';
        let uploadOk = false;
        let uploadErrorMessage = '';
        let carrierManifest: CarrierManifest | undefined;
        try {
          const route = await routeForChatEnsured(owner, file.size, MEDIA_PLAIN_LIMIT_BYTES);
          if (route.kind === 'too_big' && route.who === 'peer') {
            throw new Error(tNow('carrier.peerTooOld', { name: file.name }));
          }
          if (route.kind === 'too_big' && route.who === 'own' && file.size > MEDIA_PLAIN_LIMIT_BYTES) {
            throw new Error(
              file.size - MEDIA_PLAIN_LIMIT_BYTES <= 4096
                ? tNow('store.limitEdge', { name: file.name, limit: mediaLimitLabel() })
                : tNow('store.limitOver', { name: file.name, size: humanSize(file.size), limit: mediaLimitLabel() }),
            );
          }
          if (route.kind === 'v2') {
            const prev = msg.media;
            const dims = prev?.width && prev.height ? { w: prev.width, h: prev.height } : undefined;
            const c = await sendCarrier(file, undefined, dims);
            carrierManifest = c.manifest;
            blobId = c.transferId;
            rememberUploadedMedia(c.transferId, file);
          } else {
            const stripped = await stripMetadata(file);
            const sealed = await encryptForUpload(stripped);
            sealedKeyHex = sealed.keyHex;
            sealedNonceHex = sealed.nonceHex;
            blobSha256 = sealed.sha256;
            const blob = await messengerApi.uploadFile(chatId, sealed.ciphertext);
            if (!blob?.blob_id) throw new Error(tNow('store.noBlobId'));
            blobId = blob.blob_id;
            blobSha256 = blob.sha256 || sealed.sha256;
            rememberUploadedMedia(blobId, stripped);
          }
          uploadOk = true;
        } catch (uploadErr) {
          uploadErrorMessage = uploadFailureWord(uploadErr);
        }

        if (!uploadOk) {
          console.warn('[messenger] повторне вивантаження вкладення не вдалось:', uploadErrorMessage);
          patchMsg({ status: 'failed', attachmentState: 'upload_failed', attachmentError: uploadErrorMessage, retryCount: retryCountU, lastAttemptAtMs: Date.now() });
          return;
        }

        pendingUploadFiles.delete(messageId);
        const media: SecureMedia = {
          ...((msg.media || msg.voiceData?.media || msg.videoNoteData?.media) as SecureMedia),
          sha256: blobSha256,
          blobId,
          keyHex: sealedKeyHex,
          nonceHex: sealedNonceHex,
          ...(carrierManifest ? { carrier: carrierManifest, size: carrierManifest.size } : { carrier: undefined }),
        };
        patchMsg({
          media,
          ...(msg.voiceData ? { voiceData: { ...msg.voiceData, media } } : {}),
          ...(msg.videoNoteData ? { videoNoteData: { ...msg.videoNoteData, media } } : {}),
          attachmentState: undefined,
          attachmentError: undefined,
        });

        try {
          const body = JSON.stringify({
            name: media.name, size: media.size, mime: media.mime, sha256: media.sha256,
            blob_id: media.blobId, key_hex: media.keyHex, nonce_hex: media.nonceHex, ...(media.carrier ? { carrier: media.carrier } : {}),
            ...(media.width && media.height ? { width: media.width, height: media.height } : {}),
            ...(media.thumb ? { thumb: media.thumb } : {}),
            ...(msg.text ? { caption: msg.text } : {}),
            ...(msg.mediaBatch ? { batch_id: msg.mediaBatch.id, batch_index: msg.mediaBatch.index, batch_total: msg.mediaBatch.total } : {}),
            ...(msg.type === 'voice' && msg.voiceData ? { duration: msg.voiceData.duration, waveform: msg.voiceData.waveform, transcript: msg.voiceData.transcript } : {}),
            ...(msg.type === 'video-note' && msg.videoNoteData ? { duration: msg.videoNoteData.duration } : {}),
            ...(msg.type === 'voice' || msg.type === 'video-note' ? { kind: msg.type } : {}),
          });
          const row = await messengerApi.appendMessage(chatId, {
            client_id: messageId,
            author_id: state.currentUser.id,
            author_name: state.currentUser.name,
            kind: msg.type,
            body,
            reply_to_id: msg.replyTo?.id ?? null,
            ...qtOf(msg.replyTo),
          });
          const realId = row.id || messageId;
          const status = deliveryStatus(row.delivery ?? row.delivery_state) || 'sent';
          set((s2) => ({
            chats: s2.chats.map((c) => {
              if (c.id !== chatId || !c.messages.some((m) => m.id === messageId)) return c;
              const echoed = realId !== messageId && c.messages.some((m) => m.id === realId);
              return {
                ...c,
                messages: echoed
                  ? c.messages.filter((m) => m.id !== messageId)
                  : c.messages.map((m) => (m.id === messageId ? { ...m, id: realId, status, attachmentState: 'stored' } : m)),
              };
            }),
          }));
        } catch (err) {
          console.warn('[messenger] вузол не взяв вкладення після повторного вивантаження:', err);
          patchMsg({ status: 'failed', retryCount: retryCountU, lastAttemptAtMs: Date.now() });
        }
        return;
      }

      const retryCount = (msg.retryCount || 0) + 1;
      set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: c.messages.map((m) => m.id === messageId ? { ...m, status: 'sending', failReason: undefined, retryCount, lastAttemptAtMs: Date.now() } : m) } : c) }));
      try {
        const media = msg.media || msg.voiceData?.media || msg.videoNoteData?.media;
        if (media?.carrier) {
          if ((await routeForChatEnsured(owner, media.carrier.size, 0)).kind !== 'v2') {
            patchMsg({ status: 'failed', failReason: tNow('mediaC6.forwardNoCarrier') });
            return;
          }
        } else if (media) {
          const r = await routeForChatEnsured(owner, media.size, MEDIA_PLAIN_LIMIT_BYTES);
          if (r.kind === 'too_big' && r.who === 'peer') {
            patchMsg({ status: 'failed', failReason: tNow('carrier.peerTooOld', { name: media.name }) });
            return;
          }
        }
        const body = media
          ? JSON.stringify({
              name: media.name, size: media.size, mime: media.mime, sha256: media.sha256,
              blob_id: media.blobId, key_hex: media.keyHex, nonce_hex: media.nonceHex, ...(media.carrier ? { carrier: media.carrier } : {}),
              ...(media.width && media.height ? { width: media.width, height: media.height } : {}),
            ...(media.thumb ? { thumb: media.thumb } : {}),
              ...(msg.text ? { caption: msg.text } : {}),
              ...(msg.mediaBatch ? { batch_id: msg.mediaBatch.id, batch_index: msg.mediaBatch.index, batch_total: msg.mediaBatch.total } : {}),
              ...(msg.type === 'voice' && msg.voiceData ? { duration: msg.voiceData.duration, waveform: msg.voiceData.waveform, transcript: msg.voiceData.transcript } : {}),
              ...(msg.type === 'video-note' && msg.videoNoteData ? { duration: msg.videoNoteData.duration } : {}),
              ...(msg.type === 'voice' || msg.type === 'video-note' ? { kind: msg.type } : {}),
            })
          : msg.type === 'geo:point' && msg.geoPoint
          ? geoPointBody(msg.geoPoint)
          : msg.type === 'geo:live' && msg.liveLocation
          ? liveLocationBody(msg.liveLocation)
          : (() => {
              const cf = cardFieldOf(msg.type) ?? FORWARD_RICH_FIELD[msg.type];
              const cd = cf ? (msg as unknown as Record<string, unknown>)[cf] : undefined;
              return cf && cd !== undefined ? JSON.stringify(cd) : (msg.text ?? '');
            })();
        const row = await messengerApi.appendMessage(chatId, {
          client_id: messageId,
          author_id: state.currentUser.id,
          author_name: state.currentUser.name,
          kind: msg.type,
          body,
          transport: msg.transport ?? null,
          reply_to_id: msg.replyTo?.id ?? null,
          ...qtOf(msg.replyTo),
          ...(msg.channelId ? { channel_id: msg.channelId, ...(msg.channelTo ? { to: msg.channelTo } : {}) } : {}),
        });
        markStatus(deliveryStatus(row.delivery ?? row.delivery_state) || 'sent');
      } catch (err) {
        console.warn('[messenger] повтор надсилання не вдався:', err);
        set((s) => ({ chats: s.chats.map((c) => c.id === chatId ? { ...c, messages: c.messages.map((m) => m.id === messageId ? { ...m, status: 'failed', retryCount, lastAttemptAtMs: Date.now() } : m) } : c) }));
      }
    },

    // Interactive Widget Update Handlers
    updateMessage: (messageId, updates) => {
      set((state) => ({
        chats: state.chats.map((c) => {
          if (c.id !== state.activeChatId) return c;
          return {
            ...c,
            messages: c.messages.map((m) => (m.id === messageId ? { ...m, ...updates } : m)),
          };
        }),
      }));
    },

    sweepCodeWindows: (nowMs) => {
      let changed = false;
      const chats = get().chats.map((c) => {
        const due = new Set(dueCodes(c.messages, nowMs));
        if (due.size === 0) return c;
        changed = true;
        due.forEach((id) => shutCodes.add(id));
        return withFreshPreview({
          ...c,
          messages: c.messages.map((m) => (due.has(m.id) ? { ...asTombstone(m), linkPreview: undefined } : m)),
        });
      });
      if (!changed) return;
      set({ chats });
      void persistChatsSoon(get, set);
    },

    updateTableData: (messageId, data) => {
      set((state) => ({
        chats: state.chats.map((c) => {
          if (c.id !== state.activeChatId) return c;
          return {
            ...c,
            messages: c.messages.map((m) => (m.id === messageId ? { ...m, tableData: data } : m)),
          };
        }),
      }));
    },

    updateTaskListData: (messageId, tasks) => {
      set((state) => ({
        chats: state.chats.map((c) => {
          if (c.id !== state.activeChatId) return c;
          return {
            ...c,
            messages: c.messages.map((m) =>
              m.id === messageId ? { ...m, taskListData: tasks } : m
            ),
          };
        }),
      }));
    },

    votePoll: (messageId, optionId) => {
      soundFx.playTap();
      const currentUserId = get().currentUser.id;
      set((state) => ({
        chats: state.chats.map((c) => {
          if (c.id !== state.activeChatId) return c;
          return {
            ...c,
            messages: c.messages.map((m) => {
              if (m.id !== messageId || !m.pollData) return m;
              const options = m.pollData.options.map((opt) => {
                const isSelected = (opt.voters || []).includes(currentUserId);
                if (opt.id === optionId) {
                  return {
                    ...opt,
                    votes: isSelected ? Math.max(0, opt.votes - 1) : opt.votes + 1,
                    voters: isSelected
                      ? opt.voters.filter((v) => v !== currentUserId)
                      : [...(opt.voters || []), currentUserId],
                  };
                }
                if (!m.pollData?.allowMultiple && isSelected) {
                  return {
                    ...opt,
                    votes: Math.max(0, opt.votes - 1),
                    voters: opt.voters.filter((v) => v !== currentUserId),
                  };
                }
                return opt;
              });
              return {
                ...m,
                pollData: {
                  ...m.pollData,
                  options,
                  totalVotes: options.reduce((acc, o) => acc + o.votes, 0),
                },
              };
            }),
          };
        }),
      }));
      const voted = get().getActiveChat()?.messages.find((m) => m.id === messageId)?.pollData;
      if (voted) userMarks.vote(messageId, voted.options.filter((o) => (o.voters || []).includes(currentUserId)).map((o) => o.id));
    },

    payBillShare: (messageId, payerId) => {
      soundFx.playChime();
      set((state) => ({
        chats: state.chats.map((c) => {
          if (c.id !== state.activeChatId) return c;
          return {
            ...c,
            messages: c.messages.map((m) => {
              if (m.id !== messageId || !m.splitBillData) return m;
              const participants = m.splitBillData.participants.map((p) =>
                p.id === payerId ? { ...p, paid: true } : p
              );
              return {
                ...m,
                splitBillData: {
                  ...m.splitBillData,
                  participants,
                },
              };
            }),
          };
        }),
      }));
    },

    // Відкладені листи.
    //
    // До 03.10 тут було три вади, і разом вони означали, що відкладений лист
    // не діставався нікому: запис брав ВІДКРИТУ розмову замість обраної,
    // «надіслати зараз» ішло `sendMessage` — теж у відкриту, — а час був
    // рядком «Сьогодні о 18:00», якого диспетчер («ГГ:ХХ») не впізнавав ніколи.
    addScheduledMessage: ({ chatId, text, date, time, repeat, whenOnline }) => {
      const body = text.trim();
      const chat = get().chats.find((c) => c.id === chatId);
      const online = whenOnline === true && scheduledOnDevice();
      const due = online ? 0 : scheduledDueMs({ scheduledDate: date, scheduledExactTime: time });
      if (!chat || !body || due === null) return;
      if (scheduledOnDevice()) {
        void messengerApi
          .createScheduled({
            conversation_id: chatId,
            text: body,
            due_at_ms: due,
            ...(repeat && !online ? { repeat } : {}),
            ...(online ? { when_online: true } : {}),
          })
          .then(() => {
            soundFx.playChime();
            return get().refreshScheduled();
          })
          .catch(scheduleRefused);
        return;
      }
      soundFx.playChime();
      const newScheduled: ScheduledMessage = {
        id: `sched_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        chatId,
        chatTitle: chat.title,
        chatAvatar: chat.avatar,
        text: body,
        type: 'text',
        scheduledTime: `${date} ${time}`,
        scheduledDate: date,
        scheduledExactTime: time,
        createdAt: new Date().toISOString(),
        ...(repeat ? { repeat } : {}),
      };
      const nextList = [...get().scheduledMessages, newScheduled];
      set({ scheduledMessages: nextList });
      // Відкладений лист живе тільки на диску: не зберігся — не надійде.
      void persist(() => storagePersistence.saveScheduledMessages(nextList), set);
    },

    deleteScheduledMessage: (id) => {
      soundFx.playTap();
      const nextList = get().scheduledMessages.filter((s) => s.id !== id);
      set({ scheduledMessages: nextList });
      if (scheduledOnDevice()) {
        void messengerApi.cancelScheduled(id).catch(() => get().refreshScheduled());
        return;
      }
      void persist(() => storagePersistence.saveScheduledMessages(nextList), set);
    },

    rescheduleScheduledMessage: (id, to) => {
      const cur = get().scheduledMessages.find((s) => s.id === id);
      if (!cur) return;
      const online = 'whenOnline' in to;
      const due = online ? null : scheduledDueMs({ scheduledDate: to.date, scheduledExactTime: to.time });
      if (!online && (due === null || due <= Date.now())) return;
      if (online && !scheduledOnDevice()) return;
      if (scheduledOnDevice()) {
        const body = online
          ? { when_online: true }
          : { due_at_ms: due!, ...(to.repeat !== undefined ? { repeat: to.repeat } : {}) };
        void messengerApi
          .updateScheduled(id, body)
          .then(() => get().refreshScheduled())
          .catch((err) => {
            scheduleRefused(err);
            return get().refreshScheduled();
          });
        return;
      }
      if (online) return;
      const next = get().scheduledMessages.map((s) => {
        if (s.id !== id) return s;
        const { failure: _f, ...rest } = s;
        const repeat = to.repeat === undefined ? s.repeat : to.repeat || undefined;
        return { ...rest, scheduledDate: to.date, scheduledExactTime: to.time, scheduledTime: `${to.date} ${to.time}`, repeat };
      });
      set({ scheduledMessages: next });
      void persist(() => storagePersistence.saveScheduledMessages(next), set);
    },

    editScheduledText: (id, text) => {
      const body = text.trim();
      if (!body || !get().scheduledMessages.some((s) => s.id === id)) return;
      if (scheduledOnDevice()) {
        set({ scheduledMessages: get().scheduledMessages.map((s) => (s.id === id ? { ...s, text: body } : s)) });
        void messengerApi
          .updateScheduled(id, { text: body })
          .then(() => get().refreshScheduled())
          .catch((err) => {
            scheduleRefused(err);
            return get().refreshScheduled();
          });
        return;
      }
      const next = get().scheduledMessages.map((s) => (s.id === id ? { ...s, text: body } : s));
      set({ scheduledMessages: next });
      void persist(() => storagePersistence.saveScheduledMessages(next), set);
    },

    cancelScheduledMessage: (id) => {
      get().deleteScheduledMessage(id);
    },

    sendScheduledNow: (id) => {
      if (scheduledOnDevice()) {
        void messengerApi
          .sendScheduledNow(id)
          .then(() => set({ scheduledMessages: get().scheduledMessages.filter((s) => s.id !== id) }))
          .catch((err) => {
            scheduleRefused(err);
            return get().refreshScheduled();
          });
        return;
      }
      const scheduled = get().scheduledMessages.find((s) => s.id === id);
      const body = scheduled?.text?.trim();
      // Розмови вже немає (видалили, або ростер ще не приїхав) — лист лишається
      // в шухляді, а не їде в ту, що відкрита.
      if (!scheduled || !body || !get().chats.some((c) => c.id === scheduled.chatId)) return;
      deliverText(scheduled.chatId, body);
      const again =
        scheduled.repeat && scheduled.scheduledDate && scheduled.scheduledExactTime
          ? nextRepeat({ date: scheduled.scheduledDate, time: scheduled.scheduledExactTime }, scheduled.repeat, Date.now())
          : null;
      if (again) {
        const next = get().scheduledMessages.map((s) =>
          s.id === id ? { ...s, scheduledDate: again.date, scheduledExactTime: again.time, scheduledTime: `${again.date} ${again.time}` } : s,
        );
        set({ scheduledMessages: next });
        void persist(() => storagePersistence.saveScheduledMessages(next), set);
        return;
      }
      get().deleteScheduledMessage(id);
    },

    sendDueScheduled: (nowMs) => {
      if (scheduledOnDevice()) return;
      for (const s of get().scheduledMessages) {
        const due = scheduledDueMs(s);
        if (due === null || nowMs < due || nowMs - due > SCHEDULE_GRACE_MS) continue;
        get().sendScheduledNow(s.id);
      }
    },

    refreshScheduled: async () => {
      if (!scheduledOnDevice()) return;
      try {
        const rows = await messengerApi.listScheduled();
        set({ scheduledMessages: (rows ?? []).map((l) => scheduledFromDevice(l, get().chats)) });
      } catch {
        // Список лишається тим, що був: апарат не відповів — вигадувати нічого.
      }
    },

    // User profile
    updateCurrentUser: (updates) => {
      soundFx.playTap();
      const next = { ...get().currentUser, ...updates };
      set({ currentUser: next });
      const authUser = useAuthStore.getState().user;
      if (authUser) {
        const authPatch: Record<string, unknown> = {};
        if (typeof updates.name === 'string' && updates.name.trim()) {
          authPatch.preferences = { ...(authUser.preferences ?? {}), display_name: updates.name.trim() };
        }
        if (updates.avatar !== undefined) {
          authPatch.avatar_url = updates.avatar || undefined;
        }
        if (Object.keys(authPatch).length > 0) {
          useAuthStore.getState().updateUser(authPatch as never);
        }
      }
      void persist(() => storagePersistence.saveUserProfile(next), set);
    },

    // Multi-select handlers
    beginReportPick: (messageId) =>
      set({ reportPick: true, multiSelectMode: true, selectedMessageIds: [messageId], selectionAnchorId: messageId }),
    toggleSelectMessage: (messageId) => {
      if (
        get().reportPick &&
        !get().selectedMessageIds.includes(messageId) &&
        get().selectedMessageIds.length >= REPORT_PICK_MAX
      ) {
        useUIStore.getState().toast({ kind: 'info', message: tNow('sel.reportMax', { max: REPORT_PICK_MAX }) });
        return;
      }
      set((state) => {
        const selected = state.selectedMessageIds.includes(messageId)
          ? state.selectedMessageIds.filter((id) => id !== messageId)
          : [...state.selectedMessageIds, messageId];
        return {
          selectedMessageIds: selected,
          multiSelectMode: selected.length > 0,
          selectionAnchorId: selected.includes(messageId) ? messageId : state.selectionAnchorId,
        };
      });
    },

    // Прибирає лише напис, не «лагодить» запис: наступний успішний `persist`
    // зніме його сам. Кнопка тут для того, щоб напис не затуляв роботу вічно.
    clearStorageFailure: () => set({ storageFailure: null }),

    clearSelection: () => set({ selectedMessageIds: [], multiSelectMode: false, reportPick: false, selectionAnchorId: null }),
    setMultiSelectMode: (enabled) => set({ multiSelectMode: enabled, reportPick: false, selectedMessageIds: [], selectionAnchorId: null }),
    selectMessages: (ids) =>
      set((state) => ({
        selectedMessageIds: [...new Set(ids)],
        multiSelectMode: ids.length > 0,
        selectionAnchorId: ids.length ? state.selectionAnchorId ?? ids[ids.length - 1] : null,
      })),

    // Modal Setters
    setProfileModalOpen: (open) => set({ isProfileModalOpen: open }),
    setSettingsModalOpen: (open) => set({ isSettingsModalOpen: open }),
    setCreateChatModalOpen: (open, start) => set({ isCreateChatModalOpen: open, createChatStart: open ? start ?? null : null }),
    setScheduledDrawerOpen: (open) => {
      set({ isScheduledDrawerOpen: open });
      if (open) void get().refreshScheduled();
    },
    setGroupDetailsOpen: (open) => set({ isGroupDetailsOpen: open }),
    openLightbox: (url, title) =>
      set({ isMediaLightboxOpen: true, activeLightboxUrl: url, activeLightboxTitle: title || '' }),
    closeLightbox: () =>
      set({ isMediaLightboxOpen: false, activeLightboxUrl: '', activeLightboxTitle: '' }),
    openLocationSheet: (data) => set({ activeLocationData: data }),
    closeLocationSheet: () => set({ activeLocationData: null }),
    // Подробиці, видалення й реакції відкриває ChatArea — своїми вікнами біля
    // стрічки. Тут жили другі такі самі двері й другий монтаж тих самих вікон
    // у корені; жоден дотик до них не вів.
    openForwardModal: (msg) => {
      // Картки помічника не пересилаються: у чужій розмові це були б «факти» від мого імені.
      const list = (Array.isArray(msg) ? msg : [msg]).filter((m) => !isAssistantOnlyKind(m.type));
      if (list.length === 0) return;
      set({ isForwardModalOpen: true, activeForwardMessage: list[0], activeForwardMessages: list });
    },
    closeForwardModal: () => set({ isForwardModalOpen: false, activeForwardMessage: null, activeForwardMessages: [] }),
  };
});

if (typeof window !== 'undefined') {
  (window as any).__phantom_messenger_store = useMessengerStore;
}

if (typeof window !== 'undefined') {
  (window as unknown as { __phantomTyping?: (chatId: string, name: string, on: boolean) => void }).__phantomTyping =
    createTypingShown((chatId, status) => useMessengerStore.getState().setTypingStatus(chatId, status));
}

// Телефон смикає це, коли в меші щось змінилось: прийшов лист, доїхав свій.
if (typeof window !== 'undefined') {
  (window as unknown as { __phantomMeshChanged?: () => void }).__phantomMeshChanged = () => {
    const s = useMessengerStore.getState();
    tailMarks.clear();
    void s.refreshConversations();
    if (s.activeChatId) void s.loadMessagesForChat(s.activeChatId, { full: true });
  };
}

// Новий телефон шле дельту: які розмови зачеплено. Дочитується лише вони.
if (typeof window !== 'undefined') {
  (window as unknown as { __phantomMeshDelta?: (d: MeshDelta) => void }).__phantomMeshDelta = createMeshDeltaSink({
    activeChatId: () => useMessengerStore.getState().activeChatId || null,
    conversationsByIds: (ids) => messengerApi.conversationsByIds(ids),
    messagesChanged: (id) => messengerApi.messagesChanged(id),
    mergeConversations: (rows, gone = []) =>
      useMessengerStore.setState((s2) => {
        const goneSet = new Set(gone);
        for (const id of goneSet) chatKeep.drop(id);
        const byId = new Map(rows.map((r) => [r.id, r]));
        const known = new Set(s2.chats.map((c) => c.id));
        const fresh = rows.filter((r) => !known.has(r.id)).map(fromNode);
        const kept = goneSet.size > 0 ? s2.chats.filter((c) => !goneSet.has(c.id)) : s2.chats;
        const merged = kept.map((c) => {
          const row = byId.get(c.id);
          return row ? withNodeFields(c, row, s2.activeChatId) : c;
        });
        const droppedActive = goneSet.has(s2.activeChatId);
        if (!fresh.length && kept.length === s2.chats.length && !merged.some((c, i) => c !== s2.chats[i]) && !droppedActive) {
          return {};
        }
        return {
          chats: [...fresh, ...merged],
          ...(droppedActive ? { activeChatId: '' } : {}),
        };
      }),
    mergeMessages: (chatId, upsert, remove) => {
      const st = useMessengerStore.getState();
      const selfId = st.currentUser.id;
      const chat = st.chats.find((c) => c.id === chatId);
      if (!chat) return;
      const messages = keepMessageIdentity(
        chat.messages,
        mergeRows(chat.messages, upsert, remove, (r) => messageFromNode(r, selfId, chat.peerNodeId), {
          self: tNow('chatList.you'),
          peer: chat.title ?? '',
        }),
      );
      useMessengerStore.setState((s2) => ({
        chats: s2.chats.map((c) => (c.id === chatId ? { ...c, unreadCount: 0, messages } : c)),
      }));
      // Прочитано — лише на новому рядку: правка старого не має зсувати межу назад.
      const had = new Set(chat.messages.map((m) => m.id));
      const newest = upsert.filter((r) => !had.has(r.id)).reduce((n, r) => Math.max(n, r.seq ?? 0), 0);
      if (newest > 0) markReadSeen(chatId, newest);
    },
    reloadChat: (chatId) => useMessengerStore.getState().loadMessagesForChat(chatId, { full: true }),
    refreshAll: () => useMessengerStore.getState().refreshConversations(),
    staleTail: (chatId, lineIds) =>
      void forgetStaleTail(useMessengerStore.getState().chats.find((c) => c.id === chatId), lineIds),
  });
}

// Повернулась людина — розчитується лише та розмова, що досі відкрита.
onAppSeenAgain(() => {
  const active = useMessengerStore.getState().activeChatId;
  for (const [chatId, seq] of unseenReads) if (chatId === active) void messengerApi.markRead(chatId, seq);
  unseenReads.clear();
});

let codeWindowTimer: ReturnType<typeof setTimeout> | null = null;
const MAX_TIMER_MS = 2_147_483_647;

/** Один таймер на найближчий кінець вікна; далекий кінець — проміжний підйом. */
function armCodeWindowTimer(): void {
  if (codeWindowTimer !== null) clearTimeout(codeWindowTimer);
  codeWindowTimer = null;
  let soonest: number | null = null;
  for (const chat of useMessengerStore.getState().chats) {
    for (const m of chat.messages) {
      if (m.isDeleted) continue;
      const end = codeWindowEnd(m);
      if (end !== null && (soonest === null || end < soonest)) soonest = end;
    }
  }
  if (soonest === null) return;
  codeWindowTimer = setTimeout(() => {
    codeWindowTimer = null;
    useMessengerStore.getState().sweepCodeWindows(Date.now());
    armCodeWindowTimer();
  }, Math.min(Math.max(0, soonest - Date.now()), MAX_TIMER_MS));
}

useMessengerStore.subscribe((state, prevState) => {
  if (state.chats !== prevState.chats) armCodeWindowTimer();
});

if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return;
    useMessengerStore.getState().sweepCodeWindows(Date.now());
    armCodeWindowTimer();
  });
}

// Мову змінили — «Збережене» перейменовується одразу, не чекаючи списку з вузла.
useSettingsStore.subscribe((s, prev) => {
  if (s.values[LANGUAGE_SETTING_KEY] === prev.values[LANGUAGE_SETTING_KEY]) return;
  const title = tNow('chatList.saved');
  useMessengerStore.setState((st) =>
    st.chats.some((c) => c.id === SAVED_NOTES_ID && c.title !== title)
      ? { chats: st.chats.map((c) => (c.id === SAVED_NOTES_ID ? { ...c, title } : c)) }
      : st,
  );
});

chatKeep.track(useMessengerStore.getState().chats, [], hasLetter);
useMessengerStore.subscribe((s, prev) => {
  if (s.chats !== prev.chats) chatKeep.track(s.chats, prev.chats, hasLetter);
});

// Чернетки переживають перезапуск: кожна належить профілю, на диск — із затримкою 300 мс, порожня знімається одразу.
let draftsSwitching = false;
useMessengerStore.subscribe((s, prev) => {
  if (draftsSwitching || s.drafts === prev.drafts || !draftOwner) return;
  for (const [chatId, text] of Object.entries(s.drafts)) {
    if (prev.drafts[chatId] !== text) keepDraft(draftOwner, chatId, text ?? '');
  }
  for (const chatId of Object.keys(prev.drafts)) {
    if (!(chatId in s.drafts)) keepDraft(draftOwner, chatId, '');
  }
});
useProfiles.subscribe((p) => {
  if (p.activeId === draftOwner) return;
  flushDrafts();
  draftOwner = p.activeId;
  draftsSwitching = true;
  try {
    useMessengerStore.setState({ drafts: loadDrafts(draftOwner) });
  } finally {
    draftsSwitching = false;
  }
});
