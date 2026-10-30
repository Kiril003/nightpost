/**
 * Дії над листом (довгий дотик, правий клік, «⋯» на панелі наведення), макет 05:
 * лист піднято над запоною, над ним — смуга реакцій, під ним — меню.
 *
 * Рендериться ПОРТАЛОМ у body: усередині колонки розмови композер лежав би
 * зверху й «Видалити» опинялося б під смугою вводу (A52, 25.09).
 */
import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Copy, Reply, Forward, Pencil, Pin, Trash2, Flag, Plus, MoreHorizontal, Users, CalendarPlus, HandCoins, TextSelect, Quote, CircleCheck, ListTodo } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { ReactionGlyph } from '../glyphs/ReactionGlyph';
import { QUICK_GLYPH_IDS, glyphById } from '../glyphs/reactionGlyphs';
import type { Chat, Message } from '../../../types/messenger';
import { useEscapeClose } from '../../../hooks/useEscapeClose';
import { registerPhoneLayer } from '../../../stores/phoneLayerStore';
import { soundFx } from '../../../utils/messengerSound';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceStore } from '../../../stores/spaceStore';
import { isPhoneShell } from '../phoneShell';
import { letterPreview } from './feedParts';
import { actionFor, hintWords, letterText, runAction } from '../../../services/letterActions';
import { addNearReminder } from '../../../services/letterReminders';
import { notifyRemindersChanged, openRemindersSheet } from '../../../services/remindersBus';
import { useUIStore } from '../../../stores/uiStore';
import { listenLiving, useLiving } from '../../../services/livingPulse';
import { canForkPlan, forkPlan } from '../../../services/forkPlan';
import { openMeetupAsk } from '../../../services/nativeMeetupDraft';
import { canSnoozeNear, nearPeerOf } from '../../../services/nearSnooze';
import { askSolana, canSeal, fileOf, solanaPeerOf } from '../../../services/solanaActions';
import { SAVED_NOTES_ID } from '../../../services/messengerApi';
import { canStillEdit, editableKind } from '../../../services/editWindow';
import { plainSnippet } from '../chatListFormat';
import { plainText } from '../../../services/plainText';
import { isWorkGroup, parseTag } from '../../../services/workEvents';
import { openWork } from '../../../services/workBus';

import { useTranslation } from '../../../i18n/useTranslation';
import { isAssistantOnlyKind } from '../../../services/composerCards';
export interface MessageSheetHandlers {
  reply: (m: Message, quoted?: string) => void;
  copyText: (text: string) => void;
  forward: (m: Message) => void;
  edit: (m: Message) => void;
  react: (msgId: string, emoji: string) => void;
  openReactionPicker: (msgId: string) => void;
  speak: (m: Message) => void;
  openCanvas: () => void;
  toast: (text: string) => void;
  translate: (m: Message) => void;
  summarize: (m: Message) => void;
  actionItems: (m: Message) => void;
  togglePin: (msgId: string) => void;
  save: (m: Message) => void;
  select: (msgId: string) => void;
  inspect: (m: Message) => void;
  remove: (m: Message) => void;
  report?: (m: Message) => void;
}

interface Props {
  msg: Message;
  currentChat?: Chat;
  currentUserId: string;
  speaking: boolean;
  onClose: () => void;
  on: MessageSheetHandlers;
}

const QUICK = QUICK_GLYPH_IDS.map((id) => glyphById(id)!.emoji);
const PICK_SETTLE_MS = 220;
const reducedMotion = (): boolean => {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
};
const EDGE = 16;
const ABOVE = 68;

