// Live audio waveform — renders a rolling buffer of the most recent
// audio-level samples as a bar chart on a canvas. Samples come from
// useVoiceStore.audioLevel (0..1, set by voiceController on each
// AnalyserNode tick). When PTT is idle, the buffer decays to flat.
//
// We keep this on a canvas instead of SVG so it can update at 30fps
// without slamming React's reconciler. Buffer size scales with width
// so a 360dp phone gets ~60 bars; a tablet gets more.

import { useEffect, useRef } from 'react';
import { useVoiceStore } from '@/state/voiceStore';
import type { PhantomTheme } from '@/types/theme';
import { hexToRgb } from '@/utils/color';

interface WaveformProps {
  theme: PhantomTheme;
  accent: string;
  /** Pixel width — defaults to 100% of container via auto-sizing. */
  width?: number;
  height?: number;
  /** Visual bar count. Auto-derived from width when omitted. */
  bars?: number;
  /** When true, samples decay even while pushing — used for a softer
   *  idle look on the Voice screen footer. */
  alwaysDecay?: boolean;
}

export function Waveform({
  theme,
  accent,
  height = 36,
  bars,
  alwaysDecay = false,
}: WaveformProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const bufferRef = useRef<number[]>([]);
  const rafRef = useRef<number | null>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const wrapper = wrapperRef.current;
    if (!canvas || !wrapper) return;

    const dpr = typeof window === 'undefined' ? 1 : window.devicePixelRatio || 1;
    const [r, g, b] = hexToRgb(accent);
    const idleColor = theme.isDark ? `rgba(${r},${g},${b},0.18)` : `rgba(${r},${g},${b},0.22)`;
    const liveColor = `rgba(${r},${g},${b},0.92)`;

    function sizeCanvas() {
      if (!canvas || !wrapper) return;
      const w = wrapper.clientWidth || 320;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(height * dpr);
      canvas.style.width = `${w}px`;
      canvas.style.height = `${height}px`;
    }
    sizeCanvas();
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(sizeCanvas) : null;
    ro?.observe(wrapper);

    function tick() {
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      const w = canvas.width;
      const h = canvas.height;
      const targetBars = bars ?? Math.max(20, Math.floor(w / dpr / 5));

      // Sample the latest level once per frame and push into the ring.
      const level = useVoiceStore.getState().audioLevel;
      const decayed = alwaysDecay ? level * 0.9 : level;
      bufferRef.current.push(decayed);
      if (bufferRef.current.length > targetBars) {
        bufferRef.current = bufferRef.current.slice(-targetBars);
      }

      ctx.clearRect(0, 0, w, h);
      const slot = w / targetBars;
      const barWidth = Math.max(2 * dpr, slot * 0.5);
      const buf = bufferRef.current;

      for (let i = 0; i < targetBars; i++) {
        const sample = buf[Math.max(0, buf.length - targetBars + i)] ?? 0;
        const live = sample > 0.04;
        const v = Math.max(0.05, Math.min(1, sample));
        const barH = Math.max(1.5 * dpr, v * h * 0.92);
        const x = i * slot + (slot - barWidth) / 2;
        const y = (h - barH) / 2;
        ctx.fillStyle = live ? liveColor : idleColor;
        const radius = Math.min(barH / 2, barWidth / 2);
        const ctxAny = ctx as CanvasRenderingContext2D & {
          roundRect?: (x: number, y: number, w: number, h: number, r: number) => void;
        };
        if (typeof ctxAny.roundRect === 'function') {
          ctxAny.beginPath();
          ctxAny.roundRect(x, y, barWidth, barH, radius);
          ctxAny.fill();
        } else {
          ctxAny.fillRect(x, y, barWidth, barH);
        }
      }

      rafRef.current = requestAnimationFrame(tick);
    }
    rafRef.current = requestAnimationFrame(tick);

    return () => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      ro?.disconnect();
    };
  }, [theme.isDark, accent, height, bars, alwaysDecay]);

  return (
    <div ref={wrapperRef} style={{ width: '100%', height, display: 'block' }}>
      <canvas ref={canvasRef} aria-hidden style={{ display: 'block' }} />
    </div>
  );
}
