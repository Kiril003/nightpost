import { Suspense, lazy, useCallback, useEffect, useId, useMemo, useRef, useState, type SyntheticEvent } from 'react';
import { createPortal } from 'react-dom';
import { Download, X } from 'lucide-react';
import { useEscapeClose } from '../../../hooks/useEscapeClose';
import { useTranslation } from '../../../i18n/useTranslation';
import { viewerFor } from '../../../services/preview/registry';
import { saveMedia } from '../../../services/saveMedia';
import { soundFx } from '../../../utils/messengerSound';
import { registerPhoneLayer } from '../../../stores/phoneLayerStore';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  url: string;
  name: string;
  mime: string;
  size: number;
}

const iconBtn =
  'touch-floor w-11 h-11 rounded-control flex items-center justify-center text-ink hover:bg-raised active:bg-raised transition-colors shrink-0';

const stopSheetEvent = (e: SyntheticEvent) => {
  e.stopPropagation();
};

export function FilePreviewSheet({ isOpen, onClose, url, name, mime, size }: Props) {
  const { t } = useTranslation();
  const [failed, setFailed] = useState(false);
  const handleFail = useCallback(() => {
    setFailed(true);
  }, []);

  useEscapeClose(isOpen, onClose);
  const layerId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`file-preview${layerId}`, isOpen, () => closeRef.current()), [layerId, isOpen]);

  useEffect(() => {
    setFailed(false);
  }, [isOpen, url, name, mime]);

  const entry = viewerFor(name, mime, size);
  const load = entry.family === 'none' ? null : entry.load;
  const Viewer = useMemo(() => (load ? lazy(load) : null), [entry.family, load]);

  if (!isOpen) return null;

  const handleSave = () => {
    soundFx.playTap();
    void saveMedia(url, name, mime);
  };

  const handleClose = () => {
    soundFx.playTap();
    onClose();
  };

  return createPortal(
    <div
      data-testid="file-preview-sheet"
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex flex-col bg-canvas"
      style={{ paddingTop: 'env(safe-area-inset-top)' }}
      onClick={stopSheetEvent}
      onDoubleClick={stopSheetEvent}
      onContextMenu={stopSheetEvent}
      onPointerDown={stopSheetEvent}
      onPointerMove={stopSheetEvent}
      onPointerUp={stopSheetEvent}
      onPointerCancel={stopSheetEvent}
      onMouseDown={stopSheetEvent}
      onMouseUp={stopSheetEvent}
      onTouchStart={stopSheetEvent}
      onTouchMove={stopSheetEvent}
      onTouchEnd={stopSheetEvent}
    >
      <div className="flex items-center justify-between gap-2 px-4 py-2.5 border-b border-line shrink-0">
        <h2 className="text-headline text-ink truncate min-w-0 flex-1">{name}</h2>
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            onClick={handleSave}
            aria-label={t('filePreview.save')}
            title={t('filePreview.save')}
            className={iconBtn}
          >
            <Download className="w-5 h-5" strokeWidth={1.75} />
          </button>
          <button
            type="button"
            onClick={handleClose}
            aria-label={t('filePreview.close')}
            title={t('filePreview.close')}
            className={iconBtn}
          >
            <X className="w-5 h-5" strokeWidth={1.75} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto flex items-start justify-center">
        {entry.family === 'none' ? (
          entry.reason === 'too-big' ? (
            <p className="text-footnote text-ink-3 p-4" data-testid="preview-too-big">
              {t('filePreview.tooBig')}
            </p>
          ) : (
            <p className="text-footnote text-ink-3 p-4" data-testid="preview-failed">
              {t('secureMediaBubble.openFileFailed')}
            </p>
          )
        ) : !failed && Viewer ? (
          <Suspense fallback={null}>
            <Viewer
              url={url}
              name={name}
              mime={mime}
              maxBytes={entry.maxBytes}
              onFail={handleFail}
            />
          </Suspense>
        ) : (
          <p className="text-footnote text-ink-3 p-4" data-testid="preview-failed">
            {t('secureMediaBubble.openFileFailed')}
          </p>
        )}
      </div>
    </div>,
    document.body,
  );
}
