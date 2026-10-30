import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { useSettingsStore, LANGUAGE_SETTING_KEY } from '../stores/settingsStore';
import { SearchBar } from '../components/map/hud/SearchBar';
import tacticalMapSrc from '../components/map/TacticalMap.tsx?raw';

const TYPES = ['square', 'building', 'street', 'residential', 'city', 'cafe', 'park', 'something_new'];

function stubGeocode() {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    new Response(
      JSON.stringify({ results: TYPES.map((type, i) => ({ display_name: `P${i}`, lat: 50 + i / 100, lon: 14, type })) }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  );
}

async function subtitles(): Promise<string[]> {
  stubGeocode();
  const onResults = vi.fn();
  render(<SearchBar value="Praha" onChange={() => {}} onResults={onResults} />);
  await act(async () => {
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  });
  return onResults.mock.calls[0][0].osm.map((r: { category: string }) => r.category);
}

afterEach(() => {
  vi.restoreAllMocks();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('PC map result subtitles speak the reader’s language', () => {
  it('uk', async () => {
    expect(await subtitles()).toEqual(['площа', 'будівля', 'вулиця', 'вулиця', 'місто', 'кав’ярня', 'парк', 'місце']);
  });

  it('en', async () => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
    expect(await subtitles()).toEqual(['Square', 'Building', 'Street', 'Street', 'City', 'Café', 'Park', 'Place']);
  });
});

describe('PC map load toasts are plain words', () => {
  it('no Intel / Recon / Facts prefixes', () => {
    expect(tacticalMapSrc).not.toMatch(/`(Intel|Recon|Facts): /);
  });
});
