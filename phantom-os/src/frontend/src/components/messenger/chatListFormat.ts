import { plainText } from '../../services/plainText';
/**
 * Слова рядка списку розмов: час, назва, превʼю. Спільне для телефонного
 * списку й настільної колонки, щоб «Вчора» й «Ви:» не розходились між ними.
 */
import type { Chat, ChatLastCard, Message } from '../../types/messenger';
import { currentDateTag, tNow } from '../chat/tNow';
import type { MessageKey } from '../../i18n';
import { chatKeep } from '../../services/chatKeep';
import { expensesOf, money } from './cards/cardModel';

/** Мова дат: за вибором людини, не за мовою системи. */
const dateTag = currentDateTag;

// Вузол віддає час без зони — тлумачимо його як UTC, як це вже робить messengerApi.
export const parseNodeTime = (iso: string): Date | null => {
  const d = new Date(/[Z+]|-\d\d:\d\d$/.test(iso) ? iso : `${iso}Z`);
  return Number.isNaN(d.getTime()) ? null : d;
};

export const chatTimeLabel = (iso?: string): string => {
  if (!iso) return '';
  const d = parseNodeTime(iso);
  if (!d) return '';

  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((startOfDay(new Date()) - startOfDay(d)) / 86_400_000);

  if (days <= 0) return d.toLocaleTimeString(dateTag(), { hour: '2-digit', minute: '2-digit' });
  if (days === 1) return tNow('chatList.yesterday');
  if (days < 365) return d.toLocaleDateString(dateTag(), { day: '2-digit', month: '2-digit' });
  return d.toLocaleDateString(dateTag(), { day: '2-digit', month: '2-digit', year: '2-digit' });
};

// Вузол міг не встигнути дати розмові людську назву — тоді в title лежить хеш,
// і людина бачить не його, а «Нова розмова».
const hashTitle = /^[0-9a-f]{12,}$/;

/** Емодзі в назві, яку дав не співрозмовник, а сам вузол («PHANTOM Symbiote ⚡»). */
const decor = /[\p{Extended_Pictographic}\u{FE0F}\u{200D}]/gu;

/**
 * Хвіст відбитка, який міст дописує до однакових імен («Ноутбук · 9b10·41af»,
 * PeerRoster.shortId). Людині він нічого не каже: двох «Ноутбуків» розрізняє
 * тон аватара (chatToneKey), а не код.
 */
const idSuffix = /\s*·\s*[0-9a-f]{4}·[0-9a-f]{4}$/i;

/** Дата, приклеєна до імені («Ноутбук · 6 вер., 02:48», «Новий контакт · 27 вер., 16:04»): час живе праворуч, не в імені. */
const dateSuffix = /\s*·\s*\d{1,2}\s+\p{L}+\.?(,\s*\d{1,2}:\d{2})?$/u;

/**
 * Людина, яка не назвалась і яку власник не назвав. Не «Новий контакт»:
 * той через тиждень уже не новий, а дата поруч читалась частиною імені
 * (власник на A52, 02.10). Дата додавання живе в картці людини.
 */
export const UNNAMED = 'Без імені'; // i18n-data: placeholder the PC node stores and old rows carry

/** Те саме слово мовою інтерфейсу. Порівнюй через `isUnnamed`, не через `===`. */
export const unnamedLabel = (): string => tNow('chatList.unnamed');
export const isUnnamed = (s: string | null | undefined): boolean => s === UNNAMED || s === unnamedLabel();

/** Колишня заглушка, яку веб сам записував у вузол замість порожнього імені. */
const LEGACY_PLACEHOLDER = /^новий контакт$/i;

// Імена, які складав код, а не людина: «Вузол 4d839df7», «Вузол 10.0.0.5:8000»
// (msgr_bridge_node_name) і голий «d1d2·7b5f».
const machineName = /^((вузол|node)\s+([0-9a-f]{6,}…?|[\w.-]+:\d+)|[0-9a-f]{4}·[0-9a-f]{4})$/i;

