import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MyReports } from '../MyReports';
import { ReportSheet } from '../ReportSheet';
import { CrashPrompt } from '../CrashPrompt';
import { queuedLabelKey, useSupportStore, type SupportReport } from '../../../stores/supportStore';
import { useAuthStore } from '../../../stores/authStore';
import { CRASH_STORAGE_KEY } from '../../../lib/crashReport';
import { messengerApi } from '../../../services/messengerApi';
import { supportApi } from '../../../services/supportApi';

const err = (status: number, code: string) => Object.assign(new Error(code), { status, code });

function queued(id: string): SupportReport {
  return { id, targetKind: 'message', targetId: `m-${id}`, reason: 'spam', createdAt: Date.now(), status: 'queued' };
}

function seedReports(...ids: string[]) {
  const reports = ids.map(queued);
  useSupportStore.setState({ reports, outbox: reports });
}

const sent = (p: { id: string }) =>
  Promise.resolve({ id: p.id, target_kind: 'message', target_id: 'm', reason: 'spam', status: 'sent', created_at: new Date().toISOString() });

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('support queue after the server answers', () => {
  it('a successful list sends what was queued before', async () => {
    seedReports('r1');
    vi.spyOn(supportApi, 'listReports').mockResolvedValue([]);
    const submit = vi.spyOn(supportApi, 'submitReport').mockImplementation(sent as never);
    await act(async () => {
      await useSupportStore.getState().refreshStatuses();
    });
    await waitFor(() => expect(useSupportStore.getState().outbox).toEqual([]));
    expect(submit).toHaveBeenCalledTimes(1);
    expect(useSupportStore.getState().reports[0].status).toBe('sent');
  });

  it('a successful ticket list sends a queued ticket', async () => {
    useSupportStore.setState({
      tickets: [{ id: 't1', type: 'calls', subject: 'Звук', text: 'Нема', status: 'queued', unread: false, createdAt: 1, thread: [] }],
    });
    vi.spyOn(supportApi, 'listTickets').mockResolvedValue([]);
    const submit = vi.spyOn(supportApi, 'submitTicket').mockResolvedValue({
      id: 't1', type: 'calls', subject: 'Звук', status: 'open', created_at: new Date().toISOString(), thread: [],
    } as never);
    await act(async () => {
      await useSupportStore.getState().refreshTickets();
    });
    await waitFor(() => expect(useSupportStore.getState().tickets[0].status).toBe('open'));
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('coming back to the app flushes the queue', async () => {
    seedReports('r1');
    const submit = vi.spyOn(supportApi, 'submitReport').mockImplementation(sent as never);
    document.dispatchEvent(new Event('visibilitychange'));
    await waitFor(() => expect(submit).toHaveBeenCalled());
  });

  it('a busy server stops the flush at the first item and keeps everything queued', async () => {
    seedReports('r1', 'r2', 'r3');
    const submit = vi.spyOn(supportApi, 'submitReport').mockRejectedValue(err(429, 'support_busy'));
    await act(async () => {
      await useSupportStore.getState().flushReports();
    });
    expect(submit).toHaveBeenCalledTimes(1);
    expect(useSupportStore.getState().outbox.map((r) => r.id)).toEqual(['r1', 'r2', 'r3']);
    expect(useSupportStore.getState().serverPending).toBe(true);
  });

  it('a wrong phone clock keeps the report queued and says so', async () => {
    seedReports('r1');
    vi.spyOn(supportApi, 'submitReport').mockRejectedValue(err(401, 'clock_skew'));
    vi.spyOn(supportApi, 'listReports').mockRejectedValue(err(401, 'clock_skew'));
    await act(async () => {
      await useSupportStore.getState().flushReports();
    });
    expect(useSupportStore.getState().outbox).toHaveLength(1);
    expect(useSupportStore.getState().clockSkew).toBe(true);
    render(<MyReports open onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('report-status-chip-r1').textContent).toBe('Неточний час на телефоні'));
  });

  it('a permanent refusal marks the report as not accepted and leaves the queue', async () => {
    seedReports('r1');
    vi.spyOn(supportApi, 'submitReport').mockRejectedValue(err(413, 'support_refused'));
    await act(async () => {
      await useSupportStore.getState().flushReports();
    });
    expect(useSupportStore.getState().outbox).toEqual([]);
    expect(useSupportStore.getState().reports[0].status).toBe('failed');
  });

  it('a report that already reached the server cannot be undone on the phone alone', async () => {
    vi.spyOn(supportApi, 'submitReport').mockImplementation(sent as never);
    const report = await useSupportStore.getState().submitReport({ targetKind: 'person', targetId: 'p1', reason: 'spam' });
    expect(report.status).toBe('sent');
    expect(useSupportStore.getState().undoReport(report.id)).toBe(false);
    expect(useSupportStore.getState().reports).toHaveLength(1);
  });

  it('offline, the ticket list is not even asked', async () => {
    const list = vi.spyOn(supportApi, 'listTickets').mockResolvedValue([]);
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    await useSupportStore.getState().refreshTickets();
    expect(list).not.toHaveBeenCalled();
    online.mockRestore();
  });

  it('a retried report keeps one id, so the server stores it once', async () => {
    const submit = vi
      .spyOn(supportApi, 'submitReport')
      .mockRejectedValueOnce(err(422, 'support_refused'))
      .mockImplementation(sent as never);
    vi.spyOn(messengerApi, 'blockPeer').mockResolvedValue({ blocked: true });
    render(<ReportSheet open onClose={vi.fn()} target={{ targetKind: 'person', targetId: 'p1' }} />);
    const sheet = screen.getByTestId('report-sheet');
    fireEvent.click(within(sheet).getByText('Спам'));
    await act(async () => {
      fireEvent.click(within(sheet).getByTestId('report-send-btn'));
    });
    await act(async () => {
      fireEvent.click(within(sheet).getByTestId('report-send-btn'));
    });
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
    expect(submit.mock.calls[0][0].id).toBe(submit.mock.calls[1][0].id);
  });

  it('offline wins over a stale server flag', () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    expect(queuedLabelKey({ serverPending: true, clockSkew: false }, 'support.queued.copy')).toBe('support.queued.copy');
    online.mockRestore();
    expect(queuedLabelKey({ serverPending: true, clockSkew: false }, 'support.queued.copy')).toBe('support.status.serverPending');
  });

  it('a crash note waiting for the server says so and stays for later', async () => {
    localStorage.setItem(CRASH_STORAGE_KEY, JSON.stringify({ id: 'c9', name: 'Error', stackHash: 'abcdef01', appVersion: 'web', at: 1 }));
    useAuthStore.setState({ user: { id: 'u' } as never });
    vi.spyOn(supportApi, 'sendCrash').mockRejectedValue(err(503, 'support_pending'));
    render(<CrashPrompt />);
    await act(async () => {
      fireEvent.click(screen.getByTestId('crash-send'));
    });
    expect(screen.getByTestId('crash-held').textContent).toBe('Чекає зв\'язку з сервером');
    expect(screen.queryByTestId('crash-failed')).toBeNull();
    expect(localStorage.getItem(CRASH_STORAGE_KEY)).not.toBeNull();
    useAuthStore.setState({ user: null });
  });
});
