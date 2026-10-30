/**
 * Розмови в оболонці ПК (27.09): друга навігаційна смуга всередині
 * месенджера («до PHANTOM», безіменні теки, шестерня) дублювала док і чипи
 * над списком. Редизайн месенджера (cloud/beta) прибрав смугу взагалі —
 * в оболонці ПК її тим паче не має бути.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { InDeskContext } from '../components/shell/inDesk';

vi.mock('../services/api', async (orig) => ({ ...(await orig<object>()) }));

async function mount(inDesk: boolean) {
  const { default: MessengerLayout } = await import('../layouts/MessengerLayout');
  render(
    <MemoryRouter initialEntries={['/messenger']}>
      <InDeskContext.Provider value={inDesk}>
        <MessengerLayout />
      </InDeskContext.Provider>
    </MemoryRouter>,
  );
}

describe('Розмови в оболонці ПК', () => {
  it('без смуги-дубліката доку', async () => {
    await mount(true);
    await screen.findByTestId('chat-list');
    expect(screen.queryByTitle('Повернутися до Nightpost')).toBeNull();
    expect(screen.queryByTitle('Налаштування застосунку')).toBeNull();
  });

});
