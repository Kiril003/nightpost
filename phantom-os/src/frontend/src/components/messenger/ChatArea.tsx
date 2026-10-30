import { useTranslation } from '../../i18n/useTranslation';
import React, { useState, useRef, useEffect, useCallback, useImperativeHandle, forwardRef, lazy, Suspense } from 'react';
import { plainText } from '../../services/plainText';
import { ASSISTANT_TITLE } from '../../services/assistantChat';
import { isFeedHidden } from '../../services/serviceNotice';
import { keyChangeRow, withKeyChange } from '../../services/keyChange';
import { callLineRows, useCallLog, withCallLines } from '../../services/callLog';
import { rememberTappedMedia, takeTappedMedia, withViewTransition } from '../../services/viewTransition';
import { FileText, Info, Quote, Bookmark, PenLine, CircleHelp, CalendarDays } from 'lucide-react';
import { Message, LocationData, TableData, TaskListData, Chat } from '../../types/messenger';
import { isNodeChat, isSavedNotes } from './chatListFormat';
import { PhantomMark } from './PhantomMark';
import type { MessageKey } from '../../i18n/messages/uk';
import { translate } from '../../i18n';
import { soundFx } from '../../utils/messengerSound';
import { messengerApi, SAVED_NOTES_ID } from '../../services/messengerApi';
import { useMessengerStore } from '../../stores/messengerStore';
import { liveThinking, useAssistantLive, withLiveLine } from '../../services/assistantLive';
import { collapseLiveLocations } from '../../services/messengerGeo';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { ReactionPickerModal } from './ReactionPickerModal';
import { MessageDetailsModal } from './MessageDetailsModal';
import { DeleteMessageModal } from './DeleteMessageModal';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import type { CanvasInboxItem } from './CanvasSplitView';
import { useSpaceStore } from '../../stores/spaceStore';
import { isPhoneShell } from './phoneShell';
import { WhereCards } from './WhereCard';
import { wherePeerOf } from '../../services/peerWhere';
import { namesakeNotice } from '../../services/peopleIdentity';
import { buildFeedRows, reconcileRows, type FeedRow } from './feed/feedModel';
import { foldMode, planFold, type FoldRun } from '../../services/signalFold';
import { FeedActionsContext, useStableFeedActions } from './feed/feedActions';
import { MessageRow } from './feed/MessageRow';
import { VirtualFeed, type VirtualFeedHandle } from './feed/VirtualFeed';
import { MessageActionSheet } from './feed/MessageActionSheet';
import { ReportSheetHost } from '../support/ReportSheet';
import { useSupportStore } from '../../stores/supportStore';
import { dayBounds, firstMessageOnOrAfter } from './feed/chatDates';
import { ChatSearchBar, GroupPinBar, PinnedBanner } from './feed/FeedBanners';
import { useMessageAssist } from './feed/useMessageAssist';
import { useRowGestures } from './feed/useRowGestures';
import { letterPreview } from './feed/feedParts';
import { mergeSelection, rangeIds } from '../../services/msgSelection';
import { useLook } from '../../services/appearance';
import { resolveLook, useRingPresets } from '../../services/ringPresets';

const normPeer = (s?: string) => (s || '').toLowerCase().replace(/^node_/, '');

export { quickReactions, translationLanguages } from './feed/feedParts';

function messageSentAtIso(m: Message): string | null {
  const rec = m as Message & {
    createdAt?: string | number | null;
    sentAt?: string | number | null;
    sentAtIso?: string | null;
    timestamp?: string | number | null;
  };
  const raw = rec.createdAt ?? rec.sentAtIso ?? rec.sentAt ?? rec.timestamp;
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return new Date(raw).toISOString();
  }
  if (typeof raw === 'string' && raw.trim().length > 0) {
    return raw.trim();
  }
  return null;
}

/** Найновіший лист співрозмовника, якщо після нього ще немає нашої відповіді. */
export function lastPeerMessageOf(
  messagesOrChat: readonly Message[] | { messages?: readonly Message[] } | null | undefined,
  currentUserId: string,
): { id: string; text: string; sentAtIso?: string | null } | null {
  const messages = Array.isArray(messagesOrChat)
    ? messagesOrChat
    : (messagesOrChat as { messages?: readonly Message[] } | null | undefined)?.messages ?? [];
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (!m || isFeedHidden(m) || m.senderId === 'system') continue;
    if (m.senderId === currentUserId || m.isSelf) return null;
    return { id: m.id, text: m.text ?? '', sentAtIso: messageSentAtIso(m) };
  }
  return null;
}

// Документ розмови — лише на компʼютері. Літерал `__PHONE_BUILD__` відтинає
// гілку ще до складання, тож у телефонний бандл CanvasSplitView не їде зовсім
// (раніше їхав статично, з «Wasm Live Code» усередині, хоч телефон його не
// показував ніколи).
const CanvasSplitView = __PHONE_BUILD__
  ? null
  : lazy(() => import('./CanvasSplitView').then((m) => ({ default: m.CanvasSplitView })));

interface ChatAreaProps {
  currentChat?: Chat;
  messages: Message[];
  currentUserId: string;
  onOpenLocation: (loc: LocationData) => void;
  onVotePoll: (messageId: string, optionId: string) => void;
  onPayBillShare: (messageId: string, participantId: string) => void;
  /**
   * Повертає причину відмови словами, або null — позначка поїхала.
   *
   * Обіцянка `void` тут і була вадою: сторінка казала «Реакцію надіслано»
   * незалежно від того, чи щось вийшло з апарата.
   */
  onAddReaction: (messageId: string, emoji: string) => Promise<string | null> | void;
  onReplyMessage: (msg: Message, quoteSelectedText?: string) => void;
  onEditMessage?: (msg: Message) => void;
  /** Повертає застереження словами, або null — вузол зробив те, що просили. */
  onDeleteMessage?: (msgId: string, forEveryone?: boolean) => Promise<string | null> | void;
  onTogglePinMessage?: (msgId: string) => void;
  onForwardMessage?: (msg: Message) => void;
  onSelectMemberByName?: (name: string) => void;
  selectedMessageIds: string[];
  onToggleSelectMessage: (id: string) => void;
  /** Замінити вибране цілком — проміжок тягнеться від `selectionAnchorId`. */
  onSelectMessages?: (ids: string[]) => void;
  selectionAnchorId?: string | null;
  isSelectionMode: boolean;
  onUpdateTableData?: (messageId: string, updatedTable: TableData) => void;
  onUpdateTaskListData?: (messageId: string, updatedTaskList: TaskListData) => void;
  onOpenImageLightbox?: (imgUrl: string, title?: string) => void;
  isSearching: boolean;
  onCloseSearch: () => void;
  /**
   * Лист, до якого стрибнути, щойно він зʼявиться в стрічці (пошук по всіх
   * розмовах). Стрічка доїжджає з вузла вже після відкриття, тож намір чекає.
   */
  jumpToMessageId?: string;
  onJumped?: () => void;
  isAiTyping?: boolean;
  onOpenWhere?: () => void;
}

