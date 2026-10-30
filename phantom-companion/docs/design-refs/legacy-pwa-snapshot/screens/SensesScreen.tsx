// Phase 3 Senses — camera shell with FaceMesh overlay + OCR capture.
//
// Mounts a `<video>` driven by `getUserMedia` and a `<canvas>` overlay
// for face landmark dots. The FaceMesh detector pushes head-pose
// samples into a `GestureClassifier`; recognised gestures fire as
// `agent / gesture` frames over wsHub so the desktop's ContextEngine
// can react (e.g., a nod approves a pending Council action, a shake
// declines).
//
// OCR button captures the current frame, runs Tesseract.js on it, and
// posts the recognised text to the desktop chat as `agent / ocr_capture`.
// Confidence + word bboxes are included so the desktop can render an
// inline "review highlights" hint.
//
// ARCore overlay path (the SENTINEL "X-ray" mode in the canonical plan)
// is intentionally NOT here — WebXR has no AR plane detection on
// Capacitor Android, and a half-shipped AR demo would feel worse than
// a clearly missing one. Phase 4.5 / native track owns SENTINEL AR.

import { useCallback, useEffect, useRef, useState } from 'react';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { wsHub } from '@/services/wsHub';
import { FaceMeshDetector } from '@/services/faceMeshDetector';
import { GestureClassifier, type GestureEvent } from '@/services/gestureClassifier';
import { recognize, type OcrResult } from '@/services/ocrCapture';
import type { PhantomTheme } from '@/types/theme';

interface SensesScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

type CameraStatus = 'idle' | 'starting' | 'streaming' | 'denied' | 'error';

