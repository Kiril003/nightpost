// /me — single pane that merges /profiles + /settings.
//
// Top: active profile hero card with role + biometric flag + paired
// host. Tap → biometric reauth (or PIN fallback). Below: collapsible
// sections covering everything Settings v2 had — theme, profiles
// list, biometric enroll, notifications, foreground service, native
// bridges status, dev mode, danger zone. State + motion + comms
// channels stay too (they live in the active profile's prefs, but
// we expose them here for now until prefs sync to backend).
//
// Why one pane: operator went /profiles for switching, /settings for
// preferences, /diag for status. With this screen the only reason to
// leave is the dedicated /diag deep-dive (linked from inside).

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { Banner } from '@/components/Banner';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { useDeviceStore } from '@/state/deviceStore';
import { useProfileStore, type LocalProfile } from '@/state/profileStore';
import { useSystemStateStore } from '@/state/systemStateStore';
import { useCommsStore, type CommsTab } from '@/state/commsStore';
import { switchActiveHost } from '@/services/hostSwitcher';
import { revokePairing } from '@/services/pairingClient';
import {
  authenticate,
  ensureNotificationsPermission,
  isBiometricNative,
  isForegroundLinkActive,
  isForegroundServiceNative,
  isNotificationsNative,
  isSecureStoreNative,
  isShareNative,
  probeBiometric,
  secureClear,
  startForegroundLink,
  stopForegroundLink,
} from '@/services/native';
import type { PhantomTheme } from '@/types/theme';
import type { SystemStateKey } from '@/types/state';
import type { StringKey } from '@/i18n/strings';
import { useT } from '@/hooks/useT';
import {
  buildImportPayloadFromLocal,
  importPhoneProfile,
  pullSnapshot,
} from '@/services/profileSync';
import { useProfileSyncStore } from '@/state/profileSyncStore';
import { useHandoffStore } from '@/state/handoffStore';
import { useSensorStore } from '@/state/sensorStore';
import {
  readDirectKeys,
  writeDirectKeys,
  buildDirectProvider,
  type DirectProviderName,
} from '@/services/directLlm';

const STATE_OPTIONS: { value: SystemStateKey; key: StringKey }[] = [
  { value: 'SHADOW', key: 'settings.state.shadow' },
  { value: 'FOCUS', key: 'settings.state.focus' },
  { value: 'DIALOGUE', key: 'settings.state.dialogue' },
  { value: 'SENTINEL', key: 'settings.state.sentinel' },
  { value: 'GHOST', key: 'settings.state.ghost' },
  { value: 'DREAM', key: 'settings.state.dream' },
];

const COMMS_TABS: CommsTab[] = ['CALL', 'SMS', 'PHANTOM', 'TG/SIG/WA'];
const ROLE_HUE: Record<string, string> = {
  ROOT: '#EF4444',
  OPERATOR: '#F59E0B',
  GUEST: '#6B7280',
};

interface MeScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

interface SectionProps {
  title: string;
  hint?: string;
  defaultOpen?: boolean;
  theme: PhantomTheme;
  children: React.ReactNode;
}

function Section({ title, hint, defaultOpen = false, theme, children }: SectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
      <button
        onClick={() => setOpen((s) => !s)}
        aria-expanded={open}
        style={{
          width: '100%',
          padding: '14px 16px',
          background: 'transparent',
          border: 'none',
          color: theme.ink,
          textAlign: 'left',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          minHeight: 52,
        }}
      >
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.primary,
              fontWeight: 700,
            }}
          >
            {title}
          </div>
          {hint && (
            <div
              style={{
                fontSize: 11,
                color: theme.ink3,
                marginTop: 2,
                lineHeight: 1.4,
              }}
            >
              {hint}
            </div>
          )}
        </div>
        <span
          style={{
            fontSize: 18,
            color: theme.ink3,
            transform: open ? 'rotate(180deg)' : 'rotate(0deg)',
            transition: 'transform 0.18s',
          }}
        >
          ⌄
        </span>
      </button>
      {open && (
        <div style={{ padding: '0 16px 16px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {children}
        </div>
      )}
    </GlassCard>
  );
}

function Row({ label, value, theme }: { label: string; value: string; theme: PhantomTheme }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        padding: '6px 0',
      }}
    >
      <span
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          letterSpacing: '0.18em',
          textTransform: 'uppercase',
          color: theme.ink3,
        }}
      >
        {label}
      </span>
      <span
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 12,
          color: theme.ink,
          fontWeight: 600,
        }}
      >
        {value}
      </span>
    </div>
  );
}