export const MessageActionSheet: React.FC<Props> = ({ msg: m, currentChat, currentUserId, speaking, onClose, on }) => {
  const { t } = useTranslation();
  const [menuMore, setMenuMore] = useState(false);
  const [picking, setPicking] = useState(false);
  const [landed, setLanded] = useState('');
  const settleRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (settleRef.current) clearTimeout(settleRef.current); }, []);
  const liftedRef = useRef<HTMLParagraphElement>(null);
  const pickedRef = useRef('');
  const stackRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left?: number; right?: number } | null>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`msg-act-${m.id}`, true, () => closeRef.current()), [m.id]);
  useEffect(() => listenLiving(), []);
  useEscapeClose(true, onClose);
  const pulse = useLiving((s) => s.pulse);
  const peer = nearPeerOf(m, currentChat, currentUserId);
  const said = m.isDeleted ? '' : letterText(m);
  const derived = said ? actionFor(said, m.sentAt ? new Date(m.sentAt) : new Date()) : null;
  const done = onClose;
  const mine = m.senderId === currentUserId || Boolean(m.isSelf);
  const preview = letterPreview(m);

  useLayoutEffect(() => {
    const rowEl = document.getElementById(`message-${m.id}`);
    const el = rowEl?.querySelector('.msg-bubble-fluid') ?? rowEl;
    const r = el?.getBoundingClientRect();
    if (!r || r.height === 0) return setPos(null);
    const h = stackRef.current?.offsetHeight ?? 0;
    const top = Math.max(EDGE, Math.min(r.top - ABOVE, window.innerHeight - h - EDGE));
    setPos(
      m.isSelf
        ? { top, right: Math.max(EDGE, window.innerWidth - r.right) }
        : { top, left: Math.max(EDGE, Math.min(r.left, window.innerWidth - EDGE - (stackRef.current?.offsetWidth || 320))) },
    );
  }, [m.id, m.isSelf, menuMore, picking]);

  // Вибране тримається в ref: дотик до рядка меню на Android гасить виділення
  // ще до onClick, і «Копіювати вибране» скопіювало б порожнечу.
  useEffect(() => {
    const el = liftedRef.current;
    if (!picking || !el) return;
    const sel = window.getSelection();
    if (sel) {
      const range = document.createRange();
      range.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(range);
    }
    pickedRef.current = el.textContent ?? '';
    const track = () => {
      const s = window.getSelection();
      if (!s || s.rangeCount === 0 || s.isCollapsed) return;
      if (!el.contains(s.anchorNode) || !el.contains(s.focusNode)) return;
      pickedRef.current = s.toString();
    };
    document.addEventListener('selectionchange', track);
    return () => document.removeEventListener('selectionchange', track);
  }, [picking]);
  const picked = () => pickedRef.current.trim();


  const row = (label: string, run: () => void, Icon?: LucideIcon, disabled = false, danger = false) => (
    <button
      key={label}
      type="button"
      disabled={disabled}
      onClick={() => {
        if (disabled) return;
        soundFx.playTap();
        run();
        done();
      }}
      className={`w-full h-12 px-4 flex items-center gap-3.5 text-left text-body ${danger ? 'text-danger' : 'text-ink'} transition-colors duration-quick ${
        disabled ? 'opacity-40 cursor-not-allowed' : 'active:bg-overlay hover:bg-overlay'
      }`}
    >
      {Icon ? (
        <Icon className={`w-6 h-6 shrink-0 ${danger ? 'text-danger' : 'text-ink-2'}`} strokeWidth={1.75} aria-hidden="true" />
      ) : (
        <span className="w-6 shrink-0" aria-hidden="true" />
      )}
      <span className="truncate">{label}</span>
    </button>
  );

  return createPortal(
    <div
      className="fixed inset-0 z-[1200] font-sans select-none [-webkit-touch-callout:none]"
      onClick={done}
      onContextMenu={(e) => {
        if (picking && liftedRef.current?.contains(e.target as Node)) return;
        e.preventDefault();
      }}
    >
      <div aria-hidden="true" className="absolute inset-0 f-scrim f-layer-scrim-in" />
      <div
        ref={stackRef}
        data-msg-stack
        data-anchored={pos ? 'true' : 'false'}
        style={pos ? { top: pos.top, left: pos.left, right: pos.right } : undefined}
        className={`absolute flex flex-col gap-3 pointer-events-none bg-transparent border-0 shadow-none max-h-[calc(100vh-32px)] overflow-y-auto no-scrollbar f-pop-in ${
          m.isSelf ? 'items-end' : 'items-start'
        } ${
          pos ? (m.isSelf ? 'origin-top-right' : 'origin-top-left') : m.isSelf ? 'origin-bottom-right' : 'origin-bottom-left'
        } ${pos ? 'max-w-[calc(100vw-32px)]' : 'left-4 right-4 bottom-[calc(var(--sab)+16px)]'}`}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('messageActionSheet.actions')}
      >
        {/* Смайли є і на телефоні: довгий дотик — єдина дотикова дорога до реакцій. */}
        <div data-msg-reactions-bar className="pointer-events-auto shrink-0 h-14 px-1.5 rounded-[24px] bg-surface flex items-center">
          {QUICK.map((emoji) => (
            <button
              key={emoji}
              type="button"
              data-picked={landed === emoji || undefined}
              onClick={() => {
                if (landed) return;
                on.react(m.id, emoji);
                if (reducedMotion()) return done();
                // Вибір осідає (TG-look 4): смайл підстрибує й сідає, тоді меню йде.
                setLanded(emoji);
                settleRef.current = setTimeout(done, PICK_SETTLE_MS);
              }}
              className={`w-11 h-11 min-w-[44px] min-h-[44px] rounded-full flex items-center justify-center leading-none active:bg-overlay hover:bg-overlay transition-colors duration-quick${landed === emoji ? ' ph-pick bg-accent-soft' : ''}`}
              aria-label={t('messageActionSheet.reaction', { emoji })}
            >
              <ReactionGlyph value={emoji} size={28} play />
            </button>
          ))}
          <button
            type="button"
            onClick={() => {
              soundFx.playTap();
              on.openReactionPicker(m.id);
              done();
            }}
            className="w-11 h-11 min-w-[44px] min-h-[44px] flex items-center justify-center"
            title={t('messageActionSheet.moreEmoji')}
            aria-label={t('messageActionSheet.moreEmoji')}
          >
            <span className="w-8 h-8 rounded-full bg-ink/[.08] text-ink-2 flex items-center justify-center">
              <Plus className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
            </span>
          </button>
        </div>

        <div
          data-msg-lifted
          className={`pointer-events-auto shrink-0 max-w-[min(85vw,360px)] px-3 py-2 rounded-bubble bg-raised text-ink ${m.isSelf ? 'rounded-br-[8px]' : 'rounded-bl-[8px]'}`}
        >
          {!m.isSelf && m.senderName && (
            <span className="block text-footnote font-medium text-ink-2 truncate">{m.senderName}</span>
          )}
          {picking ? (
            <p
              ref={liftedRef}
              data-testid="msg-lifted-text"
              className="text-body break-words whitespace-pre-wrap max-h-[50vh] overflow-y-auto select-text [-webkit-touch-callout:default] [-webkit-user-select:text]"
            >
              {plainSnippet(m.text ?? '')}
            </p>
          ) : (
            <p className="text-body line-clamp-6 break-words">{preview}</p>
          )}
          <span className="block text-right text-caption tnum text-ink-3">{m.timestamp}</span>
        </div>

        {picking ? (
        <div data-msg-menu className="pointer-events-auto shrink-0 min-w-[220px] max-w-[300px] py-1.5 rounded-bubble bg-surface overflow-hidden">
          {row(t('messageActionSheet.copyPicked'), () => on.copyText(picked() || plainSnippet(m.text ?? '')), Copy)}
          {row(t('messageActionSheet.quotePicked'), () => {
            const part = picked();
            on.reply(m, part ? (part.length > 90 ? part.slice(0, 90) + '…' : part) : undefined);
          }, Quote)}
        </div>
        ) : (
        <div data-msg-menu className="pointer-events-auto shrink-0 min-w-[220px] max-w-[300px] py-1.5 rounded-bubble bg-surface overflow-hidden">
          {row(t('messageActionSheet.reply'), () => on.reply(m), Reply)}
          {!isAssistantOnlyKind(m.type) && row(t('messageActionSheet.forward'), () => on.forward(m), Forward)}
          {m.text && row(t('messageActionSheet.copy'), () => on.copyText(plainText(m.text)), Copy)}
          {mine && editableKind(m) && canStillEdit(m, currentChat?.id === SAVED_NOTES_ID) && row(t('messageActionSheet.edit'), () => on.edit(m), Pencil)}
          {row(
            m.pinShared
              ? t(m.isPinned ? 'messageActionSheet.unpinAll' : 'messageActionSheet.pinAll')
              : t(m.isPinned ? 'messageActionSheet.unpinMine' : 'messageActionSheet.pinMine'),
            () => on.togglePin(m.id),
            Pin,
          )}
          {!m.isDeleted && !!m.text && !parseTag(m.text) && isWorkGroup(currentChat) &&
            row(t('work.makeTask'), () => openWork({ chatId: currentChat!.id, taskFrom: m.id }), ListTodo)}
          {/* Вибір кількох — поруч із «Переслати», а не під «Ще» (msg-x). */}
          {row(t('messageActionSheet.selectMany'), () => on.select(m.id), CircleCheck)}
          {!m.isDeleted &&
            canForkPlan(m, currentChat, currentUserId) &&
            row(
              t('chats47.newPlan'),
              () => {
                const f = forkPlan(m, currentChat, currentUserId);
                openMeetupAsk({ kind: 'meetup', args: { people: f.people, place: f.place, at: f.at }, chatId: currentChat!.id });
              },
              CalendarPlus,
            )}
          {/* Дія, справді виведена з листа (номер посилки, телефон, час) — тут,
              на довгому дотику, а не карткою в стрічці (§7.4, AUDIT №2). */}
          {derived &&
            currentChat &&
            row(hintWords(derived).card, () => {
              void runAction(derived, { who: m.senderName || '', text: said, chatId: currentChat.id });
            })}
          {/* Печатка файла в мережі — лише відбиток (docs/solana/PAYMENT_CARD_SPEC.md). */}
          {canSeal(m, currentChat) &&
            row(t('messageActionSheet.seal'), () => askSolana({ kind: 'seal', peer: solanaPeerOf(currentChat)!, msg: m }))}
          {canSeal(m, currentChat) &&
            row(t('money48.ask'), () => {
              const chatId = currentChat!.id;
              const peerOf = solanaPeerOf(currentChat)!;
              void fileOf(m)
                .then((file) => askSolana({ kind: 'group', chatId, file, peer: { id: peerOf.id, name: peerOf.name } }))
                .catch(() => {});
            }, HandCoins)}
          {row(t('messageActionSheet.delete'), () => on.remove(m), Trash2)}
          {!mine &&
            row(
              t('messageActionSheet.report'),
              () => {
                useMessengerStore.getState().beginReportPick(m.id);
                done();
              },
              Flag,
            )}
          {menuMore ? (
            <>
            {!m.isDeleted &&
              currentChat &&
              Boolean(peer) &&
              (pulse === null || canSnoozeNear(pulse, peer)) &&
              row(
                t('chats14.whenMeet'),
                () => {
                  let added: ReturnType<typeof addNearReminder> | null = null;
                  try {
                    added = addNearReminder({
                      peer: peer!,
                      who: m.senderName || currentChat.title || '',
                      text: said || preview,
                      chatId: currentChat.id,
                    });
                  } catch {
                    added = null;
                  }
                  if (!added) return;
                  notifyRemindersChanged();
                  useUIStore.getState().toast({
                    kind: 'info',
                    message: t('reminderToast.nearSet'),
                    action: {
                      label: t('reminderToast.open'),
                      run: () => {
                        openRemindersSheet();
                        if (isPhoneShell()) useMessengerStore.setState({ activeChatId: '' });
                        (window as unknown as { __phantomOpenTab?: (k: string) => boolean }).__phantomOpenTab?.('all');
                      },
                    },
                  });
                },
                Users,
                !canSnoozeNear(pulse, peer),
              )}
              {currentChat &&
                row(t('messageActionSheet.thread'), () => useSpaceStore.getState().openThreadView({ chatId: currentChat.id, rootMessageId: m.id }))}
              {m.text &&
                row(t('messageActionSheet.quote'), () => {
                  const snippet = m.text!.length > 90 ? m.text!.slice(0, 90) + '…' : m.text!;
                  on.reply(m, snippet);
                })}
          {m.text && !m.isDeleted && (
            <button
              type="button"
              data-msg-pick
              onClick={() => {
                soundFx.playTap();
                setPicking(true);
              }}
              className="w-full h-12 px-4 flex items-center gap-3.5 text-left text-body text-ink transition-colors duration-quick active:bg-overlay hover:bg-overlay"
            >
              <TextSelect className="w-6 h-6 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
              <span className="truncate">{t('messageActionSheet.selectText')}</span>
            </button>
          )}
              {row(speaking ? t('messageActionSheet.stopSpeaking') : t('messageActionSheet.speak'), () => on.speak(m))}
              {m.text && !isPhoneShell() &&
                row(t('messageActionSheet.toCanvas'), () => {
                  on.openCanvas();
                  window.dispatchEvent(new CustomEvent('phantom:add-to-canvas', { detail: { text: m.text, type: 'text' } }));
                  on.toast(t('messageActionSheet.addedToCanvas'));
                })}
              {/* AI Translation */}
              {m.text && row(t('messageActionSheet.translate'), () => on.translate(m))}
              {/* AI Key Takeaway */}
              {m.text && row(t('messageActionSheet.summarize'), () => on.summarize(m))}
              {/* AI Action Tasks */}
              {m.text && row(t('messageActionSheet.actionItems'), () => on.actionItems(m))}
              {/* «Для всіх» їде дротом (`PeerGroup.Act.Pin`); «в себе» — вище. */}
              {currentChat?.type === 'group' &&
                currentChat.powers?.pin &&
                row(currentChat.pinnedMessage?.id === m.id ? t('messageActionSheet.unpinAll') : t('messageActionSheet.pinAll'), () => {
                  const already = currentChat.pinnedMessage?.id === m.id;
                  void useMessengerStore
                    .getState()
                    .pinGroupMessage(currentChat.id, already ? '' : m.id)
                    .catch((e: unknown) => {
                      on.toast(e instanceof Error ? e.message : t('messageActionSheet.pinFailed'));
                    });
                })}
              {!m.isDeleted && currentChat?.id !== SAVED_NOTES_ID && row(t('saveNotes.action'), () => on.save(m))}
              {row(t('messageActionSheet.details'), () => on.inspect(m))}
            </>
          ) : (
            <button
              type="button"
              onClick={() => {
                soundFx.playTap();
                setMenuMore(true);
              }}
              data-msg-more="closed"
              className="w-full h-12 px-4 flex items-center gap-3.5 text-left text-body text-ink active:bg-overlay hover:bg-overlay transition-colors duration-quick"
            >
              <MoreHorizontal className="w-6 h-6 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
              <span className="truncate">{t('messageActionSheet.more')}</span>
            </button>
          )}
        </div>
        )}
      </div>
    </div>,
    document.body,
  );
};
