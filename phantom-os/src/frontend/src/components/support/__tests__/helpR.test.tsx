import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MyReports } from '../MyReports';
import { ProblemForm } from '../ProblemForm';
import { CrashPrompt } from '../CrashPrompt';
import { ErrorBoundary } from '../../core/ErrorBoundary';
import { SUPPORT_STORAGE_KEY, canAppealReport, useSupportStore, type SupportReport } from '../../../stores/supportStore';
import { useAuthStore } from '../../../stores/authStore';
import { supportApi } from '../../../services/supportApi';
import { CRASH_STORAGE_KEY, hashStack, readPendingCrash } from '../../../lib/crashReport';

vi.mock('../../../lib/ticketImage', () => ({
  prepareTicketImage: vi.fn(async () => ({ mime: 'image/jpeg', size: 2048, dataB64: 'QUJD' })),
}));

function seed(over: Partial<SupportReport>) {
  const r: SupportReport = {
    id: 'r1',
    targetKind: 'person',
    targetId: 'p1',
    reason: 'spam',
    createdAt: Date.now() - 60_000,
    status: 'dismissed',
    ...over,
  };
  localStorage.setItem(SUPPORT_STORAGE_KEY, JSON.stringify({ v: 1, reports: [r], outbox: [] }));
  useSupportStore.getState().reset();
}

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
  vi.spyOn(supportApi, 'listReports').mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
  useAuthStore.setState({ user: null });
});

describe('report outcome + appeal', () => {
  it('appeal is offered only after a decision and only once', () => {
    expect(canAppealReport({ status: 'sent' })).toBe(false);
    expect(canAppealReport({ status: 'reviewing' })).toBe(false);
    expect(canAppealReport({ status: 'action' })).toBe(true);
    expect(canAppealReport({ status: 'dismissed' })).toBe(true);
    expect(canAppealReport({ status: 'dismissed', appeal: { reason: 'other', text: 'x', at: 1, status: 'submitted' } })).toBe(false);
  });

  it('«Мої скарги» shows the outcome and sends one appeal', async () => {
    seed({});
    const appeal = vi.spyOn(supportApi, 'appealReport').mockResolvedValue({
      id: 'r1', target_kind: 'person', target_id: 'p1', reason: 'spam', status: 'dismissed',
      appeal: { reason: 'mistake', text: 'Помилково', at: new Date().toISOString(), status: 'submitted' },
      created_at: new Date().toISOString(),
    });
    render(<MyReports open onClose={vi.fn()} />);
    expect(screen.getByTestId('report-status-chip-r1').textContent).toBe('Без змін');

    fireEvent.click(screen.getByTestId('appeal-open-r1'));
    fireEvent.click(screen.getByTestId('appeal-reason-mistake'));
    expect((screen.getByTestId('appeal-send').closest('button') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByTestId('appeal-text'), { target: { value: 'Помилково' } });
    await act(async () => {
      fireEvent.click(screen.getByTestId('appeal-send'));
    });

    expect(appeal).toHaveBeenCalledWith('r1', { reason: 'mistake', text: 'Помилково' });
    await waitFor(() => expect(screen.getByTestId('appeal-state-r1').textContent).toBe('Оскарження подано'));
    expect(screen.queryByTestId('appeal-open-r1')).toBeNull();
  });

  it('no appeal button while the report is still open', () => {
    seed({ status: 'reviewing' });
    render(<MyReports open onClose={vi.fn()} />);
    expect(screen.queryByTestId('appeal-open-r1')).toBeNull();
  });

  it('server-side appeal state arrives through the status refresh', async () => {
    seed({ status: 'action' });
    vi.spyOn(supportApi, 'listReports').mockResolvedValue([
      {
        id: 'r1', target_kind: 'person', target_id: 'p1', reason: 'spam', status: 'action',
        appeal: { reason: 'other', text: 't', at: new Date().toISOString(), status: 'overturned' },
        created_at: new Date().toISOString(),
      },
    ]);
    render(<MyReports open onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('appeal-state-r1').textContent).toBe('Рішення змінили'));
  });
});

