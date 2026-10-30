/**
 * Дрібні частини рядка стрічки: знаки стану біля часу, пігулка дня, пігулка
 * гілки, вигляди дороги реакції. Винесено з ChatArea без змін вигляду.
 */
import React from 'react';
import { Clock, AlertCircle, Check, CheckCheck, GitBranch, ChevronRight } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { Message } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceStore } from '../../../stores/spaceStore';
import { haptic } from '../../../utils/haptic';
import { SILENCE_EXTRA_PX, type SilenceGap } from '../../../services/silenceGap';
import { tNow, tPlural } from '../../chat/tNow';
import { useTranslation } from '../../../i18n/useTranslation';
import { plainText } from '../../../services/plainText';
import { kindLine } from '../chatListFormat';
export const quickReactions = ['❤️', '🔥', '👍', '👏', '💡', '🎉'];

/**
 * ТРИ СТАНИ ПОЗНАЧКИ — три різні вигляди. Це і був головний брак.
 *
 * Власник, дослівно: «начебто поставилось». Смайл зʼявлявся під рядком
 * однаково і коли доїхав до співрозмовника, і коли лежав у черзі, і коли не
 * поїхав узагалі — бо вигляд читався з одного біта «я серед авторів», а стан
 * дороги на дроті не возився зовсім.
 *
 * Розрізняє їх не лише колір: кольору мало на сірому склі й нічого не варте
 * для тих, хто його не бачить. Тому в кожного стану ще й свій знак на самій
 * пігулці та своє речення в підписі.
 *
 * `delivered` тут — це ВІДСУТНІСТЬ поля `mine`: у чужої позначки дороги немає
 * за означенням (вона приїхала), і в доїхалої власної теж.
 */
export const REACTION_ROAD: Record<
  'delivered' | 'sending' | 'queued' | 'failed',
  { skin: string; say: string; dim: boolean; Icon: LucideIcon | null }
> = {
  delivered: {
    skin: 'bg-raised border-line text-ink font-semibold',
    get say() { return tNow('feedParts.road.delivered'); },
    dim: false,
    Icon: null,
  },
  sending: {
    skin: 'bg-raised border-line text-ink-2 border-dashed',
    get say() { return tNow('feedParts.sending'); },
    dim: true,
    Icon: null,
  },
  queued: {
    skin: 'bg-raised border-line text-ink-2 border-dashed',
    get say() { return tNow('feedParts.road.queued'); },
    dim: true,
    Icon: null,
  },
  failed: {
    skin: 'bg-raised border-danger text-danger font-semibold',
    get say() { return tNow('feedParts.road.failed'); },
    dim: false,
    Icon: AlertCircle,
  },
};

/**
 * Імʼя вкладення для меню й озвучення.
 *
 * Справжні вкладення живуть у msg.media, показові — у msg.fileData; читання
 * лише другого давало «Файл: undefined» на кожному живому файлі.
 */
export const attachmentName = (msg: Message): string =>
  msg.media?.name || msg.fileData?.name || tNow('feedParts.noName');

/** Один рядок листа для меню й «Видалити?»: слова, вкладення, назва місця або рід — не «Картка» і не сирий тип. */
export const letterPreview = (msg: Message): string =>
  plainText(msg.text) ||
  (msg.media || msg.fileData
    ? `${tNow(msg.type === 'image' ? 'messageActionSheet.photo' : 'messageActionSheet.file')}: ${attachmentName(msg)}`
    : msg.geoPoint?.label?.trim() || kindLine(msg.type, msg) || tNow('messageActionSheet.card'));

export const translationLanguages = [
  { code: 'EN', name: 'English (EN)' },
  { code: 'UA', name: 'Українська (UA)' }, // i18n-data: language endonym, the same in every UI language
  { code: 'DE', name: 'Deutsch (DE)' },
  { code: 'PL', name: 'Polski (PL)' },
  { code: 'ES', name: 'Español (ES)' },
];

