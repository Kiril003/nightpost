/**
 * Сигнали рядка списку розмов: чернетка, своя дорога, згадка, відгук, гілка,
 * мініатюра, закріплене. Чистий перетворювач — без мережі, часу й DOM.
 *
 * Правило одне на всі поля: немає даних — немає сигналу. Кожне поле або
 * прочитане з того, що вже лежить у розмові, або null/false/0. Нічого не
 * домальовуємо «для вигляду»: крапка «вас згадали» без справжньої згадки —
 * та сама брехня, що й дві галочки, яких вузол не віддає.
 *
 * Читаємо лише ХВІСТ стрічки (останні TAIL листів): список малює сотні рядків
 * на кожен кадр, і повний прохід по історії кожної розмови зʼїв би прокрутку.
 * Ціна чесна й названа: сигнал про лист, глибший за хвіст, сюди не доходить.
 */
import { mentionsMeOnWire } from './mentions';
import type { Chat, Message } from '../types/messenger';
import { cachedMediaUrl } from './messengerMedia';

/** Сховок узяв лист — він уже не на телефоні. Байти вкладення, що ще тут, тримають годинник. */
export const isStored = (m: Pick<Message, 'status' | 'relayState' | 'attachmentState'>): boolean =>
  m.status === 'queued' &&
  m.relayState === 'stored' &&
  (!m.attachmentState || m.attachmentState === 'sent' || m.attachmentState === 'stored');

export interface RowSignals {
  /** Чернетка з `store.drafts`, обрізана; порожня — null. */
  draft: string | null;
  /** Дорога ОСТАННЬОГО власного листа, якщо він ще в дорозі. */
  sending: 'sending' | 'queued' | null;
  /** Останній власний лист не зберігся — потрібен новий дотик. */
  failed: boolean;
  /** Серед непрочитаних чужих — згадка мене або всіх. */
  mention: boolean;
  /** Смайл, яким ХТОСЬ ІНШИЙ відгукнувся на мій найновіший лист. */
  reaction: string | null;
  /** Скільки непрочитаних чужих листів — відповіді в гілці (мають replyTo). */
  threadNew: number;
  /** Мініатюра, коли найновіший живий лист — фото. */
  thumb: string | null;
  /** Текст закріпленого рядка; закріплене без тексту — null. */
  pinned: string | null;
}

export interface RowSignalOpts {
  drafts: Record<string, string>;
  me: {
    /** Ідентифікатор вузла. Без нього «хтось інший» не відрізнити від мене — відгуку не показуємо. */
    id?: string;
    name?: string;
    handle?: string | null;
  };
}

/** Скільки листів з кінця стрічки взагалі дивимось. */
const TAIL = 50;
/** Відгук шукаємо лише на свіжому: мій лист серед останніх стількох. */
const REACTION_TAIL = 20;

/** Той самий клас знаків, що в розборі тегів і згадок FormattedMessageText. */
const WORD = 'a-zA-Z0-9_\\u0400-\\u04FF';
const TAG_RE = new RegExp(`#([${WORD}]+)`, 'g');

const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Регулярка «згадали мене». Межа слова — своя, бо `\b` у JS знає лише
 * латиницю: для нього кирилична літера — вже межа, і `@Кирилон` пройшов би
 * як згадка «Кирило». Перед `@` не має стояти літера (пошта `a@b` — не
 * згадка), після імені — теж (`@Кирилон` — інша людина).
 */
const mentionRe = (me: RowSignalOpts['me']): RegExp => {
  const names = ['all', 'усі']; // i18n-data: mention word typed in Ukrainian
  const handle = (me.handle || '').trim().replace(/^@/, '');
  if (handle) names.push(handle);
  const first = (me.name || '').trim().split(/\s+/)[0] || '';
  // Однолітерне імʼя («Я» — заглушка профілю) збігалося б з чим завгодно.
  if (first.length >= 2) names.push(first);
  const alt = names.map(escapeRe).join('|');
  return new RegExp(`(?<![${WORD}@])@(?:${alt})(?![${WORD}])`, 'i');
};

/** Хвіст стрічки без копіювання всієї історії. */
const tailOf = (messages: Message[], n: number): Message[] =>
  messages.length <= n ? messages : messages.slice(messages.length - n);

/** Найновіший власний лист у хвості (або undefined). */
const lastOwn = (tail: Message[]): Message | undefined => {
  for (let i = tail.length - 1; i >= 0; i--) if (tail[i].isSelf) return tail[i];
  return undefined;
};

/**
 * Відгук іншої людини на мій лист. Часу позначки вузол не віддає, тож
 * «найновіший» — останній у тому порядку, в якому вузол склав масив (новий
 * смайл і новий автор дописуються в кінець). Свою позначку (`mine` або мій id)
 * відгуком не вважаємо: це не новина для мене.
 */
