import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GlassCard } from '@/components/GlassCard';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useSystemStateStore } from '@/state/systemStateStore';
import { useCommsStore, type CommsTab } from '@/state/commsStore';
import { useDeviceStore } from '@/state/deviceStore';
import { useProfileStore } from '@/state/profileStore';
import { useToastStore } from '@/state/toastStore';
import { useT } from '@/hooks/useT';
import type { StringKey } from '@/i18n/strings';
import type { SystemStateKey } from '@/types/state';
import {
  probeBiometric,
  authenticate,
  ensureNotificationsPermission,
  isSecureStoreNative,
  isNotificationsNative,
  isBiometricNative,
  isForegroundServiceNative,
  isShareNative,
  startForegroundLink,
  stopForegroundLink,
  isForegroundLinkActive,
  secureClear,
} from '@/services/native';
import { revokePairing } from '@/services/pairingClient';

const STATE_OPTIONS: { value: SystemStateKey; key: StringKey }[] = [
  { value: 'SHADOW',   key: 'settings.state.shadow' },
  { value: 'FOCUS',    key: 'settings.state.focus' },
  { value: 'DIALOGUE', key: 'settings.state.dialogue' },
  { value: 'SENTINEL', key: 'settings.state.sentinel' },
  { value: 'GHOST',    key: 'settings.state.ghost' },
  { value: 'DREAM',    key: 'settings.state.dream' },
];

const COMMS_TABS: CommsTab[] = ['CALL', 'SMS', 'PHANTOM', 'TG/SIG/WA'];

