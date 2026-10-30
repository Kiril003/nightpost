import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { frameIndex, scrubProgress } from '../../lib/scrub';

function prefersStill(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia || !('IntersectionObserver' in window)) return true;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Кадри, що крутяться прокруткою. Постер видно завжди: полотно лише малює
 * поверх нього вже завантажені кадри, тож нічого не ховається до JS.
 */
export default function ScrubFrames({
  frames,
  poster = 0,
  width,
  height,
  alt,
  track = 260,
}: {
  frames: readonly string[];
  poster?: number;
  width: number;
  height: number;
  alt: string;
  track?: number;
}) {
  const [still] = useState(prefersStill);
  const root = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (still || frames.length < 2) return;
    const el = root.current;
    const cv = canvas.current;
    const ctx = cv?.getContext('2d');
    if (!el || !cv || !ctx) return;
    const imgs: (HTMLImageElement | null)[] = frames.map(() => null);
    let shown = -1;
    let raf = 0;
    let loading = false;

    const draw = () => {
      raf = 0;
      const r = el.getBoundingClientRect();
      let i = frameIndex(scrubProgress(r.top, r.height, window.innerHeight), frames.length);
      while (i > 0 && !imgs[i]) i--;
      const img = imgs[i];
      if (!img || i === shown) return;
      shown = i;
      ctx.drawImage(img, 0, 0, cv.width, cv.height);
    };
    const queue = () => {
      if (!raf) raf = requestAnimationFrame(draw);
    };
    const load = () => {
      if (loading) return;
      loading = true;
      frames.forEach((src, i) => {
        const img = new Image();
        img.decoding = 'async';
        img.src = src;
        img.decode().then(
          () => {
            imgs[i] = img;
            queue();
          },
          () => undefined,
        );
      });
    };

    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && load(), { rootMargin: '50% 0px' });
    io.observe(el);
    window.addEventListener('scroll', queue, { passive: true });
    window.addEventListener('resize', queue);
    return () => {
      io.disconnect();
      window.removeEventListener('scroll', queue);
      window.removeEventListener('resize', queue);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [still, frames]);

  const still1 = still || frames.length < 2;
  return (
    <div ref={root} className={still1 ? 'scrub still' : 'scrub'} style={still1 ? undefined : ({ '--track': `${track}vh` } as CSSProperties)}>
      <div className="scrub-stick">
        <div className="scrub-frame" style={{ aspectRatio: `${width} / ${height}` }}>
          <img src={frames[poster] ?? frames[0]} alt={alt} width={width} height={height} loading="lazy" decoding="async" />
          {!still1 && <canvas ref={canvas} width={width} height={height} aria-hidden />}
        </div>
      </div>
    </div>
  );
}
