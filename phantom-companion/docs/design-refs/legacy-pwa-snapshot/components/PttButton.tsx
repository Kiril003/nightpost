import { useState } from 'react';
import { useTick } from '@/hooks/useTick';
import { hexToRgb } from '@/utils/color';
import type { PhantomTheme } from '@/types/theme';

export type PttState = 'Idle' | 'Capturing' | 'AwaitingFinal' | 'Speaking';

interface PttButtonProps {
  state?: PttState;
  audioLevel?: number;
  accent: string;
  theme: PhantomTheme;
  onPointerDown?: () => void;
  onPointerUp?: () => void;
}

export function PttButton({
  state = 'Idle',
  audioLevel = 0,
  accent,
  theme,
  onPointerDown,
  onPointerUp,
}: PttButtonProps) {
  const tick = useTick();
  const [pressed, setPressed] = useState(false);
  const [r, g, b] = hexToRgb(accent);

  const ringScale = state === 'Capturing' ? 1 + audioLevel * 0.4 : pressed ? 1.05 : 1;
  const pulseAlpha =
    state === 'AwaitingFinal' ? 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(tick * (Math.PI * 2 / 0.6))) : 1;
  const rotation = state === 'Speaking' ? (tick / 4) * 360 : 0;

  return (
    <div
      role="button"
      aria-label="Push to talk"
      onPointerDown={() => {
        setPressed(true);
        onPointerDown?.();
      }}
      onPointerUp={() => {
        setPressed(false);
        onPointerUp?.();
      }}
      onPointerLeave={() => setPressed(false)}
      onPointerCancel={() => setPressed(false)}
      tabIndex={0}
      style={{
        position: 'relative',
        width: 96,
        height: 96,
        touchAction: 'manipulation',
        outline: 'none',
        userSelect: 'none',
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: -8,
          borderRadius: '50%',
          border: `2px solid rgba(${r},${g},${b},${pressed ? 0.55 : 0.35})`,
          transform: `scale(${ringScale})`,
          transition: 'transform 60ms linear, border-color 120ms ease',
        }}
      />
      <div
        style={{
          position: 'absolute',
          inset: -16,
          borderRadius: '50%',
          border: `1px dashed rgba(${r},${g},${b},${pressed ? 0.4 : 0.25})`,
          transform: `rotate(${rotation}deg)`,
        }}
      />
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, rgba(255,255,255,${theme.isDark ? 0.4 : 0.7}), rgba(${r},${g},${b},0.95) 60%, rgba(${r},${g},${b},0.7))`,
          boxShadow: `0 0 ${pressed ? 36 : 28}px rgba(${r},${g},${b},${pressed ? 0.7 : 0.55}), inset 0 -8px 18px rgba(0,0,0,0.18)`,
          opacity: pulseAlpha,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transform: `scale(${pressed ? 0.96 : 1})`,
          transition: 'transform 60ms ease, box-shadow 120ms ease',
        }}
      >
        <div
          style={{
            width: 18,
            height: 18,
            borderRadius: 4,
            background: theme.isDark ? '#1c1408' : 'rgba(255,255,255,0.92)',
            boxShadow: 'inset 0 0 4px rgba(0,0,0,0.2)',
          }}
        />
      </div>
    </div>
  );
}
