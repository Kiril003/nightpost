// Phase 5 Driver — phone-controls-desktop control surface.
//
// Three actions from one screen:
//   1. Type-into-desktop. Sends typed text as a draft on the desktop's
//      chat input via `drive/text`. Optional "submit" toggles whether
//      the desktop should also fire the AI immediately.
//   2. Screen pull. Requests a one-shot PNG of the desktop, renders
//      it as a small zoomable preview. Phase 5.5 will swap to MJPEG.
//   3. File drop. Lets the operator pick a photo / document from the
//      phone's storage and POST it to `~/.phantom/drop/<user_id>/`.
//
// All three sit behind `PairingGate` so an unpaired phone can't drive
// a stranger's desktop just by knowing the URL.

import { useCallback, useRef, useState } from 'react';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import {
  driveText,
  driveScreen,
  driveUpload,
  type DriveScreenResult,
} from '@/services/driveClient';
import type { PhantomTheme } from '@/types/theme';

interface DriverScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function DriverScreen({ themeOverride, framed = false }: DriverScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;

  const [draft, setDraft] = useState('');
  const [submitOnSend, setSubmitOnSend] = useState(false);
  const [busy, setBusy] = useState<'idle' | 'text' | 'screen' | 'upload'>('idle');
  const [screen, setScreen] = useState<DriveScreenResult | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const handleSend = useCallback(async () => {
    if (!draft.trim() || busy !== 'idle') return;
    setBusy('text');
    try {
      const out = await driveText({ text: draft, submit: submitOnSend });
      useToastStore
        .getState()
        .push('success', out.submitted ? `Сабміт ${out.chars} симв.` : `Чернетка ${out.chars} симв.`);
      setDraft('');
    } catch (err) {
      useToastStore.getState().push('error', `Drive/text: ${(err as Error).message}`);
    } finally {
      setBusy('idle');
    }
  }, [draft, submitOnSend, busy]);

  const handleScreen = useCallback(async () => {
    if (busy !== 'idle') return;
    setBusy('screen');
    try {
      const out = await driveScreen();
      setScreen(out);
      useToastStore.getState().push('info', `Знімок ${out.width}×${out.height}`);
    } catch (err) {
      useToastStore.getState().push('error', `Drive/screen: ${(err as Error).message}`);
    } finally {
      setBusy('idle');
    }
  }, [busy]);

  const handleFilePick = useCallback(() => {
    fileRef.current?.click();
  }, []);

  const handleFileChosen = useCallback(
    async (ev: React.ChangeEvent<HTMLInputElement>) => {
      const f = ev.target.files?.[0];
      ev.target.value = '';
      if (!f || busy !== 'idle') return;
      setBusy('upload');
      try {
        const out = await driveUpload(f);
        useToastStore.getState().push('success', `Завантажено ${(out.bytes / 1024).toFixed(0)} KB`);
      } catch (err) {
        useToastStore.getState().push('error', `Drive/upload: ${(err as Error).message}`);
      } finally {
        setBusy('idle');
      }
    },
    [busy],
  );

  const buttonStyle = (primary: boolean, disabled = false): React.CSSProperties => ({
    minHeight: 44,
    padding: '0 16px',
    borderRadius: 14,
    border: `1px solid ${primary ? theme.primary : theme.glassBorder}`,
    background: primary ? theme.primary : 'transparent',
    color: primary ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
    fontFamily: 'var(--mono)',
    fontSize: 12,
    fontWeight: 700,
    letterSpacing: '0.18em',
    textTransform: 'uppercase',
    opacity: disabled ? 0.55 : 1,
    cursor: disabled ? 'not-allowed' : 'pointer',
  });

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
        <GlassCard level="card" theme={theme} style={{ padding: '12px 16px' }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 600,
              marginBottom: 8,
            }}
          >
            Друкувати в десктоп
          </div>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Чернетка для десктопного чату"
            rows={4}
            style={{
              width: '100%',
              boxSizing: 'border-box',
              minHeight: 96,
              padding: 10,
              borderRadius: 10,
              border: `1px solid ${theme.glassBorder}`,
              background: theme.glassCard,
              color: theme.ink,
              fontFamily: 'var(--sans)',
              fontSize: 14,
              resize: 'vertical',
            }}
          />
          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginTop: 8,
              fontSize: 12,
              color: theme.ink2,
            }}
          >
            <input
              type="checkbox"
              checked={submitOnSend}
              onChange={(e) => setSubmitOnSend(e.target.checked)}
            />
            Сабмітнути одразу (натиснути Enter)
          </label>
          <button
            onClick={() => void handleSend()}
            disabled={!draft.trim() || busy !== 'idle'}
            style={{ ...buttonStyle(true, !draft.trim() || busy !== 'idle'), marginTop: 10, width: '100%' }}
          >
            {busy === 'text' ? 'Надсилаю…' : 'Надіслати'}
          </button>
        </GlassCard>

        <GlassCard level="card" theme={theme} style={{ padding: '12px 16px' }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 600,
              marginBottom: 8,
            }}
          >
            Знімок екрану
          </div>
          <button
            onClick={() => void handleScreen()}
            disabled={busy !== 'idle'}
            style={{ ...buttonStyle(false, busy !== 'idle'), width: '100%' }}
          >
            {busy === 'screen' ? 'Захоплюю…' : 'Зробити PNG'}
          </button>
          {screen && (
            <img
              src={`data:image/png;base64,${screen.image_b64}`}
              alt="desktop screenshot"
              style={{
                marginTop: 10,
                width: '100%',
                borderRadius: 10,
                border: `1px solid ${theme.glassBorder}`,
              }}
            />
          )}
        </GlassCard>

        <GlassCard level="card" theme={theme} style={{ padding: '12px 16px' }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 600,
              marginBottom: 8,
            }}
          >
            Закинути файл
          </div>
          <input
            ref={fileRef}
            type="file"
            style={{ display: 'none' }}
            onChange={handleFileChosen}
          />
          <button
            onClick={handleFilePick}
            disabled={busy !== 'idle'}
            style={{ ...buttonStyle(false, busy !== 'idle'), width: '100%' }}
          >
            {busy === 'upload' ? 'Завантажую…' : 'Обрати з телефону'}
          </button>
          <div
            style={{
              marginTop: 8,
              fontSize: 11,
              color: theme.ink3,
              lineHeight: 1.45,
            }}
          >
            Файл піде в <code style={{ fontFamily: 'var(--mono)' }}>~/.phantom/drop/</code> на десктопі. Макс 64 MiB.
          </div>
        </GlassCard>
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
