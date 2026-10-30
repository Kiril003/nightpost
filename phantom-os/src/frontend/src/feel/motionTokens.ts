import { LAYER, PRESS, SNAP, STRETCH, prefersReduced, springAt } from './physics';

export type Primitive = 'stretch' | 'settle' | 'fill' | 'fold' | 'travel' | 'stamp';

export interface MotionSpec {
  primitive: Primitive;
  end: boolean;
  ms: number;
  easing: string;
  prop: 'transform' | 'clip-path' | 'opacity';
}

export interface MotionEnv {
  reduced: boolean;
  lowPower?: boolean;
  saveData?: boolean;
}

export const PRIMITIVES: Record<
  Primitive,
  { spring: { k: number; zeta: number }; ms: number; prop: 'transform' | 'clip-path' | 'opacity' }
> = {
  stretch: { spring: STRETCH, ms: 280, prop: 'transform' },
  settle: { spring: SNAP, ms: 360, prop: 'transform' },
  fill: { spring: SNAP, ms: 420, prop: 'clip-path' },
  fold: { spring: LAYER, ms: 320, prop: 'transform' },
  travel: { spring: LAYER, ms: 400, prop: 'transform' },
  stamp: { spring: PRESS, ms: 180, prop: 'transform' },
};

export function cssLinear(
  spring: { k: number; zeta: number },
  ms: number,
  samples = 24,
): string {
  const n = Math.max(2, samples);
  const stops: number[] = [];
  for (let i = 0; i < n; i += 1) {
    const t = (ms * i) / (n - 1);
    const p = 1 - springAt(spring.k, spring.zeta, 1, 0, t).x;
    const rounded = Math.round(p * 1000) / 1000;
    stops.push(Object.is(rounded, -0) ? 0 : rounded);
  }
  return `linear(${stops.join(', ')})`;
}

export function policy(env: MotionEnv): Record<Primitive, MotionSpec> {
  const quiet = Boolean(env.reduced || env.lowPower || env.saveData);
  const out = {} as Record<Primitive, MotionSpec>;
  (Object.keys(PRIMITIVES) as Primitive[]).forEach((primitive) => {
    const def = PRIMITIVES[primitive];
    out[primitive] = quiet
      ? { primitive, end: true, ms: 120, easing: 'linear', prop: 'opacity' }
      : {
          primitive,
          end: false,
          ms: def.ms,
          easing: cssLinear(def.spring, def.ms),
          prop: def.prop,
        };
  });
  return out;
}

interface NavigatorWithConnection {
  connection?: { saveData?: boolean };
}

export function readEnv(): MotionEnv {
  const saveData =
    typeof navigator !== 'undefined'
      ? Boolean((navigator as unknown as NavigatorWithConnection).connection?.saveData)
      : false;
  // Синхронного читання батареї немає, тому lowPower лишається false.
  return {
    reduced: prefersReduced(),
    lowPower: false,
    saveData,
  };
}

export const motionFor = (p: Primitive, env: MotionEnv = readEnv()): MotionSpec => policy(env)[p];
