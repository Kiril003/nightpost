import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, request } from '../api';
import { cancelPhoneReminder, dayApi, listPhoneReminders, snoozePhoneReminder } from '../dayApi';

vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  request: vi.fn(),
}));

const mockedRequest = vi.mocked(request);

describe('dayApi', () => {
  beforeEach(() => {
    mockedRequest.mockReset();
  });

  it('listPhoneReminders maps at_ms to atMs, sorts ascending, and returns [] on failure', async () => {
    mockedRequest.mockResolvedValueOnce([
      { id: 'r2', text: 'Later', at_ms: 2000 },
      { id: 'r1', text: 'Sooner', at_ms: 1000 },
    ]);

    const items = await listPhoneReminders();
    expect(mockedRequest).toHaveBeenCalledWith('GET', '/messenger/reminders');
    expect(items).toEqual([
      { id: 'r1', text: 'Sooner', atMs: 1000 },
      { id: 'r2', text: 'Later', atMs: 2000 },
    ]);

    mockedRequest.mockRejectedValueOnce(new Error('network'));
    expect(await listPhoneReminders()).toEqual([]);
  });

  it('cancelPhoneReminder issues DELETE /messenger/reminders/<id> and returns false on failure', async () => {
    mockedRequest.mockResolvedValueOnce({ removed: true });
    expect(await cancelPhoneReminder('r/1')).toBe(true);
    expect(mockedRequest).toHaveBeenCalledWith('DELETE', '/messenger/reminders/r%2F1');

    mockedRequest.mockRejectedValueOnce(new Error('gone'));
    expect(await cancelPhoneReminder('r/1')).toBe(false);
  });

  it('snoozePhoneReminder issues POST /messenger/reminders/<id>/snooze with minutes and returns null on failure', async () => {
    mockedRequest.mockResolvedValueOnce({ id: 'r2', at_ms: 600_000 });
    const snoozed = await snoozePhoneReminder('r1', 10);
    expect(mockedRequest).toHaveBeenCalledWith('POST', '/messenger/reminders/r1/snooze', { minutes: 10 });
    expect(snoozed).toEqual({ id: 'r2', text: '', atMs: 600_000 });

    mockedRequest.mockRejectedValueOnce(new Error('failed'));
    expect(await snoozePhoneReminder('r1', 10)).toBeNull();
  });
});

describe('dayApi (K3 B3): /messenger/day', () => {
  beforeEach(() => {
    mockedRequest.mockReset();
  });

  it('today: GET /messenger/day; snake_case → DayItem; із дроту — лише promise/reminder/event', async () => {
    mockedRequest.mockResolvedValueOnce({
      now_ms: 5,
      calendar: 'denied',
      items: [
        { id: 'p1', kind: 'promise', title: 'Olia: photo', at_ms: 1000, end_ms: null, all_day: false, peer_id: 'peer-1', overdue: true },
        { id: 'e1', kind: 'event', title: '', at_ms: 2000, end_ms: 3000, all_day: true, peer_id: null, overdue: false },
        { id: 'pl', kind: 'plan', title: 'native plan', at_ms: 2500, end_ms: null, all_day: false, peer_id: null, overdue: false },
        { id: 'r1', kind: 'reminder', title: 'Call mum', at_ms: 2600, all_day: false, overdue: false },
        { id: 'bad', kind: 'promise', title: 'no time' },
      ],
    });
    const s = await dayApi.today();
    expect(mockedRequest).toHaveBeenCalledWith('GET', '/messenger/day');
    expect(s).toEqual({
      calendar: 'denied',
      items: [
        { id: 'p1', kind: 'promise', title: 'Olia: photo', atMs: 1000, endMs: null, allDay: false, peerId: 'peer-1', overdue: true },
        { id: 'e1', kind: 'event', title: '', atMs: 2000, endMs: 3000, allDay: true, peerId: null, overdue: false },
        { id: 'r1', kind: 'reminder', title: 'Call mum', atMs: 2600, endMs: null, allDay: false, peerId: null, overdue: false },
      ],
    });

    mockedRequest.mockResolvedValueOnce({ now_ms: 5, calendar: 'granted', items: [] });
    expect(await dayApi.today()).toEqual({ calendar: 'granted', items: [] });
  });

  it('today: 404 і 501 (ПК, старий APK) → null; інші збої летять далі', async () => {
    mockedRequest.mockRejectedValueOnce(new ApiError(404, 'not_found', ''));
    expect(await dayApi.today()).toBeNull();
    mockedRequest.mockRejectedValueOnce(new ApiError(501, 'not_implemented', ''));
    expect(await dayApi.today()).toBeNull();
    mockedRequest.mockRejectedValueOnce(new ApiError(500, 'day_error', ''));
    await expect(dayApi.today()).rejects.toBeInstanceOf(ApiError);
  });

  it('дії: літеральні метод і шлях (WebBundleRoutesReachTest), id закодовано; збій летить далі', async () => {
    mockedRequest.mockResolvedValue({});
    await dayApi.done('p/1');
    expect(mockedRequest).toHaveBeenLastCalledWith('POST', '/messenger/day/promises/p%2F1/done');
    await dayApi.drop('p/1');
    expect(mockedRequest).toHaveBeenLastCalledWith('DELETE', '/messenger/day/promises/p%2F1');
    await dayApi.removeReminder('r/1');
    expect(mockedRequest).toHaveBeenLastCalledWith('DELETE', '/messenger/reminders/r%2F1');

    mockedRequest.mockResolvedValueOnce({ id: 'r2', at_ms: 9_000 });
    expect(await dayApi.snooze('r/1', 60)).toEqual({ id: 'r2', atMs: 9_000 });
    expect(mockedRequest).toHaveBeenLastCalledWith('POST', '/messenger/reminders/r%2F1/snooze', { minutes: 60 });

    mockedRequest.mockResolvedValueOnce({ asked: true, granted: false });
    expect(await dayApi.askCalendar()).toEqual({ asked: true, granted: false });
    expect(mockedRequest).toHaveBeenLastCalledWith('POST', '/messenger/day/calendar/ask');

    mockedRequest.mockRejectedValueOnce(new ApiError(404, 'not_found', ''));
    await expect(dayApi.done('p1')).rejects.toBeInstanceOf(ApiError);
  });

  it('у джерелі виклики мають форму request(\'METHOD\', \'/messenger/…\') — їх бачить WebBundleRoutesReachTest', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    const src = fs.readFileSync(path.resolve(__dirname, '../dayApi.ts'), 'utf8');
    for (const shape of [
      /\(\s*'GET',\s*'\/messenger\/day'\s*\)/,
      /\(\s*'POST',\s*`\/messenger\/day\/promises\/\$\{encodeURIComponent\(id\)\}\/done`/,
      /\(\s*'DELETE',\s*`\/messenger\/day\/promises\/\$\{encodeURIComponent\(id\)\}`/,
      /\(\s*'POST',\s*'\/messenger\/day\/calendar\/ask'/,
    ]) {
      expect(src).toMatch(shape);
    }
  });
});
