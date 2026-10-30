import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { MyReports } from '../MyReports';
import { TicketList } from '../HelpCentre';
import { isServerPending, useSupportStore } from '../../../stores/supportStore';
import { supportApi } from '../../../services/supportApi';

const pending = Object.assign(new Error('support_pending'), { status: 503, code: 'support_pending' });

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('phone support waits for the server honestly', () => {
  it('server trouble waits, a refusal does not', () => {
    expect(isServerPending(pending)).toBe(true);
    expect(isServerPending(Object.assign(new Error('x'), { status: 503, code: 'timeout' }))).toBe(true);
    expect(isServerPending(Object.assign(new Error('x'), { status: 422, code: 'support_refused' }))).toBe(false);
  });

  it('a report stays queued with the label instead of an error', async () => {
    vi.spyOn(supportApi, 'submitReport').mockRejectedValue(pending);
    vi.spyOn(supportApi, 'listReports').mockRejectedValue(pending);
    let report;
    await act(async () => {
      report = await useSupportStore.getState().submitReport({
        targetKind: 'message', targetId: 'm1', reason: 'spam',
      });
    });
    expect(report!.status).toBe('queued');
    expect(useSupportStore.getState().outbox.map((r) => r.id)).toEqual([report!.id]);
    expect(useSupportStore.getState().serverPending).toBe(true);

    render(<MyReports open onClose={vi.fn()} />);
    await waitFor(() =>
      expect(screen.getByTestId(`report-status-chip-${report!.id}`).textContent).toBe('Чекає зв\'язку з сервером'),
    );
  });

  it('a ticket list does not fall into the error state while the server is pending', async () => {
    vi.spyOn(supportApi, 'submitTicket').mockRejectedValue(pending);
    vi.spyOn(supportApi, 'listTickets').mockRejectedValue(pending);
    await act(async () => {
      await useSupportStore.getState().submitTicket({ id: 't1', type: 'calls', subject: 'Звук', text: 'Нема звуку' });
    });
    render(<TicketList open onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('ticket-row-t1').textContent).toContain('Чекає зв\'язку з сервером'));
    expect(screen.queryByTestId('ticket-error')).toBeNull();
  });

  it('a reached server clears the label', async () => {
    useSupportStore.setState({ serverPending: true });
    vi.spyOn(supportApi, 'listReports').mockResolvedValue([]);
    await act(async () => {
      await useSupportStore.getState().refreshStatuses();
    });
    expect(useSupportStore.getState().serverPending).toBe(false);
  });
});