describe('ticket screenshot', () => {
  it('shows the image in the preview and sends it with the ticket', async () => {
    const submit = vi.spyOn(supportApi, 'submitTicket').mockResolvedValue({
      id: 'x', type: 'calls', subject: 's', text: 't', status: 'open', unread: false, thread: [],
      attachment: { mime: 'image/jpeg', size: 2048 }, created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    });
    render(<ProblemForm open onClose={vi.fn()} initialType="calls" />);
    fireEvent.change(screen.getByTestId('problem-subject-input'), { target: { value: 'Тема' } });
    fireEvent.change(screen.getByTestId('problem-text-input'), { target: { value: 'Опис' } });
    const file = new File(['x'], 'shot.png', { type: 'image/png' });
    await act(async () => {
      fireEvent.change(screen.getByTestId('problem-attach-input'), { target: { files: [file] } });
    });
    await screen.findByTestId('problem-attach-thumb');
    fireEvent.click(screen.getByTestId('problem-next-btn'));
    expect(screen.getByTestId('problem-preview-image')).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByTestId('problem-submit-btn'));
    });
    expect(submit.mock.calls[0][0].attachment).toEqual({ mime: 'image/jpeg', data_b64: 'QUJD' });
    expect(useSupportStore.getState().tickets[0].attachment).toEqual({ mime: 'image/jpeg', size: 2048 });
  });

  it('removing the screenshot sends the ticket without it', async () => {
    const submit = vi.spyOn(supportApi, 'submitTicket').mockResolvedValue({
      id: 'x', type: 'calls', subject: 's', text: 't', status: 'open', unread: false, thread: [],
      created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    });
    render(<ProblemForm open onClose={vi.fn()} initialType="calls" />);
    fireEvent.change(screen.getByTestId('problem-subject-input'), { target: { value: 'Тема' } });
    fireEvent.change(screen.getByTestId('problem-text-input'), { target: { value: 'Опис' } });
    await act(async () => {
      fireEvent.change(screen.getByTestId('problem-attach-input'), { target: { files: [new File(['x'], 'a.png', { type: 'image/png' })] } });
    });
    fireEvent.click(await screen.findByTestId('problem-attach-remove'));
    fireEvent.click(screen.getByTestId('problem-next-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('problem-submit-btn'));
    });
    expect(submit.mock.calls[0][0].attachment).toBeUndefined();
  });
});

describe('crash prompt', () => {
  function Bomb(): never {
    const e = new TypeError('secret message text 4242');
    throw e;
  }

  it('boundary stores name + stack hash + version, never the message', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<ErrorBoundary><Bomb /></ErrorBoundary>);
    const raw = localStorage.getItem(CRASH_STORAGE_KEY) ?? '';
    const crash = readPendingCrash();
    expect(crash?.name).toBe('TypeError');
    expect(crash?.stackHash).toMatch(/^[0-9a-f]{16}$/);
    expect(raw).not.toContain('secret message');
  });

  it('a stale-chunk failure is not a crash', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const Chunk = (): never => {
      throw new Error('Failed to fetch dynamically imported module');
    };
    render(<ErrorBoundary><Chunk /></ErrorBoundary>);
    expect(readPendingCrash()).toBeNull();
  });

  it('hash ignores line numbers and bundle urls', () => {
    expect(hashStack('Error\n at f (https://a/x.js:10:5)')).toBe(hashStack('Error\n at f (https://b/y.js:99:1)'));
  });

  it('asks on next launch, sends only after consent, then forgets', async () => {
    localStorage.setItem(CRASH_STORAGE_KEY, JSON.stringify({ id: 'c1', name: 'TypeError', stackHash: 'abcdef0123456789', appVersion: 'web', at: 1 }));
    useAuthStore.setState({ user: { id: 'u' } as never });
    const send = vi.spyOn(supportApi, 'sendCrash').mockResolvedValue({ id: 'c1', stored: true });
    render(<CrashPrompt />);
    const preview = screen.getByTestId('crash-preview');
    expect(preview.textContent).toContain('TypeError');
    expect(preview.textContent).toContain('abcdef0123456789');
    expect(send).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.click(screen.getByTestId('crash-send'));
    });
    expect(send).toHaveBeenCalledWith({ id: 'c1', name: 'TypeError', stack_hash: 'abcdef0123456789', app_version: 'web', at: 1 });
    expect(localStorage.getItem(CRASH_STORAGE_KEY)).toBeNull();
    expect(screen.queryByTestId('crash-prompt')).toBeNull();
  });

  it('«Не зараз» drops the report without sending; signed-out shows nothing', () => {
    localStorage.setItem(CRASH_STORAGE_KEY, JSON.stringify({ id: 'c2', name: 'Error', stackHash: 'abcdef01', appVersion: 'web', at: 1 }));
    const send = vi.spyOn(supportApi, 'sendCrash');
    const { unmount } = render(<CrashPrompt />);
    expect(screen.queryByTestId('crash-prompt')).toBeNull();
    unmount();
    useAuthStore.setState({ user: { id: 'u' } as never });
    render(<CrashPrompt />);
    fireEvent.click(screen.getByTestId('crash-later'));
    expect(send).not.toHaveBeenCalled();
    expect(localStorage.getItem(CRASH_STORAGE_KEY)).toBeNull();
  });

  it('a failed send keeps the report for the next launch', async () => {
    localStorage.setItem(CRASH_STORAGE_KEY, JSON.stringify({ id: 'c3', name: 'Error', stackHash: 'abcdef01', appVersion: 'web', at: 1 }));
    useAuthStore.setState({ user: { id: 'u' } as never });
    vi.spyOn(supportApi, 'sendCrash').mockRejectedValue(new Error('net'));
    render(<CrashPrompt />);
    await act(async () => {
      fireEvent.click(screen.getByTestId('crash-send'));
    });
    expect(screen.getByTestId('crash-failed')).toBeTruthy();
    expect(localStorage.getItem(CRASH_STORAGE_KEY)).not.toBeNull();
  });
});
