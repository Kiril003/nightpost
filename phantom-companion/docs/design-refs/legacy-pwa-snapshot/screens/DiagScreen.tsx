// Polish — single-pane diagnostics. When operator says "nothing
// works", this screen tells them WHICH thing isn't working: pair
// state, WS subscribed channels, last sensor batch ts, permission
// grants, native bridge availability, host /healthz round-trip.
//
// No fancy graphs — just rows of (label, value, dot). Green / amber
// / red dot per row so the operator can scan it in 2 seconds.

import { useCallback, useEffect, useState } from 'react';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useDeviceStore } from '@/state/deviceStore';
import { useConnectionStore } from '@/state/connectionStore';
import { useProfileStore } from '@/state/profileStore';
import { useSensorStore } from '@/state/sensorStore';
import { useErrorBusStore } from '@/state/errorBusStore';
import { wsHub } from '@/services/wsHub';
import { copyErrorsToClipboard } from '@/services/errorBus';
import { probeHost, type HostHealth } from '@/services/hostSwitcher';
import { useToastStore } from '@/state/toastStore';
import {
  isSecureStoreNative,
  isBiometricNative,
  isNotificationsNative,
  isShareNative,
  isForegroundServiceNative,
  probeBiometric,
} from '@/services/native';
import type { PhantomTheme } from '@/types/theme';

interface DiagRow {
  label: string;
  value: string;
  state: 'ok' | 'warn' | 'fail' | 'idle';
  hint?: string;
}

interface DiagScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

function dotColor(state: DiagRow['state']): string {
  switch (state) {
    case 'ok':
      return '#16A34A';
    case 'warn':
      return '#F59E0B';
    case 'fail':
      return '#EF4444';
    default:
      return '#9CA3AF';
  }
}

