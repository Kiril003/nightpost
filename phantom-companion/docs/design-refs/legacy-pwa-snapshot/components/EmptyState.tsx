import type { ReactNode } from 'react';
import type { PhantomTheme } from '@/types/theme';

interface EmptyStateProps {
  theme: PhantomTheme;
  icon?: string;
  title: string;
  hint?: string;
  cta?: ReactNode;
  compact?: boolean;
}

export function EmptyState({ theme, icon = '◌', title, hint, cta, compact = false }: EmptyStateProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: compact ? 6 : 10,
        padding: compact ? '12px 14px' : '32px 24px',
        textAlign: 'center',
        color: theme.ink2,
        opacity: 0.92,
      }}
    >
      <div
        style={{
          width: compact ? 36 : 56,
          height: compact ? 36 : 56,
          borderRadius: '50%',
          border: `1px dashed ${theme.glassBorder}`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: theme.ink3,
          fontSize: compact ? 18 : 24,
        }}
        aria-hidden
      >
        {icon}
      </div>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: compact ? 11 : 13,
          letterSpacing: '0.18em',
          textTransform: 'uppercase',
          fontWeight: 700,
          color: theme.ink,
        }}
      >
        {title}
      </div>
      {hint && (
        <div style={{ fontSize: compact ? 11 : 12.5, color: theme.ink2, lineHeight: 1.5, maxWidth: 260 }}>{hint}</div>
      )}
      {cta && <div style={{ marginTop: 6 }}>{cta}</div>}
    </div>
  );
}