function PrimaryButton({
  label,
  onClick,
  theme,
  disabled,
}: {
  label: string;
  onClick: () => void;
  theme: PhantomTheme;
  disabled?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      style={{
        minHeight: 44,
        padding: '0 14px',
        borderRadius: 12,
        background: theme.primary,
        color: theme.isDark ? '#1a1208' : '#fff',
        border: `1px solid ${theme.primary}`,
        fontFamily: 'var(--mono)',
        fontSize: 11,
        fontWeight: 700,
        letterSpacing: '0.18em',
        textTransform: 'uppercase',
        opacity: disabled ? 0.5 : 1,
        width: '100%',
      }}
    >
      {label}
    </button>
  );
}

function SecondaryButton({
  label,
  onClick,
  theme,
  active = false,
  fullWidth = true,
}: {
  label: string;
  onClick: () => void;
  theme: PhantomTheme;
  active?: boolean;
  fullWidth?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      style={{
        minHeight: 40,
        padding: '0 12px',
        borderRadius: 10,
        background: active ? theme.primary + '22' : 'transparent',
        color: theme.ink,
        border: `1px solid ${active ? theme.primary : theme.glassBorder}`,
        fontFamily: 'var(--mono)',
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: '0.18em',
        textTransform: 'uppercase',
        width: fullWidth ? '100%' : 'auto',
      }}
    >
      {label}
    </button>
  );
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).slice(0, 2);
  if (!parts.length) return '?';
  return parts.map((p) => p.charAt(0).toUpperCase()).join('');
}

