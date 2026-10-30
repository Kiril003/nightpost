/**
 * Модель стрічки: з плоского списку листів — рядки, які справді малюються.
 *
 * Тут живе все, що раніше рахувалось ПОСЕРЕДИНІ `.map()` у тілі ChatArea на
 * кожен кадр: роздільник дня, черга відправника, контур бульбашки, щільність
 * вмісту, пачка медіа, згорнуті правки карток. Винесено з двох причин:
 *
 *   1. Рядок стрічки став окремим `React.memo`-компонентом. Мемо тримається
 *      лише тоді, коли пропси рядка не народжуються наново щокадру, тож
 *      `reconcileRows` повертає ТОЙ САМИЙ обʼєкт рядка, якщо в ньому нічого
 *      не змінилося. Вхідний лист у розмові на 600 листів перемальовує один
 *      рядок (і сусіда, чий хвіст черги зсунувся), а не всі.
 *   2. Віртуалізатору потрібен список рядків наперед — щоб знати, скільки їх,
 *      і оцінити висоту ще не намальованих.
 *
 * Чиста функція, без React — перевіряється звичайним юніт-тестом.
 */
import type { Message } from '../../../types/messenger';
import { parseSticker } from '../../../stickers/stickerWire';
import { stickerForEmoji } from '../../../stickers/pack';
import type { FoldRun } from '../../../services/signalFold';
import { LIVE_CARD_KINDS, THEATRE_CARD_KINDS, foldCards, isMergedCard } from '../cards/cardModel';
import { hasEventView, readMessageCard } from '../../../services/peerWire';
import { isStored } from '../../../services/listSignals';
import { isDoorCode } from '../../../services/codeWindow';
import { isOrderMessage, isStatusMessage } from '../../../services/business/orders';
import { dayMonthLabel } from '../../../utils/clockFormat';
import { silenceGap, SILENCE_EXTRA_PX, type SilenceGap } from '../../../services/silenceGap';
import { currentLocale, tNow } from '../../chat/tNow';
import { isWorkCard } from '../cards/workModel';
export type MessageDensity =
  | 'emoji-single'
  | 'emoji-multi'
  | 'short'
  | 'medium'
  | 'long'
  | 'card'
  | 'media'
  | 'standard';

/** Один намальований рядок стрічки — лист разом із тим, що про нього знає сусідство. */
export interface FeedRow {
  key: string;
  msg: Message;
  isSelf: boolean;
  /** День цього листа ('' — дати немає). */
  dateLabel: string;
  /** Над рядком стоїть роздільник дня. */
  showDateDivider: boolean;
  isFirstInGroup: boolean;
  isLastInGroup: boolean;
  /** Перша репліка співрозмовника в розмові (для одноразового підпису помічника). */
  isFirstPeerMessage?: boolean;
  /** Новий мовець після чужої черги — більший відступ згори. */
  isNewSenderTurn: boolean;
  contourClass: string;
  density: MessageDensity;
  densityClass: string;
  /** Картка (жива або показова на телефоні) — власна поверхня, без бульбашки. */
  cardView: boolean;
  /** Кружок, одиночний смайл і картка — без тла. */
  bareBubble: boolean;
  /** Голе фото: без тла й полів. */
  photoOnly: boolean;
  /** Хвостик — лише в останньої бульбашки черги, що має тло. */
  tail: boolean;
  /** Своя картка (маршрут, місце, файл) — поверхнею, а не бурштином. */
  cardBubble: boolean;
  pending: boolean;
  /** Сховок узяв лист: одна галочка «надіслано» замість годинника. */
  stored: boolean;
  failed: boolean;
  threadCount: number;
  /** Згорнута картка: найсвіжіший вміст на місці першої версії. */
  cardMsg?: Message;
  /** Пачка медіа — усі її елементи, впорядковані; лише в першого елемента пачки. */
  batchItems?: Message[];
  /** Згорнута серія низькосигнальних реплік у жвавій групі. */
  fold?: { count: number; ids: string[] };
  /** Довга тиша перед листом: підпис і сходинка відступу. */
  silence?: SilenceGap;
}

