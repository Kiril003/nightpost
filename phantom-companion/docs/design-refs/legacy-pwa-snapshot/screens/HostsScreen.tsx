// Phase 6 Mesh — paired-hosts switcher screen.
//
// Lists every paired profile with a health pill (green=reachable,
// amber=last seen N min ago, red=unreachable) and a "switch to" CTA.
// Tap → tears down the current WS, swaps deviceStore, re-connects.
// Health probes run on mount and after every switch.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { useProfileStore, type LocalProfile } from '@/state/profileStore';
import {
  isProfilePairedAndUsable,
  probeHost,
  switchActiveHost,
  type HostHealth,
} from '@/services/hostSwitcher';
import type { PhantomTheme } from '@/types/theme';

interface HostsScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function HostsScreen({ themeOverride, framed = false }: HostsScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const navigate = useNavigate();

  const profiles = useProfileStore((s) => s.profiles);
  const activeId = useProfileStore((s) => s.activeId);
  const livingProfiles = useMemo(() => profiles.filter((p) => !p.archived), [profiles]);

  const [healthByProfile, setHealthByProfile] = useState<Record<string, HostHealth>>({});
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const next: Record<string, HostHealth> = {};
    await Promise.all(
      livingProfiles.map(async (p) => {
        if (!p.hostBaseUrl) {
          next[p.id] = { ok: false, latencyMs: null, ts: Date.now(), reason: 'unpaired' };
          return;
        }
        next[p.id] = await probeHost(p.hostBaseUrl);
      }),
    );
    setHealthByProfile(next);
  }, [livingProfiles]);

  useEffect(() => {
    void refresh();
    const id = setInterval(() => void refresh(), 30_000);
    return () => clearInterval(id);
  }, [refresh]);

  const handleSwitch = useCallback(
    async (profile: LocalProfile) => {
      if (busy) return;
      if (profile.id === activeId) {
        useToastStore.getState().push('info', 'Профіль уже активний');
        return;
      }
      setBusy(profile.id);
      try {
        const out = await switchActiveHost(profile.id);
        if (out.needsPair) {
          useToastStore.getState().push('warn', 'Цей профіль ще не спарено — відкрию QR');
          navigate('/pair');
          return;
        }
        if (!out.ok) {
          useToastStore.getState().push('error', `Switch: ${out.reason ?? 'unknown'}`);
          return;
        }
        useToastStore.getState().push('success', `Активний: ${profile.displayName}`);
        await refresh();
      } finally {
        setBusy(null);
      }
    },
    [activeId, busy, navigate, refresh],
  );

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
            Спарені профілі / хости
          </div>
          <div
            style={{
              fontSize: 12,
              color: theme.ink2,
              lineHeight: 1.45,
              marginBottom: 8,
            }}
          >
            Один телефон, кілька phantom-os. Тапни на профіль — миттєво
            перемикнеш WS + сенсори на інший хост.
          </div>
          <button
            onClick={() => navigate('/pair')}
            style={{
              minHeight: 40,
              padding: '0 14px',
              borderRadius: 12,
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
            + Додати хост (QR)
          </button>
        </GlassCard>

        {livingProfiles.length === 0 && (
          <GlassCard level="card" theme={theme} style={{ padding: 18, textAlign: 'center' }}>
            <div style={{ color: theme.ink2 }}>Жоден профіль ще не створено</div>
          </GlassCard>
        )}

        {livingProfiles.map((p) => {
          const health = healthByProfile[p.id];
          const isActive = p.id === activeId;
          const usable = isProfilePairedAndUsable(p);
          const dotColor = !usable
            ? '#9CA3AF'
            : health?.ok
            ? '#16A34A'
            : health?.reason === 'timeout'
            ? '#F59E0B'
            : '#EF4444';
          return (
            <GlassCard
              key={p.id}
              level="card"
              theme={theme}
              accentBorder={isActive}
              accent={theme.primary}
              style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span
                  aria-hidden
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: '50%',
                    background: dotColor,
                    boxShadow: `0 0 8px ${dotColor}88`,
                    flexShrink: 0,
                  }}
                />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div
                    style={{
                      fontSize: 14,
                      fontWeight: 600,
                      color: theme.ink,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {p.displayName}
                    {isActive && (
                      <span
                        style={{
                          marginLeft: 8,
                          fontFamily: 'var(--mono)',
                          fontSize: 9,
                          color: theme.primary,
                          letterSpacing: '0.18em',
                          textTransform: 'uppercase',
                        }}
                      >
                        active
                      </span>
                    )}
                  </div>
                  <div
                    style={{
                      fontFamily: 'var(--mono)',
                      fontSize: 10,
                      color: theme.ink3,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {p.hostBaseUrl || 'не спарено'}
                  </div>
                </div>
              </div>

              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  fontFamily: 'var(--mono)',
                  fontSize: 10,
                  color: theme.ink3,
                  letterSpacing: '0.12em',
                  textTransform: 'uppercase',
                }}
              >
                <span>
                  {!usable
                    ? 'не спарено'
                    : health?.ok
                    ? `online · ${health.latencyMs} ms`
                    : health
                    ? `offline · ${health.reason ?? '—'}`
                    : 'probing…'}
                </span>
                <button
                  onClick={() => void handleSwitch(p)}
                  disabled={Boolean(busy) || isActive}
                  style={{
                    minHeight: 36,
                    padding: '0 14px',
                    borderRadius: 10,
                    border: `1px solid ${isActive ? theme.glassBorder : theme.primary}`,
                    background: isActive ? 'transparent' : theme.primary,
                    color: isActive ? theme.ink3 : theme.isDark ? '#1a1208' : '#fff',
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                    fontWeight: 700,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    opacity: busy === p.id ? 0.5 : 1,
                  }}
                >
                  {isActive ? 'активний' : busy === p.id ? '…' : 'switch'}
                </button>
              </div>
            </GlassCard>
          );
        })}
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
