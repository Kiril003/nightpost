import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useSettingsStore } from '../stores/settingsStore';
import { translate } from '../i18n';
import offlineManagerSrc from '../components/map/hud/OfflineRegionManager.tsx?raw';
import tacticalMapSrc from '../components/map/TacticalMap.tsx?raw';

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => {}, send: () => {}, connect: () => {}, disconnect: () => {} },
}));

vi.mock('../components/map/TacticalMap', () => ({
  TacticalMap: () => <div data-testid="tactical-map" />,
}));

const MAP_FILES = Object.keys(import.meta.glob('../components/map/**/*.tsx'));

const DELETED = [
  'hud/ViewControls.tsx',
  'panels/GhostPanel.tsx',
  'panels/AnalysisPanel.tsx',
  'hud/SearchOmnibar.tsx',
  'hud/LayerPalette.tsx',
  'hud/BearingTool.tsx',
  'hud/RulerTool.tsx',
  'hud/GeofenceDrawTool.tsx',
  'hud/TimeMachineSlider.tsx',
  'hud/MapStateBadge.tsx',
  'hud/ElevationProfileSheet.tsx',
];

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: false } }));
});

describe('R0-12/R0-13 PC map: fake and dead controls are gone', () => {
  it.each(DELETED)('%s is deleted', (file) => {
    expect(MAP_FILES.some((f) => f.endsWith(`/map/${file}`))).toBe(false);
  });

  it.each([false, true])('HUD has no «Вигляд», no «Розвідка», no «AI» (expert=%s)', async (expert) => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: expert } }));
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await screen.findByTestId('hud-shell');
    expect(screen.queryByRole('group', { name: 'Вигляд мапи' })).toBeNull();
    expect(screen.queryByLabelText(/Вигляд · /)).toBeNull();
    expect(screen.queryByRole('button', { name: /Розвідка/ })).toBeNull();
    expect(screen.queryByText('AI')).toBeNull();
  });

  it('offline manager does not promise downloads', () => {
    expect(offlineManagerSrc).not.toMatch(/download new areas/i);
  });

  it('map frame is «Мапа», the add-place sheet speaks Ukrainian', () => {
    expect(tacticalMapSrc).not.toContain('Тактична мапа');
    expect(tacticalMapSrc).not.toContain('Drop POI');
    expect(tacticalMapSrc).toContain("aria-label={t('tmap.aria')}");
    expect(translate('tmap.aria', 'uk')).toBe('Мапа');
    expect(translate('tmap.aria', 'en')).toBe('Map');
  });
});
