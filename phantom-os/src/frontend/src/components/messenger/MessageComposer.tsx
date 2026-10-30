import { pickPlural } from '../chat/tNow';
import React, { useState, useRef, useEffect, useLayoutEffect, useMemo } from 'react';
import { geoNow, type GeoNowFail } from '../../services/geoNow';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import {
  Send,
  X,
  AlertTriangle,
  Clock,
  Check,
  Layers,
  Reply,
  File as FileIcon,
  MapPin,
  LocateFixed,
  Video,
  Music,
  Mic,
  Trash2,
  Camera,
  ArrowUp,
  Plus,
  ListChecks,
  BarChart2,
  Wallet,
  Table2,
  CalendarClock,
  Pencil,
  Pause,
  Play,
  Square,
  Lock,
  LockOpen,
  ChevronUp,
  ChevronLeft,
  Coins,
  UserRound,
  Bold,
  Italic,
  Underline,
  Strikethrough,
  EyeOff,
  Code,
  Quote,
  Link2,
  BellOff,
  Gift,
  Sticker,
  Crop,
} from 'lucide-react';
import { StickerSheet } from '../../stickers/StickerSheet';
import { composeSticker } from '../../stickers/stickerWire';
import { noteSent } from '../../stickers/stickerShelf';
import { Message, ChatMember, MessageReplyInfo, type GeoPoint } from '../../types/messenger';
import { SAVED_NOTES_ID } from '../../services/messengerApi';
import { displayTitle, isNodeChat, plainSnippet } from './chatListFormat';
import { NightpostIcon } from './ask/AskSheet';
import { composeAsk } from '../../services/askContext';
import { openAsk } from '../../stores/askSheetStore';
import { soundFx } from '../../utils/messengerSound';
import { useMessengerStore } from '../../stores/messengerStore';
import {
  mediaLimitLabel,
  MEDIA_PLAIN_LIMIT_BYTES,
  humanSize,
  isRenderableImage,
} from '../../services/messengerMedia';
import { voiceApi } from '../../services/voiceApi';
import { loadVoiceText, saveVoiceText } from '../../services/voiceTextPref';
import { callEngine } from '../../services/callEngine';
import {
  applyVoiceDraftEffect,
  idbVoiceDraftStorage,
  loadVoiceDraft,
  voiceDraftBlob,
  type VoiceDraft,
} from '../../services/voiceDraft';
import { useUIStore } from '../../stores/uiStore';
import { VideoNoteRecorder } from './VideoNoteRecorder';
import { createTypingSignal, sendTyping, type TypingSignal } from '../../services/typingSignal';
import { CardBuilder, sendableBuilders, type BuilderKind } from './cards/CardBuilder';
import { PersonPickSheet } from './cards/PersonCard';
import { GiftPickSheet } from './cards/GiftCard';
import { AttachSheet } from './AttachSheet';
import { MediaCropSheet } from './MediaCropSheet';
import { readPhotoQuality, writePhotoQuality, type PhotoQuality } from '../../services/photoQuality';
import { FormSheet } from './FormSheet';
import { chatCapsPending, ensureChatCaps, routeForChat } from '../../services/carrier/route';
import { askSolana, usePayPeer } from '../../services/solanaActions';
import { BusinessChatBar } from './directions/BusinessChatBar';
import { canPayHere } from '../../services/meetupApi';
import { dayApi, type DayItem } from '../../services/dayApi';
import { sentMoment } from '../../services/understand';
import {
  formatSlotRange,
  freeSlots,
  loadRing,
  slotDay,
  wantsTime,
  type FreeSlot,
} from '../../services/freeSlots';
import { circleLabel } from './MomentViewer';
import { handleTag, ringOfPeer, sameNameSuffixes } from '../../services/sameName';
import { mentionedIds } from '../../services/mentions';
import {
  VOICE_HINT_MS,
  VOICE_HOLD_MS,
  micAlreadyGranted,
  probeMicGrant,
  VOICE_IDLE,
  compressEnvelope,
  useVoiceNote,
  voiceGesture,
  voiceHaptic,
  voiceIsSilent,
  voiceProgress,
  type VoiceEffect,
  type VoiceGestureEvent,
  type VoiceGestureState,
  type VoiceTake,
} from '../../hooks/useVoiceRecorder';
import type { VoiceNoteMode } from '../../hooks/voiceNoteProfile';

/** Скільки стовпчиків у хвилі перегляду перед надсиланням (05 §2.6: не більше 48). */
const VOICE_PREVIEW_BARS = 40;

/** 0:07, 1:24 — як у бульбашці голосового. */
const fmtVoice = (ms: number): string => {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(total / 60)}:${(total % 60).toString().padStart(2, '0')}`;
};

const voiceGhostBtn =
  'w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink-2 hover:bg-overlay active:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line';

/*
 * ── ЛИСТ ПИСЬМА: ОДНІ ДЕТАЛІ ДЛЯ РОЗМОВИ Й ГІЛКИ ─────────────────────────
 *
 * Поле, кругла кнопка «Надіслати» і привид-кнопка «+» — спільні для
 * композера розмови й відповіді в гілці (ThreadView). Гілка мала власне
 * поле: кнопки 36 замість 44, скріпку замість «+», плейсхолдер старим hex
 * `#5F6A60` і кільце фокуса навколо картки `surface`, — тож відповідь у гілці
 * виглядала як інший застосунок. Тепер форма одна; поведінка (що саме
 * надсилає кнопка) лишається в кожного своя.
 */

/** Ряд композера: «+», поле-пігулка, мікрофон чи «Надіслати». Сам ряд плаский. */
export const ComposerSheet: React.FC<{ children: React.ReactNode; className?: string } & React.HTMLAttributes<HTMLDivElement>> = ({
  children,
  className = '',
  ...rest
}) => (
  <div
    data-ph-composer="sheet"
    className={`flex-1 min-w-0 min-h-11 flex items-end gap-2 ${className}`}
    {...rest}
  >
    {children}
  </div>
);

/** Привид-кнопка на аркуші (коло 44, `ink`, `overlay` на дотик). */
export const COMPOSER_GHOST_BUTTON =
  'w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center shrink-0 text-ink hover:bg-overlay active:bg-overlay transition-colors duration-quick ease-standard focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line disabled:opacity-40';

/** Висота поля: до ~5 рядків. Коли текст довший — верхнє поле ховається прокруткою, тож рахуємо лише нижнє, і рядок не ріжеться об край. */
export function composerHeight(contentHeight: number, el?: Element): number {
  const cs = el ? getComputedStyle(el) : null;
  const lh = cs ? parseFloat(cs.lineHeight) : NaN;
  const pt = cs ? parseFloat(cs.paddingTop) : NaN;
  const pb = cs ? parseFloat(cs.paddingBottom) : NaN;
  if (!Number.isFinite(lh) || lh <= 0 || !Number.isFinite(pt) || !Number.isFinite(pb)) return Math.min(contentHeight, 140);
  const lines = Math.floor((140 - pt - pb) / lh);
  const full = pt + pb + lines * lh;
  return contentHeight <= full ? contentHeight : pb + lines * lh;
}

const COMPOSER_TEXTAREA =
  'composer-textarea no-scrollbar flex-1 min-w-0 max-h-[140px] min-h-11 py-[11px] bg-transparent text-body text-ink placeholder:text-ink-3 resize-none focus:outline-none select-text break-words !border-none !bg-transparent !outline-none !shadow-none';

/** Поле листа — пігулка `surface` з межею `line`, радіус 24, без кільця фокуса; текст — `body`. */
export const ComposerField = React.forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement> & {
    wrapTestId?: string;
    trailing?: React.ReactNode;
    'data-testid'?: string;
    'data-composer-input'?: boolean;
  }
>(({ wrapTestId, trailing, className = '', rows = 1, ...rest }, ref) => (
  <div
    data-testid={wrapTestId}
    data-ph-composer="field"
    className="flex-1 min-w-[160px] min-h-11 rounded-composer bg-surface border border-line pl-4 pr-3 flex items-end message-composer-input"
  >
    <textarea ref={ref} rows={rows} className={`${COMPOSER_TEXTAREA} ${className}`} {...rest} />
    {trailing}
  </div>
));
ComposerField.displayName = 'ComposerField';

/** «Надіслати» — бурштинове коло 44 зі стрілкою; вимкнене — `raised` + `ink-3`. */
export const SendButton: React.FC<{
  onClick: () => void;
  label: string;
  title?: string;
  disabled?: boolean;
  testId?: string;
  children?: React.ReactNode;
  hold?: {
    start: () => void;
    end: () => void;
    open: () => void;
    expanded: boolean;
  };
}> = ({ onClick, label, title, disabled = false, testId, children, hold }) => (
  <button
    type="button"
    onClick={onClick}
    onPointerDown={hold?.start}
    onPointerUp={hold?.end}
    onPointerLeave={hold?.end}
    onPointerCancel={hold?.end}
    onContextMenu={hold ? (e) => { e.preventDefault(); hold.open(); } : undefined}
    aria-haspopup={hold ? 'menu' : undefined}
    aria-expanded={hold ? hold.expanded : undefined}
    disabled={disabled}
    data-testid={testId}
    data-ph-composer="send"
    className="f-send-in ph-press w-11 h-11 min-h-0 min-w-0 rounded-full shrink-0 flex items-center justify-center bg-accent text-on-accent hover:bg-accent-hover active:bg-accent-press transition-colors duration-instant focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-line disabled:bg-raised disabled:text-ink-3"
    aria-label={label}
    title={title ?? label}
  >
    {children ?? <ArrowUp className="w-5 h-5" strokeWidth={2} />}
  </button>
);

/**
 * Хвиля голосового: смужки 2 px через 2 px, висота 4–24 (05 §2.6). Жива — нові
 * рівні праворуч; у перегляді — нормалізація за піком і прослухана частина `ink`.
 */
const VoiceBars: React.FC<{ levels: number[]; played?: number; live?: boolean; testId?: string }> = ({ levels, played, live = false, testId }) => {
  const peak = live ? 1 : Math.max(0.05, ...levels);
  return (
    <span
      data-testid={testId}
      aria-hidden
      className={`flex-1 min-w-0 h-6 flex items-center gap-0.5 overflow-hidden ${live ? 'justify-end' : ''}`}
    >
      {levels.map((v, i) => {
        const tone = played === undefined ? 'bg-ink-2' : (i + 0.5) / levels.length <= played ? 'bg-ink' : 'bg-ink-3';
        return (
          <span
            key={i}
            className={`w-0.5 shrink-0 rounded-full ${tone}`}
            style={{ height: 4 + Math.round(Math.min(1, v / peak) * 20) }}
          />
        );
      })}
    </span>
  );
};

const BUILDER_ICON: Record<BuilderKind, typeof Plus> = {
  meetup: CalendarClock,
  checklist: ListChecks,
  poll: BarChart2,
  expense: Wallet,
  table: Table2,
};

const LIVE_SPANS: ReadonlyArray<{ minutes: number; label: MessageKey }> = [
  { minutes: 15, label: 'msgTypes.live15' },
  { minutes: 60, label: 'msgTypes.live60' },
  { minutes: 480, label: 'msgTypes.live480' },
];

const BUILDER_LABEL: Record<BuilderKind, MessageKey> = {
  meetup: 'messageComposer.builderMeetup',
  checklist: 'messageComposer.builderChecklist',
  poll: 'messageComposer.builderPoll',
  expense: 'messageComposer.builderExpense',
  table: 'messageComposer.builderTable',
};

const SEND_HOLD_MS = 450;

interface MessageComposerProps {
  onSendMessage: (text: string, opts?: { quiet?: boolean; mentions?: string[] }) => void;
  /** Лише ПК: «Запланувати» з утримання «Надіслати»; `done` чистить композер після створення. */
  onScheduleDraft?: (text: string, done: () => void) => void;
  onSendVoiceMessage: (
    duration: number,
    transcript: string,
    audioBlob?: Blob,
    waveform?: number[],
  ) => void;
  onOpenScheduledList?: () => void;
  scheduledCountInCurrentChat?: number;
  replyingTo: MessageReplyInfo | null;
  onCancelReply: () => void;
  onRemoveReplyQuote?: (quoteId: string) => void;
  editingMessage: Message | null;
  onCancelEdit: () => void;
  onSaveEdit: (messageId: string, newText: string) => void;
  selectedMessagesForQuote: Message[];
  onSynthesizeMultiQuote: (title: string, userCommentary: string) => void;
  onClearSelectedQuotes: () => void;
  chatMembers?: ChatMember[];
  chatId?: string;
  initialDraft?: string;
  onDraftChange?: (chatId: string, draftText: string) => void;
  /** Розмова з людиною на телефоні: «Показати, де я» замість трансляції сирих координат. */
  wherePeer?: { id: string; name: string } | null;
  onOpenWhere?: () => void;
  /** Розмова з людиною чи групою на телефоні: співрозмовник бачить «друкує…». */
  signalTyping?: boolean;
  lastPeerMessage?: { id: string; text: string; sentAtIso?: string | null } | null;
}

