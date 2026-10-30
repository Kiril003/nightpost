import { describe, expect, it } from 'vitest';
import { SNAP, STRETCH, springAt } from '../feel/physics';
import { PRIMITIVES, cssLinear, policy, readEnv, type Primitive } from '../feel/motionTokens';

function parseLinearStops(easing: string): number[] {
  expect(easing.startsWith('linear(')).toBe(true);
  expect(easing.endsWith(')')).toBe(true);
  return easing
    .slice('linear('.length, -1)
    .split(',')
    .map((s) => Number(s.trim()));
}

describe('feel/motionTokens', () => {
  it('defines the exact six primitives', () => {
    const keys = Object.keys(PRIMITIVES) as Primitive[];
    expect(keys).toEqual(['stretch', 'settle', 'fill', 'fold', 'travel', 'stamp']);
  });

  it('samples cssLinear(SNAP, 360) into 24 stops matching springAt and settling to 1', () => {
    const easing = cssLinear(SNAP, 360);
    const stops = parseLinearStops(easing);
    expect(stops).toHaveLength(24);
    expect(stops[0]).toBe(0);
    for (let i = 0; i < 24; i += 1) {
      const expected = 1 - springAt(SNAP.k, SNAP.zeta, 1, 0, (360 * i) / 23).x;
      expect(Math.abs(stops[i] - expected)).toBeLessThan(0.01);
    }
    expect(stops[stops.length - 1]).toBeCloseTo(1, 1);

    const stretchStops = parseLinearStops(cssLinear(STRETCH, 280));
    expect(stretchStops[0]).toBe(0);
    expect(Math.abs(stretchStops[stretchStops.length - 1] - 1)).toBeLessThan(0.01);
  });

  it('switches all six primitives to end-state opacity 120ms when reduced, lowPower, or saveData is active', () => {
    const envs = [
      { reduced: true },
      { reduced: false, lowPower: true },
      { reduced: false, saveData: true },
    ];
    for (const env of envs) {
      const p = policy(env);
      for (const key of Object.keys(PRIMITIVES) as Primitive[]) {
        expect(p[key]).toEqual({
          primitive: key,
          end: true,
          ms: 120,
          easing: 'linear',
          prop: 'opacity',
        });
      }
    }
  });

  it('returns spring durations and props when motion is allowed', () => {
    const p = policy({ reduced: false });
    expect(p.stretch.end).toBe(false);
    expect(p.stretch.ms).toBe(280);
    expect(p.stretch.prop).toBe('transform');
    expect(p.stretch.easing.startsWith('linear(')).toBe(true);

    expect(p.settle.end).toBe(false);
    expect(p.settle.ms).toBe(360);
    expect(p.settle.prop).toBe('transform');

    expect(p.fill.end).toBe(false);
    expect(p.fill.ms).toBe(420);
    expect(p.fill.prop).toBe('clip-path');

    expect(p.fold.end).toBe(false);
    expect(p.fold.ms).toBe(320);
    expect(p.fold.prop).toBe('transform');

    expect(p.travel.end).toBe(false);
    expect(p.travel.ms).toBe(400);
    expect(p.travel.prop).toBe('transform');

    expect(p.stamp.end).toBe(false);
    expect(p.stamp.ms).toBe(180);
    expect(p.stamp.prop).toBe('transform');
  });

  it('reads environment safely', () => {
    const env = readEnv();
    expect(typeof env.reduced).toBe('boolean');
    expect(env.lowPower).toBe(false);
    expect(typeof env.saveData).toBe('boolean');
  });

  it('reads saveData from navigator.connection', () => {
    Object.defineProperty(navigator, 'connection', { configurable: true, value: { saveData: true } });
    try {
      expect(readEnv().saveData).toBe(true);
      expect(policy(readEnv()).settle.end).toBe(true);
    } finally {
      delete (navigator as unknown as { connection?: unknown }).connection;
    }
  });
});