/** Підпис дня за `sentAt` — кешовано: той самий рядок дня не рахується двічі за добу. */
const dayLabels = new Map<string, string>();
let dayLabelsFor = '';

/** Пауза між листами, після якої черга рветься (дизайн-система §7.4). */
export const RUN_GAP_MS = 10 * 60_000;
const apart = (a: Message, b: Message): boolean => {
  if (!a.sentAt || !b.sentAt) return false;
  const t = (iso: string) => Date.parse(iso.endsWith('Z') || /[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`);
  const gap = t(b.sentAt) - t(a.sentAt);
  return Number.isFinite(gap) && gap > RUN_GAP_MS;
};

/**
 * Роздільник дня рахується з дати відправки; немає дати — немає роздільника.
 * `today` — параметр лише для тестів.
 */
export function dateLabelOf(msg: Message, today: Date = new Date()): string {
  if (!msg.sentAt) return '';
  const loc = currentLocale();
  const todayKey = `${loc}-${today.getFullYear()}-${today.getMonth()}-${today.getDate()}`;
  if (todayKey !== dayLabelsFor) {
    dayLabels.clear();
    dayLabelsFor = todayKey;
  }
  const cached = dayLabels.get(msg.sentAt);
  if (cached !== undefined) return cached;
  const raw = msg.sentAt.endsWith('Z') ? msg.sentAt : `${msg.sentAt}Z`;
  const d = new Date(raw);
  let label = '';
  if (!Number.isNaN(d.getTime())) {
    const sameDay = (a: Date, b: Date) =>
      a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    const yesterday = new Date(today);
    yesterday.setDate(today.getDate() - 1);
    if (sameDay(d, today)) label = tNow('feedModel.today');
    else if (sameDay(d, yesterday)) label = tNow('feedModel.yesterday');
    else label = dayMonthLabel(d, d.getFullYear() !== today.getFullYear());
  }
  dayLabels.set(msg.sentAt, label);
  return label;
}

const EMOJI_ONLY = /^(?:\p{Extended_Pictographic}|\p{Emoji_Presentation}|\p{Emoji_Modifier_Base}|\uFE0F|\u200D|\s)+$/u;

const densityCache = new WeakMap<Message, MessageDensity>();

/** Щільність вмісту — від самого листа; кешовано за обʼєктом листа. */
export function densityOf(msg: Message): MessageDensity {
  let d = densityCache.get(msg);
  if (d === undefined) {
    d = computeDensity(msg);
    densityCache.set(msg, d);
  }
  return d;
}

function computeDensity(msg: Message): MessageDensity {
  if (msg.type === 'sticker' || (msg.type === 'text' && !msg.media && parseSticker(msg.text))) return 'emoji-single';
  if (
    msg.type === 'table' ||
    msg.type === 'chart' ||
    msg.type === 'task-list' ||
    msg.type === 'poll' ||
    msg.type === 'split-bill' ||
    msg.type === 'multi-quote'
  ) {
    return 'card';
  }
  if (msg.type === 'image' || msg.type === 'file' || msg.type === 'voice' || msg.type === 'location' || msg.type === 'video-note') {
    return 'media';
  }
  if (msg.text && !msg.replyTo && !msg.forwardFrom) {
    const trimmed = (msg.text || '').trim();
    if (EMOJI_ONLY.test(trimmed)) {
      const emojiMatches = trimmed.match(/\p{Extended_Pictographic}/gu);
      const emojiCount = emojiMatches ? emojiMatches.length : 0;
      if (emojiCount === 1) return 'emoji-single';
      if (emojiCount >= 2 && emojiCount <= 5) return 'emoji-multi';
    }
    if (trimmed.length <= 22 && !trimmed.includes('\n')) return 'short';
    if (trimmed.length <= 110 && !trimmed.includes('\n\n')) return 'medium';
    return 'long';
  }
  return 'standard';
}

const DENSITY_CLASS: Record<MessageDensity, string> = {
  'emoji-single': 'msg-density-emoji-single',
  'emoji-multi': 'msg-density-emoji-multi',
  short: 'msg-density-short',
  medium: 'msg-density-medium',
  long: 'msg-density-long',
  card: 'msg-density-card',
  media: 'msg-density-media',
  standard: 'msg-density-medium',
};

function contourOf(isSelf: boolean, first: boolean, last: boolean): string {
  const side = isSelf ? 'self' : 'other';
  if (first && last) return `bubble-contour-${side}-single`;
  if (first) return `bubble-contour-${side}-first`;
  if (last) return `bubble-contour-${side}-last`;
  return `bubble-contour-${side}-middle`;
}

export const isSelfMessage = (msg: Message, currentUserId: string): boolean =>
  Boolean(msg.senderId && currentUserId ? msg.senderId === currentUserId : msg.isSelf);

/**
 * Рядки стрічки з повного списку листів. Надгробки, пачки й згорнуті картки
 * обробляються тут: другий і далі елементи пачки та пізніші версії картки
 * рядка не мають узагалі.
 */
function silenceBefore(prev: Message | undefined, cur: Message): SilenceGap | undefined {
  if (!prev?.sentAt || !cur.sentAt) return undefined;
  return silenceGap(Date.parse(cur.sentAt) - Date.parse(prev.sentAt)) ?? undefined;
}

export function buildFeedRows(
  messages: Message[],
  currentUserId: string,
  today: Date = new Date(),
  folds?: FoldRun[],
): FeedRow[] {
  const cardFold = foldCards(messages);
  const labels = messages.map((m) => (m ? dateLabelOf(m, today) : ''));
  const foldStartById = new Map<string, FoldRun>();
  const foldedSkipIds = new Set<string>();
  if (folds) {
    for (const run of folds) {
      if (run.ids.length === 0) continue;
      foldStartById.set(run.ids[0], run);
      for (let i = 1; i < run.ids.length; i++) foldedSkipIds.add(run.ids[i]);
    }
  }

  const threadCounts = new Map<string, number>();
  const batches = new Map<string, Message[]>();
  for (const m of messages) {
    if (!m) continue;
    const rootId = m.replyTo?.id;
    if (rootId) threadCounts.set(rootId, (threadCounts.get(rootId) || 0) + 1);
    const b = m.mediaBatch;
    if (b && b.total > 1) {
      const list = batches.get(b.id);
      if (list) list.push(m);
      else batches.set(b.id, [m]);
    }
  }

  const rows: FeedRow[] = [];
  let lastDateLabel = '';
  let seenPeer = false;
  for (let index = 0; index < messages.length; index++) {
    const msg = messages[index];
    if (!msg) continue;
    if (msg.mediaBatch && msg.mediaBatch.total > 1 && msg.mediaBatch.index > 0) continue;
    if (cardFold.later.has(msg.id)) continue;
    if (foldedSkipIds.has(msg.id)) {
      if (labels[index]) lastDateLabel = labels[index];
      if (!isSelfMessage(msg, currentUserId) && !msg.isDeleted) seenPeer = true;
      continue;
    }

    const isSelf = isSelfMessage(msg, currentUserId);
    const isFirstPeerMessage = !isSelf && !msg.isDeleted && !seenPeer;
    if (!isSelf && !msg.isDeleted) seenPeer = true;
    const dateLabel = labels[index];
    const showDateDivider = dateLabel !== '' && dateLabel !== lastDateLabel;
    if (dateLabel) lastDateLabel = dateLabel;
    const silence = silenceBefore(messages[index - 1], msg);

    // Згортка рве чергу: сусід, схований у ній, не склеює рядок поруч.
    const prevRaw = index > 0 ? messages[index - 1] : undefined;
    const nextRaw = index < messages.length - 1 ? messages[index + 1] : undefined;
    const prevMsg = prevRaw && (foldedSkipIds.has(prevRaw.id) || foldStartById.has(prevRaw.id)) ? undefined : prevRaw;
    const nextMsg = nextRaw && foldStartById.has(nextRaw.id) ? undefined : nextRaw;
    const nextDay = nextMsg ? labels[index + 1] : '';
    // Черга — той самий автор упродовж десяти хвилин (§7.4); довша пауза
    // починає нову чергу навіть у того самого автора.
    const isFirstInGroup =
      !prevMsg || showDateDivider || prevMsg.senderId !== msg.senderId || prevMsg.senderName !== msg.senderName || apart(prevMsg, msg);
    const isLastInGroup =
      !nextMsg ||
      (nextDay !== '' && nextDay !== dateLabel) ||
      nextMsg.senderId !== msg.senderId ||
      nextMsg.senderName !== msg.senderName ||
      apart(msg, nextMsg);

    const foldRun = foldStartById.get(msg.id);
    if (foldRun) {
      rows.push({
        key: `fold:${foldRun.ids[0]}`,
        msg,
        fold: { count: foldRun.ids.length, ids: foldRun.ids },
        silence,
        isSelf,
        dateLabel,
        showDateDivider,
        isFirstInGroup,
        isLastInGroup,
        isFirstPeerMessage,
        isNewSenderTurn: isFirstInGroup && index > 0,
        contourClass: contourOf(isSelf, isFirstInGroup, isLastInGroup),
        density: 'short',
        densityClass: DENSITY_CLASS.short,
        cardView: false,
        bareBubble: false,
        photoOnly: false,
        tail: false,
        cardBubble: false,
        pending: false,
        stored: false,
        failed: false,
        threadCount: 0,
      });
      continue;
    }

    const density = densityOf(msg);
    // Переказ і печатка Solana — теж картка: речення `body` лишається запасом
    // для пошуку й копіювання, а на екрані говорить SolanaCard (MessageEmbeds).
    const cardView =
      LIVE_CARD_KINDS.has(msg.type) ||
      (__PHONE_BUILD__ && THEATRE_CARD_KINDS.has(msg.type)) ||
      isWorkCard(msg) ||
      Boolean(msg.payment || msg.seal) ||
      isDoorCode(msg) ||
      hasEventView(msg) ||
      isMergedCard(msg) ||
      isOrderMessage(msg) ||
      isStatusMessage(msg);
    const batchItems =
      msg.mediaBatch && msg.mediaBatch.total > 1 && msg.mediaBatch.index === 0
        ? (batches.get(msg.mediaBatch.id) ?? [msg]).slice().sort((a, b) => (a.mediaBatch?.index ?? 0) - (b.mediaBatch?.index ?? 0))
        : undefined;

    const bareBubble = density === 'emoji-single' || msg.type === 'video-note' || cardView;
    const photoOnly = msg.type === 'image' && !!msg.media && !msg.text && !msg.replyTo && !msg.forwardFrom && !msg.mediaBatch;
    rows.push({
      key: msg.id,
      msg,
      silence,
      isSelf,
      dateLabel,
      showDateDivider,
      isFirstInGroup,
      isLastInGroup,
      isFirstPeerMessage,
      isNewSenderTurn: isFirstInGroup && index > 0,
      contourClass: contourOf(isSelf, isFirstInGroup, isLastInGroup),
      density,
      densityClass: DENSITY_CLASS[density],
      cardView,
      bareBubble,
      photoOnly,
      tail: isLastInGroup && !bareBubble && !photoOnly,
      cardBubble:
        isSelf &&
        (!!readMessageCard(msg) ||
          msg.type === 'location' ||
          msg.type === 'geo:point' ||
          msg.type === 'geo:live' ||
          msg.type === 'file'),
      pending: isSelf && (msg.status === 'sending' || (msg.status === 'queued' && !isStored(msg))),
      stored: isSelf && isStored(msg),
      failed: isSelf && msg.status === 'failed',
      threadCount: msg.isDeleted ? 0 : threadCounts.get(msg.id) || 0,
      cardMsg: cardFold.shown.get(msg.id),
      batchItems,
    });
  }
  return rows;
}

const sameArray = (a?: Message[], b?: Message[]) =>
  a === b || (!!a && !!b && a.length === b.length && a.every((m, i) => m === b[i]));

const shallowSame = (a?: Message, b?: Message): boolean => {
  if (a === b) return true;
  if (!a || !b) return false;
  const ka = Object.keys(a) as Array<keyof Message>;
  if (ka.length !== Object.keys(b).length) return false;
  return ka.every((k) => a[k] === b[k]);
};

function sameRow(a: FeedRow, b: FeedRow): boolean {
  return (
    a.msg === b.msg &&
    a.fold?.count === b.fold?.count &&
    a.silence?.step === b.silence?.step &&
    a.silence?.n === b.silence?.n &&
    a.silence?.unit === b.silence?.unit &&
    a.isSelf === b.isSelf &&
    a.dateLabel === b.dateLabel &&
    a.showDateDivider === b.showDateDivider &&
    a.isFirstInGroup === b.isFirstInGroup &&
    a.isLastInGroup === b.isLastInGroup &&
    a.isFirstPeerMessage === b.isFirstPeerMessage &&
    a.isNewSenderTurn === b.isNewSenderTurn &&
    a.cardView === b.cardView &&
    a.threadCount === b.threadCount &&
    shallowSame(a.cardMsg, b.cardMsg) &&
    sameArray(a.batchItems, b.batchItems)
  );
}

/**
 * Повертає попередній обʼєкт рядка там, де нічого не змінилося, — на цьому
 * тримається `React.memo` рядка. Якщо не змінилося нічого взагалі, вертає
 * сам попередній масив.
 */
export function reconcileRows(prev: FeedRow[] | null, next: FeedRow[]): FeedRow[] {
  if (!prev || prev.length === 0) return next;
  const byKey = new Map<string, FeedRow>();
  for (const r of prev) byKey.set(r.key, r);
  let changed = prev.length !== next.length;
  const out = next.map((r, i) => {
    const old = byKey.get(r.key);
    if (old && sameRow(old, r)) {
      if (prev[i] !== old) changed = true;
      return old;
    }
    changed = true;
    return r;
  });
  return changed ? out : prev;
}

/**
 * Оцінка висоти ще не виміряного рядка. Точність не обовʼязкова — віртуалізатор
 * переміряє рядок, щойно той зʼявиться, — але що ближче оцінка, то менше
 * поправок прокрутки при швидкому гортанні вгору.
 */
const estimateCache = new WeakMap<FeedRow, { w: number; h: number }>();

export function estimateRowHeight(row: FeedRow, columnWidth: number): number {
  const hit = estimateCache.get(row);
  if (hit && hit.w === columnWidth) return hit.h;
  const h = computeEstimate(row, columnWidth);
  estimateCache.set(row, { w: columnWidth, h });
  return h;
}

function computeEstimate(row: FeedRow, columnWidth: number): number {
  const gapPx = row.silence && !row.showDateDivider ? SILENCE_EXTRA_PX[row.silence.step] + 20 : 0;
  if (row.fold) return 44 + gapPx;
  const msg = row.msg;
  let h = (row.isNewSenderTurn || row.showDateDivider ? 12 : 2) + gapPx;
  if (row.showDateDivider) h += 44;
  if (msg.isDeleted) return h + 22;
  if (row.batchItems) h += 260;
  else if (msg.type === 'image') h += 200;
  else if (msg.type === 'video-note') h += 240;
  else if (row.cardView || row.density === 'card') h += 180;
  else if (msg.type === 'voice') h += 64;
  else if (msg.type === 'file' || msg.type === 'location') h += 84;
  else if (row.density === 'emoji-single') h += msg.text?.includes('⟦st1:') ? 172 : msg.text && stickerForEmoji(msg.text.trim()) ? 124 : 70;
  else {
    const charsPerLine = Math.max(16, Math.floor((Math.min(columnWidth, 760) * 0.72) / 8.5));
    const lines = (msg.text || '').split('\n').reduce((n, l) => n + Math.max(1, Math.ceil(l.length / charsPerLine)), 0);
    h += 20 + Math.min(lines, 20) * 21;
  }
  if (msg.replyTo) h += 44;
  if (msg.forwardFrom) h += 22;
  if (row.isLastInGroup || row.pending || row.failed) h += 18;
  if (msg.reactions && msg.reactions.length) h += 28;
  if (!row.isSelf && row.isFirstInGroup) h += 20;
  return h;
}