const othersReaction = (msg: Message, myId: string): string | null => {
  const rs = msg.reactions;
  if (!rs || rs.length === 0) return null;
  for (let i = rs.length - 1; i >= 0; i--) {
    const r = rs[i];
    if (r.users.some((u) => u && u !== myId)) return r.emoji;
  }
  return null;
};

const thumbOf = (m: Message): string | null => {
  if (m.type !== 'image') return null;
  const media = m.media;
  if (media?.thumb) return media.thumb;
  const cached = media?.blobId ? cachedMediaUrl(media.blobId) : undefined;
  if (cached) return cached;
  return m.localPreviewUrl || null;
};

export function rowSignals(chat: Chat, opts: RowSignalOpts): RowSignals {
  const messages = chat.messages ?? [];
  const tail = tailOf(messages, TAIL);

  const draftRaw = opts.drafts[chat.id];
  const draft = typeof draftRaw === 'string' && draftRaw.trim() ? draftRaw.trim() : null;

  const own = lastOwn(tail);
  const sending = own && (own.status === 'sending' || (own.status === 'queued' && !isStored(own))) ? own.status : null;
  const failed = own?.status === 'failed';

  // Непрочитане — це хвіст довжиною unreadCount. Більше, ніж завантажено,
  // прочитати нема звідки: рахуємо лише те, що бачимо.
  const unread = Math.max(0, Math.min(chat.unreadCount || 0, tail.length));
  let mention = false;
  let threadNew = 0;
  if (unread > 0) {
    const re = mentionRe(opts.me);
    const allRe = mentionRe({});
    for (let i = tail.length - unread; i < tail.length; i++) {
      const m = tail[i];
      if (m.isSelf || m.isDeleted) continue;
      if (!mention) {
        const wire = mentionsMeOnWire(m.mentions, opts.me.id ?? '');
        mention = wire ?? Boolean(m.text && re.test(m.text));
        if (wire === false && m.text && allRe.test(m.text)) mention = true;
      }
      if (m.replyTo?.id) threadNew++;
    }
  }

  let reaction: string | null = null;
  if (opts.me.id) {
    const mine = lastOwn(tailOf(tail, REACTION_TAIL));
    if (mine && !mine.isDeleted) reaction = othersReaction(mine, opts.me.id);
  }

  let thumb: string | null = null;
  for (let i = tail.length - 1; i >= 0; i--) {
    if (tail[i].isDeleted) continue;
    thumb = thumbOf(tail[i]);
    break;
  }

  const pinnedText = chat.pinnedMessage?.text;
  const pinned = typeof pinnedText === 'string' && pinnedText.trim() ? pinnedText.trim() : null;

  return { draft, sending, failed, mention, reaction, threadNew, thumb, pinned };
}

/** Теги в тексті: малими літерами, без `#`, кожен один раз, у порядку появи. */
export function hashtagsIn(text: string): string[] {
  if (!text || text.indexOf('#') < 0) return [];
  const out: string[] = [];
  const seen = new Set<string>();
  for (const m of text.matchAll(TAG_RE)) {
    const t = m[1].toLowerCase();
    if (seen.has(t)) continue;
    seen.add(t);
    out.push(t);
  }
  return out;
}

/** `#Світ`, `світ`, ` #світ ` → `світ`. */
export const normaliseTag = (tag: string): string => tag.trim().replace(/^#+/, '').toLowerCase();

/**
 * Чи є в розмові саме цей тег — цілим словом, а не шматком: `#світ` не
 * знаходиться в `#світло`. Дивимось рядок списку і завантажені листи; чого
 * веб не завантажив, того й не стверджуємо.
 */
export function chatHasTag(chat: Chat, tag: string): boolean {
  const want = normaliseTag(tag);
  if (!want) return false;
  if (chat.lastSnippet && hashtagsIn(chat.lastSnippet).includes(want)) return true;
  for (const m of chat.messages ?? []) {
    if (m.isDeleted || !m.text) continue;
    if (hashtagsIn(m.text).includes(want)) return true;
  }
  return false;
}

export interface TaggedLine {
  chatId: string;
  messageId: string;
  text: string;
}

/** Завантажені листи з тегом, новіші першими; [skip] — уже позначені id листів. */
export function taggedLines(chats: readonly Chat[], tag: string, skip: ReadonlySet<string>): TaggedLine[] {
  const want = normaliseTag(tag);
  const out: Array<TaggedLine & { at: number }> = [];
  for (const c of chats) {
    for (const m of c.messages ?? []) {
      if (m.isDeleted || !m.text || skip.has(m.id)) continue;
      if (!hashtagsIn(m.text).includes(want)) continue;
      const text = m.text.replace(new RegExp(`#${want}(?![${WORD}])`, 'gi'), '').replace(/\s+/g, ' ').trim();
      const at = m.sentAt ? Date.parse(m.sentAt) : NaN;
      out.push({ chatId: c.id, messageId: m.id, text: text || m.text.trim(), at: Number.isFinite(at) ? at : 0 });
    }
  }
  return out.sort((a, b) => b.at - a.at).map(({ at: _at, ...line }) => line);
}
