/**
 * Мапа людини (27.09). Огляд живого застосунку: мапа була пультом
 * оператора — 8 шарів (мережі Wi-Fi, радіотепло, розвідка, реєстр), 4
 * інструменти, чипи «Спрощена графіка», «Пн · 0°», «Приймача немає»,
 * «стан невідомий · дані не надходили». Типово тепер — людське; фахове —
 * за налаштуванням ui_map_expert.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useSettingsStore } from '../stores/settingsStore';

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) })));
  useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: false } }));
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const railNames = () =>
  screen
    .getAllByRole('button')
    // Порожній шар каже «Мої місця — записів немає»: беремо саму назву.
    .map((b) => (b.getAttribute('aria-label') ?? '').split(' — ')[0])
    .filter(Boolean);

describe('мапа людини', () => {
  it('шари — про людину, інструменти — маршрут і офлайн, без техніки', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    const names = railNames();
    for (const want of ['Люди поруч', 'Мої місця', 'Спогади', 'Прокласти маршрут', 'Мапа без інтернету']) {
      expect(names, want).toContain(want);
    }
    for (const gone of ['Мережі', 'Теплокарта', 'Розвідка', 'Бібліотека шарів', 'Машина часу', 'Основа']) {
      expect(names, gone).not.toContain(gone);
    }
    expect(screen.queryByTestId('air-raid-unknown')).toBeNull();
    expect(screen.getByPlaceholderText('Знайти місце')).toBeTruthy();
  });

  it('фахівець бачить свої інструменти', async () => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: true } }));
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    const names = railNames();
    expect(names).toContain('Основа');
    expect(names).toContain('Бібліотека шарів');
  });
});
