/**
 * Рядок стрічки — один лист разом із бульбашкою, станом біля часу, панеллю
 * наведення і рядком реакцій.
 *
 * `React.memo` тут і є суть виправлення. Раніше весь список малювався одним
 * `.map()` у тілі ChatArea поруч із трьома десятками `useState`, тож наведення
 * миші, тік голосу чи вхідний лист проходили по ВСІХ листах розмови. Тепер:
 *   - стан наведення живе в самому рядку — наведення перемальовує один рядок;
 *   - дії приходять стабільним контекстом (`feedActions.ts`), а не новими
 *     замиканнями щокадру;
 *   - рядок отримує готовий `FeedRow` (`feedModel.ts`), що зберігає тотожність,
 *     поки лист і його сусідство не змінились.
 * Вигляд — без змін: розмітка перенесена з ChatArea як була.
 */
import { ReactionGlyph, hasGlyph } from '../glyphs/ReactionGlyph';
import { ReactionBurst } from '../../../stickers/ReactionBurst';
import { stickerBoxOf } from '../../../stickers/stickerBox';
import React, { memo, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { CardMetaContext } from '../kit/cardMeta';
import {
  Pin,
  Reply,
  Check,
  Quote,
  Forward,
  MoreHorizontal,
  SmilePlus,
  GitBranch,
  Timer,
  KeyRound,
} from 'lucide-react';
import { reactionAuthors } from '../../../services/reactionState';
import { Avatar, personTone } from '../Avatar';
import { soundFx } from '../../../utils/messengerSound';
import { HighlightedText } from '../HighlightedText';
import { plainSnippet } from '../chatListFormat';
import { useSpaceStore } from '../../../stores/spaceStore';
import { useMessengerStore } from '../../../stores/messengerStore';
import type { FeedRow } from './feedModel';
import { useFeedActions } from './feedActions';
import { MessageBody } from './MessageBody';
import { MomentQuote } from './MomentQuote';
import { momentQuoteOf } from '../../../services/momentReply';
import { timerLine } from '../../../services/disappearWords';
import { keyChangeLine } from '../../../services/keyChange';
import { CallLineRow } from './CallLineRow';
import { openPeerCheck } from '../../../services/peerWhere';
import { joinFactsFor, joinTag, joinedOf, type JoinTag } from '../../../services/joinTag';
import { IntentTrail } from '../IntentTrail';
import { fitBubble } from './bubbleFit';
import { motionFor, policy, readEnv } from '../../../feel/motionTokens';
import { solanaApi, type SolanaCoin } from '../../../services/solanaActions';
import { BubbleTail, FailedMark, PendingClock, REACTION_ROAD, SavedTick, SentTick, StoredTick, ThreadPill, groupToneKey } from './feedParts';
import { SAVED_NOTES_ID } from '../../../services/messengerApi';
import { CommitmentCard } from './CommitmentCard';
import { isDoorCode } from '../../../services/codeWindow';
import { sendStateOf } from './sendAck';
import { OUTBOX_STACK_GAP_PX, outboxStackOf, type OutboxStackState } from '../../../services/outboxRetryPolicy';

import { useTranslation } from '../../../i18n/useTranslation';
import { tNow } from '../../chat/tNow';
import { useLocalName } from '../../../services/localName';
/** Лишено для сумісності: підказок-карток у стрічці більше немає (AUDIT №2, §7.4). */
export type RowHint = never;

export interface MessageRowProps {
  row: FeedRow;
  chatId?: string;
  chatTitle?: string;
  currentUserId: string;
  currentUserName: string;
  /** Група чи канал — біля чужих листів стоїть аватар. */
  groupish: boolean;
  /** Розмова вдвох — імені над бульбашкою немає. */
  direct: boolean;
  /** Канал: стрічка дописів — без аватара й імені автора, ширший рядок. */
  post?: boolean;
  /** Розмова з PHANTOM: його відповіді — «аркуш» з підписом, а не бульбашка. */
  assistant?: boolean;
  phone: boolean;
  isSelectionMode: boolean;
  isSelected: boolean;
  isHighlighted: boolean;
  isSearchMatch: boolean;
  isActiveSearchMatch: boolean;
  searchQuery?: string;
  /** Лист щойно прийшов — коротка поява (`f-msg-in`). */
  fresh: boolean;
  expanded: boolean;
  translated?: { text: string; lang: string };
  summary?: string;
  actionItems?: string[];
  agentLabel?: string;
  copied: boolean;
  hint?: RowHint;
}

/**
 * Ширина місця під час у кінці останнього рядка (§7.4 «Meta sits inline»):
 * час 12 px tnum ≈ 34, «ред.» ≈ 28, знак (галочка чи годинник) 14 + проміжки. Невидимий
 * розпірник цієї ширини стоїть у кінці тексту — і час або влазить у рядок,
 * або сам переносить себе на новий, як у Telegram.
 */
/**
 * Цитата (мікро №17): смуга 2 тоном автора, імʼя в тоні, тло — той самий тон
 * на 12 %. Свій лист цитує бурштиновою смугою: тон людини — лише людям.
 */
export const quoteSkin = (author: string, me: string, inSelf = false): React.CSSProperties => {
  // У своїй (бурштиновій) бульбашці тон людини не читається — там цитата
  // говорить кольором самої бульбашки (`on-self`).
  if (inSelf) {
    return {
      borderLeftColor: 'var(--ph-on-self)',
      backgroundColor: 'color-mix(in srgb, var(--ph-on-self) 10%, transparent)',
      color: 'var(--ph-on-self)',
    };
  }
  const mine = !author || author === me || author === 'Я' || author === 'Ви' || author === tNow('chatList.you'); // i18n-data: legacy self labels stored on old rows
  const line = mine ? 'var(--ph-text-3)' : personTone(groupToneKey(author)).name;
  return {
    borderLeftColor: line,
    backgroundColor: `color-mix(in srgb, ${line} 12%, transparent)`,
    color: mine ? 'var(--ph-text-2)' : line,
  };
};

export const metaWidth = (edited: boolean, mark: boolean, fanout = false): number =>
  8 + 38 + (edited ? 40 : 0) + (mark ? 20 : 0) + (fanout ? 28 : 0);

const MessageRowImpl: React.FC<MessageRowProps> = ({
  row,
  chatId,
  currentUserId,
  currentUserName,
  groupish: groupishProp,
  direct,
  post = false,
  assistant = false,
  phone,
  isSelectionMode,
  isSelected,
  isHighlighted,
  isSearchMatch,
  isActiveSearchMatch,
  searchQuery,
  fresh,
  expanded,
  translated,
  summary,
  actionItems,
  agentLabel,
  copied,
}) => {
  const actions = useFeedActions();
  const { t } = useTranslation();
  const { msg, isSelf, isFirstInGroup, isLastInGroup, bareBubble, photoOnly, cardBubble, pending, stored, failed, threadCount } = row;
  const senderLabel = useLocalName(isSelf ? null : msg.senderId, msg.senderName || '');
  const joinedSlice = useMessengerStore((s) => {
    const j = joinedOf(msg, chatId, s.chats);
    if (!j) return null;
    const tag = joinTag(j.id, joinFactsFor(chatId, s.chats));
    return `${j.id}\n${j.name}\n${tag ?? ''}`;
  });
  const stackSlice = useMessengerStore((s) => {
    if (msg.status !== 'queued') return 'open';
    const c = s.chats.find((x) => x.id === chatId);
    if (!c) return 'open';
    const k = outboxStackOf(c.messages, msg.id, currentUserId);
    return `${k.state}|${k.stacked ? 1 : 0}|${k.tail ? 1 : 0}|${k.size}`;
  });
  // Панель наведення є лише на ПК; на телефоні рядок не тримає навіть стану.
  const hoverable = !__PHONE_BUILD__ && !phone;
  // Час — на кожній бульбашці з часом або станом; всередині черги — лише на хвості.
  const baseShowMeta = !msg.live && (Boolean(msg.timestamp) || isLastInGroup || Boolean(msg.isEdited) || pending || failed);
  const showMeta = baseShowMeta && (!stackSlice.startsWith('queued|') || stackSlice.split('|')[2] === '1');
  const collapsibleLong = row.density === 'long' && Boolean(msg.text && (msg.text.length > 1500 || (msg.text.split('\n')?.length || 0) >= 20));
  // Звичайний текст — час у кінці останнього рядка; медіа, картки — окремим рядком.
  const inlineMeta = showMeta && !failed && !photoOnly && !cardBubble && !row.cardView && !msg.media && !msg.payDraft && !msg.commitmentCard && Boolean(msg.text) && !collapsibleLong && row.density !== 'emoji-multi';
  // Імʼя автора в групі — усередині звичайної бульбашки (лист D); над карткою чи голим фото — над ними.
  const groupish = groupishProp && !post;
  // Допис каналу — від імені каналу, навіть мій: зліва, на папері, без кольору «моєї» бульбашки.
  const right = isSelf && !post;
  const showSender = !isSelf && isFirstInGroup && !direct && !assistant && !post;
  const stickerBox = bareBubble && !row.cardView ? stickerBoxOf(msg) : 0;
  const shown = useRef(false);
  useEffect(() => {
    shown.current = true;
  }, []);
  const senderInside = !cardBubble && !photoOnly && !bareBubble && !row.cardView;
  const momentQuote = msg.replyTo ? null : momentQuoteOf(msg.text);
  const sendState = isSelf && !cardBubble && !bareBubble && !photoOnly ? sendStateOf(row) : null;
  const outlined = sendState === 'outline';
  const savedNote = isSelf && chatId === SAVED_NOTES_ID && !msg.status && !msg.isDeleted;
  const tailSide = row.tail && !cardBubble && !outlined && !(assistant && !isSelf) ? (right ? 'self' : 'other') : null;
  const [hovered, setHovered] = useState(false);
  const [filling, setFilling] = useState(false);
  const prevSend = useRef(sendState);
  const bubbleRef = useRef<HTMLDivElement>(null);
  const fillRef = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    if (sendState !== 'solid') setFilling(false);
    else if (prevSend.current === 'outline' && !motionFor('fill').end) setFilling(true);
    prevSend.current = sendState;
  }, [sendState]);
  useLayoutEffect(() => {
    if (!filling) return undefined;
    const el = fillRef.current;
    if (typeof el?.animate !== 'function') {
      setFilling(false);
      return undefined;
    }
    const spec = motionFor('fill');
    const anim = el.animate(
      [{ clipPath: 'inset(0 100% 0 0)' }, { clipPath: 'inset(0 0 0 0)' }],
      { duration: spec.ms, easing: spec.easing },
    );
    let live = true;
    void anim.finished.then(
      () => live && setFilling(false),
      () => {},
    );
    return () => {
      live = false;
      anim.cancel();
    };
  }, [filling]);
  useLayoutEffect(() => {
    if (!inlineMeta) return undefined;
    const fit = () => fitBubble(bubbleRef.current);
    fit();
    void document.fonts?.ready.then(fit);
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, [inlineMeta, msg.text, msg.isEdited, msg.status]);

  // Строк зникнення — службовий рядок посередині, не бульбашка й без дій.
  if (msg.timerSeconds !== undefined) {
    return (
      <div id={`message-${msg.id}`} data-testid="timer-line" className="message-row flex justify-center my-3 px-6">
        <span className="ph-on-stage inline-flex items-center gap-1.5 text-center text-footnote text-ink-3">
          <Timer className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden="true" />
          {timerLine(msg.timerSeconds, isSelf, msg.senderName || '')}
        </span>
      </div>
    );
  }

  // Ключ безпеки змінився — сірий рядок посередині й одна дія: перевірити.
  if (msg.keyChange) {
    return (
      <div id={`message-${msg.id}`} data-testid="key-change-line" className="message-row flex flex-col items-center gap-1 my-3 px-6">
        <span className="ph-on-stage inline-flex items-center gap-1.5 text-center text-footnote text-ink-3">
          <KeyRound className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden="true" />
          {keyChangeLine(msg.senderName || '')}
        </span>
        {msg.senderId && (
          <button
            type="button"
            data-testid="key-change-check"
            onClick={() => openPeerCheck(msg.senderId)}
            className="touch-floor-inline min-h-[44px] px-3 text-footnote font-semibold text-accent-text"
          >
            {t('messageRow.check')}
          </button>
        )}
      </div>
    );
  }

  if (msg.callLine) return <CallLineRow id={msg.id} line={msg.callLine} />;

  if (joinedSlice !== null) {
    const [, joinedName, rawTag] = joinedSlice.split('\n');
    const tag = (rawTag || null) as JoinTag | null;
    let tagLabel: string | null = null;
    switch (tag) {
      case 'close':
        tagLabel = t('joinTag.close');
        break;
      case 'verified':
        tagLabel = t('joinTag.verified');
        break;
      case 'newKey':
        tagLabel = t('joinTag.newKey');
        break;
      case null:
        break;
    }
    return (
      <div id={`message-${msg.id}`} data-testid="join-line" className="message-row flex justify-center my-3 px-6">
        <span className="ph-on-stage inline-flex items-center gap-1.5 text-center text-footnote text-ink-3">
          {t('joinTag.joined', { name: joinedName })}
          {tag && tagLabel && (
            <span data-join-tag={tag} className={tag === 'newKey' ? 'text-ink-2' : undefined}>
              {' · '}
              {tagLabel}
            </span>
          )}
        </span>
      </div>
    );
  }

  // Надгробок. Тіла немає ні тут, ні на диску — тож немає ні дій над
  // ним, ні галочок стану: везти вже нічого. Скромно й сіро навмисно:
  // рядок мусить бути видимим, але не претендувати на увагу.
  if (msg.isDeleted && !isDoorCode(msg)) {
    return (
          <div
            id={`message-${msg.id}`}
            data-testid="message-tombstone"
            className={`message-row flex ${right ? 'justify-end' : 'justify-start'} mt-2`}
          >
            <span className="ph-on-stage px-1 text-footnote text-ink-3">{t('messageRow.deleted')}</span>
          </div>
    );
  }

  const [rawState, rawStacked, rawTail, rawSize] = stackSlice.split('|');
  const stackState: OutboxStackState = rawState === 'queued' ? 'queued' : 'open';
  const stacked = rawStacked === '1';
  const tail = rawTail === '1';
  const size = Number(rawSize) || 0;
  const spec = policy(readEnv()).settle;
  // Картка листа забирає час і ✓✓ у свій рядок мети (одна анатомія); не забрала — чип під нею.
  const [metaOwner, setMetaOwner] = useState<string | null>(null);
  const claimMeta = useCallback((id: string) => setMetaOwner((o) => o ?? id), []);
  const releaseMeta = useCallback((id: string) => setMetaOwner((o) => (o === id ? null : o)), []);
  const metaInCard = bareBubble && row.cardView && !msg.payment && !msg.seal;
  const cardMeta = metaInCard
    ? {
        owner: metaOwner,
        claim: claimMeta,
        release: releaseMeta,
        node: (
          <>
            {msg.isEdited && <span>{t('messageRow.edited')}</span>}
            <span className="tnum">{msg.timestamp}</span>
            {isSelf && msg.status === 'sent' && <SentTick msg={msg} />}
            {stored && <StoredTick />}
            {pending && <PendingClock onTap={msg.status === 'queued' ? actions.flushQueue : undefined} />}
            {failed && <FailedMark msgId={msg.id} reason={msg.failReason} />}
          </>
        ),
      }
    : null;

  return (
    <CardMetaContext.Provider value={cardMeta}>
      <div
        id={`message-${msg.id}`}
        data-msg-id={msg.id}
        data-selected={isSelected || undefined}
        onClickCapture={
          isSelectionMode
            ? (e) => {
                if ((e.target as HTMLElement).closest('[data-select-box]')) return;
                e.preventDefault();
                e.stopPropagation();
                soundFx.playTap();
                if (e.shiftKey) actions.rangeTo(msg.id);
                else actions.toggleSelect(msg.id);
              }
            : undefined
        }
        data-live={msg.live}
        data-state={stackState}
        data-outbox-stacked={stacked || undefined}
        onMouseEnter={hoverable ? () => setHovered(true) : undefined}
        onMouseLeave={hoverable ? () => setHovered(false) : undefined}
        style={{
          ...(stacked ? { marginTop: OUTBOX_STACK_GAP_PX } : undefined),
          transition: spec.end ? 'none' : `margin-top ${spec.ms}ms ${spec.easing}`,
        }}
        className={`message-row flex items-end gap-2 ${fresh ? (isSelf ? 'f-msg-send' : 'f-msg-in') : ''} ${right ? 'justify-end' : 'justify-start'} ${stacked ? '' : row.isNewSenderTurn || row.showDateDivider ? 'mt-2' : 'mt-0.5'} group relative ${
  isActiveSearchMatch || isHighlighted || isSearchMatch ? 'bg-accent-soft rounded-card p-0.5 transition-colors duration-standard' : isSelected ? 'bg-overlay rounded-card' : ''
  }`}
      >
        {/* Selection Checkbox */}
        {(isSelectionMode || isSelected) && (
          <button
            type="button"
            data-select-box
            aria-pressed={isSelected}
            onPointerDown={(e) => {
              if (!(e.button > 0) && !e.shiftKey) actions.rangeDrag(msg.id);
            }}
            onClick={(e) => {
              soundFx.playTap();
              if (e.shiftKey) actions.rangeTo(msg.id);
              else actions.toggleSelect(msg.id);
            }}
            style={{ touchAction: 'none' }}
            className={`touch-floor self-center shrink-0 w-11 h-11 min-h-0 min-w-0 flex items-center justify-center ${right ? 'mr-auto' : ''}`}
            aria-label={t('messageRow.select')}
          >
            <span
              aria-hidden
              className={`w-[22px] h-[22px] rounded-full border-2 flex items-center justify-center transition-colors duration-quick ${
                isSelected ? 'bg-ink border-ink text-canvas' : 'border-line-strong'
              }`}
            >
              {isSelected && <Check className="w-3.5 h-3.5" strokeWidth={3} />}
            </span>
          </button>
        )}

        {/* Message Bubble & Reactions Column */}
        <div data-post={post ? '' : undefined} className={`flex flex-col ${right ? 'items-end' : 'items-start'} min-w-0 ${(assistant && !isSelf) || (post && !right) ? 'max-w-[92%] md:max-w-[620px]' : 'max-w-[78%] md:max-w-[560px]'} relative group/msg`}>
          {/* Аватар живе в одному рядку з бульбашкою, а не з усією колонкою:
              інакше рядок реакцій тягнув би його нижче за край бульбашки. */}
          {showSender && !senderInside && (
            <p
              data-msg-sender
              onClick={() => actions.selectMember(msg.senderName)}
              className={`mb-1 ${groupish ? 'pl-12' : 'pl-3'} text-footnote font-semibold cursor-pointer w-fit max-w-full truncate`}
              style={{ color: personTone(groupToneKey(msg.senderName)).name }}
            >
              <HighlightedText text={senderLabel} query={searchQuery} activeMatch={isActiveSearchMatch} />
            </p>
          )}
          <div className="flex items-end gap-2 max-w-full min-w-0">
          {!isSelf && groupish && (
            <div className="w-7 h-7 shrink-0 mb-0.5">
              {isLastInGroup ? (
                <div
                  onClick={() => actions.selectMember(msg.senderName)}
                  className="cursor-pointer transition-transform shrink-0"
                  title={t('messageRow.viewProfile', { name: senderLabel })}
                >
                  <Avatar src={msg.senderAvatar} name={senderLabel} tone={groupToneKey(msg.senderName)} className="w-7 h-7 text-caption" />
                </div>
              ) : (
                <div className="w-7 h-7" />
              )}
            </div>
          )}

          {/* Message Bubble Container */}
          <div
            ref={bubbleRef}
            data-send-state={sendState ?? undefined}
            onMouseUp={msg.live ? undefined : (e) => actions.mouseUp(msg, e)}
            onTouchStart={msg.live ? undefined : (e) => actions.touchStart(msg, e)}
            onTouchMove={msg.live ? undefined : (e) => actions.touchMove(msg, e)}
            onTouchEnd={msg.live ? undefined : () => actions.touchEnd(msg)}
            onContextMenu={(e) => {
              e.preventDefault();
              if (msg.live) return;
              soundFx.playTap();
              actions.openMenu(msg);
            }}
            // min-w-0: бульбашка — флекс-елемент РЯДКА (аватар + бульбашка,
            // рядок вище). Без цього браузер рахує мінімальну ширину елемента
            // від НЕРОЗБИТОГО слова (довге посилання, хеш, імʼя файла без
            // пробілів) і розтягує рядок, а з ним і всю стрічку, замість
            // застосувати `break-words`/`break-all`, які вже стоять на тексті
            // всередині. `max-w-full` тут цього не лікує: то стеля згори, а
            // рушиться підлога — типова халепа flex-контейнера без min-w-0.
            className={`msg-bubble-fluid min-w-0 relative ${tailSide ? 'msg-has-tail ' : ''}${filling ? 'isolate ' : ''}select-text cursor-pointer sm:cursor-default ${row.contourClass} ${row.densityClass} ${
              bareBubble || (assistant && !isSelf && (msg.place || msg.route || msg.geoPoint))
                ? 'bg-transparent! border-transparent! shadow-none! p-0!'
                : photoOnly
                ? 'msg-bubble-photo'
                : right && !cardBubble
                ? `msg-bubble-self${outlined || filling ? ' bg-transparent! border border-line-strong!' : ''}`
                : assistant && !isSelf
                ? 'msg-bubble-other msg-bubble-phantom'
                : 'msg-bubble-other'
            } ${isSelected ? ' z-10' : ''} ${
              isActiveSearchMatch ? ' ' : ''
            }`}
          >
            {filling && right && (
              <span
                data-send-fill
                aria-hidden
                ref={fillRef}
                className="msg-bubble-self absolute inset-0 rounded-[inherit] pointer-events-none -z-10"
              />
            )}
            {tailSide && <BubbleTail side={tailSide} />}
            {outlined && !failed && msg.status !== 'failed' && (
              <span className="sr-only">{t('sendAck.awaitingAck')}</span>
            )}
            {/* Swipe to Reply Visual Indicator (керований через direct DOM для 120 FPS без лагів) */}
            <div
              className="msg-swipe-reply-indicator absolute top-1/2 -translate-y-1/2 -right-11 w-8 h-8 rounded-full bg-raised text-ink flex items-center justify-center pointer-events-none transition-colors duration-150 data-[armed=true]:bg-accent data-[armed=true]:text-on-accent"
              style={{ display: 'none', opacity: 0 }}
            >
              <Reply className="w-4 h-4" strokeWidth={1.75} />
            </div>
            {showSender && senderInside && (
              <p
                data-msg-sender
                onClick={() => actions.selectMember(msg.senderName)}
                className="text-footnote font-semibold cursor-pointer w-0 min-w-full truncate"
                style={{ color: personTone(groupToneKey(msg.senderName)).name }}
              >
                <HighlightedText text={senderLabel} query={searchQuery} activeMatch={isActiveSearchMatch} />
              </p>
            )}
            {/* Top Pinned Tag Pill */}
            {msg.isPinned && (
              <div className="flex items-center gap-1 text-caption text-ink-3 mb-1 w-fit">
                <Pin className="w-3.5 h-3.5" strokeWidth={1.75} />
                <span>{t('messageRow.pinned')}</span>
              </div>
            )}

          {/* Replying-to / Quoted Preview Header */}
          {msg.replyTo && (
            <div
              onClick={() => {
                soundFx.playTap();
                if (msg.replyTo!.id) actions.jumpTo(msg.replyTo!.id);
              }}
              data-testid="msg-quote"
              className="mb-1 py-1.5 pl-2.5 pr-3 rounded-control border-l-2 text-ink cursor-pointer transition-colors duration-quick"
              style={quoteSkin(msg.replyTo.senderName, currentUserName, right && !cardBubble)}
            >
              {msg.replyTo.quotes && msg.replyTo.quotes.length > 1 ? (
                /* Multi-message quotes preview */
                <div>
                  <p className="text-footnote font-semibold text-ink flex items-center gap-1.5">
                    <Quote className="w-3.5 h-3.5" strokeWidth={1.75} />
                    <span>{t('messageRow.quotes', { n: msg.replyTo.quotes.length })}</span>
                  </p>
                  <div className="mt-0.5">
                    {msg.replyTo.quotes.map((q) => (
                      <div key={q.id} className="text-footnote truncate flex items-center gap-1 text-ink-2">
                        <span className="font-semibold shrink-0">{q.senderName}:</span>
                        <span className="truncate">{plainSnippet(q.text)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : msg.replyTo.quoteSelectedText ? (
                /* Partial fragment quote */
                <div>
                  <p className="text-footnote font-semibold text-ink flex items-center gap-1.5">
                    <Quote className="w-3.5 h-3.5" strokeWidth={1.75} />
                    <span>{t('messageRow.quoteBy', { name: msg.replyTo.senderName })}</span>
                  </p>
                  <p className="text-footnote text-ink-2 truncate">
                    «{plainSnippet(msg.replyTo.quoteSelectedText)}»
                  </p>
                </div>
              ) : (
                /* Standard single message reply */
                <div>
                  <p className="text-footnote font-semibold truncate" style={{ color: quoteSkin(msg.replyTo.senderName, currentUserName, right && !cardBubble).color }}>
                    <HighlightedText
                      text={msg.replyTo.senderName}
                      query={searchQuery}
                    />
                  </p>
                  <p className="truncate text-footnote text-ink-2">
                    <HighlightedText
                      text={plainSnippet(msg.replyTo.text)}
                      query={searchQuery}
                    />
                  </p>
                </div>
              )}
            </div>
          )}

          {!msg.replyTo && momentQuote && (
            <MomentQuote quote={momentQuote} skin={quoteSkin(isSelf ? msg.senderName : currentUserName, currentUserName, right && !cardBubble)} />
          )}

          {/* Forwarded Header Attribution */}
          {msg.forwardFrom && (
            <div className="mb-1 text-footnote text-ink-3 flex items-center gap-1.5">
              <Forward className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} />
              {msg.forwardFrom.chatTitle ? (
                <span>
                  {t('messageRow.forwardedFrom')}
                  <HighlightedText
                    text={msg.forwardFrom.chatTitle}
                    query={searchQuery}
                  />
                  {t('messageRow.forwardedMid')}
                  <HighlightedText
                    text={msg.forwardFrom.senderName}
                    query={searchQuery}
                  />
                  )
                </span>
              ) : (
                <span data-forwarded-by className="truncate">
                  {t('messageRow.forwardedBy')}
                  <HighlightedText text={msg.forwardFrom.senderName} query={searchQuery} />
                </span>
              )}
            </div>
          )}

          <MessageBody
            row={row}
            chatId={chatId}
            currentUserId={currentUserId}
            currentUserName={currentUserName}
            phone={phone}
            searchQuery={searchQuery}
            isActiveSearchMatch={isActiveSearchMatch}
            expanded={expanded}
            translated={translated}
            summary={summary}
            actionItems={actionItems}
            agentLabel={agentLabel}
            copied={copied}
            metaSpace={
              inlineMeta
                ? metaWidth(
                    Boolean(msg.isEdited),
                    (isSelf && msg.status === 'sent') || pending || stored,
                    isSelf && msg.status === 'sent' && typeof msg.fanTotal === 'number',
                  )
                : 0
            }
          />

          {msg.live === 'failed' && (
            <p data-testid="live-cut" className="mt-1 text-footnote text-ink-3">{t('messageRow.liveCut')}</p>
          )}

          {msg.payDraft && !isSelf && (
            <div data-pay-draft className="mt-2 flex items-center justify-between gap-3">
              <span className="text-footnote text-ink-3">{t(msg.payDraft.sent ? 'payDraft.sent' : 'payDraft.open')}</span>
              <button
                type="button"
                data-pay-draft-again
                onClick={(e) => {
                  e.stopPropagation();
                  const d = msg.payDraft!;
                  void solanaApi.pay({ peer_id: d.peerId, token: d.token as SolanaCoin, amount: d.amount }).catch((err: unknown) => {
                    actions.toast(err instanceof Error ? err.message : t('payDraft.again'));
                  });
                }}
                className="touch-floor-inline relative h-9 px-4 rounded-full bg-ink/[.08] text-subhead text-ink shrink-0"
              >
                {t('payDraft.again')}
              </button>
            </div>
          )}

          {msg.commitmentCard && (isSelf ? (msg.commitmentCard.kind === 'offer' || msg.commitmentCard.kind === 'saved') : msg.commitmentCard.kind !== 'offer') && (
            <CommitmentCard card={{ ...msg.commitmentCard, sourceMessageId: msg.commitmentCard.sourceMessageId || msg.id }} />
          )}

          {/* Гілка — рядком усередині бульбашки, як коментарі в Telegram:
              окрема пігулка під листом читалась як ще один лист. */}
          {!bareBubble && !direct && threadCount > 0 && (
            <ThreadPill msgId={msg.id} count={threadCount} chatId={chatId || ''} inside />
          )}

          {/* Час — один раз, на останній бульбашці черги; «ред.» — там, де
              правили. Біля часу — лише знак: ✓, ✓✓ або годинник, поки
              лист не доставлено. Прозорі бульбашки (кружок, одиночний
              смайл) несуть час у рядку під собою. */}
          {!bareBubble && showMeta && (
            <div
              role={stackState === 'queued' && tail && size > 1 ? 'status' : undefined}
              aria-label={stackState === 'queued' && tail && size > 1 ? t('outbox.queuedStack', { n: size }) : undefined}
              className={`msg-meta-row flex items-center justify-end gap-1 select-none ${
                inlineMeta ? 'absolute right-3 bottom-1.5' : photoOnly ? '' : 'mt-0.5 -mb-0.5'
              }`}
            >
              {msg.isEdited && <span>{t('messageRow.edited')}</span>}
              <span className="tnum whitespace-nowrap">{msg.timestamp}</span>
              {isSelf && msg.status === 'sent' && <SentTick msg={msg} />}
              {stored && <StoredTick />}
              {savedNote && <SavedTick />}
              {pending && <PendingClock onTap={msg.status === 'queued' ? actions.flushQueue : undefined} />}
              {failed && <FailedMark msgId={msg.id} reason={msg.failReason} />}
            </div>
          )}

          {/* Compact & Clean Message Hover Bar (Desktop). Тримаємо його
              ПОВНІСТЮ над бульбашкою (bottom-full), інакше нижній край
              панелі накривав перший рядок тексту. */}
          {!__PHONE_BUILD__ && !phone && hovered && !msg.live && (
            <div className={`absolute bottom-full mb-1 ${
  right ? 'right-1' : 'left-1'
  } bg-raised text-ink rounded-card shadow-e2 px-1 py-0.5 flex items-center gap-0.5 z-20 f-fade-in`}>
              {/* Top 3 Quick Emojis */}
              {['❤️', '👍', '🔥'].map((emoji) => (
                <button
                  key={emoji}
                  onClick={() => actions.react(msg.id, emoji)}
                  className="text-subhead p-1 rounded-control hover:bg-overlay transition-colors duration-quick"
                  title={emoji}
                >
                  {emoji}
                </button>
              ))}

              {/* More Emojis */}
              <button
                onClick={() => actions.openReactionPicker(msg.id)}
                className="touch-floor p-1 hover:bg-overlay text-ink-2 hover:text-ink rounded-control transition-colors duration-quick"
                title={t('messageRow.moreReactions')}
              >
                <SmilePlus className="w-4 h-4" strokeWidth={1.75} />
              </button>

              <div className="w-px h-4 bg-line mx-0.5" />

              {/* Reply button */}
              <button
                onClick={() => {
                  soundFx.playTap();
                  actions.reply(msg);
                }}
                className="touch-floor p-1 hover:bg-overlay text-ink-2 hover:text-ink rounded-control transition-colors duration-quick"
                title={t('messageRow.reply')}
              >
                <Reply className="w-4 h-4" strokeWidth={1.75} />
              </button>

              {/* Thread / Branch button */}
              <button
                onClick={() => {
                  soundFx.playTap();
                  useSpaceStore.getState().openThreadView({
                    chatId: chatId || '',
                    rootMessageId: msg.id,
                  });
                }}
                className="touch-floor p-1 hover:bg-overlay text-ink-2 hover:text-ink rounded-control transition-colors duration-quick"
                title={t('messageRow.thread')}
              >
                <GitBranch className="w-4 h-4" strokeWidth={1.75} />
              </button>

              {/* More Menu / Actions Sheet */}
              <button
                onClick={() => {
                  soundFx.playTap();
                  actions.openMenu(msg);
                }}
                className="touch-floor p-1 hover:bg-overlay text-ink-2 hover:text-ink rounded-control transition-colors duration-quick"
                title={t('messageRow.more')}
              >
                <MoreHorizontal className="w-4 h-4" strokeWidth={1.75} />
              </button>
            </div>
          )}
          </div>
          </div>

          {/* Під бульбашкою: час прозорих бульбашок і — єдине слово
              стану — невдала відправка з «Повторити». */}
          {bareBubble && !msg.payment && !msg.seal && (isLastInGroup || failed || row.cardView) && !(metaInCard && metaOwner) && (
            <div
              data-msg-status-line
              data-on-sticker={stickerBox ? 'true' : undefined}
              className={`relative flex items-center text-caption text-ink-3 ${
                stickerBox ? `-mt-6 z-10 pointer-events-none justify-end ${!right && groupish ? 'pl-9' : ''}` : `mt-1 ${right ? 'justify-end' : `justify-start ${groupish ? 'pl-9' : ''}`}`
              }`}
              style={stickerBox && !right ? { width: stickerBox + (groupish ? 36 : 0) } : undefined}
            >
              <span data-msg-meta-chip className="pointer-events-auto inline-flex items-center gap-1 h-6 px-2.5 rounded-full bg-raised">
                <span className="tnum">{msg.timestamp}</span>
                {isSelf && msg.status === 'sent' && <SentTick msg={msg} />}
                {stored && <StoredTick />}
                {pending && <PendingClock onTap={msg.status === 'queued' ? actions.flushQueue : undefined} />}
                {failed && <FailedMark msgId={msg.id} reason={msg.failReason} />}
              </span>
            </div>
          )}

          {/* Питання, що стало Наміром до мого ПК (розд. 07): смуга, стан, відповідь. */}
          {msg.intentId && (
            <div data-msg-intent className={`flex ${right ? 'justify-end' : 'justify-start'}`}>
              <IntentTrail intentId={msg.intentId} />
            </div>
          )}

          {/* Гілка картки стоїть під карткою, як і її час, а не під аватаром. */}
          {bareBubble && !direct && threadCount > 0 && (
            <div data-msg-thread-line className={`flex ${right ? 'justify-end' : `justify-start ${groupish ? 'pl-9' : ''}`}`}>
              <ThreadPill msgId={msg.id} count={threadCount} chatId={chatId || ''} />
            </div>
          )}

          {/* Реакції — рядок пігулок під бульбашкою; у чужих зсунутий
              на ширину аватара, щоб стояти рівно під бульбашкою.

              ПРО РОЗМІР. 21.09 заміряно на ширині A52: пігулка була
              52x48, 54x48 і 48x48 — тобто смайл 12 px сидів у колі
              48 px, удвічі вищому за сам рядок листа. Власник назвав
              це «гігантські овали», і це не смак: `touch-floor` (48/48)
              стояв просто на кнопці, а під ним ще й голий селектор
              `button` у globals.css дає min-width/min-height кожній
              кнопці. Підлога дотику лишається, але тепер вона НЕВИДИМА
              — `touch-floor-inline` малює ціль 44 px псевдоелементом і
              знімає обидві підлоги з самого малюнка. Розмір пігулки
              відтепер задає її вміст, а він міряний від тексту листа. */}
          {msg.reactions && msg.reactions.length > 0 && (
            <div data-testid="msg-reactions" className={`flex flex-wrap items-center gap-1 mt-0.5 z-10 select-none ${
  right ? 'justify-end pr-1' : `justify-start ${groupish ? 'pl-9' : 'pl-1'}`
  }`}>
              {msg.reactions.map((r, idx) => {
                const isUserReacted = r.users.includes(currentUserId);
                // Хто поставив — СЛОВАМИ. До 18.09 тут стояло
                // `r.users.join(', ')`, тобто ключі вузлів дослівно:
                // на питання «хто?» екран відповідав `operator_me,
                // 8f3ac1d0`. Підпис живе і в `aria-label`, бо `title`
                // на дотику не показується НІКОЛИ.
                const who = reactionAuthors(r, currentUserId).join(', ');
                const said = REACTION_ROAD[r.mine ?? 'delivered'];
                return (
                  <button
                    key={idx}
                    onClick={() => actions.react(msg.id, r.emoji)}
                    className={`group/reaction touch-floor-inline ph-land relative h-7 pl-1.5 pr-2 rounded-full flex items-center gap-1 border transition-colors duration-quick ${
  isUserReacted ? said.skin : 'bg-raised border-transparent text-ink-2 hover:bg-overlay'
  }`}
                    title={`${said.say}. ${who}`}
                    aria-label={`${r.emoji}, ${said.say}. ${who}`}
                  >
                    <span className={`text-[14px] leading-none flex items-center ${said.dim ? 'opacity-60' : ''}`}>
                      {hasGlyph(r.emoji) ? (
                        <ReactionGlyph value={r.emoji} size={16} play={r.mine === 'sending'} />
                      ) : (
                        r.emoji
                      )}
                    </span>
                    <span key={r.count} data-reaction-count className="ph-count-tick inline-block text-caption text-ink-2 tnum">{r.count}</span>
                    <ReactionBurst emoji={r.emoji} count={r.count} armed={shown.current} />
                    {/* Знак дороги стоїть на самій пігулці, а не в
                        підказці: три стани мусять бути видні без
                        жодного дотику. */}
                    {said.Icon && (
                      <said.Icon className="w-3 h-3 shrink-0" strokeWidth={1.75} aria-hidden="true" />
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </CardMetaContext.Provider>
  );
};

export const MessageRow = memo(MessageRowImpl);
MessageRow.displayName = 'MessageRow';