// Шапка не має власного доступу до стрічки: закріплене гортає ChatArea.
export interface ChatAreaHandle {
  scrollToPinned: () => void;
}

/** Історія ще йде: підпис зʼявляється лише коли чекати довше миті, щоб швидке відкриття не блимало. */
const HistoryLoading: React.FC = () => {
  const { t } = useTranslation();
  const [late, setLate] = React.useState(false);
  React.useEffect(() => {
    const id = setTimeout(() => setLate(true), 400);
    return () => clearTimeout(id);
  }, []);
  return (
    <div className="px-4 pt-10 pb-4 text-left select-none" data-testid="thread-loading" aria-busy="true">
      {late && <p className="text-body text-ink-3">{t('coreChat.loading')}</p>}
    </div>
  );
};

/** Повний текст листа для пошуку в розмові. */
function matchesQuery(m: Message, q: string, extra: string[]): boolean {
  const has = (s?: string) => !!s && s.toLowerCase().includes(q);
  return (
    has(plainText(m.text)) ||
    has(m.senderName) ||
    has(m.voiceData?.transcript) ||
    has(m.locationData?.name) ||
    has(m.locationData?.address) ||
    has(m.pollData?.question) ||
    !!m.pollData?.options?.some((opt) => has(opt.text)) ||
    has(m.splitBillData?.title) ||
    !!m.splitBillData?.participants?.some((p) => has(p.name)) ||
    has(m.tableData?.title) ||
    has(m.chartData?.title) ||
    has(m.taskListData?.title) ||
    has(m.fileData?.name) ||
    has(m.media?.name) ||
    has(m.fileName) ||
    has(m.codeData?.title) ||
    has(m.codeData?.code) ||
    has(m.imageData?.caption) ||
    extra.some(has)
  );
}

/**
 * Розмова: смуги закріпленого й пошуку, віртуальна стрічка, аркуш дій,
 * спліт-документ ПК і модалки над листом.
 *
 * ChatArea тепер — диригент. Рядки малює `feed/MessageRow` (memo), стрічку
 * гортає `feed/VirtualFeed` (справжня віртуалізація), а стан, що стосується
 * одного листа (наведення, свайп), живе в самому рядку. Тож зміна стану тут
 * будить лише ті рядки, чиї пропси справді змінились.
 */
/** Перші питання до PHANTOM — лише те, на що він справді відповідає. */
const PHANTOM_STARTER_KEYS: MessageKey[] = ['chatArea.starterAbilities', 'chatArea.starterToday', 'chatArea.starterWrite'];
/** Українські тексти підказок — для тестів і місць, де мова ще не обрана. */
export const PHANTOM_STARTERS = PHANTOM_STARTER_KEYS.map((k) => translate(k, 'uk'));
const PHANTOM_STARTERS_UI = PHANTOM_STARTER_KEYS.map((key, i) => ({ key, Icon: [CircleHelp, CalendarDays, PenLine][i] }));