/**
 * Тиха позначка «лист дійшов до вузла адресата» — одна ✓ у mono біля часу.
 * Двох галочок немає свідомо: вузол не віддає ні «доставлено на телефон», ні
 * «прочитано» (див. Message.status), і ✓✓ означало б стан, якого не буває.
 * У групі поруч — скільки копій дійшло: «✓ 2/3».
 */
/**
 * Ключ тону автора в групі — перше слово імені, як у превʼю списку («Петро:»).
 * Список знає лише імʼя, тож і стрічка бере тон з імені: один Петро — один тон.
 */
export const groupToneKey = (name?: string): string => (name || '?').trim().split(/\s+/)[0];

/**
 * Печатка (ідентичність 02.10): щойно лист пішов із телефона, біля часу на
 * мить стоїть замок, що «осідає» й перетворюється на ✓ — шифрування видно
 * без слів. Раз на лист і лише для свіжого (≤ 8 с), не при гортанні історії.
 */
const sealed = new Set<string>();
export const sealOnce = (msg: Pick<Message, 'id' | 'sentAt'>, now = Date.now()): boolean => {
  if (sealed.has(msg.id)) return false;
  sealed.add(msg.id);
  const at = msg.sentAt ? Date.parse(msg.sentAt) : NaN;
  return Number.isFinite(at) && now - at >= 0 && now - at < 8000;
};

const SealLock: React.FC = () => (
  <svg viewBox="0 0 24 24" className="ph-seal-lock w-4 h-4" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <rect x="5" y="11" width="14" height="10" rx="2.5" />
    <path d="M8 11V8a4 4 0 0 1 8 0v3" />
  </svg>
);

/**
 * Дорога листа (біблія 05 №16, ідентичність 02.10): на довгому натисканні на
 * свій лист — проста схема «Ти → напряму → Оксана» або «Ти → сховок →
 * Оксана». Лише коли дорогу назвав сам вузол; невідома дорога схеми не має.
 */
export const ROUTE_WORD: Record<string, string> = {
  get direct() { return tNow('feedParts.route.direct'); },
  get p2p() { return tNow('feedParts.route.direct'); },
  get relay() { return tNow('feedParts.route.store'); },
  get mailbox() { return tNow('feedParts.route.store'); },
  get server() { return tNow('feedParts.route.server'); },
};
export const RouteDiagram: React.FC<{ transport?: Message['transport']; to: string }> = ({ transport, to }) => {
  const { t } = useTranslation();
  if (!transport || !ROUTE_WORD[transport]) return null;
  const direct = transport === 'direct' || transport === 'p2p';
  return (
    <div data-route={direct ? 'direct' : 'store'} className="px-4 pt-3" aria-label={t('feedParts.routeLabel', { route: ROUTE_WORD[transport] })}>
      <div className="flex items-center gap-2 text-caption text-ink-2">
        <span className="shrink-0 font-semibold text-ink">{t('feedParts.you')}</span>
        <svg viewBox="0 0 120 16" className="flex-1 h-4 min-w-0" preserveAspectRatio="none" aria-hidden>
          <circle cx="4" cy="8" r="3.5" fill="var(--ph-accent-line)" />
          <circle cx="116" cy="8" r="3.5" fill="var(--ph-text)" />
          {direct ? (
            <path d="M10 8H110" stroke="var(--ph-text-3)" strokeWidth="1.5" strokeLinecap="round" />
          ) : (
            <>
              <path d="M10 8H50M70 8H110" stroke="var(--ph-text-3)" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="1 4" />
              <rect x="52" y="2" width="16" height="12" rx="3" fill="none" stroke="var(--ph-text-2)" strokeWidth="1.5" />
            </>
          )}
        </svg>
        <span className="shrink-0 font-semibold text-ink truncate max-w-[40%]">{to}</span>
      </div>
      <p className="mt-1 text-center text-caption text-ink-3">{ROUTE_WORD[transport]}</p>
    </div>
  );
};