export function SettingsScreen() {
  const { theme, themeId, setThemeId } = useThemeContext();
  const navigate = useNavigate();
  const t = useT();

  const stateKey = useSystemStateStore((s) => s.stateKey);
  const setStateKey = useSystemStateStore((s) => s.setStateKey);
  const motionMul = useSystemStateStore((s) => s.motionMul);
  const setMotionMul = useSystemStateStore((s) => s.setMotionMul);

  const enabledChannels = useCommsStore((s) => s.enabledChannels);
  const setChannelEnabled = useCommsStore((s) => s.setChannelEnabled);

  const { host, status } = useDeviceStore();
  const activeProfile = useProfileStore((s) => s.profile);
  const setBiometricEnrolled = useProfileStore((s) => s.setBiometricEnrolled);
  const clearAllProfiles = useProfileStore((s) => s.clearAll);

  // Section v2 — runtime probes for biometric / notifications / FG svc.
  const [bio, setBio] = useState<{ available: boolean; factor: string } | null>(null);
  const [notifGranted, setNotifGranted] = useState<boolean | null>(null);
  const [fgActive, setFgActive] = useState<boolean>(isForegroundLinkActive());
  const [devMode, setDevMode] = useState<boolean>(() => {
    if (typeof localStorage === 'undefined') return false;
    return localStorage.getItem('phantom.devMode') === '1';
  });

  useEffect(() => {
    void probeBiometric().then((s) => setBio({ available: s.available, factor: s.factor }));
    if (typeof Notification !== 'undefined') {
      setNotifGranted(Notification.permission === 'granted');
    }
  }, []);

  const enrollBiometric = async () => {
    const r = await authenticate({ reason: 'Прив\'язати біометрію до профілю' });
    if (r.ok) {
      if (activeProfile) {
        setBiometricEnrolled(activeProfile.id, true);
        useToastStore.getState().push('success', 'Біометрія активована');
      }
    } else if (r.reason === 'unsupported') {
      useToastStore.getState().push('warn', 'Біометрія недоступна на цьому пристрої');
    } else if (r.reason !== 'cancelled') {
      useToastStore.getState().push('error', `Помилка: ${r.reason}`);
    }
  };

  const askNotifPermission = async () => {
    const ok = await ensureNotificationsPermission();
    setNotifGranted(ok);
    useToastStore
      .getState()
      .push(ok ? 'success' : 'warn', ok ? 'Нотифікації увімкнено' : 'Дозвіл відхилено');
  };

  const toggleFgService = async () => {
    if (fgActive) {
      await stopForegroundLink();
      setFgActive(false);
      useToastStore.getState().push('info', 'Foreground service зупинено');
    } else {
      const ok = await startForegroundLink('PHANTOM linked', 'Sensors + WS active');
      setFgActive(ok);
      useToastStore.getState().push(ok ? 'success' : 'warn',
        ok ? 'Service запущено' : 'Доступно тільки на нативному Android');
    }
  };

  const toggleDevMode = () => {
    const next = !devMode;
    setDevMode(next);
    if (typeof localStorage !== 'undefined') {
      if (next) localStorage.setItem('phantom.devMode', '1');
      else localStorage.removeItem('phantom.devMode');
    }
  };

  const dangerWipe = async () => {
    const confirmed = window.confirm(
      'Стерти ВСЕ: профілі, devices, vault local cache. Хост phantom-os це не зачепить. Продовжити?',
    );
    if (!confirmed) return;
    try {
      await revokePairing();
    } catch {
      // best-effort
    }
    await secureClear();
    if (typeof localStorage !== 'undefined') {
      localStorage.clear();
    }
    clearAllProfiles();
    useToastStore.getState().push('success', 'Локальні дані очищено');
    navigate('/onboarding', { replace: true });
  };

  return (
    <PhoneShell theme={theme}>
      <div
        style={{ padding: 16, flex: 1, display: 'flex', flexDirection: 'column', gap: 14, minHeight: 0, overflowY: 'auto' }}
        className="ph-scroll"
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button onClick={() => navigate(-1)} style={{ color: theme.ink2, fontSize: 18 }} aria-label={t('common.back')}>
            ←
          </button>
          <div style={{ fontSize: 24, fontWeight: 700, color: theme.ink, letterSpacing: '-0.02em' }}>{t('settings.title')}</div>
        </div>

        <Section title={t('settings.theme')} theme={theme}>
          <div style={{ display: 'flex', gap: 8 }}>
            {(['sunrise', 'amber'] as const).map((id) => (
              <button
                key={id}
                onClick={() => setThemeId(id)}
                style={{
                  flex: 1,
                  padding: '12px 0',
                  borderRadius: 12,
                  background: themeId === id ? theme.primary : 'transparent',
                  color: themeId === id ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
                  border: `1px solid ${themeId === id ? theme.primary : theme.glassBorder}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                {id === 'sunrise' ? t('settings.theme.sunrise') : t('settings.theme.amber')}
              </button>
            ))}
          </div>
        </Section>

        <Section title={t('settings.state.title')} theme={theme} hint={t('settings.state.hint')}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {STATE_OPTIONS.map((o) => (
              <button
                key={o.value}
                onClick={() => setStateKey(o.value)}
                style={{
                  padding: '11px 14px',
                  borderRadius: 12,
                  background: stateKey === o.value ? theme.primary + '22' : 'transparent',
                  color: theme.ink,
                  border: `1px solid ${stateKey === o.value ? theme.primary : theme.glassBorder}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.14em',
                  textTransform: 'uppercase',
                  textAlign: 'left',
                }}
              >
                {t(o.key)}
              </button>
            ))}
          </div>
        </Section>

        <Section title={t('settings.motion.title')} theme={theme} hint={t('settings.motion.hint')}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <input
              type="range"
              min={0.2}
              max={2.0}
              step={0.05}
              value={motionMul}
              onChange={(e) => setMotionMul(parseFloat(e.target.value))}
              style={{ flex: 1, accentColor: theme.primary }}
            />
            <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: theme.ink, fontWeight: 700, width: 56, textAlign: 'right' }}>
              {motionMul.toFixed(2)}×
            </span>
          </div>
        </Section>

        <Section title={t('settings.comms.title')} theme={theme} hint={t('settings.comms.hint')}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {COMMS_TABS.map((tab) => (
              <ChannelToggle
                key={tab}
                label={tab}
                on={enabledChannels[tab]}
                onToggle={() => setChannelEnabled(tab, !enabledChannels[tab])}
                theme={theme}
              />
            ))}
          </div>
        </Section>

        <Section title={t('settings.device.title')} theme={theme}>
          <Row label={t('settings.device.status')} value={status.toUpperCase()} theme={theme} />
          <Row label={t('settings.device.host')} value={host ? host.replace(/^https?:\/\//, '') : '—'} theme={theme} />
          <button
            onClick={() => navigate('/showcase')}
            style={{
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
              marginTop: 8,
              width: '100%',
            }}
          >
            {t('settings.showcase')}
          </button>
        </Section>

        <Section title="Профіль" theme={theme} hint="Активна персона на цьому пристрої">
          <Row
            label="Ім'я"
            value={activeProfile?.displayName ?? '—'}
            theme={theme}
          />
          <Row label="Роль" value={activeProfile?.role ?? '—'} theme={theme} />
          <Row
            label="Біометрія"
            value={
              !bio
                ? 'probing…'
                : !bio.available
                ? 'недоступна'
                : activeProfile?.biometricEnrolled
                ? `увімкнена · ${bio.factor || 'unknown'}`
                : 'не прив\'язана'
            }
            theme={theme}
          />
          <button
            onClick={() => void enrollBiometric()}
            disabled={!bio?.available}
            style={{
              padding: '10px 14px',
              borderRadius: 12,
              background: bio?.available ? theme.primary : 'transparent',
              color: bio?.available ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
              border: `1px solid ${bio?.available ? theme.primary : theme.glassBorder}`,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              marginTop: 8,
              width: '100%',
              opacity: bio?.available ? 1 : 0.5,
            }}
          >
            {activeProfile?.biometricEnrolled ? 'Перепривʼязати' : 'Привʼязати біометрію'}
          </button>
          <button
            onClick={() => navigate('/profiles')}
            style={{
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
              marginTop: 8,
              width: '100%',
            }}
          >
            Керування профілями →
          </button>
        </Section>

        <Section title="Нотифікації" theme={theme} hint="Системні буззи коли PHANTOM пише першим">
          <Row
            label="Дозвіл"
            value={
              notifGranted === null
                ? '—'
                : notifGranted
                ? 'granted'
                : 'denied'
            }
            theme={theme}
          />
          <Row
            label="Канал"
            value={isNotificationsNative() ? 'Android system' : 'Web Notification'}
            theme={theme}
          />
          <button
            onClick={() => void askNotifPermission()}
            style={{
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
              marginTop: 8,
              width: '100%',
            }}
          >
            Запитати дозвіл
          </button>
        </Section>

        <Section title="Foreground service" theme={theme} hint="Тримає сенсори + WS живими коли екран off (тільки Android)">
          <Row
            label="Платформа"
            value={isForegroundServiceNative() ? 'Android native' : 'web — недоступно'}
            theme={theme}
          />
          <Row label="Стан" value={fgActive ? 'active' : 'idle'} theme={theme} />
          <button
            onClick={() => void toggleFgService()}
            disabled={!isForegroundServiceNative()}
            style={{
              padding: '10px 14px',
              borderRadius: 12,
              background: fgActive ? theme.primary : 'transparent',
              color: fgActive ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
              border: `1px solid ${fgActive ? theme.primary : theme.glassBorder}`,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              marginTop: 8,
              width: '100%',
              opacity: isForegroundServiceNative() ? 1 : 0.5,
            }}
          >
            {fgActive ? 'Зупинити' : 'Запустити'}
          </button>
        </Section>

        <Section title="Native bridges" theme={theme} hint="Що увімкнено на цьому пристрої">
          <Row
            label="Secure store"
            value={isSecureStoreNative() ? 'Keystore' : 'localStorage'}
            theme={theme}
          />
          <Row
            label="Біометрія"
            value={isBiometricNative() ? 'BiometricPrompt' : 'web — n/a'}
            theme={theme}
          />
          <Row
            label="Share"
            value={isShareNative() ? 'Android Intent' : 'navigator.share'}
            theme={theme}
          />
        </Section>

        <Section title="Розробник" theme={theme} hint="Допоміжні режими">
          <button
            onClick={toggleDevMode}
            style={{
              padding: '10px 14px',
              borderRadius: 12,
              background: devMode ? theme.primary + '22' : 'transparent',
              color: theme.ink,
              border: `1px solid ${devMode ? theme.primary : theme.glassBorder}`,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              width: '100%',
              textAlign: 'left',
            }}
          >
            Dev mode {devMode ? '· ON' : '· off'}
          </button>
          <button
            onClick={() => navigate('/diag')}
            style={{
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
              width: '100%',
              marginTop: 8,
            }}
          >
            Відкрити /diag →
          </button>
        </Section>

        <Section title="Danger zone" theme={theme} hint="Стерти всі локальні дані">
          <button
            onClick={() => void dangerWipe()}
            style={{
              padding: '12px 14px',
              borderRadius: 12,
              background: '#7F1D1D',
              color: '#FECACA',
              border: '1px solid #B91C1C',
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              width: '100%',
            }}
          >
            Стерти все локально
          </button>
        </Section>
      </div>
    </PhoneShell>
  );
}

