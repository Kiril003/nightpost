import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { CALL_PREFS_KEY, loadCallPrefs } from '../services/callPrefs';
import { callEngine } from '../services/callEngine';
import { remoteTile } from '../services/callView';
import { CallSettingsSheet } from '../components/messenger/CallSettingsSheet';

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

describe('CallSettingsSheet', () => {
  beforeEach(() => {
    localStorage.clear();
    setLocale('en');
    callEngine.setAudioOnly(false);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    callEngine.setAudioOnly(false);
    localStorage.clear();
    setLocale('uk');
  });

  it('renders the four rows in en when not in an active call', () => {
    render(<CallSettingsSheet active={false} readDataUsedBytes={() => null} />);

    expect(screen.getByText('Calls')).toBeTruthy();
    expect(screen.getByText('On Wi-Fi')).toBeTruthy();
    expect(screen.getByText('On mobile data')).toBeTruthy();
    expect(screen.getByText('Video')).toBeTruthy();
    expect(screen.getByText('Data used')).toBeTruthy();
    expect(screen.queryByRole('switch', { name: 'Audio only' })).toBeNull();
  });

  it('writes np.callPrefs.v1 when choosing "Save data" under mobile', () => {
    render(<CallSettingsSheet active={false} readDataUsedBytes={() => null} />);

    const mobileGroup = screen.getByRole('group', { name: 'On mobile data' });
    fireEvent.click(within(mobileGroup).getByRole('button', { name: 'Save data' }));

    const raw = localStorage.getItem(CALL_PREFS_KEY);
    expect(raw).toBeTruthy();
    expect(JSON.parse(raw!)).toEqual({
      wifi: 'best',
      mobile: 'save',
      video: 'auto',
    });
    expect(loadCallPrefs().mobile).toBe('save');
  });

  it('shows — with a null reader and 12.3 MB when the reader returns 12_300_000', async () => {
    const { rerender } = render(
      <CallSettingsSheet active={false} readDataUsedBytes={async () => null} />,
    );

    expect(screen.getByText('—')).toBeTruthy();

    rerender(<CallSettingsSheet active={false} readDataUsedBytes={async () => 12_300_000} />);

    await waitFor(() => {
      expect(screen.getByText('12.3 MB')).toBeTruthy();
    });
  });

  it('calls the engine setAudioOnly(true) when toggling the Audio only switch', () => {
    const spy = vi.spyOn(callEngine, 'setAudioOnly');
    const tileEl = document.createElement('video');
    remoteTile.set(tileEl);

    render(<CallSettingsSheet active={true} readDataUsedBytes={() => null} />);

    const toggle = screen.getByRole('switch', { name: 'Audio only' });
    act(() => {
      fireEvent.click(toggle);
    });

    expect(spy).toHaveBeenCalledWith(true);
    expect(callEngine.getSnapshot().audioOnly).toBe(true);
    expect(remoteTile.state().vis).toBe(false);
  });

  it('shows the data unit and decimal mark of the chosen language', async () => {
    setLocale('uk');
    render(<CallSettingsSheet active={false} readDataUsedBytes={async () => 12_300_000} />);
    await waitFor(() => {
      expect(screen.getByText('12,3 МБ')).toBeTruthy();
    });
  });

  it('audio only is a real switch with a track, not a text state', () => {
    render(<CallSettingsSheet active={true} readDataUsedBytes={() => null} />);
    const row = screen.getByRole('switch', { name: 'Audio only' });
    expect(row.querySelector('[data-switch-track]')).not.toBeNull();
    expect(within(row).queryByText(/^(On|Off)$/)).toBeNull();
  });

  it('every segment label fits one line: the best mode is a single word', () => {
    render(<CallSettingsSheet active={false} readDataUsedBytes={() => null} />);
    const wifi = screen.getByRole('group', { name: 'On Wi-Fi' });
    expect(within(wifi).getByRole('button', { name: 'Best' })).toBeTruthy();
  });
});
