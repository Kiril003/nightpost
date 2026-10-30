/**
 * Пакет фото/файлів у стрічці — СІТКА, а не N окремих бульбашок.
 *
 * Власник: «файли і фото не по одному, а групуванно можна було надсилати».
 * Композер уже групує відправку (спільний передогляд, одна прогресія); тут —
 * друга половина скарги: кожен елемент пачки й далі окремий Message на дроті
 * (сумісність із вузлом і `retrySend` не чіпаємо — там свій client_id на
 * елемент), але людина повинна бачити ОДНУ картку з мініатюрами, а не пачку
 * бульбашок, що займає весь екран.
 *
 * `ChatArea` рендерить цей компонент лише на позиції ПЕРШОГО елемента пачки
 * (mediaBatch.index === 0) і не малює рядок для решти елементів узагалі.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, Download, FileText, Image as ImageIcon, RefreshCw } from 'lucide-react';
import type { Message } from '../../types/messenger';
import { messengerApi } from '../../services/messengerApi';
import {
  MediaNotArrived,
  MediaTampered,
  cachedMediaUrl,
  forgetMedia,
  humanSize,
  loadMedia,
} from '../../services/messengerMedia';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { saveMedia } from '../../services/saveMedia';
import { useNearViewport } from '../../hooks/useNearViewport';
import { readNet, shouldFetch } from '../../services/autoFetch';
import { viewerFor } from '../../services/preview/registry';
import { nativePreviewFor, openInNativeViewer, wasNativeOpenRejected } from '../../services/preview/nativePreview';
import { useUIStore } from '../../stores/uiStore';
import { FilePreviewSheet } from './preview/FilePreviewSheet';

interface Props {
  items: Message[];
  isSelf: boolean;
  onOpenLightbox?: (url: string, title?: string) => void;
}

type CellPhase = 'reading' | 'ready' | 'waiting' | 'tampered' | 'error' | 'upload_failed' | 'manual';

const Cell: React.FC<{ msg: Message; isSelf: boolean; onOpenLightbox?: (url: string, title?: string) => void }> = ({
  msg,
  isSelf,
  onOpenLightbox,
}) => {
  const { t } = useTranslation();
  const media = msg.media!;
  const [imgBroken, setImgBroken] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const nativeHeic = msg.type === 'image' && nativePreviewFor(media.name, media.mime, media.size) === 'heic';
  const isImage = msg.type === 'image' && !imgBroken && !nativeHeic;
  const uploadFailed = msg.attachmentState === 'upload_failed';
  // Своє вкладення ще їде: показуємо місцевий передогляд і частку, вузол не питаємо.
  const uploading = !uploadFailed && (msg.uploadProgress !== undefined || (!!msg.localPreviewUrl && !media.blobId));

  const chat = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id)));
  const rings = chat?.circles?.length ? chat.circles : [chat?.circle ?? ''];
  const isPhoto = (media.mime || '').toLowerCase().startsWith('image/');
  const allowed =
    isSelf ||
    Boolean(msg.isSelf) ||
    !chat ||
    shouldFetch({
      kind: isPhoto ? 'photo' : 'file',
      sizeBytes: media.size,
      rings,
      ...readNet(),
    });

  const [url, setUrl] = useState<string | undefined>(() => (uploadFailed ? undefined : cachedMediaUrl(media.blobId)));
  const [phase, setPhase] = useState<CellPhase>(() =>
    uploadFailed ? 'upload_failed' : cachedMediaUrl(media.blobId) ? 'ready' : 'reading',
  );
  const manualOpened = useRef(false);

  // Розшифровуємо лише клітинки поблизу видимого (hooks/useNearViewport).
  const [nearRef, near] = useNearViewport(!uploadFailed && !uploading && !url);

  const openCell = useCallback(async () => {
    manualOpened.current = true;
    setPhase('reading');
    try {
      const u = await loadMedia(media);
      setUrl(u);
      setPhase('ready');
    } catch (err) {
      if (err instanceof MediaNotArrived) setPhase('waiting');
      else if (err instanceof MediaTampered) setPhase('tampered');
      else setPhase('error');
    }
  }, [media]);

  useEffect(() => {
    if (uploadFailed) {
      setPhase('upload_failed');
      return;
    }
    if (uploading || !media.blobId || url) return;
    if (cachedMediaUrl(media.blobId)) {
      setUrl(cachedMediaUrl(media.blobId));
      setPhase('ready');
      return;
    }
    if (!allowed) {
      if (!manualOpened.current) setPhase('manual');
      return;
    }
    if (!near) return;
    let alive = true;
    setPhase('reading');
    loadMedia(media)
      .then((u) => alive && (setUrl(u), setPhase('ready')))
      .catch((err) => {
        if (!alive) return;
        if (err instanceof MediaNotArrived) setPhase('waiting');
        else if (err instanceof MediaTampered) setPhase('tampered');
        else setPhase('error');
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [media.blobId, uploadFailed, uploading, near, allowed, url]);

  const retry = (e: React.MouseEvent) => {
    e.stopPropagation();
    soundFx.playTap();
    void useMessengerStore.getState().retrySend(msg.id);
  };

  const askAgain = async (e: React.MouseEvent) => {
    e.stopPropagation();
    soundFx.playTap();
    try {
      if (!media.carrier) {
        await messengerApi.requestBlob(media.blobId);
      }
    } catch {
      /* ignore */
    }
    forgetMedia(media.blobId);
    await openCell();
  };

  const save = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!url) return;
    soundFx.playTap();
    void saveMedia(url, media.name, media.mime);
  };

  if (uploading) {
    const pct = Math.round((msg.uploadProgress ?? 0) * 100);
    return (
      <div
        className="relative w-full h-full overflow-hidden bg-raised flex items-center justify-center"
        title={media.name}
        data-testid="batch-cell-uploading"
      >
        {isImage && msg.localPreviewUrl ? (
          <img src={msg.localPreviewUrl} alt={media.name} className="w-full h-full object-cover opacity-80" />
        ) : (
          <FileText className="w-4 h-4 text-ink-3 " strokeWidth={1.75} />
        )}
        <div
          className="absolute inset-x-1.5 bottom-1.5 h-1 rounded-full bg-canvas overflow-hidden"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pct}
          aria-label={t('mediaBatchGrid.uploading')}
        >
          <div className="h-full bg-accent transition-all" style={{ width: `${pct}%` }} />
        </div>
      </div>
    );
  }

  if (phase === 'ready' && url && isImage) {
    return (
      <button
        type="button"
        onClick={() => {
          soundFx.playTap();
          onOpenLightbox?.(url, media.name);
        }}
        className="relative w-full h-full overflow-hidden bg-raised group/cell"
        title={media.name}
      >
        {media.thumb && <img src={media.thumb} alt="" aria-hidden className="ph-thumb" />}
        <img
          src={url}
          alt={media.name}
          onError={() => setImgBroken(true)}
          className="ph-develop relative w-full h-full object-cover"
        />
      </button>
    );
  }

  if (phase === 'ready' && url) {
    const preview = imgBroken
      ? ({ family: 'none', reason: 'unknown' } as const)
      : viewerFor(media.name, media.mime, media.size);
    const nativeKind = nativePreviewFor(media.name, media.mime, media.size);
    const handleFileClick = async (e: React.MouseEvent) => {
      if (nativeKind) {
        e.stopPropagation();
        soundFx.playTap();
        try {
          const blob = await (await fetch(url)).blob();
          if (await openInNativeViewer(nativeKind, blob, media.name, media.mime)) return;
          if (wasNativeOpenRejected()) useUIStore.getState().toast({ kind: 'error', message: t('secureMediaBubble.openFileFailed') });
          else save(e);
        } catch {
          save(e);
        }
        return;
      }
      if (preview.family !== 'none') {
        e.stopPropagation();
        soundFx.playTap();
        setPreviewOpen(true);
        return;
      }
      save(e);
    };
    return (
      <>
        <button
          type="button"
          onClick={(e) => void handleFileClick(e)}
          className="relative w-full h-full overflow-hidden bg-raised flex flex-col items-center justify-center gap-1 p-1.5 text-center"
          title={t('mediaBatchGrid.save', { name: media.name })}
        >
          <FileText className="w-5 h-5 text-ink-2" strokeWidth={1.75} />
          <span className="text-caption text-ink truncate w-full">{media.name}</span>
          <Download className="w-3 h-3 text-ink-3 absolute bottom-1 right-1" strokeWidth={1.75} />
        </button>
        <FilePreviewSheet
          isOpen={previewOpen}
          onClose={() => setPreviewOpen(false)}
          url={url}
          name={media.name}
          mime={media.mime}
          size={media.size}
        />
      </>
    );
  }

  if (phase === 'upload_failed') {
    return (
      <button
        type="button"
        onClick={retry}
        className="relative w-full h-full overflow-hidden bg-raised flex flex-col items-center justify-center gap-1 p-1 text-center"
        title={t('mediaBatchGrid.notSentTitle')}
        aria-label={t('mediaBatchGrid.notSent')}
      >
        <AlertTriangle className="w-4 h-4 text-danger" strokeWidth={1.75} />
        <RefreshCw className="w-3 h-3 text-ink-2" strokeWidth={1.75} />
      </button>
    );
  }

  if (phase === 'waiting') {
    return (
      <button
        type="button"
        onClick={(e) => void askAgain(e)}
        className="relative w-full h-full overflow-hidden bg-raised flex flex-col items-center justify-center gap-1 p-1 text-center"
        aria-label={t('mediaBatchGrid.loading')}
      >
        <span className="w-1.5 h-1.5 rounded-full bg-accent shrink-0" />
        <RefreshCw className="w-3 h-3 text-ink-2" strokeWidth={1.75} />
      </button>
    );
  }

  if (phase === 'manual' && !url) {
    return (
      <button
        ref={nearRef as unknown as React.Ref<HTMLButtonElement>}
        type="button"
        onClick={() => {
          soundFx.playTap();
          void openCell();
        }}
        className="relative w-full h-full overflow-hidden bg-raised flex flex-col items-center justify-center gap-1 p-1 text-center"
        aria-label={t('autoFetch.tap', { size: humanSize(media.size) })}
      >
        {isImage && media.thumb && <img src={media.thumb} alt="" aria-hidden className="ph-thumb" />}
        <Download className="w-4 h-4 text-ink-2 relative" strokeWidth={1.75} />
      </button>
    );
  }

  if (phase === 'tampered' || phase === 'error') {
    return (
      <div
        className="relative w-full h-full overflow-hidden bg-raised flex flex-col items-center justify-center gap-1 p-1 text-center"
        aria-label={phase === 'tampered' ? t('mediaBatchGrid.tampered') : t('mediaBatchGrid.openFailed')}
      >
        <AlertTriangle className="w-4 h-4 text-danger" strokeWidth={1.75} />
      </div>
    );
  }

  // reading
  return (
    <div ref={nearRef} className="relative w-full h-full overflow-hidden bg-raised flex items-center justify-center" title={media.name}>
      {isImage && media.thumb ? (
        <img src={media.thumb} alt="" aria-hidden className="ph-thumb" />
      ) : isImage ? (
        <ImageIcon className="w-4 h-4 text-ink-3 " strokeWidth={1.75} />
      ) : (
        <FileText className="w-4 h-4 text-ink-3 " strokeWidth={1.75} />
      )}
    </div>
  );
};

