/**
 * Той самий клас, що в `deadControls.test.tsx`, але на мапі. Нумерація
 * продовжує той файл; окремий файл тут тому, що ці двоє живуть під іншим
 * оточенням (мапа, ws, справжній fetch), і зносити його на екрани входу й
 * «Додатків» не можна.
 *
 * 4. Кнопки «Бібліотека шарів» не існувало ніде. `HudShell` оголошував
 *    `onOpenLibrary` у пропсах і не діставав його з деструктуризації, тож
 *    `OmniMap` слухняно передавав обробник у нікуди, а `libraryOpen`
 *    не міг стати `true` жодним дотиком по екрану. Панель на 236 рядків
 *    із одинадцятьма зеленими тестами була змонтована й недосяжна.
 *    Корінь: кнопку тримала `LayerPalette`, яку рейка `MapRail` замінила,
 *    а вхід у бібліотеку разом із нею загубила.
 *
 * Тест бʼє у справжній ланцюг: тисне кнопку й дивиться, чи панель
 * відкрилась, а не чи викликали сеттер.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useSettingsStore } from '../stores/settingsStore';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => {}, send: () => {}, connect: () => {}, disconnect: () => {} },
}));

// Полотно MapLibre до жодного з цих ланцюгів не належить.
vi.mock('../components/map/TacticalMap', () => ({
  TacticalMap: () => <div data-testid="tactical-map" />,
}));

const LAYERS_PAYLOAD = {
  layers: [
    {
      id: 'earthquakes',
      name_ua: 'Землетруси',
      name_en: 'Earthquakes',
      category: 'live',
      license: 'Public Domain (USGS)',
      attribution: 'USGS Earthquake Hazards Program',
      source: { type: 'rest_polling', ttl_s: 600, bbox_required: false, extras: {} },
      geometry: 'polygon',
      style: { fill_opacity: 0.18, stroke_width: 2, point_radius: 4, pulse: false, legend: [] },
      agent_verbs: [],
      require_internet: true,
      require_setting: null,
      require_root: false,
      private: false,
      priority: 'critical',
      default_active: false,
      available_offline: false,
      tags: ['live'],
      active: false,
    },
  ],
  total: 1,
  categories: ['live'],
  load_errors: [],
};

/** Порожні, але правильні за формою відповіді для всього, що тягне HUD. */
function payloadFor(url: string): unknown {
  if (url.includes('/map/layers')) return LAYERS_PAYLOAD;
  if (url.includes('/map/position_sources')) return { phone: null, aps: [], paired_devices: 0 };
  if (url.includes('/map/wifi_scan')) return { aps: [], known: 0 };
  if (url.includes('/map/nearby')) return { remembered: [], osm: [], pois: [] };
  return {};
}

let fetchSpy: ReturnType<typeof vi.fn>;

beforeEach(() => {
  // Ці тести — про інструменти фахівця; мапа людини — у mapPerson.test.
  useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: true } }));
  fetchSpy = vi.fn(async (url: string) => ({
    ok: true,
    status: 200,
    json: async () => payloadFor(String(url)),
  }));
  vi.stubGlobal('fetch', fetchSpy);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('дефект 4 — бібліотеку шарів не було чим відкрити', () => {
  it('кнопка на рейці шарів справді відкриває панель бібліотеки', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);

    const button = await screen.findByRole('button', { name: /Бібліотека шарів/ });
    fireEvent.click(button);

    // Не `setLibraryOpen`, а сама панель у документі.
    expect(await screen.findByTestId('layer-library-panel')).toBeTruthy();
  });

  it('доти панелі в документі немає', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);

    await screen.findByRole('button', { name: /Бібліотека шарів/ });
    expect(screen.queryByTestId('layer-library-panel')).toBeNull();
  });
});
