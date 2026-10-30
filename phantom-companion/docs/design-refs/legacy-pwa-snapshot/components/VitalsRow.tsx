import type { CSSProperties } from 'react';
import { GlassCard } from './GlassCard';
import { Sparkline } from './Sparkline';
import { EmptyState } from './EmptyState';
import { useSensorStore } from '@/state/sensorStore';
import { useT } from '@/hooks/useT';
import type { PhantomTheme } from '@/types/theme';

interface VitalsRowProps {
  theme: PhantomTheme;
  accent: string;
}

export function VitalsRow({ theme, accent }: VitalsRowProps) {
  const vitals = useSensorStore((s) => s.vitals);
  const t = useT();

  if (!vitals) {
    return (
      <GlassCard level="subtle" theme={theme} style={{ padding: 0 }}>
        <EmptyState
          theme={theme}
          icon="♡"
          title={t('pulse.no_signal.title')}
          hint={t('pulse.no_signal.hint')}
          compact
        />
      </GlassCard>
    );
  }

  const cellStyle: CSSProperties = {
    flex: 1,
    padding: '10px 12px',
    display: 'flex',
    flexDirection: 'column',
    gap: 4,
  };
  const labelStyle: CSSProperties = {
    fontFamily: 'var(--mono)',
    fontSize: 9.5,
    letterSpacing: '0.2em',
    textTransform: 'uppercase',
    color: theme.ink3,
    fontWeight: 600,
  };
  const valStyle = (size = 22): CSSProperties => ({
    fontFamily: 'var(--mono)',
    fontSize: size,
    fontWeight: 600,
    color: theme.ink,
    lineHeight: 1,
    letterSpacing: '-0.02em',
  });

  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <GlassCard level="subtle" theme={theme} style={cellStyle}>
        <div style={labelStyle}>BPM</div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={valStyle(24)}>{vitals.bpm || '—'}</span>
          {!!vitals.bpmDelta && (
            <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>
              {vitals.bpmDelta < 0 ? '↓' : '↑'} {Math.abs(vitals.bpmDelta)}
            </span>
          )}
        </div>
        <Sparkline data={vitals.bpmTrend} color={accent} />
      </GlassCard>
      <GlassCard level="subtle" theme={theme} style={cellStyle}>
        <div style={labelStyle}>BREATH</div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={valStyle(24)}>{vitals.breath || '—'}</span>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>/min</span>
        </div>
        <Sparkline data={vitals.breathTrend} color={accent} />
      </GlassCard>
      <GlassCard level="subtle" theme={theme} style={cellStyle}>
        <div style={labelStyle}>STRESS</div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={valStyle(24)}>
            {vitals.stress > 0 ? vitals.stress.toFixed(2).replace('0.', '.') : '—'}
          </span>
          {vitals.stress > 0 && vitals.stress < 0.4 && (
            <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: '#16A34A' }}>calm</span>
          )}
          {vitals.stress >= 0.4 && vitals.stress < 0.7 && (
            <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: '#F4AF25' }}>elev</span>
          )}
          {vitals.stress >= 0.7 && (
            <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: '#EF4444' }}>high</span>
          )}
        </div>
        <Sparkline data={vitals.stressTrend} color={accent} />
      </GlassCard>
    </div>
  );
}
