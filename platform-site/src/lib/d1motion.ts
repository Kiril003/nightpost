const SPRINGS: Record<string, [number, number]> = {
  press: [700, 0.9],
  layer: [400, 0.86],
  flight: [260, 0.78],
  snap: [180, 0.75],
};

export function springLinear(k: number, z: number): [string, number] {
  const w = Math.sqrt(k);
  const wd = w * Math.sqrt(1 - z * z);
  const x = (t: number) => 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + (z * w / wd) * Math.sin(wd * t));
  let T = 0;
  for (let t = 0; t < 3; t += 0.004) {
    if (Math.abs(1 - x(t)) > 0.002) T = t;
  }
  const n = 36;
  const pts: number[] = [];
  for (let i = 0; i <= n; i++) pts.push(+x((T * i) / n).toFixed(4));
  pts[n] = 1;
  return [`linear(${pts.join(', ')})`, Math.round(T * 1000)];
}

export function scrubProgress(top: number, height: number, vh: number, pin = false): number {
  const span = pin ? height - vh : height + vh * 0.4;
  const from = pin ? 0 : vh * 0.6;
  return Math.min(1, Math.max(0, (from - top) / Math.max(1, span)));
}

export function stepIndex(p: number, count: number): number {
  if (count <= 1) return 0;
  const clamped = Math.min(1, Math.max(0, p));
  return Math.min(count - 1, Math.floor(clamped * count * 0.999));
}

export function shouldArm(top: number, vh: number, automated: boolean): boolean {
  return !automated && top >= vh;
}

export function reducedMotion(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    const q = new URLSearchParams(window.location.search);
    if (q.get('motion') === 'reduce') return true;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return true;
    if (document.documentElement.classList.contains('rm')) return true;
  } catch {
    return false;
  }
  return false;
}

export function bindD1Motion(root: HTMLElement | null): () => void {
  if (!root || typeof window === 'undefined') return () => {};

  const q = new URLSearchParams(window.location.search);
  const k = q.get('slow') ? 2 : 1;
  for (const [name, [s, z]] of Object.entries(SPRINGS)) {
    const [easing, ms] = springLinear(s, z);
    root.style.setProperty(`--sp-${name}`, easing);
    root.style.setProperty(`--sp-${name}-ms`, `${ms * k}ms`);
  }
  root.style.setProperty('--k', String(k));

  const revealNodes = Array.from(root.querySelectorAll<HTMLElement>('[data-r]'));
  const scrubNodes = Array.from(root.querySelectorAll<HTMLElement>('[data-scrub]'));

  let io: IntersectionObserver | null = null;
  let raf = 0;

  const applyStep = (el: HTMLElement, idx: number) => {
    if (el.dataset.i === String(idx)) return;
    el.dataset.i = String(idx);
    el.querySelectorAll<HTMLElement>('[data-step]').forEach((s, j) => s.classList.toggle('on', j === idx));
    el.querySelectorAll<HTMLElement>('[data-scr]').forEach((s, j) => {
      s.classList.toggle('on', j === idx);
      s.classList.toggle('past', j < idx);
    });
  };

  const tick = () => {
    raf = 0;
    const isReduced = reducedMotion();
    root.classList.toggle('rm', isReduced);
    if (isReduced) {
      revealNodes.forEach((el) => el.classList.remove('rv'));
      scrubNodes.forEach((el) => {
        el.style.setProperty('--p', '0');
        if (el.querySelector('[data-step]')) applyStep(el, 0);
      });
      return;
    }
    const vh = window.innerHeight;
    for (const el of scrubNodes) {
      const r = el.getBoundingClientRect();
      const p = scrubProgress(r.top, r.height, vh, el.dataset.scrub === 'pin');
      el.style.setProperty('--p', p.toFixed(4));
      const steps = el.querySelectorAll('[data-step]');
      if (steps.length) {
        applyStep(el, stepIndex(p, steps.length));
      }
    }
  };

  const onScroll = () => {
    if (!raf) raf = window.requestAnimationFrame(tick);
  };

  if (reducedMotion() || !('IntersectionObserver' in window)) {
    root.classList.toggle('rm', reducedMotion());
  } else {
    const vh = window.innerHeight;
    const automated = navigator.webdriver === true;
    io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add('in');
            io?.unobserve(entry.target);
          }
        }
      },
      { threshold: 0.12, rootMargin: '0px 0px -6% 0px' },
    );
    for (const el of revealNodes) {
      if (el.classList.contains('in')) continue;
      if (!shouldArm(el.getBoundingClientRect().top, vh, automated)) {
        el.classList.remove('rv');
        continue;
      }
      el.classList.add('rv');
      io.observe(el);
    }
  }

  const mql = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  mql?.addEventListener?.('change', tick);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', tick);
  tick();

  return () => {
    if (raf) window.cancelAnimationFrame(raf);
    io?.disconnect();
    mql?.removeEventListener?.('change', tick);
    window.removeEventListener('scroll', onScroll);
    window.removeEventListener('resize', tick);
  };
}
