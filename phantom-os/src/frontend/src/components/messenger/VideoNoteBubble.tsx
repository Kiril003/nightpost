import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Volume2, VolumeX, Play, RefreshCw, VideoOff } from 'lucide-react';
import type { Message } from '../../types/messenger';
import { messengerApi } from '../../services/messengerApi';
import { cachedMediaUrl, forgetMedia, humanSize, loadMedia, MediaNotArrived, MediaTampered } from '../../services/messengerMedia';
import { useMessengerStore } from '../../stores/messengerStore';
import { readNet, shouldFetch } from '../../services/autoFetch';
import { soundFx } from '../../utils/messengerSound';
import { useNearViewport } from '../../hooks/useNearViewport';
import { useTranslation } from '../../i18n/useTranslation';

interface VideoNoteBubbleProps {
  msg: Message;
}

export const VideoNoteBubble: React.FC<VideoNoteBubbleProps> = ({ msg }) => {
  const { t } = useTranslation();
  const chat = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id)));
  const videoNote = msg.videoNoteData;
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  const uploadFailed = Boolean(msg.isSelf && msg.attachmentState === 'upload_failed');
  // Свій кружок ще вивантажується — грає з місцевого файла, вузол не питаємо.
  const uploading = !uploadFailed && (msg.uploadProgress !== undefined || (!!msg.localPreviewUrl && !videoNote?.media?.blobId));
  const rings = chat?.circles?.length ? chat.circles : [chat?.circle ?? ''];
  const allowed =
    Boolean(msg.isSelf) ||
    !videoNote?.media ||
    uploading ||
    !chat ||
    shouldFetch({
      kind: 'photo',
      sizeBytes: videoNote.media.size,
      rings,
      ...readNet(),
    });
  const [url, setUrl] = useState<string | undefined>(() => {
    if (msg.localPreviewUrl && uploading) return msg.localPreviewUrl;
    if (videoNote?.media?.blobId) {
      return cachedMediaUrl(videoNote.media.blobId);
    }
    return videoNote?.videoUrl;
  });
  // Качаємо й розшифровуємо лише кружок поблизу видимого (hooks/useNearViewport).
  const [nearRef, near] = useNearViewport(!url && !uploading && !uploadFailed);

  const [error, setError] = useState<string | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isMuted, setIsMuted] = useState(true);
  const [progress, setProgress] = useState(0);
  const [soundToast, setSoundToast] = useState(false);
  /**
   * Запис є, а кадрів у ньому немає (камера не віддала зображення — лише
   * звук). Доти це був порожній кружок без жодного слова, і людина не знала,
   * чи він ще вантажиться, чи зламаний (A52, 02.10). Тепер кружок так і каже.
   */
  const [noPicture, setNoPicture] = useState(false);

  const fetchVideo = useCallback(async () => {
    if (!videoNote?.media?.blobId) {
      if (!uploading) setError(t('videoNoteBubble.loadFailed'));
      return;
    }
    setError(null);
    try {
      const decryptedUrl = await loadMedia(videoNote.media);
      setUrl(decryptedUrl);
    } catch (e) {
      if (e instanceof MediaNotArrived) {
        setError(t('videoNoteBubble.loading'));
      } else if (e instanceof MediaTampered) {
        setError(t('videoNoteBubble.tampered'));
      } else {
        setError(t('videoNoteBubble.loadFailed'));
      }
    }
  }, [videoNote?.media, uploading, t]);

  const handleRetry = async (e: React.MouseEvent) => {
    e.stopPropagation();
    soundFx.playTap();
    if (uploadFailed) {
      await useMessengerStore.getState().retrySend(msg.id);
      return;
    }
    if (videoNote?.media?.blobId) {
      if (!videoNote.media.carrier) {
        await messengerApi.requestBlob(videoNote.media.blobId).catch(() => undefined);
      }
      forgetMedia(videoNote.media.blobId);
    }
    await fetchVideo();
  };

  useEffect(() => {
    if (!url && near && !uploading && !uploadFailed && allowed) {
      void fetchVideo();
    }
  }, [url, fetchVideo, near, uploading, uploadFailed, allowed]);

  // Autoplay when in viewport
  useEffect(() => {
    const el = containerRef.current;
    if (!el || !url || typeof IntersectionObserver === 'undefined') return;

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            if (videoRef.current && videoRef.current.paused) {
              videoRef.current.play().catch(() => {});
            }
          } else {
            if (videoRef.current && !videoRef.current.paused) {
              videoRef.current.pause();
            }
          }
        });
      },
      { threshold: 0.5 }
    );

    observer.observe(el);
    return () => observer.disconnect();
  }, [url]);

  // 120fps smooth circular progress tracking
  useEffect(() => {
    let animId: number;
    if (isPlaying) {
      const updateProgress = () => {
        if (videoRef.current && videoRef.current.duration) {
          const p = (videoRef.current.currentTime / videoRef.current.duration) * 100;
          setProgress(p);
        }
        animId = requestAnimationFrame(updateProgress);
      };
      animId = requestAnimationFrame(updateProgress);
    }
    return () => {
      if (animId) cancelAnimationFrame(animId);
    };
  }, [isPlaying]);

  // Clean resource teardown on unmount
  useEffect(() => {
    return () => {
      if (videoRef.current) {
        try {
          videoRef.current.pause();
          videoRef.current.removeAttribute('src');
          videoRef.current.load();
        } catch {
          /* ignore */
        }
      }
    };
  }, []);

  const handleToggleSound = (e: React.MouseEvent) => {
    e.stopPropagation();
    soundFx.playTap();
    if (!videoRef.current) return;

    const nextMuted = !isMuted;
    videoRef.current.muted = nextMuted;
    setIsMuted(nextMuted);

    setSoundToast(true);
    setTimeout(() => setSoundToast(false), 1500);

    if (videoRef.current.paused) {
      videoRef.current.play().catch(() => {});
    }
  };

  const handleVideoClick = () => {
    if (!videoRef.current) return;
    soundFx.playTap();
    if (videoRef.current.paused) {
      videoRef.current.play().catch(() => {});
    } else {
      videoRef.current.pause();
    }
  };

  const radius = 106;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (progress / 100) * circumference;

  return (
    <div
      ref={(el) => {
        containerRef.current = el;
        nearRef(el);
      }}
      className="relative inline-flex flex-col items-center select-none py-1 w-[224px] min-w-[224px] min-h-[224px]"
      data-testid="video-note-bubble"
    >
      <div className="relative w-[224px] h-[224px] shrink-0 flex items-center justify-center">
        {/* Playback Progress Ring */}
{progress > 0 && (
        <svg className="absolute inset-0 w-full h-full -rotate-90 pointer-events-none" viewBox="0 0 224 224">
          <circle
            cx="112"
            cy="112"
            r={radius}
            className="text-ink-2 stroke-current"
            strokeWidth="2"
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            strokeLinecap="round"
            fill="transparent"
          />
        </svg>
        )}

        {/* Circular Video Container */}
        <div
          onClick={handleVideoClick}
          className="w-[200px] h-[200px] rounded-full overflow-hidden bg-raised relative cursor-pointer group"
        >
          {url ? (
            <video
              ref={videoRef}
              src={url}
              // Без постера WebView Android малює свою величезну сіру кнопку ▶,
              // поки немає першого кадру. Прозорий піксель її гасить; наш
              // власний значок відтворення лишається поверх.
              poster="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
              preload="metadata"
              playsInline
              loop
              muted={isMuted}
              onLoadedMetadata={(e) => {
                const v = e.currentTarget;
                setNoPicture(v.videoWidth === 0 && v.videoHeight === 0);
              }}
              onPlay={() => setIsPlaying(true)}
              onPause={() => setIsPlaying(false)}
              onTimeUpdate={() => {
                if (videoRef.current && videoRef.current.duration) {
                  setProgress((videoRef.current.currentTime / videoRef.current.duration) * 100);
                }
              }}
              className="w-full h-full object-cover"
            />
          ) : (
            <div className="w-full h-full flex flex-col items-center justify-center p-3 text-center bg-surface">
              {uploadFailed || error ? (
                <>
                  <span className="text-caption text-danger leading-snug mb-2">
                    {uploadFailed ? msg.attachmentError || t('videoNoteBubble.loadFailed') : error}
                  </span>
                  <button
                    type="button"
                    onClick={(e) => void handleRetry(e)}
                    className="p-2 rounded-full bg-raised text-ink hover:bg-raised"
                    aria-label={t('videoNoteBubble.retry')}
                  >
                    <RefreshCw className="w-4 h-4" />
                  </button>
                </>
              ) : !allowed && videoNote?.media ? (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    soundFx.playTap();
                    void fetchVideo();
                  }}
                  className="px-3 py-1.5 rounded-control bg-raised text-caption font-semibold text-ink"
                >
                  {t('autoFetch.tap', { size: humanSize(videoNote.media.size) })}
                </button>
              ) : (
                <div className="w-8 h-8 rounded-full border-2 border-line border-t-transparent animate-spin" />
              )}
            </div>
          )}

          {url && noPicture && (
            <div
              data-testid="video-note-no-picture"
              className="absolute inset-0 flex flex-col items-center justify-center gap-1.5 bg-raised text-ink-3 pointer-events-none"
            >
              <VideoOff className="w-6 h-6" strokeWidth={1.75} aria-hidden />
              <span className="text-caption">{t('videoNoteBubble.noImage')}</span>
            </div>
          )}

          {/* Пауза: тло — scrim теми, значок — на темній пігулці `.ph-on-photo`
              (вдень `text-ink-2` на чорному ставав темним на темному). */}
          {!isPlaying && url && !noPicture && (
            <div className="absolute inset-0 bg-scrim flex items-center justify-center">
              <div className="ph-on-photo w-12 h-12 rounded-full flex items-center justify-center">
                <Play className="w-6 h-6 ml-0.5 fill-current" aria-hidden="true" />
              </div>
            </div>
          )}

          {/* Sound Toast notification */}
          {soundToast && (
            <div className="ph-on-photo absolute top-3 inset-x-0 mx-auto w-fit px-2.5 py-1 rounded-full text-caption flex items-center gap-1 pointer-events-none f-fade-in">
              {isMuted ? (
                <>
                  <VolumeX className="w-3 h-3" aria-hidden="true" />
                  <span>{t('videoNoteBubble.muted')}</span>
                </>
              ) : (
                <>
                  <Volume2 className="w-3 h-3" aria-hidden="true" />
                  <span>{t('videoNoteBubble.soundOn')}</span>
                </>
              )}
            </div>
          )}
        </div>
        {/*
          Кнопка звуку — ПОЗА круглою маскою. Усередині `rounded-full
          overflow-hidden` правий нижній кут квадрата лежить за колом, і
          кнопку зрізало навпіл (A52, 02.10). Тут вона сидить на колі, під 45°.
        */}
        {url && (
          <button
            type="button"
            onClick={handleToggleSound}
            data-testid="video-note-sound"
            className="ph-on-photo touch-floor absolute bottom-[32px] right-[32px] w-8 h-8 rounded-full flex items-center justify-center transition-colors"
            aria-label={isMuted ? t('videoNoteBubble.unmute') : t('videoNoteBubble.mute')}
          >
            {isMuted ? <VolumeX className="w-4 h-4" strokeWidth={1.75} /> : <Volume2 className="w-4 h-4" strokeWidth={1.75} />}
          </button>
        )}
      </div>

      {/* Duration Badge */}
      {/* `duration && …` при 0 малювало саму цифру «0» під кружком (25.09). */}
      {videoNote?.duration ? (
        <span data-testid="video-note-duration" className="ph-on-photo absolute bottom-[30px] left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-full text-caption pointer-events-none">
          {Math.floor(videoNote.duration / 60)}:
          {Math.floor(videoNote.duration % 60) < 10
            ? `0${Math.floor(videoNote.duration % 60)}`
            : Math.floor(videoNote.duration % 60)}
        </span>
      ) : null}
    </div>
  );
};
