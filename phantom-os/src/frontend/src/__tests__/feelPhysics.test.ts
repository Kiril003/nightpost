import { describe, it, expect, vi } from 'vitest';
import {
  LAYER,
  STRETCH,
  SNAP,
  PRESS,
  rb,
  stretchScale,
  velocity80,
  springAt,
  runSpring,
  rowRelease,
  sheetRelease,
  pageRelease,
} from '../feel/physics';

describe('feel/physics', () => {
  it('exports spring constants', () => {
    expect(LAYER).toEqual({ k: 400, zeta: 0.86 });
    expect(STRETCH).toEqual({ k: 608, zeta: 0.98 });
    expect(SNAP).toEqual({ k: 180, zeta: 0.75 });
    expect(PRESS).toEqual({ k: 700, zeta: 0.9 });
  });

  it('computes rb and stretchScale with 1.06 cap', () => {
    expect(rb(0, 100)).toBe(0);
    expect(rb(100, 0)).toBe(0);
    expect(rb(-50, 120)).toBeCloseTo(-rb(50, 120), 6);
    expect(stretchScale(200, 800)).toBeCloseTo(1.0302, 4);
    expect(stretchScale(1e6, 800)).toBeCloseTo(1.06, 6);
    expect(stretchScale(0, 800)).toBe(1);
    expect(stretchScale(100, 0)).toBe(1);
  });

  it('computes velocity80 over the last 80 ms window', () => {
    expect(
      velocity80([
        { t: 0, y: 0 },
        { t: 600, y: 300 },
        { t: 640, y: 360 },
      ]),
    ).toBeCloseTo(1.5, 6);
    expect(velocity80([{ t: 0, y: 0 }])).toBe(0);
    expect(
      velocity80([
        { t: 0, y: 0 },
        { t: 200, y: 100 },
      ]),
    ).toBe(0);
  });

  it('computes rowRelease, sheetRelease, and pageRelease', () => {
    expect(rowRelease(-30, 0.5, 160, 160)).toBe(0);
    expect(rowRelease(-60, 0, 160, 160)).toBe(-160);
    expect(rowRelease(25, 0.4, 120, 160)).toBe(120);
    expect(rowRelease(80, 0, 0, 160)).toBe(0);

    expect(sheetRelease(50, 0.6, 200)).toBe(200);
    expect(sheetRelease(50, 0.2, 200)).toBe(0);
    expect(sheetRelease(70, 0, 200)).toBe(200);

    expect(pageRelease(-40, -0.67, 412)).toBe(1);
    expect(pageRelease(-40, -0.1, 412)).toBe(0);
    expect(pageRelease(40, 0.67, 412)).toBe(-1);
    expect(pageRelease(100, 0, 412)).toBe(-1);
  });

  it('solves springAt and settles within 600 ms for SNAP', () => {
    const s = springAt(180, 0.75, 160, 0, 600);
    expect(Math.abs(s.x)).toBeLessThan(1);
    const crit = springAt(400, 1, 100, 0, 500);
    expect(Math.abs(crit.x)).toBeLessThan(1);
    const over = springAt(400, 1.2, 100, 0, 800);
    expect(Math.abs(over.x)).toBeLessThan(1);
  });

  it('runs spring frames to target and calls onDone once, and respects cancel', () => {
    let clock = 1000;
    let pending = null as FrameRequestCallback | null;
    let nextId = 1;
    const raf = (cb: FrameRequestCallback) => {
      pending = cb;
      return nextId++;
    };
    const caf = () => {
      pending = null;
    };
    const frames: number[] = [];
    const onDone = vi.fn();

    runSpring({
      k: LAYER.k,
      zeta: LAYER.zeta,
      from: 0,
      to: 200,
      v0: 0,
      onFrame: (x) => frames.push(x),
      onDone,
      raf,
      caf,
      now: () => clock,
    });

    let steps = 0;
    while (pending && steps < 200) {
      clock += 16;
      const cb = pending;
      pending = null;
      cb(clock);
      steps += 1;
    }

    expect(frames[frames.length - 1]).toBe(200);
    expect(onDone).toHaveBeenCalledTimes(1);

    const onDone2 = vi.fn();
    const frames2: number[] = [];
    const cancel = runSpring({
      k: LAYER.k,
      zeta: LAYER.zeta,
      from: 0,
      to: 200,
      v0: 0,
      onFrame: (x) => frames2.push(x),
      onDone: onDone2,
      raf,
      caf,
      now: () => clock,
    });
    clock += 16;
    if (pending) {
      const cb = pending;
      pending = null;
      cb(clock);
    }
    cancel();
    expect(pending).toBeNull();
    expect(onDone2).not.toHaveBeenCalled();
  });
});
