import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { HelpCentre } from '../HelpCentre';
import { MyReports } from '../MyReports';
import { holdKind, isRefused, useSupportStore, type SupportReport } from '../../../stores/supportStore';
import { supportApi } from '../../../services/supportApi';

const err = (status: number, code: string, detail = code) => Object.assign(new Error(detail), { status, code });

const queued = (id: string): SupportReport => ({
  id, targetKind: 'message', targetId: `m-${id}`, reason: 'spam', createdAt: Date.now(), status: 'queued',
});

const flushWith = async (e: unknown) => {
  const reports = [queued('r1')];
  useSupportStore.setState({ reports, outbox: reports });
  vi.spyOn(supportApi, 'submitReport').mockRejectedValue(e);
  await act(async () => {
    await useSupportStore.getState().flushReports();
  });
  return useSupportStore.getState();
};

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  localStorage.clear();
});

describe('APK without the /support relay', () => {
  it('bridge not_found holds the report; nothing is marked failed', async () => {
    const e = err(404, 'not_found', 'Шлях /support/reports мосту невідомий');
    expect(holdKind(e)).toBe('server');
    expect(isRefused(e)).toBe(false);
    const s = await flushWith(e);
    expect(s.outbox).toHaveLength(1);
    expect(s.reports[0].status).toBe('queued');
    expect(s.serverPending).toBe(true);
  });

  it('a server 404 relayed as support_refused is still a refusal', () => {
    expect(isRefused(err(404, 'support_refused', 'report not found'))).toBe(true);
  });
});

describe('401: only clock and replay wait', () => {
  it('clock skew and replay hold', async () => {
    expect(holdKind(err(401, 'clock_skew'))).toBe('clock');
    const s = await flushWith(err(401, 'replay', 'цю заяву вже використано'));
    expect(s.outbox).toHaveLength(1);
    expect(s.reports[0].status).toBe('queued');
  });

  it('bad device key or signature is refused with a reason', async () => {
    const e = err(401, 'support_unauthorized', 'підпис пристрою не зійшовся');
    expect(holdKind(e)).toBeNull();
    const s = await flushWith(e);
    expect(s.outbox).toEqual([]);
    expect(s.reports[0]).toMatchObject({ status: 'failed', failReason: 'device' });
    expect(s.serverPending).toBe(false);
    render(<MyReports open onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('report-refused-r1').textContent).toBe('Пристрій не підтверджено'));
  });

  it('other refusals keep the server words', async () => {
    const s = await flushWith(err(413, 'support_refused', 'знімок завеликий'));
    expect(s.reports[0]).toMatchObject({ status: 'failed', failReason: 'знімок завеликий' });
  });
});

describe('phone: no new tickets until staff can answer them', () => {
  it('phone shell hides «Повідомити про проблему» and an empty ticket list', () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{"ok":true,"status":200,"data":[]}' };
    render(<HelpCentre open onClose={vi.fn()} />);
    expect(screen.queryByTestId('help-report')).toBeNull();
    expect(screen.queryByTestId('help-tickets')).toBeNull();
    expect(screen.getByTestId('help-reports')).toBeTruthy();
  });

  it('phone shell still shows tickets the person already has', () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{"ok":true,"status":200,"data":[]}' };
    useSupportStore.setState({
      tickets: [{ id: 't1', type: 'calls', subject: 'Звук', text: 'Нема', status: 'open', unread: false, createdAt: 1, thread: [] }],
    });
    render(<HelpCentre open onClose={vi.fn()} />);
    expect(screen.queryByTestId('help-report')).toBeNull();
    expect(screen.getByTestId('help-tickets')).toBeTruthy();
  });

  it('PC keeps both', () => {
    render(<HelpCentre open onClose={vi.fn()} />);
    expect(screen.getByTestId('help-report')).toBeTruthy();
    expect(screen.getByTestId('help-tickets')).toBeTruthy();
  });
});
