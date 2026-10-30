import React, { useEffect, useState } from 'react';
import { Message, ChatMember, ReactionRoad } from '../../types/messenger';
import { ExternalLink } from 'lucide-react';
import { reactionAuthors } from '../../services/reactionState';
import { explorerTxUrl, shortChainId } from '../../services/solanaFields';
import { FormSheet } from './FormSheet';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import type { Locale } from '../../i18n/locales';
import { dateTag } from '../../i18n/dateTag';
import { messengerApi } from '../../services/messengerApi';

/** Дорога позначки СЛОВАМИ. Той самий словник, що в пігулці під рядком. */
const REACTION_ROAD_SAY: Record<ReactionRoad, MessageKey> = {
  sending: 'messageDetailsModal.roadSending',
  queued: 'messageDetailsModal.roadQueued',
  failed: 'messageDetailsModal.roadFailed',
  delivered: 'messageDetailsModal.roadDelivered',
};

const deliveryStatusLabel: Record<NonNullable<Message['status']>, MessageKey> = {
  sending: 'messageDetailsModal.statusSending',
  queued: 'messageDetailsModal.statusQueued',
  sent: 'messageDetailsModal.statusDelivered',
  failed: 'messageDetailsModal.statusFailed',
};

interface MessageDetailsModalProps {
  message: Message | null;
  isOpen: boolean;
  onClose: () => void;
  chatTitle?: string;
  members?: ChatMember[];
  /** Хто «я» — щоб під смайлом стояло «Я», а не власний ключ вузла. */
  selfId?: string;
  conversationId?: string;
}

type Version = { text: string; at: string };

const versionTime = (at: string, locale: Locale): string => {
  const d = new Date(at);
  const today = d.toDateString() === new Date().toDateString();
  return d.toLocaleString(
    dateTag(locale),
    today ? { hour: '2-digit', minute: '2-digit' } : { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' },
  );
};

export const when = (m: Message, locale: Locale): string => {
  const ms = Date.parse(m.sentAt || '');
  return Number.isFinite(ms)
    ? new Date(ms).toLocaleString(dateTag(locale), { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })
    : m.timestamp;
};

/**
 * Подробиці листа — лише те, що людині справді треба: коли, чи дійшло, хто
 * поставив смайли. Ідентифікатора, типу вмісту, лічильника символів і
 * дороги доставки тут більше немає: це механіка, не відповідь.
 */
export const MessageDetailsModal: React.FC<MessageDetailsModalProps> = ({
  message,
  isOpen,
  onClose,
  selfId = '',
  conversationId,
}) => {
  const { t, locale } = useTranslation();
  useEscapeClose(isOpen, onClose);
  const [versions, setVersions] = useState<Version[]>([]);
  const conv = conversationId || message?.chatId;
  const edited = isOpen && message?.isEdited && conv ? `${conv}/${message.id}` : '';
  useEffect(() => {
    setVersions([]);
    if (!edited || !conv || !message) return;
    let live = true;
    messengerApi
      .messageHistory(conv, message.id)
      .then((r) => live && setVersions(Array.isArray(r?.versions) ? r.versions.filter((v) => v.text) : []))
      .catch(() => undefined);
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [edited]);
  if (!isOpen || !message) return null;
  const history = versions.length > 1;

  const rows: Array<[string, React.ReactNode]> = [
    [message.isSelf ? t('messageDetailsModal.sent') : t('messageDetailsModal.received'), when(message, locale)],
  ];
  if (message.isSelf && message.status) {
    rows.push([
      t('messageDetailsModal.status'),
      <span className={message.status === 'failed' ? 'text-danger' : undefined}>{t(deliveryStatusLabel[message.status])}</span>,
    ]);
  }
  if (message.isEdited && !history) rows.push([t('messageDetailsModal.edited'), t('messageDetailsModal.yes')]);

  // Мережа й відбиток транзакції живуть тут, а не на картці переказу в розмові.
  const chain = message.payment ?? message.seal;
  if (chain) {
    rows.push([
      t('messageDetailsModal.network'),
      chain.cluster === 'devnet' ? <span title={t('solanaCard.devnetTitle')}>Devnet</span> : 'Mainnet',
    ]);
    // Купюра, що ще не подана в мережу, транзакції в оглядачі не має.
    const onChain = !(message.payment?.offlineCash && message.payment.status === 'pending');
    if (onChain) {
      rows.push([
        t('messageDetailsModal.transaction'),
        <a
          href={explorerTxUrl(chain.signature, chain.cluster)}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={`${t('solanaCard.verifyOnChain')} ${shortChainId(chain.signature)}`}
          className="touch-floor-inline inline-flex items-center gap-1 whitespace-nowrap font-medium tnum text-accent-text"
        >
          <span className="shrink-0">{shortChainId(chain.signature)}</span>
          <ExternalLink className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden />
        </a>,
      ]);
    }
  }

  return (
    <FormSheet open onClose={onClose} title={t('messageDetailsModal.title')} testId="message-details">
      <div className="pb-4">
        {message.text && (
          <div className={`px-4 pb-3 flex ${message.isSelf ? 'justify-end' : 'justify-start'}`}>
            <p
              className={`max-w-[85%] px-4 py-2.5 rounded-bubble text-callout text-ink line-clamp-4 break-words select-text ${
                message.isSelf ? 'bg-self' : 'bg-raised'
              }`}
            >
              {message.text}
            </p>
          </div>
        )}

        <dl className="mx-4 rounded-card bg-raised">
          {rows.map(([k, v], i) => (
            <div key={k} className={`min-h-12 px-4 py-3 flex items-center justify-between gap-4 ${i ? 'border-t border-line' : ''}`}>
              <dt className="text-callout text-ink-2">{k}</dt>
              <dd className="text-callout text-ink text-right">{v}</dd>
            </div>
          ))}
        </dl>

        {history && (
          <>
            <h3 className="px-4 pt-5 pb-1 text-footnote font-semibold text-ink-3">{t('messageDetailsModal.history')}</h3>
            <ul data-testid="message-history">
              {versions.map((v, i) => (
                <li key={v.at} className={`mx-4 py-3 ${i ? 'border-t border-line' : ''}`}>
                  <div className="text-caption text-ink-3">{i ? versionTime(v.at, locale) : t('messageDetailsModal.historyNow')}</div>
                  <div className="text-callout text-ink break-words select-text">{v.text}</div>
                </li>
              ))}
            </ul>
          </>
        )}

        {message.reactions && message.reactions.length > 0 && (
          <>
            <h3 className="px-4 pt-5 pb-1 text-footnote font-semibold text-ink-3">{t('messageDetailsModal.reactions')}</h3>
            <ul>
              {message.reactions.map((r, idx) => (
                <li key={idx} className="min-h-12 px-4 py-2 flex items-center gap-3">
                  <span className="text-[24px] shrink-0">{r.emoji}</span>
                  <span className="flex-1 min-w-0 text-callout text-ink truncate">
                    {/* Імена, а не ключі вузлів. */}
                    {reactionAuthors(r, selfId).join(', ')}
                  </span>
                  {/* Дорога ВЛАСНОЇ позначки — словами. */}
                  {r.mine && r.mine !== 'delivered' && (
                    <span className="shrink-0 text-caption text-ink-3">{t(REACTION_ROAD_SAY[r.mine])}</span>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </FormSheet>
  );
};
