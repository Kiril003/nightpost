import { plainText } from '../../services/plainText';
import { pickPlural } from '../chat/tNow';
/**
 * «Розмови» — список розмов телефона й ПК (10-messenger-proposal §2, §6).
 *
 * Один список для всіх родів розмов (1:1, «Збережене», Nightpost, група,
 * канал, бізнес). Згори вниз: заголовок «Розмови» зі сканером, «Новою» й
 * профілем; поле «Шукати або спитати Nightpost»; пʼять фільтрів (Усі ·
 * Непрочитані · Близькі · Друзі · Групи); рядок «Запити · N», коли пише
 * людина поза колами; закріплені й далі за часом; «Інші профілі»; «Архів · N»
 * унизу. Над пошуком — лише моменти людей (list-fix), прокрутка згортає їх
 * у мініатюри біля заголовка; «Далі», живі кімнати й двері належать Дню й Мапі.
 * Довгий дотик вибирає розмови (шапка вибору), правий клік — меню дій.
 *
 * Рядок 72 (ПК — 60): аватар 52, імʼя 16/600, час 13 tnum; превʼю 14 text-2,
 * галочки сірі. Лічильник бурштиновий лише в прямих неприглушених і згадках,
 * не більше трьох на екрані; решта — `raised`.
 */
