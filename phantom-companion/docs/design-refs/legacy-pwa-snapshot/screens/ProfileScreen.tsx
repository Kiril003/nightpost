import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GlassCard } from '@/components/GlassCard';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useProfileStore } from '@/state/profileStore';
import { useDeviceStore } from '@/state/deviceStore';
import { fetchProfile, updateProfile } from '@/services/profileService';
import { revokePairing } from '@/services/pairingClient';
import { useT } from '@/hooks/useT';

export function ProfileScreen() {
  const { theme } = useThemeContext();
  const navigate = useNavigate();
  const t = useT();
  const profile = useProfileStore((s) => s.profile);
  const loading = useProfileStore((s) => s.loading);
  const status = useDeviceStore((s) => s.status);

  const [edit, setEdit] = useState({ displayName: '', language: 'uk' as 'uk' | 'en', voiceProfile: '' });
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (status === 'paired' && !profile && !loading) {
      void fetchProfile();
    }
  }, [status, profile, loading]);

  useEffect(() => {
    if (profile) {
      setEdit({ displayName: profile.displayName, language: profile.language, voiceProfile: profile.voiceProfile });
    }
  }, [profile]);

  if (status !== 'paired') {
    return (
      <PhoneShell theme={theme}>
        <div style={{ padding: 16, flex: 1, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Header theme={theme} title={t('profile.title')} backLabel={t('common.back')} />
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="◱"
              title={t('profile.unpaired.title')}
              hint={t('profile.unpaired.hint')}
              cta={
                <button
                  onClick={() => navigate('/pair')}
                  style={{
                    padding: '10px 16px',
                    borderRadius: 999,
                    background: theme.primary,
                    color: theme.isDark ? '#1a1208' : '#fff',
                    fontFamily: 'var(--mono)',
                    fontSize: 11,
                    fontWeight: 700,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                  }}
                >
                  {t('profile.unpaired.cta')}
                </button>
              }
            />
          </GlassCard>
        </div>
      </PhoneShell>
    );
  }

  return (
    <PhoneShell theme={theme}>
      <div style={{ padding: 16, flex: 1, display: 'flex', flexDirection: 'column', gap: 14, minHeight: 0, overflowY: 'auto' }} className="ph-scroll">
        <Header theme={theme} title={t('profile.title')} backLabel={t('common.back')} />

        <GlassCard level="card" theme={theme} style={{ padding: '14px 16px' }}>
          {!profile && loading && (
            <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: theme.ink2 }}>{t('profile.loading')}</div>
          )}
          {profile && !editing && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div
                  style={{
                    width: 56,
                    height: 56,
                    borderRadius: '50%',
                    background: `linear-gradient(135deg, ${theme.primary}55, ${theme.primary}22)`,
                    border: `2px solid ${theme.primary}`,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontFamily: 'var(--serif)',
                    fontStyle: 'italic',
                    fontSize: 22,
                    fontWeight: 600,
                    color: theme.primary,
                  }}
                >
                  {profile.displayName.slice(0, 2).toUpperCase()}
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 18, fontWeight: 700, color: theme.ink }}>{profile.displayName}</div>
                  <div style={{ fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.22em', color: theme.primary, fontWeight: 700, textTransform: 'uppercase' }}>
                    {profile.role}
                  </div>
                </div>
              </div>
              <Row label={t('profile.row.language')} value={profile.language.toUpperCase()} theme={theme} />
              <Row label={t('profile.row.voice')} value={profile.voiceProfile} theme={theme} />
              <Row label={t('profile.row.paired')} value={profile.pairedAt ? new Date(profile.pairedAt).toLocaleDateString() : '—'} theme={theme} />
              <Row label={t('profile.row.last_seen')} value={profile.lastSeen ? new Date(profile.lastSeen).toLocaleString() : '—'} theme={theme} />
              <button
                onClick={() => setEditing(true)}
                style={{
                  marginTop: 12,
                  padding: '10px 14px',
                  borderRadius: 12,
                  background: theme.primary,
                  color: theme.isDark ? '#1a1208' : '#fff',
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  width: '100%',
                }}
              >
                {t('profile.edit')}
              </button>
            </>
          )}
          {profile && editing && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <Field label={t('profile.field.name')} theme={theme}>
                <input
                  type="text"
                  value={edit.displayName}
                  onChange={(e) => setEdit({ ...edit, displayName: e.target.value })}
                  style={inputStyle(theme)}
                />
              </Field>
              <Field label={t('profile.field.language')} theme={theme}>
                <select
                  value={edit.language}
                  onChange={(e) => setEdit({ ...edit, language: e.target.value as 'uk' | 'en' })}
                  style={inputStyle(theme)}
                >
                  <option value="uk">Українська</option>
                  <option value="en">English</option>
                </select>
              </Field>
              <Field label={t('profile.field.voice')} theme={theme}>
                <input
                  type="text"
                  value={edit.voiceProfile}
                  onChange={(e) => setEdit({ ...edit, voiceProfile: e.target.value })}
                  style={inputStyle(theme)}
                />
              </Field>
              <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                <button
                  onClick={() => setEditing(false)}
                  style={{
                    flex: 1,
                    padding: '10px 14px',
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
                  {t('profile.cancel')}
                </button>
                <button
                  onClick={async () => {
                    await updateProfile(edit);
                    setEditing(false);
                  }}
                  style={{
                    flex: 1,
                    padding: '10px 14px',
                    borderRadius: 12,
                    background: theme.primary,
                    color: theme.isDark ? '#1a1208' : '#fff',
                    fontFamily: 'var(--mono)',
                    fontSize: 11,
                    fontWeight: 700,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                  }}
                >
                  {t('profile.save')}
                </button>
              </div>
            </div>
          )}
        </GlassCard>

        <button
          onClick={async () => {
            await revokePairing();
            navigate('/pair', { replace: true });
          }}
          style={{
            padding: '12px 14px',
            borderRadius: 12,
            background: 'transparent',
            color: theme.coral,
            border: `1px solid ${theme.coral}55`,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          {t('profile.forget')}
        </button>
      </div>
    </PhoneShell>
  );
}

function Header({ theme, title, backLabel }: { theme: ReturnType<typeof useThemeContext>['theme']; title: string; backLabel: string }) {
  const navigate = useNavigate();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <button onClick={() => navigate(-1)} style={{ color: theme.ink2, fontSize: 18 }} aria-label={backLabel}>
        ←
      </button>
      <div style={{ fontSize: 24, fontWeight: 700, color: theme.ink, letterSpacing: '-0.02em' }}>{title}</div>
    </div>
  );
}

function Row({ label, value, theme }: { label: string; value: string; theme: ReturnType<typeof useThemeContext>['theme'] }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', padding: '10px 0', borderTop: `1px solid ${theme.glassBorder}` }}>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.2em', textTransform: 'uppercase', color: theme.ink3 }}>{label}</span>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: theme.ink, fontWeight: 600 }}>{value}</span>
    </div>
  );
}

function Field({ label, theme, children }: { label: string; theme: ReturnType<typeof useThemeContext>['theme']; children: React.ReactNode }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 9.5, letterSpacing: '0.22em', textTransform: 'uppercase', color: theme.ink3, fontWeight: 700 }}>{label}</span>
      {children}
    </label>
  );
}

function inputStyle(theme: ReturnType<typeof useThemeContext>['theme']) {
  return {
    padding: '10px 12px',
    borderRadius: 10,
    background: theme.glassPanel,
    border: `1px solid ${theme.glassBorder}`,
    color: theme.ink,
    fontFamily: 'var(--mono)',
    fontSize: 12,
    width: '100%',
  } as const;
}
