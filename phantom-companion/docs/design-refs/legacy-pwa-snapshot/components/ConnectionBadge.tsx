import { useConnectionStore } from '@/state/connectionStore';
import type { PhantomTheme } from '@/types/theme';

interface ConnectionBadgeProps {
  theme: PhantomTheme;
}

const COLOR = {
  online: '#16A34A',
  connecting: '#F4AF25',
  offline: '#9A8A6F',
  unpaired: '#9A8A6F',
} as const;

const LABEL = {
  online: 'LINK',
  connecting: '…',
  offline: 'OFFLINE',
  unpaired: 'UNPAIRED',
} as const;

export function ConnectionBadge({ theme }: ConnectionBadgeProps) {
  const status = useConnectionStore((s) => s.status);
  const color = COLOR[status];
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '3px 9px',
        borderRadius: 999,
        background: theme.isDark ? 'rgba(0,0,0,0.35)' : 'rgba(255,255,255,0.35)',
        border: `1px solid ${color}33`,
        fontFamily: 'var(--mono)',
        fontSize: 9.5,
        letterSpacing: '0.18em',
        fontWeight: 700,
        color,
        textTransform: 'uppercase',
      }}
      aria-label={`Connection: ${status}`}
    >
      <span
        style={{
          width: 6,
          height: 6,
          borderRadius: '50%',
          background: color,
          boxShadow: status === 'online' ? `0 0 6px ${color}` : 'none',
          animation: status === 'connecting' ? 'pulse 1s ease-in-out infinite' : 'none',
        }}
      />
      {LABEL[status]}
    </div>
  );
}
