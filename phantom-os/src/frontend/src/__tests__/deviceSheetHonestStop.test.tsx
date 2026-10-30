import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { DeviceSheet } from '../components/messenger/DeviceSheet';
import { deviceApi, type JournalLine, type Ladders, type OwnDevice } from '../services/deviceApi';
import * as useTranslationMod from '../i18n/useTranslation';
import { en as l1En } from '../i18n/messages/parts/l1';

const NOW = Date.UTC(2026, 9, 17, 12, 0, 0);

const stoppedPc: OwnDevice = {
  id: 'pc',
  name: 'Laptop',
  kind: 'pc',
  peer_id: 'node-pc',
  online: true,
  battery_pct: 81,
  charging: true,
  stopped: true,
};

const phoneLadders: Ladders = {
  here: {
    editable: true,
    spheres: [
      { sphere: 'place', title: 'Place', level: 'see', irreversible: false },
      { sphere: 'sound', title: 'Sound and voice', level: 'see', irreversible: false },
      { sphere: 'notices', title: 'Notifications', level: 'see', irreversible: false },
    ],
  },
  there: null,
};

const asPhone = () => {
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
};

const useEnglishLocale = () => {
  document.documentElement.lang = 'en';
  for (const key of ['lang', 'locale', 'phantom_lang', 'phantom_locale', 'ui_lang', 'language']) {
    try {
      localStorage.setItem(key, 'en');
    } catch {
      // ignore storage errors in test env
    }
  }
  const mod = useTranslationMod as Record<string, unknown>;
  for (const fn of ['setLang', 'setLocale', 'setLanguage', 'changeLanguage']) {
    if (typeof mod[fn] === 'function') (mod[fn] as (l: string) => void)('en');
  }
  const realUseTranslation = useTranslationMod.useTranslation;
  vi.spyOn(useTranslationMod, 'useTranslation').mockImplementation((...args: unknown[]) => {
    const real = (realUseTranslation as (...a: unknown[]) => ReturnType<typeof useTranslationMod.useTranslation>)(...args);
    if (real.t('deviceSheet.close') === 'Close') {
      return real;
    }
    return {
      ...real,
      t: ((key: string, params?: Record<string, string | number>) => {
        const raw = (l1En as Record<string, string | undefined>)[key] ?? real.t(key as never, params as never);
        if (!params) return raw;
        return raw.replace(/\{(\w+)\}/g, (_, k: string) => String(params[k] ?? `{${k}}`));
      }) as typeof real.t,
    };
  });
};

beforeEach(() => {
  vi.spyOn(Date, 'now').mockReturnValue(NOW);
  useEnglishLocale();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('DeviceSheet honest PC stop and localized journal keys', () => {
  it('shows Stopped on the computer and hides Stop everything and live actions when PC is stopped but phone is not', async () => {
    asPhone();
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [stoppedPc] });
    vi.spyOn(deviceApi, 'ladder').mockResolvedValue(phoneLadders);
    vi.spyOn(deviceApi, 'journal').mockResolvedValue({ stopped: false, entries: [] });

    render(<DeviceSheet title="My computer" peerNodeId="node-pc" onClose={() => {}} />);

    const stoppedBanner = await screen.findByTestId('device-stopped-there');
    expect(stoppedBanner.textContent).toBe('Stopped on the computer');
    expect(screen.queryByTestId('device-stop-all')).toBeNull();
    expect(screen.queryByTestId('device-actions')).toBeNull();
  });

  it('renders journal row in English without Cyrillic when what_key and who keys are provided', async () => {
    asPhone();
    const row = {
      id: '1',
      at: new Date(NOW - 60_000).toISOString(),
      verb: 'symbiote.stop',
      status: 'stop_on',
      what_key: 'status.stop_on',
      who: 'this_phone',
      what: 'Усе зупинено',
      status_title: '',
      detail: '',
      can_answer: false,
    } as JournalLine & { what_key: string };

    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [{ ...stoppedPc, stopped: false }] });
    vi.spyOn(deviceApi, 'ladder').mockResolvedValue(phoneLadders);
    vi.spyOn(deviceApi, 'journal').mockResolvedValue({ stopped: false, entries: [row] });

    render(<DeviceSheet title="My computer" peerNodeId="node-pc" onClose={() => {}} />);

    const journal = await screen.findByTestId('device-journal');
    await waitFor(() => expect(journal.querySelector('[data-journal-line]')).toBeTruthy());
    const lineText = journal.querySelector('[data-journal-line]')?.textContent ?? '';
    expect(lineText).toContain('Everything stopped');
    expect(lineText).toContain('This phone');
    expect(lineText).not.toMatch(/[\u0400-\u04FF]/);
  });

  it('fresh mount after the phone stopped everything shows Resume here, not Stopped on the computer', async () => {
    asPhone();
    const lastStop = {
      id: '9', at: new Date(NOW - 60_000).toISOString(), verb: 'symbiote.stop', status: 'stop_on',
      what_key: 'status.stop_on', who: 'this_phone', what: '', status_title: '', detail: '', can_answer: false,
    } as JournalLine & { what_key: string };
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: true, devices: [stoppedPc] });
    vi.spyOn(deviceApi, 'ladder').mockResolvedValue(phoneLadders);
    vi.spyOn(deviceApi, 'journal').mockResolvedValue({ stopped: true, entries: [lastStop] });

    render(<DeviceSheet title="My computer" peerNodeId="node-pc" onClose={() => {}} />);

    const here = await screen.findByTestId('device-stopped');
    expect(here.textContent).toContain('Resume');
    expect(screen.queryByTestId('device-stopped-there')).toBeNull();
    expect(screen.queryByText('Stopped on the computer')).toBeNull();
  });
});
