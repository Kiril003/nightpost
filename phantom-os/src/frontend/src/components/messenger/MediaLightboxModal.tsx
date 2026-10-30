/**
 * Перегляд фото — галерея розмови, а не одна картинка.
 *
 * Відкривається тим самим `openLightbox(url)`, що й раніше; сусідні фото
 * переглядач бере з розмови на екрані. Гортання — свайп убік, закриття —
 * свайп донизу, збільшення — пінч, подвійний дотик або Ctrl+коліщатко.
 * Не знайшлось фото в розмові (аватар, картка) — показує одне.
 */
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ChevronLeft, ChevronRight, Download, MessageCircle, Reply, X } from 'lucide-react';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { useMessengerStore } from '../../stores/messengerStore';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { MediaNotArrived, MediaTampered, cachedMediaUrl, loadMedia } from '../../services/messengerMedia';
import { saveMedia } from '../../services/saveMedia';
import type { Message } from '../../types/messenger';
import { Avatar } from './Avatar';
import { SHEET_SPRING } from '../../hooks/useSheetDrag';

interface MediaLightboxModalProps {
  isOpen: boolean;
  onClose: () => void;
  mediaUrl: string;
  mediaTitle?: string;
  mediaDate?: string;
}

interface Slide {
  key: string;
  msg: Message | null;
  url?: string;
  name: string;
  mime: string;
}

interface Transform {
  scale: number;
  x: number;
  y: number;
}

type GestureState =
  | {
      kind: 'pinch';
      startDist: number;
      startMidX: number;
      startMidY: number;
      startScale: number;
      startX: number;
      startY: number;
    }
  | {
      kind: 'pan';
      startTouchX: number;
      startTouchY: number;
      startX: number;
      startY: number;
      moved: boolean;
    }
  | {
      kind: 'swipe';
      startTouchX: number;
      startTouchY: number;
      moved: boolean;
    };

const IDENTITY: Transform = { scale: 1, x: 0, y: 0 };

const clamp = (v: number, min: number, max: number): number => Math.min(max, Math.max(min, v));

const urlOf = (m: Message): string | undefined =>
  (m.media ? cachedMediaUrl(m.media.blobId) : undefined) || m.mediaUrl || m.imageData?.url;

const isPhoto = (m: Message): boolean =>
  !m.isDeleted && m.type === 'image' && Boolean(m.media || m.mediaUrl || m.imageData?.url);

export const whenOf = (m: Message | null, locale: string): string => {
  const t = m ? Date.parse(m.sentAt || m.timestamp) : NaN;
  return Number.isFinite(t)
    ? new Date(t).toLocaleString(dateTag(locale), { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })
    : '';
};

