/**
 * Тіло бульбашки: усе, що лист несе всередині, за родами — текст, хід думок
 * PHANTOM, відповіді локального агента, картки, голос, кружок, медіа, код,
 * віджети ПК. Винесено з ChatArea без змін розмітки; змінилось лише те, ЗВІДКИ
 * беруться дані: з пропсів рядка й контексту дій, а не із замикань на стан
 * усієї розмови.
 */
import React from 'react';
import {
  ChevronDown,
  ChevronUp,
  Languages,
  CheckCircle2,
} from 'lucide-react';
import { FormattedMessageText } from '../FormattedMessageText';
import { HighlightedText } from '../HighlightedText';
import type { FeedRow } from './feedModel';
import { useFeedActions } from './feedActions';
import { MessageEmbeds } from './MessageEmbeds';
import { LinkPreviewCard } from './LinkPreviewCard';
import { EduEventCard } from '../directions/education/EduEventCard';
import { eduStrip, parseEdu } from '../../../services/edu/eduWire';
import { parseSticker } from '../../../stickers/stickerWire';
import { StickerView } from '../../../stickers/StickerView';
import { stickerForEmoji } from '../../../stickers/pack';
import { BIG_EMOJI, STICKER } from '../../../stickers/stickerBox';

import { useTranslation } from '../../../i18n/useTranslation';
export interface MessageBodyProps {
  row: FeedRow;
  chatId?: string;
  currentUserId: string;
  currentUserName: string;
  phone: boolean;
  searchQuery?: string;
  isActiveSearchMatch: boolean;
  expanded: boolean;
  translated?: { text: string; lang: string };
  summary?: string;
  actionItems?: string[];
  agentLabel?: string;
  copied: boolean;
  /** Місце під час у кінці останнього рядка тексту (MessageRow). */
  metaSpace?: number;
}


