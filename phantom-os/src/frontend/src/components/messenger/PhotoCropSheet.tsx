/**
 * Своє фото: обрати коло на знімку й надіслати контактам одним дотиком.
 */
import React, { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { base64Of, closeImage, compressProfilePhoto, type SquareCrop } from '../../services/avatarCompress';
import { messengerApi } from '../../services/messengerApi';
import { soundFx } from '../../utils/messengerSound';
import { useTranslation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useFocusTrap } from './FormSheet';

/** Зсув і масштаб → квадрат у пікселях знімка. Чиста арифметика — її й перевіряє тест. */
export function cropOf(w: number, h: number, view: number, zoom: number, ox: number, oy: number): SquareCrop {
  const scale = (view / Math.min(w, h)) * zoom;
  const side = Math.min(view / scale, w, h);
  const sx = Math.min(Math.max(0, -ox / scale), w - side);
  const sy = Math.min(Math.max(0, -oy / scale), h - side);
  return { sx: Math.round(sx), sy: Math.round(sy), side: Math.round(side) };
}

/** Знімок мусить закривати коло цілком: край не заходить усередину. */
export function clampOffset(w: number, h: number, view: number, zoom: number, ox: number, oy: number): { ox: number; oy: number } {
  const scale = (view / Math.min(w, h)) * zoom;
  return {
    ox: Math.min(0, Math.max(view - w * scale, ox)),
    oy: Math.min(0, Math.max(view - h * scale, oy)),
  };
}

interface Props {
  img: HTMLImageElement;
  onClose: () => void;
  /** Фото прийняте телефоном; `avatar` — що показати на «Я». */
  onDone: (avatar: string) => void;
  onCropped?: (cropped: { avatarDataUrl: string; thumbB64: string; photoB64: string }) => void;
}

export const PhotoCropSheet: React.FC<Props> = ({ img, onClose, onDone, onCropped }) => {
  const { t } = useTranslation();
  const w = img.naturalWidth || img.width;
  const h = img.naturalHeight || img.height;
  const view = Math.max(200, Math.min((typeof window !== 'undefined' ? window.innerWidth : 360) - 32, 360));
  const base = view / Math.min(w, h);
  const [zoom, setZoom] = useState(1);
  const [pos, setPos] = useState(() => ({ ox: (view - w * base) / 2, oy: (view - h * base) / 2 }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const drag = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);

  const scale = base * zoom;
  const placed = clampOffset(w, h, view, zoom, pos.ox, pos.oy);

  useEffect(() => () => closeImage(img), [img]);
  const layerRef = useRef<HTMLDivElement>(null);
  useFocusTrap(layerRef, true);
  const layerId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`photo-crop${layerId}`, true, () => closeRef.current()), [layerId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const setZoomKeepingCentre = (next: number) => {
    const s0 = base * zoom;
    const s1 = base * next;
    const cx = (view / 2 - placed.ox) / s0;
    const cy = (view / 2 - placed.oy) / s0;
    setZoom(next);
    setPos(clampOffset(w, h, view, next, view / 2 - cx * s1, view / 2 - cy * s1));
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    const res = compressProfilePhoto(img, cropOf(w, h, view, zoom, placed.ox, placed.oy));
    if (!res.ok) {
      setError(t('photoCropSheet.prepareFailed'));
      setBusy(false);
      return;
    }
    if (onCropped) {
      soundFx.playTap();
      onCropped({
        avatarDataUrl: res.photo.dataUrl,
        thumbB64: base64Of(res.thumb.bytes),
        photoB64: base64Of(res.photo.bytes),
      });
      return;
    }
    try {
      const report = await messengerApi.publishProfile({
        avatar: base64Of(res.thumb.bytes),
        photo: base64Of(res.photo.bytes),
      });
      soundFx.playSend();
      onDone(report.avatar || res.photo.dataUrl);
    } catch {
      setError(t('photoCropSheet.publishFailed'));
      setBusy(false);
    }
  };

  return createPortal(
    <div
      ref={layerRef}
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      aria-label={t('photoCropSheet.label')}
      data-testid="photo-crop"
      className="f-dialog fixed inset-0 z-[90] bg-black text-white font-sans outline-none flex flex-col items-center justify-center gap-6 px-4"
    >
      <div
        className="relative overflow-hidden touch-none select-none"
        style={{ width: view, height: view }}
        onPointerDown={(e) => {
          (e.target as Element).setPointerCapture?.(e.pointerId);
          drag.current = { x: e.clientX, y: e.clientY, ox: placed.ox, oy: placed.oy };
        }}
        onPointerMove={(e) => {
          const d = drag.current;
          if (!d) return;
          setPos({ ox: d.ox + e.clientX - d.x, oy: d.oy + e.clientY - d.y });
        }}
        onPointerUp={() => {
          drag.current = null;
          setPos(placed);
        }}
        onWheel={(e) => setZoomKeepingCentre(Math.min(4, Math.max(1, zoom * (e.deltaY < 0 ? 1.1 : 0.9))))}
      >
        <img
          src={img.src}
          alt=""
          draggable={false}
          className="absolute left-0 top-0 max-w-none origin-top-left pointer-events-none"
          style={{ width: w, height: h, transform: `translate(${placed.ox}px, ${placed.oy}px) scale(${scale})` }}
        />
        <div aria-hidden="true" className="absolute inset-0 rounded-full pointer-events-none" style={{ boxShadow: '0 0 0 9999px var(--ph-lit-black-a62)' }} />
      </div>
      <input
        type="range"
        min={1}
        max={4}
        step={0.01}
        value={zoom}
        aria-label={t('photoCropSheet.zoom')}
        onChange={(e) => setZoomKeepingCentre(Number(e.target.value))}
        className="w-full max-w-[360px] accent-[var(--f-accent)]"
      />
      {error && (
        <p role="status" className="text-callout text-warning text-center">
          {error}
        </p>
      )}
      <div className="w-full max-w-[360px] grid grid-cols-2 gap-2 pb-[var(--sab,0px)]">
        <button type="button" onClick={onClose} disabled={busy} className="h-12 rounded-full bg-overlay text-callout font-semibold">
          {t('photoCropSheet.cancel')}
        </button>
        <button
          type="button"
          data-testid="photo-crop-done"
          onClick={() => void save()}
          disabled={busy}
          className="h-12 rounded-full bg-accent text-on-accent text-callout font-bold disabled:opacity-60"
        >
          {busy ? t('photoCropSheet.sending') : t('photoCropSheet.done')}
        </button>
      </div>
    </div>,
    document.body,
  );
};