export const SentTick: React.FC<{ msg: Message }> = ({ msg }) => {
  const { t } = useTranslation();
  const [seal] = React.useState(() => sealOnce(msg));
  // Лист пішов із телефона — коротке «так» пальцю (05 §1.4 `confirm`).
  React.useEffect(() => {
    if (seal) haptic('success');
  }, [seal]);
  const group = typeof msg.fanTotal === 'number';
  const read = group ? (msg.fanRead ?? 0) > 0 : Boolean(msg.readAt);
  const label = read
    ? group
      ? t('feedParts.readBy', { read: msg.fanRead ?? 0, total: msg.fanTotal ?? 0 })
      : t('feedParts.read')
    : t('feedParts.delivered');
  const Mark = read ? CheckCheck : Check;
  return (
    <span
      data-msg-tick={read ? 'read' : 'delivered'}
      data-seal={seal || undefined}
      className={`inline-flex items-center gap-1 tnum whitespace-nowrap ${seal ? 'ph-seal' : 'f-fade-in'} ${read ? 'msg-tick-read' : ''}`}
      aria-label={label}
      title={label}
    >
      <span className="relative inline-flex">
        {seal && <SealLock />}
        <Mark className="ph-seal-mark w-4 h-4" strokeWidth={1.75} aria-hidden />
      </span>
      {group ? `${read ? msg.fanRead : msg.fanDelivered ?? 0}/${msg.fanTotal}` : ''}
    </span>
  );
};

/** «Збережене»: нотатка лежить на цьому телефоні — одна галочка, як у Telegram. */
export const SavedTick: React.FC = () => (
  <span data-msg-tick="saved" className="inline-flex items-center" aria-label={tNow('tgLook.savedHere')} title={tNow('tgLook.savedHere')}>
    <Check className="w-4 h-4" strokeWidth={1.75} aria-hidden />
  </span>
);

/** Хвостик останньої бульбашки черги; колір і бік бере з бульбашки (form.css). */
export const BubbleTail: React.FC<{ side: 'self' | 'other' }> = ({ side }) => (
  <span data-bubble-tail={side} aria-hidden className="msg-tail">
    <svg viewBox="0 0 11 17">
      <path className="msg-tail-fill" d="M0 0C0 8 3 14 11 17H0Z" />
      <path className="msg-tail-edge" d="M0 0C0 8 3 14 11 17H0" fill="none" />
    </svg>
  </span>
);

/** Сховок узяв лист: одна галочка «надіслано»; «доставлено» скаже квитанція. */
export const StoredTick: React.FC = () => (
  <span data-msg-tick="stored" className="inline-flex items-center" aria-label={tNow('today.tickSent')} title={tNow('today.tickSent')}>
    <Check className="w-4 h-4" strokeWidth={1.75} aria-hidden />
  </span>
);

/** Не пішло — червоний знак біля часу замість речення; дотик пробує ще раз. */
export const FailedMark: React.FC<{ msgId: string; reason?: string }> = ({ msgId, reason }) => {
  const label = reason ? `${reason} · ${tNow('feedParts.retry')}` : tNow('feedParts.failedRetry');
  return (
    <button
      type="button"
      data-testid="msg-failed"
      onClick={(e) => {
        e.stopPropagation();
        soundFx.playTap();
        void useMessengerStore.getState().retrySend(msgId);
      }}
      className="touch-floor-inline relative min-h-0 min-w-0 inline-flex items-center gap-1 max-w-full text-danger whitespace-nowrap"
      aria-label={label}
      title={label}
    >
      <AlertCircle className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden />
      <span className="truncate">{label}</span>
    </button>
  );
};

/**
 * День — тихий рядок посередині, 13/500 text-2: «Сьогодні», «Вчора», «1 жовтня».
 *
 * Прилиплий підпис поверх стрічки лежить на плоскій пігулці `surface`.
 * Поки прилиплий показаний, власний підпис цього дня ховається (`concealed`).
 */
