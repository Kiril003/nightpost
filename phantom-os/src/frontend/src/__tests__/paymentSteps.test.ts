import { afterEach, describe, expect, it, vi } from 'vitest';
import { NOTCHES, chainStep, sheetStep } from '../feel/paymentSteps';
import type { SolanaPaymentStatus } from '../types/messenger';

const ALL: (SolanaPaymentStatus | undefined)[] = ['pending', 'confirmed', 'failed', 'mismatch', 'unknown', undefined];

describe('paymentSteps (motion-r2b-1)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('stamps only on the bridge confirmed status', () => {
    expect(NOTCHES).toBe(3);
    for (const s of ALL) expect(chainStep(s).stamp).toBe(s === 'confirmed');
    expect(chainStep('confirmed')).toEqual({ state: 'stamped', notch: 3, stamp: true });
  });

  it('moves forward pending -> confirmed and marks refusals at notch 2', () => {
    expect(chainStep('pending')).toEqual({ state: 'sent', notch: 2, stamp: false });
    expect(chainStep('confirmed').notch).toBeGreaterThan(chainStep('pending').notch);
    expect(chainStep('failed')).toEqual({ state: 'refused', notch: 2, stamp: false });
    expect(chainStep('mismatch')).toEqual({ state: 'refused', notch: 2, stamp: false });
    expect(chainStep('unknown')).toEqual({ state: 'unknown', notch: 2, stamp: false });
    expect(chainStep(undefined)).toEqual({ state: 'unknown', notch: 2, stamp: false });
  });

  it('sheet shows signing only while the bridge call is open', () => {
    expect(sheetStep({ busy: true })).toEqual({ state: 'signing', notch: 1, stamp: false });
    expect(sheetStep({ busy: false })).toEqual({ state: 'draft', notch: 0, stamp: false });
  });

  it('is a function of state only, never of time', () => {
    expect(chainStep.length).toBe(1);
    expect(sheetStep.length).toBe(1);
    vi.useFakeTimers();
    const before = ALL.map(chainStep);
    vi.advanceTimersByTime(600_000);
    expect(ALL.map(chainStep)).toEqual(before);
  });
});
