// Voice memos — quick-capture audio notes. Different surface than
// Vault audio (which is encrypted + PIN-gated): this is the
// shopping-list / "remember this" sketch, no auth required.

import { useEffect, useRef, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import {
  useVoiceMemosStore,
  storeMemoBlob,
  loadMemoBlob,
  type VoiceMemo,
} from '@/state/voiceMemosStore';
import type { PhantomTheme } from '@/types/theme';

interface VoiceMemosScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

function newId(): string {
  return `vm-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function fmtDur(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function VoiceMemosScreen({ themeOverride, framed = false }: VoiceMemosScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const memos = useVoiceMemosStore((s) => s.memos);
  const upsert = useVoiceMemosStore((s) => s.upsert);
  const remove = useVoiceMemosStore((s) => s.remove);
  const rename = useVoiceMemosStore((s) => s.rename);
  const setUrl = useVoiceMemosStore((s) => s.setUrl);
  const releaseUrl = useVoiceMemosStore((s) => s.releaseUrl);
  const urlCache = useVoiceMemosStore((s) => s.urlCache);

  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const startedAtRef = useRef<number>(0);
  const tickRef = useRef<number | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    return () => {
      if (tickRef.current !== null) window.clearInterval(tickRef.current);
      if (recorderRef.current && recorderRef.current.state !== 'inactive') {
        try {
          recorderRef.current.stop();
        } catch {
          /* ignore */
        }
      }
      if (streamRef.current) streamRef.current.getTracks().forEach((t) => t.stop());
    };
  }, []);

  const startRecord = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : 'audio/webm';
      const rec = new MediaRecorder(stream, { mimeType: mime });
      chunksRef.current = [];
      rec.ondataavailable = (ev) => {
        if (ev.data && ev.data.size > 0) chunksRef.current.push(ev.data);
      };
      rec.onstop = async () => {
        const blob = new Blob(chunksRef.current, { type: mime });
        const id = newId();
        const memo: VoiceMemo = {
          id,
          label: `Memo ${new Date().toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' })}`,
          ts: Date.now(),
          durationMs: Date.now() - startedAtRef.current,
          mime,
          bytes: blob.size,
        };
        try {
          await storeMemoBlob(id, blob);
          upsert(memo);
          useToastStore.getState().push('success', 'Memo збережено');
        } catch (err) {
          useToastStore.getState().push('error', (err as Error).message);
        }
        if (streamRef.current) {
          streamRef.current.getTracks().forEach((t) => t.stop());
          streamRef.current = null;
        }
      };
      rec.start();
      recorderRef.current = rec;
      startedAtRef.current = Date.now();
      setRecording(true);
      setElapsed(0);
      tickRef.current = window.setInterval(() => {
        setElapsed(Date.now() - startedAtRef.current);
      }, 200);
    } catch (err) {
      useToastStore.getState().push('error', `Mic: ${(err as Error).message}`);
    }
  };

  const stopRecord = () => {
    if (tickRef.current !== null) {
      window.clearInterval(tickRef.current);
      tickRef.current = null;
    }
    if (recorderRef.current && recorderRef.current.state === 'recording') {
      recorderRef.current.stop();
    }
    setRecording(false);
  };

  const playMemo = async (memo: VoiceMemo) => {
    let url = urlCache[memo.id];
    if (!url) {
      const blob = await loadMemoBlob(memo.id, memo.mime);
      if (!blob) {
        useToastStore.getState().push('warn', 'Audio не знайдено');
        return;
      }
      url = URL.createObjectURL(blob);
      setUrl(memo.id, url);
    }
    const audio = new Audio(url);
    void audio.play();
  };

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '12px 16px 8px' }}>
        <span
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11,
            letterSpacing: '0.22em',
            color: theme.ink3,
            fontWeight: 700,
            textTransform: 'uppercase',
          }}
        >
          🎙 voice memos · {memos.length}
        </span>
      </div>

      <div style={{ padding: '0 12px 8px' }}>
        {recording ? (
          <button
            onClick={stopRecord}
            style={{
              width: '100%',
              minHeight: 60,
              borderRadius: 14,
              background: theme.coral,
              color: '#fff',
              border: 'none',
              fontFamily: 'var(--mono)',
              fontSize: 14,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            ◼ stop · {fmtDur(elapsed)}
          </button>
        ) : (
          <button
            onClick={() => void startRecord()}
            style={{
              width: '100%',
              minHeight: 60,
              borderRadius: 14,
              background: theme.primary,
              color: theme.isDark ? '#1a1208' : '#fff',
              border: 'none',
              fontFamily: 'var(--mono)',
              fontSize: 14,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            ● запис
          </button>
        )}
      </div>

      <div
        style={{
          flex: 1,
          padding: '0 12px 14px',
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          minHeight: 0,
        }}
      >
        {memos.length === 0 ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="🎙"
              title="ще немає memo"
              hint="Натисни ● запис щоб зафіксувати думку. Локально, до 500 KB на memo."
            />
          </GlassCard>
        ) : (
          memos.map((m) => (
            <GlassCard
              key={m.id}
              level="card"
              theme={theme}
              style={{ padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  value={m.label}
                  onChange={(e) => rename(m.id, e.target.value)}
                  style={{
                    flex: 1,
                    minHeight: 32,
                    padding: '0 8px',
                    borderRadius: 8,
                    background: 'transparent',
                    border: `1px solid ${theme.glassBorder}`,
                    color: theme.ink,
                    fontFamily: 'var(--sans)',
                    fontSize: 13,
                  }}
                />
                <span style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3 }}>
                  {fmtDur(m.durationMs)}
                </span>
              </div>
              <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3, flex: 1 }}>
                  {(m.bytes / 1024).toFixed(0)} KB · {new Date(m.ts).toLocaleString('uk')}
                </span>
                <button
                  onClick={() => void playMemo(m)}
                  style={{
                    minWidth: 36,
                    minHeight: 28,
                    borderRadius: 6,
                    background: theme.primary,
                    color: theme.isDark ? '#1a1208' : '#fff',
                    border: `1px solid ${theme.primary}`,
                    fontSize: 12,
                  }}
                >
                  ▶
                </button>
                <button
                  onClick={() => {
                    releaseUrl(m.id);
                    remove(m.id);
                  }}
                  style={{
                    minWidth: 30,
                    minHeight: 28,
                    borderRadius: 6,
                    background: 'transparent',
                    color: theme.coral,
                    border: `1px solid ${theme.coral}55`,
                    fontSize: 11,
                  }}
                >
                  ×
                </button>
              </div>
            </GlassCard>
          ))
        )}
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