export function DiagScreen({ themeOverride, framed = false }: DiagScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;

  const deviceState = useDeviceStore((s) => s.status);
  const jwt = useDeviceStore((s) => s.jwt);
  const host = useDeviceStore((s) => s.host);
  const connStatus = useConnectionStore((s) => s.status);
  const connReason = useConnectionStore((s) => s.lastError);
  const activeProfile = useProfileStore((s) => s.profile);
  const profilesCount = useProfileStore((s) => s.profiles.filter((p) => !p.archived).length);
  const vitals = useSensorStore((s) => s.vitals);
  const systemState = useSensorStore((s) => s.systemState);

  const [hostProbe, setHostProbe] = useState<HostHealth | null>(null);
  const [bioStatus, setBioStatus] = useState<{ available: boolean; factor: string } | null>(null);
  const [permissions, setPermissions] = useState<Record<string, string>>({});
  const [subscribedChannels, setSubscribedChannels] = useState<string[] | null>(
    wsHub.getSubscribedChannels(),
  );
  const [now, setNow] = useState(() => Date.now());

  const refreshAll = useCallback(async () => {
    if (host) setHostProbe(await probeHost(host));
    setBioStatus(await probeBiometric());

    // Permissions API (where available — Capacitor / Chromium fully,
    // Safari partial, Firefox no for some).
    const perms: Record<string, string> = {};
    if (typeof navigator !== 'undefined' && navigator.permissions) {
      for (const name of [
        'geolocation',
        'camera',
        'microphone',
        'notifications',
      ] as PermissionName[]) {
        try {
          const r = await navigator.permissions.query({ name } as { name: PermissionName });
          perms[name] = r.state;
        } catch {
          perms[name] = 'unsupported';
        }
      }
    }
    setPermissions(perms);
    setSubscribedChannels(wsHub.getSubscribedChannels());
  }, [host]);

  useEffect(() => {
    void refreshAll();
    const t = setInterval(() => {
      void refreshAll();
      setNow(Date.now());
    }, 5_000);
    return () => clearInterval(t);
  }, [refreshAll]);

  const rows: DiagRow[] = [];

  // --- Pair / connection ---
  rows.push({
    label: 'Pair status',
    value: deviceState,
    state: deviceState === 'paired' ? 'ok' : 'fail',
    hint: jwt ? `JWT 🔑 first 12: ${jwt.slice(0, 12)}…` : 'jwt missing',
  });
  rows.push({
    label: 'WS connection',
    value: connStatus,
    state: connStatus === 'online' ? 'ok' : connStatus === 'connecting' ? 'warn' : 'fail',
    hint: connReason ?? undefined,
  });
  rows.push({
    label: 'Subscribed channels',
    value: subscribedChannels ? subscribedChannels.join(', ') || '∅' : 'all (no filter)',
    state: subscribedChannels && subscribedChannels.length > 0 ? 'ok' : 'idle',
  });
  rows.push({
    label: 'Host base URL',
    value: host ?? '—',
    state: host ? 'ok' : 'fail',
  });
  rows.push({
    label: 'Host /healthz',
    value: hostProbe
      ? hostProbe.ok
        ? `online · ${hostProbe.latencyMs} ms`
        : `offline · ${hostProbe.reason ?? '?'}`
      : 'probing…',
    state: hostProbe ? (hostProbe.ok ? 'ok' : 'fail') : 'idle',
  });

  // --- Profiles ---
  rows.push({
    label: 'Active profile',
    value: activeProfile?.displayName ?? '—',
    state: activeProfile ? 'ok' : 'fail',
    hint: activeProfile ? `id ${activeProfile.id}` : undefined,
  });
  rows.push({
    label: 'Profiles on device',
    value: String(profilesCount),
    state: profilesCount > 0 ? 'ok' : 'fail',
  });

  // --- Live data ---
  rows.push({
    label: 'System state',
    value: systemState ?? 'unknown',
    state: systemState ? 'ok' : 'idle',
  });
  rows.push({
    label: 'Body vitals (from desktop)',
    value: vitals
      ? `bpm ${vitals.bpm.toFixed(0)} · breath ${vitals.breath.toFixed(0)} · stress ${(vitals.stress * 100).toFixed(0)}%`
      : 'not received yet',
    state: vitals ? 'ok' : 'idle',
    hint: vitals ? `last seen ${Math.round((now - vitals.ts) / 1000)}s ago` : 'desktop sensors disabled or no ESP32',
  });

  // --- Permissions ---
  for (const [name, state] of Object.entries(permissions)) {
    rows.push({
      label: `perm: ${name}`,
      value: state,
      state: state === 'granted' ? 'ok' : state === 'prompt' ? 'warn' : 'fail',
    });
  }
  if (Object.keys(permissions).length === 0) {
    rows.push({ label: 'perms', value: 'Permissions API unavailable', state: 'idle' });
  }

  // --- Native bridges ---
  rows.push({
    label: 'Native: secure store',
    value: isSecureStoreNative() ? 'Keystore-backed' : 'localStorage fallback',
    state: isSecureStoreNative() ? 'ok' : 'warn',
  });
  rows.push({
    label: 'Native: biometric',
    value: bioStatus
      ? bioStatus.available
        ? `available · ${bioStatus.factor || 'unknown factor'}`
        : 'unavailable'
      : 'probing…',
    state: bioStatus?.available ? 'ok' : isBiometricNative() ? 'warn' : 'idle',
  });
  rows.push({
    label: 'Native: notifications',
    value: isNotificationsNative() ? 'system' : 'web Notification fallback',
    state: isNotificationsNative() ? 'ok' : 'warn',
  });
  rows.push({
    label: 'Native: share',
    value: isShareNative() ? 'Android Intent.createChooser' : 'navigator.share fallback',
    state: isShareNative() ? 'ok' : 'warn',
  });
  rows.push({
    label: 'Native: foreground service',
    value: isForegroundServiceNative() ? 'Android service active when paired' : 'web — sensors stop when locked',
    state: isForegroundServiceNative() ? 'ok' : 'warn',
  });

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          padding: '8px 12px 0',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          minHeight: 0,
          overflowY: 'auto',
        }}
      >
        <GlassCard level="card" theme={theme} style={{ padding: '12px 14px' }}>
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
            Діагностика
          </div>
          <div style={{ fontSize: 12, color: theme.ink2, lineHeight: 1.45 }}>
            Якщо щось не працює — спочатку подивись сюди. Кольорові точки
            показують статус кожного шару. Оновлюється кожні 5 секунд.
          </div>
          <button
            onClick={() => void refreshAll()}
            style={{
              marginTop: 10,
              minHeight: 36,
              padding: '0 14px',
              borderRadius: 10,
              border: `1px solid ${theme.glassBorder}`,
              background: 'transparent',
              color: theme.ink2,
              fontFamily: 'var(--mono)',
              fontSize: 10,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            оновити
          </button>
        </GlassCard>

        {rows.map((r, i) => (
          <GlassCard
            key={`${r.label}-${i}`}
            level="subtle"
            theme={theme}
            style={{ padding: '10px 12px' }}
          >
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
              <span
                aria-hidden
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: '50%',
                  background: dotColor(r.state),
                  boxShadow: `0 0 6px ${dotColor(r.state)}88`,
                  flexShrink: 0,
                  marginTop: 4,
                }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    color: theme.ink3,
                    fontWeight: 600,
                  }}
                >
                  {r.label}
                </div>
                <div
                  style={{
                    fontSize: 13,
                    color: theme.ink,
                    fontWeight: 500,
                    wordBreak: 'break-all',
                  }}
                >
                  {r.value}
                </div>
                {r.hint && (
                  <div style={{ fontSize: 10, color: theme.ink3, marginTop: 2, lineHeight: 1.4 }}>
                    {r.hint}
                  </div>
                )}
              </div>
            </div>
          </GlassCard>
        ))}

        <ErrorsPanel theme={theme} />
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}

