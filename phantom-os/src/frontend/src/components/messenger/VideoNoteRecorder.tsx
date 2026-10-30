import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Camera, RefreshCw, Send, Trash2 } from 'lucide-react';
import { soundFx } from '../../utils/messengerSound';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';

interface VideoNoteRecorderProps {
  onSend: (blob: Blob, durationS: number) => void;
  onCancel: () => void;
}

const MAX_DURATION_S = 60;

/**
 * Відмова камери — людським реченням за `err.name`, ніколи сирим
 * `err.message`: WebView A52 казав англійською «Not supported», і саме це
 * людина й бачила. Усе, що не «заборонено» і не «нема камери» (зокрема
 * NotSupportedError і голий Error), — «недоступна на цьому пристрої».
 */
const cameraRefusal = (err: unknown, t: Translation['t']): string => {
  const name = err && typeof err === 'object' ? (err as { name?: unknown }).name : undefined;
  if (name === 'NotAllowedError') return t('videoNoteRecorder.denied');
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return t('videoNoteRecorder.notFound');
  return t('videoNoteRecorder.unavailable');
};

export const VideoNoteRecorder: React.FC<VideoNoteRecorderProps> = ({ onSend, onCancel }) => {
  const { t } = useTranslation();
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const isMountedRef = useRef(true);
  const elapsedRef = useRef(0);
  const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [isRecording, setIsRecording] = useState(false);
  const [take, setTake] = useState(0);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const stopStream = useCallback(() => {
    if (videoRef.current) {
      try {
        videoRef.current.pause();
        videoRef.current.srcObject = null;
      } catch {
        /* ignore */
      }
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => {
        try {
          track.stop();
        } catch {
          /* ignore */
        }
      });
      streamRef.current = null;
    }
  }, []);

  const initCamera = useCallback(async (mode: 'user' | 'environment') => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      try {
        mediaRecorderRef.current.onstop = null;
        mediaRecorderRef.current.stop();
      } catch {
        /* ignore */
      }
    }
    stopStream();
    setErrorMsg(null);
    let capturedStream: MediaStream | null = null;
    try {
      const constraints: MediaStreamConstraints = {
        video: {
          facingMode: mode,
          width: { ideal: 480 },
          height: { ideal: 480 },
          aspectRatio: { ideal: 1 },
        },
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      };

      capturedStream = await navigator.mediaDevices.getUserMedia(constraints);
      if (!isMountedRef.current) {
        capturedStream.getTracks().forEach((track) => track.stop());
        return;
      }

      streamRef.current = capturedStream;
      if (videoRef.current) {
        videoRef.current.srcObject = capturedStream;
        await Promise.resolve()
          .then(() => videoRef.current?.play())
          .catch(() => {});
      }

      let mimeType = 'video/webm;codecs=vp8,opus';
      if (!MediaRecorder.isTypeSupported(mimeType)) {
        mimeType = MediaRecorder.isTypeSupported('video/mp4') ? 'video/mp4' : '';
      }

      const recorder = mimeType ? new MediaRecorder(capturedStream, { mimeType }) : new MediaRecorder(capturedStream);
      mediaRecorderRef.current = recorder;
      chunksRef.current = [];
      elapsedRef.current = 0;
      setElapsedSeconds(0);
      setTake((n) => n + 1);

      recorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) {
          chunksRef.current.push(e.data);
        }
      };

      recorder.start(250);
      setIsRecording(true);
      soundFx.playTap();
    } catch (err: unknown) {
      if (capturedStream) {
        capturedStream.getTracks().forEach((track) => track.stop());
      }
      if (isMountedRef.current) {
        setErrorMsg(cameraRefusal(err, t));
      }
    }
  }, [stopStream, t]);

  useEffect(() => {
    isMountedRef.current = true;
    void initCamera(facingMode);
    return () => {
      isMountedRef.current = false;
      stopStream();
      if (timerRef.current) {
        cancelAnimationFrame(timerRef.current as unknown as number);
      }
    };
  }, [facingMode, initCamera, stopStream]);

  const finishAndSend = useCallback(() => {
    if (timerRef.current) {
      cancelAnimationFrame(timerRef.current as unknown as number);
      timerRef.current = null;
    }
    if (!mediaRecorderRef.current || mediaRecorderRef.current.state === 'inactive') {
      stopStream();
      onCancel();
      return;
    }

    const currentDuration = Math.max(1, Math.round(elapsedRef.current));
    mediaRecorderRef.current.onstop = () => {
      const blob = new Blob(chunksRef.current, {
        type: mediaRecorderRef.current?.mimeType || 'video/webm',
      });
      stopStream();
      soundFx.playTap();
      onSend(blob, currentDuration);
    };

    try {
      mediaRecorderRef.current.stop();
    } catch {
      stopStream();
      onCancel();
    }
  }, [onCancel, onSend, stopStream]);

  // 120 FPS high-precision RAF timer for silky-smooth circular progress
  useEffect(() => {
    if (!isRecording) return;
    const startTime = performance.now() - elapsedRef.current * 1000;
    let animId: number;

    const tick = () => {
      const now = performance.now();
      const elapsed = (now - startTime) / 1000;
      elapsedRef.current = elapsed;
      if (elapsed >= MAX_DURATION_S) {
        elapsedRef.current = MAX_DURATION_S;
        setElapsedSeconds(MAX_DURATION_S);
        finishAndSend();
        return;
      }
      setElapsedSeconds(elapsed);
      animId = requestAnimationFrame(tick);
    };

    animId = requestAnimationFrame(tick);
    return () => {
      if (animId) cancelAnimationFrame(animId);
    };
  }, [isRecording, take, finishAndSend]);

  const handleFlipCamera = () => {
    soundFx.playTap();
    setFacingMode((m) => (m === 'user' ? 'environment' : 'user'));
  };

  const handleCancel = () => {
    soundFx.playTap();
    if (timerRef.current) {
      cancelAnimationFrame(timerRef.current as unknown as number);
      timerRef.current = null;
    }
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      try {
        mediaRecorderRef.current.onstop = null;
        mediaRecorderRef.current.stop();
      } catch {
        /* ignore */
      }
    }
    stopStream();
    onCancel();
  };

  const cancelRef = useRef(handleCancel);
  cancelRef.current = handleCancel;
  useEffect(() => registerPhoneLayer('video-note-recorder', true, () => cancelRef.current()), []);

  const progressPercent = Math.min(100, (elapsedSeconds / MAX_DURATION_S) * 100);
  const displaySeconds = Math.min(MAX_DURATION_S, Math.floor(elapsedSeconds));
  const radius = 126;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (progressPercent / 100) * circumference;

  // Тло — суцільне полотно теми, як у дзвінку й переглядачі фото: крізь
  // напівпрозорий scrim (04.10, знімки 390×844) просвічувала стрічка, і
  // «Скасувати» лягало просто на чужі бульбашки — вдень особливо.
  // Денний блок messenger.css фарбує КОЖЕН [role=dialog] світлою поверхнею з
  // рамкою й тінню; тло й «без рамки» — інлайном, щоб воно лишалось полотном.
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col items-center justify-start pt-[max(calc(var(--sat,0px)+16px),calc(33vh-134px))] select-none touch-none"
      style={{ backgroundColor: 'var(--ph-canvas)', border: 0, boxShadow: 'none' }}
      role="dialog"
      aria-modal="true"
      aria-label={t('videoNoteRecorder.dialog')}
    >
      <div className="relative flex flex-col items-center">
        <div className="relative w-[268px] h-[268px] flex items-center justify-center">
          <svg className="absolute inset-0 w-full h-full -rotate-90" viewBox="0 0 268 268" aria-hidden="true">
            <circle cx="134" cy="134" r={radius} className="text-line-strong stroke-current" strokeWidth="4" fill="transparent" />
            {!errorMsg && (
              <circle
                cx="134"
                cy="134"
                r={radius}
                className="text-accent stroke-current transition-all duration-75 ease-linear"
                strokeWidth="5"
                strokeDasharray={circumference}
                strokeDashoffset={strokeDashoffset}
                strokeLinecap="round"
                fill="transparent"
              />
            )}
          </svg>

          <div className="w-[240px] h-[240px] rounded-full overflow-hidden bg-surface relative flex items-center justify-center">
            <video
              ref={videoRef}
              playsInline
              muted
              autoPlay
              className={`w-full h-full object-cover ${facingMode === 'user' ? '-scale-x-100' : ''}`}
            />
            {errorMsg && (
              <div className="absolute inset-0 bg-surface flex flex-col items-center justify-center gap-2 p-6 text-center" role="alert">
                <Camera className="w-8 h-8 text-danger" strokeWidth={1.75} aria-hidden="true" />
                <span className="text-callout text-danger">{errorMsg}</span>
              </div>
            )}
          </div>
        </div>

        {errorMsg ? (
          // Камери нема — таймер, замок, перемикач і «Надіслати» нічого б не
          // зробили. Лишається одна дія: піти.
          <button
            type="button"
            onClick={handleCancel}
            className="mt-6 h-12 px-8 rounded-full bg-raised text-headline text-ink active:scale-95 transition-transform"
          >
            {t('videoNoteRecorder.cancel')}
          </button>
        ) : (
          <>
            <div className="mt-4 flex items-center gap-2 px-3 py-1.5 rounded-full bg-raised text-footnote tnum">
              <span className="w-2 h-2 rounded-full bg-danger" aria-hidden="true" />
              <span className="text-ink font-semibold">
                0:{displaySeconds < 10 ? `0${displaySeconds}` : displaySeconds} / 1:00
              </span>
            </div>

            <div className="mt-6 flex items-center gap-6">
              <button
                type="button"
                onClick={handleCancel}
                className="w-12 h-12 min-h-[44px] min-w-[44px] rounded-full bg-raised text-danger flex items-center justify-center active:scale-95 transition-transform"
                aria-label={t('videoNoteRecorder.cancelRecording')}
              >
                <Trash2 className="w-5 h-5" strokeWidth={1.75} />
              </button>

              <button
                type="button"
                onClick={handleFlipCamera}
                className="w-12 h-12 min-h-[44px] min-w-[44px] rounded-full bg-raised text-ink-2 flex items-center justify-center active:scale-95 transition-transform"
                aria-label={t('videoNoteRecorder.flip')}
              >
                <RefreshCw className="w-5 h-5" strokeWidth={1.75} />
              </button>

              <button
                type="button"
                onClick={finishAndSend}
                className="w-12 h-12 min-h-[44px] min-w-[44px] rounded-full bg-accent text-on-accent flex items-center justify-center active:scale-95 transition-transform"
                aria-label={t('videoNoteRecorder.send')}
              >
                <Send className="w-6 h-6 ml-0.5" strokeWidth={1.75} />
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
