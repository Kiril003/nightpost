// /profiles — multi-profile manager.
//
// Shows every profile on the device as a card with avatar initials,
// role badge, paired-host hint, biometric flag. Operator can switch
// active, archive, restore, or jump straight to /pair to add a new
// host. Long-press → biometric prompt (if enrolled), confirms then
// switches.
//
// Replaces the ad-hoc "tap your name in the corner" UX with a real
// surface that scales past 2 profiles.

import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { useProfileStore, type LocalProfile } from '@/state/profileStore';
import { switchActiveHost } from '@/services/hostSwitcher';
import { authenticate, probeBiometric } from '@/services/native';
import type { PhantomTheme } from '@/types/theme';

interface ProfilesScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).slice(0, 2);
  if (!parts.length) return '?';
  return parts.map((p) => p.charAt(0).toUpperCase()).join('');
}

const ROLE_HUE: Record<string, string> = {
  ROOT: '#EF4444',
  OPERATOR: '#F59E0B',
  GUEST: '#6B7280',
};

export function ProfilesScreen({ themeOverride, framed = false }: ProfilesScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const navigate = useNavigate();

  const profiles = useProfileStore((s) => s.profiles);
  const activeId = useProfileStore((s) => s.activeId);
  const archiveProfile = useProfileStore((s) => s.archiveProfile);

  const [showArchived, setShowArchived] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  const livingProfiles = profiles.filter((p) => !p.archived);
  const archivedProfiles = profiles.filter((p) => p.archived);

  const handleSwitch = useCallback(
    async (profile: LocalProfile) => {
      if (busyId) return;
      if (profile.id === activeId) {
        useToastStore.getState().push('info', 'Профіль уже активний');
        return;
      }
      setBusyId(profile.id);
      try {
        // If biometric is enrolled on the target profile AND the device
        // has a biometric factor — gate the switch behind it.
        if (profile.biometricEnrolled) {
          const status = await probeBiometric();
          if (status.available) {
            const r = await authenticate({
              reason: `Перейти у профіль ${profile.displayName}`,
            });
            if (!r.ok) {
              if (r.reason === 'cancelled') {
                useToastStore.getState().push('info', 'Скасовано');
              } else {
                useToastStore
                  .getState()
                  .push('warn', `Біометрія: ${r.reason ?? 'unknown'}`);
              }
              return;
            }
          }
        }
        const out = await switchActiveHost(profile.id);
        if (out.needsPair) {
          useToastStore.getState().push('warn', 'Профіль ще не спарено — відкрию QR');
          navigate('/pair');
          return;
        }
        if (!out.ok) {
          useToastStore
            .getState()
            .push('error', `Switch: ${out.reason ?? 'unknown'}`);
          return;
        }
        useToastStore
          .getState()
          .push('success', `Активний: ${profile.displayName}`);
      } finally {
        setBusyId(null);
      }
    },
    [activeId, busyId, navigate],
  );

  const handleArchive = useCallback(
    (profile: LocalProfile) => {
      if (profile.id === activeId) {
        useToastStore
          .getState()
          .push('warn', 'Не можна архівувати активний профіль');
        return;
      }
      const ok = window.confirm(`Архівувати профіль ${profile.displayName}?`);
      if (!ok) return;
      archiveProfile(profile.id);
      useToastStore.getState().push('info', 'Профіль в архіві');
    },
    [activeId, archiveProfile],
  );

  const profileCard = (p: LocalProfile, archived: boolean) => {
    const isActive = p.id === activeId;
    const roleColor = ROLE_HUE[p.role] ?? theme.ink3;
    return (
      <GlassCard
        key={p.id}
        level="card"
        theme={theme}
        accentBorder={isActive}
        accent={theme.primary}
        style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {/* Avatar */}
          <div
            style={{
              width: 48,
              height: 48,
              borderRadius: 12,
              background: archived ? theme.glassBorder : theme.primary + '40',
              border: `1px solid ${archived ? theme.glassBorder : theme.primary}`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontFamily: 'var(--mono)',
              fontSize: 16,
              fontWeight: 700,
              color: archived ? theme.ink3 : theme.ink,
              flexShrink: 0,
            }}
          >
            {initials(p.displayName)}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div
              style={{
                fontSize: 16,
                fontWeight: 600,
                color: theme.ink,
                opacity: archived ? 0.6 : 1,
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {p.displayName}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 2 }}>
              <span
                style={{
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  color: roleColor,
                  fontWeight: 700,
                  padding: '1px 6px',
                  borderRadius: 4,
                  border: `1px solid ${roleColor}`,
                }}
              >
                {p.role}
              </span>
              {p.biometricEnrolled && (
                <span
                  aria-label="biometric enrolled"
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    color: theme.primary,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    fontWeight: 700,
                  }}
                >
                  ☉ bio
                </span>
              )}
              {isActive && (
                <span
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    color: theme.primary,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    fontWeight: 700,
                  }}
                >
                  · active
                </span>
              )}
            </div>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                color: theme.ink3,
                marginTop: 2,
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {p.hostBaseUrl || 'не спарено'}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', gap: 6 }}>
          {!archived ? (
            <>
              <button
                onClick={() => void handleSwitch(p)}
                disabled={busyId !== null || isActive}
                style={{
                  flex: 1,
                  minHeight: 36,
                  padding: '0 12px',
                  borderRadius: 10,
                  background: isActive ? 'transparent' : theme.primary,
                  color: isActive ? theme.ink3 : theme.isDark ? '#1a1208' : '#fff',
                  border: `1px solid ${isActive ? theme.glassBorder : theme.primary}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  opacity: busyId === p.id ? 0.5 : 1,
                }}
              >
                {isActive ? 'активний' : busyId === p.id ? '…' : 'switch'}
              </button>
              <button
                onClick={() => handleArchive(p)}
                disabled={isActive}
                style={{
                  minHeight: 36,
                  padding: '0 12px',
                  borderRadius: 10,
                  background: 'transparent',
                  color: theme.ink3,
                  border: `1px solid ${theme.glassBorder}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  opacity: isActive ? 0.4 : 1,
                }}
              >
                archive
              </button>
            </>
          ) : (
            <button
              onClick={() => {
                useProfileStore
                  .getState()
                  .patchProfile({ /* no-op patch */ }, p.id);
                // The patch hook keeps `archived` as the source of
                // truth; restore by calling upsertProfile with
                // archived=false.
                useProfileStore.getState().upsertProfile({ ...p, archived: false });
                useToastStore.getState().push('success', 'Профіль відновлено');
              }}
              style={{
                flex: 1,
                minHeight: 36,
                padding: '0 12px',
                borderRadius: 10,
                background: 'transparent',
                color: theme.ink2,
                border: `1px solid ${theme.glassBorder}`,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              restore
            </button>
          )}
        </div>
      </GlassCard>
    );
  };

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          padding: '8px 12px 0',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
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
              fontWeight: 700,
              marginBottom: 6,
            }}
          >
            Профілі ({livingProfiles.length})
          </div>
          <div style={{ fontSize: 12, color: theme.ink2, lineHeight: 1.45 }}>
            Один телефон, кілька персон. Біометрія блокує перемикання
            якщо профіль її потребує.
          </div>
          <div style={{ display: 'flex', gap: 6, marginTop: 10 }}>
            <button
              onClick={() => navigate('/onboarding')}
              style={{
                flex: 1,
                minHeight: 38,
                padding: '0 12px',
                borderRadius: 10,
                background: theme.primary,
                color: theme.isDark ? '#1a1208' : '#fff',
                border: `1px solid ${theme.primary}`,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              + новий профіль
            </button>
            {archivedProfiles.length > 0 && (
              <button
                onClick={() => setShowArchived((s) => !s)}
                style={{
                  minHeight: 38,
                  padding: '0 12px',
                  borderRadius: 10,
                  background: 'transparent',
                  color: theme.ink3,
                  border: `1px solid ${theme.glassBorder}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                {showArchived ? 'сховати архів' : `архів (${archivedProfiles.length})`}
              </button>
            )}
          </div>
        </GlassCard>

        {livingProfiles.length === 0 && (
          <GlassCard level="card" theme={theme} style={{ padding: 22, textAlign: 'center' }}>
            <div style={{ color: theme.ink2 }}>Жоден профіль ще не створено</div>
          </GlassCard>
        )}

        {livingProfiles.map((p) => profileCard(p, false))}

        {showArchived && archivedProfiles.length > 0 && (
          <>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                color: theme.ink3,
                padding: '4px 4px 0',
              }}
            >
              архів
            </div>
            {archivedProfiles.map((p) => profileCard(p, true))}
          </>
        )}
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
