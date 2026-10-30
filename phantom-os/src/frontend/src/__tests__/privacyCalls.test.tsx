import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { messengerApi, type PrivacyState } from '../services/messengerApi';
import { PrivacyPage } from '../components/messenger/SettingsRoot';
import { callEngine } from '../services/callEngine';
import { whyLabel } from '../services/callRoom';
import { callStateLine } from '../services/callWords';
import * as api from '../services/api';

beforeEach(() => {
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('offline'));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Приватність: хто може дзвонити', () => {
  it('рядок «Дзвінки» лише коли телефон його виконує, і вибір іде на телефон', async () => {
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true, last_seen: 'all', calls: 'all' });
    const set = vi
      .spyOn(messengerApi, 'setVisibility')
      .mockImplementation(async (patch) => ({ read_receipts: true, last_seen: 'all', calls: 'all', ...patch }) as PrivacyState);
    render(<PrivacyPage />);
    const row = await screen.findByTestId('settings-vis-calls');
    expect(row.textContent).toContain('Дзвінки');
    fireEvent.click(row);
    fireEvent.click(within(screen.getByTestId('settings-vis-calls-options')).getByText('Мої люди'));
    expect(set).toHaveBeenCalledWith({ calls: 'people' });
    await waitFor(() => expect(screen.getByTestId('settings-vis-calls').textContent).toContain('Мої люди'));
  });

  it('стара збірка без поля — рядка «Дзвінки» немає', async () => {
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true, last_seen: 'all' });
    render(<PrivacyPage />);
    await screen.findByTestId('settings-vis-last_seen');
    expect(screen.queryByTestId('settings-vis-calls')).toBeNull();
  });

  it('той, кому відмовили, бачить «Не приймає дзвінків», а не «відхилив»', async () => {
    expect(whyLabel('calls-off')).toBe('не приймає дзвінків');
    vi.spyOn(api, 'request').mockResolvedValue({ delivered: true, call_id: 'c_off', detail: '' } as never);
    (callEngine as unknown as { patch: (p: unknown) => void }).patch({
      state: 'calling',
      callId: 'c_off',
      peer: { peerNodeId: 'peer_1', displayName: 'Olena' },
      media: 'audio',
    });
    await callEngine.onSignal({ kind: 'hangup', call_id: 'c_off', from_node_id: 'peer_1', reason: 'calls-off' });
    expect(callStateLine(callEngine.getSnapshot())).toBe('Не приймає дзвінків');
  });
});