export const ChatArea = forwardRef<ChatAreaHandle, ChatAreaProps>(({
  currentChat,
  messages: allMessages,
  currentUserId,
  onOpenLocation,
  onPayBillShare,
  onAddReaction,
  onReplyMessage,
  onEditMessage,
  onDeleteMessage,
  onTogglePinMessage,
  onForwardMessage,
  onSelectMemberByName,
  selectedMessageIds,
  onToggleSelectMessage,
  onSelectMessages,
  selectionAnchorId = null,
  isSelectionMode,
  onOpenImageLightbox,
  isSearching,
  onCloseSearch,
  jumpToMessageId,
  onJumped,
  isAiTyping = false,
  onOpenWhere,
}, ref) => {
  const { t, locale } = useTranslation();
  const wherePeer = wherePeerOf(currentChat);
  const resolved = resolveLook(currentChat, useLook((s) => s.look), useRingPresets((s) => s.presets));
  const namesake = useMessengerStore((s) => (currentChat ? namesakeNotice(currentChat, s.chats) : null));
  const liveLine = useAssistantLive((s) => (currentChat ? s.lines[currentChat.id] : undefined));
  const aiThinking = isAiTyping || liveThinking(liveLine, allMessages || []);
  const callLog = useCallLog((s) => s.entries);
  const fedMessages = React.useMemo(
    () => withLiveLine(allMessages || [], liveLine, currentChat?.peerNodeId || currentChat?.id || '', currentUserId),
    [allMessages, liveLine, currentChat?.peerNodeId, currentChat?.id, currentUserId],
  );
  /**
   * Керівних кадрів меша у розмові немає ВЗАГАЛІ: жива позначка, її відклик,
   * шматок файла, обгортка групи й системна дія — не сказане людиною
   * (`PeerOutbox.isSpoken`). Без фільтра такий лист лишав порожню бульбашку.
   */
  const messages = React.useMemo(
    () => // Службові листи (керівні кадри, речення рідного боку про рід, якого ця
    // збірка не малює) бульбашкою не стають: services/serviceNotice.
    withCallLines(
      withKeyChange(
        collapseLiveLocations(fedMessages.filter((m) => !isFeedHidden(m))),
        currentChat ? keyChangeRow(currentChat) : null,
      ),
      currentChat ? callLineRows(callLog, currentChat) : [],
    ),
    [fedMessages, callLog, currentChat?.id, currentChat?.title, currentChat?.peerNodeId, currentChat?.contactKeyChanged, currentChat?.contactKeyChangedAt],
  );
  const phone = __PHONE_BUILD__ || isPhoneShell();

  // Рядки стрічки зберігають тотожність між рендерами — на цьому тримається memo рядка.
  const [openFolds, setOpenFolds] = useState<Set<string>>(() => new Set());
  useEffect(() => setOpenFolds(new Set()), [currentChat?.id]);
  const foldsRef = useRef<FoldRun[]>([]);
  const meName = useMessengerStore((s) => s.currentUser.name);
  const meHandle = useMessengerStore((s) => (s.currentUser as { handle?: string }).handle);
  // Підпис кілець людей — рядок, тож стрічка перебудовується лише коли кільця справді змінились.
  const ringSig = useMessengerStore((s) =>
    s.chats
      .filter((c) => c.peerNodeId)
      .map((c) => `${normPeer(c.peerNodeId)}=${(c.circles?.length ? c.circles : c.circle ? [c.circle] : []).join(',')}`)
      .join('|'),
  );
  const prevRowsRef = useRef<FeedRow[] | null>(null);
  const rows = React.useMemo(() => {
    let folds: FoldRun[] = [];
    if (foldMode(currentChat) !== 'off') {
      const rings = new Map(ringSig.split('|').filter(Boolean).map((e) => {
        const [id, list] = e.split('=');
        return [id, list ? list.split(',') : []] as const;
      }));
      const meNames = [meName, meHandle].filter((x): x is string => Boolean(x)).map((x) => x.replace(/^@/, '').toLowerCase());
      folds = planFold(messages, { meNames, ringsOf: (id) => rings.get(normPeer(id)) ?? [] }).filter((r) => !openFolds.has(r.ids[0]));
    }
    foldsRef.current = folds;
    const next = reconcileRows(prevRowsRef.current, buildFeedRows(messages, currentUserId, undefined, folds));
    prevRowsRef.current = next;
    return next;
  }, [messages, currentUserId, locale, openFolds, currentChat, meName, meHandle, ringSig]);
  const pendingJumpRef = useRef<string | null>(null);
  const unreadSnap = useRef<{ chatId: string; n: number; key: string | null; resolved: boolean } | null>(null);
  if (currentChat && unreadSnap.current?.chatId !== currentChat.id) {
    unreadSnap.current = { chatId: currentChat.id, n: useMessengerStore.getState().unreadAtOpen[currentChat.id] ?? currentChat.unreadCount ?? 0, key: null, resolved: false };
  }
  const snap = unreadSnap.current;
  if (snap && !snap.resolved && rows.length > 0) {
    snap.resolved = true;
    const from = snap.n > 0 ? Math.max(0, rows.length - snap.n) : -1;
    const firstIdx = from < 0 ? -1 : rows.findIndex((r, i) => i >= from && !r.isSelf);
    snap.key = firstIdx >= 0 ? rows[firstIdx].key : null;
    if (snap.key && firstIdx > 0) pendingJumpRef.current = snap.key;
  }
  useEffect(() => {
    const id = pendingJumpRef.current;
    if (!id) return;
    const land = id === unreadSnap.current?.key;
    const raf = requestAnimationFrame(() => {
      if (feedRef.current?.jumpTo(id, { land })) pendingJumpRef.current = null;
    });
    return () => cancelAnimationFrame(raf);
  }, [rows]);

  const [expandedMsgIds, setExpandedMsgIds] = useState<Record<string, boolean>>({});
  const [copiedCodeId, setCopiedCodeId] = useState<string | null>(null);
  const [contextMenuMsg, setContextMenuMsg] = useState<Message | null>(null);
  const typingStatus = useMessengerStore((s) => s.typingStatus[currentChat?.id || '']);
  const historyLoad = useMessengerStore((s) => {
    const h = currentChat ? s.historyLoad[currentChat.id] : undefined;
    return h === 'read' ? undefined : h;
  });
  const currentUserName = useMessengerStore((s) => s.currentUser.name);

  // Довгий тап відкриває шторку дій, а вона кладе заслінку на весь екран.
  // Escape її не закривав — і телефон стояв мертвий.
  const closeContextMenu = useCallback(() => setContextMenuMsg(null), []);
  useEscapeClose(!!contextMenuMsg, closeContextMenu);

  const [isReactionModalOpen, setIsReactionModalOpen] = useState(false);
  const [targetReactionMsgId, setTargetReactionMsgId] = useState<string | null>(null);
  useEffect(
    () => registerPhoneLayer('chat:context-menu', !!contextMenuMsg, closeContextMenu),
    [contextMenuMsg, closeContextMenu],
  );
  useEffect(
    () => registerPhoneLayer('chat:reaction-picker', isReactionModalOpen, () => setIsReactionModalOpen(false)),
    [isReactionModalOpen],
  );
  const [inspectingMessage, setInspectingMessage] = useState<Message | null>(null);
  const [deletingMessage, setDeletingMessage] = useState<Message | null>(null);

  const [toastNotification, setToastNotification] = useState<string | null>(null);
  const showToast = (text: string) => {
    setToastNotification(text);
    setTimeout(() => setToastNotification(null), 2500);
  };
  const assist = useMessageAssist(showToast);

  const [isCanvasSplitOpen, setIsCanvasSplitOpen] = useState(false);
  const [canvasWidthMode, setCanvasWidthMode] = useState<'half' | 'wide' | 'full'>('half');
  // «У документ» (виділення, аркуш дій) — поштова скринька документа. Подію
  // ловить ChatArea, а не сам документ: той вантажиться ліниво й у мить події
  // ще не змонтований, і фрагмент, надісланий у закритий документ, губився.
  const [canvasInbox, setCanvasInbox] = useState<CanvasInboxItem[]>([]);
  useEffect(() => {
    if (__PHONE_BUILD__) return;
    const onAdd = (e: Event) => {
      const d = (e as CustomEvent<{ text?: string; type?: CanvasInboxItem['type'] }>).detail;
      if (!d?.text) return;
      setCanvasInbox((q) => [...q, { id: `in_${Date.now()}_${q.length}`, text: d.text!, type: d.type }]);
      setIsCanvasSplitOpen(true);
    };
    window.addEventListener('phantom:add-to-canvas', onAdd);
    return () => window.removeEventListener('phantom:add-to-canvas', onAdd);
  }, []);
  const [splitWidth, setSplitWidth] = useState(460);
  const [isDraggingSplit, setIsDraggingSplit] = useState(false);

  const startDragSplit = (e: React.MouseEvent) => {
    e.preventDefault();
    setIsDraggingSplit(true);
    const startX = e.clientX;
    const startWidth = splitWidth;
    const onMouseMove = (moveEvent: MouseEvent) => {
      const delta = startX - moveEvent.clientX;
      setSplitWidth(Math.max(340, Math.min(window.innerWidth - 360, startWidth + delta)));
    };
    const onMouseUp = () => {
      setIsDraggingSplit(false);
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
  };

  // Плаваюча «Цитувати» над виділеним фрагментом.
  const [selectedTextSnippet, setSelectedTextSnippet] = useState<{
    text: string;
    msg: Message;
    x: number;
    y: number;
  } | null>(null);

  const feedRef = useRef<VirtualFeedHandle>(null);

  const [chatSearchQuery, setChatSearchQuery] = useState('');
  const [searchMatchIndex, setSearchMatchIndex] = useState(0);
  // Пошук однієї розмови не переходить в іншу разом із запитом і лічильником.
  const searchChatId = currentChat?.id;
  useEffect(() => {
    setChatSearchQuery('');
    setSearchMatchIndex(0);
  }, [searchChatId]);

  const pinnedMessages = React.useMemo(() => {
    const local = messages.filter((m) => m && m.isPinned);
    const wire = currentChat?.pinnedMessage;
    if (!wire || currentChat?.type === 'group') return local;
    if (wire.id && local.some((m) => m.id === wire.id)) return local;
    const inFeed = wire.id ? messages.find((m) => m && m.id === wire.id) : undefined;
    const wireMsg: Message = inFeed ?? {
      id: wire.id || `pin_${wire.authorId}_${wire.at}`,
      senderId: wire.authorId,
      senderName: wire.author || t('chatList.you'),
      senderAvatar: '',
      timestamp: '',
      type: 'text',
      text: wire.text ?? '',
      isSelf: wire.authorId === currentUserId,
    };
    return [wireMsg, ...local];
  }, [messages, currentChat?.pinnedMessage, currentUserId, t]);
  const [currentPinnedIndex, setCurrentPinnedIndex] = useState(0);
  const [highlightedMessageId, setHighlightedMessageId] = useState<string | null>(null);

  // Лист, що ще не доставлений, має біля часу лише годинник; дотик до нього —
  // тихе «спробуй зараз».
  const [flushing, setFlushing] = useState(false);
  const handleFlushQueue = async () => {
    if (flushing) return;
    soundFx.playTap();
    setFlushing(true);
    try {
      const res = await messengerApi.flushQueue();
      showToast(res.delivered > 0 ? t('chatArea.delivered') : t('chatArea.queued'));
      if (currentChat) await useMessengerStore.getState().loadMessagesForChat(currentChat.id);
    } catch {
      showToast(t('chatArea.tryLater'));
    } finally {
      setFlushing(false);
    }
  };

  /**
   * ОДНА дорога для кожного дотику по смайлу — і вона говорить правду: успіх
   * без напису (смайл під рядком і є відповіддю), відмова — причиною вузла.
   */
  const react = (msgId: string, emoji: string) => {
    soundFx.playTap();
    const outcome = onAddReaction(msgId, emoji);
    if (outcome && typeof (outcome as Promise<string | null>).then === 'function') {
      void (outcome as Promise<string | null>).then((refusal) => {
        if (refusal) showToast(refusal);
      });
    }
  };

  const handleOpenReactionPicker = (msgId: string) => {
    soundFx.playTap();
    setTargetReactionMsgId(msgId);
    setIsReactionModalOpen(true);
  };

  const gestures = useRowGestures({
    react,
    openMenu: setContextMenuMsg,
    reply: (msg) => onReplyMessage(msg),
  });

  const handleMessageMouseUp = (msg: Message) => {
    const selection = window.getSelection();
    const selectedStr = selection ? selection.toString().trim() : '';
    if (selectedStr.length > 1) {
      const rect = selection?.getRangeAt(0)?.getBoundingClientRect();
      if (rect) {
        setSelectedTextSnippet({ text: selectedStr, msg, x: rect.left + rect.width / 2, y: Math.max(15, rect.top - 10) });
      }
    } else {
      setTimeout(() => {
        if (!window.getSelection()?.toString().trim()) setSelectedTextSnippet(null);
      }, 100);
    }
  };

  /**
   * «Скопійовано» — тільки після того, як буфер це підтвердив: запис у буфер
   * відмовляє реально (без дозволу, поза захищеним контекстом, без фокусу).
   */
  const CLIPBOARD_REFUSED = t('chatArea.clipboardRefused');
  const copyCode = async (code: string, id: string) => {
    soundFx.playTap();
    try {
      await navigator.clipboard.writeText(code);
    } catch {
      showToast(CLIPBOARD_REFUSED);
      return;
    }
    setCopiedCodeId(id);
    showToast(t('chatArea.codeCopied'));
    setTimeout(() => setCopiedCodeId(null), 2000);
  };
  const copyMessageText = async (text: string) => {
    soundFx.playTap();
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      showToast(CLIPBOARD_REFUSED);
      return;
    }
    showToast(t('chatArea.textCopied'));
  };

  /**
   * Стрибок до листа: закріплене, знайдене, цитоване. Віртуальна стрічка
   * знає, де лист, навіть коли його немає в DOM, — тож намір більше не
   * губиться «за межею вікна», як раніше.
   */
  const highlightTimer = useRef<number | undefined>(undefined);
  const onFeedJumped = useCallback((key: string) => {
    setHighlightedMessageId(key);
    window.clearTimeout(highlightTimer.current);
    highlightTimer.current = window.setTimeout(() => setHighlightedMessageId(null), 2500);
  }, []);
  const scrollToMessage = (msgId: string) => {
    if (feedRef.current?.jumpTo(msgId)) return;
    const hitFold = foldsRef.current.find((r) => r.ids.includes(msgId));
    if (hitFold) {
      pendingJumpRef.current = msgId;
      setOpenFolds((prev) => new Set(prev).add(hitFold.ids[0]));
      return;
    }
    // Лист без власного рядка (другий елемент пачки) — до першого елемента пачки.
    const batchId = messages.find((m) => m.id === msgId)?.mediaBatch?.id;
    const leader = batchId ? messages.find((m) => m.mediaBatch?.id === batchId && m.mediaBatch.index === 0) : undefined;
    if (leader) feedRef.current?.jumpTo(leader.id);
  };

  // Стрибок із пошуку по всіх розмовах: чекаємо, доки лист доїде в стрічку.
  useEffect(() => {
    if (!jumpToMessageId) return;
    if (!messages.some((m) => m && m.id === jumpToMessageId)) return;
    scrollToMessage(jumpToMessageId);
    onJumped?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jumpToMessageId, messages]);

  // Закладка в шапці веде до ПЕРШОГО закріпленого і повертає банер на нього ж.
  useImperativeHandle(ref, () => ({
    scrollToPinned: () => {
      const first = pinnedMessages[0];
      if (!first) return;
      setCurrentPinnedIndex(0);
      scrollToMessage(first.id);
    },
  }));

  const searchQ = isSearching ? chatSearchQuery.trim().toLowerCase() : '';
  const searchMatchingIds = React.useMemo(
    () =>
      searchQ
        ? messages
            .filter((m) =>
              matchesQuery(m, searchQ, [
                assist.translatedMessages[m.id]?.text ?? '',
                assist.summarizedMessages[m.id] ?? '',
              ]),
            )
            .map((m) => m.id)
        : [],
    [messages, searchQ, assist.translatedMessages, assist.summarizedMessages],
  );
  const searchMatchSet = React.useMemo(() => new Set(searchMatchingIds), [searchMatchingIds]);

  // Перший збіг — одразу, щойно людина почала друкувати.
  useEffect(() => {
    if (isSearching && chatSearchQuery.trim() && searchMatchingIds.length > 0) {
      setSearchMatchIndex(0);
      scrollToMessage(searchMatchingIds[0]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatSearchQuery, isSearching]);

  const stepSearch = (dir: 1 | -1) => {
    if (searchMatchingIds.length === 0) return;
    const next = (searchMatchIndex + dir + searchMatchingIds.length) % searchMatchingIds.length;
    setSearchMatchIndex(next);
    scrollToMessage(searchMatchingIds[next]);
  };

  // Розмова сам-на-сам: підписувати кожну бульбашку іменем нема сенсу.
  const isDirectConversation =
    currentChat?.type === 'dm' || currentChat?.type === 'direct' || !!currentChat?.peerNodeId;
  const groupish = currentChat?.type === 'group' || currentChat?.type === 'channel';
  const channelFeed = !!currentChat && (currentChat.type === 'channel' || (currentChat.type === 'group' && currentChat.groupSettings?.kind === 'channel'));
  // «Збережене» — нотатки собі: там немає ні співрозмовника, ні наскрізного каналу.
  const savedNotesChat = Boolean(currentChat && isSavedNotes(currentChat));
  // Розмова з PHANTOM: відповіді — «аркуш» з підписом, на початку — тихі підказки.
  const phantomChat = Boolean(currentChat && isNodeChat(currentChat));

  // Поява нового листа (160 мс, зсув + прозорість) — лише для листів, що
  // прийшли ПІСЛЯ відкриття розмови. Історія при відкритті не анімується.
  const knownIdsRef = useRef<{ chat: string; ids: Set<string> }>({ chat: '\u0000', ids: new Set() });
  const freshIdsRef = useRef<Set<string>>(new Set());
  const convKey = currentChat?.id ?? '';
  if (knownIdsRef.current.chat !== convKey) {
    knownIdsRef.current = { chat: convKey, ids: new Set(messages.map((m) => m.id)) };
  } else {
    for (const m of messages) {
      if (m && !knownIdsRef.current.ids.has(m.id)) {
        knownIdsRef.current.ids.add(m.id);
        freshIdsRef.current.add(m.id);
        window.setTimeout(() => freshIdsRef.current.delete(m.id), 400);
      }
    }
  }

  const actions = useStableFeedActions({
    react,
    openReactionPicker: handleOpenReactionPicker,
    openMenu: (msg) => setContextMenuMsg(msg),
    reply: (msg) => onReplyMessage(msg),
    jumpTo: (msgId) => scrollToMessage(msgId),
    toggleExpand: (msgId, e) => {
      if (e) e.stopPropagation();
      soundFx.playTap();
      setExpandedMsgIds((prev) => ({ ...prev, [msgId]: !prev[msgId] }));
    },
    toggleTranslate: (msg) => void assist.handleToggleTranslate(msg),
    selectMember: (name) => onSelectMemberByName?.(name),
    toggleSelect: (msgId) => onToggleSelectMessage(msgId),
    // Проміжок: від останнього, якого торкнулись, до цього (Shift+клік).
    rangeTo: (msgId) => {
      const from = selectionAnchorId && messages.some((m) => m.id === selectionAnchorId) ? selectionAnchorId : null;
      if (!from || !onSelectMessages) return onToggleSelectMessage(msgId);
      onSelectMessages(mergeSelection(messages, selectedMessageIds || [], rangeIds(messages, from, msgId)));
    },
    // Протяг по позначках: палець веде від листа до листа, вибране — проміжок.
    rangeDrag: (msgId) => {
      if (!onSelectMessages) return;
      const base = selectedMessageIds || [];
      let last = msgId;
      const move = (e: PointerEvent) => {
        const el = document.elementFromPoint(e.clientX, e.clientY)?.closest?.('[data-msg-id]');
        const id = el?.getAttribute('data-msg-id');
        if (!id || id === last) return;
        last = id;
        onSelectMessages(mergeSelection(messages, base, rangeIds(messages, msgId, id)));
      };
      const stop = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', stop);
        window.removeEventListener('pointercancel', stop);
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', stop);
      window.addEventListener('pointercancel', stop);
    },
    copyCode: (code, msgId) => void copyCode(code, msgId),
    toast: showToast,
    flushQueue: () => void handleFlushQueue(),
    // Фото (§6): мініатюра, якої торкнулись, розростається в перегляд (360).
    openLightbox: (url, title) => {
      const from = takeTappedMedia();
      withViewTransition(() => onOpenImageLightbox?.(url, title), { pairs: [[from, 'photo-zoom']], kind: 'vt-zoom' });
    },
    openLocation: (loc) => onOpenLocation(loc),
    payBillShare: (msgId, participantId) => onPayBillShare(msgId, participantId),
    openCanvasSplit: () => setIsCanvasSplitOpen(true),
    openThread: (msgId) => useSpaceStore.getState().openThreadView({ chatId: currentChat?.id || '', rootMessageId: msgId }),
    touchStart: gestures.handleTouchStart,
    touchMove: gestures.handleTouchMove,
    touchEnd: gestures.handleTouchEnd,
    mouseUp: (msg) => handleMessageMouseUp(msg),
  });

  const selectedSet = React.useMemo(() => new Set(selectedMessageIds || []), [selectedMessageIds]);
  const activeMatchId = isSearching ? searchMatchingIds[searchMatchIndex] : undefined;
  const searchText = isSearching ? chatSearchQuery : undefined;

  const renderRow = (row: FeedRow) => {
    const id = row.msg.id;
    return (
      <MessageRow
        row={row}
        chatId={currentChat?.id}
        chatTitle={currentChat?.title}
        currentUserId={currentUserId}
        currentUserName={currentUserName}
        groupish={groupish}
        direct={isDirectConversation}
        post={channelFeed}
        assistant={phantomChat}
        phone={phone}
        isSelectionMode={isSelectionMode}
        isSelected={selectedSet.has(id)}
        isHighlighted={highlightedMessageId === id}
        isSearchMatch={isSearching && searchMatchSet.has(id)}
        isActiveSearchMatch={activeMatchId === id}
        searchQuery={searchText}
        fresh={freshIdsRef.current.has(id)}
        expanded={!!expandedMsgIds[id]}
        translated={assist.translatedMessages[id]}
        summary={assist.summarizedMessages[id]}
        actionItems={assist.actionItemMessages[id]}
        agentLabel={assist.agentTask?.msgId === id ? assist.agentTask.label : undefined}
        copied={copiedCodeId === id}
      />
    );
  };

  /* Порожня розмова: одне речення й одне дієслово, поруч із композером. */
  const head = (
    <>
      {namesake && (
        <p data-testid="thread-namesake" className="px-4 pt-4 text-center text-caption text-ink-3"><span className="ph-on-stage">
          {namesake}</span>
        </p>
      )}
      {messages.length === 0 && phantomChat && !historyLoad && (
        // PHANTOM на початку: знак, одне речення і тихі підказки-чипи. Дотик
        // надсилає саме ці слова — те саме, що людина написала б сама.
        <div className="ph-stage-card px-4 pt-8 pb-4 flex flex-col items-center select-none" data-testid="phantom-start">
          <PhantomMark size="w-16 h-16" />
          <p className="mt-3 text-headline text-ink">{currentChat?.title || ASSISTANT_TITLE}</p>
          <div className="mt-6 w-full rounded-2xl bg-raised overflow-hidden divide-y divide-line" data-testid="phantom-chips">
            {PHANTOM_STARTERS_UI.map(({ key, Icon }) => {
              const q = t(key);
              return (
              <button
                key={q}
                type="button"
                onClick={() => {
                  soundFx.playTap();
                  useMessengerStore.getState().sendMessage(q);
                }}
                className="ph-press w-full min-h-[52px] px-4 py-3 flex items-center gap-3 text-left text-body text-ink hover:bg-overlay transition-colors duration-quick"
              >
                <Icon className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
                <span className="min-w-0">{q}</span>
              </button>
              );
            })}
          </div>
        </div>
      )}
      {messages.length === 0 && historyLoad === 'loading' && <HistoryLoading />}
      {messages.length === 0 && historyLoad === 'error' && (
        <div className="px-4 pt-10 pb-4 text-left select-none" data-testid="thread-load-failed" role="alert">
          <p className="text-body text-ink">{t('coreChat.loadFailed')}</p>
          <button
            type="button"
            onClick={() => {
              soundFx.playTap();
              if (currentChat) void useMessengerStore.getState().loadMessagesForChat(currentChat.id, { full: true });
            }}
            className="mt-2 h-11 -ml-1 px-1 text-headline text-accent-text"
          >
            {t('coreChat.retry')}
          </button>
        </div>
      )}
      {messages.length === 0 && !phantomChat && !historyLoad && (
        <div className="px-6 py-10 flex flex-col items-center text-center select-none" data-testid="thread-empty">
          <span data-empty-plate className="w-14 h-14 mb-3 rounded-full bg-raised text-ink-2 flex items-center justify-center" aria-hidden>
            {savedNotesChat ? (
              <Bookmark className="w-6 h-6" strokeWidth={1.5} />
            ) : (
              <PenLine className="w-6 h-6" strokeWidth={1.5} />
            )}
          </span>
          <p className="text-body text-ink max-w-[340px]">
            {savedNotesChat ? t('chatArea.savedNotes') : t('chatArea.noLetters')}
          </p>
          <span data-accent="amber" className="contents">
            <button
              type="button"
              onClick={() => {
                soundFx.playTap();
                document.querySelector<HTMLTextAreaElement>('[data-composer-input]')?.focus();
              }}
              className="mt-1 h-11 px-4 text-headline text-accent-text"
            >
              {savedNotesChat ? t('chatArea.write') : t('chatArea.writeFirst')}
            </button>
          </span>
        </div>
      )}
    </>
  );

  const tail = (
    <>
      {(aiThinking || typingStatus) && (
        // «Пише…» / «PHANTOM рахує»: три крапки по черзі наливаються й
        // витягуються (мікро №11), без стрибків і без «Думаю…» з переливом.
        <div
          data-testid="typing-bubble"
          className="mt-3 ml-1.5 flex items-center gap-2 text-footnote w-fit msg-bubble-other bubble-contour-other-single relative px-3 py-2.5 f-msg-in"
        >
          <span className="ph-dots text-ink-3" aria-hidden>
            <i />
            <i />
            <i />
          </span>
          <span className="text-ink-3">{typingStatus || (aiThinking ? t('chatArea.nightpostThinking') : t('chatArea.typing'))}</span>
        </div>
      )}
      {wherePeer && <WhereCards peer={wherePeer} onChange={() => onOpenWhere?.()} />}
    </>
  );

  return (
    // Стрічка прозора: шпалеру (зерно + тонкий візерунок, identity.css) несе
    // корінь розмови `talk-root`, щоб плаваючі шапка й композер стояли на ній же.
    <div
      data-chat-bubbles={resolved.bubbles}
      className="flex-1 flex flex-col h-full bg-transparent relative overflow-hidden select-none"
      onPointerDownCapture={(e) => rememberTappedMedia(e.target)}
    >
      {currentChat?.type === 'group' && currentChat.pinnedMessage && (
        <GroupPinBar chat={currentChat} onJump={scrollToMessage} />
      )}
      {pinnedMessages.length > 0 && (
        <PinnedBanner
          pinned={pinnedMessages}
          index={currentPinnedIndex}
          onIndex={setCurrentPinnedIndex}
          onJump={scrollToMessage}
          onUnpin={(id) => {
            const wire = currentChat?.pinnedMessage;
            if (
              currentChat?.type === 'group' &&
              wire &&
              (id === wire.id || id === `pin_${wire.authorId}_${wire.at}`) &&
              currentChat.powers?.pin
            ) {
              void useMessengerStore
                .getState()
                .pinGroupMessage(currentChat.id, '')
                .catch((e: unknown) => {
                  showToast(e instanceof Error ? e.message : t('messageActionSheet.pinFailed'));
                });
              return;
            }
            onTogglePinMessage?.(id);
          }}
        />
      )}

      {isSearching && (
        <ChatSearchBar
          query={chatSearchQuery}
          matchCount={searchMatchingIds.length}
          matchIndex={Math.min(searchMatchIndex, Math.max(0, searchMatchingIds.length - 1))}
          onQuery={(q) => {
            setChatSearchQuery(q);
            setSearchMatchIndex(0);
          }}
          onPrev={() => stepSearch(-1)}
          onNext={() => stepSearch(1)}
          onClose={onCloseSearch}
          dateBounds={dayBounds(messages)}
          onJumpDate={(day) => {
            const hit = firstMessageOnOrAfter(messages, day);
            if (hit) scrollToMessage(hit.id);
          }}
        />
      )}

      <div className="relative flex-1 min-h-0 flex flex-row overflow-hidden">
        {/* Документ розмови поруч (лише ПК) */}
        {CanvasSplitView && !isCanvasSplitOpen && (
          <div className="hidden md:block absolute top-3 right-4 z-20">
            <button
              type="button"
              onClick={() => {
                soundFx.playTap();
                setIsCanvasSplitOpen(true);
              }}
              className="h-9 touch-floor-inline px-3 rounded-full flex items-center gap-1.5 text-subhead text-ink-2 hover:text-ink bg-surface hover:bg-overlay transition-colors duration-instant ease-standard"
              title={t('chatArea.openDoc')}
            >
              <FileText className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
              <span>{t('chatArea.docLabel')}</span>
            </button>
          </div>
        )}

        <div className={`flex-1 min-w-0 flex-col h-full overflow-hidden ${isCanvasSplitOpen ? 'hidden md:flex' : 'flex'}`}>
          <FeedActionsContext.Provider value={actions}>
            <VirtualFeed
              ref={feedRef}
              rows={rows}
              unreadFromKey={unreadSnap.current?.chatId === currentChat?.id ? unreadSnap.current?.key : null}
              chatId={currentChat?.id}
              renderRow={renderRow}
              onShowFold={(ids) => setOpenFolds((prev) => new Set(prev).add(ids[0]))}
              head={head}
              tail={tail}
              onJumped={onFeedJumped}
            />
          </FeedActionsContext.Provider>
        </div>

        {/* На телефоні не монтується взагалі: другої колонки на 393px немає. */}
        {CanvasSplitView && isCanvasSplitOpen && !isPhoneShell() && (
          <div className="flex-1 md:flex-initial flex h-full max-w-full relative z-20 overflow-hidden">
            {canvasWidthMode !== 'full' && (
              <div
                onMouseDown={startDragSplit}
                className={`hidden md:block w-1 hover:w-1.5 hover:opacity-90 cursor-col-resize transition-all shrink-0 select-none ${
                  isDraggingSplit ? 'bg-accent w-1.5' : 'bg-raised'
                }`}
                title={t('chatArea.dragWidth')}
              />
            )}
            <div
              style={{
                width:
                  canvasWidthMode === 'full'
                    ? '100%'
                    : canvasWidthMode === 'wide'
                    ? '65vw'
                    : typeof window !== 'undefined' && window.innerWidth < 768
                    ? '100%'
                    : `${splitWidth}px`,
                maxWidth: '100vw',
              }}
              className={`h-full w-full bg-surface border-l border-line transition-all overflow-hidden ${
                canvasWidthMode === 'full' ? 'fixed inset-0 z-40' : ''
              }`}
            >
              <Suspense fallback={<p className="p-6 text-footnote text-ink-3">{t('chatArea.openingDoc')}</p>}>
                <CanvasSplitView
                  chatTitle={currentChat?.title || t('chatArea.chatFallback')}
                  chatId={currentChat?.id}
                  messages={messages}
                  widthMode={canvasWidthMode}
                  onToggleWidthMode={setCanvasWidthMode}
                  onClose={() => setIsCanvasSplitOpen(false)}
                  inbox={canvasInbox}
                  onInboxTaken={(ids) => setCanvasInbox((q) => q.filter((i) => !ids.includes(i.id)))}
                />
              </Suspense>
            </div>
          </div>
        )}
      </div>

      {toastNotification && (
        <div className="absolute bottom-16 left-1/2 -translate-x-1/2 bg-raised text-ink px-3.5 py-2 rounded-control text-footnote z-30 animate-in fade-in duration-150 flex items-center gap-2">
          <Info className="w-3.5 h-3.5 text-ink-2 shrink-0" strokeWidth={1.75} />
          <span>{toastNotification}</span>
        </div>
      )}

      {/* Цитата виділеного фрагмента; «У документ» — лише там, де документ існує (ПК). */}
      {selectedTextSnippet && (
        <div
          style={{
            position: 'fixed',
            left: `${selectedTextSnippet.x}px`,
            top: `${selectedTextSnippet.y}px`,
            transform: 'translate(-50%, -100%)',
          }}
          className="z-50 bg-surface text-ink p-1 rounded-control flex items-center gap-1 text-footnote animate-in fade-in duration-150 select-none"
        >
          <button
            onClick={(e) => {
              e.stopPropagation();
              soundFx.playTap();
              onReplyMessage(selectedTextSnippet.msg, selectedTextSnippet.text);
              window.getSelection()?.removeAllRanges();
              setSelectedTextSnippet(null);
              showToast(t('chatArea.quoteAdded'));
            }}
            className="touch-floor px-2.5 py-1 hover:bg-raised rounded-control flex items-center gap-1.5 text-ink transition-colors"
          >
            <Quote className="w-3.5 h-3.5 text-ink-2 shrink-0" strokeWidth={1.75} />
            <span>{t('chatArea.quote')}</span>
          </button>
          {CanvasSplitView && !isPhoneShell() && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                soundFx.playTap();
                window.dispatchEvent(
                  new CustomEvent('phantom:add-to-canvas', { detail: { text: selectedTextSnippet.text, type: 'text' } }),
                );
                window.getSelection()?.removeAllRanges();
                setSelectedTextSnippet(null);
                showToast(t('chatArea.addedToDoc'));
              }}
              className="touch-floor px-2.5 py-1 hover:bg-raised rounded-control flex items-center gap-1.5 text-ink-2 font-semibold transition-colors"
            >
              <FileText className="w-3.5 h-3.5 text-ink-2 shrink-0" strokeWidth={1.75} />
              <span>{t('chatArea.toDoc')}</span>
            </button>
          )}
        </div>
      )}

      {/* Аркуш дій над листом — порталом у body (див. MessageActionSheet). */}
      {contextMenuMsg && (
        <MessageActionSheet
          msg={contextMenuMsg}
          currentChat={currentChat}
          currentUserId={currentUserId}
          speaking={assist.speakingMsgId === contextMenuMsg.id}
          onClose={closeContextMenu}
          on={{
            reply: (m, quoted) => onReplyMessage(m, quoted),
            copyText: (text) => void copyMessageText(text),
            forward: (m) => onForwardMessage?.(m),
            edit: (m) => onEditMessage?.(m),
            react,
            openReactionPicker: handleOpenReactionPicker,
            speak: assist.handleSpeakMessage,
            openCanvas: () => setIsCanvasSplitOpen(true),
            toast: showToast,
            translate: (m) => void assist.handleToggleTranslate(m),
            summarize: (m) => void assist.handleToggleSummary(m),
            actionItems: (m) => void assist.handleGenerateActionItems(m),
            togglePin: (id) => onTogglePinMessage?.(id),
            save: (m) => void useMessengerStore.getState().forwardMessage(m, SAVED_NOTES_ID),
            select: (id) => onToggleSelectMessage(id),
            inspect: setInspectingMessage,
            remove: setDeletingMessage,
            report: (m) => {
              useSupportStore.getState().openReport({
                targetKind: 'message',
                targetId: m.id,
                targetName: m.senderName || currentChat?.title,
                chatId: currentChat?.id,
                messageIds: [m.id],
                messageText: m.text,
                senderKey: m.senderId || currentChat?.peerNodeId,
                surface: currentChat?.isPublic ? 'public' : 'private',
                peerId:
                  currentChat?.peerNodeId ||
                  (m.senderId && m.senderId !== 'system' ? m.senderId : undefined),
              });
            },
          }}
        />
      )}

      <ReportSheetHost />

      <ReactionPickerModal
        isOpen={isReactionModalOpen}
        onClose={() => {
          setIsReactionModalOpen(false);
          setTargetReactionMsgId(null);
        }}
        onSelectEmoji={(emoji) => {
          if (targetReactionMsgId) react(targetReactionMsgId, emoji);
        }}
      />

      <MessageDetailsModal
        isOpen={!!inspectingMessage}
        message={inspectingMessage}
        chatTitle={currentChat?.title}
        members={currentChat?.members}
        selfId={currentUserId}
        conversationId={currentChat?.id}
        onClose={() => setInspectingMessage(null)}
      />

      <DeleteMessageModal
        isOpen={!!deletingMessage}
        isSelfMessage={deletingMessage?.senderId === currentUserId || !!deletingMessage?.isSelf}
        noOtherSide={savedNotesChat}
        group={groupish}
        messageTextPreview={deletingMessage ? letterPreview(deletingMessage) : undefined}
        onClose={() => setDeletingMessage(null)}
        onConfirmDelete={(deleteForEveryone) => {
          if (!deletingMessage) return;
          const outcome = onDeleteMessage?.(deletingMessage.id, deleteForEveryone);
          // «Повідомлення видалено» казалось беззастережно — навіть коли вузол
          // відповів `for_everyone: false`, тобто стер лише тут.
          if (outcome && typeof (outcome as Promise<string | null>).then === 'function') {
            void (outcome as Promise<string | null>).then((caveat) => {
              showToast(caveat ?? t('chatArea.messageDeleted'));
            });
          } else {
            showToast(t('chatArea.messageDeleted'));
          }
        }}
      />

    </div>
  );
});

ChatArea.displayName = 'ChatArea';