export function MeScreen({ themeOverride, framed = false }: MeScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const { themeId, setThemeId } = ctx;
  const navigate = useNavigate();
  const t = useT();

  const profiles = useProfileStore((s) => s.profiles);
  const activeId = useProfileStore((s) => s.activeId);
  const activeProfile = useProfileStore((s) => s.profile);
  const archiveProfile = useProfileStore((s) => s.archiveProfile);
  const setBiometricEnrolled = useProfileStore((s) => s.setBiometricEnrolled);
  const clearAllProfiles = useProfileStore((s) => s.clearAll);

  const stateKey = useSystemStateStore((s) => s.stateKey);
  const setStateKey = useSystemStateStore((s) => s.setStateKey);
  const motionMul = useSystemStateStore((s) => s.motionMul);
  const setMotionMul = useSystemStateStore((s) => s.setMotionMul);

  const enabledChannels = useCommsStore((s) => s.enabledChannels);
  const setChannelEnabled = useCommsStore((s) => s.setChannelEnabled);

  const { host, status: pairStatus } = useDeviceStore();

  const livingProfiles = useMemo(() => profiles.filter((p) => !p.archived), [profiles]);

  const [bio, setBio] = useState<{ available: boolean; factor: string } | null>(null);
  const [notifGranted, setNotifGranted] = useState<boolean | null>(null);
  const [fgActive, setFgActive] = useState<boolean>(isForegroundLinkActive());
  const [busyId, setBusyId] = useState<string | null>(null);
  const [devMode, setDevMode] = useState<boolean>(() =>
    typeof localStorage !== 'undefined' && localStorage.getItem('phantom.devMode') === '1',
  );

  useEffect(() => {
    void probeBiometric().then((s) => setBio({ available: s.available, factor: s.factor }));
    if (typeof Notification !== 'undefined') {
      setNotifGranted(Notification.permission === 'granted');
    }
  }, []);

  const enrollBiometric = async () => {
    const r = await authenticate({ reason: 'Прив\'язати біометрію до профілю' });
    if (r.ok && activeProfile) {
      setBiometricEnrolled(activeProfile.id, true);
      useToastStore.getState().push('success', 'Біометрія активована');
    } else if (r.reason === 'unsupported') {
      useToastStore.getState().push('warn', 'Біометрія недоступна на цьому пристрої');
    } else if (r.reason && r.reason !== 'cancelled') {
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
      useToastStore.getState().push('info', 'Service зупинено');
    } else {
      const ok = await startForegroundLink('PHANTOM linked', 'Sensors + WS active');
      setFgActive(ok);
      useToastStore
        .getState()
        .push(ok ? 'success' : 'warn', ok ? 'Service запущено' : 'Тільки на нативному Android');
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
      /* best-effort */
    }
    await secureClear();
    if (typeof localStorage !== 'undefined') localStorage.clear();
    clearAllProfiles();
    useToastStore.getState().push('success', 'Локальні дані очищено');
    navigate('/onboarding', { replace: true });
  };

  const switchProfile = async (p: LocalProfile) => {
    if (busyId || p.id === activeId) return;
    setBusyId(p.id);
    try {
      if (p.biometricEnrolled) {
        const stat = await probeBiometric();
        if (stat.available) {
          const r = await authenticate({ reason: `Перейти у профіль ${p.displayName}` });
          if (!r.ok) {
            if (r.reason === 'cancelled') useToastStore.getState().push('info', 'Скасовано');
            else useToastStore.getState().push('warn', `Біометрія: ${r.reason ?? 'unknown'}`);
            return;
          }
        }
      }
      const out = await switchActiveHost(p.id);
      if (out.needsPair) {
        useToastStore.getState().push('warn', 'Профіль ще не спарено');
        navigate('/pair');
        return;
      }
      if (!out.ok) {
        useToastStore.getState().push('error', `Switch: ${out.reason ?? '?'}`);
        return;
      }
      useToastStore.getState().push('success', `Активний: ${p.displayName}`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          padding: '6px 12px 0',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          minHeight: 0,
          overflowY: 'auto',
        }}
      >
        {/* Unpaired-state banner — surfaced when operator deferred
            pairing in onboarding ("Пізніше" button). Without this,
            /me looks identical paired vs unpaired and the operator
            wonders why the QR scan never happened. The deferred-ts
            distinguishes "deliberately later" (softer copy) from
            "never paired" (harder push). */}
        {pairStatus !== 'paired' && (
          <Banner
            theme={theme}
            kind="warn"
            title={
              typeof localStorage !== 'undefined' &&
              localStorage.getItem('phantom.pair.deferred')
                ? 'Поки без phantom-os'
                : 'Не спарено з phantom-os'
            }
            message="Більшість екранів живі тільки коли телефон спарений з десктопним PHANTOM. Скан QR займає секунди."
            action={{ label: 'Спарити зараз', onPress: () => navigate('/pair') }}
            pinned
          />
        )}

        {/* Active profile hero */}
        {activeProfile && (
          <GlassCard
            level="elevated"
            theme={theme}
            accentBorder
            accent={theme.primary}
            style={{ padding: '14px 16px', display: 'flex', gap: 14, alignItems: 'center' }}
          >
            <div
              style={{
                width: 56,
                height: 56,
                borderRadius: 14,
                background: theme.primary + '40',
                border: `1px solid ${theme.primary}`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontFamily: 'var(--mono)',
                fontSize: 20,
                fontWeight: 700,
                color: theme.ink,
                flexShrink: 0,
              }}
            >
              {initials(activeProfile.displayName)}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div
                style={{
                  fontSize: 18,
                  fontWeight: 700,
                  color: theme.ink,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {activeProfile.displayName}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
                <span
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    color: ROLE_HUE[activeProfile.role] ?? theme.ink3,
                    fontWeight: 700,
                    padding: '1px 6px',
                    borderRadius: 4,
                    border: `1px solid ${ROLE_HUE[activeProfile.role] ?? theme.glassBorder}`,
                  }}
                >
                  {activeProfile.role}
                </span>
                {activeProfile.biometricEnrolled && (
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
                    ☉ bio
                  </span>
                )}
              </div>
              <div
                style={{
                  fontFamily: 'var(--mono)',
                  fontSize: 10,
                  color: theme.ink3,
                  marginTop: 4,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {pairStatus === 'paired' ? `paired · ${host?.replace(/^https?:\/\//, '') ?? ''}` : 'не спарено'}
              </div>
            </div>
          </GlassCard>
        )}

        {/* Profile picker */}
        <Section
          title={`Профілі (${livingProfiles.length})`}
          hint="Перемикач персон. Біометрія блокує перехід якщо профіль її потребує."
          defaultOpen
          theme={theme}
        >
          {livingProfiles.length === 0 && (
            <div style={{ color: theme.ink3, fontSize: 12 }}>Жоден профіль не створено.</div>
          )}
          {livingProfiles.map((p) => {
            const active = p.id === activeId;
            return (
              <div
                key={p.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '6px 8px',
                  borderRadius: 10,
                  border: `1px solid ${active ? theme.primary : theme.glassBorder}`,
                  background: active ? theme.primary + '15' : 'transparent',
                }}
              >
                <div
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 10,
                    background: theme.primary + '30',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontFamily: 'var(--mono)',
                    fontSize: 13,
                    fontWeight: 700,
                    color: theme.ink,
                    flexShrink: 0,
                  }}
                >
                  {initials(p.displayName)}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div
                    style={{
                      fontSize: 13,
                      fontWeight: 600,
                      color: theme.ink,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {p.displayName}
                    {p.biometricEnrolled && (
                      <span
                        style={{ marginLeft: 6, color: theme.primary, fontSize: 11 }}
                      >
                        ☉
                      </span>
                    )}
                  </div>
                  <div
                    style={{
                      fontFamily: 'var(--mono)',
                      fontSize: 9,
                      color: theme.ink3,
                      letterSpacing: '0.14em',
                      textTransform: 'uppercase',
                    }}
                  >
                    {p.role}
                    {!p.deviceJwt && ' · не спарено'}
                  </div>
                </div>
                <SecondaryButton
                  label={active ? 'active' : busyId === p.id ? '…' : 'switch'}
                  onClick={() => void switchProfile(p)}
                  theme={theme}
                  fullWidth={false}
                  active={active}
                />
                {!active && (
                  <button
                    onClick={() => archiveProfile(p.id)}
                    aria-label="Archive profile"
                    style={{
                      width: 32,
                      height: 32,
                      borderRadius: 8,
                      background: 'transparent',
                      color: theme.ink3,
                      border: `1px solid ${theme.glassBorder}`,
                      fontSize: 14,
                    }}
                  >
                    ✕
                  </button>
                )}
              </div>
            );
          })}
          <PrimaryButton
            label="+ Новий профіль"
            onClick={() => navigate('/onboarding')}
            theme={theme}
          />
        </Section>

        {/* Cross-world profile sync — pull a fresh snapshot from the
            paired phantom-os, push the active phone-built profile up
            so the desktop has the full picture (display name, role,
            behavioural model, fact metadata), and surface the live
            inbound events the desktop pushed via WS. Empty state
            tells the operator what the section does. */}
        <Section
          title="Cross-world sync"
          hint="Профіль, налаштування та факти бігають між телефоном і phantom-os будь-де у світі"
          theme={theme}
        >
          <CrossWorldSyncBlock theme={theme} activeProfile={activeProfile} />
        </Section>

        {/* Cross-device handoff — single auto-accept rule for now: when
            phantom-os pushes a map route while we're driving, take it
            without making the operator look at the screen. Every other
            handoff kind keeps the manual sheet. */}
        <Section
          title="Cross-device handoff"
          hint="Як телефон поводиться з тим, що phantom-os сам передає сюди"
          theme={theme}
        >
          <CrossDeviceHandoffBlock theme={theme} />
        </Section>

        {/* Theme */}
        <Section title={t('settings.theme')} theme={theme}>
          <div style={{ display: 'flex', gap: 8 }}>
            {(['sunrise', 'amber'] as const).map((id) => (
              <SecondaryButton
                key={id}
                label={id === 'sunrise' ? t('settings.theme.sunrise') : t('settings.theme.amber')}
                onClick={() => setThemeId(id)}
                theme={theme}
                active={themeId === id}
              />
            ))}
          </div>
        </Section>

        {/* Biometric */}
        <Section
          title="Біометрія"
          hint={
            !bio
              ? 'Перевіряю…'
              : !bio.available
              ? 'Цей пристрій не підтримує біометрію'
              : activeProfile?.biometricEnrolled
              ? `Активна · ${bio.factor || 'unknown'}`
              : 'Не привʼязана'
          }
          theme={theme}
        >
          <PrimaryButton
            label={activeProfile?.biometricEnrolled ? 'Перепривʼязати' : 'Привʼязати'}
            onClick={() => void enrollBiometric()}
            theme={theme}
            disabled={!bio?.available}
          />
        </Section>

        {/* Notifications */}
        <Section title="Нотифікації" hint="Системні буззи від PHANTOM" theme={theme}>
          <Row
            label="Дозвіл"
            value={notifGranted === null ? '—' : notifGranted ? 'granted' : 'denied'}
            theme={theme}
          />
          <Row
            label="Канал"
            value={isNotificationsNative() ? 'Android system' : 'web'}
            theme={theme}
          />
          <SecondaryButton
            label="Запитати дозвіл"
            onClick={() => void askNotifPermission()}
            theme={theme}
          />
        </Section>

        {/* Foreground service */}
        <Section
          title="Foreground service"
          hint="Тримає сенсори + WS живими коли екран off (Android only)"
          theme={theme}
        >
          <Row
            label="Платформа"
            value={isForegroundServiceNative() ? 'Android' : 'web — n/a'}
            theme={theme}
          />
          <Row label="Стан" value={fgActive ? 'active' : 'idle'} theme={theme} />
          <PrimaryButton
            label={fgActive ? 'Зупинити' : 'Запустити'}
            onClick={() => void toggleFgService()}
            theme={theme}
            disabled={!isForegroundServiceNative()}
          />
        </Section>

        {/* AI keys — operator's own Gemini / OpenAI key for direct
            chat that works without phantom-os. Keys are per-profile
            and stored in secureStore (Android Keystore-backed when
            native, prefixed localStorage on web). */}
        {activeProfile && (
          <Section
            title="AI ключі (standalone)"
            hint="Дозволяє Чату відповідати без phantom-os через твою власну API-підписку"
            theme={theme}
          >
            <AiKeysBlock theme={theme} profileId={activeProfile.id} />
          </Section>
        )}

        {/* State / motion / comms — historical settings */}
        <Section title={t('settings.state.title')} hint={t('settings.state.hint')} theme={theme}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {STATE_OPTIONS.map((o) => (
              <SecondaryButton
                key={o.value}
                label={t(o.key)}
                onClick={() => setStateKey(o.value)}
                theme={theme}
                active={stateKey === o.value}
              />
            ))}
          </div>
        </Section>

        <Section title={t('settings.motion.title')} hint={t('settings.motion.hint')} theme={theme}>
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
            <span
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 12,
                color: theme.ink,
                fontWeight: 700,
                width: 56,
                textAlign: 'right',
              }}
            >
              {motionMul.toFixed(2)}×
            </span>
          </div>
        </Section>

        <Section title={t('settings.comms.title')} hint={t('settings.comms.hint')} theme={theme}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {COMMS_TABS.map((tab) => (
              <SecondaryButton
                key={tab}
                label={`${tab} ${enabledChannels[tab] ? '· on' : '· off'}`}
                onClick={() => setChannelEnabled(tab, !enabledChannels[tab])}
                theme={theme}
                active={enabledChannels[tab]}
              />
            ))}
          </div>
        </Section>

        {/* Native bridges status */}
        <Section title="Native bridges" hint="Що увімкнено на цьому APK" theme={theme}>
          <Row label="Secure store" value={isSecureStoreNative() ? 'Keystore' : 'localStorage'} theme={theme} />
          <Row label="Біометрія" value={isBiometricNative() ? 'BiometricPrompt' : 'web n/a'} theme={theme} />
          <Row label="Share" value={isShareNative() ? 'Android Intent' : 'navigator.share'} theme={theme} />
          <SecondaryButton
            label="Відкрити /diag"
            onClick={() => navigate('/diag')}
            theme={theme}
          />
        </Section>

        {/* Dev mode */}
        <Section title="Розробник" hint="Допоміжні режими" theme={theme}>
          <SecondaryButton
            label={`Dev mode${devMode ? ' · ON' : ' · off'}`}
            onClick={toggleDevMode}
            theme={theme}
            active={devMode}
          />
        </Section>

        {/* Danger */}
        <Section title="Danger zone" hint="Стерти всі локальні дані" theme={theme}>
          <button
            onClick={() => void dangerWipe()}
            style={{
              minHeight: 48,
              padding: '0 14px',
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

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}

// ── Cross-world sync block ─────────────────────────────────────────────────
//
// Surface for the new phantom-os ↔ companion sync route. Three things:
//
//   1. Pull snapshot — refreshes user-scoped picture from the desktop
//      (preferences, profile list, fact metadata, vault card meta).
//      Used after a long offline window, factory reset, or new phone.
//
//   2. Push profile up — sends the local active LocalProfile to
//      /profile-sync/import so the desktop creates / updates the
//      matching Profile row + emits a `profile_imported` event.
//      Idempotent — clicking twice is safe.
//
//   3. Recent events — last 5 inbound `profile_sync` frames the WS
//      hub appended to profileSyncStore.events. Lets the operator
//      see "remote rename happened" / "fact added on the desktop"
//      without flipping screens.

interface CrossWorldSyncBlockProps {
  theme: PhantomTheme;
  activeProfile: LocalProfile | null;
}

function CrossWorldSyncBlock({ theme, activeProfile }: CrossWorldSyncBlockProps) {
  const snapshot = useProfileSyncStore((s) => s.snapshot);
  const snapshotPulledTs = useProfileSyncStore((s) => s.snapshotPulledTs);
  const events = useProfileSyncStore((s) => s.events);
  const [busy, setBusy] = useState<'pull' | 'push' | null>(null);

  const onPull = async () => {
    if (busy) return;
    setBusy('pull');
    try {
      const snap = await pullSnapshot(false);
      useToastStore.getState().push('success', `Snapshot · ${snap.profiles.length} profiles · ${snap.facts.length} facts`);
    } catch (err) {
      useToastStore.getState().push('error', `Snapshot: ${(err as Error).message}`);
    } finally {
      setBusy(null);
    }
  };

  const onPush = async () => {
    if (busy || !activeProfile) return;
    setBusy('push');
    try {
      const payload = buildImportPayloadFromLocal(activeProfile);
      const out = await importPhoneProfile(payload);
      useToastStore.getState().push(
        'success',
        out.is_new ? `Створено профіль ${activeProfile.displayName} на phantom-os` : 'Оновлено на phantom-os',
      );
    } catch (err) {
      useToastStore.getState().push('error', `Import: ${(err as Error).message}`);
    } finally {
      setBusy(null);
    }
  };

  const recent = events.slice(-5).reverse();

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          color: theme.ink3,
          letterSpacing: '0.16em',
        }}
      >
        {snapshotPulledTs
          ? `останній snapshot: ${new Date(snapshotPulledTs).toLocaleTimeString('en-GB')}`
          : 'snapshot ще не тягнули'}
        {snapshot && (
          <>
            {' · '}
            {snapshot.profiles.length} profiles · {snapshot.facts.length} facts · {snapshot.vault_cards.length}{' '}
            cards
          </>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8 }}>
        <button
          onClick={() => void onPull()}
          disabled={Boolean(busy)}
          style={{
            flex: 1,
            minHeight: 44,
            padding: '0 12px',
            borderRadius: 12,
            border: `1px solid ${theme.glassBorder}`,
            background: 'transparent',
            color: theme.ink2,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            opacity: busy ? 0.5 : 1,
          }}
        >
          {busy === 'pull' ? '…' : 'Pull snapshot'}
        </button>
        <button
          onClick={() => void onPush()}
          disabled={!activeProfile || Boolean(busy)}
          style={{
            flex: 1,
            minHeight: 44,
            padding: '0 12px',
            borderRadius: 12,
            border: `1px solid ${theme.primary}`,
            background: theme.primary,
            color: theme.isDark ? '#1a1208' : '#fff',
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            opacity: !activeProfile || busy ? 0.5 : 1,
          }}
        >
          {busy === 'push' ? '…' : 'Push profile up'}
        </button>
      </div>

      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          color: theme.ink3,
          letterSpacing: '0.16em',
          textTransform: 'uppercase',
          marginTop: 4,
        }}
      >
        recent events
      </div>
      {recent.length === 0 ? (
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11,
            color: theme.ink3,
            opacity: 0.7,
          }}
        >
          Поки тиша. Зміни на телефоні чи на phantom-os зʼявляться тут вживу.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {recent.map((e) => (
            <div
              key={e.id}
              style={{
                display: 'flex',
                alignItems: 'baseline',
                gap: 6,
                fontFamily: 'var(--mono)',
                fontSize: 10.5,
                color: e.remote ? theme.primary : theme.ink2,
                letterSpacing: '0.04em',
              }}
            >
              <span style={{ opacity: 0.6 }}>{new Date(e.ts).toLocaleTimeString('en-GB')}</span>
              <span style={{ fontWeight: 700, letterSpacing: '0.1em' }}>{e.remote ? '↓' : '↑'}</span>
              <span style={{ flex: 1 }}>{e.type}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Cross-device handoff block ────────────────────────────────────────────
//
// Today: a single rule. When phantom-os pushes a `map_route` handoff
// AND the IMU classifier has decided we're driving, the phone
// auto-accepts and routes to /map without asking. The whole point is
// "не лізь у телефон поки кермуєш" — operator's words.
//
// Toggle here flips that rule off; manual sheet still works for
// every kind regardless. Future rounds can add more rules
// (auto-decline file_drop while in DREAM, etc.).

interface CrossDeviceHandoffBlockProps {
  theme: PhantomTheme;
}

function CrossDeviceHandoffBlock({ theme }: CrossDeviceHandoffBlockProps) {
  const enabled = useHandoffStore((s) => s.autoAcceptDrivingRoute);
  const setEnabled = useHandoffStore((s) => s.setAutoAcceptDrivingRoute);
  const motionClass = useSensorStore((s) => s.motionClass);

  const motionLabel = motionClass ?? 'не визначено';
  const drivingNow = motionClass === 'driving';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          color: theme.ink3,
          letterSpacing: '0.16em',
        }}
      >
        зараз: {motionLabel}
        {drivingNow && enabled && ' · авто-acquire активне'}
      </div>

      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '8px 10px',
          borderRadius: 12,
          border: `1px solid ${theme.glassBorder}`,
          background: 'transparent',
        }}
      >
        <div style={{ flex: 1, fontSize: 13, color: theme.ink, lineHeight: 1.4 }}>
          Автоматично приймати маршрут від phantom-os коли я за кермом
          <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, marginTop: 4 }}>
            {enabled ? 'увімкнено' : 'вимкнено'}
          </div>
        </div>
        <button
          role="switch"
          aria-checked={enabled}
          onClick={() => setEnabled(!enabled)}
          style={{
            minWidth: 56,
            minHeight: 32,
            padding: '0 14px',
            borderRadius: 999,
            background: enabled ? theme.primary : 'transparent',
            color: enabled ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
            border: `1px solid ${enabled ? theme.primary : theme.glassBorder}`,
            fontFamily: 'var(--mono)',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          {enabled ? 'on' : 'off'}
        </button>
      </div>
    </div>
  );
}

