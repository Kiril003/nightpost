/**
 * Мапа ПК без театру (закони власника: немає даних — немає плитки; кожна
 * кнопка кудись веде). Перепис 26.09 знайшов на рейці інструментів:
 *   - «Привид» — панель із вшитими «Safe House Alpha», «Sector 7 Transit»
 *     і «Hardware TPM Verified», жодних даних;
 *   - «Аналіз» — профіль висот без шляху: OmniMap не передавав `selectedPath`;
 *   - «Зона» — малювання геозони без `onSave`: зона нікуди не зберігалась;
 *   - повзунок «Машини часу» з `onDateChange`, який не читав ніхто;
 *   - «Завантажити область» в Офлайн — кнопка без обробника.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useSettingsStore } from '../stores/settingsStore';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => {}, send: () => {}, connect: () => {}, disconnect: () => {} },
}));
vi.mock('../components/map/TacticalMap', () => ({
  TacticalMap: () => <div data-testid="tactical-map" />,
}));

function payloadFor(url: string): unknown {
  if (url.includes('/map/layers')) return { layers: [], total: 0, categories: [], load_errors: [] };
  if (url.includes('/map/position_sources')) return { phone: null, aps: [], paired_devices: 0 };
  if (url.includes('/map/wifi_scan')) return { aps: [], known: 0 };
  if (url.includes('/map/nearby')) return { remembered: [], osm: [], pois: [] };
  if (url.includes('/location_history')) return { entries: [] };
  if (url.includes('/offline/regions')) return { regions: [], used_bytes: 0, capacity_bytes: 0 };
  return {};
}

beforeEach(() => {
  // Ці тести — про інструменти фахівця; мапа людини — у mapPerson.test.
  useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: true } }));
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
    ok: true, status: 200, json: async () => payloadFor(String(url)),
  })));
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('мапа ПК без театру', () => {
  it('на рейці немає дверей, що нікуди не ведуть', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await screen.findByRole('button', { name: /Прокласти маршрут/ });
    for (const dead of [/^Привид$/, /^Аналіз$/, /^Зона$/, /Малювання зон/]) {
      expect(screen.queryByRole('button', { name: dead }), String(dead)).toBeNull();
    }
    const text = document.body.textContent ?? '';
    for (const fake of ['Safe House', 'Sector 7', 'GHOST Protocol', 'TPM']) {
      expect(text, fake).not.toContain(fake);
    }
  });

  it('«Час» відкриває історію місць без мертвого повзунка', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    fireEvent.click(await screen.findByRole('button', { name: /Машина часу/ }));
    expect(screen.queryByTestId('time-machine-slider')).toBeNull();
  });

  it('Офлайн: без кнопки завантаження, що нічого не робить; без техніки', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    fireEvent.click(await screen.findByRole('button', { name: /Мапа без інтернету/ }));
    await screen.findByText("На цьому комп'ютері");
    expect(screen.queryByText(/Завантажити область/)).toBeNull();
    expect(document.body.textContent).not.toContain('Radxa');
  });
});
