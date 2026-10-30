import { useEffect, useRef, useState } from 'react';
import { Modal } from './Modal';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useT } from '@/hooks/useT';
import { recordAudioMemo, type AudioMemoHandle } from '@/services/audioMemo';

const MAX_SEC = 60;

interface AudioRecordModalProps {
  open: boolean;
  onClose: () => void;
  onSeal: (blob: Blob, mime: string, durationMs: number) => Promise<void> | void;
  onError?: (err: Error) => void;
}

export function AudioRecordModal({ open, onClose, onSeal, onError }: AudioRecordModalProps) {
  const { theme } = useThemeContext();
  const t = useT();
  const [phase, setPhase] = useState<'idle' | 'recording' | 'sealed'>('idle');
  const [seconds, setSeconds] = useState(0);
  const handleRef = useRef<AudioMemoHandle | null>(null);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastBlobRef = useRef<{ blob: Blob; mime: string; durationMs: number } | null>(null);

  useEffect(() => {
    if (!open) {
      // Hard reset on close.
      handleRef.current?.cancel();
      handleRef.current = null;
      if (tickRef.current) clearInterval(tickRef.current);
      tickRef.current = null;
      setPhase('idle');
      setSeconds(0);
      lastBlobRef.current = null;
    }
  }, [open]);

  const startRecording = async () => {
    try {
      const h = await recordAudioMemo();
      handleRef.current = h;
      setPhase('recording');
      setSeconds(0);
      const startedAt = performance.now();
      tickRef.current = setInterval(() => {
        const s = Math.min(MAX_SEC, Math.floor((performance.now() - startedAt) / 1000));
        setSeconds(s);
        if (s >= MAX_SEC) {
          void stopRecording();
        }
      }, 200);
    } catch (err) {
      onError?.(err as Error);
    }
  };

  const stopRecording = async () => {
    if (!handleRef.current) return;
    if (tickRef.current) clearInterval(tickRef.current);
    tickRef.current = null;
    try {
      const out = await handleRef.current.stop();
      handleRef.current = null;
      lastBlobRef.current = out;
      setPhase('sealed');
    } catch (err) {
      onError?.(err as Error);
      onClose();
    }
  };

  const sealAndClose = async () => {
    const out = lastBlobRef.current;
    if (!out) {
      onClose();
      return;
    }
    await onSeal(out.blob, out.mime, out.durationMs);
    onClose();
  };

  const cancel = () => {
    handleRef.current?.cancel();
    handleRef.current = null;
    if (tickRef.current) clearInterval(tickRef.current);
    tickRef.current = null;
    onClose();
  };

  const dur = lastBlobRef.current
    ? `${Math.round(lastBlobRef.current.durationMs / 1000)}s`
    : `${seconds}s`;

  return (
    <Modal open={open} onClose={cancel} title={t('vault.audio.modal.title')}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16, padding: '4px 0 8px' }}>
        <button
          onClick={phase === 'recording' ? stopRecording : phase === 'idle' ? startRecording : undefined}
          aria-label={phase === 'recording' ? t('vault.audio.modal.stop') : t('vault.audio.modal.start')}
          disabled={phase === 'sealed'}
          style={{
            width: 110,
            height: 110,
            borderRadius: '50%',
            background: phase === 'recording'
              ? `radial-gradient(circle at 30% 25%, ${theme.coral}, ${theme.coral}cc)`
              : `radial-gradient(circle at 30% 25%, ${theme.primary}, ${theme.primary}cc)`,
            color: theme.isDark ? '#1a1208' : '#fff',
            fontSize: 44,
            fontWeight: 600,
            boxShadow: phase === 'recording'
              ? `0 0 38px ${theme.coral}aa, 0 14px 28px ${theme.coral}55`
              : `0 14px 28px ${theme.primary}55`,
            border: 'none',
            transition: 'transform 0.12s, box-shadow 0.18s',
            transform: phase === 'recording' ? 'scale(1.04)' : 'scale(1)',
          }}
        >
          {phase === 'recording' ? '■' : '🎙'}
        </button>
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 12,
            color: theme.ink2,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            fontWeight: 700,
            minHeight: 16,
            textAlign: 'center',
          }}
        >
          {phase === 'idle' && t('vault.audio.modal.idle')}
          {phase === 'recording' && t('vault.audio.modal.recording', { sec: String(seconds) })}
          {phase === 'sealed' && `✓ ${dur}`}
        </div>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, textAlign: 'center', maxWidth: 240 }}>
          {t('vault.audio.modal.limit_hint')}
        </div>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
        <button
          onClick={cancel}
          style={{
            flex: 1,
            padding: '11px 14px',
            borderRadius: 12,
            background: 'transparent',
            color: theme.ink2,
            border: `1px solid ${theme.glassBorder}`,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          {t('vault.audio.modal.cancel')}
        </button>
        <button
          onClick={sealAndClose}
          disabled={phase !== 'sealed'}
          style={{
            flex: 1,
            padding: '11px 14px',
            borderRadius: 12,
            background: phase === 'sealed' ? '#16A34A' : theme.glassBorder,
            color: phase === 'sealed' ? '#fff' : theme.ink3,
            border: 'none',
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          {t('vault.audio.modal.seal', { dur })}
        </button>
      </div>
    </Modal>
  );
}
