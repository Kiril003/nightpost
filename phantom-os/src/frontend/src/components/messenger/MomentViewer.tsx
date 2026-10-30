/** Перегляд моменту на весь екран (DESIGN_SYSTEM 2.19): дотик праворуч/ліворуч — крок, униз — закрити. */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Eye, MoreVertical, Pin, SendHorizontal, Smile, Users, X } from 'lucide-react';
import { SAVED_NOTES_ID } from '../../services/messengerApi';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { chatKeep } from '../../services/chatKeep';
import { deliveryStatus, messengerApi } from '../../services/messengerApi';
import { loadMedia } from '../../services/messengerMedia';
import { queueRemoval } from '../../services/momentsUndo';
import { CAPTION_MAX, REACTIONS, keepMomentMedia, useMoments, type Moment } from '../../services/moments';
import { clockLabel } from '../../utils/clockFormat';
import { Avatar } from './Avatar';
import { CIRCLE_KEY, CIRCLES, todayTimeLabel, type RealCircle } from './todayFormat';
import { SELF_AUTHOR, chatKind, displayTitle } from './chatListFormat';
import { isMyDevice } from '../../services/peopleIdentity';
import { LAYER_EXIT_MS, isReducedMotion } from '../../feel/motionKit';
import { stickerForEmoji } from '../../stickers/pack';
import { StickerView } from '../../stickers/StickerView';
import { momentReplyBody } from '../../services/momentReply';
import { MomentShare, canShareMoment, momentAsMessage } from './MomentShare';
import { MomentAudienceEdit } from './MomentAudienceEdit';
import type { Chat, Message } from '../../types/messenger';

export function useMomentMedia(m: Moment | null): { url: string | null; failed: boolean } {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const first = m?.media[0];
  const blobId = first?.blobId;
  const ready = first?.state === 'ready';
  useEffect(() => {
    setUrl(null);
    setFailed(false);
    if (!first || !ready) return;
    let live = true;
    loadMedia(first).then(
      (u) => live && setUrl(u),
      () => live && setFailed(true),
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blobId, ready]);
  return { url, failed };
}

export const isVideo = (m: Moment) => Boolean(m.media[0]?.mime.startsWith('video/'));

export interface MomentPerson {
  id: string;
  name: string;
  avatar?: string;
  chatId: string;
}

/** Люди, з якими є пряма розмова, — і лише вони можуть бачити момент. */
export function momentPeople(chats: Chat[]): MomentPerson[] {
  const seen = new Set<string>();
  const out: MomentPerson[] = [];
  for (const c of chats) {
    if (chatKind(c) !== 'person' || isMyDevice(c) || c.archived || !c.peerNodeId || seen.has(c.peerNodeId)) continue;
    seen.add(c.peerNodeId);
    out.push({ id: c.peerNodeId, name: displayTitle(c.title), avatar: c.avatar || undefined, chatId: c.id });
  }
  return out;
}

type T = (key: MessageKey, vars?: Record<string, string | number>) => string;

export const circleLabel = (t: T, name: string) =>
  (CIRCLES as readonly string[]).includes(name) ? t(CIRCLE_KEY[name as RealCircle]) : name;

/** Рядок «хто бачить». Назви кіл бачить лише автор: чужі кола — не наша справа. */
export function audienceLine(t: T, m: Moment): string {
  if (m.mine) {
    switch (m.reach) {
      case 'me':
        return t('moments.audOnlyMe');
      case 'people':
        return t('moments.audChosen', { n: m.toCount });
      case 'circles':
        return t('moments.audCircles', { names: m.circles.map((c) => circleLabel(t, c)).join(', '), n: m.toCount });
      default:
        return t('moments.audContacts', { n: m.toCount });
    }
  }
  if (m.reach === 'circles') return t('moments.audTheirCircle');
  if (m.reach === 'people') return t('moments.audPeople');
  return t('moments.audEveryone');
}

/** Текстовий момент — спокійна картка, без фото. */
/** Тон 1..6 — тло й чорнило людського тону; 0 — звичайне тло моменту. */
export const toneStyle = (tone = 0): React.CSSProperties =>
  tone > 0 ? { backgroundColor: `var(--ph-person-${tone - 1})`, color: `var(--ph-person-${tone - 1}-ink)` } : { backgroundColor: 'var(--np-moment-bed)', color: '#fff' };

export const TextCard: React.FC<{ text: string; small?: boolean; tone?: number }> = ({ text, small, tone = 0 }) => (
  <div data-moment-textcard data-tone={tone} className={`absolute inset-0 flex items-center justify-center ${small ? 'px-2' : 'px-8'}`} style={toneStyle(tone)}>
    <p className={`${small ? 'text-footnote line-clamp-5' : text.length <= 90 ? 'text-display' : 'text-title-1'} text-center whitespace-pre-wrap break-words`}>{text}</p>
  </div>
);

export const STEP_MS = 6000;
const TOAST_MS = 2600;
const SWIPE_CLOSE_PX = 80;
/** Вихід — та сама пружина layer, що й вхід (tg-look 5); «менше руху» — коротке згасання з CSS. */
export const MOMENT_EXIT_MS = LAYER_EXIT_MS;
const REDUCED_EXIT_MS = 150;
const HOLD_MS = 220;
const BURST_MS = 2400;

export interface MomentViewerProps {
  moments: Moment[];
  startId: string;
  nameOf: (m: Moment) => string;
  avatarOf: (m: Moment) => string | undefined;
  chatOf: (m: Moment) => string | undefined;
  onOpenChat: (chatId: string) => void;
  onClose: () => void;
}

type Sheet = null | 'menu' | 'edit' | 'viewers' | 'share' | 'audience';

const SheetFrame: React.FC<{ label: string; onClose: () => void; children: React.ReactNode }> = ({ label, onClose, children }) => (
  <div className="absolute inset-0 z-10 flex flex-col justify-end" style={{ backgroundColor: 'var(--np-moment-label)' }} onClick={onClose} onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}>
    <div
      role="dialog"
      aria-label={label}
      data-moment-sheet
      className="rounded-t-[16px] px-4 pt-4 bg-canvas"
      style={{ backgroundColor: 'var(--ph-canvas)', paddingBottom: 'max(16px, env(safe-area-inset-bottom))' }}
      onClick={(e) => e.stopPropagation()}
    >
      {children}
    </div>
  </div>
);

