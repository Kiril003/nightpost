/**
 * Вбудовані речі листа (див. MessageEmbeds нижче).
 */
import React, { lazy, Suspense } from 'react';
import {
  Loader2,
  Copy,
  Download,
  FileText,
} from 'lucide-react';
import { soundFx } from '../../../utils/messengerSound';
import { useMessengerStore } from '../../../stores/messengerStore';
import { MultiQuoteEmbed } from '../MultiQuoteEmbed';
import { HighlightedText } from '../HighlightedText';
import { SecureMediaBubble } from '../SecureMediaBubble';
import { MediaBatchGrid } from '../MediaBatchGrid';
import { UnifiedVoiceBubble } from '../UnifiedVoiceBubble';
import { VideoNoteBubble } from '../VideoNoteBubble';
import { GeoPointBubble } from '../GeoPointBubble';
import { LiveLocationBubble } from '../LiveLocationBubble';
import { PeerWireBubble } from '../PeerWireBubble';
import { hasEventView, readMessageCard } from '../../../services/peerWire';
import { EventCard } from '../cards/EventCard';
import { CanvasCardEmbed } from '../widgets/CanvasCardEmbed';
import { MessageCard, TheatreCard, isDoorCodeMessage } from '../cards/MessageCard';
import { WorkCard, isWorkCard } from '../cards/WorkCards';
import { LIVE_CARD_KINDS, THEATRE_CARD_KINDS } from '../cards/cardModel';
import type { FeedRow } from './feedModel';
import { useFeedActions } from './feedActions';
import { SolanaCard, hasSolanaCard } from './SolanaCard';
import { MeetupCard } from '../meetup/MeetupCard';
import { BillCard } from '../meetup/BillCard';
import { LegacyBillCard, PlaceCard } from '../cards/PlaceCard';
import { BILL_KIND, MEETUP_KIND } from '../../../types/meetup';
import { OrderCard, OrderStatusLine } from '../directions/OrderCard';
import { orderOf, statusOfText } from '../../../services/business/orders';

import { useTranslation } from '../../../i18n/useTranslation';
import { stickerDef } from '../../../stickers/pack';
import { StickerView } from '../../../stickers/StickerView';
/**
 * ВАДА, що вже була виправлена в ChatArea: ChartEmbed тягне recharts + d3
 * (окремий чанк на ~500 КБ). Лінива межа лишається тут — діаграма рідкісна,
 * і платить за неї лише та розмова, у якій вона є.
 */
const ChartEmbed = lazy(() => import('../ChartEmbed').then((m) => ({ default: m.ChartEmbed })));

export interface MessageEmbedsProps {
  row: FeedRow;
  currentUserId: string;
  currentUserName: string;
  searchQuery?: string;
  isActiveSearchMatch: boolean;
  copied: boolean;
}

/**
 * Речі, які лист несе в собі: картки, діаграма, голос, кружок, стікер, місце,
 * код, віджети ПК, вкладення. Без змін розмітки — розділено з MessageBody лише
 * заради розміру файла.
 */
