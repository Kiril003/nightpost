/**
 * Фото перед надсиланням: вільна рамка й поворот. Оболонка та сама, що в
 * PhotoCropSheet (портал, пастка фокуса, шар телефона, «Скасувати»/«Готово»).
 */
import React, { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { RotateCw } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useFocusTrap } from './FormSheet';
import {
  FULL_FRAME,
  cropImage,
  dragFrame,
  isUntouched,
  nextQuarter,
  turnedSize,
  type CropFrame,
  type Handle,
  type Quarter,
} from '../../services/photoCrop';

interface Props {
  file: File;
  url: string;
  onClose: () => void;
  /** Новий файл; той самий — якщо рамку й поворот не чіпали. */
  onDone: (file: File) => void;
}

const DIM = 'var(--ph-lit-black-a62)';

const CORNERS: Exclude<Handle, 'move'>[] = ['nw', 'ne', 'sw', 'se'];

export const MediaCropSheet: React.FC<Props> = ({ file, url, onClose, onDone }) => {
  const { t } = useTranslation();
  const [natural, setNatural] = useState<{ w: number; h: number } | null>(null);
  const [q, setQ] = useState<Quarter>(0);
  const [frame, setFrame] = useState<CropFrame>(FULL_FRAME);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const drag = useRef<{ handle: Handle; x: number; y: number; start: CropFrame } | null>(null);

  const layerRef = useRef<HTMLDivElement>(null);
  useFocusTrap(layerRef, true);
  const layerId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`media-crop${layerId}`, true, () => closeRef.current()), [layerId]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const maxW = Math.max(200, Math.min((typeof window !== 'undefined' ? window.innerWidth : 360) - 32, 360));
  const maxH = Math.max(200, Math.round((typeof window !== 'undefined' ? window.innerHeight : 640) * 0.6));
  const turned = natural ? turnedSize(natural.w, natural.h, q) : null;
  const k = turned ? Math.min(maxW / turned.w, maxH / turned.h) : 1;
  const boxW = turned ? Math.round(turned.w * k) : maxW;
  const boxH = turned ? Math.round(turned.h * k) : maxW;
  const imgW = natural ? natural.w * k : 0;
  const imgH = natural ? natural.h * k : 0;

  const start = (handle: Handle) => (e: React.PointerEvent) => {
    e.stopPropagation();
    (e.currentTarget as Element).setPointerCapture?.(e.pointerId);
    drag.current = { handle, x: e.clientX, y: e.clientY, start: frame };
  };
  const move = (e: React.PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    setFrame(dragFrame(d.start, d.handle, (e.clientX - d.x) / boxW, (e.clientY - d.y) / boxH));
  };
  const end = () => {
    drag.current = null;
  };

  const rotate = () => {
    setQ(nextQuarter(q));
    setFrame(FULL_FRAME);
  };

  const save = async () => {
    if (isUntouched(frame, q)) {
      onDone(file);
      return;
    }
    setBusy(true);
    setError(null);
    const out = await cropImage(file, frame, q);
    if (!out) {
      setError(t('mediaCrop.failed'));
      setBusy(false);
      return;
    }
    onDone(out);
  };

  const pct = (v: number) => `${v * 100}%`;

  return createPortal(
    <div
      ref={layerRef}
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      aria-label={t('mediaCrop.label')}
      data-testid="media-crop"
      className="f-dialog fixed inset-0 z-[90] bg-black text-white font-sans outline-none flex flex-col items-center justify-center gap-6 px-4"
    >
      <div
        className="relative touch-none select-none"
        style={{ width: boxW, height: boxH }}
        onPointerMove={move}
        onPointerUp={end}
        onPointerCancel={end}
      >
        <img
          src={url}
          alt=""
          draggable={false}
          onLoad={(e) => setNatural({ w: e.currentTarget.naturalWidth || 1, h: e.currentTarget.naturalHeight || 1 })}
          className="absolute max-w-none pointer-events-none"
          style={
            natural
              ? { width: imgW, height: imgH, left: (boxW - imgW) / 2, top: (boxH - imgH) / 2, transform: `rotate(${q * 90}deg)` }
              : { width: 1, height: 1, opacity: 0 }
          }
        />
        {natural && (
          <>
            <div aria-hidden="true" className="absolute left-0 right-0 top-0 pointer-events-none" style={{ background: DIM, height: pct(frame.y) }} />
            <div aria-hidden="true" className="absolute left-0 right-0 bottom-0 pointer-events-none" style={{ background: DIM, height: pct(1 - frame.y - frame.h) }} />
            <div aria-hidden="true" className="absolute left-0 pointer-events-none" style={{ background: DIM, top: pct(frame.y), height: pct(frame.h), width: pct(frame.x) }} />
            <div aria-hidden="true" className="absolute right-0 pointer-events-none" style={{ background: DIM, top: pct(frame.y), height: pct(frame.h), width: pct(1 - frame.x - frame.w) }} />
            <div
              data-testid="media-crop-frame"
              className="absolute border-2 border-white cursor-move"
              style={{ left: pct(frame.x), top: pct(frame.y), width: pct(frame.w), height: pct(frame.h) }}
              onPointerDown={start('move')}
            >
              {CORNERS.map((c) => (
                <span
                  key={c}
                  data-handle={c}
                  onPointerDown={start(c)}
                  className="absolute w-11 h-11 flex items-center justify-center"
                  style={{
                    left: c.endsWith('w') ? -22 : undefined,
                    right: c.endsWith('e') ? -22 : undefined,
                    top: c.startsWith('n') ? -22 : undefined,
                    bottom: c.startsWith('s') ? -22 : undefined,
                  }}
                >
                  <span aria-hidden="true" className="block w-4 h-4 rounded-full bg-white" />
                </span>
              ))}
            </div>
          </>
        )}
      </div>

      <button
        type="button"
        onClick={rotate}
        disabled={busy}
        data-testid="media-crop-rotate"
        aria-label={t('mediaCrop.rotate')}
        title={t('mediaCrop.rotate')}
        className="touch-floor min-h-[44px] min-w-[44px] flex items-center justify-center rounded-full bg-overlay"
      >
        <RotateCw className="w-5 h-5" strokeWidth={1.75} />
      </button>

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
          data-testid="media-crop-done"
          onClick={() => void save()}
          disabled={busy || !natural}
          className="h-12 rounded-full bg-accent text-on-accent text-callout font-bold disabled:opacity-60"
        >
          {t('photoCropSheet.done')}
        </button>
      </div>
    </div>,
    document.body,
  );
};