export const MediaBatchGrid: React.FC<Props> = ({ items, isSelf, onOpenLightbox }) => {
  const { t } = useTranslation();
  if (!items.length) return null;
  const total = items[0].mediaBatch?.total ?? items.length;
  // Підпис пачки — з ПЕРШОГО елемента: саме туди його поклав композер.
  const caption = (items[0].text || '').trim();

  // Мозаїка (ідентичність 02.10): 2 — пара; 3 — велике ліворуч і два
  // праворуч; 4 — 2×2; 5+ — два великі зверху й ряд по три. Проміжок 2 px,
  // внутрішній радіус 14 лише на зовнішніх кутах (обрізає рамка).
  const n = items.length;
  const layout = n === 1 ? 'grid-cols-1' : n === 2 ? 'grid-cols-2' : n === 3 ? 'grid-cols-2 grid-rows-2' : n === 4 ? 'grid-cols-2' : 'grid-cols-6';
  const span = (i: number): string => {
    if (n === 3) return i === 0 ? 'row-span-2 aspect-[1/2]' : 'aspect-square';
    if (n >= 5) {
      if (i < 2) return 'col-span-3 aspect-[4/3]';
      // Останній неповний ряд розтягується на всю ширину, а не лишає порожню смугу праворуч.
      const rest = n - 2;
      const lastRow = rest % 3 === 0 ? 3 : rest % 3;
      if (i - 2 >= rest - lastRow && lastRow < 3) return lastRow === 2 ? 'col-span-3 aspect-[4/3]' : 'col-span-6 aspect-[16/9]';
      return 'col-span-2 aspect-square';
    }
    return n === 1 ? 'aspect-[4/3]' : 'aspect-square';
  };
  return (
    <div className="space-y-1.5" data-testid="media-batch-grid">
      <div className={`ph-media-frame grid gap-0.5 overflow-hidden rounded-control ${layout} w-[280px] max-w-full`}>
        {items.map((m, i) => (
          <div key={m.id} className={span(i)} data-batch-cell>
            <Cell msg={m} isSelf={isSelf} onOpenLightbox={onOpenLightbox} />
          </div>
        ))}
      </div>
      {items.length < total && (
        <div className="flex items-center gap-2 text-caption text-ink-3">
          <span>{t('mediaBatchGrid.countOf', { shown: items.length, total })}</span>
        </div>
      )}
      {caption && (
        <p className="px-2 pb-0.5 text-body text-ink whitespace-pre-wrap break-words">{caption}</p>
      )}
    </div>
  );
};
