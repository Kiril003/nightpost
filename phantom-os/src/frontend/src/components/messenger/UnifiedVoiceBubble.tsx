import React, { useEffect, useState, useRef, useCallback } from 'react';
import { Play, Pause, RotateCw } from 'lucide-react';
import type { Message } from '../../types/messenger';
import { messengerApi } from '../../services/messengerApi';
import { cachedMediaUrl, forgetMedia, humanSize, loadMedia, MediaNotArrived, MediaTampered } from '../../services/messengerMedia';
import { useAudioPlaybackStore } from '../../stores/audioPlaybackStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { readNet, shouldFetch } from '../../services/autoFetch';
import { HighlightedText } from './HighlightedText';
import { useTranslation } from '../../i18n/useTranslation';
import { loadPlayed, nextInRow, voiceQueueOf } from '../../services/voiceQueue';

interface UnifiedVoiceBubbleProps {
  msg: Message;
  chatSearchQuery?: string;
  isActiveSearchMatch?: boolean;
}

/** Хвиля голосового: до `max` смужок, висота 4…24 px, нормалізація за піком. */
export const voiceBars = (wave: number[], max = 40): number[] => {
  const clean = wave.filter((v) => Number.isFinite(v) && v >= 0);
  if (clean.length === 0) return [];
  const n = Math.min(max, clean.length);
  const out: number[] = [];
  for (let i = 0; i < n; i += 1) {
    const a = Math.floor((i * clean.length) / n);
    const b = Math.max(a + 1, Math.floor(((i + 1) * clean.length) / n));
    out.push(Math.max(...clean.slice(a, b)));
  }
  const peak = Math.max(...out) || 1;
  return out.map((v) => Math.round(4 + (v / peak) * 20));
};