function Section({
  title,
  hint,
  theme,
  children,
}: {
  title: string;
  hint?: string;
  theme: ReturnType<typeof useThemeContext>['theme'];
  children: React.ReactNode;
}) {
  return (
    <GlassCard level="card" theme={theme} style={{ padding: '14px 16px' }}>
      <div style={{ fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.22em', textTransform: 'uppercase', fontWeight: 700, color: theme.primary, marginBottom: 10 }}>
        {title}
      </div>
      {hint && <div style={{ fontSize: 11.5, color: theme.ink3, marginBottom: 10, lineHeight: 1.4 }}>{hint}</div>}
      {children}
    </GlassCard>
  );
}

function Row({ label, value, theme }: { label: string; value: string; theme: ReturnType<typeof useThemeContext>['theme'] }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', padding: '8px 0' }}>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.2em', textTransform: 'uppercase', color: theme.ink3 }}>{label}</span>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: theme.ink, fontWeight: 600 }}>{value}</span>
    </div>
  );
}

function ChannelToggle({
  label,
  on,
  onToggle,
  theme,
}: {
  label: string;
  on: boolean;
  onToggle: () => void;
  theme: ReturnType<typeof useThemeContext>['theme'];
}) {
  return (
    <button
      onClick={onToggle}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '10px 14px',
        borderRadius: 12,
        background: on ? theme.primary + '22' : 'transparent',
        border: `1px solid ${on ? theme.primary : theme.glassBorder}`,
        color: theme.ink,
      }}
    >
      <span style={{ fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 700, letterSpacing: '0.18em', textTransform: 'uppercase' }}>{label}</span>
      <span
        style={{
          width: 36,
          height: 20,
          borderRadius: 999,
          background: on ? theme.primary : theme.glassBorder,
          position: 'relative',
          transition: 'background 0.18s',
        }}
      >
        <span
          style={{
            position: 'absolute',
            top: 2,
            left: on ? 18 : 2,
            width: 16,
            height: 16,
            borderRadius: '50%',
            background: '#fff',
            transition: 'left 0.18s',
          }}
        />
      </span>
    </button>
  );
}
