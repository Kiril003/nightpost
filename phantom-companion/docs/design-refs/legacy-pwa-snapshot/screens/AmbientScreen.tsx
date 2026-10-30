// Phase 7 Hub Mode — Ambient / Always-On-Display style screen.
//
// Minimal-pixel surface designed to live on a propped-up phone as a
// glance widget: time, latest pulse vitals, current PHANTOM state,
// last proactive line. No animations beyond the breathing orb. Wake-
// lock keeps the screen alive while the route is mounted.
//
// True Android AOD requires a system role (the launcher) which only
// the native track gets. This route covers the PWA-feasible "leave
// it on the desk" use case.

import { useEffect, useState } from 'react';

import { GlassCard } from '@/components/GlassCard';
import { OrbView } from '@/components/OrbView';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useSensorStore } from '@/state/sensorStore';
import { useConnectionStore } from '@/state/connectionStore';
import { startWakeLock, stopWakeLock, isWakeLockSupported } from '@/services/wakeLock';
import type { PhantomTheme } from '@/types/theme';

interface AmbientScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

function formatClock(d: Date): string {
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

export function AmbientScreen({ themeOverride, framed = false }: AmbientScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const { stateKey, accent, motionScale } = useStateAccent();
  const vitals = useSensorStore((s) => s.vitals);
  const quote = useSensorStore((s) => s.quote);
  const connStatus = useConnectionStore((s) => s.status);

  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    void startWakeLock();
    const tick = setInterval(() => setNow(new Date()), 1000);
    return () => {
      clearInterval(tick);
      void stopWakeLock();
    };
  }, []);

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          padding: '24px 24px 16px',
          display: 'flex',
          flexDirection: 'column',
          gap: 18,
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontWeight: 300,
            fontSize: 86,
            lineHeight: 1,
            color: theme.ink,
            letterSpacing: '-0.04em',
          }}
        >
          {formatClock(now)}
        </div>

        <div style={{ position: 'relative', display: 'flex', justifyContent: 'center' }}>
          <OrbView accent={accent} theme={theme} motionScale={motionScale} size={170} ringCount={2} />
          <div
            style={{
              position: 'absolute',
              bottom: -8,
              left: 0,
              right: 0,
              textAlign: 'center',
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              color: theme.ink3,
              textTransform: 'uppercase',
            }}
          >
            {stateKey.toLowerCase()} · {connStatus}
          </div>
        </div>

        {vitals && (
          <GlassCard level="card" theme={theme} style={{ padding: '12px 18px', width: '100%', textAlign: 'center' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                color: theme.ink3,
                fontWeight: 600,
                marginBottom: 4,
              }}
            >
              vitals
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: 16, color: theme.ink }}>
              <span>{vitals.bpm.toFixed(0)} bpm</span>
              <span>{vitals.breath.toFixed(0)} br</span>
              <span>{(vitals.stress * 100).toFixed(0)}% stress</span>
            </div>
          </GlassCard>
        )}

        {quote && (
          <GlassCard level="subtle" theme={theme} style={{ padding: '14px 18px', width: '100%' }}>
            <div
              style={{
                fontFamily: 'var(--serif)',
                fontStyle: 'italic',
                fontSize: 14,
                lineHeight: 1.45,
                color: theme.ink2,
                textAlign: 'center',
              }}
            >
              {quote.text}
            </div>
          </GlassCard>
        )}

        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 9,
            color: theme.ink3,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            opacity: 0.7,
          }}
        >
          {isWakeLockSupported() ? 'wake-lock active' : 'screen may sleep'}
        </div>
      </div>
    </PhoneShell>
  );
}