export const MessageBody: React.FC<MessageBodyProps> = ({
  row,
  chatId,
  currentUserId,
  currentUserName,
  searchQuery,
  isActiveSearchMatch,
  expanded,
  translated,
  summary,
  actionItems,
  agentLabel,
  copied,
  metaSpace = 0,
}) => {
  const { t } = useTranslation();
  const actions = useFeedActions();
  const { msg, isSelf, cardView } = row;
  const edu = msg.type === 'text' && !msg.media && !cardView ? parseEdu(msg.text) : null;
  const sticker = msg.type === 'text' && !msg.media && !cardView && !edu ? parseSticker(msg.text) : null;
  const bigEmoji = !sticker && row.density === 'emoji-single' && msg.text ? stickerForEmoji(msg.text.trim()) : null;
  return (
    <>
      {sticker && <StickerView packId={sticker.packId} id={sticker.id} emoji={sticker.emoji} size={STICKER} />}
      {edu && <EduEventCard parsed={edu} chatId={chatId} senderId={isSelf ? currentUserId : msg.senderId} text={eduStrip(msg.text ?? '')} />}
      {/* Хід думок PHANTOM етапами («сприймаю → пригадую → міркую…») прибрано:
          справжній стан — лише «Nightpost відповідає…» у шапці (AUDIT, театр ШІ). */}
      {/* 1. TEXT MESSAGE (Formatted with Markdown, Spoilers, Mentions, Search Highlighting, or Organic Emoji Cluster) */}
      {/* У вкладення текст — це підпис, і його місце ПІД превʼю;
          малює його SecureMediaBubble, тож тут ми мовчимо. */}
      {/* У картки текст листа — старе пояснення («Створено таблицю…»),
          а не слова людини: стан картка каже сама. */}
      {msg.text && !msg.media && !cardView && !edu && !sticker && (
        bigEmoji ? (
          <StickerView packId={bigEmoji.packId} id={bigEmoji.id} emoji={bigEmoji.emoji} size={BIG_EMOJI} onceKey={msg.id} />
        ) : row.density === 'emoji-single' ? (
          <div className="text-[44px] leading-none select-text py-0.5 text-center">
            {msg.text.trim()}
          </div>
        ) : row.density === 'emoji-multi' ? (
          <div className="text-[28px] leading-tight select-text py-0.5">
            {msg.text.trim()}
          </div>
        ) : row.density === 'long' && msg.text && (msg.text.length > 1500 || (msg.text.split('\n')?.length || 0) >= 20) ? (
          (() => {
            const isExpanded = expanded;
            return (
              <div className="relative">
                <div
                  className={`msg-density-long-content ${
                    isExpanded ? 'msg-density-long-expanded pb-1' : 'msg-density-long-collapsed cursor-pointer'
                  }`}
                  onClick={(e) => {
                    if (!isExpanded) {
                      actions.toggleExpand(msg.id, e);
                    }
                  }}
                >
                  <FormattedMessageText
                    text={msg.text}
                    onMentionClick={(handle) => actions.selectMember(handle)}
                    searchQuery={searchQuery}
                    isActiveMatch={isActiveSearchMatch}
                    amountsTappable
                  />

                  {/* Gradient Fade and Read-More Toggle Button (when collapsed) */}
                  {!isExpanded && (
                    <div
                      className={`absolute bottom-0 left-0 right-0 h-16 flex items-end justify-center pb-0.5 cursor-pointer rounded-b-2xl transition-opacity duration-200 ${
isSelf ? 'msg-read-more-fade-self' : 'msg-read-more-fade-other'
}`}
                      onClick={(e) => actions.toggleExpand(msg.id, e)}
                    >
                      <button
                        type="button"
                        onClick={(e) => actions.toggleExpand(msg.id, e)}
                        className="touch-floor inline-flex items-center gap-1.5 text-subhead px-3 py-1 rounded-control bg-overlay text-ink hover:bg-raised transition-colors duration-quick"
                      >
                        <span>{t('messageBody.readMore')}</span>
                        <ChevronDown className="w-3.5 h-3.5" strokeWidth={1.75} />
                      </button>
                    </div>
                  )}
                </div>

                {/* Collapse Toggle Button (when expanded) */}
                {isExpanded && (
                  <div className="mt-1.5 flex justify-end">
                    <button
                      type="button"
                      onClick={(e) => actions.toggleExpand(msg.id, e)}
                      className="touch-floor inline-flex items-center gap-1 text-subhead px-3 py-1 rounded-control bg-overlay text-ink hover:bg-raised transition-colors duration-quick"
                    >
                      <span>{t('messageBody.collapse')}</span>
                      <ChevronUp className="w-3.5 h-3.5" strokeWidth={1.75} />
                    </button>
                  </div>
                )}
              </div>
            );
          })()
        ) : (
          <div className="text-body">
            <FormattedMessageText
              text={msg.text}
              onMentionClick={(handle) => actions.selectMember(handle)}
              searchQuery={searchQuery}
              isActiveMatch={isActiveSearchMatch}
              tailSpace={metaSpace}
              amountsTappable
            />
          </div>
        )
      )}

      {/* Картка посилання — склав відправник; сторінка за нею не ходить. */}
      {msg.linkPreview && !msg.media && !cardView && <LinkPreviewCard preview={msg.linkPreview} />}

      {/* Очікування відповіді локального агента */}
      {agentLabel && (
        <div className="mt-2 px-3 py-2 rounded-control text-footnote bg-overlay text-ink-2">{agentLabel}</div>
      )}

      {/* Переклад від локального агента */}
      {translated && (
        <div className="mt-2 px-3 py-2 rounded-control bg-overlay text-ink f-fade-in">
          <div className="flex items-center justify-between gap-2 mb-1 text-footnote text-ink-2">
            <span className="flex items-center gap-1">
              <Languages className="w-4 h-4" strokeWidth={1.75} />
              <span>{t('messageBody.translation', { lang: translated.lang })}</span>
            </span>
            <button onClick={() => actions.toggleTranslate(msg)} className="touch-floor-inline relative text-footnote text-accent-text">
              {t('messageBody.hide')}
            </button>
          </div>
          <p className="text-body text-ink">
            <HighlightedText
              text={translated.text}
              query={searchQuery}
            />
          </p>
        </div>
      )}

      {/* Підсумок від локального агента */}
      {summary && (
        <div className="mt-2 px-3 py-2 rounded-control bg-overlay text-ink f-fade-in">
          <div className="flex-1 min-w-0">
            <p className="text-footnote text-ink">
              <HighlightedText
                text={summary}
                query={searchQuery}
              />
            </p>
          </div>
        </div>
      )}

      {/* Завдання, які агент вичитав із повідомлення */}
      {actionItems && (
        <div className="mt-2 px-3 py-2 rounded-control space-y-1.5 bg-overlay text-ink f-fade-in">
          <div className="flex items-center justify-between text-footnote font-semibold text-ink-2">
            <span className="flex items-center gap-1">
              <CheckCircle2 className="w-4 h-4" strokeWidth={1.75} />
              <span>{t('messageBody.tasks')}</span>
            </span>
          </div>
          {actionItems.map((item, idx) => (
            <div key={idx} className="flex items-start gap-2 text-footnote text-ink">
              <span className="w-1.5 h-1.5 rounded-full bg-ink-3 mt-1.5 shrink-0" />
              <span>{item}</span>
            </div>
          ))}
        </div>
      )}

      <MessageEmbeds row={row} currentUserId={currentUserId} currentUserName={currentUserName} searchQuery={searchQuery} isActiveSearchMatch={isActiveSearchMatch} copied={copied} />
    </>
  );
};

