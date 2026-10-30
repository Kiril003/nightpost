// First-run permissions guide. Shown once after a successful pair —
// three cards, each asking for one platform privilege:
//
//   1. Notifications — system buzz when phantom-os pushes a line
//      while we're backgrounded.
//   2. Biometric — replace PIN re-prompts on warm starts.
//   3. Foreground service — keep WS + sensor stream alive when the
//      screen is off (Android only; web shows a "n/a" footnote).
//
// Each card has Allow + Skip. Skipping never blocks the operator
// from finishing onboarding; the corresponding feature stays
// in the "off" state and Settings → Me re-asks later.
//
// State persists in localStorage under `phantom.permGuide.completed`
// so we don't keep re-showing the cards.

import { useEffect, useState } from 'react';

import { GlassCard } from '@/components/GlassCard';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { useProfileStore } from '@/state/profileStore';
import {
  authenticate,
  ensureNotificationsPermission,
  isBiometricNative,
  isForegroundServiceNative,
  isNotificationsNative,
  probeBiometric,
  startForegroundLink,
} from '@/services/native';
import type { PhantomTheme } from '@/types/theme';

const COMPLETED_KEY = 'phantom.permGuide.completed';

interface PermissionsGuideProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
  onComplete: () => void;
}

interface CardSpec {
  id: 'notifications' | 'biometric' | 'foreground';
  icon: string;
  title: string;
  body: string;
  webHint?: string;
  cta: string;
}

export function isPermissionsGuideCompleted(): boolean {
  if (typeof localStorage === 'undefined') return false;
  return Boolean(localStorage.getItem(COMPLETED_KEY));
}

export function markPermissionsGuideCompleted(): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(COMPLETED_KEY, new Date().toISOString());
}

const CARDS: CardSpec[] = [
  {
    id: 'notifications',
    icon: '◓',
    title: 'Дозвіл на нотифікації',
    body: 'Коли PHANTOM пише першим — а ти не в застосунку — телефон буззне. Без дозволу ці лінії осідають тихо в Inbox.',
    cta: 'Дозволити',
  },
  {
    id: 'biometric',
    icon: '☉',
    title: 'Біометричний unlock',
    body: 'Палець або обличчя замість PIN при перемиканні профілів і чутливих діях. Жодних паролів у локальних даних — тільки Argon2id хеш.',
    webHint: 'У браузері — недоступно. Поставиш APK — і покаже опцію.',
    cta: 'Привʼязати',
  },
  {
    id: 'foreground',
    icon: '◬',
    title: 'Foreground service',
    body: 'Тримає сенсори та WebSocket живими коли екран вимкнений. Без цього Android прибиває JavaScript engine за хвилину доза.',
    webHint: 'У браузері — Android тільки. На веб-версії пропусти.',
    cta: 'Запустити',
  },
];

