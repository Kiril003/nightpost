// In-screen contextual banner. Sits inline above the action it
// describes (rather than floating at the top of the viewport like
// Toaster), so the success/error message lives next to the button
// that produced it. Dismissed via the X icon or via `auto-hide` ms.
//
// The Toaster is still alive for global / cross-screen events (pair
// success, profile switch). Banners are for in-flow feedback ("Текст
// доставлено", "Не вдалося прочитати картку").

import { useEffect } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import type { PhantomTheme } from '@/types/theme';
import { hexToRgb } from '@/utils/color';

export type BannerKind = 'info' | 'success' | 'warn' | 'error';

const KIND_TINT: Record<BannerKind, (t: PhantomTheme) => string> = {
  info: (t) => t.primary,
  success: () => '#16A34A',
  warn: () => '#F4AF25',
  error: (t) => t.coral,
};

export interface BannerProps {
  theme: PhantomTheme;
  kind?: BannerKind;
  /** Title shown in mono uppercase. Optional — if absent, only the
   *  body line is rendered. */
  title?: string;
  /** Body line; takes priority over children. */
  message?: string;
  /** Slot for richer content (e.g. inline buttons). */
  children?: ReactNode;
  /** Auto-dismiss after N ms. Set 0 to disable. */
  autoHideMs?: number;
  /** Called when the X icon is tapped or autoHideMs elapses. */
  onDismiss?: () => void;
  /** Right-side trailing slot — usually a tiny action button. */
  action?: { label: string; onPress: () => void };
  style?: CSSProperties;
  /** When true, the close X disappears (banner is non-dismissable). */
  pinned?: boolean;
}

export function Banner({
  theme,
  kind = 'info',
  title,
  message,
  children,
  autoHideMs = 0,
  onDismiss,
  action,
  style,
  pinned = false,
}: BannerProps) {
  const tint = KIND_TINT[kind](theme);
  const [r, g, b] = hexToRgb(tint);

  useEffect(() => {
    if (!autoHideMs || !onDismiss) return;
    const id = setTimeout(onDismiss, autoHideMs);
    return () => clearTimeout(id);
  }, [autoHideMs, onDismiss]);

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        background: `linear-gradient(135deg, rgba(${r},${g},${b},0.16) 0%, rgba(${r},${g},${b},0.07) 100%)`,
        border: `1px solid rgba(${r},${g},${b},0.45)`,
        borderLeft: `3px solid ${tint}`,
        borderRadius: 12,
        padding: '10px 12px 10px 14px',
        display: 'flex',
        alignItems: 'flex-start',
        gap: 10,
        ...style,
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        {title && (
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              fontWeight: 700,
              color: tint,
              marginBottom: message || children ? 4 : 0,
            }}
          >
            {title}
          </div>
        )}
        {message && (
          <div
            style={{
              fontSize: 12.5,
              color: theme.ink,
              lineHeight: 1.45,
              fontFamily: 'var(--sans)',
            }}
          >
            {message}
          </div>
        )}
        {children && <div style={{ fontSize: 12.5, color: theme.ink, lineHeight: 1.45 }}>{children}</div>}
      </div>
      {action && (
        <button
          onClick={action.onPress}
          style={{
            flex: '0 0 auto',
            minHeight: 30,
            padding: '0 10px',
            borderRadius: 8,
            border: `1px solid rgba(${r},${g},${b},0.55)`,
            background: 'transparent',
            color: tint,
            fontFamily: 'var(--mono)',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          {action.label}
        </button>
      )}
      {!pinned && onDismiss && (
        <button
          onClick={onDismiss}
          aria-label="Dismiss"
          style={{
            flex: '0 0 auto',
            width: 24,
            height: 24,
            borderRadius: 8,
            background: 'transparent',
            border: 'none',
            color: theme.ink3,
            fontSize: 14,
            lineHeight: 1,
            cursor: 'pointer',
          }}
        >
          ×
        </button>
      )}
    </div>
  );
}
