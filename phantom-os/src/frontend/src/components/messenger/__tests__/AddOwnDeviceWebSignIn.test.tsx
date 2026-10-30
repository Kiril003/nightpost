import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { AddOwnDevice, SignInOnWebRow } from '../AddOwnDevice';
import { DeviceSheet } from '../DeviceSheet';

type FakeBridge = {
  openWebSignIn?: () => boolean;
  call?: (method: string, path: string, body?: string | null) => string;
  haptic?: (type: string) => void;
  getTheme?: () => string;
  getThemePhase?: () => string;
  getThemeMode?: () => string;
  getLanguage?: () => string;
};

const BRIDGE_KEYS = [
  'PhantomNative',
  'PhantomBridge',
  '__phantomBridge',
  '__phantom',
  'phantomNative',
  'phantomBridge',
  'AndroidBridge',
  'Android',
] as const;

function installBridge(bridge: FakeBridge | null) {
  const win = window as unknown as Record<string, unknown>;
  for (const key of BRIDGE_KEYS) {
    if (bridge) {
      win[key] = bridge;
    } else {
      delete win[key];
    }
  }
}

describe('Web sign-in entry row in own-devices UI', () => {
  beforeEach(() => {
    installBridge(null);
  });

  afterEach(() => {
    cleanup();
    installBridge(null);
    vi.restoreAllMocks();
  });

  it('renders "Увійти на сайті" in AddOwnDevice on phone and calls openWebSignIn on click', () => {
    const openWebSignIn = vi.fn(() => true);
    const call = vi.fn(() => JSON.stringify({ ok: true, status: 200, data: {} }));
    installBridge({ openWebSignIn, call, haptic: vi.fn() });

    render(<AddOwnDevice />);

    const row = screen.getByRole('button', { name: /Увійти на сайті|Sign in on the web/i });
    expect(row).toBeTruthy();

    fireEvent.click(row);
    expect(openWebSignIn).toHaveBeenCalledTimes(1);
  });

  it('falls back to POST /messenger/devices/web-signin when openWebSignIn method is not on bridge', () => {
    const call = vi.fn(() => JSON.stringify({ ok: true, status: 200, data: { opened: true } }));
    installBridge({ call, haptic: vi.fn() });

    render(<SignInOnWebRow />);

    const row = screen.getByRole('button', { name: /Увійти на сайті|Sign in on the web/i });
    fireEvent.click(row);

    expect(call).toHaveBeenCalledWith('POST', '/messenger/devices/web-signin', '{}');
  });

  it('renders "Увійти на сайті" in DeviceSheet on phone and invokes openWebSignIn', () => {
    const openWebSignIn = vi.fn(() => true);
    const call = vi.fn(() =>
      JSON.stringify({ ok: true, status: 200, data: { stopped: false, devices: [] } }),
    );
    installBridge({ openWebSignIn, call, haptic: vi.fn() });

    const onClose = vi.fn();
    render(<DeviceSheet title="Мій ПК" onClose={onClose} />);

    const row = screen.getByTestId('device-web-signin');
    expect(row.textContent).toMatch(/Увійти на сайті|Sign in on the web/);

    fireEvent.click(row);
    expect(openWebSignIn).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