export function PermissionsGuide({
  themeOverride,
  framed = false,
  onComplete,
}: PermissionsGuideProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const setBiometricEnrolled = useProfileStore((s) => s.setBiometricEnrolled);
  const activeProfile = useProfileStore((s) => s.profile);

  const [stepIdx, setStepIdx] = useState(0);
  const [bio, setBio] = useState<{ available: boolean; factor: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void probeBiometric().then((s) => setBio({ available: s.available, factor: s.factor }));
  }, []);

  const card = CARDS[stepIdx];

  const finish = () => {
    markPermissionsGuideCompleted();
    onComplete();
  };

  const advance = () => {
    if (stepIdx + 1 < CARDS.length) {
      setStepIdx((s) => s + 1);
    } else {
      finish();
    }
  };

  const handleAllow = async () => {
    if (!card || busy) return;
    setBusy(true);
    try {
      switch (card.id) {
        case 'notifications': {
          const ok = await ensureNotificationsPermission();
          useToastStore.getState().push(
            ok ? 'success' : 'warn',
            ok ? 'Дозвіл на нотифікації — ✓' : 'Дозвіл відхилено',
          );
          break;
        }
        case 'biometric': {
          if (!bio?.available) {
            useToastStore.getState().push('warn', 'Біометрія недоступна');
            break;
          }
          const r = await authenticate({ reason: 'Привʼязати біометрію' });
          if (r.ok && activeProfile) {
            setBiometricEnrolled(activeProfile.id, true);
            useToastStore.getState().push('success', 'Біометрія активована');
          } else if (r.reason && r.reason !== 'cancelled') {
            useToastStore.getState().push('warn', `Біометрія: ${r.reason}`);
          }
          break;
        }
        case 'foreground': {
          if (!isForegroundServiceNative()) {
            useToastStore.getState().push('info', 'Тільки на нативному Android');
            break;
          }
          const ok = await startForegroundLink('PHANTOM linked', 'Sensors + WS active');
          useToastStore
            .getState()
            .push(ok ? 'success' : 'warn', ok ? 'Service запущено' : 'Не вдалось');
          break;
        }
      }
    } finally {
      setBusy(false);
      advance();
    }
  };

  const indicator = (
    <div style={{ display: 'flex', justifyContent: 'center', gap: 6, marginTop: 12 }}>
      {CARDS.map((_, i) => (
        <span
          key={i}
          style={{
            width: i === stepIdx ? 24 : 6,
            height: 6,
            borderRadius: 3,
            background: i === stepIdx ? theme.primary : theme.glassBorder,
            transition: 'width 0.18s',
          }}
        />
      ))}
    </div>
  );

  // Hint about web-platform availability.
  const webHint = (() => {
    if (!card) return null;
    if (card.id === 'biometric' && !isBiometricNative()) return card.webHint;
    if (card.id === 'foreground' && !isForegroundServiceNative()) return card.webHint;
    if (card.id === 'notifications' && !isNotificationsNative() && typeof Notification === 'undefined') {
      return 'Цей браузер не підтримує Notifications API.';
    }
    return null;
  })();

  return (
    <PhoneShell theme={theme} framed={framed} showStatusBar={false}>
      <div
        style={{
          flex: 1,
          padding: 22,
          display: 'flex',
          flexDirection: 'column',
          gap: 18,
          justifyContent: 'center',
        }}
      >
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11,
            letterSpacing: '0.22em',
            textTransform: 'uppercase',
            color: theme.ink3,
            fontWeight: 700,
          }}
        >
          Крок {stepIdx + 1} з {CARDS.length}
        </div>

        <GlassCard
          level="elevated"
          theme={theme}
          accentBorder
          accent={theme.primary}
          style={{ padding: 22, display: 'flex', flexDirection: 'column', gap: 14 }}
        >
          <div
            style={{
              width: 56,
              height: 56,
              borderRadius: 16,
              background: theme.primary + '30',
              border: `1px solid ${theme.primary}`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 26,
              color: theme.primary,
            }}
          >
            {card?.icon}
          </div>
          <div
            style={{
              fontSize: 22,
              fontWeight: 700,
              color: theme.ink,
              letterSpacing: '-0.01em',
            }}
          >
            {card?.title}
          </div>
          <div
            style={{
              fontSize: 14,
              color: theme.ink2,
              lineHeight: 1.55,
            }}
          >
            {card?.body}
          </div>
          {webHint && (
            <div
              style={{
                padding: '8px 12px',
                background: theme.primary + '12',
                border: `1px solid ${theme.primary}30`,
                borderRadius: 10,
                fontSize: 12,
                color: theme.ink2,
              }}
            >
              {webHint}
            </div>
          )}
        </GlassCard>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <button
            onClick={() => void handleAllow()}
            disabled={busy}
            style={{
              minHeight: 52,
              padding: '0 18px',
              borderRadius: 14,
              background: theme.primary,
              color: theme.isDark ? '#1a1208' : '#fff',
              border: `1px solid ${theme.primary}`,
              fontFamily: 'var(--mono)',
              fontSize: 12,
              fontWeight: 700,
              letterSpacing: '0.2em',
              textTransform: 'uppercase',
              opacity: busy ? 0.6 : 1,
            }}
          >
            {busy ? '…' : card?.cta}
          </button>
          <button
            onClick={advance}
            style={{
              minHeight: 44,
              padding: '0 14px',
              borderRadius: 12,
              background: 'transparent',
              color: theme.ink3,
              border: `1px solid ${theme.glassBorder}`,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.2em',
              textTransform: 'uppercase',
            }}
          >
            Пропустити
          </button>
        </div>

        {indicator}
      </div>
    </PhoneShell>
  );
}
