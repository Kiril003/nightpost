import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useSettingsStore, LANGUAGE_SETTING_KEY } from '../stores/settingsStore';
import { TilesDownBanner } from '../components/map/TilesDownBanner';
import tacticalMapSrc from '../components/map/TacticalMap.tsx?raw';

afterEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('R0-12 tiles banner is a label, not a sentence', () => {
  it('uk: «Мапа без зв’язку · Спробувати ще»', () => {
    const onRetry = vi.fn();
    render(<TilesDownBanner reason="нема зв’язку з сервером тайлів" onRetry={onRetry} />);
    const banner = screen.getByTestId('map-tiles-down');
    expect(banner.textContent).toBe('Мапа без зв’язку·Спробувати ще');
    expect(banner.getAttribute('title')).toBe('нема зв’язку з сервером тайлів');
    fireEvent.click(screen.getByRole('button', { name: 'Спробувати ще' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('en: "Map offline · Try again"', () => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
    render(<TilesDownBanner reason="x" onRetry={() => {}} />);
    expect(screen.getByTestId('map-tiles-down').textContent).toBe('Map offline·Try again');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
  });

  it('the map renders the banner component, not the old sentence', () => {
    expect(tacticalMapSrc).toContain('<TilesDownBanner');
    expect(tacticalMapSrc).not.toContain('Детальна мапа недосяжна');
  });
});
