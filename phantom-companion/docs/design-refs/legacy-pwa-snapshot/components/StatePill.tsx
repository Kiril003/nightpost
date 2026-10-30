import type { CSSProperties } from 'react';
import { PHANTOM_STATES } from '@/theme/tokens';
import type { PhantomTheme } from '@/types/theme';
import type { SystemStateKey } from '@/types/state';

interface StatePillProps {
  stateKey: SystemStateKey;
  accent: string;
  theme: PhantomTheme;
  sub?: string;
  expanded?: boolean;
  style?: CSSProperties;
}

export function StatePill({ stateKey, accent, theme, sub, expanded = false, style }: StatePillProps) {
  const s = PHANTOM_STATES[stateKey];
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 10,
        padding: expanded ? '12px 16px' : '6px 12px',
        borderRadius: 999,
        background: theme.isDark ? 'rgba(244,175,37,0.08)' : 'rgba(176,122,16,0.06)',
        border: `1px solid ${accent}33`,
        ...style,
      }}
    >
      <span
        style={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          background: accent,
          boxShadow: `0 0 8px ${accent}`,
        }}
      />
      <span
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 11,
          fontWeight: 600,
          letterSpacing: '0.18em',
          color: accent,
        }}
      >
        {s.label}
      </span>
      {sub && (
        <>
          <span style={{ color: theme.ink3, fontSize: 11 }}>·</span>
          <span style={{ fontSize: 12, color: theme.ink2, fontWeight: 500 }}>{sub}</span>
        </>
      )}
      {expanded && <span style={{ marginLeft: 'auto', color: theme.ink2, fontSize: 12 }}>▾</span>}
    </div>
  );
}
