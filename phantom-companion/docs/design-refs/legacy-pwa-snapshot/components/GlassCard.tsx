import { type CSSProperties, type PointerEventHandler, type ReactNode } from 'react';
import type { PhantomTheme } from '@/types/theme';

export type GlassLevel = 'subtle' | 'panel' | 'card' | 'elevated';

interface GlassCardProps {
  level?: GlassLevel;
  accentBorder?: boolean;
  accent?: string;
  theme: PhantomTheme;
  style?: CSSProperties;
  children?: ReactNode;
  onClick?: () => void;
  className?: string;
  onPointerDown?: PointerEventHandler<HTMLDivElement>;
  onPointerUp?: PointerEventHandler<HTMLDivElement>;
  onPointerLeave?: PointerEventHandler<HTMLDivElement>;
  onPointerCancel?: PointerEventHandler<HTMLDivElement>;
}

const BG: Record<GlassLevel, (t: PhantomTheme) => string> = {
  subtle: (t) => t.glassPanel,
  panel: (t) => t.glassPanel,
  card: (t) => t.glassCard,
  elevated: (t) => t.glassElevated,
};

const BLUR: Record<GlassLevel, number> = {
  subtle: 12,
  panel: 12,
  card: 24,
  elevated: 32,
};

export function GlassCard({
  level = 'panel',
  accentBorder = false,
  accent,
  theme,
  style,
  children,
  onClick,
  className,
  onPointerDown,
  onPointerUp,
  onPointerLeave,
  onPointerCancel,
}: GlassCardProps) {
  const blur = BLUR[level];
  return (
    <div
      onClick={onClick}
      onPointerDown={onPointerDown}
      onPointerUp={onPointerUp}
      onPointerLeave={onPointerLeave}
      onPointerCancel={onPointerCancel}
      className={className}
      style={{
        position: 'relative',
        background: BG[level](theme),
        backdropFilter: `blur(${blur}px) saturate(1.1)`,
        WebkitBackdropFilter: `blur(${blur}px) saturate(1.1)`,
        border: `1px solid ${theme.glassBorder}`,
        borderRadius: 20,
        // Default visible so internal expanders (sliders, accordion
        // section bodies, dropdown menus) don't get clipped — operator
        // bug 2026-05-04 "елементи в картках не видно". Callers that
        // need to clip (video preview, QR camera card) pass
        // `overflow: 'hidden'` via `style` and override this.
        overflow: 'visible',
        boxShadow: theme.isDark
          ? `0 8px 24px rgba(0,0,0,0.45), inset 0 1px 0 ${theme.glassHL}`
          : `0 8px 24px rgba(120,80,10,0.08), inset 0 1px 0 ${theme.glassHL}`,
        ...style,
      }}
    >
      {accentBorder && (
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: 12,
            right: 12,
            height: 1,
            background: `linear-gradient(90deg, transparent, ${accent ?? theme.primary}, transparent)`,
          }}
        />
      )}
      {children}
    </div>
  );
}