export const MessageEmbeds: React.FC<MessageEmbedsProps> = ({
  row,
  currentUserId,
  currentUserName,
  searchQuery,
  isActiveSearchMatch,
  copied,
}) => {
  const actions = useFeedActions();
  const { t } = useTranslation();
  const { msg, isSelf, cardView } = row;
  return (
    <>
      {/* 2. TABLE MESSAGE */}
      {/* Живі картки: таблиця, список справ, опитування, витрати —
          одна мова (cards/MessageCard), хоч би яким родом приїхали. */}
      {/* Зустріч і рахунок: тіло незмінне, стан — позначки card_marks. */}
      {cardView && msg.type === MEETUP_KIND && <MeetupCard msg={msg} meId={currentUserId} />}
      {cardView && msg.type === BILL_KIND && <BillCard msg={msg} meId={currentUserId} />}
      {cardView && orderOf(msg) && <OrderCard msg={msg} />}
      {cardView && hasEventView(msg) && <EventCard msg={msg} />}
      {cardView && !msg.isDeleted && statusOfText(msg.text) && (
        <OrderStatusLine id={statusOfText(msg.text)!.id} status={statusOfText(msg.text)!.status} />
      )}
      {cardView && !LIVE_CARD_KINDS.has(msg.type) && isDoorCodeMessage(msg) && (
        <MessageCard msg={msg} meId={currentUserId} meName={currentUserName} />
      )}
      {cardView && LIVE_CARD_KINDS.has(msg.type) && msg.type !== MEETUP_KIND && msg.type !== BILL_KIND && (
        <MessageCard msg={row.cardMsg ?? msg} meId={currentUserId} meName={currentUserName} />
      )}
      {/* Показові віджети робочої ОС — коротка картка в ОБОХ збірках: живих
          даних у них немає, а демо-начинку (дошка, RACI, «Wasm»…) віднято.
          Виняток — документ на компʼютері: у нього є справжній вид
          (CanvasCardEmbed нижче) і справжній виробник («У чат» з документа). */}
      {isWorkCard(msg) && <WorkCard msg={row.cardMsg ?? msg} />}
      {THEATRE_CARD_KINDS.has(msg.type) && !isWorkCard(msg) && (__PHONE_BUILD__ || !(msg.type === 'widget:canvas' && msg.canvasData)) && (
        <TheatreCard msg={msg} />
      )}

      {/* Переказ, купюра, відбиток файла — поля payment/seal з мосту. */}
      {hasSolanaCard(msg) && <SolanaCard msg={msg} />}

      {/* 3. CHART MESSAGE */}
      {msg.type === 'chart' && msg.chartData && (
        <div className="w-full max-w-full min-w-0 overflow-hidden">
          {/* Очікування каже про себе НА ЕКРАНІ. Порожній fallback
              лишав би на місці діаграми діру без пояснення — на
              повільному каналі це читається як зламаний лист. */}
          <Suspense
            fallback={
              <div className="flex items-center gap-2 py-3 text-caption text-ink-3">
                <Loader2 className="w-3.5 h-3.5 animate-spin" strokeWidth={1.75} />
                <span>{t('messageEmbeds.diagram')}</span>
              </div>
            }
          >
            <ChartEmbed data={msg.chartData} />
          </Suspense>
        </div>
      )}

      {/* 4. TASK LIST MESSAGE */}
      {/* 4. Список справ — у MessageCard вище. */}

      {/* 5. MULTI-QUOTE SYNTHESIS MESSAGE */}
      {msg.type === 'multi-quote' && msg.multiQuoteData && (
        <div className="w-full max-w-full min-w-0 overflow-hidden">
          <MultiQuoteEmbed data={msg.multiQuoteData} isSelf={isSelf} />
        </div>
      )}

      {/* 6. UNIFIED VOICE MESSAGE (ISOLATED AUDIO ENGINE WITH 120 FPS & SEEK) */}
      {msg.type === 'voice' && msg.voiceData && (
        <UnifiedVoiceBubble
          msg={msg}
          chatSearchQuery={searchQuery}
          isActiveSearchMatch={isActiveSearchMatch}
        />
      )}

      {/* 6.5 VIDEO NOTE MESSAGE (CIRCULAR TELEGRAM-STYLE BUBBLE) */}
      {msg.type === 'video-note' && msg.videoNoteData && (
        <div className="w-[224px] min-w-[224px] min-h-[224px] shrink-0 select-none overflow-hidden flex items-center justify-center">
          <VideoNoteBubble msg={msg} />
        </div>
      )}

      {/* 7. STICKER MESSAGE (ZERO LATENCY NATIVE RENDERING) */}
      {msg.type === 'sticker' && msg.stickerData && stickerDef(msg.stickerData.packId, msg.stickerData.id) && (
        <StickerView packId={msg.stickerData.packId} id={msg.stickerData.id} emoji={msg.stickerData.emojiAlt || '🙂'} />
      )}
      {msg.type === 'sticker' && msg.stickerData && !stickerDef(msg.stickerData.packId, msg.stickerData.id) && (
        <div className="py-1 select-none">
          <img
            src={msg.stickerData.url}
            alt={msg.stickerData.emojiAlt || msg.stickerData.title || t('messageEmbeds.sticker')}
            className="w-32 h-32 object-contain pointer-events-none transition-transform "
            loading="lazy"
          />
        </div>
      )}

      {/* 7. Опитування — у MessageCard вище. */}

      {/* 8. SPLIT BILL MESSAGE — стара картка (PH-BILL), лише для читання.
          Перемикач «Оплачено» з дотику прибрано (збірка 18): оплату тут ніщо
          не перевіряло, і дотик ставив «Оплачено» лише в себе. Нові рахунки —
          `widget:bill`, де оплачене бачить лише мережа. */}
      {msg.type === 'split-bill' && msg.splitBillData && <LegacyBillCard msg={msg} meId={currentUserId} meName={currentUserName} />}

      {/* 9. LOCATION MESSAGE */}
      {msg.type === 'location' && msg.locationData && (
        <PlaceCard
          testId="card-place"
          title={msg.locationData.name}
          status={[msg.locationData.address, msg.locationData.distance, msg.locationData.walkingTime].map((x) => x?.trim()).filter(Boolean).join(' · ')}
          action={{
            label: t('messageCard.open'),
            onClick: () => {
              soundFx.playTap();
              actions.openLocation(msg.locationData!);
            },
          }}
        />
      )}

      {/* 9b. ТОЧКА «Я ТУТ» — вимір із пристрою, не картка місця */}
      {msg.type === 'geo:point' && msg.geoPoint && (
        <GeoPointBubble
          point={msg.geoPoint}
          viaMailbox={msg.transport === 'mailbox'}
          isSelf={isSelf}
        />
      )}
      {msg.type === 'geo:live' && msg.liveLocation && (
        <LiveLocationBubble location={msg.liveLocation} isSelf={isSelf} />
      )}

      {/* 9c. КАДР МЕША — маршрут, місце або чесне «не прочитали». */}
      {(() => {
        const card = readMessageCard(msg);
        return card ? <PeerWireBubble wire={card} /> : null;
      })()}

      {/* 10. CODE MESSAGE */}
      {msg.type === 'code' && msg.codeData && (
        <div className="space-y-1.5 pt-1">
          <div className="flex items-center justify-between text-caption text-ink-3 ">
            <span>
              <HighlightedText
                text={msg.codeData.title || msg.codeData.language}
                query={searchQuery}
              />
            </span>
            <button
              onClick={() => actions.copyCode(msg.codeData!.code, msg.id)}
              className="touch-floor hover:underline flex items-center gap-1 text-ink-2"
            >
              <Copy className="w-3 h-3" strokeWidth={1.75} />
              <span>{copied ? t('messageEmbeds.copied') : t('messageEmbeds.copy')}</span>
            </button>
          </div>
          <pre className="p-3 bg-canvas text-ink-2 text-xs rounded-control overflow-x-auto select-text ">
            <code>{msg.codeData.code}</code>
          </pre>
        </div>
      )}

      {/* 10. WORK OS: LIVE CANVAS EMBED */}
      {!__PHONE_BUILD__ && (msg.type === 'widget:canvas' || msg.canvasData) && msg.canvasData && (
        <div className="w-full max-w-full min-w-0 overflow-hidden pt-1">
          <CanvasCardEmbed
            data={msg.canvasData}
            isSelf={isSelf}
            onOpenSplit={(_doc) => {
              actions.openCanvasSplit();
            }}
            onUpdate={(updated) => {
              useMessengerStore.getState().updateMessage(msg.id, { canvasData: updated });
            }}
          />
        </div>
      )}

      {/* 11a. ВКЛАДЕННЯ З НАСКРІЗНИМ КЛЮЧЕМ — фото і файли від людей.
           Демонстраційні картки нижче лишаються для показової стрічки:
           у них є готовий url і немає чого розшифровувати.
           Пачка (кілька файлів разом) — ОДНА сітка на позиції
           першого елемента; решта елементів уже не дійшли сюди —
           їх рядок обірвано раніше в .map(), і сітка малює всіх. */}
      {msg.mediaBatch && msg.mediaBatch.total > 1 && msg.mediaBatch.index === 0 ? (
        <MediaBatchGrid
          items={row.batchItems ?? [msg]}
          isSelf={Boolean(isSelf)}
          onOpenLightbox={actions.openLightbox}
        />
      ) : (
        msg.media && (msg.type === 'image' || msg.type === 'file') && (
          <SecureMediaBubble
            msg={msg}
            isSelf={Boolean(isSelf)}
            onOpenLightbox={actions.openLightbox}
          />
        )
      )}

      {/* 11. FILE MESSAGE */}
      {msg.type === 'file' && msg.fileData && (
        <div className="p-3 rounded-control flex items-center justify-between gap-3 border mt-1 bg-surface border-line ">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="p-2 bg-raised text-ink-2 rounded-control shrink-0">
              <FileText className="w-4 h-4" strokeWidth={1.75} />
            </div>
            <div className="min-w-0">
              <p className="font-semibold text-xs truncate text-ink">
                <HighlightedText
                  text={msg.fileData.name}
                  query={searchQuery}
                  activeMatch={isActiveSearchMatch}
                />
              </p>
              <p className="text-caption text-ink-3">{msg.fileData.size} · {msg.fileData.extension.toUpperCase()}</p>
            </div>
          </div>

          <button
            onClick={() => {
              soundFx.playTap();
              actions.toast(t('messageEmbeds.downloading', { name: msg.fileData!.name }));
            }}
            className="touch-floor p-2 rounded-control transition-colors shrink-0 bg-raised hover:bg-raised text-ink-2"
            title={t('messageEmbeds.downloadFile')}
          >
            <Download className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
      )}

      {/* 12. IMAGE MESSAGE */}
      {msg.type === 'image' && msg.imageData && (
        <div className="space-y-1 pt-1">
          <img
            src={msg.imageData.url}
            alt="chat image"
            onClick={() => {
              soundFx.playTap();
              actions.openLightbox(msg.imageData!.url, msg.imageData?.caption);
            }}
            className="w-full rounded-control max-h-64 object-cover cursor-pointer hover:opacity-95 transition-opacity"
          />
          {msg.imageData.caption && (
            <p data-image-caption className="px-1 pt-0.5 text-body">
              <HighlightedText
                text={msg.imageData.caption}
                query={searchQuery}
              />
            </p>
          )}
        </div>
      )}

    </>
  );
};
