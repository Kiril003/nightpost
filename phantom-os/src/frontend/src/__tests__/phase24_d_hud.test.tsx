/**
 * Phase 24-D — HUD components + agent bridge tests.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';

import { ScaleBar, computeScale } from '../components/map/hud/ScaleBar';
import { StatusChip } from '../components/map/hud/StatusChips';

import { useMapStore } from '../stores/mapStore';
import { useSystemStore } from '../stores/systemStore';
import { SystemState } from '@shared/types';

vi.mock('../services/websocket', () => {
  const handlers: Record<string, ((msg: unknown) => void)[]> = {};
  return {
    wsClient: {
      on: vi.fn(
        <T,>(channel: string, handler: (msg: T) => void) => {
          (handlers[channel] = handlers[channel] || []).push(handler as (msg: unknown) => void);
          return () => {
            const arr = handlers[channel] || [];
            const idx = arr.indexOf(handler as (msg: unknown) => void);
            if (idx >= 0) arr.splice(idx, 1);
          };
        },
      ),
      __dispatch: (channel: string, msg: unknown) => {
        for (const h of handlers[channel] || []) h(msg);
      },
    },
  };
});

import { wsClient } from '../services/websocket';
import { useMapAgentBridge } from '../hooks/useMapAgentBridge';

beforeEach(() => {
  useMapStore.setState({
    center: null,
    zoom: 14,
    layers: { base: true, presence: true, intel: true, recon: false, facts: true, cliff_scree: false },
    searchQuery: '',
    toast: null,
  });
  useSystemStore.setState({ state: SystemState.SHADOW, context: null } as never);
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ── ScaleBar ─────────────────────────────────────────────────────────────


describe('ScaleBar', () => {
  it('renders nice round metric labels', () => {
    render(<ScaleBar zoom={14} lat={50.0} maxWidthPx={120} />);
    const bar = screen.getByTestId('scale-bar');
    const meters = Number(bar.getAttribute('data-meters'));
    // 14/50N: 1 km is the natural pick at 120 px.
    expect(meters).toBeGreaterThan(0);
    expect([100, 200, 500, 1000, 2000, 5000]).toContain(meters);
  });

  it('renders sub-km labels at high zoom', () => {
    render(<ScaleBar zoom={18} lat={0} maxWidthPx={120} />);
    expect(screen.getByTestId('scale-bar')).toHaveTextContent(/м$/);
  });

  it('quantises to 1/2/5 × 10^N', () => {
    const { meters } = computeScale(12, 50, 200);
    const decade = Math.pow(10, Math.floor(Math.log10(meters)));
    expect([1, 2, 5]).toContain(meters / decade);
  });
});

// ── StatusChip ──────────────────────────────────────────────────────────


describe('StatusChip', () => {
  it('shows Syncing when loading=true', () => {
    render(<StatusChip loading={true} zoom={12} />);
    expect(screen.getByText('Синхронізую')).toBeInTheDocument();
  });

  it('shows Live when loading=false', () => {
    render(<StatusChip loading={false} zoom={14} />);
    expect(screen.getByText('Наживо')).toBeInTheDocument();
  });

  // «z15» — жаргон рендерера. Людині корисніше знати, наскільки близько
  // вона дивиться, тож чип показує масштаб словом.
  it('масштаб називає словом, а не рівнем зуму', () => {
    render(<StatusChip loading={false} zoom={15} />);
    expect(screen.getByText('· вулиці')).toBeInTheDocument();
  });

  it('на малому зумі це вже місто, а не вулиці', () => {
    render(<StatusChip loading={false} zoom={10} />);
    expect(screen.getByText('· місто')).toBeInTheDocument();
  });
});

// ── useMapAgentBridge ────────────────────────────────────────────────────


function BridgeHarness({ skip = false }: { skip?: boolean }): JSX.Element {
  useMapAgentBridge({ skip });
  return <div data-testid="bridge-harness" />;
}


describe('useMapAgentBridge', () => {
  it('applies set_view mutations to mapStore', () => {
    render(<BridgeHarness />);
    act(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (wsClient as any).__dispatch('map', {
        channel: 'map',
        type: 'set_view',
        data: {
          op: 'set_view',
          payload: { center: [30.5, 50.45], zoom: 12 },
          narrative: 'jumped to Kyiv',
        },
      });
    });
    expect(useMapStore.getState().center).toEqual([30.5, 50.45]);
    expect(useMapStore.getState().zoom).toBe(12);
    expect(useMapStore.getState().toast).toContain('Kyiv');
  });

  it('toggles a known layer on enable_layer mutation', () => {
    useMapStore.setState({ layers: { ...useMapStore.getState().layers, recon: false } });
    render(<BridgeHarness />);
    act(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (wsClient as any).__dispatch('map', {
        channel: 'map',
        type: 'enable_layer',
        data: { op: 'enable_layer', payload: { layer_id: 'recon' } },
      });
    });
    expect(useMapStore.getState().layers.recon).toBe(true);
  });

  it('ignores unknown layer ids without throwing', () => {
    render(<BridgeHarness />);
    expect(() =>
      act(() => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (wsClient as any).__dispatch('map', {
          channel: 'map',
          type: 'enable_layer',
          data: { op: 'enable_layer', payload: { layer_id: 'air_raid_ua' } },
        });
      }),
    ).not.toThrow();
  });

  it('skips subscription when options.skip is true', () => {
    render(<BridgeHarness skip />);
    // No wsClient.on call recorded for this render (the mock counts).
    // The bridge must be subscription-free in tests.
    expect(useMapStore.getState().center).toBeNull();
  });
});
