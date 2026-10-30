// Global status bar — persistent across every screen via PhoneShell.
//
// Tells the operator at a glance:
//   * a colored pulse dot (paired+online / connecting / offline / unpaired)
//   * the active profile initials (tap → /me)
//   * connection word (LINK / OFFLINE)
//   * compact host hint (e.g. "10.0.0.5") so multi-host operators
//     know which Radxa they're talking to
//   * clock
//   * hamburger to open the utility drawer
//
// All this used to live across /diag + ConnectionBadge — now it's in
// thumb-tap reach without leaving the current screen. Tapping the
// dot opens /diag for the full breakdown.

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDeviceStore } from '@/state/deviceStore';
import { useConnectionStore } from '@/state/connectionStore';
import { useProfileStore } from '@/state/profileStore';
import type { PhantomTheme } from '@/types/theme';

const COLOR = {
  online: '#16A34A',
  connecting: '#F4AF25',
  offline: '#EF4444',
  unpaired: '#9A8A6F',
} as const;

interface GlobalStatusBarProps {
  theme: PhantomTheme;
  onOpenDrawer: () => void;
}

export function GlobalStatusBar({ theme, onOpenDrawer }: GlobalStatusBarProps) {
  const navigate = useNavigate();
  const profile = useProfileStore((s) => s.profile);
  const status = useConnectionStore((s) => s.status);
  const host = useDeviceStore((s) => s.host);
  const initials = profile
    ? profile.displayName.slice(0, 2).toUpperCase()
    : '◱';
  const dotColor = COLOR[status];
  const linkLabel = status === 'online' ? 'LINK' : status === 'connecting' ? '…' : status === 'offline' ? 'OFFLINE' : 'UNPAIRED';

  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(id);
  }, []);

  const cleanHost = host
    ? host.replace(/^https?:\/\//, '').split(':')[0]
    : '';

  return (
    <div
      style={{
        height: 44,
        padding: '8px 12px 0',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        flexShrink: 0,
        zIndex: 5,
        position: 'relative',
      }}
    >
      <button
        onClick={() => navigate('/me')}
        aria-label={`Open profile ${profile?.displayName ?? 'unset'}`}
        style={{
          width: 30,
          height: 30,
          borderRadius: '50%',
          background: `linear-gradient(135deg, ${theme.primary}55, ${theme.primary}22)`,
          border: `1px solid ${theme.glassBorder}`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontFamily: 'var(--mono)',
          fontSize: 10,
          fontWeight: 700,
          color: theme.primary,
          flexShrink: 0,
        }}
      >
        {initials}
      </button>

      <button
        onClick={() => navigate('/diag')}
        aria-label={`Connection ${status}; tap for diagnostics`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '4px 10px',
          borderRadius: 999,
          background: theme.isDark ? 'rgba(0,0,0,0.35)' : 'rgba(255,255,255,0.5)',
          border: `1px solid ${dotColor}33`,
          fontFamily: 'var(--mono)',
          fontSize: 9.5,
          letterSpacing: '0.18em',
          fontWeight: 700,
          color: dotColor,
          textTransform: 'uppercase',
          minHeight: 26,
        }}
      >
        <span
          style={{
            width: 7,
            height: 7,
            borderRadius: '50%',
            background: dotColor,
            boxShadow: status === 'online' ? `0 0 6px ${dotColor}` : 'none',
            animation: status === 'connecting' ? 'pulse 1s ease-in-out infinite' : 'none',
          }}
        />
        {linkLabel}
      </button>

      {cleanHost && (
        <span
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 10,
            color: theme.ink3,
            letterSpacing: '0.08em',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            maxWidth: '24vw',
          }}
        >
          {cleanHost}
        </span>
      )}

      <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 4 }}>
        <span
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11,
            color: theme.ink2,
          }}
        >
          {now.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}
        </span>
        <button
          onClick={onOpenDrawer}
          aria-label="Open utility drawer"
          style={{
            width: 32,
            height: 32,
            borderRadius: 8,
            color: theme.ink2,
            fontSize: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'transparent',
            border: `1px solid ${theme.glassBorder}`,
          }}
        >
          ☰
        </button>
      </div>
    </div>
  );
}