const SlideImage: React.FC<{ slide: Slide; onUrl: (url: string) => void }> = ({
  slide,
  onUrl,
}) => {
  const { t } = useTranslation();
  const [url, setUrl] = useState<string | undefined>(slide.url);
  const [failKind, setFailKind] = useState<'none' | 'waiting' | 'tampered' | 'error'>('none');
  useEffect(() => {
    setUrl(slide.url);
    setFailKind('none');
    const media = slide.msg?.media;
    if (slide.url || !media) return;
    let alive = true;
    loadMedia(media)
      .then((u) => {
        if (!alive) return;
        setUrl(u);
        onUrl(u);
      })
      .catch((err) => {
        if (!alive) return;
        if (err instanceof MediaTampered) setFailKind('tampered');
        else if (err instanceof MediaNotArrived) setFailKind('waiting');
        else setFailKind('error');
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slide.key]);

  if (!url || failKind !== 'none') {
    const label =
      failKind === 'tampered'
        ? t('secureMediaBubble.tamperedPhoto')
        : failKind === 'error'
        ? t('secureMediaBubble.openPhotoFailed')
        : failKind === 'waiting'
        ? t('mediaLightboxModal.notArrived')
        : t('mediaLightboxModal.opening');
    return (
      <p className="text-callout text-ink-2 text-center px-8">
        {label}
      </p>
    );
  }
  return (
    <img
      src={url}
      alt={slide.name}
      data-lightbox-img
      draggable={false}
      onError={() => setFailKind('error')}
      className="select-none max-h-full max-w-full object-contain"
    />
  );
};

export const MediaLightboxModal: React.FC<MediaLightboxModalProps> = ({ isOpen, onClose, mediaUrl, mediaTitle, mediaDate }) => {
  const { t, locale } = useTranslation();
  useEscapeClose(isOpen, onClose);
  const layerId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const shown = isOpen && Boolean(mediaUrl);
  useEffect(() => registerPhoneLayer(`lightbox${layerId}`, shown, () => closeRef.current()), [layerId, shown]);
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === s.activeChatId));
  const selfId = useMessengerStore((s) => s.currentUser.id);

  const slides = useMemo<Slide[]>(() => {
    const photos = (chat?.messages ?? []).filter(isPhoto);
    const list: Slide[] = photos.map((m) => ({
      key: m.id,
      msg: m,
      url: urlOf(m),
      name: m.media?.name || `phantom-${m.id}.jpg`,
      mime: m.media?.mime || 'image/jpeg',
    }));
    if (list.some((s) => s.url === mediaUrl)) return list;
    return [{ key: 'single', msg: null, url: mediaUrl, name: mediaTitle || 'phantom.jpg', mime: '' }];
    // Галерею збираємо на відкритті: нове фото посеред перегляду не зсуває лічильник.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, mediaUrl]);

  const [index, setIndex] = useState(0);
  const [transform, setTransform] = useState<Transform>(IDENTITY);
  const transformRef = useRef<Transform>(IDENTITY);
  const [drag, setDrag] = useState<{ dx: number; dy: number } | null>(null);
  const dragRef = useRef<{ dx: number; dy: number } | null>(null);
  const [interacting, setInteracting] = useState(false);

  const stageRef = useRef<HTMLDivElement | null>(null);
  const gestureRef = useRef<GestureState | null>(null);
  const lastTapRef = useRef<{ time: number; x: number; y: number } | null>(null);
  const lastTouchEndRef = useRef(0);
  const loaded = useRef<Map<string, string>>(new Map());

  const updateTransform = (next: Transform) => {
    transformRef.current = next;
    setTransform(next);
  };

  const updateDrag = (next: { dx: number; dy: number } | null) => {
    dragRef.current = next;
    setDrag(next);
  };

  const resetTransform = () => {
    updateTransform(IDENTITY);
    updateDrag(null);
    gestureRef.current = null;
    lastTapRef.current = null;
    setInteracting(false);
  };

  const getStageCenter = () => {
    const el = stageRef.current;
    if (!el) return { cx: 0, cy: 0 };
    const rect = el.getBoundingClientRect();
    return {
      cx: rect.left + el.clientWidth / 2,
      cy: rect.top + el.clientHeight / 2,
    };
  };

  const clampPan = (x: number, y: number, scale: number) => {
    const stageEl = stageRef.current;
    const imgEl = stageEl?.querySelector('[data-lightbox-img]') as HTMLElement | null;
    const sw = stageEl?.clientWidth ?? 0;
    const sh = stageEl?.clientHeight ?? 0;
    const iw = imgEl?.offsetWidth ?? 0;
    const ih = imgEl?.offsetHeight ?? 0;
    let cx = x;
    let cy = y;
    if (sw > 0 && iw > 0) {
      const maxX = Math.max(0, (iw * scale - sw) / 2);
      cx = clamp(x, -maxX, maxX);
    }
    if (sh > 0 && ih > 0) {
      const maxY = Math.max(0, (ih * scale - sh) / 2);
      cy = clamp(y, -maxY, maxY);
    }
    return { x: cx, y: cy };
  };

  const toggleDoubleTapZoom = (clientX: number, clientY: number) => {
    const cur = transformRef.current;
    if (cur.scale > 1) {
      updateTransform(IDENTITY);
      return;
    }
    const nextScale = 2.5;
    const { cx, cy } = getStageCenter();
    const px = clientX - cx;
    const py = clientY - cy;
    const ratio = nextScale / cur.scale;
    const rawX = px - (px - cur.x) * ratio;
    const rawY = py - (py - cur.y) * ratio;
    const clamped = clampPan(rawX, rawY, nextScale);
    updateTransform({ scale: nextScale, x: clamped.x, y: clamped.y });
  };

  useEffect(() => {
    if (!isOpen) return;
    const at = slides.findIndex((s) => s.url === mediaUrl);
    setIndex(at >= 0 ? at : 0);
    resetTransform();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, mediaUrl, slides]);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') {
        resetTransform();
        setIndex((i) => Math.max(0, i - 1));
      }
      if (e.key === 'ArrowRight') {
        resetTransform();
        setIndex((i) => Math.min(slides.length - 1, i + 1));
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, slides.length]);

  // React вішає wheel пасивно — тоді preventDefault не діє і ctrl+колесо масштабує всю сторінку.
  useEffect(() => {
    const el = stageRef.current;
    if (!isOpen || !el) return;
    const onWheel = (e: WheelEvent) => {
      const cur = transformRef.current;
      if (!e.ctrlKey && cur.scale <= 1) return;
      e.preventDefault();
      const nextScale = clamp(cur.scale * Math.exp(-e.deltaY / 300), 1, 4);
      if (nextScale <= 1) {
        updateTransform(IDENTITY);
        return;
      }
      const { cx, cy } = getStageCenter();
      const px = e.clientX - cx;
      const py = e.clientY - cy;
      const ratio = nextScale / cur.scale;
      const rawX = px - (px - cur.x) * ratio;
      const rawY = py - (py - cur.y) * ratio;
      const clamped = clampPan(rawX, rawY, nextScale);
      updateTransform({ scale: nextScale, x: clamped.x, y: clamped.y });
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, mediaUrl]);

  if (!isOpen || !mediaUrl) return null;

  const slide = slides[Math.min(index, slides.length - 1)];
  const msg = slide.msg;
  const who = msg ? (msg.isSelf || msg.senderId === selfId ? t('mediaLightboxModal.you') : msg.senderName) : mediaTitle || t('mediaLightboxModal.photo');
  const when = msg ? whenOf(msg, locale) : mediaDate || '';

  const go = (step: number) => {
    const next = index + step;
    if (next < 0 || next >= slides.length) return;
    soundFx.playTap();
    resetTransform();
    setIndex(next);
  };

  const onTouchStart = (e: React.TouchEvent) => {
    setInteracting(true);
    if (e.touches.length === 2) {
      updateDrag(null);
      lastTapRef.current = null;
      const t0 = e.touches[0];
      const t1 = e.touches[1];
      const startDist = Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY) || 1;
      const { cx, cy } = getStageCenter();
      const cur = transformRef.current;
      gestureRef.current = {
        kind: 'pinch',
        startDist,
        startMidX: (t0.clientX + t1.clientX) / 2 - cx,
        startMidY: (t0.clientY + t1.clientY) / 2 - cy,
        startScale: cur.scale,
        startX: cur.x,
        startY: cur.y,
      };
      return;
    }
    if (e.touches.length === 1) {
      const t0 = e.touches[0];
      const cur = transformRef.current;
      if (cur.scale > 1) {
        gestureRef.current = {
          kind: 'pan',
          startTouchX: t0.clientX,
          startTouchY: t0.clientY,
          startX: cur.x,
          startY: cur.y,
          moved: false,
        };
      } else {
        gestureRef.current = {
          kind: 'swipe',
          startTouchX: t0.clientX,
          startTouchY: t0.clientY,
          moved: false,
        };
      }
    }
  };

  const onTouchMove = (e: React.TouchEvent) => {
    const g = gestureRef.current;
    if (!g) return;

    if (e.touches.length === 2 && g.kind === 'pinch') {
      const t0 = e.touches[0];
      const t1 = e.touches[1];
      const dist = Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY);
      const { cx, cy } = getStageCenter();
      const midX = (t0.clientX + t1.clientX) / 2 - cx;
      const midY = (t0.clientY + t1.clientY) / 2 - cy;
      const nextScale = clamp(g.startScale * (dist / g.startDist), 1, 4);
      const ratio = nextScale / g.startScale;
      const rawX = midX - (g.startMidX - g.startX) * ratio;
      const rawY = midY - (g.startMidY - g.startY) * ratio;
      const clamped = clampPan(rawX, rawY, nextScale);
      updateTransform({ scale: nextScale, x: clamped.x, y: clamped.y });
      return;
    }

    if (e.touches.length === 1 && g.kind === 'pan') {
      const t0 = e.touches[0];
      const dx = t0.clientX - g.startTouchX;
      const dy = t0.clientY - g.startTouchY;
      if (dx !== 0 || dy !== 0) {
        g.moved = true;
        lastTapRef.current = null;
      }
      const cur = transformRef.current;
      const clamped = clampPan(g.startX + dx, g.startY + dy, cur.scale);
      updateTransform({ scale: cur.scale, x: clamped.x, y: clamped.y });
      return;
    }

    if (e.touches.length === 1 && g.kind === 'swipe') {
      const t0 = e.touches[0];
      const dx = t0.clientX - g.startTouchX;
      const dy = t0.clientY - g.startTouchY;
      if (dx !== 0 || dy !== 0) {
        g.moved = true;
        lastTapRef.current = null;
      }
      updateDrag({ dx, dy });
    }
  };

  const onTouchEnd = (e: React.TouchEvent) => {
    lastTouchEndRef.current = Date.now();
    const g = gestureRef.current;
    if (e.touches.length === 1 && g?.kind === 'pinch') {
      const cur = transformRef.current;
      if (cur.scale < 1.05) {
        updateTransform(IDENTITY);
        gestureRef.current = null;
      } else {
        const t0 = e.touches[0];
        gestureRef.current = {
          kind: 'pan',
          startTouchX: t0.clientX,
          startTouchY: t0.clientY,
          startX: cur.x,
          startY: cur.y,
          moved: true,
        };
      }
      return;
    }

    setInteracting(false);
    gestureRef.current = null;

    if (!g) return;

    if (g.kind === 'pinch') {
      if (transformRef.current.scale < 1.05) {
        updateTransform(IDENTITY);
      }
      return;
    }

    if ((g.kind === 'swipe' || g.kind === 'pan') && !g.moved) {
      updateDrag(null);
      const changed = e.changedTouches?.[0];
      const tapX = changed ? changed.clientX : g.startTouchX;
      const tapY = changed ? changed.clientY : g.startTouchY;
      const now = Date.now();
      const prev = lastTapRef.current;
      if (prev && now - prev.time <= 300 && Math.hypot(tapX - prev.x, tapY - prev.y) <= 24) {
        lastTapRef.current = null;
        toggleDoubleTapZoom(tapX, tapY);
      } else {
        lastTapRef.current = { time: now, x: tapX, y: tapY };
      }
      return;
    }

    if (g.kind === 'swipe') {
      const d = dragRef.current;
      updateDrag(null);
      if (!d) return;
      if (d.dy > 110 && Math.abs(d.dy) > Math.abs(d.dx)) onClose();
      else if (d.dx < -60) go(1);
      else if (d.dx > 60) go(-1);
    }
  };

  const onDoubleClick = (e: React.MouseEvent) => {
    // Після дотикового подвійного тапу браузер ще й емулює dblclick — він би одразу скасував зум.
    if (Date.now() - lastTouchEndRef.current < 800) return;
    toggleDoubleTapZoom(e.clientX, e.clientY);
  };

  const isAtScaleOne = transform.scale === 1;
  const tx = isAtScaleOne && drag ? (Math.abs(drag.dx) > Math.abs(drag.dy) ? drag.dx : 0) : transform.x;
  const ty = isAtScaleOne && drag ? (Math.abs(drag.dx) > Math.abs(drag.dy) ? 0 : Math.max(0, drag.dy)) : transform.y;
  const iconBtn = 'w-11 h-11 min-w-0 min-h-0 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay';

  return createPortal(
    <div
      data-testid="media-lightbox"
      role="dialog"
      aria-modal="true"
      aria-label={t('mediaLightboxModal.viewer')}
      className="fixed inset-0 z-[1500] select-none"
    >
      {/* Тло тьмяніє разом із відстанню, на яку фото стягнули вниз (§6 «Photo»). */}
      <div
        aria-hidden
        className="absolute inset-0 bg-black"
        style={{ opacity: drag && isAtScaleOne && drag.dy > 0 && Math.abs(drag.dy) > Math.abs(drag.dx) ? Math.max(0.2, 1 - drag.dy / 400) : 1 }}
      />
      <div
        className="absolute inset-x-0 top-0 z-10 px-1 pb-2 flex items-center gap-1"
        style={{
          paddingTop: 'calc(max(var(--sat, 0px), 28px) + 6px)',
        }}
      >
        <button type="button" onClick={onClose} className={iconBtn} aria-label={t('mediaLightboxModal.close')}>
          <X className="w-6 h-6" strokeWidth={1.75} />
        </button>
        {msg && (
          <Avatar
            name={who}
            src={msg.senderAvatar}
            tone={msg.isSelf || msg.senderId === selfId ? selfId || 'me' : msg.senderId}
            radius="rounded-full"
            className="w-9 h-9 text-subhead ml-1"
          />
        )}
        <div className="flex-1 min-w-0 px-2">
          <p className="text-headline text-white truncate">{who}</p>
          <p className="text-caption text-ink-2 truncate">
            {slides.length > 1 ? t('mediaLightboxModal.counter', { index: index + 1, total: slides.length }) : ''}
            {slides.length > 1 && when ? ' · ' : ''}
            {when}
          </p>
        </div>
        {msg && chat && (
          <button
            type="button"
            data-testid="lightbox-reply"
            onClick={() => {
              onClose();
              useMessengerStore.getState().startReply(msg);
            }}
            className={iconBtn}
            aria-label={t('msgTypes.replyToItem')}
          >
            <Reply className="w-5 h-5" strokeWidth={1.75} />
          </button>
        )}
        {msg && chat && (
          <button
            type="button"
            onClick={() => {
              soundFx.playTap();
              onClose();
              useMessengerStore.getState().openChatAtMessage(chat.id, msg.id);
            }}
            className={iconBtn}
            aria-label={t('mediaLightboxModal.showInChat')}
          >
            <MessageCircle className="w-5 h-5" strokeWidth={1.75} />
          </button>
        )}
        <button
          type="button"
          disabled={!(slide.url || loaded.current.get(slide.key))}
          onClick={() => {
            soundFx.playTap();
            const url = slide.url || loaded.current.get(slide.key);
            if (url) void saveMedia(url, slide.name, slide.mime);
          }}
          className={`${iconBtn} disabled:opacity-40`}
          aria-label={t('mediaLightboxModal.saveToDevice')}
        >
          <Download className="w-5 h-5" strokeWidth={1.75} />
        </button>
      </div>

      <div
        ref={stageRef}
        data-scale={transform.scale.toFixed(2)}
        className="absolute inset-0 flex items-center justify-center overflow-hidden"
        style={{ touchAction: 'none' }}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onTouchCancel={onTouchEnd}
        onDoubleClick={onDoubleClick}
      >
        <div
          className="w-full h-full flex items-center justify-center"
          style={{
            transform: `translate3d(${tx}px, ${ty}px, 0) scale(${transform.scale})`,
            transformOrigin: 'center center',
            transition: interacting || drag ? 'none' : `transform var(--ph-spring-layer-ms) ${SHEET_SPRING}`,
          }}
        >
          <SlideImage
            slide={slide}
            onUrl={(u) => loaded.current.set(slide.key, u)}
          />
        </div>
        {slides.length > 1 && index > 0 && (
          <button type="button" onClick={() => go(-1)} className={`${iconBtn} absolute left-2 hidden sm:flex bg-overlay`} aria-label={t('mediaLightboxModal.prev')}>
            <ChevronLeft className="w-6 h-6" strokeWidth={1.75} />
          </button>
        )}
        {slides.length > 1 && index < slides.length - 1 && (
          <button type="button" onClick={() => go(1)} className={`${iconBtn} absolute right-2 hidden sm:flex bg-overlay`} aria-label={t('mediaLightboxModal.next')}>
            <ChevronRight className="w-6 h-6" strokeWidth={1.75} />
          </button>
        )}
      </div>

      {msg?.text && (
        <p
          className="absolute inset-x-0 bottom-0 z-10 px-4 pt-10 text-body text-white max-h-[30vh] overflow-y-auto whitespace-pre-wrap break-words"
          style={{
            paddingBottom: 'calc(env(safe-area-inset-bottom) + 16px)',
            background: 'var(--ph-lit-black-a70)',
          }}
        >
          {msg.text}
        </p>
      )}
    </div>,
    document.body,
  );
};

export default MediaLightboxModal;
