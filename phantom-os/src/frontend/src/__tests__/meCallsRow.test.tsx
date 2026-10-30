import type { ReactNode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MeScreen } from '../components/messenger/MeScreen';
import { uk, type MessageKey } from '../i18n/messages/uk';

const { costTrackingSpy } = vi.hoisted(() => ({
  costTrackingSpy: vi.fn(),
}));

const idleSnapshot = {
  state: 'idle' as const,
  callId: null,
  audioOnly: false,
  musicMode: false,
};

vi.mock('../services/callCost', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/callCost')>();
  return {
    ...actual,
    ensureCallCostTracking: () => {
      costTrackingSpy();
      actual.ensureCallCostTracking();
    },
  };
});

vi.mock('../services/peerWhere', () => ({
  minutesLeft: () => '',
  onWhereChanged: () => () => {},
  wherePeerOf: () => null,
  whereApi: {
    get: vi.fn(async () => ({ mine: null, peer: null })),
    stop: vi.fn(async () => ({ mine: null, peer: null })),
  },
}));

vi.mock('../components/messenger/HandleSheet', () => ({
  useMyHandle: () => ({ kind: 'none' as const }),
}));

vi.mock('../components/messenger/MeSections', () => ({
  FindMe: () => null,
  MyDevices: () => null,
  MyMemory: () => null,
  SectionTitle: ({ children }: { children?: ReactNode }) => <h2>{children}</h2>,
  ThemeChoice: () => null,
  useNetworkLine: () => ({ line: null, go: () => {} }),
  useOwnDevices: () => [],
}));

vi.mock('../components/messenger/QuietNights', () => ({
  QuietNightsCard: () => null,
}));

vi.mock('../components/messenger/PhantomFeed', () => ({
  PhantomRows: () => null,
}));

vi.mock('../components/messenger/MyIntents', () => ({
  MyIntents: () => null,
}));

vi.mock('../components/messenger/OfflineSetup', () => ({
  OfflineEntry: () => null,
}));

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      renameSelf: vi.fn(async (name: string) => ({ bundle: { name } })),
    },
  };
});

vi.mock('../services/callEngine', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/callEngine')>();
  return {
    ...actual,
    callEngine: {
      ...actual.callEngine,
      subscribe: () => () => {},
      getSnapshot: () => idleSnapshot,
      dataUsedBytes: () => null,
      setAudioOnly: vi.fn(),
      setMusicMode: vi.fn(),
    },
  };
});

vi.mock('../utils/messengerSound', () => ({
  soundFx: { playTap: vi.fn() },
}));

const t = (key: MessageKey): string => uk[key];

afterEach(() => {
  delete (window as unknown as { __phantomOpenTab?: (key: string) => boolean }).__phantomOpenTab;
  cleanup();
});

describe('MeScreen calls row', () => {
  it('renders me-calls in settings and opens the call log, whose settings row routes to call-settings without loading CallSettingsSheet', () => {
    const openTab = vi.fn(() => true);
    (window as unknown as { __phantomOpenTab?: (key: string) => boolean }).__phantomOpenTab = openTab;

    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);

    const row = screen.getByTestId('me-calls');
    expect(row.textContent).toContain(t('callsHub.title'));

    fireEvent.click(row);
    expect(openTab).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('call-log-settings'));
    expect(openTab).toHaveBeenCalledTimes(1);
    expect(openTab).toHaveBeenCalledWith('call-settings');
    expect(costTrackingSpy).not.toHaveBeenCalled();
  });

  it('lazily opens CallSettingsSheet locally when __phantomOpenTab is unavailable', async () => {
    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
    expect(costTrackingSpy).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('me-calls'));
    fireEvent.click(screen.getByTestId('call-log-settings'));

    const sheet = await screen.findByTestId('call-settings-sheet', {}, { timeout: 8000 });
    expect(costTrackingSpy).toHaveBeenCalledTimes(1);
    expect(within(sheet).getByText(t('callsHub.title'))).toBeTruthy();

    fireEvent.click(within(sheet).getByLabelText(t('formSheet.close')));
    await waitFor(() => {
      expect(screen.queryByTestId('call-settings-sheet')).toBeNull();
    });
  });
});
