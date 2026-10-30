import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { mapApi } from '../services/api';
import { useMapStore } from '../stores/mapStore';
import { geocodeErrorKey } from '../services/geocodeError';

function reply503(reason: string) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    new Response(JSON.stringify({ detail: { reason } }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  useMapStore.setState({ toast: null, routeError: null, routing: false });
});

describe('R0-11 geocoder reason codes', () => {
  it('request() keeps detail.reason instead of an object message', async () => {
    reply503('geocoder_not_configured');
    const err = await mapApi.geocode('Ostrava').catch((e) => e);
    expect(err.reason).toBe('geocoder_not_configured');
    expect(String(err.message)).not.toContain('[object');
    expect(geocodeErrorKey(err)).toBe('map.geocode.notConfigured');
  });

  it('geocode sends lang and viewbox only when given', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ results: [] }), { status: 200 }),
    );
    await mapApi.geocode('Ostrava', 3, { lang: 'cs', viewbox: [18.1, 49.7, 18.4, 49.9] });
    await mapApi.geocode('Ostrava', 3);
    const bodies = spy.mock.calls.map((c) => JSON.parse(String((c[1] as RequestInit).body)));
    expect(bodies[0]).toEqual({ query: 'Ostrava', limit: 3, lang: 'cs', viewbox: [18.1, 49.7, 18.4, 49.9] });
    expect(bodies[1]).toEqual({ query: 'Ostrava', limit: 3 });
  });

  it('SearchBar toasts mapped copy for an unconfigured geocoder', async () => {
    reply503('geocoder_not_configured');
    const { SearchBar } = await import('../components/map/hud/SearchBar');
    render(<SearchBar value="Ostrava" onChange={() => {}} onResults={() => {}} />);
    await act(async () => {
      fireEvent.keyDown(screen.getByPlaceholderText('Знайти місце'), { key: 'Enter' });
    });
    expect(useMapStore.getState().toast).toBe('Пошук місць не налаштований на цьому вузлі');
  });

  it('planRoute shows mapped copy, never raw detail', async () => {
    reply503('geocoder_unavailable');
    await act(async () => {
      await useMapStore.getState().planRoute('', 'Ostrava');
    });
    expect(useMapStore.getState().routeError).toBe('Пошук місць зараз недоступний');
  });
});