export const MomentViewer: React.FC<MomentViewerProps> = ({ moments, startId, nameOf, avatarOf, chatOf, onOpenChat, onClose: onClosed }) => {
  const { t } = useTranslation();
  const meAvatar = useMessengerStore((s) => s.currentUser.avatar);
  const markSeen = useMoments((s) => s.markSeen);
  const react = useMoments((s) => s.react);
  const edit = useMoments((s) => s.edit);
  const mute = useMoments((s) => s.mute);
  const [idx, setIdx] = useState(() => Math.max(0, moments.findIndex((m) => m.id === startId)));
  const [reply, setReply] = useState('');
  const [typing, setTyping] = useState(false);
  const [tray, setTray] = useState(false);
  const [sheet, setSheet] = useState<Sheet>(null);
  const [draft, setDraft] = useState('');
  const [toast, setToast] = useState<{ ok: boolean; chatId?: string; text?: string } | null>(null);
  const downY = useRef<number | null>(null);
  const [held, setHeld] = useState(false);
  const holdTimer = useRef<number | null>(null);
  const swallowTap = useRef(false);
  const [burst, setBurst] = useState<{ key: string; emoji: string } | null>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const replyRef = useRef<HTMLInputElement>(null);
  const m = moments[Math.min(idx, moments.length - 1)];
  const { url, failed } = useMomentMedia(m ?? null);

  const [closing, setClosing] = useState(false);
  const deletingRef = useRef(false);
  const closedRef = useRef(onClosed);
  closedRef.current = onClosed;
  const exitTimer = useRef<number | null>(null);
  const onClose = useCallback(() => {
    if (exitTimer.current !== null) return;
    setClosing(true);
    exitTimer.current = window.setTimeout(() => closedRef.current(), isReducedMotion() ? REDUCED_EXIT_MS : MOMENT_EXIT_MS);
  }, []);
  useEffect(
    () => () => {
      if (exitTimer.current !== null) window.clearTimeout(exitTimer.current);
      if (holdTimer.current !== null) window.clearTimeout(holdTimer.current);
    },
    [],
  );
  useEffect(() => registerPhoneLayer('moments:viewer', true, onClose), [onClose]);
  useEffect(() => {
    if (!closing && !deletingRef.current && m && !m.mine && !m.seen) void markSeen(m.id);
  }, [closing, m, markSeen]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  useEffect(() => {
    setReply('');
    setToast(null);
    setTray(false);
    setSheet(null);
    setBurst(null);
  }, [idx]);
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    try {
      if (held) v.pause();
      else void v.play()?.catch(() => {});
    } catch {
      /* jsdom і старі WebView без play() */
    }
  }, [held]);
  useEffect(() => {
    if (!burst) return;
    const id = window.setTimeout(() => setBurst(null), BURST_MS);
    return () => window.clearTimeout(id);
  }, [burst]);

  // Відповідь чи реакція не тримають історію на паузі: плашка зникає сама, і прогрес іде далі.
  useEffect(() => {
    if (!toast) return;
    const id = window.setTimeout(() => setToast(null), TOAST_MS);
    return () => window.clearTimeout(id);
  }, [toast]);

  if (!m) return null;
  const name = nameOf(m);
  const next = () => (idx < moments.length - 1 ? setIdx(idx + 1) : onClose());
  const prev = () => setIdx(Math.max(0, idx - 1));
  // Утримання — пауза (як у Telegram); дотик після утримання не гортає.
  const tapped = (go: () => void) => () => {
    if (swallowTap.current) {
      swallowTap.current = false;
      return;
    }
    go();
  };
  const holdStart = () => {
    swallowTap.current = false;
    if (holdTimer.current !== null) window.clearTimeout(holdTimer.current);
    holdTimer.current = window.setTimeout(() => {
      holdTimer.current = null;
      setHeld(true);
    }, HOLD_MS);
  };
  const holdEnd = () => {
    if (holdTimer.current !== null) {
      window.clearTimeout(holdTimer.current);
      holdTimer.current = null;
    }
    if (held) {
      swallowTap.current = true;
      setHeld(false);
    }
  };
  const textOnly = m.media.length === 0;
  const video = isVideo(m);
  const paused = held || typing || tray || Boolean(sheet) || Boolean(toast) || (!textOnly && !url);
  const chatId = chatOf(m);
  const stepMs = video && m.media[0]?.durationMs ? Math.max(STEP_MS, m.media[0].durationMs) : STEP_MS;
  const viewerCount = m.viewers.length;

  const send = async (text: string) => {
    const said = text.trim();
    if (!chatId || !said) return;
    const body = momentReplyBody(said, m);
    const me = useMessengerStore.getState().currentUser;
    const clientId = `c_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
    try {
      const row = await messengerApi.appendMessage(chatId, {
        client_id: clientId,
        author_id: me.id,
        author_name: me.name,
        kind: 'text',
        body,
      });
      chatKeep.add(chatId);
      const nowIso = new Date().toISOString();
      const msg: Message = {
        id: row?.id || clientId,
        senderId: me.id,
        senderName: me.name,
        senderAvatar: me.avatar,
        timestamp: clockLabel(new Date()),
        sentAt: row?.sent_at || nowIso,
        type: 'text',
        text: body,
        isSelf: true,
        status: deliveryStatus(row?.delivery ?? row?.delivery_state) || 'sent',
      };
      useMessengerStore.setState((s) => ({
        chats: s.chats.map((c) =>
          c.id === chatId
            ? {
                ...c,
                messages: c.messages.some((x) => x.id === msg.id || x.id === clientId) ? c.messages : [...c.messages, msg],
                lastKind: 'text',
                lastSnippet: said.slice(0, 90),
                lastAuthor: SELF_AUTHOR,
                lastAt: msg.sentAt || nowIso,
              }
            : c,
        ),
      }));
      setReply('');
      replyRef.current?.blur();
      setTyping(false);
      setToast({ ok: true, chatId });
    } catch {
      setToast({ ok: false });
    }
  };

  const pickReaction = async (emoji: string) => {
    setTray(false);
    const twin = m.myReaction === emoji ? null : stickerForEmoji(emoji);
    if (twin && !isReducedMotion()) setBurst({ key: `${twin.id}:${Date.now()}`, emoji });
    try {
      await react(m.id, m.myReaction === emoji ? '' : emoji);
    } catch {
      setToast({ ok: false, text: t('moments.reactFailed') });
    }
  };

  const saveEdit = async () => {
    try {
      await edit(m.id, draft);
      setSheet(null);
    } catch {
      setToast({ ok: false, text: t('moments.editFailed') });
      setSheet(null);
    }
  };

  const shareable = canShareMoment(m);
  const saveToSaved = async () => {
    setSheet(null);
    try {
      await keepMomentMedia(m);
      await useMessengerStore.getState().forwardMessage(momentAsMessage(m, ''), SAVED_NOTES_ID);
      setToast({ ok: true, text: t('momentsR.inSaved') });
    } catch {
      setToast({ ok: false, text: t('momentsR.saveFailed') });
    }
  };

  const removeNow = (archive: boolean) => {
    const hasMoreOwn = moments.some((x) => x.id !== m.id && x.fromId === m.fromId);
    if (!hasMoreOwn) deletingRef.current = true;
    setSheet(null);
    void queueRemoval(m, archive).then(() => {
      if (!hasMoreOwn) onClose();
    });
  };

  const menuRow = (label: string, onClick: () => void, attr: string, danger = false) => (
    <button type="button" {...{ [attr]: '' }} onClick={onClick} className={`w-full h-12 min-h-0 px-2 text-left text-body ${danger ? 'text-danger' : 'text-ink'}`}>
      {label}
    </button>
  );

  const moreButton = (
    <button
      type="button"
      data-moment-more
      aria-label={t('moments.more')}
      onClick={() => setSheet('menu')}
      className="shrink-0 w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-white"
    >
      <MoreVertical className="w-6 h-6" strokeWidth={1.75} aria-hidden />
    </button>
  );

  return createPortal(
    <div
      data-moment-viewer={m.id}
      role="dialog"
      aria-modal="true"
      aria-label={t('moments.open', { name })}
      data-moment-held={held ? '' : undefined}
      className={`fixed inset-0 z-[1300] flex flex-col select-none ${closing ? 'np-moment-out pointer-events-none' : 'np-moment-in'}`}
      style={{ backgroundColor: 'var(--np-moment-stage)', border: 0, boxShadow: 'none' }}
      onPointerDown={(e) => {
        downY.current = e.clientY;
      }}
      onPointerUp={(e) => {
        if (!sheet && downY.current !== null && e.clientY - downY.current > SWIPE_CLOSE_PX) onClose();
        downY.current = null;
      }}
    >
      <div
        data-moment-stage
        className="relative flex-1 min-h-0 rounded-b-[16px] overflow-hidden"
        style={{ backgroundColor: 'var(--np-moment-bed)' }}
        onPointerDown={holdStart}
        onPointerUp={holdEnd}
        onPointerCancel={holdEnd}
        onPointerLeave={holdEnd}
        onContextMenu={(e) => e.preventDefault()}
      >
        {textOnly && <TextCard text={m.text} tone={m.tone} />}
        {url && !video && <img data-moment-photo src={url} alt="" draggable={false} className="absolute inset-0 w-full h-full object-cover" />}
        {url && video && <video ref={videoRef} data-moment-video src={url} autoPlay muted playsInline loop className="absolute inset-0 w-full h-full object-cover" />}
        {!textOnly && !url && (
          <p className="absolute inset-0 flex items-center justify-center px-8 text-center text-callout text-white/70">
            {m.media[0]?.state === 'broken' || failed ? t('moments.broken') : m.media[0]?.state === 'ready' ? '' : t('moments.arriving')}
          </p>
        )}
        <button type="button" aria-label={t('moments.prev')} data-moment-prev onClick={tapped(prev)} className="absolute inset-y-0 left-0 right-2/3 min-h-0 min-w-0" />
        <button type="button" aria-label={t('moments.next')} data-moment-next onClick={tapped(next)} className="absolute inset-y-0 right-0 left-1/3 min-h-0 min-w-0" />

        <div
          className={`absolute inset-x-0 top-0 px-4 pointer-events-none transition-opacity duration-quick ${held ? 'opacity-0' : ''}`}
          style={{ paddingTop: 'calc(env(safe-area-inset-top) + 8px)' }}
        >
          <div className="flex gap-1">
            {moments.map((x, i) => (
              <span key={x.id} className="flex-1 h-[3px] rounded-full bg-white/35 overflow-hidden">
                <span
                  key={i === idx ? `run-${x.id}` : x.id}
                  data-moment-progress={i === idx ? 'current' : undefined}
                  className="block h-full bg-white"
                  style={
                    i < idx
                      ? { width: '100%' }
                      : i > idx
                        ? { width: '0%' }
                        : { width: '100%', transformOrigin: 'left', animation: `np-moment-step ${stepMs}ms linear forwards`, animationPlayState: paused ? 'paused' : 'running' }
                  }
                  onAnimationEnd={i === idx ? next : undefined}
                />
              </span>
            ))}
          </div>
          <div className="mt-3 flex items-start gap-3">
            <Avatar src={avatarOf(m) ?? (m.mine ? meAvatar || undefined : undefined)} name={name} tone={m.mine ? null : m.fromId} className="w-10 h-10 text-callout" />
            <div className="flex-1 min-w-0">
              <p className="flex items-baseline gap-2">
                <span className="truncate text-headline text-white">{name}</span>
                <span className="shrink-0 text-footnote text-white/60 tnum">
                  {todayTimeLabel(new Date(m.atMs).toISOString())}
                  {m.editedMs > 0 ? ` · ${t('moments.edited')}` : ''}
                </span>
                {!m.keep && (
                  <span className="shrink-0 text-footnote text-white/60 tnum">
                    <span aria-hidden>· </span>
                    <span data-moment-ttl>{t('momentsR.ttl')}</span>
                  </span>
                )}
              </p>
              <p data-moment-audience className="mt-0.5 flex items-center gap-1.5 text-footnote text-white/80">
                {m.keep ? <Pin className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden /> : <Users className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden />}
                <span className="truncate">{m.keep ? `${t('moments.kept')} · ${audienceLine(t, m)}` : audienceLine(t, m)}</span>
              </p>
            </div>
            <button
              type="button"
              data-moment-close
              aria-label={t('moments.close')}
              onClick={onClose}
              className="pointer-events-auto w-11 h-11 min-h-0 min-w-0 -mr-2 flex items-center justify-center text-white"
            >
              <X className="w-6 h-6" strokeWidth={1.75} aria-hidden />
            </button>
          </div>
        </div>

        {m.text && !textOnly && (
          <p
            data-moment-caption-label
            className={`absolute left-4 right-4 ${toast ? 'bottom-[84px]' : 'bottom-4'} pointer-events-none w-fit max-w-[calc(100%-32px)] px-4 py-2.5 rounded-[12px] text-body text-white line-clamp-3`}
            style={{ background: 'var(--np-moment-label)' }}
          >
            {m.text}
          </p>
        )}
        {burst && (() => {
          const d = stickerForEmoji(burst.emoji);
          return d ? (
            <span aria-hidden data-moment-burst={`${d.packId}/${d.id}`} className="pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 z-20">
              <StickerView key={burst.key} packId={d.packId} id={d.id} emoji={d.emoji} size={176} onceKey={burst.key} onDone={() => setBurst(null)} />
            </span>
          ) : null;
        })()}
        {toast && (
          <div role="status" data-moment-toast className="absolute left-4 right-4 bottom-4 min-h-12 px-4 py-2 rounded-[16px] flex items-center gap-3" style={{ backgroundColor: 'var(--np-moment-toast)' }}>
            <span className="flex-1 min-w-0 text-callout text-white">{toast.text ?? (toast.ok ? t('moments.replySent', { name }) : t('moments.replyFailed'))}</span>
            {toast.ok && toast.chatId && (
              <button type="button" onClick={() => onOpenChat(toast.chatId as string)} className="shrink-0 h-12 min-h-0 px-2 text-callout font-semibold text-white">
                {t('moments.openChat')}
              </button>
            )}
          </div>
        )}
        {tray && (
          <div data-moment-tray className="absolute left-4 right-4 bottom-4 h-14 px-2 rounded-full flex items-center justify-around" style={{ backgroundColor: 'var(--np-moment-toast)' }}>
            {REACTIONS.map((e) => (
              <button
                key={e}
                type="button"
                data-moment-react={e}
                aria-pressed={m.myReaction === e}
                onClick={() => void pickReaction(e)}
                className={`w-11 h-11 min-h-0 min-w-0 rounded-full text-[24px] leading-none ${m.myReaction === e ? 'bg-white/20' : ''}`}
              >
                {e}
              </button>
            ))}
          </div>
        )}

        {sheet === 'menu' && (
          <SheetFrame label={t('moments.more')} onClose={() => setSheet(null)}>
            {shareable && (
              <>
                {menuRow(t('momentsR.sendTo'), () => setSheet('share'), 'data-moment-forward')}
                {menuRow(t('momentsR.toSaved'), () => void saveToSaved(), 'data-moment-save')}
              </>
            )}
            {m.mine ? (
              <>
                {menuRow(t('moments.editCaption'), () => {
                  setDraft(m.text);
                  setSheet('edit');
                }, 'data-moment-edit')}
                {menuRow(t('momentsR.whoSeesEdit'), () => setSheet('audience'), 'data-moment-audience-edit')}
                {menuRow(t('momentsR.toArchive'), () => removeNow(true), 'data-moment-archive')}
                {menuRow(t('moments.delete'), () => removeNow(false), 'data-moment-delete', true)}
              </>
            ) : (
              menuRow(t('moments.muteName', { name }), () => {
                setSheet(null);
                void mute(m.fromId, true).then(onClose, () => setToast({ ok: false, text: t('moments.muteFailed') }));
              }, 'data-moment-mute')
            )}
          </SheetFrame>
        )}
        {sheet === 'edit' && (
          <SheetFrame label={t('moments.editCaption')} onClose={() => setSheet(null)}>
            <h3 className="text-headline text-ink">{t('moments.editCaption')}</h3>
            <textarea
              data-moment-edit-text
              value={draft}
              maxLength={CAPTION_MAX}
              rows={3}
              onChange={(e) => setDraft(e.target.value)}
              className="mt-3 w-full px-4 py-3 rounded-control bg-raised text-body text-ink outline-none resize-none"
            />
            <div className="mt-3 flex gap-2 justify-end">
              <button type="button" onClick={() => setSheet(null)} className="h-12 min-h-0 px-4 rounded-full text-callout text-ink-2">{t('moments.cancel')}</button>
              <button
                type="button"
                data-moment-edit-save
                disabled={textOnly && !draft.trim()}
                onClick={() => void saveEdit()}
                className="h-12 min-h-0 px-6 rounded-full bg-accent text-on-accent text-headline disabled:opacity-40"
              >
                {t('moments.save')}
              </button>
            </div>
          </SheetFrame>
        )}
        {sheet === 'viewers' && (
          <SheetFrame label={t('moments.seenBy')} onClose={() => setSheet(null)}>
            <h3 className="text-headline text-ink">{t('moments.seenBy')}</h3>
            {viewerCount === 0 ? (
              <p data-moment-viewers-empty className="mt-2 mb-2 text-subhead font-normal text-ink-3">{t('moments.noViewers')}</p>
            ) : (
              <ul data-moment-viewers className="mt-2 max-h-[50vh] overflow-y-auto">
                {m.viewers.map((v) => (
                  <li key={v.id} data-moment-viewer-row={v.id} className="h-14 flex items-center gap-3">
                    <Avatar src={avatarOf({ ...m, mine: false, fromId: v.id })} name={v.name || t('chatList.unnamed')} tone={v.id} className="w-10 h-10 text-subhead" />
                    <span className="flex-1 min-w-0 truncate text-body text-ink">{v.name || t('chatList.unnamed')}</span>
                    {v.emoji && <span className="text-[20px] leading-none" aria-label={v.emoji}>{v.emoji}</span>}
                    <span className="shrink-0 text-footnote text-ink-3 tnum">{todayTimeLabel(new Date(v.atMs).toISOString())}</span>
                  </li>
                ))}
              </ul>
            )}
          </SheetFrame>
        )}
      </div>

      {m.mine ? (
        <div className="shrink-0 px-4 pt-3 flex items-center" style={{ paddingBottom: 'max(12px, env(safe-area-inset-bottom))' }}>
          <button type="button" data-moment-seenby onClick={() => setSheet('viewers')} className="h-12 min-h-0 px-2 flex items-center gap-2 text-callout text-white">
            <Eye className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            <span>{t('moments.seenCount', { n: viewerCount })}</span>
            {Object.entries(m.reactions).map(([e, n]) => (
              <span key={e} className="text-footnote text-white/80">{e}{n > 1 ? ` ${n}` : ''}</span>
            ))}
          </button>
          <span className="flex-1" />
          {moreButton}
        </div>
      ) : chatId ? (
        <form
          className="shrink-0 px-4 pt-3 flex items-center gap-3"
          style={{ paddingBottom: 'max(12px, env(safe-area-inset-bottom))' }}
          onSubmit={(e) => {
            e.preventDefault();
            void send(reply);
          }}
        >
          <input
            type="text"
            data-moment-reply
            ref={replyRef}
            value={reply}
            onChange={(e) => setReply(e.target.value)}
            onFocus={() => setTyping(true)}
            onBlur={() => setTyping(false)}
            placeholder={t('moments.replyTo', { name })}
            className="flex-1 min-w-0 h-12 px-5 rounded-full text-body text-white placeholder:text-white/50 outline-none"
            style={{ backgroundColor: 'var(--np-moment-bed)' }}
          />
          {reply.trim() ? (
            <button type="submit" aria-label={t('moments.send')} className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-white">
              <SendHorizontal className="w-6 h-6" strokeWidth={1.75} aria-hidden />
            </button>
          ) : (
            <button
              type="button"
              data-moment-react-open
              aria-label={t('moments.reactLabel')}
              aria-expanded={tray}
              onClick={() => setTray(!tray)}
              className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-white text-[22px] leading-none"
            >
              {m.myReaction || <Smile className="w-6 h-6" strokeWidth={1.75} aria-hidden />}
            </button>
          )}
          {moreButton}
        </form>
      ) : (
        <div
          className="shrink-0 px-4 pt-3 flex items-center justify-end"
          style={{ paddingBottom: 'max(12px, env(safe-area-inset-bottom))' }}
        >
          <button
            type="button"
            data-moment-react-open
            aria-label={t('moments.reactLabel')}
            aria-expanded={tray}
            onClick={() => setTray(!tray)}
            className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-white text-[22px] leading-none"
          >
            {m.myReaction || <Smile className="w-6 h-6" strokeWidth={1.75} aria-hidden />}
          </button>
          {moreButton}
        </div>
      )}
      {sheet === 'share' && <MomentShare m={m} layerClassName="z-[1400]" onClose={() => setSheet(null)} />}
      {sheet === 'audience' && m.mine && (
        <MomentAudienceEdit
          m={m}
          onClose={() => setSheet(null)}
          onFailed={(code) => setToast({ ok: false, text: t(code === 'no_one' ? 'moments.noOne' : 'moments.editFailed') })}
        />
      )}
    </div>,
    document.body,
  );
};

export default MomentViewer;
