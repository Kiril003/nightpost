import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { PayMoneySheet } from '../components/messenger/solana/PayMoneySheet';

describe('переказ з розмови не каже людині «devnet»', () => {
  it('тестова мережа — словами «тестові гроші»', () => {
    render(<PayMoneySheet peer={{ id: 'p', name: 'Olena' }} cluster="devnet" onClose={() => {}} />);
    const line = document.querySelector('[data-testid="solana-pay-cluster"]');
    expect(line).not.toBeNull();
    expect(line!.textContent).not.toMatch(/devnet/i);
    expect(document.body.textContent ?? '').not.toMatch(/devnet/i);
  });
});
