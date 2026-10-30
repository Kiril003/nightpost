// Local timer / pomodoro / alarm screen. Uses Capacitor
// LocalNotifications for fire-on-completion (already installed)
// so timers survive screen-off. Pure-local — no phantom-os call.

import { useEffect, useMemo, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import {
  useTimerStore,
  remainingMs,
  buildPomodoroPhases,
  type Timer,
  type TimerKind,
} from '@/state/timerStore';
import {
  scheduleTimer,
  cancelTimer,
  ensureNotificationPermission,
} from '@/services/timerScheduler';
import type { PhantomTheme } from '@/types/theme';

function newId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return (crypto as Crypto).randomUUID();
  }
  return `t-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function fmtMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

interface TimerScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function TimerScreen({ themeOverride, framed = false }: TimerScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const timers = useTimerStore((s) => s.timers);
  const upsert = useTimerStore((s) => s.upsert);
  const remove = useTimerStore((s) => s.remove);
  const setStatus = useTimerStore((s) => s.setStatus);
  const patch = useTimerStore((s) => s.patch);
  const [nowTick, setNowTick] = useState(Date.now());
  const [composeOpen, setComposeOpen] = useState(false);

  // Tick UI once per second so live readouts stay current.
  useEffect(() => {
    const id = setInterval(() => setNowTick(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const sorted = useMemo(
    () => [...timers].sort((a, b) => b.createdAtMs - a.createdAtMs),
    [timers],
  );

  const onCreate = async (kind: TimerKind, label: string, secondsOrTargetMs: number) => {
    await ensureNotificationPermission();
    const t: Timer = {
      id: newId(),
      kind,
      label: label || (kind === 'pomodoro' ? 'Pomodoro' : kind === 'alarm' ? 'Alarm' : 'Timer'),
      status: 'running',
      targetMs: kind === 'alarm' ? secondsOrTargetMs : secondsOrTargetMs * 1000,
      startedAtMs: Date.now(),
      pausedRemainingMs: null,
      pomodoro:
        kind === 'pomodoro'
          ? { phaseIdx: 0, phases: buildPomodoroPhases() }
          : undefined,
      notificationId: null,
      createdAtMs: Date.now(),
    };
    upsert(t);
    await scheduleTimer(t);
    setComposeOpen(false);
    useToastStore.getState().push('success', `${t.label} запущено`);
  };

  const onPause = async (t: Timer) => {
    if (t.status !== 'running') return;
    const remain = remainingMs(t);
    patch(t.id, { status: 'paused', pausedRemainingMs: remain });
    await cancelTimer(t);
  };

  const onResume = async (t: Timer) => {
    if (t.status !== 'paused' || t.pausedRemainingMs === null) return;
    const restarted: Timer = {
      ...t,
      status: 'running',
      startedAtMs: Date.now(),
      targetMs:
        t.kind === 'alarm'
          ? t.targetMs
          : t.pausedRemainingMs,
      pausedRemainingMs: null,
    };
    patch(t.id, restarted);
    await scheduleTimer(restarted);
  };

  const onStop = async (t: Timer) => {
    await cancelTimer(t);
    setStatus(t.id, 'finished');
  };

  const onDelete = async (t: Timer) => {
    await cancelTimer(t);
    remove(t.id);
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
          ⏱ таймери · {timers.length}
        </span>
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
        {sorted.length === 0 ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="⏱"
              title="ще немає таймерів"
              hint="Натисни + щоб створити Pomodoro або таймер."
            />
          </GlassCard>
        ) : (
          sorted.map((t) => {
            const remaining = remainingMs(t, nowTick);
            const isLive = t.status === 'running';
            const isFinished = remaining <= 0 && t.status === 'running';
            return (
              <GlassCard
                key={t.id}
                level="card"
                theme={theme}
                accentBorder={isLive}
                accent={theme.primary}
                style={{ padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span
                    style={{
                      fontFamily: 'var(--mono)',
                      fontSize: 9,
                      color: theme.primary,
                      letterSpacing: '0.18em',
                      fontWeight: 700,
                      textTransform: 'uppercase',
                    }}
                  >
                    {t.kind}
                  </span>
                  <span style={{ flex: 1, fontSize: 14, color: theme.ink, fontWeight: 600 }}>
                    {t.label}
                  </span>
                  <span
                    style={{
                      fontFamily: 'var(--mono)',
                      fontSize: 9,
                      color: theme.ink3,
                      letterSpacing: '0.14em',
                    }}
                  >
                    {t.status}
                  </span>
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 32,
                    fontWeight: 700,
                    color: isFinished ? theme.coral : theme.ink,
                    letterSpacing: '0.04em',
                  }}
                >
                  {fmtMs(remaining)}
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  {t.status === 'running' && (
                    <button
                      onClick={() => void onPause(t)}
                      style={btnStyle(theme, 'secondary')}
                    >
                      ⏸ пауза
                    </button>
                  )}
                  {t.status === 'paused' && (
                    <button
                      onClick={() => void onResume(t)}
                      style={btnStyle(theme, 'primary')}
                    >
                      ▶ продовжити
                    </button>
                  )}
                  {(t.status === 'running' || t.status === 'paused') && (
                    <button
                      onClick={() => void onStop(t)}
                      style={btnStyle(theme, 'secondary')}
                    >
                      ◼ стоп
                    </button>
                  )}
                  <button
                    onClick={() => void onDelete(t)}
                    style={{ ...btnStyle(theme, 'danger'), flex: '0 0 auto' }}
                  >
                    ×
                  </button>
                </div>
              </GlassCard>
            );
          })
        )}
      </div>

      <button
        onClick={() => setComposeOpen(true)}
        aria-label="Новий таймер"
        style={{
          position: 'absolute',
          right: 16,
          bottom: 76,
          width: 56,
          height: 56,
          borderRadius: 18,
          background: `radial-gradient(circle at 30% 25%, ${theme.primary}, ${theme.primary}dd)`,
          color: theme.isDark ? '#1a1208' : '#fff',
          border: 'none',
          fontSize: 28,
          fontWeight: 300,
          lineHeight: 1,
          boxShadow: `0 10px 24px ${theme.primary}55`,
          zIndex: 5,
        }}
      >
        +
      </button>

      {composeOpen && (
        <ComposeSheet theme={theme} onClose={() => setComposeOpen(false)} onCreate={onCreate} />
      )}

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}

function btnStyle(theme: PhantomTheme, variant: 'primary' | 'secondary' | 'danger'): React.CSSProperties {
  const isPrimary = variant === 'primary';
  const isDanger = variant === 'danger';
  return {
    flex: 1,
    minHeight: 36,
    padding: '0 10px',
    borderRadius: 10,
    background: isPrimary ? theme.primary : 'transparent',
    color: isPrimary ? (theme.isDark ? '#1a1208' : '#fff') : isDanger ? theme.coral : theme.ink2,
    border: `1px solid ${isPrimary ? theme.primary : isDanger ? theme.coral + '55' : theme.glassBorder}`,
    fontFamily: 'var(--mono)',
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: '0.16em',
    textTransform: 'uppercase',
  };
}

interface ComposeSheetProps {
  theme: PhantomTheme;
  onClose: () => void;
  onCreate: (kind: TimerKind, label: string, secondsOrTargetMs: number) => void;
}

function ComposeSheet({ theme, onClose, onCreate }: ComposeSheetProps) {
  const [kind, setKind] = useState<TimerKind>('countdown');
  const [label, setLabel] = useState('');
  const [minutes, setMinutes] = useState('25');
  const [alarmAt, setAlarmAt] = useState(() => {
    const next = new Date(Date.now() + 30 * 60 * 1000);
    return next.toISOString().slice(0, 16);
  });

  const onSubmit = () => {
    if (kind === 'alarm') {
      const targetMs = Date.parse(alarmAt);
      if (!Number.isFinite(targetMs) || targetMs <= Date.now()) return;
      onCreate(kind, label, targetMs);
      return;
    }
    if (kind === 'pomodoro') {
      // First phase = 25 min work.
      onCreate(kind, label || 'Pomodoro', 25 * 60);
      return;
    }
    const m = Number.parseInt(minutes, 10);
    if (!Number.isFinite(m) || m <= 0) return;
    onCreate(kind, label, m * 60);
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.4)',
        zIndex: 20,
        display: 'flex',
        alignItems: 'flex-end',
      }}
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: '100%',
          background: theme.surfaceBase,
          borderTop: `1px solid ${theme.glassBorder}`,
          borderRadius: '14px 14px 0 0',
          padding: '14px 16px calc(env(safe-area-inset-bottom, 14px) + 14px)',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
        }}
      >
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 10,
            letterSpacing: '0.22em',
            fontWeight: 700,
            textTransform: 'uppercase',
            color: theme.primary,
          }}
        >
          новий таймер
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          {(['countdown', 'pomodoro', 'alarm'] as TimerKind[]).map((k) => (
            <button
              key={k}
              onClick={() => setKind(k)}
              style={{
                flex: 1,
                minHeight: 36,
                borderRadius: 10,
                background: kind === k ? theme.primary : 'transparent',
                color: kind === k ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
                border: `1px solid ${kind === k ? theme.primary : theme.glassBorder}`,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
              }}
            >
              {k}
            </button>
          ))}
        </div>
        <input
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          placeholder="Назва (опціонально)"
          style={{
            minHeight: 38,
            padding: '0 12px',
            borderRadius: 10,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--sans)',
            fontSize: 13,
          }}
        />
        {kind === 'countdown' && (
          <input
            type="number"
            min={1}
            value={minutes}
            onChange={(e) => setMinutes(e.target.value)}
            placeholder="Хвилин"
            style={{
              minHeight: 38,
              padding: '0 12px',
              borderRadius: 10,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--mono)',
              fontSize: 14,
            }}
          />
        )}
        {kind === 'alarm' && (
          <input
            type="datetime-local"
            value={alarmAt}
            onChange={(e) => setAlarmAt(e.target.value)}
            style={{
              minHeight: 38,
              padding: '0 12px',
              borderRadius: 10,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--mono)',
              fontSize: 13,
            }}
          />
        )}
        {kind === 'pomodoro' && (
          <div style={{ fontSize: 12, color: theme.ink3, fontFamily: 'var(--mono)' }}>
            25 хв робота · 5 хв пауза · 4 цикли · довга пауза 15 хв
          </div>
        )}
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={onClose} style={btnStyle(theme, 'secondary')}>
            скасувати
          </button>
          <button onClick={onSubmit} style={btnStyle(theme, 'primary')}>
            запустити
          </button>
        </div>
      </div>
    </div>
  );
}
