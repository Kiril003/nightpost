import type { SolanaPaymentStatus } from '../types/messenger';

export type PayStepState = 'draft' | 'signing' | 'sent' | 'stamped' | 'refused' | 'unknown';

export interface PayStep {
  state: PayStepState;
  notch: 0 | 1 | 2 | 3;
  stamp: boolean;
}

export const NOTCHES = 3;

export function sheetStep(o: { busy: boolean }): PayStep {
  return o.busy
    ? { state: 'signing', notch: 1, stamp: false }
    : { state: 'draft', notch: 0, stamp: false };
}

export function chainStep(status: SolanaPaymentStatus | undefined): PayStep {
  switch (status) {
    case 'pending':
      return { state: 'sent', notch: 2, stamp: false };
    case 'confirmed':
      return { state: 'stamped', notch: 3, stamp: true };
    case 'failed':
    case 'mismatch':
      return { state: 'refused', notch: 2, stamp: false };
    default:
      return { state: 'unknown', notch: 2, stamp: false };
  }
}