// Типова назва ПК, яку дає рідний бік при спаруванні (paired_pc_name) мовою телефона того дня.
const defaultPcName = /^(my computer|мій комп[ʼ'’]?ютер|můj počítač)$/i;

/** Типова назва ПК — мовою інтерфейсу; назву, яку дала людина, не чіпаємо. */
export const localDeviceName = (name: string): string =>
  defaultPcName.test(name.trim()) ? tNow('polish1.myComputer') : name;

// Імʼя без жодної літери чи цифри («.», «—», лише емодзі) — не імʼя.
const hasNameChar = /[\p{L}\p{N}]/u;

export const displayTitle = (title: string): string => {
  const raw = localDeviceName((title || '').replace(decor, '').replace(/\s{2,}/g, ' ').trim().replace(dateSuffix, ''));
  const bare = raw.replace(idSuffix, '');
  if (machineName.test(bare) || LEGACY_PLACEHOLDER.test(bare) || bare === UNNAMED || !hasNameChar.test(bare)) return unnamedLabel();
  if (raw.includes('•')) {
    const first = raw.split('•')[0].trim();
    if (hasNameChar.test(first)) return first;
  }
  if (hashTitle.test(raw)) return tNow('chatList.newChat');
  return bare;
};

// groupTitle() bakes "Venue · 19:00" into the name; with a plan the time comes from plan.at, so a moved plan never shows the old one.
const planTimeSuffix = /\s*·\s*\d{1,2}:\d{2}$/;
export const planTitle = (chat: { title: string; plan?: unknown }): string => {
  const name = displayTitle(chat.title);
  return chat.plan ? name.replace(planTimeSuffix, '') || name : name;
};

/**
 * Службове імʼя автора з мосту — людською мовою.
 * `me` — ідентифікатор власного вузла, а не підпис.
 */
/** Знак «автор — я» у `lastAuthor` (так пише й міст). Людині не показується: його перекладає `selfWord`. */
export const SELF_AUTHOR = 'Я'; // i18n-data: wire marker for «author is me», translated by selfWord

export const selfWord = (author?: string | null): string =>
  author && ['me', 'я'].includes(author.trim().toLowerCase()) // i18n-data: recognises the wire marker
    ? tNow('chatList.you') : (author || '');

export const isSavedNotes = (chat: Pick<Chat, 'id' | 'type' | 'circle'>): boolean =>
  chat.id === 'chat_saved_notes' || chat.type === 'saved';

/** Розмова з самим вузлом (PHANTOM) — не людина, тону людини не має. */
export const isNodeChat = (chat: Pick<Chat, 'type'>): boolean => chat.type === 'phantom' || chat.type === 'ai';

/** Ключ тону людини: вузол співрозмовника, інакше id розмови (група); без імені — нейтральний. */
export const chatToneKey = (
  chat: Pick<Chat, 'id' | 'peerNodeId'> & Partial<Pick<Chat, 'title' | 'type' | 'circle'>>,
): string | null => {
  if (chat.type && (isSavedNotes(chat as Pick<Chat, 'id' | 'type' | 'circle'>) || isNodeChat(chat as Pick<Chat, 'type'>))) {
    return null;
  }
  if (typeof chat.title === 'string') {
    const shown = displayTitle(chat.title).trim();
    if (!shown || isUnnamed(shown) || isUnnamed(chat.title.trim())) return null;
  }
  return chat.peerNodeId || chat.id || null;
};

/** 0:07, 1:05, 12:00 — тривалість так, як її пише годинник. */
export const clockDuration = (seconds: number): string => {
  const s = Math.max(0, Math.round(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/** Слово для нетекстового листа. Імʼя файлу людині нічого не каже. */
export const KIND_WORD: Record<string, string> = (() => {
  const keyOf: Record<string, MessageKey> = {
    'video-note': 'chatList.kind.videoNote',
    video_note: 'chatList.kind.videoNote',
    voice: 'chatList.kind.voice',
    image: 'chatList.kind.image',
    video: 'chatList.kind.video',
    file: 'chatList.kind.file',
    table: 'chatList.kind.table',
    chart: 'chatList.kind.chart',
    'task-list': 'messageCard.checklist',
    location: 'chatList.kind.place',
    'geo:point': 'chatList.kind.place',
    'geo:live': 'chatList.kind.placeLive',
    poll: 'chatList.kind.poll',
    event: 'chatList.kind.event',
    code: 'chatList.kind.code',
    'split-bill': 'chatList.kind.bill',
    'widget:meetup': 'chatList.kind.meetup',
    'widget:bill': 'chatList.kind.bill',
    'widget:family-expense': 'phonefix.kind.expenses',
    'widget:family-checklist': 'messageCard.checklist',
    'widget:voting': 'chatList.kind.poll',
    'widget:money': 'chatList.kind.money',
    'widget:day': 'chatList.kind.day',
    'widget:glance': 'chatList.kind.glance',
    'widget:person': 'msgTypes.person',
    sticker: 'chatList.kind.sticker',
    'widget:gift': 'gifts.title',
  };
  // Слово береться в мить читання, тож перемикання мови діє без перезапуску.
  const out: Record<string, string> = {};
  for (const [kind, key] of Object.entries(keyOf)) {
    Object.defineProperty(out, kind, { enumerable: true, get: () => tNow(key) });
  }
  return out;
})();

/** Тривалість із листа або з тіла, яке міст поклав у превʼю. */
const durationOf = (msg: Partial<Message> | null | undefined, snippet?: string | null): number | null => {
  const d = msg?.voiceData?.duration ?? msg?.videoNoteData?.duration;
  if (typeof d === 'number' && Number.isFinite(d) && d > 0) return d;
  if (snippet && snippet.trim().startsWith('{')) {
    try {
      const parsed = JSON.parse(snippet) as { duration?: unknown };
      const n = Number(parsed.duration);
      if (Number.isFinite(n) && n > 0) return n;
    } catch {
      /* не опис — не число */
    }
  }
  return null;
};

/** «Відеокружок · 0:01», «Голосове · 0:12», «Фото». null — не нетекстовий рід. */
export const kindLine = (
  kind: string | undefined | null,
  msg?: Partial<Message> | null,
  snippet?: string | null,
): string | null => {
  if (!kind || kind === 'text') return null;
  const word = KIND_WORD[kind];
  if (!word) return null;
  if (kind === 'voice' || kind === 'video-note' || kind === 'video_note') {
    const d = durationOf(msg, snippet);
    return d ? `${word} · ${clockDuration(d)}` : word;
  }
  if (kind === 'sticker') {
    const emoji = (snippet ?? '').trim().match(STICKER_EMOJI)?.[0];
    return emoji ? `${emoji} ${word}` : word;
  }
  return word;
};

const STICKER_EMOJI = /^\p{Extended_Pictographic}[\p{Extended_Pictographic}\p{Emoji_Modifier}\u200d\ufe0f]*/u;

const stripInlineMarkers = (s: string): string =>
  s
    .replace(/\*\*(?=\S)(.+?)(?<=\S)\*\*/g, '$1')
    .replace(/(?<![\p{L}\p{N}])__(?=\S)(.+?)(?<=\S)__(?![\p{L}\p{N}])/gu, '$1')
    .replace(/~~(?=\S)(.+?)(?<=\S)~~/g, '$1')
    .replace(/(?<!~)~(?=\S)([^~\n]+?)(?<=\S)~(?!~)/g, '$1')
    .replace(/(?<!\*)\*(?=\S)([^*\n]+?)(?<=\S)\*(?!\*)/g, '$1')
    .replace(/(?<![\p{L}\p{N}_])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![\p{L}\p{N}_])/gu, '$1');

/** Слова листа без розмітки: превʼю — рядок, а не відмальований текст. Спойлер не розкриваємо. */
export const plainSnippet = (text: string): string => {
  let out = plainText(text)
    .replace(/\|\|[\s\S]+?\|\|/g, '•••')
    .replace(/```(?:[a-zA-Z0-9_+-]+[\t ]*\r?\n)?([\s\S]*?)```/g, '$1')
    .replace(/`([^`\n]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)\s]+\)/g, '$1')
    .replace(/^\s*(?:>\s*|#{1,6}\s+)/gm, '');
  for (let i = 0; i < 3; i++) {
    const next = stripInlineMarkers(out);
    if (next === out) break;
    out = next;
  }
  return out.replace(/\s+/g, ' ').trim();
};

/** «Pho dinner · 840 ₴» — картка з назвою й сумою з рядка вузла чи з листа в памʼяті. */
export const cardSnippet = (kind: string | undefined | null, card?: ChatLastCard | null, msg?: Message | null): string | null => {
  if (!kind?.startsWith('widget:')) return null;
  const fromMsg = msg ? expensesOf(msg) : null;
  const title = (card?.kind === 'card' ? card.title : fromMsg?.title)?.trim();
  const total = card?.kind === 'card' ? card.total : fromMsg?.items.reduce((s, i) => s + i.amount, 0);
  const currency = (card?.kind === 'card' ? card.currency : fromMsg?.currency) || '';
  const summed = kind === 'widget:family-expense' || kind === 'widget:money';
  const sum = summed && typeof total === 'number' ? `${money(total)} ${currency}`.trim() : '';
  if (kind === 'widget:money') return [KIND_WORD[kind], sum].filter(Boolean).join(' · ');
  const word = kind ? KIND_WORD[kind] : undefined;
  const head = title || (sum ? '' : word);
  return [head, sum].filter(Boolean).join(' · ') || null;
};

/**
 * Рід вкладення з сирого опису, який міст інколи кладе в превʼю як є:
 * `{"name":"voice_1790….webm","size":…}` — власник бачив це JSON-ом у рядку
 * PHANTOM. null — превʼю не схоже на опис вкладення.
 */
export const attachmentKindFromSnippet = (snippet: string | null | undefined): string | null => {
  const s = snippet?.trim();
  // «точка phantom://map?lat=49.83…» — місце, яке міст переказав текстом.
  if (s && /(^|\s)(phantom:\/\/map\?|geo:-?\d)/i.test(s)) return 'geo:point';
  if (!s || !s.startsWith('{')) return null;
  let d: { name?: unknown; mime?: unknown; type?: unknown };
  try {
    d = JSON.parse(s);
  } catch {
    return null;
  }
  const name = typeof d.name === 'string' ? d.name.toLowerCase() : '';
  const mime = String(d.mime ?? d.type ?? '').toLowerCase();
  if (!name && !mime) return null;
  if (name.startsWith('video_note') || name.startsWith('video-note')) return 'video-note';
  if (name.startsWith('voice') || mime.startsWith('audio/')) return 'voice';
  if (mime.startsWith('image/') || /\.(jpe?g|png|gif|webp|heic)$/.test(name)) return 'image';
  if (mime.startsWith('video/') || /\.(mp4|webm|mov)$/.test(name)) return 'video';
  return 'file';
};

/** Імʼя файлу замість слова — саме те, що бачив власник: «video_note_1790….webm». */
export const looksLikeFileName = (s: string): boolean =>
  /^[\w.\-() ]+\.(webm|mp4|m4a|ogg|opus|mp3|jpe?g|png|gif|webp|heic|pdf|zip|bin)$/i.test(s.trim());

/**
 * Чи була в розмові хоч одна репліка. Контакт без жодного листа — людина в
 * записнику, а не розмова: у «Розмовах» він не стоїть (рішення 25.09 — список
 * «Ноутбук · dfff…» без жодного слова й був тим, що «опустіло»). Лишається він
 * у «Нова → Людина»; з даних не зникає нічого.
 */
export const hasLetter = (
  chat: Pick<Chat, 'messages' | 'lastKind' | 'lastSnippet'>,
): boolean => Boolean(chat.messages?.length || chat.lastKind || chat.lastSnippet);

/** Стоїть у «Розмовах»: було листування, або закріплена, або є що дописати. */
export const belongsInConversations = (
  chat: Pick<Chat, 'id' | 'type' | 'circle' | 'pinned' | 'draft' | 'unreadCount' | 'messages' | 'lastKind' | 'lastSnippet'>,
): boolean =>
  isSavedNotes(chat) || isNodeChat(chat) || chat.type === 'group' || chat.type === 'channel' ||
  Boolean(chat.pinned) || Boolean(chat.draft) || chat.unreadCount > 0 || hasLetter(chat) || chatKeep.has(chat.id);

/** Рід розмови для чипів списку. Збережене й PHANTOM — лише в «Усі». */
export type ChatKind = 'person' | 'group' | 'other';

export const chatKind = (chat: Pick<Chat, 'id' | 'type' | 'circle'>): ChatKind => {
  if (isSavedNotes(chat) || isNodeChat(chat)) return 'other';
  if (chat.type === 'group' || chat.type === 'channel') return 'group';
  return 'person';
};

const ASK_WORDS =
  /(^|[^\p{L}])(скинь|скиньте|надішли|надішліть|пришли|можеш|можете|коли|приїдеш|приїдете|прийдеш|прийдете|допоможи|допоможіть|подзвони|подзвоніть|передзвони|передзвоніть|зателефонуй|напиши|напишіть|відпиши|підкажи|підкажіть|глянь|гляньте|купи|забери|чекаю)(?![\p{L}])/iu;

/** Чи лист — питання чи прохання: знак питання в кінці або слово прохання. */
export const isAsk = (text: string | null | undefined): boolean => {
  const t = (text || '').trim();
  if (!t) return false;
  return /\?[\s!?)»"'.…]*$/u.test(t) || ASK_WORDS.test(t);
};

export interface WaitingItem {
  chat: Chat;
  /** Сам лист — або «Голосове, 0:42», якщо розшифровки немає. */
  text: string;
  voice: boolean;
  at?: string;
}

/**
 * «Чекають на вас»: непрочитаний вхідний лист у розмові вдвох, що є питанням
 * чи проханням, або голосове. Лише з того, що вже лежить у розмові — нічого
 * не питаємо в мережі й нічого не домальовуємо.
 */
export const waitingOf = (chat: Chat): WaitingItem | null => {
  if (chatKind(chat) !== 'person' || chat.archived || chat.muted || chat.unreadCount <= 0) return null;
  const last = chat.messages?.length ? chat.messages[chat.messages.length - 1] : null;
  const fromMe = last ? Boolean(last.isSelf) : selfWord(chat.lastAuthor) === tNow('chatList.you');
  if (fromMe) return null;
  const at = chat.lastAt || last?.sentAt;
  const kind = chat.lastKind || last?.type;
  if (kind === 'voice') {
    const transcript = last?.voiceData?.transcript?.trim();
    if (transcript) return { chat, text: `«${transcript}»`, voice: true, at };
    const d = durationOf(last, chat.lastSnippet);
    return { chat, text: d ? tNow('chatList.voiceWithDuration', { d: clockDuration(d) }) : tNow('chatList.kind.voice'), voice: true, at };
  }
  if (kind && kind !== 'text') return null;
  const text = plainText(last?.text ?? chat.lastSnippet ?? '').trim();
  if (!text || attachmentKindFromSnippet(text) || looksLikeFileName(text) || !isAsk(text)) return null;
  return { chat, text, voice: false, at };
};
