import React, { useEffect, useRef } from 'react';
import { glyphForReaction } from './reactionGlyphs';

const SHAKE = new Set(['laugh', 'no', 'cant', 'sorry']);

const glyphCss = `
.np-gl{display:block;overflow:visible;--g-tone:var(--ph-overlay);--g-ink:var(--ph-text);--g-amber:var(--ph-accent);--g-amber-line:var(--ph-accent-line)}
.np-gl .t{fill:var(--g-tone)}
.np-gl .ti{fill:var(--g-tone);stroke:var(--g-ink);stroke-width:var(--sw,1.75);stroke-linecap:round;stroke-linejoin:round}
.np-gl .i{fill:none;stroke:var(--g-ink);stroke-width:var(--sw,1.75);stroke-linecap:round;stroke-linejoin:round}
.np-gl .if{fill:var(--g-ink)}
.np-gl .a{fill:var(--g-amber)}
.np-gl .ai{fill:var(--g-amber);stroke:var(--g-ink);stroke-width:var(--sw,1.75);stroke-linecap:round;stroke-linejoin:round}
.np-gl .as{fill:none;stroke:var(--g-amber-line);stroke-width:var(--sw,1.75);stroke-linecap:round;stroke-linejoin:round}
.np-gl .dots{fill:none;stroke:var(--g-amber-line);stroke-width:2;stroke-linecap:round;stroke-dasharray:0 3.1}
.np-gl .wedge{fill:none;stroke:var(--g-amber);stroke-width:5.5;stroke-dasharray:7.2 17.28}
.np-gl [pathLength="1"]{stroke-dasharray:1 2}
`;

let injected = false;
function ensureCss() {
  if (injected || typeof document === 'undefined') return;
  injected = true;
  const el = document.createElement('style');
  el.setAttribute('data-np-glyphs', '');
  el.textContent = glyphCss;
  document.head.appendChild(el);
}

interface Props {
  /** Запасний смайл із дроту або `np:<id>`. */
  value: string;
  size?: number;
  /** Програти рух знака один раз (≤600 мс, лише transform/opacity). */
  play?: boolean;
  className?: string;
}

function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

export function hasGlyph(value: string): boolean {
  return !!glyphForReaction(value);
}

export const ReactionGlyph: React.FC<Props> = ({ value, size = 24, play = false, className }) => {
  const g = glyphForReaction(value);
  const ref = useRef<SVGSVGElement>(null);
  ensureCss();

  useEffect(() => {
    const el = ref.current;
    if (!play || prefersReducedMotion() || !g || !el || typeof el.animate !== 'function') return;
    const dur = Math.min(g.ms, 600);
    const frames: Keyframe[] = SHAKE.has(g.id)
      ? [{ transform: 'translateX(0)' }, { transform: 'translateX(-2px)' }, { transform: 'translateX(2px)' }, { transform: 'translateX(-1px)' }, { transform: 'translateX(0)' }]
      : [{ transform: 'scale(.6)', opacity: 0.4 }, { transform: 'scale(1.18)', opacity: 1, offset: 0.6 }, { transform: 'scale(1)', opacity: 1 }];
    const a = el.animate(frames, { duration: dur, easing: 'cubic-bezier(.2,0,0,1)' });
    return () => a.cancel();
  }, [play, g]);

  if (!g) return null;
  const sw = size <= 16 ? 2.1 : size <= 20 ? 1.9 : size <= 24 ? 1.75 : size <= 32 ? 1.6 : 1.5;
  return (
    <svg
      ref={ref}
      className={`np-gl ${className ?? ''}`}
      data-glyph={g.id}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      role="img"
      aria-label={g.en}
      style={{ ['--sw' as string]: sw }}
      dangerouslySetInnerHTML={{ __html: `<g data-p="all">${g.svg}</g>` }}
    />
  );
};
