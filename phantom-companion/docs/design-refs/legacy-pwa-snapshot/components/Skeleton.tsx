import type { CSSProperties } from 'react';
import type { PhantomTheme } from '@/types/theme';

interface SkeletonProps {
  theme: PhantomTheme;
  width?: number | string;
  height?: number | string;
  radius?: number;
  style?: CSSProperties;
}

export function Skeleton({ theme, width = '100%', height = 14, radius = 6, style }: SkeletonProps) {
  return (
    <div
      style={{
        width,
        height,
        borderRadius: radius,
        background: `linear-gradient(90deg, ${theme.glassPanel} 0%, ${theme.glassCard} 50%, ${theme.glassPanel} 100%)`,
        backgroundSize: '200% 100%',
        animation: 'phantom-shimmer 1.6s ease-in-out infinite',
        ...style,
      }}
      aria-hidden
    />
  );
}