import { useEnteringIds } from '../../feel/motionKit';
import React, { Suspense, lazy, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { AlarmClock, Archive, ArchiveRestore, BellOff, Bell, Bookmark, CalendarClock, Check, CheckCheck, ChevronDown, ChevronLeft, CircleDot, Clock, GitBranch, Inbox, Laptop, Loader2, RotateCw, MoreHorizontal, Pin, PinOff, Trash2, UserPlus, Users } from 'lucide-react';
import type { Chat, UserProfile } from '../../types/messenger';
import { sortChatsByRecent } from '../../services/chatOrder';
import { cardTypeOfWire, peerWireLine } from '../../services/peerWire';
import { isHiddenMessage, isServiceKind, isServiceText, lastHumanMessage } from '../../services/serviceNotice';
import { soundFx } from '../../utils/messengerSound';
import { haptic } from '../../utils/haptic';
import { withViewTransition } from '../../services/viewTransition';
import { Avatar } from './Avatar';
import { CirclePickSheet } from './CirclePickSheet';
import { PhantomMark } from './PhantomMark';
import { StatusChip } from './kit/StatusChip';
import { EmptyState } from './kit/EmptyState';
import { ListRow, RowBadge } from './kit/ListRow';
import { AskField } from './kit/AskField';
import { GlobalSearchResults, useGlobalSearch } from './GlobalSearch';
import { AddOwnDevice } from './AddOwnDevice';
import { DeviceSheet } from './DeviceSheet';
import { isMyDevice, myDevices, namesakeHint } from '../../services/peopleIdentity';
import { useMessengerStore } from '../../stores/messengerStore';
import { chatMarkUnread, useUnreadMarks, watchOpenClears } from '../../services/chatMarkUnread';
import { chatKeep } from '../../services/chatKeep';
import { selfAdded } from '../../services/selfAdded';
import { splitByProfile } from '../../services/profileScope';
import { useProfiles } from '../../stores/profilesStore';
import { startChatProfiles, useChatProfiles } from '../../stores/chatProfileStore';
import { startStaffSync } from '../../services/staffPass';
import { askNightpost, talkToNightpost } from '../../services/askNightpost';
import { DoorRow, FormSheet } from './FormSheet';
import { useEdgeStretch } from '../../feel/useEdgeStretch';

import { CALL_SETTINGS_TAB } from '../../services/callSettingsRoute';

const CallSettingsSheet = lazy(() => import('./CallSettingsSheet').then((m) => ({ default: m.CallSettingsSheet })));
import { MomentsStrip } from './MomentsStrip';
import { createDrift, ownsHorizontalSwipe } from '../../utils/ownSwipe';
import { RemindersLine, type RemindersSummary } from './today/RemindersSheet';
import { PageRequestsRow } from './PageRequestsRow';
import { openRemindersSheet } from '../../services/remindersBus';
import { DaySheetHost, openDayItem, useDayRun, useDayView } from './today/DaySheetHost';
import { DayBand } from './today/DayBand';
import { DayStrip } from './today/DayStrip';
import { TempEndingNotice } from './TempEndingNotice';
import { endedProfileNames } from '../../services/tempProfileEnd';
import { dayApi } from '../../services/dayApi';
import { TopSheet, type FilterTab } from './today/TopSheetView';
import type { TopSheetState } from './today/topSheet';
import { LIST_FILTER_EVENT } from './ChatTypeChips';
import { LIST_FILTERS, inListFilter, isRequest, isSystemChat, pageForLegacyKey, pageForOpenKey, type ListPage } from '../../services/chatFilters';
import { rowSignals } from '../../services/listSignals';
import { TAG_SEARCH_EVENT } from '../../services/tagSearch';
import { listenLiving, useLiving } from '../../services/livingPulse';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { ChatsProfileTitle } from './ProfileFan';
import { RecipientSheet, SendManyDock } from './recipients/SendToMany';
import { useListMoments } from './list/ListMoments';
import { groupSeen } from './list/momentsStripModel';
import { MomentsHome } from './moments/MomentsHome';
import type { Pane } from './moments/PaneTitles';
import { ListSelectBar } from './list/ListSelectBar';
import { needsToggle, selectionCaps } from './list/listSelection';
import {
  attachmentKindFromSnippet,
  cardSnippet,
  plainSnippet,
  belongsInConversations,
  chatKind,
  chatToneKey,
  displayTitle,
  planTitle,
  hasLetter,
  isNodeChat,
  isSavedNotes,
  kindLine,
  looksLikeFileName,
  selfWord,
} from './chatListFormat';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { ownTick, planWhenLabel, todayTimeLabel } from './todayFormat';
import { eduPreview } from '../../services/edu/eduWire';

export interface PhoneChatListProps {
  chats: Chat[];
  currentUser: UserProfile;
  onSelectChat: (id: string) => void;
  onNewChat: () => void;
  /** Порожні «Групи» — одна дія. Немає — відкривається загальна «Нова». */
  onNewGroup?: () => void;
  /**
   * Повне меню дій з розмовою: правий клік (ПК), довгий дотик на ПК, «⋮ → Усі дії»
   * у виборі. На телефоні довгий дотик вибирає (list-fix C2).
   */
  onOpenChatActions: (chat: Chat) => void;
  /** Видалити розмову (вибір → кошик). Немає — кошик вимкнений. */
  onDeleteChat?: (chat: Chat) => Promise<void> | void;
  /** Дотик по знайденому листу: відкрити розмову й прокрутити до нього. */
  onOpenMessage?: (chatId: string, messageId: string) => void;
  /** Свайп «Прочитано». Немає — дії немає. */
  onMarkRead?: (chat: Chat) => Promise<void> | void;
  /** ПК: щільні рядки без волосин і вибраний рядок відкритої розмови. */
  desk?: boolean;
  activeChatId?: string;
  /** Скільки повідомлень ще не пішло (черга мосту) — одним тихим рядком під заголовком. */
  queued?: number;
  /**
   * Телефон: одна капсула «Пошук або спитати Nightpost» з мікрофоном (голос) і
   * першим рядком пошуку «Спитати Nightpost: «…»» (збірка 18; окремого поля
   * питання над пошуком більше немає — власник 09.10).
   */
  ask?: boolean;
}

/** Місце однієї круглої дії під рядком (коло 48 + повітря) і поріг, за яким свайп лишає дії відкритими. */
const SWIPE_ACTION = 64;
const SWIPE_OPEN_AT = 56;
/** Повний свайп (як у Telegram): за цією часткою ширини рядка відпускання виконує першу дію боку. */
const SWIPE_FULL_RATIO = 0.42;
const SWIPE_FULL_MIN = 160;
export const swipeFullAt = (rowWidth: number): number =>
  Math.max(SWIPE_FULL_MIN, Math.round((rowWidth > 0 ? rowWidth : 390) * SWIPE_FULL_RATIO));
/** Смуга біля правого краю: свайп, що почався в ній, гортає на наступну вкладку, а не відкриває дії рядка. */
const NEXT_TAB_EDGE = 24;

const trackAt = (page: number, dx: number) => `translateX(calc(${-page * 100}% + ${dx}px))`;

export const LIST_SEARCH_OPEN_EVENT = 'phantom:list-search-open';
export const LIST_SEARCH_CLOSE_EVENT = 'phantom:list-search-close';

/** Розмови списку: «Збережене» першим, решта за часом; порожні контакти й свої пристрої сюди не йдуть. */
// Шапка не смикається, коли список сам зсуває scrollTop після її згортання.
const BAR_SETTLE_MS = 300;
const BAR_HIDE_DIST = 24;

const arrangeChats = (chats: Chat[]): Chat[] => {
  const sorted = sortChatsByRecent(chats.filter((c) => !isMyDevice(c) && belongsInConversations(c)));
  return [...sorted.filter(isSavedNotes), ...sorted.filter((c) => !isSavedNotes(c))];
};

/** Закріплений блок «Усіх»: PHANTOM (лише коли є прострочене), Збережене, закріплені; решта — за часом. */
const splitPinned = (ordered: Chat[], pinNode = false): { pinned: Chat[]; rest: Chat[] } => {
  const rank = (c: Chat) => (isNodeChat(c) ? 0 : isSavedNotes(c) ? 1 : 2);
  const isPinned = (c: Chat) => (isNodeChat(c) ? pinNode : c.pinned || isSavedNotes(c));
  const unpinned = ordered.filter((c) => !isPinned(c));
  const rest = !pinNode && unpinned.some((c) => isNodeChat(c) && c.pinned)
    ? sortChatsByRecent(unpinned.map((c) => (isNodeChat(c) && c.pinned ? { ...c, pinned: false } : c)))
    : unpinned;
  return {
    pinned: ordered.filter(isPinned).sort((a, b) => rank(a) - rank(b)),
    rest,
  };
};

/** Alt+↑/↓ ходить рівно тими рядками й у тому порядку, що видно у списку «Усі». */
export const orderedChatsForNav = (chats: Chat[]): Chat[] => {
  const { pinned, rest } = splitPinned(arrangeChats(chats).filter((c) => !c.archived), true);
  return [...pinned, ...rest];
};

/** Розмови, що зараз видно у списку на ПК, у порядку рядків; null — списку на екрані немає. */
let shownIds: string[] | null = null;

/**
 * Alt+↑/↓ ходить рівно тими рядками, що видно під вибраною вкладкою (під запитом —
 * жодними: видно знахідки). Списку на екрані немає — порядком «Усі».
 */
export const shownChatsForNav = (chats: Chat[]): Chat[] => {
  if (shownIds === null) return orderedChatsForNav(chats);
  const byId = new Map(chats.map((c) => [c.id, c]));
  return shownIds.flatMap((id) => byId.get(id) ?? []);
};

interface SwipeAction {
  key: string;
  label: string;
  icon: React.ReactNode;
  /** Тло дії під рядком: бурштин — прочитано, решта — свої тихі тони (токени act-*). */
  tone?: 'accent' | 'pin' | 'mute' | 'archive';
  run: () => void;
}

const ICON = { strokeWidth: 1.75 } as const;


/**
 * Превʼю: слово замість імені файлу, «Чернетка:» — станом.
 *
 * Службовий лист (речення рідного боку про рід, якого ця збірка не малює,
 * керівний кадр) превʼю не стає: береться останній ЛЮДСЬКИЙ лист, а немає
 * його — рядка немає зовсім (services/serviceNotice).
 *
 * Автор — лише в групі («Марта:», «Ви:»): у розмові вдвох імʼя вже стоїть
 * заголовком, а «Ви:» там — шум, якого немає в жодному месенджері.
 */
const previewOf = (chat: Chat, t: Translation['t']): React.ReactNode => {
  // Про ключ людині не кажемо — кажемо, що це означає для неї.
  if (chat.contactKeyChanged) {
    return <span className="text-warning">{t('phoneChatList.keyChanged')}</span>;
  }
  if (chat.draft) {
    return (
      <>
        <span className="text-ink-3">{t('phoneChatList.draft')} </span>
        {chat.draft}
      </>
    );
  }
  const tail = chat.messages?.length ? chat.messages[chat.messages.length - 1] : null;
  const nodeHidden = isServiceKind(chat.lastKind) || isServiceText(chat.lastSnippet);
  const human = nodeHidden || isHiddenMessage(tail) ? lastHumanMessage(chat.messages) : tail;
  if ((nodeHidden || isHiddenMessage(tail)) && !human) {
    return isNodeChat(chat) ? <span className="text-ink-3">{t('today.assistant')}</span> : null;
  }
  const fromNode = !nodeHidden && !isHiddenMessage(tail);
  const lastMsg = human;
  const rawSnippet = (fromNode && chat.lastSnippet) || lastMsg?.text || '';
  // Старий міст клав картку помічника в уривок сирим `PH-CRD:` — це рід, не слова.
  const wiredCard = cardTypeOfWire(rawSnippet);
  const kind = wiredCard ?? ((fromNode && chat.lastKind) || lastMsg?.type);
  const snippet = wiredCard ? '' : plainText(eduPreview(rawSnippet));
  const group = chatKind(chat) === 'group' && chat.groupSettings?.kind !== 'channel';
  const authorRaw = group
    ? (fromNode && selfWord(chat.lastAuthor)) || (lastMsg ? (lastMsg.isSelf ? t('phoneChatList.you') : lastMsg.senderName?.split(' ')[0]) : '')
    : '';
  const author = authorRaw ? <span className="font-medium text-ink">{`${authorRaw}: `}</span> : '';

  const wire = peerWireLine({ kind, card: fromNode ? chat.lastCard : null, message: lastMsg, snippet });
  if (wire) return wire.line && !wire.control ? <>{author}{wire.line}</> : null;

  const card = cardSnippet(kind, fromNode ? chat.lastCard : null, lastMsg);
  if (card) return <>{author}{card}</>;
  const word = kindLine(kind, lastMsg, snippet);
  if (word) return <>{author}{word}</>;
  const described = attachmentKindFromSnippet(snippet);
  if (described) return <>{author}{kindLine(described, lastMsg, snippet) ?? t('phoneChatList.file')}</>;
  if (snippet && looksLikeFileName(snippet)) return <>{author}{t('phoneChatList.file')}</>;
  // «Ви: .» — порожній лист не має превʼю (A52_AUDIT_28SEP).
  if (snippet && /[\p{L}\p{N}\p{Extended_Pictographic}]/u.test(snippet)) return <>{author}{plainSnippet(snippet)}</>;
  if (!hasLetter(chat) && !(chat.unreadCount > 0) && !isNodeChat(chat)) return <span className="text-ink-3">{t('chatRow.noLetters')}</span>;
  if (isNodeChat(chat)) return <span className="text-ink-3">{t('today.assistant')}</span>;
  return null;
};

const SWIPE_SKIN: Record<NonNullable<SwipeAction['tone']> | 'plain', string> = {
  accent: 'bg-accent text-on-accent',
  pin: 'bg-act-pin text-on-act',
  mute: 'bg-act-mute text-on-act',
  archive: 'bg-act-archive text-on-act',
  plain: 'bg-overlay text-ink',
};

/**
 * Дія під рядком — кругла кнопка на тлі списку (власник 28.10: «квадратні кнопки по краях» прибрано).
 * Спокійне коло `raised`; колір дії — лише коли повний свайп її «взвів» (`armed`).
 */
const SwipeButton: React.FC<{ action: SwipeAction; onDone: () => void; armed?: boolean }> = ({ action, onDone, armed = false }) => (
  <span className="h-full flex items-center justify-center shrink-0" style={{ width: SWIPE_ACTION }}>
    <button
      type="button"
      data-swipe-action={action.key}
      data-swipe-armed={armed ? 'true' : undefined}
      aria-label={action.label}
      title={action.label}
      onClick={() => {
        soundFx.playTap();
        action.run();
        onDone();
      }}
      data-swipe-tone={action.tone ?? 'plain'}
      className={`w-12 h-12 rounded-full flex items-center justify-center transition-[transform,background-color,color] duration-150 ${
        armed ? `${SWIPE_SKIN[action.tone ?? 'plain']} scale-110` : 'bg-raised text-ink'
      }`}
    >
      {action.icon}
    </button>
  </span>
);

/** Знак розмови без людини: «Збережене» — закладка, PHANTOM — власний знак (PhantomMark). */
const SystemMark: React.FC<{ kind: 'saved' | 'phantom' | 'devices' | 'archive' | 'others' | 'requests' | 'reminders'; desk?: boolean; lift?: boolean }> = ({ kind, desk, lift }) => {
  const box = desk ? 'w-11 h-11' : 'w-[52px] h-[52px]';
  if (kind === 'phantom') return <PhantomMark size={box} />;
  if (kind === 'saved') {
    return (
      <span data-saved-avatar className={`${box} shrink-0 rounded-full ${lift ? 'bg-overlay' : 'bg-raised'} text-ink-2 flex items-center justify-center`}>
        <Bookmark className="w-5 h-5" {...ICON} />
      </span>
    );
  }
  const Glyph = kind === 'devices' ? Laptop : kind === 'others' ? MoreHorizontal : kind === 'requests' ? Inbox : kind === 'reminders' ? Bell : Archive;
  return (
    <span className={`${box} shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center`}>
      <Glyph className="w-6 h-6" {...ICON} />
    </span>
  );
};

/** Лічильник (np 2.13): 20, пігулка, 12/600; бурштин — лише прямі неприглушені, решта `raised`. */
const Badge: React.FC<{ n: number; lit: boolean; quiet?: boolean }> = ({ n, lit, quiet = false }) => {
  const { t, locale } = useTranslation();
  return <RowBadge n={n} lit={lit} quiet={quiet} label={`${n} ${pluralFor(locale, n, t('phoneChatList.unread1'), t('phoneChatList.unread2'), t('phoneChatList.unread5'))}`} />;
};

/**
 * Позначка «непрочитане» (ЧС-03): крапка без числа там, де стоїть лічильник.
 * Числа не вигадуємо — непрочитаних листів немає, є лише намір повернутись.
 */
const MarkDot: React.FC<{ lit: boolean }> = ({ lit }) => {
  const { t } = useTranslation();
  return (
  <span
    data-row-unread-mark
    role="img"
    aria-label={t('phoneChatList.markedUnread')}
    className="shrink-0 w-5 h-5 flex items-center justify-center f-fade-in"
  >
    <span className={`w-3 h-3 rounded-full ${lit ? 'bg-accent' : 'bg-ink-3'}`} />
  </span>
  );
};

const ChatRow: React.FC<{
  chat: Chat;
  /** Людина позначила розмову непрочитаною: крапка, доки не відкриє. */
  marked?: boolean;
  /** Друге слово для однакових імен: @нік або «доданий 6 вересня». */
  hint?: string | null;
  /** Фото людини з її профілю (кадр PH-PRF); немає — монограма в тоні. */
  face?: string | null;
  /** Лічильник бурштином (np 2.13) — вирішує список: бурштинових не більше трьох. */
  amber?: boolean;
  /** Тег кола після імені (тека людини); усередині самої теки його немає. */
  circle?: string | null;
  /** Рядок вкладки «Чекає» замість останнього листа. */
  openLine?: string | null;
  /** Прострочене нагадування у рядку Nightpost. */
  overdueLine?: string | null;
  /** Чи показувати значок закріплення. */
  pinned?: boolean;
  /** ПК: щільний рядок 60, без волосини, з вибраним станом. */
  desk?: boolean;
  selected?: boolean;
  /** Рядок з'явився після першого показу списку: вхід f-row-in. */
  entering?: boolean;
  /** Відкрити розмову; `el` — сам рядок (з нього перетікають аватар і імʼя). */
  onOpen: (el: HTMLElement) => void;
  onActions: () => void;
  /** Довгий дотик; немає — дії з розмовою. */
  onLongPress?: () => void;
  /** Режим вибору: позначка на аватарі; null — рядок не вибирається або вибору немає. */
  check?: 'on' | 'off' | null;
  /** Дії під рядком: зліва (свайп управо) і справа (свайп уліво). */
  leading?: SwipeAction[];
  trailing?: SwipeAction[];
  /** Відкрито може бути лише один рядок — стан тримає список. */
  swiped?: 'leading' | 'trailing' | null;
  onSwiped?: (side: 'leading' | 'trailing' | null) => void;
  /** Є попередня вкладка: свайп управо по рядку гортає її, а не відкриває дії. */
  pagerPrev?: boolean;
}> = ({ chat, marked = false, hint, face, amber = false, circle, openLine, overdueLine, pinned, desk = false, selected = false, entering = false, onOpen, onActions, onLongPress, check = null, leading = [], trailing = [], swiped = null, onSwiped, pagerPrev = false }) => {
  const { t, locale } = useTranslation();
  const unread = chat.unreadCount > 0;
  const saved = isSavedNotes(chat);
  const lastMsg = chat.messages?.length ? chat.messages[chat.messages.length - 1] : null;
  // Без жодного листа вузол віддає `last_at` = час запиту: розмова без
  // листування стояла б «щойно». Немає листа — немає часу.
  const hasLetter = Boolean(lastMsg || chat.lastKind || chat.lastSnippet);
  const time = hasLetter ? todayTimeLabel(chat.lastAt || lastMsg?.sentAt) : '';
  const pressTimer = useRef<number | null>(null);
  const longPressed = useRef(false);
  // Android шле contextmenu і на довгий дотик: дотик щойно був — це не правий клік.
  const touchAt = useRef(-Infinity);

  const clearPress = () => {
    if (pressTimer.current !== null) window.clearTimeout(pressTimer.current);
    pressTimer.current = null;
  };

  const start = useRef<{ x: number; y: number; base: number; lock: 'h' | 'v' | null; edge: boolean } | null>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const rowRef = useRef<HTMLButtonElement | null>(null);
  const leadW = leading.length * SWIPE_ACTION + (leading.length ? 8 : 0);
  const trailW = trailing.length * SWIPE_ACTION + (trailing.length ? 8 : 0);
  const rest = swiped === 'leading' ? leadW : swiped === 'trailing' ? -trailW : 0;
  const offset = drag ?? rest;
  const rowW = () => rowRef.current?.offsetWidth || (typeof window !== 'undefined' ? window.innerWidth : 390);
  const fullAt = swipeFullAt(rowW());
  // Повний свайп «взводить» першу дію боку: відпустив — виконано, як у Telegram.
  const armedSide: 'leading' | 'trailing' | null =
    drag !== null && Math.abs(drag) >= fullAt ? (drag > 0 && leading.length ? 'leading' : drag < 0 && trailing.length ? 'trailing' : null) : null;
  const muted = Boolean(chat.muted);
  const lit = amber && (unread || marked);
  const typing = useMessengerStore((s) => Boolean(s.typingStatus[chat.id]));

  const node = isNodeChat(chat);
  const device = !face && !chat.avatar && isMyDevice(chat);
  // Живі сигнали рядка (власник 02.10: «дуже все сухо»): чернетка, дорога
  // свого листа, згадка, відгук, гілки, мініатюра — services/listSignals.ts.
  const draftText = useMessengerStore((s) => s.drafts[chat.id]);
  const me = useMessengerStore((s) => s.currentUser);
  const sig = useMemo(
    () => rowSignals(chat, { drafts: draftText ? { [chat.id]: draftText } : {}, me: { id: me.id, name: me.name } }),
    [chat, draftText, me.id, me.name],
  );
  const preview = previewOf(chat, t);
  const tick = sig.sending || sig.failed ? null : ownTick(chat, me.name);
  // Майбутня зустріч у групі (LOOK-SPEC §2.1 п. 7): час і місце замість останнього листа, якщо немає нового.
  const planAt = chat.plan && !chat.plan.cancelled && chat.plan.at != null && chat.plan.at >= Date.now() - 2 * 3_600_000 ? chat.plan.at : null;
  const showPlan = planAt !== null && chatKind(chat) === 'group' && chat.unreadCount === 0 && !typing && !sig.draft && !sig.sending && !sig.failed;
  const planVenue = chat.plan?.venue?.trim();
  const avatar = desk ? 'w-11 h-11 text-headline' : 'w-[52px] h-[52px] text-title-2';
  return (
    <li className={`relative overflow-hidden ${entering ? 'f-row-in' : ''}`}>
      {offset !== 0 && (
        <div aria-hidden={false} data-swipe-reveal className="absolute inset-0 flex justify-between bg-canvas">
          <div className="flex h-full px-1" style={offset > 0 ? { width: Math.max(leadW, offset), justifyContent: armedSide === 'leading' ? 'flex-end' : 'flex-start' } : undefined}>
            {offset > 0 &&
              (armedSide === 'leading'
                ? <SwipeButton key={leading[0].key} action={leading[0]} armed onDone={() => onSwiped?.(null)} />
                : leading.map((a) => <SwipeButton key={a.key} action={a} onDone={() => onSwiped?.(null)} />))}
          </div>
          <div className="flex h-full px-1" style={offset < 0 ? { width: Math.max(trailW, -offset), justifyContent: armedSide === 'trailing' ? 'flex-start' : 'flex-end' } : undefined}>
            {offset < 0 &&
              (armedSide === 'trailing'
                ? <SwipeButton key={trailing[0].key} action={trailing[0]} armed onDone={() => onSwiped?.(null)} />
                : trailing.map((a) => <SwipeButton key={a.key} action={a} onDone={() => onSwiped?.(null)} />))}
          </div>
        </div>
      )}
      <button
        type="button"
        ref={rowRef}
        data-chat-row={chat.id}
        data-row-checked={check ?? undefined}
        aria-current={selected ? 'true' : undefined}
        aria-pressed={check ? check === 'on' : undefined}
        style={{ transform: offset ? `translateX(${offset}px)` : undefined, touchAction: 'pan-y' }}
        onClick={(e) => {
          if (longPressed.current) {
            longPressed.current = false;
            return;
          }
          if (swiped) {
            onSwiped?.(null);
            return;
          }
          soundFx.playTap();
          onOpen(e.currentTarget as HTMLElement);
        }}
        onContextMenu={(e) => {
          e.preventDefault();
          if (performance.now() - touchAt.current < 1000) return;
          onActions();
        }}
        onTouchStart={(e) => {
          longPressed.current = false;
          touchAt.current = performance.now();
          clearPress();
          const t = e.touches[0];
          start.current = { x: t.clientX, y: t.clientY, base: rest, lock: null, edge: t.clientX >= window.innerWidth - NEXT_TAB_EDGE };
          pressTimer.current = window.setTimeout(() => {
            longPressed.current = true;
            (onLongPress ?? onActions)();
          }, 480);
        }}
        onTouchMove={(e) => {
          const st = start.current;
          if (!st) return;
          const t = e.touches[0];
          const dx = t.clientX - st.x;
          const dy = t.clientY - st.y;
          if (st.lock === null && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
            st.lock = Math.abs(dx) > Math.abs(dy) * 1.3 ? 'h' : 'v';
            clearPress();
          }
          if (st.lock !== 'h' || (!leadW && !trailW)) return;
          // Уліво — дії рядка завжди (крім краю); управо — попередня вкладка, а на першій — дії.
          if (st.base === 0 && ((dx < 0 && st.edge) || (dx > 0 && pagerPrev))) {
            st.lock = 'v';
            return;
          }
          e.stopPropagation();
          const far = Math.round(rowW() * 0.9);
          const next = Math.max(trailW ? -far : 0, Math.min(leadW ? far : 0, st.base + dx));
          const prev = Math.abs(drag ?? st.base);
          // Пороги відчуваються пальцем (05 §1.4): відкрити — легкий тік, повний свайп — `threshold`, назад — `release`.
          if ((Math.abs(next) >= SWIPE_OPEN_AT) !== (prev >= SWIPE_OPEN_AT)) haptic('tap');
          if (Math.abs(next) >= fullAt && prev < fullAt) haptic('threshold');
          else if (Math.abs(next) < fullAt && prev >= fullAt) haptic('release');
          setDrag(next);
        }}
        onTouchEnd={(e) => {
          clearPress();
          touchAt.current = performance.now();
          const st = start.current;
          start.current = null;
          if (!st || st.lock !== 'h' || drag === null) {
            setDrag(null);
            return;
          }
          e.stopPropagation();
          longPressed.current = true;
          if (armedSide) {
            const first = armedSide === 'leading' ? leading[0] : trailing[0];
            setDrag(null);
            onSwiped?.(null);
            soundFx.playTap();
            first.run();
            return;
          }
          const side = drag > SWIPE_OPEN_AT && leadW ? 'leading' : drag < -SWIPE_OPEN_AT && trailW ? 'trailing' : null;
          setDrag(null);
          onSwiped?.(side);
        }}
        className={`relative w-full ${desk ? 'h-[60px] rounded-control' : 'h-[72px] ph-list-row'} px-4 flex items-center gap-3 text-left ${
          check === 'on' ? 'bg-raised' : selected ? 'bg-accent-soft' : 'bg-canvas active:bg-overlay hover:bg-overlay/60'
        } ${drag === null ? 'ph-row-settle' : ''} focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-line`}
      >
        {selected && <span aria-hidden className="absolute left-0 top-2 bottom-2 w-[3px] rounded-sm bg-accent-line" />}
        <span data-row-lead className="relative shrink-0 flex">
        {saved ? (
          <SystemMark kind="saved" desk={desk} lift={check === 'on'} />
        ) : node ? (
          // PHANTOM — власний знак: не людина, тож і не тон людини.
          <SystemMark kind="phantom" desk={desk} />
        ) : device ? (
          <span data-device-avatar className="shrink-0">
            <SystemMark kind="devices" desk={desk} />
          </span>
        ) : (
          <span data-row-avatar className="shrink-0">
            <Avatar
              src={face || chat.avatar}
              name={displayTitle(chat.title)}
              tone={chatToneKey(chat)}
              radius={!desk && chatKind(chat) === 'group' ? 'rounded-control' : 'rounded-full'}
              className={avatar}
            />
          </span>
        )}
        {check && (
          <span
            aria-hidden
            data-row-check={check}
            className={`absolute -right-0.5 -bottom-0.5 w-[22px] h-[22px] rounded-full border-2 flex items-center justify-center ${
              check === 'on' ? 'bg-ink text-canvas border-raised' : 'bg-canvas border-canvas outline outline-[1.5px] -outline-offset-[3.5px] outline-line-strong'
            }`}
          >
            {check === 'on' && <Check className="w-[13px] h-[13px]" strokeWidth={2.8} />}
          </span>
        )}
        </span>

        <span
          data-row-body
          className={`min-w-0 flex-1 self-stretch flex flex-col justify-center gap-[3px]${desk ? '' : ' border-b border-line'}`}
        >
          <span className="flex items-center gap-2">
            <span className="min-w-0 flex-1 flex items-baseline gap-1.5">
              <span data-row-name className="min-w-0 truncate text-headline font-semibold text-ink">{planTitle(chat)}</span>
              {circle && (
                <span data-row-circle className="shrink-0 max-w-[40%] truncate text-footnote text-ink-3">
                  · {circle}
                </span>
              )}
              {hint && (
                <span data-row-hint className="shrink-0 max-w-[55%] truncate text-footnote text-ink-3">
                  · {hint}
                </span>
              )}
              {muted && <BellOff aria-label={t('phoneChatList.mutedMark')} data-row-muted className="w-4 h-4 shrink-0 text-ink-3" {...ICON} />}
            </span>
            {tick === 'read' && !typing && !sig.draft && <CheckCheck data-row-tick="read" aria-label={t('today.tickRead')} className="w-4 h-4 shrink-0 text-ink-3" {...ICON} />}
            {tick === 'sent' && !typing && !sig.draft && <Check data-row-tick="sent" aria-label={t('today.tickSent')} className="w-4 h-4 shrink-0 text-ink-3" {...ICON} />}
            {time && (
              <span data-row-time className={`shrink-0 text-footnote tnum ${lit ? 'text-ink-2 font-semibold' : 'text-ink-3'}`}>
                {time}
              </span>
            )}
          </span>
          <span data-row-line2 className="flex items-center gap-2">
            {/* Керівний кадр мережі не дає рядка зовсім — ні тексту, ні порожнього місця. */}
            {overdueLine ? (
              <span data-row-overdue className="min-w-0 flex-1 flex items-center gap-1 text-callout font-medium text-ink" data-testid="chat-preview-line">
                <AlarmClock aria-hidden className="w-4 h-4 shrink-0 text-ink" {...ICON} />
                <span className="min-w-0 truncate">{`${t('day.overdue')} · ${overdueLine}`}</span>
              </span>
            ) : openLine ? (
              <span className="min-w-0 flex-1 flex items-center gap-1 text-callout font-normal text-ink-2" data-testid="chat-preview-line">
                <span className="min-w-0 truncate">{openLine}</span>
              </span>
            ) : (
              <>
                {showPlan && planAt !== null && (
                  <span data-row-plan className="min-w-0 flex-1 flex items-center gap-1 text-callout font-normal text-ink-2">
                    <CalendarClock aria-hidden className="w-3.5 h-3.5 shrink-0 text-ink-3" {...ICON} />
                    <span className="min-w-0 truncate">{planWhenLabel(planAt, Date.now(), locale)}{planVenue ? ` · ${planVenue}` : ''}</span>
                  </span>
                )}
                {!showPlan && (typing || sig.draft || preview !== null) && (
                  <span className="min-w-0 flex-1 flex items-center gap-1 text-callout font-normal text-ink-2" data-testid="chat-preview-line">
                    {typing ? (
                      <span className="text-ink-2">{t('phoneChatList.typing')}</span>
                    ) : sig.draft ? (
                      <span className="truncate" data-row-draft><span className="text-ink-3">{t('phoneChatList.draft')} </span>{sig.draft}</span>
                    ) : (
                      <>
                        {sig.sending && <span data-row-sending className="shrink-0 flex items-center"><StatusChip kind="pending">{t('look.pending')}</StatusChip></span>}
                        {sig.failed && <span data-row-failed className="shrink-0 text-danger">{t('phoneChatList.notSent')}</span>}
                        {sig.thumb && <img src={sig.thumb} alt="" aria-hidden data-row-thumb className="w-5 h-5 shrink-0 rounded-[5px] object-cover" />}
                        <span className="min-w-0 truncate">{preview}</span>
                      </>
                    )}
                  </span>
                )}
                {!showPlan && !typing && !sig.draft && preview === null && <span className="flex-1" />}
              </>
            )}
            {sig.threadNew > 0 && (
              <span data-row-threads className="shrink-0 h-5 px-1.5 rounded-full bg-raised text-caption text-ink-2 font-semibold tnum flex items-center gap-0.5" aria-label={t('phoneChatList.threadNew', { threadNew: sig.threadNew })}>
                <GitBranch className="w-3 h-3" {...ICON} aria-hidden />{sig.threadNew}
              </span>
            )}
            {sig.reaction && !unread && (
              <span data-row-reaction className="shrink-0 w-5 h-5 rounded-full bg-raised flex items-center justify-center text-[12px] leading-none" aria-label={t('phoneChatList.reaction', { reaction: sig.reaction })}>
                {sig.reaction}
              </span>
            )}
            {sig.mention && (
              <span data-row-mention className={`shrink-0 w-[22px] h-[22px] rounded-full text-footnote font-semibold flex items-center justify-center ${lit ? 'bg-accent text-on-accent' : chat.muted ? 'bg-line-strong text-ink' : 'bg-ink-2 text-canvas'}`} aria-label={t('phoneChatList.mention')}>@</span>
            )}
            {unread ? <Badge n={chat.unreadCount} lit={lit} quiet={Boolean(chat.muted)} /> : marked && <MarkDot lit={lit} />}
            {(pinned ?? chat.pinned) && !saved && !unread && !marked && (
              <Pin aria-label={t('phoneChatList.pinnedMark')} data-row-pinned className="w-4 h-4 shrink-0 text-ink-3 rotate-45" {...ICON} />
            )}
          </span>
        </span>
      </button>
    </li>
  );
};

/**
 * Рядок-тека, як «Архів» у Telegram: знак, назва, імена всередині й тихий
 * лічильник. Відкриває свій вміст на місці.
 */
const FolderRow: React.FC<{
  kind: 'archive' | 'devices' | 'others' | 'requests';
  title: string;
  chats: Chat[];
  open?: boolean;
  desk?: boolean;
  onToggle: () => void;
}> = ({ kind, title, chats, open, desk, onToggle }) => {
  const unread = chats.reduce((n, g) => n + Math.max(0, g.unreadCount), 0);
  const names = chats.map((g) => displayTitle(g.title)).join(', ');
  return (
    <ListRow
      data-folder-row={kind}
      aria-expanded={open}
      onClick={() => {
        soundFx.playTap();
        onToggle();
      }}
      density={desk ? 'desk' : 'phone'}
      leading={<SystemMark kind={kind} desk={desk} />}
      title={title}
      meta={
        <>
          {kind !== 'requests' && <span className="shrink-0 text-caption text-ink-3 tnum">{chats.length}</span>}
          {open !== undefined && (
            <ChevronDown
              aria-hidden
              className={`w-5 h-5 shrink-0 text-ink-3 transition-transform duration-quick ${open ? 'rotate-180' : ''}`}
              {...ICON}
            />
          )}
        </>
      }
      subtitle={names}
      badge={unread > 0 && <Badge n={unread} lit={false} />}
    />
  );
};

const ARCHIVE_OPEN_KEY = 'ph.archiveLineOpen';

/** «Архів» угорі «Усі» (LOOK-2910): тонкий рядок 48; шеврон розгортає три останні просто в списку, тап — уся сторінка архіву. */
const ArchiveLine: React.FC<{ chats: Chat[]; open: boolean; onOpen: () => void; onFold: () => void }> = ({ chats, open, onOpen, onFold }) => {
  const { t } = useTranslation();
  const unread = chats.reduce((n, g) => n + Math.max(0, g.unreadCount), 0);
  return (
    <div data-archive-line data-open={open ? 'true' : 'false'} className="h-12 pr-1 flex items-center">
      <button
        type="button"
        data-folder-row="archive"
        onClick={() => {
          soundFx.playTap();
          onOpen();
        }}
        className="min-w-0 flex-1 h-12 pl-4 flex items-center gap-3 text-left active:bg-overlay focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-line"
      >
        <span aria-hidden className="w-[52px] shrink-0 flex items-center justify-center text-ink-2">
          <Archive className="w-5 h-5" {...ICON} />
        </span>
        <span className="min-w-0 truncate text-callout font-medium text-ink">{t('msgV2.archive')}</span>
        <span className="shrink-0 text-footnote text-ink-3 tnum">{chats.length}</span>
        <span aria-hidden className="flex-1" />
        {unread > 0 && <Badge n={unread} lit={false} quiet />}
      </button>
      <button
        type="button"
        data-archive-fold
        aria-expanded={open}
        aria-label={t('msgV2.archive')}
        onClick={() => {
          soundFx.playTap();
          onFold();
        }}
        className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink-3 active:bg-overlay"
      >
        <ChevronDown className={`w-5 h-5 transition-transform duration-quick ${open ? 'rotate-180' : ''}`} {...ICON} />
      </button>
    </div>
  );
};

/** Порожній стан (np 2.16): плитка 48 зі значком, назва і одна другорядна кнопка. */
export const ListEmpty: React.FC<{ icon: React.ReactNode; title: string; action: string; onAction: () => void; testId?: string; inline?: boolean }> = ({
  icon,
  title,
  action,
  onAction,
  testId,
  inline,
}) => (
  <EmptyState
    testId={testId}
    inline={inline}
    icon={icon}
    label={title}
    action={{
      label: action,
      onClick: () => {
        soundFx.playTap();
        onAction();
      },
    }}
  />
);

const pluralFor = (locale: string, n: number, one: string, few: string, many: string): string =>
  pickPlural(locale, n, one, few, many);

export const PhoneChatList: React.FC<PhoneChatListProps> = ({
  chats,
  currentUser,
  onSelectChat,
  onNewChat,
  onNewGroup,
  onOpenChatActions,
  onDeleteChat,
  onOpenMessage,
  onMarkRead,
  desk = false,
  activeChatId,
  queued = 0,
  ask = false,
}) => {
  const { t, locale } = useTranslation();
  // Список ще не прийшов із вузла (C2): порожнеча до цього — не «нікого немає».
  const hydrated = useMessengerStore((s) => s.hydrated);
  const hydrateFailed = useMessengerStore((s) => s.hydrateFailed);
  const [searchOpen, setSearchOpen] = useState(false);
  const [circlePick, setCirclePick] = useState<'close' | 'friends' | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [swipedRow, setSwipedRow] = useState<{ id: string; side: 'leading' | 'trailing' } | null>(null);
  // Сторінка списку: один із пʼяти фільтрів або «Запити» / «Архів» (10 §2).
  const [pick, setPick] = useState<ListPage>('all');
  const swipe = useRef<{ x: number; y: number; w: number; lock: 'h' | 'v' | null; d: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const pagerRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const frame = useRef(0);
  const driftSource = useMemo(() => createDrift(), []);
  const listRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(listRef, { keepLayer: !desk });
  const [query, setQuery] = useState('');
  // Чому питання чи голос не вийшли — тихим рядком під капсулою, не мовчки.
  const [askNote, setAskNote] = useState<string | null>(null);
  const [devicesOpen, setDevicesOpen] = useState(false);
  const [reminders, setReminders] = useState<RemindersSummary>({ total: 0, overdue: null });
  const overdueReminder = desk ? null : reminders.overdue;
  // Великий заголовок згортається в шапку, щойно список поїхав угору (§7.1).
  const [scrolled, setScrolled] = useState(false);
  const [barHidden, setBarHidden] = useState(false);
  const lastScrollY = useRef(0);
  const scrollRun = useRef(0);
  const barToggledAt = useRef(-Infinity);
  const [sendTo, setSendTo] = useState<string[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [sheetState, setSheetState] = useState<TopSheetState>('compact');
  const [daySheetOpen, setDaySheetOpen] = useState(false);
  const dayNow = Math.floor(Date.now() / 60_000) * 60_000;
  const day = useDayView(daySheetOpen || !desk, chats, dayNow, daySheetOpen || (!desk && sheetState === 'open'));
  const dayRun = useDayRun(day.refresh);
  const pullY = useRef<number | null>(null);
  const pullDown = {
    onTouchStart: (e: React.TouchEvent<HTMLElement>) => {
      pullY.current = e.currentTarget.scrollTop <= 0 ? e.touches[0].clientY : null;
    },
    onTouchMove: (e: React.TouchEvent<HTMLElement>) => {
      if (pullY.current === null || e.touches[0].clientY - pullY.current <= 48 || sheetState !== 'compact') return;
      pullY.current = null;
      setSheetState('open');
    },
    onTouchEnd: () => {
      pullY.current = null;
    },
  };
  // «Чати · Моменти» (20-moments §1): стрічка — друга половина вкладки, смуги над чатами немає.
  const moments = useListMoments(chats, currentUser);
  const momentsUnseen = moments.people.some((g) => !groupSeen(g));
  const [pane, setPane] = useState<Pane>('chats');
  // Вибір розмов (C2): довгий дотик вибирає, дотик перемикає, X або BACK — вихід.
  const [picked, setPicked] = useState<string[]>([]);
  const [confirmDelete, setConfirmDelete] = useState<Chat[] | null>(null);
  const pickedChats = useMemo(() => {
    const byId = new Map(chats.map((c) => [c.id, c]));
    return picked.flatMap((id) => byId.get(id) ?? []);
  }, [picked, chats]);
  const selecting = !desk && pickedChats.length > 0;
  useEffect(() => {
    if (!desk && (searchOpen || selecting)) setSheetState('compact');
  }, [desk, searchOpen, selecting]);
  const exitSelect = () => setPicked([]);
  const togglePick = (id: string) => setPicked((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));
  useEffect(() => registerPhoneLayer('list:select', selecting, () => setPicked([])), [selecting]);
  // Свій пристрій відкривається вікном «Мій пристрій», листування — з нього.
  const [deviceChat, setDeviceChat] = useState<Chat | null>(null);
  const q = query.trim();
  const marks = useUnreadMarks();
  // Відкрита розмова знімає позначку, хоч би звідки її відкрили (сповіщення, пошук).
  useEffect(() => watchOpenClears(useMessengerStore), []);
  // Плашка вкладки «Чати» на телефоні рахує й позначене — лише серед розмов, що є.
  const chatIdsKey = chats.map((c) => c.id).join('\n');
  const entering = useEnteringIds(chats.map((c) => c.id));
  useEffect(() => {
    chatMarkUnread.syncKnown(chatIdsKey ? chatIdsKey.split('\n') : []);
  }, [chatIdsKey]);

  // Інші екрани («Я», кола) просять показати зріз; старі ключі теж розуміємо.
  useEffect(() => {
    const on = (e: Event) => {
      const want = (e as CustomEvent<string>).detail;
      if (typeof want === 'string' && want) setPick(pageForLegacyKey(want));
    };
    window.addEventListener(LIST_FILTER_EVENT, on);
    return () => window.removeEventListener(LIST_FILTER_EVENT, on);
  }, []);
  // Дотик по #тегу в листі (services/tagSearch.ts) — пошук цього тегу по всіх розмовах.
  useEffect(() => {
    const on = (e: Event) => {
      const tag = (e as CustomEvent<{ tag?: string }>).detail?.tag;
      if (!tag) return;
      setSearchOpen(true);
      setQuery(tag);
    };
    window.addEventListener(TAG_SEARCH_EVENT, on);
    return () => window.removeEventListener(TAG_SEARCH_EVENT, on);
  }, []);

  useEffect(() => {
    const onOpen = () => {
      setAskNote(null);
      setSearchOpen(true);
      searchInputRef.current?.focus();
    };
    const onClose = () => {
      setQuery('');
      setSearchOpen(false);
      setAskNote(null);
    };
    window.addEventListener(LIST_SEARCH_OPEN_EVENT, onOpen);
    window.addEventListener(LIST_SEARCH_CLOSE_EVENT, onClose);
    return () => {
      window.removeEventListener(LIST_SEARCH_OPEN_EVENT, onOpen);
      window.removeEventListener(LIST_SEARCH_CLOSE_EVENT, onClose);
    };
  }, []);

  useEffect(() => {
    if (searchOpen) {
      searchInputRef.current?.focus();
    }
  }, [searchOpen]);

  // Обличчя людей з їхніх профілів — одне опитування мосту на список і шапку розмови.
  useEffect(() => listenLiving(), []);
  const faces = useLiving((s) => s.faces);
  const [callsOpen, setCallsOpen] = useState(false);

  const keepVersion = useSyncExternalStore(chatKeep.subscribe, chatKeep.version, chatKeep.version);
  const addedVersion = useSyncExternalStore(selfAdded.subscribe, selfAdded.version, selfAdded.version);
  // «Збережене» — першим серед закріплених, решта за останнім листом.
  // Контакт без жодного листа сюди не йде: він у «Нова → Людина».
  const { ordered: allOrdered, archived } = useMemo(() => {
    const all = arrangeChats(chats);
    return { ordered: all.filter((c) => !c.archived), archived: all.filter((c) => c.archived) };
  }, [chats, keepVersion]);
  // Кожна розмова — рівно в одному профілі; перша повна стрічка тихо їх розкладає.
  useEffect(() => startChatProfiles(useMessengerStore), []);
  useEffect(() => startStaffSync(useMessengerStore), []);
  const scopeProfile = useProfiles((s) => s.profiles.find((p) => p.id === s.activeId));
  const allProfiles = useProfiles((s) => s.profiles);
  const owners = useChatProfiles((s) => s.map);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const { inside: scoped, outside: others } = useMemo(() => splitByProfile(allOrdered, scopeProfile), [allOrdered, scopeProfile, allProfiles, owners]);
  // Перше слово від людини поза колами — у «Запити», не в загальний список (10 §2).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const requests = useMemo(() => scoped.filter(isRequest), [scoped, addedVersion]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const ordered = useMemo(() => scoped.filter((c) => !isRequest(c)), [scoped, addedVersion]);
  const [othersOpen, setOthersOpen] = useState(false);
  const [archiveOpen, setArchiveOpen] = useState(() => {
    try {
      return localStorage.getItem(ARCHIVE_OPEN_KEY) === '1';
    } catch {
      return false;
    }
  });
  const foldArchive = (next: boolean) => {
    setArchiveOpen(next);
    try {
      localStorage.setItem(ARCHIVE_OPEN_KEY, next ? '1' : '0');
    } catch {
      /* приватне вікно */
    }
  };
  useEffect(() => setOthersOpen(false), [scopeProfile?.id]);

  const devices = useMemo(() => sortChatsByRecent(myDevices(chats).filter((c) => !c.archived)), [chats]);
  const hints = useMemo(() => {
    const out: Record<string, string> = {};
    for (const c of chats) {
      const h = namesakeHint(c, chats);
      if (h) out[c.id] = h;
    }
    return out;
  }, [chats]);

  const actionsFor = (chat: Chat): { leading: SwipeAction[]; trailing: SwipeAction[] } => {
    const store = useMessengerStore.getState();
    const I = { className: 'w-5 h-5', ...ICON };
    const leading: SwipeAction[] = [];
    // Свайп праворуч — перша дія «Прочитано / Непрочитане», як у Telegram.
    // «Прочитано» знімає і число вузла, і власну позначку людини.
    if (chat.unreadCount > 0 || marks[chat.id] !== undefined) {
      leading.push({
        key: 'read',
        label: t('phoneChatList.read'),
        icon: <CheckCheck {...I} />,
        tone: 'accent',
        run: () => {
          chatMarkUnread.clear(chat.id);
          if (chat.unreadCount > 0 && onMarkRead) void onMarkRead(chat);
        },
      });
    } else {
      leading.push({
        key: 'unread',
        label: t('phoneChatList.unread'),
        icon: <CircleDot {...I} />,
        tone: 'accent',
        run: () => chatMarkUnread.mark(chat.id),
      });
    }
    if (!isSavedNotes(chat)) {
      leading.push({
        key: 'pin',
        label: chat.pinned ? t('phoneChatList.unpin') : t('phoneChatList.pin'),
        icon: chat.pinned ? <PinOff {...I} /> : <Pin {...I} />,
        tone: 'pin',
        run: () => store.togglePinChat(chat.id),
      });
    }
    const trailing: SwipeAction[] = [
      {
        key: 'mute',
        label: chat.muted ? t('phoneChatList.unmute') : t('phoneChatList.mute'),
        icon: chat.muted ? <Bell {...I} /> : <BellOff {...I} />,
        tone: 'mute',
        run: () => store.toggleMuteChat(chat.id),
      },
    ];
    // Перша дія боку — та, що виконує повний свайп: уліво — архів (з «Скасувати»), як у Telegram.
    if (!isSavedNotes(chat)) {
      trailing.unshift({
        key: 'archive',
        label: chat.archived ? t('phoneChatList.unarchive') : t('phoneChatList.toArchive'),
        icon: chat.archived ? <ArchiveRestore {...I} /> : <Archive {...I} />,
        tone: 'archive',
        run: () => store.toggleArchiveChat(chat.id),
      });
    }
    return { leading, trailing };
  };

  const renderRow = (chat: Chat, quiet: boolean, page: { amber: Set<string> }, open?: () => void) => {
    const { leading, trailing } = actionsFor(chat);
    // Свій пристрій (теки) не вибирається; на ПК вибору немає.
    const selectable = !desk && !open;
    const check = selecting && selectable ? (picked.includes(chat.id) ? 'on' : 'off') : null;
    return (
      <ChatRow
        key={chat.id}
        chat={chat}
        marked={marks[chat.id] !== undefined}
        hint={hints[chat.id]}
        amber={!quiet && page.amber.has(chat.id)}
        overdueLine={!desk && isNodeChat(chat) ? overdueReminder : null}
        pinned={isNodeChat(chat) ? desk || Boolean(overdueReminder) : chat.pinned}
        desk={desk}
        selected={desk && chat.id === activeChatId}
        entering={entering.has(chat.id)}
        face={chat.peerNodeId ? faces[chat.peerNodeId]?.avatar : null}
        leading={desk || selecting ? [] : leading}
        trailing={desk || selecting ? [] : trailing}
        check={check}
        onLongPress={
          selectable
            ? () => {
                haptic('long');
                setSwipedRow(null);
                togglePick(chat.id);
              }
            : undefined
        }
        swiped={swipedRow?.id === chat.id ? swipedRow.side : null}
        onSwiped={(side) => setSwipedRow(side ? { id: chat.id, side } : null)}
        pagerPrev={paged && pageAt > 0}
        onOpen={(el) => {
          if (selecting) {
            if (selectable) togglePick(chat.id);
            return;
          }
          chatMarkUnread.clear(chat.id);
          setSheetState('compact');
          if (searchOpen) closeSearch();
          if (open) return open();
          // Відкрити розмову (§6): на телефоні аватар і імʼя рядка перетікають
          // у шапку (360), а розмова наїжджає збоку; на ПК — згасання 150.
          withViewTransition(() => onSelectChat(chat.id), {
            pairs: desk
              ? []
              : [
                  [el.querySelector('[data-row-avatar]'), 'chat-avatar'],
                  [el.querySelector('[data-row-name]'), 'chat-name'],
                ],
            kind: desk ? 'vt-fade' : 'vt-push',
          });
        }}
        onActions={() => onOpenChatActions(chat)}
      />
    );
  };
  // Пошук — по всіх розмовах одразу (GlobalSearch): люди й листи окремими
  // групами, а не відфільтрований список розмов.
  const found = useGlobalSearch(chats, query, searchOpen);
  const openMessage = (chatId: string, messageId: string) => {
    closeSearch();
    if (onOpenMessage) onOpenMessage(chatId, messageId);
    else useMessengerStore.getState().openChatAtMessage(chatId, messageId);
  };
  // Порядок, якого чекає людина з Telegram: закріплені (PHANTOM першим на ПК,
  // далі «Збережене») — і далі за часом.
  const pinNode = desk || Boolean(overdueReminder);
  const pinnedFirst = useMemo(() => splitPinned(ordered, pinNode), [ordered, pinNode]);

  const closeSearch = () => {
    setQuery('');
    setSearchOpen(false);
    setAskNote(null);
  };
  // Перший рядок пошуку: запит — у закріплену розмову з Nightpost.
  const askFromSearch = () => {
    const outcome = askNightpost(q);
    if (outcome === 'notReady') setAskNote(t('askNightpost.chatNotReady'));
    else if (outcome === 'sent') closeSearch();
  };
  const voice = () => {
    soundFx.playTap();
    if (talkToNightpost()) closeSearch();
    else setAskNote(t('askNightpost.voiceUnavailable'));
  };
  // Пошук — шар у стеку системного BACK: на A52 BACK із відкритого пошуку
  // виходив із застосунку замість закрити пошук (02.10).
  const closeSearchRef = useRef(closeSearch);
  closeSearchRef.current = closeSearch;
  useEffect(() => registerPhoneLayer('list:search', searchOpen, () => closeSearchRef.current()), [searchOpen]);

  // Пʼять фільтрів (10 §2) на сталих місцях: людина вчить їх пальцем.
  const tabs = useMemo<FilterTab[]>(
    () => LIST_FILTERS.map((key) => ({ key, label: t(`msgV2.f.${key}`) })),
    [locale],
  );
  // Перший запуск (C1): міст завжди дає «Збережене» й Nightpost, тож порожнечу
  // рахуємо лише серед людей і груп — інакше «Додати людину» не видно ніколи.
  // Вузол не відповів — «нікого немає» не стверджуємо: каже рядок помилки.
  const noPeople =
    !q && !hydrateFailed && requests.length === 0 && ![...ordered, ...others, ...archived].some((c) => !isSystemChat(c));
  // До першої відповіді вузла порожній список — це «завантаження», а не «нікого».
  const waiting = !hydrated && !hydrateFailed && chats.length === 0;
  const sub = pick === 'requests' || pick === 'archive';
  // Без людей чипів нема — і застряглий зріз («Близькі» з «Я») не ховає «Збережене» й «Додати людину».
  const filter: ListPage = (pick === 'requests' && requests.length === 0) || (noPeople && !sub) ? 'all' : pick;
  // «Запити» й «Архів» — сторінки з «Назад»; системний BACK теж повертає до «Усі».
  useEffect(() => registerPhoneLayer('list:sub', sub, () => setPick('all')), [sub]);
  const tabsRef = useRef(tabs);
  tabsRef.current = tabs;
  useEffect(() => {
    const w = window as unknown as { __phantomOpenTab?: (key: string) => boolean };
    const open = (key: string) => {
      if (key === CALL_SETTINGS_TAB) {
        closeSearchRef.current();
        setCallsOpen(true);
        return true;
      }
      const page = pageForOpenKey(key);
      if (!page) return false;
      closeSearchRef.current();
      setPick(page);
      return true;
    };
    w.__phantomOpenTab = open;
    return () => {
      if (w.__phantomOpenTab === open) delete w.__phantomOpenTab;
    };
  }, []);
  // Вузол не відповів і показати нічого — чипи над порожньою сторінкою не ставимо.
  const showChips = !noPeople && !sub && !(hydrateFailed && chats.length === 0);
  const pages: string[] = showChips ? [...LIST_FILTERS] : [filter];
  const pageAt = pages.indexOf(filter);
  const paged = !desk && pages.length > 1 && pageAt >= 0 && !searchOpen && !q;
  const [leaving, setLeaving] = useState<string | null>(null);
  const shownRef = useRef<string>(filter);
  useEffect(() => {
    const prev = shownRef.current;
    if (prev === filter) return undefined;
    shownRef.current = filter;
    setScrolled(false);
    setBarHidden(false);
    lastScrollY.current = 0;
    scrollRun.current = 0;
    setSheetState('compact');
    setSwipedRow(null);
    if (!pages.includes(prev)) return undefined;
    setLeaving(prev);
    const id = window.setTimeout(() => setLeaving(null), 300);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter]);
  const archiveSections = (list: Chat[]) => {
    const names = endedProfileNames();
    const groups = new Map<string, Chat[]>();
    const rest: Chat[] = [];
    for (const c of list) {
      const owner = owners[c.id];
      if (owner && names[owner] !== undefined) groups.set(owner, [...(groups.get(owner) ?? []), c]);
      else rest.push(c);
    }
    return [
      ...[...groups].map(([id, group]) => ({ key: `ended-${id}`, label: t('profiles.tempEnded', { name: names[id] }), quiet: true, chats: group })),
      ...(rest.length ? [{ key: 'archive', quiet: true, chats: rest }] : []),
    ];
  };
  const derive = (page: ListPage) => {
    const sections: Array<{ key: string; label?: string; quiet?: boolean; chats: Chat[] }> =
      page === 'all'
        ? [
            ...(pinnedFirst.pinned.length > 0 ? [{ key: 'pinned', label: pinnedFirst.pinned.length > 3 ? t('phoneChatList.pinned') : undefined, chats: pinnedFirst.pinned }] : []),
            ...(pinnedFirst.rest.length > 0 ? [{ key: 'rest', chats: pinnedFirst.rest }] : []),
          ]
        : page === 'archive'
          ? archiveSections(archived)
          : page === 'requests'
            ? [{ key: 'requests', quiet: true, chats: requests }]
            : [
                {
                  key: 'rest',
                  chats: [
                    ...ordered.filter((c) => c.pinned && !isSystemChat(c) && inListFilter(c, page, marks[c.id] !== undefined)),
                    ...ordered.filter((c) => !c.pinned && !isSystemChat(c) && inListFilter(c, page, marks[c.id] !== undefined)),
                    ...(page === 'unread' ? ordered.filter((c) => isSystemChat(c) && inListFilter(c, page, marks[c.id] !== undefined)) : []),
                  ],
                },
              ];
    // Бурштин (np 2.13): прямі неприглушені й згадки, не більше трьох — згори вниз.
    const amberIds = new Set<string>();
    for (const c of sections.filter((sec) => !sec.quiet).flatMap((sec) => sec.chats)) {
      if (amberIds.size >= 3) break;
      if (c.muted || !(c.unreadCount > 0 || marks[c.id] !== undefined)) continue;
      if (chatKind(c) !== 'group' || rowSignals(c, { drafts: {}, me: { id: currentUser.id, name: currentUser.name } }).mention) amberIds.add(c.id);
    }
    return { sections, page: { amber: amberIds } };
  };
  const navKey = __PHONE_BUILD__
    ? null
    : q
      ? ''
      : (() => {
          const { sections } = derive(filter);
          const ids = sections.flatMap((sec) => sec.chats).map((c) => c.id);
          return Array.from(new Set(ids)).join('\n');
        })();
  useEffect(() => {
    if (navKey === null) return undefined;
    shownIds = navKey ? navKey.split('\n') : [];
    return () => {
      shownIds = null;
    };
  }, [navKey]);
  const gutter = desk ? 'px-2' : '';

  const openSearch = (seed?: string) => {
    soundFx.playTap();
    setPicked([]);
    setAskNote(null);
    setSearchOpen(true);
    if (typeof seed === 'string' && seed) setQuery(seed);
  };

  const emptyFor = (page: ListPage): React.ReactNode => {
    const I = { className: 'w-6 h-6', ...ICON };
    if (page === 'group') {
      return (
        <ListEmpty
          testId="empty-kind"
          icon={<Users {...I} />}
          title={t('phoneChatList.noGroups')}
          action={t('phoneChatList.createGroup')}
          onAction={onNewGroup ?? onNewChat}
        />
      );
    }
    if (page === 'close' || page === 'friends') {
      return (
        <ListEmpty
          testId="empty-kind"
          icon={<UserPlus {...I} />}
          title={page === 'close' ? t('msgV2.noClose') : t('msgV2.noFriends')}
          action={t('msgV2.addPerson')}
          onAction={() => setCirclePick(page as "close" | "friends")}
        />
      );
    }
    const quiet = page === 'unread' ? t('msgV2.noUnread') : page === 'archive' ? t('msgV2.noArchive') : page === 'requests' ? t('msgV2.noRequests') : null;
    if (!quiet) return null;
    return <EmptyState testId={`empty-${page}`} icon={page === 'archive' ? <Archive {...I} /> : <CheckCheck {...I} />} label={quiet} />;
  };

  const pageBody = (page: ListPage) => {
    const { sections, page: rows } = derive(page);
    const empty = sections.every((sec) => sec.chats.length === 0);
    const devicesBlock =
      !searchOpen &&
      page === 'all' &&
      devices.length > 0 && (
        <section aria-label={t('phoneChatList.devices')} data-list-section="devices">
          <FolderRow kind="devices" title={t('phoneChatList.devices')} chats={devices} open={devicesOpen} desk={desk} onToggle={() => setDevicesOpen((v) => !v)} />
          {devicesOpen && <ul>{devices.map((d) => renderRow(d, true, rows, () => setDeviceChat(d)))}</ul>}
          {devicesOpen && (
            <div data-own-device-rows className="[&>button]:min-h-[72px]">
              <AddOwnDevice />
            </div>
          )}
        </section>
      );
    if (searchOpen) {
      return (
        <div className={gutter}>
          <GlobalSearchResults
            query=""
            found={found}
            onOpenChat={(id) => {
              closeSearch();
              onSelectChat(id);
            }}
            onOpenMessage={openMessage}
          />
        </div>
      );
    }
    return (
      <div className={gutter}>
        {(page === 'requests' || page === 'archive') && (
          <div data-list-subhead={page} className="h-12 pl-1 pr-4 flex items-center gap-1">
            <button
              type="button"
              data-list-back
              onClick={() => {
                soundFx.playTap();
                setPick('all');
              }}
              aria-label={t('msgV2.back')}
              className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink active:bg-overlay"
            >
              <ChevronLeft className="w-6 h-6" {...ICON} />
            </button>
            <h2 className="min-w-0 truncate text-headline font-semibold text-ink">
              {page === 'requests' ? t('msgV2.requests') : t('msgV2.archive')}
            </h2>
          </div>
        )}
        {page === 'all' && archived.length > 0 && (
          <section aria-label={t('msgV2.archive')} data-list-section="archive-top">
            <ArchiveLine chats={archived} open={archiveOpen} onOpen={() => setPick('archive')} onFold={() => foldArchive(!archiveOpen)} />
            {archiveOpen && <ul data-archive-open>{archived.slice(0, 3).map((chat) => renderRow(chat, true, rows))}</ul>}
          </section>
        )}
        {page === 'all' && requests.length > 0 && (
          <FolderRow kind="requests" title={t('msgV2.requests')} chats={requests} desk={desk} onToggle={() => setPick('requests')} />
        )}
        {empty && emptyFor(page)}
        {!sections.some((sec) => sec.key === 'pinned') && devicesBlock}
        {sections.filter((sec) => sec.key === 'pinned').map((sec) => {
          const system = sec.chats.filter((c) => isNodeChat(c) || isSavedNotes(c));
          const own = sec.chats.filter((c) => !isNodeChat(c) && !isSavedNotes(c));
          return (
            <section key={sec.key} aria-label={sec.label || t('phoneChatList.pinned')} data-list-section={sec.key}>
              {sec.label && <h2 className="px-4 pt-4 pb-1 text-caption text-ink-3">{sec.label}</h2>}
              {system.length > 0 && <ul>{system.map((chat) => renderRow(chat, Boolean(sec.quiet), rows))}</ul>}
              {devicesBlock}
              {own.length > 0 && <ul>{own.map((chat) => renderRow(chat, Boolean(sec.quiet), rows))}</ul>}
            </section>
          );
        })}
        {sections.filter((sec) => sec.key !== 'pinned' && sec.chats.length > 0).map((sec) => (
          <section
            key={sec.key}
            aria-label={sec.label || (sec.key === 'archive' ? t('msgV2.archive') : sec.key === 'requests' ? t('msgV2.requests') : t('phoneChatList.chats'))}
            data-list-section={sec.key}
          >
            {sec.label && <h2 className="px-4 pt-4 pb-1 text-caption text-ink-3">{sec.label}</h2>}
            <ul>{sec.chats.map((chat) => renderRow(chat, Boolean(sec.quiet), rows))}</ul>
          </section>
        ))}
        {page === 'all' && noPeople && (
          <ListEmpty
            testId="list-empty"
            inline={sections.some((sec) => sec.chats.length > 0) || devices.length > 0}
            icon={<UserPlus className="w-6 h-6" {...ICON} />}
            title={t('phoneChatList.emptyTitle')}
            action={t('phoneChatList.addPerson')}
            onAction={onNewChat}
          />
        )}
        {page === 'all' && others.length > 0 && (
          <section aria-label={t('phoneChatList.others')} data-list-section="others">
            <FolderRow kind="others" title={t('phoneChatList.others')} chats={others} open={othersOpen} desk={desk} onToggle={() => setOthersOpen((v) => !v)} />
            {othersOpen && <ul data-others-open>{others.map((chat) => renderRow(chat, true, rows))}</ul>}
          </section>
        )}
        {page === 'all' && <PageRequestsRow desk={desk} />}
        {page === 'all' && reminders.total > 0 && (
          <ListRow
            data-folder-row="reminders"
            data-testid="reminders-line"
            onClick={() => {
              soundFx.playTap();
              openRemindersSheet();
            }}
            density={desk ? 'desk' : 'phone'}
            leading={<SystemMark kind="reminders" desk={desk} />}
            title={t('day.reminders', { n: reminders.total })}
          />
        )}
        <div aria-hidden className="h-6" />
      </div>
    );
  };

  const settleTrack = (next: number) => {
    if (frame.current) cancelAnimationFrame(frame.current);
    frame.current = 0;
    driftSource.set(null);
    const track = trackRef.current;
    if (track) {
      track.style.transition = '';
      track.style.transform = trackAt(next, 0);
    }
    setDragging(false);
  };

  // Сторінку змінено будь-чим (чип, подія, жест) — тяга, що лишилась без touchend, знімається.
  useLayoutEffect(() => {
    if (frame.current) cancelAnimationFrame(frame.current);
    frame.current = 0;
    swipe.current = null;
    driftSource.set(null);
    const track = trackRef.current;
    if (track) track.style.transition = '';
    setDragging(false);
  }, [pageAt, paged, driftSource]);
  useEffect(() => () => {
    if (frame.current) cancelAnimationFrame(frame.current);
  }, []);

  const handleListScroll = (el: HTMLElement) => {
    const y = el.scrollTop;
    const dy = y - lastScrollY.current;
    lastScrollY.current = y;
    if (y > 24 !== scrolled) setScrolled(y > 24);
    if (y > 24 && sheetState === 'open') setSheetState('compact');
    if (desk || performance.now() - barToggledAt.current < BAR_SETTLE_MS) {
      scrollRun.current = 0;
      return;
    }
    if ((dy > 0 && scrollRun.current < 0) || (dy < 0 && scrollRun.current > 0)) scrollRun.current = 0;
    scrollRun.current += dy;
    const next =
      y <= 0 || scrollRun.current <= -BAR_HIDE_DIST
        ? false
        : scrollRun.current >= BAR_HIDE_DIST && y > 24
          ? true
          : barHidden;
    if (next !== barHidden) {
      barToggledAt.current = performance.now();
      scrollRun.current = 0;
      setBarHidden(next);
    }
  };

  const momentsPane = !desk && !sub && pane === 'moments';
  const unreadOf = (c: Chat) => c.unreadCount > 0 || marks[c.id] !== undefined;
  const caps = selectionCaps(pickedChats, { unread: unreadOf, canDelete: Boolean(onDeleteChat) });
  const flip = (key: 'pinned' | 'muted' | 'archived', want: boolean, run: (id: string) => void) => {
    needsToggle(pickedChats, key, want).forEach((c) => run(c.id));
    exitSelect();
  };
  const selectBar = selecting ? (
    <ListSelectBar
      count={pickedChats.length}
      caps={caps}
      onExit={exitSelect}
      onPin={() => flip('pinned', caps.pin === 'pin', (id) => useMessengerStore.getState().togglePinChat(id))}
      onMute={() => flip('muted', caps.mute === 'mute', (id) => useMessengerStore.getState().toggleMuteChat(id))}
      onArchive={() => flip('archived', caps.archive === 'archive', (id) => useMessengerStore.getState().toggleArchiveChat(id))}
      onRead={() => {
        for (const c of pickedChats) {
          chatMarkUnread.clear(c.id);
          if (c.unreadCount > 0 && onMarkRead) void onMarkRead(c);
        }
        exitSelect();
      }}
      onDelete={() => setConfirmDelete(pickedChats)}
      onSend={() => {
        // Той самий шлях, що мала смуга людей: адресати → «Кому» → смуга надсилання.
        setSendTo(pickedChats.filter((c) => !isNodeChat(c)).map((c) => c.id));
        exitSelect();
        setPickerOpen(true);
      }}
      onAllActions={
        pickedChats.length === 1
          ? () => {
              const only = pickedChats[0];
              exitSelect();
              onOpenChatActions(only);
            }
          : undefined
      }
    />
  ) : undefined;

  return (
    <section data-testid="chat-list" data-list-page={filter} data-search-open={searchOpen ? 'true' : undefined} className={`${searchOpen && !desk ? 'fixed inset-0 z-[1200] pt-[var(--sat)]' : 'relative w-full h-full'} flex flex-col bg-canvas text-ink font-sans`}>
      {momentsPane ? (
        <MomentsHome data={moments} me={currentUser} onPane={setPane} onOpenChat={onSelectChat} />
      ) : (
      <>
      <header className="shrink-0 bg-canvas">
        {searchOpen ? (
          <div className="h-14 pl-4 pr-2 flex items-center gap-1">
            <AskField
              data-search-box
              inputRef={searchInputRef}
              autoFocus
              value={query}
              onChange={(v) => {
                setQuery(v);
                setAskNote(null);
              }}
              label={ask ? t('phoneChatList.searchOrAsk') : t('phoneChatList.searchAll')}
              placeholder={ask ? t('phoneChatList.searchShort') : t('phoneChatList.searchPeople')}
              onVoice={ask ? voice : undefined}
              voiceLabel={t('askNightpost.talk')}
              voiceTestId="search-ask-voice"
            />
            <button type="button" onClick={closeSearch} className="h-11 px-3 shrink-0 text-callout font-medium text-ink-2 rounded-control active:bg-overlay">
              {t('phoneChatList.cancel')}
            </button>
          </div>
        ) : (
          <TopSheet
            state={sheetState}
            onStateChange={setSheetState}
            showChips={showChips}
            scrolled={scrolled}
            hidden={barHidden}
            ask={ask}
            desk={desk}
            onVoice={ask ? voice : undefined}
            onOpenSearch={openSearch}
            onNewChat={onNewChat}
            onOpenDay={() => setDaySheetOpen(true)}
            tabs={tabs}
            activeTab={filter}
            onPickTab={setPick}
            driftSource={driftSource}
            profileSlot={allProfiles.length > 1 ? <ChatsProfileTitle fallback="" slot /> : undefined}
            panes={desk || sub ? undefined : { onMoments: () => setPane('moments'), dot: momentsUnseen }}
            dayLine={desk ? undefined : <DayStrip items={day.view.items} nowMs={dayNow} onOpen={() => setSheetState('open')} />}
            selectBar={selectBar}
            day={
              desk ? undefined : (
                <DayBand
                  top={day.view.top}
                  items={day.view.items}
                  total={day.view.items.length}
                  status={day.status}
                  calendar={day.calendar}
                  nowMs={dayNow}
                  onOpenAll={() => setDaySheetOpen(true)}
                  onOpenItem={(item) => {
                    setSheetState('compact');
                    openDayItem(item, { chat: onSelectChat, all: () => setDaySheetOpen(true) });
                  }}
                  onAskCalendar={() => void dayApi.askCalendar().then(day.refresh, () => undefined)}
                  onDone={(item) => void (item.kind === 'reminder' ? dayRun(() => dayApi.removeReminder(item.id), true) : dayRun(() => dayApi.done(item.id), false))}
                  onSnooze={(item) => void dayRun(() => dayApi.snooze(item.id, 60), true)}
                  onCreateReminder={(text, at) => dayRun(() => dayApi.createReminder(text, at), true)}
                />
              )
            }
          />
        )}
        {!searchOpen && <TempEndingNotice />}
        {askNote && (
          <p role="status" data-testid="ask-note" className="px-4 pb-2 text-footnote text-ink-2">
            {askNote}
          </p>
        )}
        {!searchOpen && hydrateFailed && (
          <div role="alert" data-testid="list-load-failed" className="min-h-12 pl-4 pr-2 flex items-center gap-3">
            <span className="flex-1 min-w-0 truncate text-callout text-ink-2">{t('msgV2.loadFailed')}</span>
            <button
              type="button"
              data-testid="list-retry"
              onClick={() => {
                soundFx.playTap();
                void useMessengerStore.getState().hydrateFromNode({ openFirst: false });
              }}
              className="h-12 min-h-0 px-3 shrink-0 rounded-full flex items-center gap-1.5 text-callout font-medium text-ink active:bg-overlay"
            >
              <RotateCw className="w-4 h-4" {...ICON} />
              {t('msgV2.retry')}
            </button>
          </div>
        )}
        {!searchOpen && queued > 0 && (
          <p role="status" data-testid="list-queued" className="min-h-9 px-4 flex items-center gap-2 text-footnote text-ink-3">
            <Clock className="w-4 h-4 shrink-0" {...ICON} />
            <span className="truncate">{t('outbox.queuedStack', { n: queued })}</span>
          </p>
        )}
      </header>

      {paged ? (
        <div
          data-today-pager
          ref={pagerRef}
          className="relative flex-1 min-h-0 overflow-hidden"
          style={{ touchAction: 'pan-y' }}
          onTouchStart={(e) => {
            if (ownsHorizontalSwipe(e.target, e.currentTarget)) {
              swipe.current = null;
              return;
            }
            const p = e.touches[0];
            swipe.current = { x: p.clientX, y: p.clientY, w: e.currentTarget.clientWidth || window.innerWidth || 360, lock: null, d: 0 };
          }}
          onTouchMove={(e) => {
            const st = swipe.current;
            if (!st) return;
            const p = e.touches[0];
            const dx = p.clientX - st.x;
            const dy = p.clientY - st.y;
            if (st.lock === null && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) st.lock = Math.abs(dx) > Math.abs(dy) * 1.3 ? 'h' : 'v';
            if (st.lock !== 'h') return;
            const edge = (pageAt === 0 && dx > 0) || (pageAt === pages.length - 1 && dx < 0);
            st.d = edge ? dx / 3 : dx;
            if (!dragging) setDragging(true);
            if (frame.current) return;
            frame.current = requestAnimationFrame(() => {
              frame.current = 0;
              const live = swipe.current;
              const track = trackRef.current;
              if (!live || !track) return;
              track.style.transition = 'none';
              track.style.transform = trackAt(pageAt, live.d);
              driftSource.set(pageAt - live.d / live.w);
            });
          }}
          onTouchEnd={() => {
            const st = swipe.current;
            swipe.current = null;
            const turn = Boolean(st && st.lock === 'h' && Math.abs(st.d) >= Math.min(st.w * 0.2, 72));
            const next = turn && st ? Math.max(0, Math.min(pages.length - 1, pageAt + (st.d < 0 ? 1 : -1))) : pageAt;
            settleTrack(next);
            if (next !== pageAt) {
              haptic('tap');
              setPick(pages[next] as ListPage);
            }
          }}
          onTouchCancel={() => {
            swipe.current = null;
            settleTrack(pageAt);
          }}
        >
          <div
            ref={trackRef}
            data-today-track
            data-active-page={filter}
            className="flex h-full transition-transform duration-standard ease-standard"
            style={{ transform: trackAt(pageAt, 0) }}
          >
            {pages.map((key, i) => (
              <div
                key={key}
                data-today-page={key}
                data-phantom-list={i === pageAt ? '' : undefined}
                ref={i === pageAt ? listRef : undefined}
                aria-hidden={i === pageAt ? undefined : true}
                onScroll={(e) => {
                  if (i !== pageAt) return;
                  handleListScroll(e.currentTarget as HTMLElement);
                }}
                className="w-full shrink-0 h-full overflow-y-auto no-scrollbar"
                style={{ paddingBottom: 'var(--sab)' }}
              >
                {(i === pageAt || key === leaving || (dragging && Math.abs(i - pageAt) === 1)) && pageBody(key as ListPage)}
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div
          data-phantom-list
          ref={listRef}
          {...(desk ? pullDown : {})}
          onScroll={(e) => handleListScroll(e.currentTarget as HTMLElement)}
          className="flex-1 min-h-0 overflow-y-auto no-scrollbar"
          style={{ paddingBottom: 'var(--sab)' }}
        >
          {waiting && !searchOpen ? (
            <div data-testid="list-loading" role="status" className="pt-[18vh] flex flex-col items-center gap-3 text-callout text-ink-3">
              <Loader2 className="w-6 h-6 animate-spin motion-reduce:animate-none" {...ICON} />
              <span>{t('msgV2.loading')}</span>
            </div>
          ) : q ? (
            <>
              {ask && (
                <button
                  type="button"
                  data-testid="search-ask"
                  onClick={() => {
                    soundFx.playTap();
                    askFromSearch();
                  }}
                  className="w-full min-h-[60px] px-4 py-2 flex items-center gap-3 text-left bg-canvas active:bg-overlay transition-colors duration-quick"
                >
                  <PhantomMark size="w-10 h-10" />
                  <span className="min-w-0 flex-1 truncate text-headline text-ink">{t('phoneChatList.askRow', { query: q })}</span>
                </button>
              )}
              <GlobalSearchResults
                query={query}
                found={found}
                onOpenChat={(id) => {
                  closeSearch();
                  onSelectChat(id);
                }}
                onOpenMessage={openMessage}
              />
            </>
          ) : (
            pageBody(filter)
          )}
        </div>
      )}
      </>
      )}
      {!desk && !searchOpen && !momentsPane && <SendManyDock chats={chats} selected={sendTo} onChange={setSendTo} />}
      {pickerOpen && <RecipientSheet chats={chats} selected={sendTo} onChange={setSendTo} onClose={() => setPickerOpen(false)} />}
      {!desk && <MomentsStrip variant="host" chats={chats} onOpenChat={onSelectChat} />}
      <RemindersLine refreshKey={activeChatId} line={false} onSummary={setReminders} />
      {confirmDelete && (
        <FormSheet
          open
          onClose={() => setConfirmDelete(null)}
          title={t('msgV2.sel.deleteTitle', { n: confirmDelete.length })}
          layerKey="list-delete"
          testId="list-delete-sheet"
        >
          <div role="group" className="pb-2">
            <DoorRow
              tone="danger"
              icon={<Trash2 className="w-5 h-5" {...ICON} />}
              label={t('msgV2.sel.delete')}
              onClick={() => {
                soundFx.playTap();
                const doomed = confirmDelete;
                setConfirmDelete(null);
                exitSelect();
                if (onDeleteChat) void (async () => { for (const c of doomed) await onDeleteChat(c); })();
              }}
            />
          </div>
        </FormSheet>
      )}
      {deviceChat && (
        <DeviceSheet
          title={displayTitle(deviceChat.title)}
          peerNodeId={deviceChat.peerNodeId}
          onClose={() => setDeviceChat(null)}
          onWrite={() => {
            setDeviceChat(null);
            onSelectChat(deviceChat.id);
          }}
        />
      )}
      {(desk || daySheetOpen) && (
        <DaySheetHost
          open={daySheetOpen}
          day={day}
          nowMs={dayNow}
          onClose={() => setDaySheetOpen(false)}
          onOpenChat={(chatId) => {
            setSheetState('compact');
            onSelectChat(chatId);
          }}
        />
      )}
      {callsOpen && (
        <Suspense fallback={null}>
          <CallSettingsSheet active={false} onClose={() => setCallsOpen(false)} />
        </Suspense>
      )}
      {circlePick && <CirclePickSheet circle={circlePick} onClose={() => setCirclePick(null)} />}
    </section>
  );
};

/** Імʼя для нового коду: список той самий на телефоні й на ПК. */
export const ChatList = PhoneChatList;

export default PhoneChatList;