export const UnifiedVoiceBubble: React.FC<UnifiedVoiceBubbleProps> = ({
  msg,
  chatSearchQuery,
  isActiveSearchMatch,
}) => {
  const { t } = useTranslation();
  const chat = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id)));
  const voice = msg.voiceData;
  if (!voice) return null;

  // Своє голосове ще вивантажується: blobId порожній, і питати вузол нема про
  // що. Доти бульбашка казала червоне «Голосове ще завантажується» — про лист,
  // який уже лежав у «Збереженому» (A52, 02.10). Грає з місцевого файла, як кружок.
  const uploadFailed = Boolean(msg.isSelf && msg.attachmentState === 'upload_failed');
  const uploading = !uploadFailed && !!voice.media && !voice.media.blobId;
  const directUrl = msg.localPreviewUrl || voice.audioUrl || (msg as unknown as { audioUrl?: string }).audioUrl;
  const rings = chat?.circles?.length ? chat.circles : [chat?.circle ?? ''];
  const allowed =
    !voice.media ||
    uploading ||
    !!directUrl ||
    !chat ||
    shouldFetch({
      kind: 'voice',
      sizeBytes: voice.media.size,
      rings,
      ...readNet(),
    });
  const [resolvedUrl, setResolvedUrl] = useState<string | undefined>(() => {
    if (uploading) return msg.localPreviewUrl;
    if (voice.media) return cachedMediaUrl(voice.media.blobId);
    return voice.audioUrl || (msg as unknown as { audioUrl?: string }).audioUrl;
  });
  const [loadError, setLoadError] = useState<string | null>(null);

  // Audio store granular selectors (zero unnecessary re-renders for other messages!)
  const isCurrentAudio = useAudioPlaybackStore((s) => s.currentMsgId === msg.id);
  const isPlaying = useAudioPlaybackStore((s) => s.currentMsgId === msg.id && s.isPlaying);
  const progress = useAudioPlaybackStore((s) => (s.currentMsgId === msg.id ? s.progress : 0));
  const currentTime = useAudioPlaybackStore((s) => (s.currentMsgId === msg.id ? s.currentTime : 0));
  const playbackRate = useAudioPlaybackStore((s) => s.playbackRate);
  const playStoreError = useAudioPlaybackStore((s) => (s.currentMsgId === msg.id ? s.error : null));
  const isNextUp = useAudioPlaybackStore(
    (s) => s.isPlaying && s.currentMsgId !== null && s.currentMsgId !== msg.id && s.queue.some((i) => i.id === msg.id) && nextInRow(s.queue, s.currentMsgId, loadPlayed())?.id === msg.id,
  );

  const toggle = useAudioPlaybackStore((s) => s.toggle);
  const seek = useAudioPlaybackStore((s) => s.seek);
  const cyclePlaybackRate = useAudioPlaybackStore((s) => s.cyclePlaybackRate);

  const waveformRef = useRef<HTMLDivElement>(null);
  const isDraggingWave = useRef(false);

  const fetchAudio = useCallback(() => {
    setLoadError(null);
    if (voice.media && !voice.media.blobId) {
      if (msg.localPreviewUrl) setResolvedUrl(msg.localPreviewUrl);
      return;
    }
    if (!voice.media) {
      const url = voice.audioUrl || (msg as unknown as { audioUrl?: string }).audioUrl;
      if (url) {
        setResolvedUrl(url);
      } else {
        setLoadError(t('unifiedVoiceBubble.unavailable'));
      }
      return;
    }

    void loadMedia(voice.media)
      .then((url) => {
        setResolvedUrl(url);
        setLoadError(null);
      })
      .catch((e) => {
        if (e instanceof MediaNotArrived) {
          setLoadError(t('unifiedVoiceBubble.loading'));
        } else if (e instanceof MediaTampered) {
          setLoadError(t('unifiedVoiceBubble.tampered'));
        } else {
          setLoadError(t('unifiedVoiceBubble.openFailed'));
        }
      });
  }, [voice.media, voice.audioUrl, msg, t]);

  useEffect(() => {
    if (!resolvedUrl && allowed) {
      fetchAudio();
    }
  }, [resolvedUrl, fetchAudio, allowed]);

  const handleRetry = async () => {
    if (uploadFailed) {
      await useMessengerStore.getState().retrySend(msg.id);
      return;
    }
    if (voice.media?.blobId) {
      if (!voice.media.carrier) {
        await messengerApi.requestBlob(voice.media.blobId).catch(() => undefined);
      }
      forgetMedia(voice.media.blobId);
    }
    fetchAudio();
  };

  const handleTogglePlay = () => {
    if (!resolvedUrl) {
      fetchAudio();
      return;
    }
    toggle(msg.id, resolvedUrl, voice.duration, voiceQueueOf(chat?.messages ?? []));
  };

  const handleWaveSeek = (clientX: number) => {
    if (!waveformRef.current) return;
    const rect = waveformRef.current.getBoundingClientRect();
    if (rect.width <= 0) return;
    const fraction = (clientX - rect.left) / rect.width;
    seek(Math.min(1, Math.max(0, fraction)));
  };

  const onMouseDownWave = (e: React.MouseEvent) => {
    if (!resolvedUrl) return;
    isDraggingWave.current = true;
    handleWaveSeek(e.clientX);

    const onMouseMove = (ev: MouseEvent) => {
      if (isDraggingWave.current) {
        handleWaveSeek(ev.clientX);
      }
    };
    const onMouseUp = () => {
      isDraggingWave.current = false;
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
  };

  const onTouchStartWave = (e: React.TouchEvent) => {
    if (!resolvedUrl || e.touches.length === 0) return;
    handleWaveSeek(e.touches[0].clientX);
  };

  const onTouchMoveWave = (e: React.TouchEvent) => {
    if (!resolvedUrl || e.touches.length === 0) return;
    handleWaveSeek(e.touches[0].clientX);
  };

  // Хвиля — лише з реальних семплів запису. Немає їх — смуга прогресу, а не
  // намальована «хвиля», яка вдає, що хтось її міряв. Смужки 2 через 2,
  // висота 4…24, не більше 40, нормалізовано за піком (біблія 05 §2.6).
  const bars = voiceBars(voice.waveform ?? [], 40);
  const hasWave = bars.length >= 8;

  const formatSec = (s: number) => {
    const mins = Math.floor(s / 60);
    const secs = Math.floor(s % 60);
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  return (
    <div data-testid="voice-media-bubble" className="pt-0.5 w-full min-w-[240px] max-w-[300px] select-none">
      <div className="flex items-center gap-3">
        {/* Відтворення — коло 40 кольору тексту, трикутник кольору самої
            бульбашки (вирізаний у диску); ціль дотику 44. */}
        <button
          type="button"
          onClick={handleTogglePlay}
          aria-label={isPlaying ? t('unifiedVoiceBubble.pause') : t('unifiedVoiceBubble.play')}
          data-testid="voice-play"
          className="touch-floor-inline ph-press w-10 h-10 rounded-full flex items-center justify-center bg-ink shrink-0 cursor-pointer"
          style={{ color: 'var(--ph-voice-glyph, var(--ph-surface))' }}
        >
          {isPlaying ? (
            <Pause className="w-[18px] h-[18px]" fill="currentColor" strokeWidth={0} />
          ) : (
            <Play className="w-[18px] h-[18px] ml-0.5" fill="currentColor" strokeWidth={0} />
          )}
        </button>

        {/* Waveform Scrubber */}
        <div
          ref={waveformRef}
          onMouseDown={onMouseDownWave}
          onTouchStart={onTouchStartWave}
          onTouchMove={onTouchMoveWave}
          className="flex-1 min-w-0 flex items-center justify-between h-7 relative overflow-hidden cursor-pointer"
          title={t('unifiedVoiceBubble.seek')}
        >
          {hasWave ? (
            bars.map((h, i) => {
              const isFilled = isCurrentAudio && (i + 1) / bars.length <= progress;
              return (
                <span
                  key={i}
                  data-played={isFilled || undefined}
                  className={`w-[2px] shrink-0 rounded-full pointer-events-none transition-colors duration-quick ${isFilled ? 'bg-ink' : 'bg-ink-3'}`}
                  style={{ height: `${h}px`, opacity: isFilled ? 1 : 0.7 }}
                />
              );
            })
          ) : (
            <span className="relative flex-1 h-1 rounded-full bg-line overflow-hidden pointer-events-none">
              <span
                className="absolute inset-y-0 left-0 bg-ink"
                style={{ width: `${isCurrentAudio ? Math.round(progress * 100) : 0}%` }}
              />
            </span>
          )}
        </div>

        {isNextUp && (
          <span data-testid="voice-next-up" className="ml-auto shrink-0 text-caption text-ink-3">
            {t('media25.nextUp')}
          </span>
        )}
        <span className={`${isNextUp ? '' : 'ml-auto '}shrink-0 text-caption text-ink-3 tnum`} data-testid="voice-duration">
          {isPlaying && isCurrentAudio ? formatSec(currentTime) : formatSec(voice.duration)}
        </span>
        {isCurrentAudio && resolvedUrl && (
          <button
            type="button"
            onClick={cyclePlaybackRate}
            className="touch-floor-inline shrink-0 px-1.5 rounded-full text-caption font-semibold bg-raised text-ink-2 cursor-pointer"
            title={t('unifiedVoiceBubble.speed')}
          >
            {playbackRate}×
          </button>
        )}
      </div>

      {/* Розшифровка — просто текст під хвилею: її читають, а не розгортають. */}
      {voice.transcript && (
        <p data-testid="voice-transcript" className="mt-2 pl-1 text-callout leading-[21px] text-ink-2 select-text">
          «
          <HighlightedText text={voice.transcript} query={chatSearchQuery} activeMatch={isActiveSearchMatch} />
          »
        </p>
      )}

      {!allowed && !resolvedUrl && !loadError && !playStoreError && voice.media && (
        <p className="mt-1.5 text-caption text-ink-3" data-testid="voice-manual">
          {t('autoFetch.tap', { size: humanSize(voice.media.size) })}
        </p>
      )}

      {/* Errors and Retry */}
      {(uploadFailed || loadError || playStoreError) && (
        <div data-voice-failed className="mt-1 flex items-center gap-1.5 text-caption text-ink-3">
          <span className="min-w-0 truncate">{uploadFailed ? msg.attachmentError || t('unifiedVoiceBubble.openFailed') : loadError || playStoreError}</span>
          <button
            type="button"
            onClick={() => void handleRetry()}
            aria-label={t('unifiedVoiceBubble.retry')}
            title={t('unifiedVoiceBubble.retry')}
            className="touch-floor-inline relative shrink-0 inline-flex items-center justify-center w-6 h-6 rounded-full text-ink-2 cursor-pointer"
          >
            <RotateCw className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
          </button>
        </div>
      )}
    </div>
  );
};
UnifiedVoiceBubble.displayName = 'UnifiedVoiceBubble';
