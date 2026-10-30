/**
 * Фото і файли в стрічці — з розшифруванням тут, у вкладці.
 *
 * Байти лежать на вузлі шифротекстом, ключ приїхав у тілі повідомлення.
 * Чотири стани, названі людськими словами (без «відбитків» і «передач»):
 *   чекаємо  — байти ще не доїхали («Спробувати ще раз»);
 *   читаємо  — фетч і розшифрування триває (лише спінер);
 *   готово   — відбиток зійшовся, показуємо вміст;
 *   помилка  — відбиток чи тег AEAD не зійшовся; вміст не показуємо.
 *
 * Смуга прогресу є лише для пошматованих файлів: тільки там вкладка сама лічить отримані шматки.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, Download, FileText, Image as ImageIcon, RefreshCw } from 'lucide-react';
import type { Message } from '../../types/messenger';
import { messengerApi } from '../../services/messengerApi';
import {
  MediaNotArrived,
  MediaTampered,
  cachedMediaUrl,
  extensionOf,
  forgetMedia,
  humanSize,
  loadMedia,
} from '../../services/messengerMedia';
import { soundFx } from '../../utils/messengerSound';
import { saveMedia } from '../../services/saveMedia';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { useNearViewport } from '../../hooks/useNearViewport';
import { mediaBox } from '../../services/mediaBox';
import { useTranslation } from '../../i18n/useTranslation';
import { readNet, shouldFetch } from '../../services/autoFetch';
import { viewerFor } from '../../services/preview/registry';
import { nativePreviewFor, openInNativeViewer, wasNativeOpenRejected } from '../../services/preview/nativePreview';
import { FilePreviewSheet } from './preview/FilePreviewSheet';

type Phase = 'reading' | 'ready' | 'waiting' | 'tampered' | 'error' | 'manual';

interface Props {
  msg: Message;
  isSelf: boolean;
  onOpenLightbox?: (url: string, title?: string) => void;
}

export const SecureMediaBubble: React.FC<Props> = ({ msg, isSelf, onOpenLightbox }) => {
  const { t } = useTranslation();
  const media = msg.media!;
  // Якщо браузер таки не намалював те, що ми вважали фото, — падаємо в картку
  // файла. Бита іконка не дає дістати файл, картка дає.
  const [imgBroken, setImgBroken] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [nativeBusy, setNativeBusy] = useState(false);
  const nativeHeic = msg.type === 'image' && nativePreviewFor(media.name, media.mime, media.size) === 'heic';
  const isImage = msg.type === 'image' && !imgBroken && !nativeHeic;
  const caption = (msg.text || '').trim();

  const [url, setUrl] = useState<string | undefined>(() => cachedMediaUrl(media.blobId));
  const [phase, setPhase] = useState<Phase>(() =>
    cachedMediaUrl(media.blobId) ? 'ready' : 'reading',
  );
  const [asking, setAsking] = useState(false);
  const [got, setGot] = useState<{ done: number; total: number } | null>(null);
  const manualOpened = useRef(false);
  const open = useCallback(async () => {
    manualOpened.current = true;
    setPhase('reading');
    try {
      setUrl(
        await loadMedia(
          media,
          media.carrier ? (done, total) => setGot({ done, total }) : undefined,
        ),
      );
      setPhase('ready');
    } catch (err) {
      if (err instanceof MediaNotArrived) {
        setPhase('waiting');
      } else if (err instanceof MediaTampered) {
        setPhase('tampered');
      } else {
        setPhase('error');
      }
    } finally {
      setGot(null);
    }
  }, [media]);

  // Вкладення, чиї байти ніколи не виїхали з цього пристрою: blobId порожній,
  // і питати вузол «чи воно приїхало» немає сенсу — воно НЕ виїжджало. Власна
  // гілка рендеру нижче ловить цей стан ДО мережевого запиту.
  const uploadFailed = isSelf && msg.attachmentState === 'upload_failed';
  // Власне вкладення ще їде з цього пристрою: байтів на вузлі немає, питати
  // нема про що — показуємо місцевий передогляд і частку вивантаженого.
  const uploading = isSelf && !uploadFailed && (msg.uploadProgress !== undefined || (!!msg.localPreviewUrl && !media.blobId));

  const chat = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id)));
  const rings = chat?.circles?.length ? chat.circles : [chat?.circle ?? ''];
  const isPhoto = (media.mime || '').toLowerCase().startsWith('image/');
  const allowed =
    isSelf ||
    !chat ||
    shouldFetch({
      kind: isPhoto ? 'photo' : 'file',
      sizeBytes: media.size,
      rings,
      ...readNet(),
    });

  // Розшифровуємо лише те, що близько до видимого (hooks/useNearViewport):
  // сотня фото в довгій розмові не качається й не дешифрується наперед.
  const [nearRef, near] = useNearViewport(!uploadFailed && !uploading && !url);

  useEffect(() => {
    if (uploadFailed || uploading || !media.blobId || url) return;
    const cached = cachedMediaUrl(media.blobId);
    if (cached) {
      // Щойно вивантажене власне: кеш уже має відкритий файл.
      setUrl(cached);
      setPhase('ready');
      return;
    }
    if (!allowed) {
      if (!manualOpened.current) setPhase('manual');
      return;
    }
    if (near) void open();
  }, [media.blobId, open, uploadFailed, uploading, near, allowed, url]);

  // Місце під фото — з пропорцій листа (4:3, якщо їх немає): коробка не
  // міняє розміру ні між «читаємо» і «готово», ні між передоглядом і фото.
  const box = mediaBox(media.width, media.height);
  const boxStyle: React.CSSProperties = { width: box.width, maxWidth: '100%', aspectRatio: box.aspectRatio };

  const askAgain = async () => {
    soundFx.playTap();
    setAsking(true);
    try {
      if (!media.carrier) {
        await messengerApi.requestBlob(media.blobId);
      }
      forgetMedia(media.blobId);
      await open();
    } catch {
      setPhase('waiting');
    } finally {
      setAsking(false);
    }
  };

  const save = () => {
    if (!url) return;
    soundFx.playTap();
    void saveMedia(url, media.name, media.mime);
  };

  /** Підпис живе під превʼю — він пояснює те, що вже видно вище. */
  const captionLine = caption ? (
    <p className="px-2 pb-0.5 text-body text-ink whitespace-pre-wrap break-words">
      {caption}
    </p>
  ) : null;
  const batchLine = msg.mediaBatch ? (
    <p className="text-caption font-semibold text-ink-2 mt-0.5">{t('secureMediaBubble.batch', { index: msg.mediaBatch.index + 1, total: msg.mediaBatch.total })}</p>
  ) : null;

  const shell = 'bg-raised text-ink  rounded-control ';
  const batchShell = msg.mediaBatch ? 'border-l-2 border-l-line' : '';

  const progressBar = (pct: number, label: string, testId?: string) => (
    <div
      className="h-1 w-full rounded-full bg-canvas overflow-hidden"
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={pct}
      aria-label={label}
      data-testid={testId}
    >
      <div className="h-full bg-accent transition-all" style={{ width: `${pct}%` }} />
    </div>
  );

  // ── Байти їдуть з ЦЬОГО пристрою ───────────────────────────────────────────
  if (uploading) {
    const pct = Math.round((msg.uploadProgress ?? 0) * 100);
    const bar = progressBar(pct, t('secureMediaBubble.uploading'));
    if (isImage && msg.localPreviewUrl) {
      return (
        <div className="space-y-1.5 pt-1" data-testid="media-uploading">
          <div className="relative rounded-control overflow-hidden bg-raised" style={boxStyle}>
            <img src={msg.localPreviewUrl} alt={media.name} className="w-full h-full object-cover opacity-80" />
            <div className="absolute inset-x-2 bottom-2">{bar}</div>
          </div>
          {captionLine}
        </div>
      );
    }
    return (
      <div className={`mt-1 p-3.5 ${shell} ${batchShell}`} data-testid="media-uploading">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="p-2.5 bg-canvas text-ink-2 rounded-control shrink-0">
            {isImage ? <ImageIcon className="w-4 h-4" strokeWidth={1.75} /> : <FileText className="w-4 h-4" strokeWidth={1.75} />}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-subhead font-semibold truncate text-ink">{media.name}</p>
            <p className="text-footnote text-ink-3 truncate">
              {humanSize(media.size)} · {pct}%
            </p>
            {batchLine}
          </div>
        </div>
        <div className="mt-2">{bar}</div>
        {caption && <div className="mt-2">{captionLine}</div>}
      </div>
    );
  }

  // ── Байти не вивантажились з ЦЬОГО пристрою ────────────────────────────────
  // Окрема гілка ДО будь-якого мережевого запиту: blobId тут порожній, і
  // «чекаємо» чи «Запитати ще раз» були б тим самим вигаданим станом, що й
  // раніше вигаданий blobId. Людина мусить побачити ЧОМУ саме не вийшло.
  if (uploadFailed) {
    return (
      <div
        className={`mt-1 p-3.5 ${shell} ${batchShell}`}
        data-testid="media-upload-failed"
      >
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="p-2.5 bg-canvas text-danger rounded-control shrink-0">
            <AlertTriangle className="w-4 h-4" strokeWidth={1.75} />
          </div>
          <div className="min-w-0">
            <p className="text-subhead font-semibold truncate text-ink">{media.name}</p>
            <p className="text-footnote text-ink-3 truncate">
              {humanSize(media.size)} · {extensionOf(media.name)}
            </p>
            {batchLine}
          </div>
        </div>
        <button
          type="button"
          onClick={() => {
            soundFx.playTap();
            void useMessengerStore.getState().retrySend(msg.id);
          }}
          className="touch-floor min-h-[44px] mt-2 flex items-center gap-1.5 px-3 h-[36px] rounded-control bg-canvas hover:bg-raised text-footnote font-semibold text-ink-2 transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" strokeWidth={1.75} />
          {t('secureMediaBubble.retry')}
        </button>
        {caption && <div className="mt-2">{captionLine}</div>}
      </div>
    );
  }

  // ── Байти ще не доїхали ────────────────────────────────────────────────────
  if (phase === 'waiting') {
    return (
      <div className={`mt-1 p-3.5 ${shell} ${batchShell}`} data-testid="media-waiting">
        {/* Та сама картка, що й у готового файла: значок, імʼя, вага. Без неї
            «очікує передачі» було порожнім прямокутником — людина не бачила
            навіть того, ЩО саме до неї їде. */}
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="p-2.5 bg-canvas text-ink-2 rounded-control shrink-0">
            {isImage ? <ImageIcon className="w-4 h-4" strokeWidth={1.75} /> : <FileText className="w-4 h-4" strokeWidth={1.75} />}
          </div>
          <div className="min-w-0">
            <p className="text-subhead font-semibold truncate text-ink">{media.name}</p>
            <p className="text-footnote text-ink-3 truncate">
              {humanSize(media.size)} · {extensionOf(media.name)}
            </p>
            {batchLine}
          </div>
        </div>
        <p className="mt-2 flex items-center gap-1.5 text-footnote text-ink-3">
          <span className="w-1.5 h-1.5 rounded-full bg-accent shrink-0" />
          {media.carrier
            ? t('secureMediaBubble.partMissing')
            : isImage
              ? t('secureMediaBubble.photoLoading')
              : t('secureMediaBubble.fileLoading')}
        </p>
        <button
          type="button"
          onClick={askAgain}
          disabled={asking}
          className="touch-floor min-h-[44px] mt-2 flex items-center gap-1.5 px-3 h-[36px] rounded-control bg-canvas hover:bg-raised disabled:opacity-60 text-footnote font-semibold text-ink-2 transition-colors"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${asking ? 'animate-spin' : ''}`} strokeWidth={1.75} />
          {asking ? t('secureMediaBubble.trying') : t('secureMediaBubble.tryAgain')}
        </button>
        {caption && <div className="mt-2">{captionLine}</div>}
      </div>
    );
  }

  // ── Не відкрилось ─────────────────────────────────────────────────────────
  // Підмінені байти не показуємо ніколи; людині кажемо одне тепле речення.
  if (phase === 'tampered' || phase === 'error') {
    const tampered = phase === 'tampered';
    return (
      <div className={`mt-1 p-3.5 ${shell} ${batchShell}`} data-testid="media-error">
        <div className="flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 text-danger shrink-0" strokeWidth={1.75} />
          <span className="text-subhead font-semibold text-ink">
            {tampered
              ? t(isImage ? 'secureMediaBubble.tamperedPhoto' : 'secureMediaBubble.tamperedFile')
              : t(isImage ? 'secureMediaBubble.openPhotoFailed' : 'secureMediaBubble.openFileFailed')}
          </span>
        </div>
        <p className="mt-1 text-footnote text-ink-3 truncate">{media.name}</p>
      </div>
    );
  }

  // ── За запитом ─────────────────────────────────────────────────────────────
  if (phase === 'manual' && !url) {
    const tapLabel = t('autoFetch.tap', { size: humanSize(media.size) });
    if (isImage) {
      return (
        <div
          ref={nearRef}
          className="ph-media-frame relative mt-1 max-w-full rounded-control bg-raised overflow-hidden flex items-center justify-center"
          style={boxStyle}
          data-testid="media-manual"
          onClick={() => {
            soundFx.playTap();
            void open();
          }}
        >
          {media.thumb && <img src={media.thumb} alt="" aria-hidden className="ph-thumb" data-testid="media-thumb" />}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              soundFx.playTap();
              void open();
            }}
            className="touch-floor min-h-[44px] relative px-3.5 h-9 rounded-control bg-canvas hover:bg-raised text-footnote font-semibold text-ink transition-colors"
          >
            {tapLabel}
          </button>
        </div>
      );
    }
    return (
      <div
        ref={nearRef}
        className={`mt-1 p-3.5 ${shell} ${batchShell}`}
        data-testid="media-manual"
        onClick={() => {
          soundFx.playTap();
          void open();
        }}
      >
        <div className="flex items-center justify-between gap-2.5 min-w-0">
          <span className="text-subhead text-ink-2 truncate">{media.name || t('secureMediaBubble.file')}</span>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              soundFx.playTap();
              void open();
            }}
            className="touch-floor min-h-[44px] shrink-0 px-3 h-9 rounded-control bg-canvas hover:bg-raised text-footnote font-semibold text-ink-2 transition-colors"
          >
            {tapLabel}
          </button>
        </div>
      </div>
    );
  }

  // ── Читаємо ────────────────────────────────────────────────────────────────
  if (phase === 'reading' || !url) {
    const readBar =
      media.carrier && got && got.total > 0
        ? progressBar(
            Math.round((got.done / got.total) * 100),
            t('secureMediaBubble.opening'),
            'media-opening-progress',
          )
        : null;
    if (isImage) {
      return (
        // Місце під фото — остаточних пропорцій; у ньому вже стоїть розмита
        // мікромініатюра з листа (мікро №8). Без неї — тихе тло, без спінера.
        <div
          ref={nearRef}
          className="ph-media-frame relative mt-1 max-w-full rounded-control bg-raised overflow-hidden"
          style={boxStyle}
          data-testid="media-reading"
          aria-label={t('secureMediaBubble.photoLoadingAlt')}
        >
          {media.thumb && <img src={media.thumb} alt="" aria-hidden className="ph-thumb" data-testid="media-thumb" />}
          {readBar && <div className="absolute inset-x-2 bottom-2">{readBar}</div>}
        </div>
      );
    }
    return (
      <div ref={nearRef} className={`mt-1 p-3.5 ${shell} ${batchShell}`} data-testid="media-reading">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="w-5 h-5 rounded-full border-2 border-line border-t-transparent animate-spin shrink-0" />
          <span className="text-subhead text-ink-2 truncate">{media.name || t('secureMediaBubble.file')}</span>
        </div>
        {readBar && <div className="mt-2">{readBar}</div>}
      </div>
    );
  }

  // ── Готово ─────────────────────────────────────────────────────────────────
  if (isImage) {
    return (
      <div className="space-y-1.5 pt-1" data-testid="media-image">
        {/* Фото — теж файл, тож «Зберегти» мусить бути і тут. На дотику
            кнопка видима завжди, на миші зʼявляється під курсором. */}
        <div className="ph-media-frame relative group/photo max-w-full overflow-hidden rounded-control bg-raised" style={boxStyle}>
          {media.thumb && <img src={media.thumb} alt="" aria-hidden className="ph-thumb" />}
          <img
            src={url}
            alt={media.name}
            onError={() => setImgBroken(true)}
            // Не кожен непридатний байт доходить до onError: буває, що браузер
            // «завантажив» картинку нульового розміру. Тоді бульбашка ставала
            // порожнім прямокутником із самотньою крапкою під ним. Нульова
            // сторона — те саме, що бите фото: показуємо картку файла.
            onLoad={(e) => {
              const img = e.currentTarget;
              if (!img.naturalWidth || !img.naturalHeight) setImgBroken(true);
            }}
            onClick={() => {
              soundFx.playTap();
              onOpenLightbox?.(url, media.name);
            }}
            className="ph-develop relative w-full h-full object-cover cursor-pointer"
          />
          <button
            type="button"
            onClick={save}
            data-testid="media-photo-save"
            className="touch-floor-inline absolute top-2 right-2 w-8 h-8 rounded-full ph-on-photo transition-opacity sm:opacity-0 sm:group-hover/photo:opacity-100 focus:opacity-100 flex items-center justify-center"
            title={t('secureMediaBubble.save')}
            aria-label={t('secureMediaBubble.save')}
          >
            <Download className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
        {captionLine}
      </div>
    );
  }

  const preview = imgBroken
    ? ({ family: 'none', reason: 'unknown' } as const)
    : viewerFor(media.name, media.mime, media.size);
  const nativeKind = nativePreviewFor(media.name, media.mime, media.size);
  const openNative = async () => {
    if (!url || !nativeKind || nativeBusy) return;
    soundFx.playTap();
    setNativeBusy(true);
    try {
      const blob = await (await fetch(url)).blob();
      if (!(await openInNativeViewer(nativeKind, blob, media.name, media.mime))) {
        if (wasNativeOpenRejected()) {
          useUIStore.getState().toast({ kind: 'error', message: t('secureMediaBubble.openFileFailed') });
        } else {
          save();
        }
      }
    } catch {
      save();
    } finally {
      setNativeBusy(false);
    }
  };

  return (
    <div className={`mt-1 space-y-1.5 ${batchShell}`} data-testid="media-file">
      <div
        className={`p-3.5 rounded-control border flex items-center justify-between gap-3 ${shell}`}
      >
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="p-2.5 bg-canvas text-ink-2 rounded-control shrink-0">
            {imgBroken || nativeHeic ? <ImageIcon className="w-4 h-4" strokeWidth={1.75} /> : <FileText className="w-4 h-4" strokeWidth={1.75} />}
          </div>
          <div className="min-w-0">
            <p className="text-subhead font-semibold truncate text-ink">{media.name}</p>
            <p className="text-footnote text-ink-3">
              {humanSize(media.size)} · {extensionOf(media.name)}
            </p>
            {!nativeKind && preview.family === 'none' && preview.reason === 'too-big' && (
              <p className="text-footnote text-ink-3">{t('filePreview.tooBig')}</p>
            )}
            {batchLine}
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {(nativeKind || preview.family !== 'none') && (
            <button
              type="button"
              disabled={nativeBusy}
              aria-busy={nativeBusy}
              onClick={() => {
                if (nativeKind) {
                  void openNative();
                  return;
                }
                soundFx.playTap();
                setPreviewOpen(true);
              }}
              data-testid="media-file-open"
              className="touch-floor min-h-[44px] min-w-[44px] px-3 flex items-center justify-center rounded-control transition-colors shrink-0 bg-canvas hover:bg-raised text-footnote font-semibold text-ink-3 hover:text-ink disabled:opacity-50"
            >
              {t('filePreview.open')}
            </button>
          )}
          <button
            type="button"
            onClick={save}
            className="touch-floor min-h-[44px] min-w-[44px] flex items-center justify-center rounded-control transition-colors shrink-0 bg-canvas hover:bg-raised text-ink-3 hover:text-ink "
            title={t('secureMediaBubble.save')}
            aria-label={t('secureMediaBubble.save')}
          >
            <Download className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
      </div>
      {captionLine}
      <FilePreviewSheet
        isOpen={previewOpen}
        onClose={() => setPreviewOpen(false)}
        url={url}
        name={media.name}
        mime={media.mime}
        size={media.size}
      />
    </div>
  );
};