export function SensesScreen({ themeOverride, framed = false }: SensesScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const detectorRef = useRef<FaceMeshDetector | null>(null);
  const classifierRef = useRef<GestureClassifier | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const [cameraStatus, setCameraStatus] = useState<CameraStatus>('idle');
  const [lastGesture, setLastGesture] = useState<GestureEvent | null>(null);
  const [ocrResult, setOcrResult] = useState<OcrResult | null>(null);
  const [ocrBusy, setOcrBusy] = useState(false);

  // Drive lifecycle with a stable stop fn so React StrictMode + nav
  // teardown both flush the camera + WASM detector promptly.
  const stop = useCallback(() => {
    detectorRef.current?.stop();
    detectorRef.current = null;
    classifierRef.current?.reset();
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setCameraStatus('idle');
  }, []);

  const start = useCallback(async () => {
    if (cameraStatus === 'starting' || cameraStatus === 'streaming') return;
    setCameraStatus('starting');
    try {
      const media = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: 'user',
          width: { ideal: 640 },
          height: { ideal: 480 },
        },
        audio: false,
      });
      streamRef.current = media;
      const video = videoRef.current;
      if (!video) throw new Error('Video element not mounted');
      video.srcObject = media;
      video.muted = true;
      await video.play();

      const detector = new FaceMeshDetector();
      detectorRef.current = detector;
      classifierRef.current = new GestureClassifier();
      await detector.start(video, (frame) => {
        const canvas = canvasRef.current;
        if (canvas) {
          // Match canvas internal resolution to video so coordinates align.
          if (canvas.width !== frame.width) canvas.width = frame.width;
          if (canvas.height !== frame.height) canvas.height = frame.height;
          const cx = canvas.getContext('2d');
          if (cx) {
            cx.clearRect(0, 0, canvas.width, canvas.height);
            cx.fillStyle = theme.primary;
            for (const lm of frame.landmarks) {
              const x = lm.x * canvas.width;
              const y = lm.y * canvas.height;
              cx.fillRect(x - 1, y - 1, 2, 2);
            }
          }
        }
        if (frame.pose && classifierRef.current) {
          const ev = classifierRef.current.push(frame.pose);
          if (ev) {
            setLastGesture(ev);
            wsHub.send('agent', 'gesture', {
              source: 'phone-camera',
              gesture: ev.gesture,
              amplitude: ev.amplitude,
              start_ts_ms: ev.startTsMs,
              end_ts_ms: ev.endTsMs,
            });
          }
        }
      });
      setCameraStatus('streaming');
    } catch (err) {
      const e = err as DOMException;
      if (e?.name === 'NotAllowedError' || e?.name === 'PermissionDeniedError') {
        setCameraStatus('denied');
        useToastStore.getState().push('warn', 'Дозволь доступ до камери в налаштуваннях');
      } else {
        setCameraStatus('error');
        useToastStore.getState().push('error', `Camera: ${(err as Error).message}`);
      }
    }
  }, [cameraStatus, theme.primary]);

  // Stop camera + detector on unmount — never leave a foreground
  // MediaStream alive after the user navigates away.
  useEffect(() => {
    return () => stop();
  }, [stop]);

  const handleOcr = useCallback(async () => {
    if (ocrBusy) return;
    const video = videoRef.current;
    if (!video || video.readyState < 2) {
      useToastStore.getState().push('warn', 'Спочатку увімкни камеру');
      return;
    }
    setOcrBusy(true);
    try {
      // Snapshot the current frame into an off-screen canvas; Tesseract
      // accepts canvas blobs directly so we skip a base64 round-trip.
      const snap = document.createElement('canvas');
      snap.width = video.videoWidth;
      snap.height = video.videoHeight;
      const cx = snap.getContext('2d');
      if (!cx) throw new Error('Canvas 2D context unavailable');
      cx.drawImage(video, 0, 0, snap.width, snap.height);
      const result = await recognize(snap);
      setOcrResult(result);
      if (result.text) {
        wsHub.send('agent', 'ocr_capture', {
          source: 'phone-camera',
          text: result.text,
          confidence: result.confidence,
          duration_ms: result.durationMs,
          word_count: result.words.length,
        });
        useToastStore.getState().push('success', `OCR: ${result.text.split('\n')[0] ?? ''}`);
      } else {
        useToastStore.getState().push('info', 'Текст не розпізнано');
      }
    } catch (err) {
      useToastStore.getState().push('error', `OCR: ${(err as Error).message}`);
    } finally {
      setOcrBusy(false);
    }
  }, [ocrBusy]);

  const isStreaming = cameraStatus === 'streaming';

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          padding: '8px 16px 0',
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
          minHeight: 0,
          overflowY: 'auto',
        }}
      >
        <GlassCard
          level="panel"
          theme={theme}
          accentBorder
          accent={theme.primary}
          style={{
            position: 'relative',
            padding: 0,
            overflow: 'hidden',
            aspectRatio: '4 / 3',
            background: '#000',
          }}
        >
          <video
            ref={videoRef}
            playsInline
            muted
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'cover',
              display: isStreaming ? 'block' : 'none',
              transform: 'scaleX(-1)',
            }}
          />
          <canvas
            ref={canvasRef}
            style={{
              position: 'absolute',
              inset: 0,
              width: '100%',
              height: '100%',
              pointerEvents: 'none',
              transform: 'scaleX(-1)',
            }}
          />
          {!isStreaming && (
            <div
              style={{
                position: 'absolute',
                inset: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: theme.ink2,
                fontFamily: 'var(--mono)',
                fontSize: 12,
                letterSpacing: '0.2em',
                textTransform: 'uppercase',
              }}
            >
              {cameraStatus === 'starting'
                ? 'Запускаю камеру…'
                : cameraStatus === 'denied'
                ? 'Доступ до камери заборонено'
                : cameraStatus === 'error'
                ? 'Помилка камери'
                : 'Камера вимкнена'}
            </div>
          )}
        </GlassCard>

        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {!isStreaming ? (
            <button
              onClick={() => void start()}
              style={{
                flex: '1 1 auto',
                minHeight: 44,
                padding: '0 16px',
                borderRadius: 14,
                border: `1px solid ${theme.glassBorder}`,
                background: theme.primary,
                color: theme.isDark ? '#1a1208' : '#fff',
                fontFamily: 'var(--mono)',
                fontSize: 12,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              Увімкнути камеру
            </button>
          ) : (
            <button
              onClick={() => stop()}
              style={{
                flex: '0 1 auto',
                minHeight: 44,
                padding: '0 16px',
                borderRadius: 14,
                border: `1px solid ${theme.glassBorder}`,
                background: 'transparent',
                color: theme.ink2,
                fontFamily: 'var(--mono)',
                fontSize: 11,
                fontWeight: 600,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              Зупинити
            </button>
          )}

          <button
            onClick={() => void handleOcr()}
            disabled={!isStreaming || ocrBusy}
            style={{
              flex: '1 1 auto',
              minHeight: 44,
              padding: '0 16px',
              borderRadius: 14,
              border: `1px solid ${isStreaming ? theme.primary : theme.glassBorder}`,
              background: isStreaming ? 'transparent' : 'rgba(0,0,0,0.05)',
              color: isStreaming ? theme.primary : theme.ink3,
              fontFamily: 'var(--mono)',
              fontSize: 12,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              opacity: ocrBusy ? 0.6 : 1,
            }}
          >
            {ocrBusy ? 'OCR…' : 'OCR кадр'}
          </button>
        </div>

        <GlassCard level="card" theme={theme} style={{ padding: '12px 16px' }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 600,
              marginBottom: 6,
            }}
          >
            Останній жест
          </div>
          <div style={{ fontSize: 14, color: theme.ink, fontWeight: 500 }}>
            {lastGesture
              ? `${lastGesture.gesture} · ${lastGesture.amplitude.toFixed(2)} рад`
              : 'Чекаю на голову'}
          </div>
        </GlassCard>

        {ocrResult && (
          <GlassCard level="card" theme={theme} style={{ padding: '12px 16px' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                color: theme.ink3,
                fontWeight: 600,
                marginBottom: 6,
              }}
            >
              OCR · {ocrResult.confidence.toFixed(0)}% · {ocrResult.durationMs.toFixed(0)} мс
            </div>
            <div
              style={{
                fontSize: 13,
                color: theme.ink,
                whiteSpace: 'pre-wrap',
                maxHeight: 140,
                overflowY: 'auto',
              }}
            >
              {ocrResult.text || '—'}
            </div>
          </GlassCard>
        )}
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