// Слеш-команд більше немає. Їх було дві — `/git` і `/roles`, і обидві
// відкривали показові модалки ПК без жодного виклику вузла (видалено 24.09,
// docs/pc-msgr-census.md). Лист, що починається з «/», їде як звичайний текст.

export const MessageComposer: React.FC<MessageComposerProps> = ({
  onSendMessage,
  onSendVoiceMessage: _onSendVoiceMessage,
  onOpenScheduledList,
  scheduledCountInCurrentChat = 0,
  replyingTo,
  onCancelReply,
  onRemoveReplyQuote: _onRemoveReplyQuote,
  editingMessage,
  onCancelEdit,
  onSaveEdit,
  selectedMessagesForQuote,
  onSynthesizeMultiQuote,
  onClearSelectedQuotes,
  chatMembers = [],
  chatId,
  initialDraft = '',
  onDraftChange,
  wherePeer = null,
  onOpenWhere,
  signalTyping = false,
  lastPeerMessage,
  onScheduleDraft,
}) => {
  const { t, locale } = useTranslation();
  const [text, setText] = useState(initialDraft);
  const typingRef = useRef<TypingSignal | null>(null);
  if (!typingRef.current) typingRef.current = createTypingSignal(sendTyping);
  useEffect(() => () => typingRef.current?.stop(), []);
  const [showAttachMenu, setShowAttachMenu] = useState(false);
  const [showStickers, setShowStickers] = useState(false);
  // «Переказати» — лише коли телефон каже, що цій людині можна (її адреса вже тут).
  const { pay: payPeer, refresh: refreshPayPeer } = usePayPeer(wherePeer);
  const [builder, setBuilder] = useState<BuilderKind | null>(null);
  // Смуга завантаження живе тут, бо саме тут людина натиснула «+». Відсоток
  // приходить з XHR — це справжні надіслані байти, а не анімація очікування.
  // index/total — чесна черга, коли файлів кілька.
  const [upload, setUpload] = useState<{
    name: string;
    percent: number;
    index: number;
    total: number;
  } | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [slot, setSlot] = useState<FreeSlot | null>(null);
  const usedSlotsRef = useRef<Map<string, Set<string>>>(new Map());
  const [, bumpUsedSlots] = useState(0);
  const effectivePeerMessage = lastPeerMessage ?? null;

  const isSlotUsed = (cId: string | undefined, msgId: string | undefined): boolean => {
    if (!msgId) return false;
    return usedSlotsRef.current.get(cId || '*')?.has(msgId) ?? false;
  };

  const markSlotUsed = (cId: string | undefined, msgId: string) => {
    const key = cId || '*';
    let set = usedSlotsRef.current.get(key);
    if (!set) {
      set = new Set<string>();
      usedSlotsRef.current.set(key, set);
    }
    set.add(msgId);
    bumpUsedSlots((n) => n + 1);
  };
  /**
   * Спільний передогляд ПЕРЕД відправкою: власник хотів «групувати», а не
   * слати по одному. Заповнюється, коли вибрано більше одного файла або
   * одне фото (його можна обрізати); один не-фото файл іде напряму.
   */
  const [pendingBatch, setPendingBatch] = useState<{ id: string; items: { file: File; url: string; isImage: boolean }[] } | null>(null);
  const [cropIdx, setCropIdx] = useState<number | null>(null);
  const [photoQuality, setPhotoQuality] = useState<PhotoQuality>(readPhotoQuality);
  /**
   * Прапорець «більше не надсилай». Ref, а не стан: цикл надсилання читає його
   * між файлами, а перемальовування компонента йому не потрібне й не гарантоване
   * вчасно — стан прочитався б замкненим на мить запуску циклу.
   */
  const cancelUploadRef = useRef(false);
  const [geoBusy, setGeoBusy] = useState(false);
  const [geoError, setGeoError] = useState<string | null>(null);
  const [placeAsk, setPlaceAsk] = useState<GeoPoint | null>(null);
  const [liveSharing, setLiveSharing] = useState(false);
  const [liveSpanOpen, setLiveSpanOpen] = useState(false);
  const [personOpen, setPersonOpen] = useState(false);
  const [giftOpen, setGiftOpen] = useState(false);
  const [isRecordingVideo, setIsRecordingVideo] = useState(false);
  // Мікрофон ⇄ кружечок: дотик перемикає, утримання пише поточний режим; останній режим пам'ятається.
  const [videoMode, setVideoModeState] = useState<boolean>(() => {
    try {
      return localStorage.getItem('phantom.micMode') === 'video';
    } catch {
      return false;
    }
  });
  const setVideoMode = (on: boolean) => {
    setVideoModeState(on);
    try {
      localStorage.setItem('phantom.micMode', on ? 'video' : 'voice');
    } catch {
      /* без сховища режим живе до перезапуску */
    }
  };
  const videoHoldRef = useRef(false);
  const [isTranscribingVoice, setIsTranscribingVoice] = useState(false);
  const [voiceText, setVoiceText] = useState<boolean>(() => loadVoiceText());
  const voiceTextRef = useRef(voiceText);
  voiceTextRef.current = voiceText;
  const photoInputRef = useRef<HTMLInputElement>(null);
  const cameraInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const musicInputRef = useRef<HTMLInputElement>(null);
  const sendVideoNote = useMessengerStore((st) => st.sendVideoNote);
  const sendGeoPoint = useMessengerStore((st) => st.sendGeoPoint);
  const currentUserId = useMessengerStore((st) => st.currentUser.id);
  const liveShareStart = useMessengerStore((st) => st.liveShareStart);
  const liveShareStop = useMessengerStore((st) => st.liveShareStop);
  const liveShareStatus = useMessengerStore((st) => st.liveShareStatus);
  const livePeers = chatMembers.filter((m) => !m.isMe && m.id !== currentUserId).map((m) => m.id);
  const firstLivePeer = livePeers[0] ?? '';
  const storageFailure = useMessengerStore((st) => st.storageFailure);
  const clearStorageFailure = useMessengerStore((st) => st.clearStorageFailure);

  /** Стільки стовпчиків малює ChatArea під голосовим листом. */
  const VOICE_WAVEFORM_BARS = 32;

  /*
   * Голосове (W0-6, 05 §2.6): тримай мікрофон — запис; угору 64 — замок;
   * уліво 40 % — скасування; відпустив без замка — надіслано. У замку —
   * жива хвиля, таймер, пауза (де MediaRecorder її вміє), «Стоп» → перегляд.
   * Дотик < 200 мс нічого не пише — лише підказка; другий дотик, поки
   * підказка видна, клавіатура, зчитувач екрана і миша записують без
   * утримання (одразу в замку). Логіка жесту — чиста машина `voiceGesture`.
   */
  const voice = useVoiceNote();
  const nextVoiceModeRef = useRef<VoiceNoteMode>('voice');
  const [gesture, setGesture] = useState<VoiceGestureState>(VOICE_IDLE);
  const gestureRef = useRef<VoiceGestureState>(VOICE_IDLE);
  const holdTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const holdPointerIdRef = useRef<number | null>(null);
  /** Останній дотик/перо по мікрофону — щоб його «click» не став ще й записом без утримання. */
  const lastTouchAtRef = useRef(Number.NEGATIVE_INFINITY);
  const voiceRowRef = useRef<HTMLDivElement>(null);
  const [voiceHint, setVoiceHint] = useState(false);
  const voiceHintTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [voiceBin, setVoiceBin] = useState(false);
  const voiceBinTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [preview, setPreview] = useState<(VoiceTake & { url: string }) | null>(null);
  const previewRef = useRef<(VoiceTake & { url: string }) | null>(null);
  const stoppingRef = useRef<Promise<void> | null>(null);
  const previewAudioRef = useRef<HTMLAudioElement>(null);
  const [previewPlaying, setPreviewPlaying] = useState(false);
  const [previewPosMs, setPreviewPosMs] = useState(0);

  const clock = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

  const dropPreview = () => {
    const p = previewRef.current;
    previewRef.current = null;
    setPreview(null);
    setPreviewPlaying(false);
    setPreviewPosMs(0);
    if (p) {
      try {
        URL.revokeObjectURL(p.url);
      } catch {
        /* ignore */
      }
    }
  };

  // Підказка і тост мікрофона стоять в одному місці над композером: видно лише одне.
  const voiceToastRef = useRef<string | null>(null);
  const voiceWarn = (key: MessageKey) => {
    if (voiceHintTimerRef.current) clearTimeout(voiceHintTimerRef.current);
    setVoiceHint(false);
    const ui = useUIStore.getState();
    if (voiceToastRef.current) ui.dismissToast(voiceToastRef.current);
    voiceToastRef.current = ui.toast({ kind: 'warn', message: t(key) });
  };

  const showVoiceHint = () => {
    if (voiceHintTimerRef.current) clearTimeout(voiceHintTimerRef.current);
    if (voiceToastRef.current) {
      useUIStore.getState().dismissToast(voiceToastRef.current);
      voiceToastRef.current = null;
    }
    setVoiceHint(true);
    voiceHintTimerRef.current = setTimeout(() => setVoiceHint(false), VOICE_HINT_MS);
  };

  const showVoiceBin = () => {
    if (voiceBinTimerRef.current) clearTimeout(voiceBinTimerRef.current);
    setVoiceBin(true);
    voiceBinTimerRef.current = setTimeout(() => setVoiceBin(false), 600);
  };

  const deliverVoice = async (take: VoiceTake) => {
    if (!take.blob || take.blob.size === 0) {
      voiceWarn('messageComposer.micEmptyNotSent');
      return;
    }
    if (voiceIsSilent(take.envelope)) {
      voiceWarn('messageComposer.micBusy');
      return;
    }
    soundFx.playSend();
    const duration = Math.max(1, Math.round(take.durationMs / 1000));
    const waveform = compressEnvelope(take.envelope, VOICE_WAVEFORM_BARS);
    let transcript = '';
    if (voiceTextRef.current) {
      setIsTranscribingVoice(true);
      try {
        const result = await voiceApi.transcribe(take.blob);
        transcript = typeof result.text === 'string' ? result.text.trim() : '';
      } catch {
        useUIStore.getState().toast({
          kind: 'warn',
          message: t('messageComposer.transcribeFailed'),
        });
      } finally {
        setIsTranscribingVoice(false);
      }
    }
    // Без реального STT transcript порожній: назва типу повідомлення не є
    // словами користувача і не має виглядати як підроблена розшифровка.
    _onSendVoiceMessage(duration, transcript, take.blob, waveform);
  };

  const [voiceDraft, setVoiceDraft] = useState<VoiceDraft | null>(null);
  const draftOwnedRef = useRef(false);
  const warmRef = useRef(false);

  const runVoiceEffects = (effects: VoiceEffect[], fromPressing = false) => {
    for (const fx of effects) {
      if (chatId && (fx === 'keep' || ((fx === 'send' || fx === 'discard') && draftOwnedRef.current))) {
        if (fx === 'keep') draftOwnedRef.current = true;
        else {
          draftOwnedRef.current = false;
          setVoiceDraft(null);
        }
        void applyVoiceDraftEffect(idbVoiceDraftStorage, chatId, fx, voice.snapshot);
      }
      switch (fx) {
        case 'warm':
        case 'start': {
          // Утримання підхоплює запис, що йде від дотику: перші слова не губляться.
          if (fx === 'start' && warmRef.current) {
            warmRef.current = false;
            nextVoiceModeRef.current = 'voice';
            break;
          }
          if (fx === 'warm' && !micAlreadyGranted()) break;
          warmRef.current = fx === 'warm';
          const modeToStart = nextVoiceModeRef.current;
          if (fx === 'start') nextVoiceModeRef.current = 'voice';
          dropPreview();
          void voice.start(modeToStart).then((result) => {
            if (result !== 'denied') return;
            dispatchVoice({ type: 'fail' });
            voiceWarn('messageComposer.micDenied');
          });
          break;
        }
        case 'pause':
          voice.pause();
          break;
        case 'resume':
          voice.resume();
          break;
        case 'stop':
          stoppingRef.current = voice.finish().then((take) => {
            stoppingRef.current = null;
            if (!take.blob) {
              dispatchVoice({ type: 'fail' });
              voiceWarn('messageComposer.micEmpty');
              return;
            }
            const p = { ...take, url: URL.createObjectURL(take.blob) };
            previewRef.current = p;
            setPreview(p);
          });
          break;
        case 'send':
          void (async () => {
            if (stoppingRef.current) await stoppingRef.current;
            const p = previewRef.current;
            if (p) {
              previewAudioRef.current?.pause();
              const take: VoiceTake = { blob: p.blob, durationMs: p.durationMs, envelope: p.envelope };
              dropPreview();
              await deliverVoice(take);
              return;
            }
            await deliverVoice(await voice.finish());
          })();
          break;
        case 'discard':
          warmRef.current = false;
          previewAudioRef.current?.pause();
          voice.discard();
          dropPreview();
          // Дотик без утримання запису не мав — смітнику нема чого показувати.
          if (!effects.includes('hint') && !fromPressing) showVoiceBin();
          break;
        case 'hint':
          showVoiceHint();
          break;
        case 'haptic:threshold':
          voiceHaptic('threshold');
          break;
        case 'haptic:reject':
          voiceHaptic('reject');
          break;
        case 'haptic:confirm':
          // «confirm» несе сам soundFx.playSend у deliverVoice — у мить, коли лист
          // справді пішов; другий поштовх тут дав би подвійну вібрацію.
          break;
      }
    }
  };

  function dispatchVoice(event: VoiceGestureEvent) {
    const fromPressing = gestureRef.current.phase === 'pressing';
    const { state, effects } = voiceGesture(gestureRef.current, event);
    if (state !== gestureRef.current) {
      gestureRef.current = state;
      setGesture(state);
    }
    if (effects.length) runVoiceEffects(effects, fromPressing);
  }

  useEffect(() => {
    if (!chatId || gestureRef.current.phase !== 'idle') return;
    let alive = true;
    void loadVoiceDraft(idbVoiceDraftStorage, chatId).then((d) => {
      if (alive) setVoiceDraft(d);
    });
    return () => {
      alive = false;
    };
  }, [chatId]);

  useEffect(() => {
    void probeMicGrant();
  }, []);

  useEffect(() => {
    const away = () => dispatchVoice({ type: 'interrupt', background: true });
    const onVis = () => {
      if (document.visibilityState === 'hidden') away();
    };
    document.addEventListener('visibilitychange', onVis);
    window.addEventListener('pagehide', away);
    const unsub = callEngine.subscribe(() => {
      if (callEngine.getSnapshot().state === 'ringing') away();
    });
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      window.removeEventListener('pagehide', away);
      unsub();
    };
  }, []);

  const resumeDraft = () => {
    if (!voiceDraft) return;
    const blob = voiceDraftBlob(voiceDraft);
    const p = { blob, durationMs: voiceDraft.durationMs, envelope: voiceDraft.envelope, url: URL.createObjectURL(blob) };
    previewRef.current = p;
    setPreview(p);
    draftOwnedRef.current = true;
    dispatchVoice({ type: 'restore' });
  };

  const sendDraft = () => {
    resumeDraft();
    dispatchVoice({ type: 'send' });
  };

  const deleteDraft = () => {
    draftOwnedRef.current = true;
    runVoiceEffects(['discard']);
  };

  const voicePhase = gesture.phase;
  const voiceProgressNow = voiceProgress(gesture);
  const voiceBusy = voicePhase === 'holding' || voicePhase === 'locked' || voicePhase === 'paused' || voicePhase === 'preview';

  useEffect(() => () => {
    if (holdTimerRef.current) clearTimeout(holdTimerRef.current);
    if (voiceHintTimerRef.current) clearTimeout(voiceHintTimerRef.current);
    if (voiceBinTimerRef.current) clearTimeout(voiceBinTimerRef.current);
    const p = previewRef.current;
    if (p) {
      try {
        URL.revokeObjectURL(p.url);
      } catch {
        /* ignore */
      }
    }
  }, []);

  const clearHold = () => {
    if (holdTimerRef.current) clearTimeout(holdTimerRef.current);
    holdTimerRef.current = null;
  };

  const handleMicPointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    // Миша — ПК: клік без утримання (05 §2.6 «ПК»), його веде onClick.
    if (e.pointerType === 'mouse') return;
    lastTouchAtRef.current = clock();
    holdPointerIdRef.current = e.pointerId;
    if (videoMode) clearHold();
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      /* старий WebView без захоплення: рух усе одно приходить, поки палець на кнопці */
    }
    if (videoMode) {
      videoHoldRef.current = false;
      clearHold();
      holdTimerRef.current = setTimeout(() => {
        holdTimerRef.current = null;
        videoHoldRef.current = true;
        setIsRecordingVideo(true);
      }, VOICE_HOLD_MS);
      return;
    }
    const width = voiceRowRef.current?.getBoundingClientRect().width || (typeof window !== 'undefined' ? window.innerWidth : 360);
    const t = clock();
    dispatchVoice({ type: 'down', x: e.clientX, y: e.clientY, t, width });
    clearHold();
    holdTimerRef.current = setTimeout(() => {
      holdTimerRef.current = null;
      dispatchVoice({ type: 'hold', t: t + VOICE_HOLD_MS });
    }, VOICE_HOLD_MS);
  };

  const handleMicPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (holdPointerIdRef.current !== e.pointerId) return;
    dispatchVoice({ type: 'move', x: e.clientX, y: e.clientY });
  };

  const handleMicPointerUp = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (holdPointerIdRef.current !== e.pointerId) return;
    holdPointerIdRef.current = null;
    lastTouchAtRef.current = clock();
    clearHold();
    if (videoMode) {
      if (!videoHoldRef.current) setVideoMode(false);
      videoHoldRef.current = false;
      return;
    }
    if (gestureRef.current.phase === 'pressing') {
      dispatchVoice({ type: 'interrupt' });
      setVideoMode(true);
      return;
    }
    dispatchVoice({ type: 'up', t: clock() });
  };

  const handleMicPointerCancel = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (holdPointerIdRef.current !== e.pointerId) return;
    holdPointerIdRef.current = null;
    clearHold();
    dispatchVoice({ type: 'interrupt' });
  };

  const startLocked = (mode: VoiceNoteMode = 'voice') => {
    nextVoiceModeRef.current = mode;
    dispatchVoice({ type: 'activate' });
  };

  /** Клавіатура, зчитувач екрана, миша — запис без утримання. Дотик сюди не доходить. */
  const handleMicClick = () => {
    if (clock() - lastTouchAtRef.current < 1000) return;
    if (videoMode) {
      setIsRecordingVideo(true);
      return;
    }
    startLocked('voice');
  };

  /**
   * «Моє місце»: одна точка з браузера, з часом ВИМІРУ в тілі кадру.
   *
   * Тут не вигадується нічого: немає дозволу, немає координат, не встиг
   * пристрій — так і кажемо, замість останньої відомої або нуля на екваторі.
   */
  const sendPlace = (point: GeoPoint) => {
    void sendGeoPoint(point).catch(() => setGeoError(t('messageComposer.placeNotSent')));
  };

  const shareMyPlace = () => {
    setShowAttachMenu(false);
    setGeoError(null);
    setGeoBusy(true);
    geoNow().then(
      (point) => {
        setGeoBusy(false);
        // Точне місце йде іншим людям лише після «Надіслати»; «Збережене» — лише моє.
        if (chatId === SAVED_NOTES_ID) sendPlace(point);
        else setPlaceAsk(point);
      },
      (why: GeoNowFail) => {
        setGeoBusy(false);
        setGeoError(t(`messageComposer.${why}` as const));
      },
    );
  };

  useEffect(() => {
    if (wherePeer || !firstLivePeer) {
      setLiveSharing(false);
      return;
    }
    let alive = true;
    void liveShareStatus(firstLivePeer).then((active) => {
      if (alive) setLiveSharing(active);
    });
    return () => {
      alive = false;
    };
  }, [chatId, wherePeer, firstLivePeer, liveShareStatus]);

  const shareLiveLocation = async () => {
    setShowAttachMenu(false);
    setGeoError(null);
    if (!livePeers.length) return;
    if (liveSharing) {
      await liveShareStop(livePeers);
      setLiveSharing(false);
      return;
    }
    setLiveSpanOpen(true);
  };

  const startLiveFor = async (minutes: number) => {
    setLiveSpanOpen(false);
    const ok = await liveShareStart(livePeers, minutes);
    if (ok) {
      setLiveSharing(true);
    } else {
      setGeoError(t('messageComposer.liveUnsupported'));
    }
  };

  /**
   * Завеликий файл відсівається ТУТ — на виборі, до будь-якої спроби.
   *
   * Межа доти перевірялась усередині `sendAttachment`, тобто ПІСЛЯ того, як
   * людина натиснула «Надіслати»: у пачці завеликий файл спокійно лежав у
   * сітці передогляду поруч зі здоровими, з мініатюрою й розміром, і ставав
   * червоним рядком лише в кінці — коли решта вже поїхала. Людина читає, чому
   * і яка межа, перш ніж чекати.
   *
   * Межу називає ВУЗОЛ (`/identity` -> `max_attachment_bytes`), а не цей файл:
   * див. `mediaLimitLabel()` у `messengerMedia.ts`. Тут вона лише
   * переказується словами.
   */
  const sieveBySize = (files: File[]): { fit: File[]; refused: string[] } => {
    const fit: File[] = [];
    const refused: string[] = [];
    const chat = chatId ? useMessengerStore.getState().chats.find((c) => c.id === chatId) : undefined;
    for (const file of files) {
      // Фото стор зменшить до перевірки — тут судимо лише те, що поїде як є.
      const route = file.size > 0 && !isRenderableImage(file.name, file.type) ? routeForChat(chat, file.size, MEDIA_PLAIN_LIMIT_BYTES) : null;
      if (file.size <= 0) {
        refused.push(t('messageComposer.fileEmpty', { name: file.name }));
      } else if (route?.kind === 'too_big' && route.who === 'peer') {
        refused.push(t('carrier.peerTooOld', { name: file.name }));
      } else if (route?.kind !== 'v2' && file.size > MEDIA_PLAIN_LIMIT_BYTES) {
        refused.push(
          file.size - MEDIA_PLAIN_LIMIT_BYTES <= 4096
            ? t('messageComposer.fileSlightlyLarge', { name: file.name, limit: mediaLimitLabel() })
              : t('messageComposer.fileTooLarge', {
                name: file.name,
                size: humanSize(file.size),
                limit: mediaLimitLabel(),
              }),
        );
      } else {
        fit.push(file);
      }
    }
    return { fit, refused };
  };

  /**
   * Один не-фото файл їде напряму. Фото й кілька файлів — власник хотів
   * ГРУПУВАННЯ, а не чергу по одному: замість негайного надсилання відкриваємо
   * спільний передогляд (мініатюри, можна прибрати зайве, один підпис на
   * пачку), і лист у стрічку йде лише після «Надіслати N».
   */
  const handlePickedFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files || []);
    e.target.value = '';
    if (!picked.length) return;
    setShowAttachMenu(false);
    setUploadError(null);

    // Відсів ДО показу пачки: завеликий файл не сміє лежати в сітці, вдаючи,
    // що чекає своєї черги.
    const capsChat = chatId ? useMessengerStore.getState().chats.find((c) => c.id === chatId) : undefined;
    if (chatCapsPending(capsChat)) await ensureChatCaps(capsChat);
    const { fit: files, refused } = sieveBySize(picked);
    if (refused.length) setUploadError(refused.join('; '));
    if (!files.length) return;

    if (files.length === 1 && !isRenderableImage(files[0].name, files[0].type)) {
      await sendFilesSequentially(files);
      return;
    }

    const items = files.map((file) => ({
      file,
      url: URL.createObjectURL(file),
      isImage: isRenderableImage(file.name, file.type),
    }));
    setPendingBatch({ id: `batch_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`, items });
  };

  /** Прибирає передогляд і звільняє його локальні object URL. */
  const closePendingBatch = () => {
    if (pendingBatch) {
      for (const it of pendingBatch.items) URL.revokeObjectURL(it.url);
    }
    setPendingBatch(null);
    setCropIdx(null);
  };

  const replaceInPendingBatch = (idx: number, file: File) => {
    setPendingBatch((prev) => {
      if (!prev || !prev.items[idx] || prev.items[idx].file === file) return prev;
      URL.revokeObjectURL(prev.items[idx].url);
      const items = prev.items.map((it, i) => (i === idx ? { ...it, file, url: URL.createObjectURL(file) } : it));
      return { ...prev, items };
    });
  };

  const choosePhotoQuality = (q: PhotoQuality) => {
    setPhotoQuality(q);
    writePhotoQuality(q);
  };

  const removeFromPendingBatch = (idx: number) => {
    setPendingBatch((prev) => {
      if (!prev) return prev;
      const removed = prev.items[idx];
      if (removed) URL.revokeObjectURL(removed.url);
      const items = prev.items.filter((_, i) => i !== idx);
      if (!items.length) return null;
      return { ...prev, items };
    });
  };

  // Звільняємо object URL передогляду, якщо composer зникає з непідтвердженою пачкою.
  useEffect(() => {
    return () => {
      if (pendingBatch) for (const it of pendingBatch.items) URL.revokeObjectURL(it.url);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Вкладення їдуть через стор (`sendAttachments`): бульбашка кожного файла
   * зʼявляється одразу з часткою вивантаженого, а на вузол — по три
   * одночасно (services/uploadQueue), фото зменшене й з розмірами. Порядок
   * записів у розмові — порядок вибору. Людина бачить ОДНУ прогресію на всю
   * пачку. Підпис їде з першим вкладенням: його набрали ДО вибору файлів.
   * Скасування спиняє лише ті, що ще не почались: файл у дорозі доїде.
   */
  const sendFilesSequentially = async (files: File[], batchId?: string) => {
    const note = text.trim();
    if (note) {
      setText('');
      if (chatId && onDraftChangeRef.current) onDraftChangeRef.current(chatId, '');
    }
    const total = files.length;
    const label = total > 1 ? t('messageComposer.batchLabel', { n: total }) : files[0].name;
    cancelUploadRef.current = false;
    setUpload({ name: label, percent: 0, index: total, total });
    let failed: string[] = [];
    try {
      failed = await useMessengerStore.getState().sendAttachments(files, {
        caption: note || undefined,
        batchId,
        original: readPhotoQuality() === 'original',
        cancelled: () => cancelUploadRef.current,
        onProgress: (percent) => setUpload({ name: label, percent, index: total, total }),
      });
    } catch {
      failed = [total > 1 ? t('messageComposer.batchNotSent') : t('messageComposer.fileNotSent', { name: files[0].name })];
    }
    setUpload(null);
    cancelUploadRef.current = false;
    if (failed.length) setUploadError(failed.join('; '));
  };

  const confirmPendingBatch = async () => {
    if (!pendingBatch) return;
    const { id, items } = pendingBatch;
    // URL передогляду — локальні превʼю, не ті, що піде на вузол; закриваємо
    // панель і звільняємо їх ДО завантаження, а не після — людина вже бачить
    // єдину прогресію нижче замість статичної сітки, що завмерла.
    for (const it of items) URL.revokeObjectURL(it.url);
    setPendingBatch(null);
    setCropIdx(null);
    soundFx.playTap();
    await sendFilesSequentially(items.map((it) => it.file), id);
  };
  const [multiQuoteTitle, setMultiQuoteTitle] = useState(() => t('messageComposer.multiQuoteDefault'));

  // Mention autocomplete state
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [mentionCursorPos, setMentionCursorPos] = useState<number>(0);
  /** Members picked from the @ list in this draft: id → name. */
  const pickedRef = useRef(new Map<string, string>());
  useEffect(() => {
    pickedRef.current = new Map();
  }, [chatId]);

  const [selRange, setSelRange] = useState<{ start: number; end: number } | null>(null);
  const [linkPrompt, setLinkPrompt] = useState<{ start: number; end: number } | null>(null);
  const [linkUrl, setLinkUrl] = useState('');
  const linkInputRef = useRef<HTMLInputElement>(null);
  const pendingSelectionRef = useRef<{ start: number; end: number } | null>(null);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const prevChatIdRef = useRef<string | undefined>(chatId);
  const onDraftChangeRef = useRef(onDraftChange);
  onDraftChangeRef.current = onDraftChange;

  const syncSelection = () => {
    const el = textareaRef.current;
    if (!el) return;
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;
    if (start !== end) {
      setSelRange((prev) => (prev && prev.start === start && prev.end === end ? prev : { start, end }));
    } else {
      setSelRange((prev) => (prev === null ? prev : null));
    }
  };

  useEffect(() => {
    if (linkPrompt) {
      linkInputRef.current?.focus();
    }
  }, [linkPrompt]);

  // Sync draft when switching chats
  useEffect(() => {
    if (prevChatIdRef.current !== chatId) {
      // Switching to another chat: load this chat's initial draft
      prevChatIdRef.current = chatId;
      typingRef.current?.stop();
      setText(initialDraft || '');
      setShowAttachMenu(false);
      setMentionQuery(null);
      setSelRange(null);
      setLinkPrompt(null);
      setLinkUrl('');
    }
  }, [chatId, initialDraft]);

  const peerMsgId = effectivePeerMessage?.id;
  const peerMsgText = effectivePeerMessage?.text;
  const peerMsgSentAt = effectivePeerMessage?.sentAtIso;

  useEffect(() => {
    setSlot(null);
    if (!peerMsgId || !peerMsgText) return;
    if (isSlotUsed(chatId, peerMsgId)) return;
    const sentAt = sentMoment(peerMsgSentAt ?? undefined) ?? new Date();
    if (!wantsTime(peerMsgText, sentAt)) return;
    let alive = true;
    void dayApi
      .today()
      .then((state) => {
        if (!alive || !state) return;
        if (isSlotUsed(chatId, peerMsgId)) return;
        const items: readonly DayItem[] = state.items;
        if (state.calendar === 'denied' && items.length === 0) return;
        const next = freeSlots(items, { nowMs: Date.now(), ring: loadRing(chatId), max: 1 })[0] ?? null;
        setSlot(next);
      })
      .catch(() => {
        if (alive) setSlot(null);
      });
    return () => {
      alive = false;
    };
  }, [chatId, peerMsgId, peerMsgText, peerMsgSentAt]);

  // Композер росте під чернетку до ~6 рядків, далі — власний скрол. Без цього
  // з другого рядка людина не бачить, що пише: поле лишалось 28px завжди.
  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${composerHeight(el.scrollHeight, el)}px`;
    if (pendingSelectionRef.current) {
      const { start, end } = pendingSelectionRef.current;
      pendingSelectionRef.current = null;
      el.setSelectionRange(start, end);
    }
  }, [text]);

  // Sync editing message
  const draftBeforeEditRef = useRef<string | null>(null);
  const textNowRef = useRef(text);
  textNowRef.current = text;
  useEffect(() => {
    if (editingMessage) {
      if (draftBeforeEditRef.current === null) draftBeforeEditRef.current = textNowRef.current;
      setText(editingMessage.text || '');
      textareaRef.current?.focus();
    } else if (draftBeforeEditRef.current !== null) {
      setText(draftBeforeEditRef.current);
      draftBeforeEditRef.current = null;
    }
  }, [editingMessage]);

  // Track mentions in text and save draft per chat when typing
  const handleTextChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const val = e.target.value;
    const cursorPos = e.target.selectionStart || 0;
    setText(val);

    if (chatId && !editingMessage && onDraftChangeRef.current) {
      onDraftChangeRef.current(chatId, val);
    }
    if (signalTyping && chatId && !editingMessage) {
      if (val.trim()) typingRef.current?.input(chatId);
      else typingRef.current?.stop();
    }

    // Look back from cursor to see if inside @mention
    const textBeforeCursor = val.slice(0, cursorPos);
    const match = textBeforeCursor.match(/@([a-zA-Z0-9_\u0400-\u04FF]*)$/);

    if (match) {
      setMentionQuery(match[1].toLowerCase());
      setMentionCursorPos(cursorPos);
    } else {
      setMentionQuery(null);
    }
  };

  const handleSelectMention = (member: ChatMember) => {
    soundFx.playTap();
    if (!textareaRef.current) return;
    const cursorPos = mentionCursorPos;
    const textBefore = text.slice(0, cursorPos);
    const atIndex = textBefore.lastIndexOf('@');
    const textAfter = text.slice(cursorPos);

    const replacement = `@${member.name} `;
    pickedRef.current.set(member.id, member.name);
    const newText = text.slice(0, atIndex) + replacement + textAfter;
    setText(newText);
    if (chatId && !editingMessage && onDraftChangeRef.current) {
      onDraftChangeRef.current(chatId, newText);
    }
    setMentionQuery(null);

    setTimeout(() => {
      if (textareaRef.current) {
        textareaRef.current.focus();
        const newCursor = atIndex + replacement.length;
        textareaRef.current.setSelectionRange(newCursor, newCursor);
      }
    }, 50);
  };

  const handleSend = (opts?: { quiet?: boolean }) => {
    typingRef.current?.stop();
    if (editingMessage) {
      if (text.trim()) {
        onSaveEdit(editingMessage.id, text.trim());
        setText('');
        if (chatId && onDraftChangeRef.current) {
          onDraftChangeRef.current(chatId, '');
        }
      }
      return;
    }

    if (selectedMessagesForQuote.length > 0) {
      onSynthesizeMultiQuote(multiQuoteTitle, text.trim());
      setText('');
      if (chatId && onDraftChangeRef.current) {
        onDraftChangeRef.current(chatId, '');
      }
      return;
    }

    const trimmed = text.trim();
    if (!trimmed) return;

    soundFx.playSend();
    const mentions = mentionedIds(trimmed, pickedRef.current);
    pickedRef.current = new Map();
    onSendMessage(trimmed, mentions.length ? { ...opts, mentions } : opts);
    setText('');
    if (chatId && onDraftChangeRef.current) {
      onDraftChangeRef.current(chatId, '');
    }
    setMentionQuery(null);
    setSelRange(null);
    setLinkPrompt(null);
    setLinkUrl('');
  };

  const [sendMenu, setSendMenu] = useState(false);
  const sendHoldRef = useRef<number | null>(null);
  const sendHeldRef = useRef(false);
  const sendMenuAllowed = !editingMessage && selectedMessagesForQuote.length === 0;
  const endSendHold = () => {
    if (sendHoldRef.current !== null) window.clearTimeout(sendHoldRef.current);
    sendHoldRef.current = null;
  };
  const openSendMenu = () => {
    endSendHold();
    sendHeldRef.current = true;
    setSendMenu(true);
  };
  const startSendHold = () => {
    sendHeldRef.current = false;
    endSendHold();
    sendHoldRef.current = window.setTimeout(openSendMenu, SEND_HOLD_MS);
  };
  useEffect(() => endSendHold, []);
  const onSendTap = () => {
    if (sendHeldRef.current) {
      sendHeldRef.current = false;
      return;
    }
    handleSend();
  };
  const sendQuiet = () => {
    setSendMenu(false);
    handleSend({ quiet: true });
  };
  const scheduleDraft = () => {
    setSendMenu(false);
    const trimmed = text.trim();
    if (!trimmed || !onScheduleDraft) return;
    onScheduleDraft(trimmed, () => {
      setText('');
      if (chatId && onDraftChangeRef.current) onDraftChangeRef.current(chatId, '');
    });
  };

  const getActiveSelection = (): { start: number; end: number } | null => {
    const el = textareaRef.current;
    if (el && (el.selectionStart ?? 0) !== (el.selectionEnd ?? 0)) {
      return { start: el.selectionStart, end: el.selectionEnd };
    }
    if (selRange && selRange.start !== selRange.end) {
      return selRange;
    }
    return null;
  };

  const commitFormat = (nextText: string, nextStart: number, nextEnd: number) => {
    const el = textareaRef.current;
    pendingSelectionRef.current = { start: nextStart, end: nextEnd };
    setText(nextText);
    if (chatId && !editingMessage && onDraftChangeRef.current) {
      onDraftChangeRef.current(chatId, nextText);
    }
    setSelRange(nextStart !== nextEnd ? { start: nextStart, end: nextEnd } : null);
    if (el) {
      el.value = nextText;
      el.focus();
      el.setSelectionRange(nextStart, nextEnd);
    }
  };

  const applyFormat = (kind: 'bold' | 'italic' | 'underline' | 'strike' | 'spoiler' | 'code' | 'quote') => {
    const range = getActiveSelection();
    if (!range) return;
    const { start, end } = range;
    const selected = text.slice(start, end);
    if (!selected) return;

    if (kind === 'quote') {
      if (start >= 3 && text.slice(start - 3, start) === '\n> ') {
        const inner = selected.replace(/\n> /g, '\n');
        commitFormat(text.slice(0, start - 3) + inner + text.slice(end), start - 3, start - 3 + inner.length);
        return;
      }
      if (start >= 2 && text.slice(start - 2, start) === '> ' && (start - 2 === 0 || text[start - 3] === '\n')) {
        const inner = selected.replace(/\n> /g, '\n');
        commitFormat(text.slice(0, start - 2) + inner + text.slice(end), start - 2, start - 2 + inner.length);
        return;
      }
      if (selected.startsWith('> ') && selected.split('\n').every((l) => l.startsWith('> '))) {
        const inner = selected.replace(/^> /gm, '');
        commitFormat(text.slice(0, start) + inner + text.slice(end), start, start + inner.length);
        return;
      }
      const innerWithQuotes = selected.replace(/\n/g, '\n> ');
      const prefix = start === 0 || text[start - 1] === '\n' ? '> ' : '\n> ';
      commitFormat(
        text.slice(0, start) + prefix + innerWithQuotes + text.slice(end),
        start + prefix.length,
        start + prefix.length + innerWithQuotes.length,
      );
      return;
    }

    const pairs: Array<{ o: string; c: string }> =
      kind === 'bold'
        ? [{ o: '**', c: '**' }]
        : kind === 'italic'
          ? [
              { o: '_', c: '_' },
              { o: '*', c: '*' },
            ]
          : kind === 'underline'
            ? [{ o: '__', c: '__' }]
            : kind === 'strike'
              ? [
                  { o: '~~', c: '~~' },
                  { o: '~', c: '~' },
                ]
              : kind === 'spoiler'
                ? [{ o: '||', c: '||' }]
                : selected.includes('\n')
                  ? [
                      { o: '```\n', c: '\n```' },
                      { o: '```', c: '```' },
                      { o: '`', c: '`' },
                    ]
                  : [
                      { o: '`', c: '`' },
                      { o: '```\n', c: '\n```' },
                      { o: '```', c: '```' },
                    ];

    for (const { o, c } of pairs) {
      const singleCharGuard =
        o.length !== 1 ||
        ((start < 2 || text[start - 2] !== o || (start >= 3 && text[start - 3] === o)) &&
          (end + 1 >= text.length || text[end + 1] !== c || (end + 2 < text.length && text[end + 2] === c)));
      if (
        singleCharGuard &&
        start >= o.length &&
        text.slice(start - o.length, start) === o &&
        text.slice(end, end + c.length) === c
      ) {
        commitFormat(text.slice(0, start - o.length) + selected + text.slice(end + c.length), start - o.length, end - o.length);
        return;
      }
      const selectedSingleGuard =
        o.length !== 1 || (!selected.startsWith(o + o) || selected.startsWith(o + o + o));
      if (selectedSingleGuard && selected.length > o.length + c.length && selected.startsWith(o) && selected.endsWith(c)) {
        const inner = selected.slice(o.length, selected.length - c.length);
        commitFormat(text.slice(0, start) + inner + text.slice(end), start, start + inner.length);
        return;
      }
    }

    const { o, c } = pairs[0];
    commitFormat(text.slice(0, start) + o + selected + c + text.slice(end), start + o.length, end + o.length);
  };

  const openLinkPrompt = () => {
    const range = getActiveSelection();
    if (!range) return;
    const { start, end } = range;
    const selected = text.slice(start, end);
    if (!selected) return;
    if (start >= 1 && text[start - 1] === '[') {
      const afterMatch = /^\]\(([^)\s]+)\)/.exec(text.slice(end));
      if (afterMatch) {
        commitFormat(
          text.slice(0, start - 1) + selected + text.slice(end + afterMatch[0].length),
          start - 1,
          end - 1,
        );
        return;
      }
    }
    const fullMatch = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(selected);
    if (fullMatch) {
      const inner = fullMatch[1];
      commitFormat(text.slice(0, start) + inner + text.slice(end), start, start + inner.length);
      return;
    }
    setLinkPrompt({ start, end });
    setLinkUrl('');
  };

  const applyLink = () => {
    const range = linkPrompt ?? getActiveSelection();
    const rawUrl = linkUrl.trim();
    setLinkPrompt(null);
    setLinkUrl('');
    if (!range || !rawUrl) {
      textareaRef.current?.focus();
      return;
    }
    const { start, end } = range;
    const selected = text.slice(start, end);
    const wrapped = `[${selected}](${rawUrl})`;
    const nextText = text.slice(0, start) + wrapped + text.slice(end);
    commitFormat(nextText, start + 1, start + 1 + selected.length);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.ctrlKey || e.metaKey) && !e.altKey) {
      const k = e.key.toLowerCase();
      if (e.shiftKey) {
        if (k === 'x') {
          e.preventDefault();
          applyFormat('strike');
          return;
        }
        if (k === 'p') {
          e.preventDefault();
          applyFormat('spoiler');
          return;
        }
      } else {
        if (k === 'b') {
          e.preventDefault();
          applyFormat('bold');
          return;
        }
        if (k === 'i') {
          e.preventDefault();
          applyFormat('italic');
          return;
        }
        if (k === 'u') {
          e.preventDefault();
          applyFormat('underline');
          return;
        }
        if (k === 'e') {
          e.preventDefault();
          applyFormat('code');
          return;
        }
        if (k === 'k') {
          e.preventDefault();
          openLinkPrompt();
          return;
        }
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const formatSlotParts = (s: FreeSlot) => {
    const range = formatSlotRange(s);
    const dayKind = slotDay(s.startMs, Date.now());
    const when =
      dayKind === 'today'
        ? t('freeSlots.today')
        : dayKind === 'tomorrow'
        ? t('freeSlots.tomorrow')
        : new Date(s.startMs).toLocaleDateString(locale, { weekday: 'short' });
    return { when, range };
  };

  const askChatType = useMessengerStore((s) => (chatId ? s.chats.find((c) => c.id === chatId)?.type : undefined));
  const writeWithNightpost = () => {
    const chat = chatId ? useMessengerStore.getState().chats.find((c) => c.id === chatId) : undefined;
    const name = wherePeer?.name ?? (chat && chat.type !== 'group' && chat.type !== 'channel' ? displayTitle(chat.title) : null);
    openAsk(composeAsk({ draft: text, lastIncoming: effectivePeerMessage?.text ?? null, name }, { t, locale }), {
      onInsert: (next) => {
        setText(next);
        if (chatId) onDraftChangeRef.current?.(chatId, next);
        textareaRef.current?.focus();
      },
    });
  };

  const insertSlot = () => {
    if (!slot || !effectivePeerMessage) return;
    soundFx.playTap();
    const { when, range } = formatSlotParts(slot);
    const reply = t('freeSlots.reply', { when, range });
    const trimmed = text.trim();
    const nextText = trimmed ? `${trimmed} ${reply}` : reply;
    setText(nextText);
    if (chatId && onDraftChangeRef.current) {
      onDraftChangeRef.current(chatId, nextText);
    }
    markSlotUsed(chatId, effectivePeerMessage.id);
    if (textareaRef.current) {
      textareaRef.current.focus();
      const len = nextText.length;
      textareaRef.current.setSelectionRange(len, len);
    }
  };

  const slotParts =
    slot &&
    !editingMessage &&
    selectedMessagesForQuote.length === 0 &&
    !voiceBusy &&
    !pendingBatch &&
    !upload &&
    !isSlotUsed(chatId, effectivePeerMessage?.id)
      ? formatSlotParts(slot)
      : null;

  const canSend = !!text.trim() || selectedMessagesForQuote.length > 0 || !!editingMessage;

  const chats = useMessengerStore((st) => st.chats);
  const memberIdsKey = chatMembers
    .filter((m) => !m.isMe)
    .map((m) => `${m.id}:${m.name}:${m.handle}`)
    .join(',');

  const mentionSuffixes = useMemo(
    () =>
      sameNameSuffixes(
        chatMembers
          .filter((m) => !m.isMe)
          .map((m) => ({
            id: m.id,
            name: m.name,
            handle: m.handle,
            ring: ((r) => r && circleLabel(t, r))(ringOfPeer(chats, m.id)),
          })),
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [memberIdsKey, chats],
  );

  const filteredMembers = mentionQuery !== null
    ? chatMembers.filter((m) =>
        m.name.toLowerCase().includes(mentionQuery) ||
        m.handle.toLowerCase().includes(mentionQuery)
      )
    : [];

  return (
    <div data-floating-composer className="px-2 md:px-3 pt-1.5 pb-[calc(var(--sab)+6px)] bg-canvas border-t border-line shrink-0 select-none relative z-30 text-ink">
      <BusinessChatBar chatId={chatId} />
      {/*
        Запис на диск не вдався — кажемо це там, де людина писала, і доти,
        доки воно правда. Тост тут не годиться: він гасне, а написане так і
        лишається незбереженим. Напис зникає сам, щойно наступний запис
        пройде (`persist` скидає `storageFailure` на успіху).
      */}
      {storageFailure && (
        <div
          data-testid="storage-failure"
          role="status"
          className="flex items-start gap-2 mb-1.5 px-3 py-2 rounded-control bg-raised text-callout leading-snug text-ink"
        >
          <AlertTriangle size={14} className="shrink-0 mt-0.5 text-danger" aria-hidden="true" />
          <span className="flex-1 min-w-0">{storageFailure}</span>
          <button
            type="button"
            onClick={clearStorageFailure}
            aria-label={t('messageComposer.hideStorageWarning')}
            className="touch-floor min-h-[44px] min-w-[44px] shrink-0 -my-2 -mr-2 flex items-center justify-center text-ink-3 hover:text-ink transition-colors"
          >
            <X size={14} aria-hidden="true" />
          </button>
        </div>
      )}

      {/* Mention Autocomplete Dropdown */}
      {mentionQuery !== null && filteredMembers.length > 0 && (
        <div className="absolute bottom-full left-4 mb-2 bg-surface rounded-control w-64 max-h-48 overflow-y-auto p-1.5 z-30 animate-in fade-in zoom-in-95 duration-100 text-ink">
          <p className="text-footnote font-semibold text-ink-3 px-2 py-1 ">
            {t('messageComposer.mentionMember')}
          </p>
          {filteredMembers.map((member) => {
            const suffix = mentionSuffixes.get(member.id);
            const tag = handleTag(member.handle);
            return (
              <button
                key={member.id}
                onClick={() => handleSelectMention(member)}
                className="touch-floor min-h-[44px] w-full flex items-center gap-2 px-2 py-1.5 hover:bg-raised rounded-control text-left transition-colors"
              >
                <img src={member.avatar} alt={member.name} className="w-6 h-6 rounded-control object-cover " />
                <div className="min-w-0 flex-1 text-callout">
                  <p className="font-semibold text-ink truncate">
                    {member.name}
                    {suffix && <span className="text-ink-3">{t('sameName.suffix', { suffix })}</span>}
                  </p>
                  {suffix !== tag && (
                    <p className="text-callout text-ink-3 truncate">{member.handle}</p>
                  )}
                </div>
              </button>
            );
          })}
        </div>
      )}

      {/* 1. Multi-Quote Synthesis Banner */}
      {selectedMessagesForQuote.length > 0 && (
        <div className="mb-2 p-2.5 bg-raised rounded-control space-y-2 animate-in fade-in duration-150">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Layers className="w-3.5 h-3.5 text-ink-2" strokeWidth={1.75} />
              <span className="font-semibold text-callout text-ink-2">
                {t('messageComposer.multiQuoteHeader', { n: selectedMessagesForQuote.length })}
              </span>
            </div>
            <button
              onClick={onClearSelectedQuotes}
              className="touch-floor min-h-[44px] min-w-[44px] flex items-center justify-center p-1 text-ink-3 hover:text-ink rounded-control"
            >
              <X className="w-3.5 h-3.5" strokeWidth={1.75} />
            </button>
          </div>

          <input
            type="text"
            value={multiQuoteTitle}
            onChange={(e) => setMultiQuoteTitle(e.target.value)}
            placeholder={t('messageComposer.multiQuoteTitle')}
            className="w-full px-2.5 py-2 bg-canvas rounded-control text-callout font-semibold text-ink placeholder:text-ink-3 focus:outline-none "
          />

          <div className="space-y-1 max-h-20 overflow-y-auto">
            {selectedMessagesForQuote.map((m) => (
              <div key={m.id} className="text-callout text-ink-3 bg-canvas p-1.5 rounded-control truncate">
                <span className="font-semibold text-ink-2">{m.senderName}: </span>
                <span className="text-ink">{m.text ? plainSnippet(m.text) : m.type}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Відповідь (§7.5): смуга 48 з планкою `accent-line`, автором і рядком цитати. */}
      {replyingTo && (
        <div data-testid="composer-reply" className="mb-2 min-h-12 pl-3 pr-1 flex items-center gap-3 f-fade-in md:max-w-[760px] md:mx-auto">
          <Reply className="w-5 h-5 text-accent-line shrink-0" strokeWidth={1.75} />
          <div className="min-w-0 flex-1 border-l-[3px] border-accent-line pl-2.5">
            <p className="text-footnote font-semibold text-ink truncate">
              {replyingTo.quotes && replyingTo.quotes.length > 1
                ? t('messageComposer.replyToN', { n: replyingTo.quotes.length })
                : replyingTo.isSelf
                  ? t('msgX.replyToSelf')
                  : t('messageComposer.replyTo', { name: replyingTo.senderName })}
            </p>
            <p className="text-footnote text-ink-2 truncate">
              {replyingTo.quoteSelectedText
                ? `«${plainSnippet(replyingTo.quoteSelectedText)}»`
                : replyingTo.quotes && replyingTo.quotes.length > 1
                  ? replyingTo.quotes.map((q) => `${q.senderName}: ${plainSnippet(q.text)}`).join(' · ')
                  : plainSnippet(replyingTo.text)}
            </p>
          </div>
          <button
            onClick={onCancelReply}
            className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2 hover:bg-overlay rounded-full transition-colors duration-quick shrink-0"
            title={t('messageComposer.cancelReply')}
            aria-label={t('messageComposer.cancelReply')}
          >
            <X className="w-5 h-5" strokeWidth={1.75} />
          </button>
        </div>
      )}

      {/* Редагування — та сама смуга, що й відповідь. */}
      {editingMessage && (
        <div data-testid="composer-edit" className="mb-2 min-h-12 pl-3 pr-1 flex items-center gap-3 f-fade-in md:max-w-[760px] md:mx-auto">
          <Pencil className="w-5 h-5 text-accent-line shrink-0" strokeWidth={1.75} />
          <div className="min-w-0 flex-1 border-l-[3px] border-accent-line pl-2.5">
            <p className="text-footnote font-semibold text-ink">{t('messageComposer.editing')}</p>
            <p className="text-footnote text-ink-2 truncate">{editingMessage.text ? plainSnippet(editingMessage.text) : ''}</p>
          </div>
          <button
            onClick={onCancelEdit}
            className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2 hover:bg-overlay rounded-full transition-colors duration-quick shrink-0"
            aria-label={t('messageComposer.cancelEdit')}
          >
            <X className="w-5 h-5" strokeWidth={1.75} />
          </button>
        </div>
      )}

      {/* Відкладені листи цієї розмови — смуга веде до шухляди. Створити
          відкладений можна лише з шухляди на ПК; плашки «Заплановано на» тут
          більше немає: її час не читав жоден диспетчер. */}
      {scheduledCountInCurrentChat > 0 && onOpenScheduledList ? (
        <button
          type="button"
          data-testid="composer-scheduled-strip"
          onClick={() => {
            soundFx.playTap();
            onOpenScheduledList();
          }}
          className="mb-2 w-full min-h-11 px-3 rounded-control bg-raised flex items-center gap-2 text-left text-callout text-ink-2 hover:text-ink transition-colors duration-quick md:max-w-[760px] md:mx-auto"
        >
          <Clock className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
          <span className="flex-1 min-w-0 truncate">
            {t(
              pickPlural(locale, scheduledCountInCurrentChat, 'messageComposer.scheduled1', 'messageComposer.scheduled2', 'messageComposer.scheduled5'),
              { n: scheduledCountInCurrentChat },
            )}
          </span>
          <span className="shrink-0 text-footnote font-semibold text-ink">{t('messageComposer.view')}</span>
        </button>
      ) : null}

      {/* Спільний передогляд ПАЧКИ вкладень — до відправки, не по одному.
          Підпис у полі композера нижче — ОДИН на всю пачку, як і раніше йшов
          лише з першим файлом. */}
      {pendingBatch && (
        <div
          data-testid="attach-batch-preview"
          className="mb-2 px-3 py-2.5 bg-raised rounded-control"
        >
          <div className="flex items-center justify-between gap-2 mb-2">
            <span className="text-footnote font-semibold text-ink">
              {t(pendingBatch.items.length === 1 ? 'messageComposer.batchReady1' : 'messageComposer.batchReadyN', { n: pendingBatch.items.length })}
            </span>
            <button
              type="button"
              onClick={closePendingBatch}
              className="touch-floor min-h-[44px] min-w-[44px] flex items-center justify-center text-ink-3 hover:text-ink shrink-0"
              aria-label={t('messageComposer.cancelBatch')}
              title={t('messageComposer.cancel')}
            >
              <X className="w-3.5 h-3.5" strokeWidth={1.75} />
            </button>
          </div>

          <div className="grid grid-cols-4 sm:grid-cols-5 gap-1.5 max-h-[36dvh] overflow-y-auto" data-testid="attach-batch-grid">
            {pendingBatch.items.map((it, idx) => (
              <div key={`${it.file.name}_${idx}`} className="relative aspect-square rounded-control overflow-hidden bg-canvas group/thumb">
                {it.isImage ? (
                  <button
                    type="button"
                    onClick={() => setCropIdx(idx)}
                    data-testid="attach-batch-crop"
                    className="block w-full h-full"
                    aria-label={t('messageComposer.cropName', { name: it.file.name })}
                    title={t('messageComposer.crop')}
                  >
                    <img src={it.url} alt="" className="w-full h-full object-cover" />
                    <span aria-hidden="true" className="absolute top-1 left-1 w-6 h-6 flex items-center justify-center rounded-full bg-canvas text-ink">
                      <Crop className="w-3 h-3" strokeWidth={2} />
                    </span>
                  </button>
                ) : (
                  <div className="w-full h-full flex flex-col items-center justify-center gap-1 p-1 text-center">
                    <FileIcon className="w-5 h-5 text-ink-3" strokeWidth={1.75} />
                    <span className="text-caption text-ink-3 truncate w-full px-0.5">{it.file.name}</span>
                  </div>
                )}
                <button
                  type="button"
                  onClick={() => removeFromPendingBatch(idx)}
                  data-testid="attach-batch-remove"
                  className="touch-floor-inline absolute top-1 right-1 w-6 h-6 flex items-center justify-center rounded-full bg-canvas text-ink"
                  aria-label={t('messageComposer.removeFromBatch', { name: it.file.name })}
                  title={t('messageComposer.remove')}
                >
                  <X className="w-3 h-3" strokeWidth={2} />
                </button>
                <span className="absolute bottom-0 left-0 right-0 px-1 py-0.5 bg-canvas text-caption text-ink truncate">
                  {humanSize(it.file.size)}
                </span>
              </div>
            ))}
          </div>

          {pendingBatch.items.some((it) => it.isImage) && (
            <div role="group" aria-label={t('messageComposer.quality')} data-testid="attach-quality" className="mt-2 grid grid-cols-2 gap-1 p-0.5 rounded-control bg-canvas">
              {(['compressed', 'original'] as const).map((q) => (
                <button
                  key={q}
                  type="button"
                  aria-pressed={photoQuality === q}
                  data-quality={q}
                  onClick={() => choosePhotoQuality(q)}
                  className={`touch-floor min-h-[36px] rounded-control text-footnote font-semibold transition-colors ${photoQuality === q ? 'bg-raised text-ink' : 'text-ink-3 hover:text-ink'}`}
                >
                  {t(q === 'original' ? 'messageComposer.qualityOriginal' : 'messageComposer.qualityCompressed')}
                </button>
              ))}
            </div>
          )}

          {cropIdx !== null && pendingBatch.items[cropIdx]?.isImage && (
            <MediaCropSheet
              file={pendingBatch.items[cropIdx].file}
              url={pendingBatch.items[cropIdx].url}
              onClose={() => setCropIdx(null)}
              onDone={(file) => {
                replaceInPendingBatch(cropIdx, file);
                setCropIdx(null);
              }}
            />
          )}

          <button
            type="button"
            onClick={confirmPendingBatch}
            data-testid="attach-batch-send"
            className="touch-floor min-h-[44px] w-full mt-2.5 flex items-center justify-center gap-1.5 py-2 rounded-control bg-accent hover:bg-accent-hover text-on-accent text-headline transition-colors"
          >
            <Send className="w-3.5 h-3.5" strokeWidth={2} />
            {t('messageComposer.sendN', { n: pendingBatch.items.length })}
          </button>
        </div>
      )}

      {/* Upload Progress */}
      {upload && (
        <div className="mb-2 px-3 py-2 bg-raised rounded-control">
          <div className="flex items-center justify-between gap-2 mb-1.5">
            <span className="text-footnote font-semibold text-ink truncate">
              {upload.total > 1 && (
                <span className="text-ink-2 tnum">
                  {t('composer.uploadOf', { i: upload.index, n: upload.total })} ·{' '}
                </span>
              )}
              {upload.name}
            </span>
            <span className="flex items-center gap-1.5 shrink-0">
              <span className="text-footnote font-semibold text-ink-2 tnum">
                {upload.percent}%
              </span>
              {upload.total > 1 && (
                <button
                  type="button"
                  onClick={() => {
                    cancelUploadRef.current = true;
                  }}
                  data-testid="attach-upload-cancel"
                  className="touch-floor min-h-[28px] min-w-[28px] flex items-center justify-center rounded-full text-ink-3 hover:text-danger"
                  aria-label={t('messageComposer.stopBatchAria')}
                  title={t('messageComposer.stopBatch')}
                >
                  <X className="w-3.5 h-3.5" strokeWidth={2} />
                </button>
              )}
            </span>
          </div>
          <div className="h-1 bg-canvas rounded-full overflow-hidden">
            <div
              className="h-full bg-accent transition-[width] duration-150"
              style={{ width: `${upload.percent}%` }}
            />
          </div>
        </div>
      )}

      {geoBusy && (
        <div className="mb-2 px-3 py-2 bg-raised rounded-control">
          <span className="text-callout text-ink-2">
            {t('messageComposer.askingPlace')}
          </span>
        </div>
      )}

      <FormSheet open={!!placeAsk} onClose={() => setPlaceAsk(null)} title={t('messageComposer.placeAsk')} testId="place-ask">
        <div className="px-4 pb-4">
          <p className="text-headline text-ink">{t('geoPointBubble.mine')}</p>
          {placeAsk?.accuracyM !== undefined && (
            <p className="text-footnote text-ink-3 tnum">{t('messageComposer.placeAccuracy', { m: Math.max(1, Math.round(placeAsk.accuracyM)) })}</p>
          )}
          <div className="mt-4 grid grid-cols-2 gap-2">
            <button type="button" onClick={() => setPlaceAsk(null)} className="min-h-12 py-2 rounded-full bg-raised text-headline text-ink">
              {t('messageComposer.placeCancel')}
            </button>
            <button
              type="button"
              data-testid="place-ask-send"
              onClick={() => {
                const point = placeAsk;
                setPlaceAsk(null);
                if (point) sendPlace(point);
              }}
              className="min-h-12 py-2 rounded-full bg-accent text-headline text-on-accent"
            >
              {t('messageComposer.placeSend')}
            </button>
          </div>
        </div>
      </FormSheet>

      {(uploadError || geoError) && (
        <div className="mb-2 px-3 py-2 bg-raised rounded-control flex items-center justify-between gap-2 text-danger">
          <span className="text-callout">{uploadError || geoError}</span>
          <button
            type="button"
            onClick={() => {
              setUploadError(null);
              setGeoError(null);
            }}
            className="touch-floor min-h-[44px] min-w-[44px] flex items-center justify-center text-danger hover:opacity-70 shrink-0"
            aria-label={t('messageComposer.hideError')}
          >
            <X className="w-3.5 h-3.5" strokeWidth={1.75} />
          </button>
        </div>
      )}

      {slotParts && (
        <button
          type="button"
          data-testid="composer-free-slot"
          onClick={insertSlot}
          aria-label={t('freeSlots.chip')}
          className="mb-2 min-h-11 px-3 rounded-full bg-raised text-callout text-ink flex items-center gap-2 hover:bg-overlay transition-colors duration-quick md:max-w-[760px] md:mx-auto"
        >
          <CalendarClock className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />
          <span>{`${slotParts.when} ${slotParts.range}`}</span>
        </button>
      )}

      <input
        ref={photoInputRef}
        type="file"
        accept="image/*,video/*"
        multiple
        className="hidden"
        onChange={handlePickedFile}
        data-testid="composer-photo-input"
      />
      <input
        ref={cameraInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="hidden"
        onChange={handlePickedFile}
        data-testid="composer-camera-input"
      />
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={handlePickedFile}
        data-testid="composer-file-input"
      />
      <input
        ref={musicInputRef}
        type="file"
        accept="audio/*"
        multiple
        className="hidden"
        onChange={handlePickedFile}
        data-testid="composer-music-input"
      />

      {voiceDraft && !voiceBusy && !isTranscribingVoice ? (
        <div data-testid="voice-draft-offer" className="flex items-center gap-2 px-3 pb-2 md:max-w-[760px] md:mx-auto">
          <span className="text-subhead text-ink-2 tnum">{fmtVoice(voiceDraft.durationMs)}</span>
          <button type="button" data-testid="voice-draft-resume" onClick={resumeDraft} className="min-h-11 px-3 rounded-full bg-accent text-on-accent text-callout">
            {t('voiceDraft.resume')}
          </button>
          <button type="button" data-testid="voice-draft-send" onClick={sendDraft} className="min-h-11 px-3 rounded-full text-ink text-callout hover:bg-overlay">
            {t('voiceDraft.send')}
          </button>
          <button type="button" data-testid="voice-draft-delete" onClick={deleteDraft} className="min-h-11 px-3 rounded-full text-ink-2 text-callout hover:bg-overlay">
            {t('voiceDraft.delete')}
          </button>
        </div>
      ) : null}

      {((selRange && selRange.start !== selRange.end) || linkPrompt) && !voiceBusy && !isTranscribingVoice && (
        <div
          data-testid="composer-format-bar"
          role="toolbar"
          className="mb-1.5 min-h-11 px-1 bg-surface border border-line rounded-control flex items-center gap-0.5 overflow-x-auto no-scrollbar md:max-w-[760px] md:mx-auto"
        >
          {(
            [
              { kind: 'bold', icon: Bold, label: t('composer.fmt.bold') },
              { kind: 'italic', icon: Italic, label: t('composer.fmt.italic') },
              { kind: 'underline', icon: Underline, label: t('composer.fmt.underline') },
              { kind: 'strike', icon: Strikethrough, label: t('composer.fmt.strike') },
              { kind: 'spoiler', icon: EyeOff, label: t('composer.fmt.spoiler') },
              { kind: 'code', icon: Code, label: t('composer.fmt.code') },
              { kind: 'quote', icon: Quote, label: t('composer.fmt.quote') },
            ] as const
          ).map(({ kind, icon: Icon, label }) => (
            <button
              key={kind}
              type="button"
              data-testid={`composer-fmt-${kind}`}
              aria-label={label}
              title={label}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => applyFormat(kind)}
              className="w-11 h-11 min-h-[44px] min-w-[44px] shrink-0 rounded-control flex items-center justify-center text-ink hover:bg-overlay active:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
            >
              <Icon className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            </button>
          ))}
          <button
            type="button"
            data-testid="composer-fmt-link"
            aria-label={t('composer.fmt.link')}
            title={t('composer.fmt.link')}
            onMouseDown={(e) => e.preventDefault()}
            onClick={openLinkPrompt}
            className="w-11 h-11 min-h-[44px] min-w-[44px] shrink-0 rounded-control flex items-center justify-center text-ink hover:bg-overlay active:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
          >
            <Link2 className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          </button>
        </div>
      )}

      {linkPrompt && !voiceBusy && !isTranscribingVoice && (
        <div
          data-testid="composer-fmt-link-row"
          className="mb-1.5 min-h-11 pl-3 pr-1 bg-surface border border-line rounded-control flex items-center gap-1.5 md:max-w-[760px] md:mx-auto"
        >
          <Link2 className="w-4 h-4 text-ink-3 shrink-0" strokeWidth={1.75} aria-hidden />
          <input
            ref={linkInputRef}
            type="url"
            data-testid="composer-fmt-link-url"
            value={linkUrl}
            onChange={(e) => setLinkUrl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                applyLink();
              } else if (e.key === 'Escape') {
                e.preventDefault();
                setLinkPrompt(null);
                setLinkUrl('');
                textareaRef.current?.focus();
              }
            }}
            placeholder="https://"
            aria-label={t('composer.fmt.linkUrl')}
            className="flex-1 min-w-0 h-11 bg-transparent text-callout text-ink placeholder:text-ink-3 focus:outline-none"
          />
          <button
            type="button"
            data-testid="composer-fmt-link-apply"
            onMouseDown={(e) => e.preventDefault()}
            onClick={applyLink}
            aria-label={t('composer.fmt.apply')}
            title={t('composer.fmt.apply')}
            className="w-11 h-11 min-h-[44px] min-w-[44px] shrink-0 rounded-control flex items-center justify-center text-ink hover:bg-overlay active:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
          >
            <Check className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          </button>
        </div>
      )}

      {/* [+] [поле з камерою] [мікрофон/надіслати] — макет 04. */}
      <div className="flex items-end gap-2 md:max-w-[760px] md:mx-auto">
        <ComposerSheet data-composer-pill>
          {/* Attachments Button (+) — під час запису його місце займає смуга запису */}
          <div className={`relative shrink-0 ${voiceBusy ? 'hidden' : ''}`}>
            <button
              type="button"
              onClick={() => {
                soundFx.playTap();
                if (!showAttachMenu) refreshPayPeer();
                setShowAttachMenu((v: boolean) => !v);
              }}
              data-testid="composer-attach"
              className={`${COMPOSER_GHOST_BUTTON} ${showAttachMenu ? 'bg-overlay' : ''}`}
              title={t('messageComposer.attach')}
              aria-label={t('messageComposer.attach')}
            >
              <Plus className={`w-6 h-6 transition-transform duration-quick ${showAttachMenu ? 'rotate-45' : ''}`} strokeWidth={1.75} />
            </button>

            {showAttachMenu && (
              <AttachSheet
                onClose={() => {
                  setShowAttachMenu(false);
                }}
                gallery={{
                  pick: () => photoInputRef.current?.click(),
                  onRecent: chatId ? (m) => void useMessengerStore.getState().forwardMessage(m, chatId, { asOwn: true }) : undefined,
                }}
                tiles={[
                  { icon: Camera, label: t('messageComposer.tileCamera'), pick: () => cameraInputRef.current?.click(), testId: 'send-camera' },
                  { icon: FileIcon, label: t('messageComposer.tileFile'), pick: () => fileInputRef.current?.click(), testId: 'send-file' },
                  // «Місце» і «Де я» — одні двері (§7.5): у розмові вдвох це живе
                  // місце (WhereSheet уміє і разово, і наживо), інакше — точка зараз.
                  wherePeer
                    ? { icon: MapPin, label: t('messageComposer.tilePlace'), pick: () => onOpenWhere?.(), testId: 'composer-where' }
                    : { icon: MapPin, label: t('messageComposer.tilePlace'), pick: shareMyPlace, testId: 'send-place' },
                ]}
                moreTiles={[
                  ...(askChatType && isNodeChat({ type: askChatType })
                    ? []
                    : [{ icon: NightpostIcon, label: t('ask.composeItem'), pick: writeWithNightpost, testId: 'send-ask' }]),
                  ...(payPeer && wherePeer
                    ? [{ icon: Coins, label: t('messageComposer.tilePay'), pick: () => askSolana({ kind: 'pay', peer: wherePeer, cluster: payPeer.cluster }), testId: 'send-money' }]
                    : []),
                  ...(!wherePeer && chatId && livePeers.length > 0 && canPayHere()
                    ? [{ icon: Coins, label: t('messageComposer.tilePay'), pick: () => askSolana({ kind: 'group', chatId }), testId: 'send-group-money' }]
                    : []),
                  // Зустріч: заклад, час, люди — чернетка; «Запросити» сама скаже, чи дріт її везе.
                  { icon: BUILDER_ICON.meetup, label: t(BUILDER_LABEL.meetup), pick: () => setBuilder('meetup'), testId: 'send-meetup' },
                  { icon: Gift, label: t('gifts.title'), pick: () => setGiftOpen(true), testId: 'send-gift' },
                  // Кружечок — окрема двері, а не другий режим мікрофона (ГЛ-04).
                  { icon: Video, label: t('messageComposer.tileVideoNote'), pick: () => setIsRecordingVideo(true), testId: 'send-video-note' },
                  { icon: Music, label: t('voiceNote.music'), pick: () => musicInputRef.current?.click(), testId: 'send-music' },
                  ...(sendableBuilders().includes('poll')
                    ? [{ icon: BUILDER_ICON.poll, label: t(BUILDER_LABEL.poll), pick: () => setBuilder('poll'), testId: 'send-poll' }]
                    : []),
                  ...(!wherePeer && livePeers.length > 0
                    ? [{ icon: LocateFixed, label: liveSharing ? t('messageComposer.liveStop') : t('messageComposer.liveStart'), pick: () => void shareLiveLocation(), testId: 'send-live' }]
                    : []),
                  { icon: UserRound, label: t('msgTypes.person'), pick: () => setPersonOpen(true), testId: 'send-person' },
                  ...sendableBuilders()
                    .filter((kind) => kind !== 'poll')
                    .map((kind) => ({ icon: BUILDER_ICON[kind], label: t(BUILDER_LABEL[kind]), pick: () => setBuilder(kind), testId: `send-${kind}` })),
                ]}
              />
            )}
          </div>

          {/* Center Input Box / Voice Recording Bar */}
          {isTranscribingVoice ? (
            <div data-testid="voice-transcribing" className="min-h-11 flex-1 min-w-0 rounded-composer bg-surface border border-line px-4 flex items-center gap-2 text-callout text-ink-3">
              {t('messageComposer.transcribing')}
            </div>
          ) : voicePhase === 'holding' ? (
            <div
              data-testid="voice-holding"
              className="min-h-11 flex-1 min-w-0 rounded-composer bg-surface border border-line pl-4 pr-3 flex items-center gap-3 overflow-hidden f-fade-in"
            >
              <span aria-hidden className="w-2.5 h-2.5 shrink-0 rounded-full bg-danger" />
              <span className="shrink-0 text-subhead text-ink tnum">{fmtVoice(voice.elapsedMs)}</span>
              <span
                data-testid="voice-slide-cancel"
                className="flex-1 min-w-0 flex items-center justify-center gap-1 text-callout text-ink-3 whitespace-nowrap"
                style={{
                  transform: `translateX(${-Math.round(voiceProgressNow.cancel * 48)}px)`,
                  opacity: 1 - voiceProgressNow.cancel * 0.6,
                }}
              >
                <ChevronLeft className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden />
                {t('messageComposer.cancelVoice')}
              </span>
            </div>
          ) : voicePhase === 'locked' || voicePhase === 'paused' ? (
            <div
              data-testid="voice-locked"
              role="group"
              aria-label={voicePhase === 'paused' ? t('messageComposer.voicePaused') : t('messageComposer.voiceRecording')}
              className="min-h-11 flex-1 min-w-0 rounded-composer bg-surface border border-line pl-0.5 pr-1 flex items-center gap-2 overflow-hidden f-fade-in"
            >
              <button
                type="button"
                data-testid="voice-delete"
                onClick={() => dispatchVoice({ type: 'delete' })}
                className={voiceGhostBtn}
                aria-label={t('messageComposer.deleteRecording')}
                title={t('messageComposer.deleteRecording')}
              >
                <Trash2 className="w-5 h-5" strokeWidth={1.75} />
              </button>
              <span aria-hidden className={`w-2.5 h-2.5 shrink-0 rounded-full ${voicePhase === 'paused' ? 'bg-ink-3' : 'bg-danger'}`} />
              <span className="shrink-0 text-subhead text-ink tnum">{fmtVoice(voice.elapsedMs)}</span>
              {voice.mode === 'music' && (
                <span data-testid="voice-mode-music" className="shrink-0 text-footnote text-ink-2">
                  {t('voiceNote.music')}
                </span>
              )}
              <VoiceBars levels={voice.live} live testId="voice-live-wave" />
              {voice.canPause && (
                <button
                  type="button"
                  data-testid="voice-pause"
                  onClick={() => dispatchVoice({ type: voicePhase === 'paused' ? 'resume' : 'pause' })}
                  className={voiceGhostBtn}
                  aria-label={voicePhase === 'paused' ? t('messageComposer.resumeRecording') : t('messageComposer.pause')}
                  title={voicePhase === 'paused' ? t('messageComposer.resumeRecording') : t('messageComposer.pause')}
                >
                  {voicePhase === 'paused' ? (
                    <Mic className="w-5 h-5 text-danger" strokeWidth={1.75} />
                  ) : (
                    <Pause className="w-5 h-5" strokeWidth={1.75} />
                  )}
                </button>
              )}
            </div>
          ) : voicePhase === 'preview' ? (
            <div
              data-testid="voice-preview"
              role="group"
              aria-label={t('messageComposer.voicePreview')}
              className="min-h-11 flex-1 min-w-0 rounded-composer bg-surface border border-line pl-0.5 pr-3 flex items-center gap-2 overflow-hidden f-fade-in"
            >
              <button
                type="button"
                data-testid="voice-delete"
                onClick={() => dispatchVoice({ type: 'delete' })}
                className={voiceGhostBtn}
                aria-label={t('messageComposer.deleteRecording')}
                title={t('messageComposer.deleteRecording')}
              >
                <Trash2 className="w-5 h-5" strokeWidth={1.75} />
              </button>
              <button
                type="button"
                data-testid="voice-play"
                disabled={!preview}
                onClick={() => {
                  const a = previewAudioRef.current;
                  if (!a) return;
                  if (a.paused) void a.play().catch(() => setPreviewPlaying(false));
                  else a.pause();
                }}
                className={`${voiceGhostBtn} disabled:opacity-40`}
                aria-label={previewPlaying ? t('messageComposer.pause') : t('messageComposer.listen')}
                title={previewPlaying ? t('messageComposer.pause') : t('messageComposer.listen')}
              >
                {previewPlaying ? <Pause className="w-5 h-5" strokeWidth={1.75} /> : <Play className="w-5 h-5" strokeWidth={1.75} />}
              </button>
              <VoiceBars
                levels={preview ? compressEnvelope(preview.envelope, VOICE_PREVIEW_BARS) : []}
                played={preview && preview.durationMs > 0 ? previewPosMs / preview.durationMs : 0}
                testId="voice-preview-wave"
              />
              <span className="shrink-0 text-footnote text-ink-2 tnum">
                {fmtVoice(preview ? (previewPlaying || previewPosMs > 0 ? previewPosMs : preview.durationMs) : voice.elapsedMs)}
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={voiceText}
                data-testid="voice-text-switch"
                onClick={() => {
                  const next = !voiceText;
                  setVoiceText(next);
                  saveVoiceText(next);
                }}
                className={`shrink-0 min-h-11 px-3 rounded-full text-footnote border ${voiceText ? 'border-accent-line text-ink' : 'border-line text-ink-2'}`}
              >
                {t('voiceText.label')}
              </button>
              {preview && (
                <audio
                  ref={previewAudioRef}
                  src={preview.url}
                  preload="auto"
                  className="hidden"
                  onPlay={() => setPreviewPlaying(true)}
                  onPause={() => setPreviewPlaying(false)}
                  onTimeUpdate={(e) => setPreviewPosMs(Math.min(preview.durationMs, e.currentTarget.currentTime * 1000))}
                  onEnded={() => {
                    setPreviewPlaying(false);
                    setPreviewPosMs(0);
                  }}
                />
              )}
            </div>
          ) : (
            <>
            {showStickers && (
              <StickerSheet
                onClose={() => setShowStickers(false)}
                onPick={(d) => {
                  setShowStickers(false);
                  onSendMessage(composeSticker(d));
                  noteSent(d);
                }}
              />
            )}
            <ComposerField
              ref={textareaRef}
              wrapTestId="composer-field"
              value={text}
              onChange={handleTextChange}
              onKeyDown={handleKeyDown}
              onSelect={syncSelection}
              onKeyUp={syncSelection}
              onMouseUp={syncSelection}
              placeholder={
                selectedMessagesForQuote.length > 0
                  ? t('messageComposer.quoteComment')
                  : editingMessage
                  ? t('messageComposer.editPlaceholder')
                  : t('messageComposer.placeholder')
              }
              data-testid="composer-textarea"
              data-composer-input
              trailing={
                !editingMessage && selectedMessagesForQuote.length === 0 ? (
                  <button
                    type="button"
                    data-testid="composer-stickers"
                    onClick={() => {
                      soundFx.playTap();
                      setShowStickers(true);
                    }}
                    className="w-9 h-9 touch-floor-inline -mr-1.5 mb-[3px] rounded-full flex items-center justify-center shrink-0 text-ink-2 hover:bg-overlay active:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
                    title={t('stickers.title')}
                    aria-label={t('stickers.title')}
                  >
                    <Sticker className="w-6 h-6" strokeWidth={1.75} />
                  </button>
                ) : undefined
              }
            />
            </>
          )}


          {/* Send / Mic — усередині того ж листа, праворуч, завжди на одному місці:
              мікрофон стає бурштиновим колом «Надіслати» (форма, не місце). */}
          {voicePhase === 'locked' || voicePhase === 'paused' || voicePhase === 'preview' ? (
            <div className="relative shrink-0 flex items-center justify-center">
              {voicePhase !== 'preview' && (
                // Замок закрився й став «Стопом» (мікро №13): зупинити й прослухати.
                <button
                  type="button"
                  data-testid="voice-stop"
                  onClick={() => dispatchVoice({ type: 'stop' })}
                  className="absolute bottom-full right-0 mb-2 w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised shadow-e2 text-ink flex items-center justify-center f-send-in focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
                  aria-label={t('messageComposer.stopAndListen')}
                  title={t('messageComposer.stopAndListen')}
                >
                  <Square className="w-4 h-4" strokeWidth={1.75} />
                </button>
              )}
              <button
                type="button"
                data-testid="voice-send"
                onClick={() => dispatchVoice({ type: 'send' })}
                className="f-send-in w-11 h-11 min-h-0 min-w-0 rounded-full shrink-0 flex items-center justify-center bg-accent text-on-accent hover:bg-accent-hover active:bg-accent-press transition-colors duration-instant focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-line"
                aria-label={t('messageComposer.sendVoice')}
                title={t('messageComposer.sendVoice')}
              >
                <ArrowUp className="w-5 h-5" strokeWidth={2} />
              </button>
            </div>
          ) : canSend ? (
              <div className="relative shrink-0">
                <SendButton
                  onClick={onSendTap}
                  disabled={!canSend}
                  testId="composer-send"
                  label={editingMessage ? t('messageComposer.saveChanges') : t('messageComposer.send')}
                  title={editingMessage ? t('messageComposer.saveChanges') : t('messageComposer.sendMessage')}
                  hold={sendMenuAllowed ? { start: startSendHold, end: endSendHold, open: openSendMenu, expanded: sendMenu } : undefined}
                >
                  {editingMessage ? <Check className="w-5 h-5" strokeWidth={1.75} /> : undefined}
                </SendButton>
                {sendMenu && sendMenuAllowed && (
                  <>
                    <div className="fixed inset-0 z-40" aria-hidden onPointerDown={() => setSendMenu(false)} />
                    <div
                      role="menu"
                      aria-label={t('composer.sendMenu.label')}
                      data-testid="composer-send-menu"
                      onKeyDown={(e) => { if (e.key === 'Escape') setSendMenu(false); }}
                      className="absolute bottom-full right-0 mb-2 z-50 min-w-[200px] py-1 rounded-card bg-raised border border-line text-ink f-toast-in"
                    >
                      <button
                        type="button"
                        role="menuitem"
                        autoFocus
                        data-testid="composer-send-quiet"
                        onClick={sendQuiet}
                        className="w-full min-h-[48px] px-4 flex items-center gap-3 text-body text-left hover:bg-overlay focus-visible:bg-overlay focus-visible:outline-none"
                      >
                        <BellOff className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                        {t('composer.sendMenu.quiet')}
                      </button>
                      {onScheduleDraft && (
                        <button
                          type="button"
                          role="menuitem"
                          data-testid="composer-send-schedule"
                          onClick={scheduleDraft}
                          className="w-full min-h-[48px] px-4 flex items-center gap-3 text-body text-left hover:bg-overlay focus-visible:bg-overlay focus-visible:outline-none"
                        >
                          <CalendarClock className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                          {t('composer.sendMenu.schedule')}
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            ) : (
              <div className="relative shrink-0 flex items-center justify-center">
                {voiceHint && voicePhase !== 'holding' && (
                  <div
                    role="status"
                    data-testid="voice-hint"
                    className="absolute bottom-full right-0 mb-3 px-3 py-2 rounded-card bg-raised shadow-e2 text-ink z-40 flex items-center gap-2 pointer-events-none f-toast-in whitespace-nowrap select-none"
                  >
                    <Mic className="w-4 h-4 text-ink-2 shrink-0" strokeWidth={1.75} />
                    <div className="flex flex-col text-left">
                      <span className="text-subhead text-ink">{t('messageComposer.holdToRecord')}</span>
                      <span className="text-caption text-ink-3">{t('messageComposer.tapAgain')}</span>
                    </div>
                  </div>
                )}

                {voicePhase === 'holding' && (
                  // Замок над мікрофоном (мікро №13): капсула 40×96 іде за пальцем угору.
                  <div
                    data-testid="voice-lock"
                    aria-hidden
                    className="absolute bottom-full right-0.5 mb-3 w-10 h-24 rounded-full bg-raised shadow-e2 text-ink-2 flex flex-col items-center pt-3 gap-1 pointer-events-none f-toast-in transition-transform duration-instant ease-standard"
                    style={{ transform: `translateY(${-Math.round(voiceProgressNow.lock * 24)}px)` }}
                  >
                    {voiceProgressNow.lock >= 0.75 ? (
                      <Lock className="w-5 h-5 text-ink" strokeWidth={1.75} />
                    ) : (
                      <LockOpen className="w-5 h-5" strokeWidth={1.75} />
                    )}
                    <ChevronUp className="w-4 h-4" strokeWidth={1.75} />
                  </div>
                )}

                <button
                  type="button"
                  onPointerDown={handleMicPointerDown}
                  onPointerMove={handleMicPointerMove}
                  onPointerUp={handleMicPointerUp}
                  onPointerCancel={handleMicPointerCancel}
                  onClick={handleMicClick}
                  onContextMenu={(e) => e.preventDefault()}
                  style={{
                    touchAction: 'none',
                    transform:
                      voicePhase === 'holding'
                        ? `translate(${-Math.round(voiceProgressNow.cancel * 24)}px, ${-Math.round(voiceProgressNow.lock * 12)}px) scale(1.6)`
                        : undefined,
                  }}
                  className={`w-11 h-11 min-h-0 min-w-0 rounded-full shrink-0 flex items-center justify-center select-none relative transition-[transform,background-color,color] duration-quick ease-standard focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line ${
                    voicePhase === 'holding' ? 'bg-accent text-on-accent' : 'text-ink hover:bg-overlay active:bg-overlay'
                  }`}
                  title={t('messageComposer.holdTitle')}
                  data-testid="composer-media-button"
                  aria-label={t('messageComposer.recordVoice')}
                  aria-describedby="composer-voice-help"
                >
                  {voiceBin ? (
                    // Скасований запис (мікро №14): на місці мікрофона смітник на 600 мс.
                    <Trash2 data-testid="voice-bin" className="w-5 h-5 text-danger f-send-in" strokeWidth={1.75} />
                  ) : (
                    videoMode ? <Video data-testid="mic-mode-video" className="w-5 h-5" strokeWidth={1.75} /> : <Mic className="w-5 h-5" strokeWidth={1.75} />
                  )}
                </button>
                <span id="composer-voice-help" className="sr-only">
                  {t('messageComposer.voiceHelp')}
                </span>
              </div>
            )}
        </ComposerSheet>
        </div>

      {/* Video Note Recorder Modal */}
      {isRecordingVideo && (
        <VideoNoteRecorder
          onSend={(blob, durationS) => {
            void sendVideoNote(blob, durationS);
            setIsRecordingVideo(false);
          }}
          onCancel={() => setIsRecordingVideo(false)}
        />
      )}

      <CardBuilder kind={builder} onClose={() => setBuilder(null)} />
      <PersonPickSheet open={personOpen} onClose={() => setPersonOpen(false)} />
      <GiftPickSheet open={giftOpen} onClose={() => setGiftOpen(false)} />
      <FormSheet open={liveSpanOpen} onClose={() => setLiveSpanOpen(false)} title={t('messageComposer.liveStart')} testId="live-span">
        <div className="pb-2">
          {LIVE_SPANS.map(({ minutes, label }) => (
            <button
              key={minutes}
              type="button"
              data-testid={`live-span-${minutes}`}
              onClick={() => void startLiveFor(minutes)}
              className="w-full min-h-12 px-4 flex items-center text-left text-callout text-ink active:bg-raised"
            >
              {t(label)}
            </button>
          ))}
        </div>
      </FormSheet>
    </div>
  );
};