export const SilenceLabel: React.FC<{ gap: SilenceGap }> = ({ gap }) => {
  const { t } = useTranslation();
  const text = t(gap.unit === 'h' ? 'silenceGap.hours' : 'silenceGap.days', { n: gap.n });
  return (
    <div
      data-silence-gap
      className="flex justify-center text-footnote text-ink-3 pointer-events-none"
      style={{ paddingTop: SILENCE_EXTRA_PX[gap.step] }}
    >
      {text}
    </div>
  );
};

export const DayPill: React.FC<{ label: string; concealed?: boolean }> = ({ label, concealed }) => (
  <div
    className="relative z-10 flex justify-center pt-4 pb-2 pointer-events-none"
    style={concealed ? { visibility: 'hidden' } : undefined}
    data-day-divider
  >
    <span className="ph-day-pill">{label}</span>
  </div>
);

export const FoldRow: React.FC<{ count: number; onShow?: () => void }> = ({ count, onShow }) => {
  const { t } = useTranslation();
  if (count < 3) return null;
  return (
    <div data-testid="fold-row" className="flex items-center justify-center gap-2 py-1.5 text-footnote text-ink-3">
      <span>{t('foldRow.count', { n: count })}</span>
      {onShow && (
        <button
          type="button"
          data-testid="fold-show"
          onClick={(e) => {
            e.stopPropagation();
            soundFx.playTap();
            onShow();
          }}
          className="touch-floor-inline text-footnote font-semibold text-accent-text"
        >
          {t('foldRow.show')}
        </button>
      )}
    </div>
  );
};

/** Прилиплий день — поверх стрічки під час прокрутки. */
export const StuckDayPill: React.FC<{ label: string }> = ({ label }) => (
  <div className="absolute top-2 left-0 right-0 z-10 flex justify-center pointer-events-none f-fade-in" data-day-stuck>
    <span className="ph-day-pill ph-day-pill-float">{label}</span>
  </div>
);

/** Лист ще не доставлено — тихий годинник біля часу. Дотик пробує ще раз. */
export const PendingClock: React.FC<{ onTap?: () => void }> = ({ onTap }) =>
  onTap ? (
    <button
      type="button"
      data-msg-pending
      onClick={(e) => {
        e.stopPropagation();
        onTap();
      }}
      className="touch-floor-inline relative min-h-0 min-w-0 inline-flex items-center"
      aria-label={tNow('feedParts.pendingNow')}
    >
      <Clock className="w-4 h-4" strokeWidth={1.75} aria-hidden />
    </button>
  ) : (
    <span data-msg-pending className="inline-flex items-center" aria-label={tNow('feedParts.sending')}>
      <Clock className="w-4 h-4" strokeWidth={1.75} aria-hidden />
    </span>
  );

export const ThreadPill: React.FC<{ msgId: string; count: number; chatId: string; inside?: boolean }> = ({
  msgId,
  count,
  chatId,
  inside,
}) => (
  <button
    type="button"
    data-testid={`thread-pill-${msgId}`}
    onClick={(e) => {
      e.stopPropagation();
      soundFx.playTap();
      useSpaceStore.getState().openThreadView({ chatId, rootMessageId: msgId });
    }}
    className={
      inside
        ? 'touch-floor-inline relative mt-1.5 -mx-0.5 flex w-full min-h-0 items-center gap-1.5 border-t border-line pt-1.5 text-caption font-semibold text-accent-text'
        : 'touch-floor-inline relative mt-1 inline-flex min-h-0 items-center gap-1 rounded-full bg-raised px-2 py-0.5 text-caption font-semibold text-ink-2'
    }
    aria-label={tNow('feedParts.openThread', { count })}
  >
    <GitBranch className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden />
    <span>
      {count} {tPlural(count, 'feedParts.replies')}
    </span>
    {inside && <ChevronRight className="ml-auto w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden />}
  </button>
);
