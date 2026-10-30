// Big primary action bar — sits above BottomNav on screens that have
// one obvious "do the thing" CTA. Replaces ad-hoc absolutely-positioned
// FABs scattered across screens (CommsScreen, VaultScreen, DriverScreen),
// and gives Voice / Driver / Senses a uniform PTT-style anchor.
//
// Usage:
//   <PrimaryActionBar
//     theme={theme}
//     accent={accent}
//     label="Зняти знімок"
//     hint="Камера → OCR → чат"
//     onPress={handleCapture}
//     icon="◉"
//     busy={busy}
//   />
//
// The bar is full-width with safe-area-aware bottom padding so it
// never collides with the system gesture bar. Disabled state keeps
// the geometry stable so layouts don't jump when busy=true.

import type { CSSProperties, PointerEvent, ReactNode } from 'react';
import type { PhantomTheme } from '@/types/theme';
import { hexToRgb } from '@/utils/color';

export interface PrimaryActionBarProps {
  theme: PhantomTheme;
  accent: string;
  /** Main button label. */
  label: string;
  /** Sub-label rendered below the main label in mono. Optional. */
  hint?: string;
  /** Iconographic glyph/letter that goes in the round chip on the left. */
  icon?: string;
  /** Pressed → fires once on pointer-up. Use the press-hold flow below
   *  if you want a PTT-style hold-to-speak interaction instead. */
  onPress?: () => void;
  /** Press-hold pair. When provided, replaces onPress and the bar
   *  emits both events. */
  onPressDown?: (e: PointerEvent<HTMLButtonElement>) => void;
  onPressUp?: (e: PointerEvent<HTMLButtonElement>) => void;
  /** Visual busy state — dims the bar, swaps the label for an ellipsis. */
  busy?: boolean;
  /** Disabled state — like busy but keeps the label visible. */
  disabled?: boolean;
  /** Coral / error tint for destructive actions. */
  destructive?: boolean;
  /** Optional secondary action rendered to the left as an outline pill. */
  secondary?: { label: string; onPress: () => void };
  /** Right-side trailing slot. Used by Voice for the live audio meter. */
  trailing?: ReactNode;
  /** Override the bottom safe-area padding when the bar lives outside
   *  the standard PhoneShell scaffold (e.g. Showcase). */
  bottomInset?: number;
  /** Extra style overrides for the outer wrapper. */
  style?: CSSProperties;
  /** Optional aria-label override; falls back to `label`. */
  ariaLabel?: string;
}

export function PrimaryActionBar({
  theme,
  accent,
  label,
  hint,
  icon,
  onPress,
  onPressDown,
  onPressUp,
  busy = false,
  disabled = false,
  destructive = false,
  secondary,
  trailing,
  bottomInset,
  style,
  ariaLabel,
}: PrimaryActionBarProps) {
  const tint = destructive ? theme.coral : accent;
  const [r, g, b] = hexToRgb(tint);
  const idle = !busy && !disabled;
  const supportsHold = Boolean(onPressDown || onPressUp);

  return (
    <div
      style={{
        position: 'relative',
        padding: '10px 12px',
        paddingBottom:
          typeof bottomInset === 'number'
            ? bottomInset
            : 'calc(10px + env(safe-area-inset-bottom, 0px))',
        background: `linear-gradient(180deg, transparent 0%, ${
          theme.isDark ? 'rgba(8,5,2,0.55)' : 'rgba(255,247,233,0.55)'
        } 60%)`,
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
        ...style,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'stretch', gap: 8 }}>
        {secondary && (
          <button
            onClick={secondary.onPress}
            disabled={!idle}
            style={{
              flex: '0 0 auto',
              minWidth: 88,
              padding: '0 14px',
              borderRadius: 16,
              border: `1px solid ${theme.glassBorder}`,
              background: 'transparent',
              color: theme.ink2,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
              opacity: idle ? 1 : 0.5,
            }}
          >
            {secondary.label}
          </button>
        )}
        <button
          aria-label={ariaLabel ?? label}
          disabled={!idle}
          onClick={
            supportsHold || !onPress
              ? undefined
              : () => {
                  if (idle) onPress();
                }
          }
          onPointerDown={onPressDown}
          onPointerUp={onPressUp}
          onPointerCancel={onPressUp}
          onPointerLeave={onPressUp}
          style={{
            flex: 1,
            minHeight: 64,
            display: 'flex',
            alignItems: 'center',
            gap: 14,
            padding: '0 18px',
            borderRadius: 18,
            background: idle
              ? `linear-gradient(135deg, rgba(${r},${g},${b},0.95) 0%, rgba(${r},${g},${b},0.78) 100%)`
              : `rgba(${r},${g},${b},0.42)`,
            color: theme.isDark ? '#1a1208' : '#fff',
            border: `1px solid rgba(${r},${g},${b},0.55)`,
            fontFamily: 'var(--mono)',
            fontSize: 13,
            fontWeight: 800,
            letterSpacing: '0.16em',
            textTransform: 'uppercase',
            boxShadow: idle
              ? `0 12px 28px rgba(${r},${g},${b},0.45), inset 0 -6px 16px rgba(0,0,0,0.18)`
              : 'none',
            transition: 'transform 90ms ease, box-shadow 120ms ease',
            transform: busy ? 'scale(0.99)' : 'scale(1)',
            touchAction: 'manipulation',
            userSelect: 'none',
          }}
        >
          {icon && (
            <span
              aria-hidden
              style={{
                width: 36,
                height: 36,
                borderRadius: 12,
                background: `rgba(255,255,255,${theme.isDark ? 0.18 : 0.32})`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 18,
                color: theme.isDark ? '#1a1208' : '#3a200e',
                flexShrink: 0,
              }}
            >
              {icon}
            </span>
          )}
          <div
            style={{
              flex: 1,
              textAlign: 'left',
              display: 'flex',
              flexDirection: 'column',
              gap: 2,
              minWidth: 0,
            }}
          >
            <span
              style={{
                fontSize: 13,
                fontWeight: 800,
                letterSpacing: '0.16em',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {busy ? '…' : label}
            </span>
            {hint && (
              <span
                style={{
                  fontSize: 10,
                  fontWeight: 600,
                  letterSpacing: '0.14em',
                  textTransform: 'none',
                  opacity: 0.85,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {hint}
              </span>
            )}
          </div>
          {trailing && (
            <div style={{ flexShrink: 0, display: 'flex', alignItems: 'center' }}>
              {trailing}
            </div>
          )}
        </button>
      </div>
    </div>
  );
}
