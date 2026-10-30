import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { DeskSidePane } from '../components/messenger/DeskSidePane';
import { useUIStore } from '../stores/uiStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { OmniMapProps } from '../components/map/OmniMap';

const seen = vi.hoisted(() => ({ props: [] as unknown[], crash: false }));

vi.mock('../components/map/OmniMap', () => ({
  OmniMap: (props: unknown) => {
    seen.props.push(props);
    if (seen.crash) throw new Error('map failed');
    return <div data-testid="omnimap" />;
  },
}));

describe('pcSidePaneMap: колонка Мапа — та сама жива мапа, що й повна', () => {
  beforeEach(() => {
    seen.props.length = 0;
    seen.crash = false;
    useUIStore.setState({ deskPane: 'map', toasts: [] });
    useMessengerStore.setState({ activeLocationData: null });
  });

  it('міст помічника лишається ввімкненим, а початковий масштаб — з налаштувань', () => {
    act(() => {
      useSettingsStore.setState({ values: { ...useSettingsStore.getState().values, ui_map_default_zoom: 12 } });
    });
    render(<DeskSidePane />);
    const last = seen.props[seen.props.length - 1] as OmniMapProps;
    expect(last.bridgeAgent).not.toBe(false);
    expect(last.initialZoom).toBe(12);
  });

  it('мапа впала — підпис має власний testid і не видає себе за мапу', () => {
    seen.crash = true;
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<DeskSidePane />);
    spy.mockRestore();
    expect(screen.queryByTestId('omnimap')).toBeNull();
    expect(screen.getByTestId('desk-pane-map-failed')).toBeInTheDocument();
  });
});
