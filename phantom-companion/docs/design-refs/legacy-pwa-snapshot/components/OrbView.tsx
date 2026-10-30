import { useTick } from '@/hooks/useTick';
import { useReducedMotion } from '@/hooks/useReducedMotion';
import { hexToRgb } from '@/utils/color';
import type { PhantomTheme } from '@/types/theme';

interface OrbViewProps {
  accent: string;
  theme: PhantomTheme;
  motionScale?: number;
  audioLevel?: number;
  size?: number;
  ringCount?: number;
  intensity?: number;
}

/** Smoother breathing curve than raw sin: easeInOutQuad on a [0,1] phase. */
function eased(phase: number): number {
  const t = (phase + 1) / 2; // 0..1
  const e = t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
  return e * 2 - 1; // -1..1
}

export function OrbView({
  accent,
  theme,
  motionScale = 1,
  audioLevel = 0,
  size = 240,
  ringCount = 1,
  intensity = 1,
}: OrbViewProps) {
  const reduced = useReducedMotion();
  const tick = useTick(!reduced);
  const period = 2 / motionScale;
  const breath = reduced ? 1 : 1 + 0.05 * eased(Math.sin((tick / period) * Math.PI * 2));
  const audioPulse = 1 + audioLevel * 0.18;
  const [r, g, b] = hexToRgb(accent);

  return (
    <div
      style={{
        position: 'relative',
        width: size,
        height: size,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      {!reduced &&
        Array.from({ length: ringCount }).map((_, i) => {
          const phase = (tick / (1.2 / motionScale)) * Math.PI * 2 - i * 0.8;
          const ringScale = 1 + 0.15 * (0.5 + 0.5 * eased(Math.sin(phase))) + audioLevel * 0.1;
          return (
            <div
              key={i}
              style={{
                position: 'absolute',
                width: size * 0.95,
                height: size * 0.95,
                borderRadius: '50%',
                border: `1px solid rgba(${r},${g},${b},${0.35 - i * 0.1})`,
                transform: `scale(${ringScale})`,
                opacity: 1 - i * 0.3,
                willChange: 'transform',
              }}
            />
          );
        })}
      <div
        style={{
          width: size,
          height: size,
          borderRadius: '50%',
          transform: `scale(${breath * audioPulse})`,
          transition: 'transform 50ms linear',
          willChange: 'transform',
          background: `
            radial-gradient(circle at 35% 30%, rgba(255,255,255,${theme.isDark ? 0.35 : 0.65}) 0%, rgba(255,255,255,0) 35%),
            radial-gradient(circle at 50% 55%, rgba(${r},${g},${b},0.95) 0%, rgba(${r},${g},${b},0.55) 35%, rgba(${r},${g},${b},0.18) 65%, rgba(${r},${g},${b},0.0) 80%)
          `,
          filter: `drop-shadow(0 0 ${30 * intensity}px rgba(${r},${g},${b},${theme.isDark ? 0.6 : 0.45}))`,
          position: 'relative',
        }}
      >
        <div
          style={{
            position: 'absolute',
            inset: '20%',
            borderRadius: '50%',
            background: `radial-gradient(circle at 40% 35%, rgba(255,255,255,${theme.isDark ? 0.5 : 0.85}) 0%, rgba(${r},${g},${b},0.4) 40%, rgba(${r},${g},${b},0.0) 75%)`,
            mixBlendMode: theme.isDark ? 'screen' : 'normal',
          }}
        />
      </div>
    </div>
  );
}