// ── AI keys block ──────────────────────────────────────────────────────────
//
// Per-profile direct LLM API keys. Two providers supported:
//   • Gemini — Google AI Studio key (free tier covers personal use)
//   • OpenAI — gpt-4o-mini key (paid)
//
// When at least one is set, `aiRouter.chatTurn` gains a fallback path:
// host-paired+online → direct provider → queued. Keys live in
// `secureStore` keyed by profile id, so switching profiles loads the
// right keys automatically.

interface AiKeysBlockProps {
  theme: PhantomTheme;
  profileId: string;
}

function AiKeysBlock({ theme, profileId }: AiKeysBlockProps) {
  const [gemini, setGemini] = useState('');
  const [openai, setOpenai] = useState('');
  const [revealed, setRevealed] = useState<Record<DirectProviderName, boolean>>({
    gemini: false,
    openai: false,
  });
  const [busy, setBusy] = useState<DirectProviderName | null>(null);
  const [testResult, setTestResult] = useState<{ name: DirectProviderName; ok: boolean; msg: string } | null>(null);

  // Load saved keys for the current profile.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const k = await readDirectKeys(profileId);
      if (cancelled) return;
      setGemini(k.gemini);
      setOpenai(k.openai);
    })();
    return () => {
      cancelled = true;
    };
  }, [profileId]);

  const onSave = async (name: DirectProviderName) => {
    if (name === 'gemini') {
      await writeDirectKeys(profileId, { gemini });
      useToastStore.getState().push('success', gemini ? 'Gemini ключ збережено' : 'Gemini ключ стерто');
    } else {
      await writeDirectKeys(profileId, { openai });
      useToastStore.getState().push('success', openai ? 'OpenAI ключ збережено' : 'OpenAI ключ стерто');
    }
  };

  const onTest = async (name: DirectProviderName) => {
    setBusy(name);
    setTestResult(null);
    try {
      // Save first so the test reads the latest input value.
      await onSave(name);
      const provider = await buildDirectProvider(profileId, name);
      if (!provider) {
        setTestResult({ name, ok: false, msg: 'Ключ порожній' });
        return;
      }
      const out = await provider.chat({
        text: 'Скажи "ok" одним словом, без пунктуації.',
        maxOutputTokens: 16,
      });
      setTestResult({
        name,
        ok: true,
        msg: `${out.provider}:${out.model} → "${out.text.slice(0, 40)}"`,
      });
    } catch (err) {
      setTestResult({ name, ok: false, msg: (err as Error).message });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <KeyRow
        theme={theme}
        label="Gemini API key"
        hint="aistudio.google.com → Get API key (безкоштовний tier)"
        value={gemini}
        revealed={revealed.gemini}
        onChange={setGemini}
        onToggleReveal={() => setRevealed((r) => ({ ...r, gemini: !r.gemini }))}
        onSave={() => void onSave('gemini')}
        onTest={() => void onTest('gemini')}
        busy={busy === 'gemini'}
      />
      <KeyRow
        theme={theme}
        label="OpenAI API key"
        hint="platform.openai.com → API keys (платний)"
        value={openai}
        revealed={revealed.openai}
        onChange={setOpenai}
        onToggleReveal={() => setRevealed((r) => ({ ...r, openai: !r.openai }))}
        onSave={() => void onSave('openai')}
        onTest={() => void onTest('openai')}
        busy={busy === 'openai'}
      />
      {testResult && (
        <div
          style={{
            padding: '8px 10px',
            borderRadius: 10,
            border: `1px solid ${testResult.ok ? '#16A34A55' : theme.coral + '55'}`,
            background: 'transparent',
            fontFamily: 'var(--mono)',
            fontSize: 11,
            color: testResult.ok ? '#16A34A' : theme.coral,
            lineHeight: 1.45,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {testResult.ok ? '✓ ' : '✗ '}
          {testResult.name} · {testResult.msg}
        </div>
      )}
      <div
        style={{
          fontSize: 11,
          color: theme.ink3,
          lineHeight: 1.5,
          padding: '8px 10px',
          borderRadius: 10,
          background: theme.surfaceBase,
          border: `1px solid ${theme.glassBorder}`,
        }}
      >
        Пріоритет роутингу: phantom-os (paired+online) → direct
        Gemini → direct OpenAI → черга. Ключі зберігаються в
        secureStore (Android Keystore коли native, префіксований
        localStorage на web).
      </div>
    </div>
  );
}

interface KeyRowProps {
  theme: PhantomTheme;
  label: string;
  hint: string;
  value: string;
  revealed: boolean;
  onChange: (v: string) => void;
  onToggleReveal: () => void;
  onSave: () => void;
  onTest: () => void;
  busy: boolean;
}

function KeyRow({
  theme,
  label,
  hint,
  value,
  revealed,
  onChange,
  onToggleReveal,
  onSave,
  onTest,
  busy,
}: KeyRowProps) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          letterSpacing: '0.18em',
          color: theme.ink2,
          fontWeight: 700,
          textTransform: 'uppercase',
        }}
      >
        {label}
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          type={revealed ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={revealed ? 'AIza... or sk-...' : '•••••••••••'}
          autoComplete="off"
          spellCheck={false}
          style={{
            flex: 1,
            minHeight: 38,
            padding: '0 10px',
            borderRadius: 10,
            background: theme.surfaceBase,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--mono)',
            fontSize: 12,
          }}
        />
        <button
          onClick={onToggleReveal}
          aria-label={revealed ? 'Сховати' : 'Показати'}
          style={{
            minWidth: 38,
            minHeight: 38,
            borderRadius: 10,
            background: 'transparent',
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink2,
            fontSize: 14,
          }}
        >
          {revealed ? '◉' : '◌'}
        </button>
      </div>
      <div style={{ fontSize: 10, color: theme.ink3, lineHeight: 1.4 }}>{hint}</div>
      <div style={{ display: 'flex', gap: 6 }}>
        <button
          onClick={onSave}
          style={{
            flex: 1,
            minHeight: 36,
            borderRadius: 10,
            background: 'transparent',
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink2,
            fontFamily: 'var(--mono)',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.16em',
            textTransform: 'uppercase',
          }}
        >
          Зберегти
        </button>
        <button
          onClick={onTest}
          disabled={busy}
          style={{
            flex: 1,
            minHeight: 36,
            borderRadius: 10,
            background: busy ? theme.glassPanel : theme.primary,
            color: busy ? theme.ink3 : theme.isDark ? '#1a1208' : '#fff',
            border: `1px solid ${busy ? theme.glassBorder : theme.primary}`,
            fontFamily: 'var(--mono)',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.16em',
            textTransform: 'uppercase',
          }}
        >
          {busy ? '…' : '🧪 Тест'}
        </button>
      </div>
    </div>
  );
}