interface ErrorsPanelProps {
  theme: PhantomTheme;
}

/** Last-N error feed with one-tap clipboard export. Surfaces every
 *  JS throw, unhandled rejection and console.error captured by
 *  errorBus. Operator copies a single blob and sends to engineer. */
function ErrorsPanel({ theme }: ErrorsPanelProps) {
  const entries = useErrorBusStore((s) => s.entries);
  const clear = useErrorBusStore((s) => s.clear);
  const total = entries.length;
  const recent = entries.slice(-15).reverse();

  const handleCopy = async () => {
    const ok = await copyErrorsToClipboard();
    useToastStore
      .getState()
      .push(
        ok ? 'success' : 'warn',
        ok ? `Скопійовано ${total} помилок` : 'Не вдалося скопіювати',
      );
  };

  return (
    <GlassCard level="card" theme={theme} style={{ padding: '12px 14px' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 6,
        }}
      >
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 10,
            letterSpacing: '0.22em',
            textTransform: 'uppercase',
            color: total > 0 ? theme.coral : theme.ink3,
            fontWeight: 700,
          }}
        >
          Помилки · {total}
        </div>
        <div style={{ flex: 1 }} />
        {total > 0 && (
          <>
            <button
              onClick={() => void handleCopy()}
              style={{
                minHeight: 30,
                padding: '0 12px',
                borderRadius: 8,
                border: `1px solid ${theme.primary}`,
                background: theme.primary,
                color: theme.isDark ? '#1a1208' : '#fff',
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              ⎘ Скопіювати
            </button>
            <button
              onClick={() => clear()}
              style={{
                minHeight: 30,
                padding: '0 10px',
                borderRadius: 8,
                border: `1px solid ${theme.glassBorder}`,
                background: 'transparent',
                color: theme.ink3,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              ✕
            </button>
          </>
        )}
      </div>

      {total === 0 ? (
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11,
            color: theme.ink3,
            opacity: 0.7,
          }}
        >
          Тиша. Якщо застосунок щось зловить — впаде сюди разом зі стеком.
          Тоді тапни «Скопіювати» і пришли.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {recent.map((e, i) => (
            <div
              key={`${e.ts}-${i}`}
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10.5,
                color: theme.ink2,
                lineHeight: 1.4,
                paddingTop: 6,
                borderTop: i > 0 ? `1px solid ${theme.glassBorder}` : 'none',
              }}
            >
              <div style={{ display: 'flex', gap: 6, alignItems: 'baseline' }}>
                <span style={{ color: theme.ink3 }}>
                  {new Date(e.ts).toLocaleTimeString('en-GB')}
                </span>
                <span
                  style={{
                    fontWeight: 700,
                    letterSpacing: '0.12em',
                    color:
                      e.kind === 'error'
                        ? theme.coral
                        : e.kind === 'rejection'
                        ? '#F4AF25'
                        : theme.ink3,
                  }}
                >
                  {e.kind.toUpperCase()}
                </span>
                <span style={{ color: theme.ink3 }}>{e.url || '/'}</span>
              </div>
              <div style={{ marginTop: 2, color: theme.ink, wordBreak: 'break-word' }}>
                {e.summary}
              </div>
            </div>
          ))}
          {entries.length > recent.length && (
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 9.5,
                color: theme.ink3,
                opacity: 0.7,
                marginTop: 4,
              }}
            >
              + {entries.length - recent.length} давніших — у скопійованому
              блобі будуть всі.
            </div>
          )}
        </div>
      )}
    </GlassCard>
  );
}
