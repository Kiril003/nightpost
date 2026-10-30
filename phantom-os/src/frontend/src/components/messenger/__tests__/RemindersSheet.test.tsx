import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as dayApi from '../../../services/dayApi';
import { RemindersLine } from '../today/RemindersSheet';
import { notifyRemindersChanged } from '../../../services/remindersBus';

vi.mock('../../../services/dayApi', () => ({
  listPhoneReminders: vi.fn(),
  snoozePhoneReminder: vi.fn(),
  cancelPhoneReminder: vi.fn(),
}));

const mockedList = vi.mocked(dayApi.listPhoneReminders);
const mockedSnooze = vi.mocked(dayApi.snoozePhoneReminder);
const mockedCancel = vi.mocked(dayApi.cancelPhoneReminder);

describe('RemindersLine & RemindersSheet', () => {
  beforeEach(() => {
    mockedList.mockReset();
    mockedSnooze.mockReset();
    mockedCancel.mockReset();
  });

  it('renders nothing when empty, shows count for two reminders, and opens sheet with both texts on tap', async () => {
    mockedList.mockResolvedValueOnce([]);
    const emptyRender = render(<RemindersLine />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(1));
    expect(emptyRender.container.firstChild).toBeNull();
    emptyRender.unmount();

    const now = Date.now();
    mockedList.mockResolvedValue([
      { id: 'r1', text: 'Call back', atMs: now + 600_000 },
      { id: 'r2', text: 'Buy milk', atMs: now + 1_200_000 },
    ]);

    render(<RemindersLine />);
    const lineButton = await screen.findByRole('button', { name: /2/ });
    expect(lineButton).toBeTruthy();

    fireEvent.click(lineButton);
    expect(await screen.findByText('Call back')).toBeTruthy();
    expect(screen.getByText('Buy milk')).toBeTruthy();
  });

  it('snoozes and reloads on Snooze click, and cancels rows until empty state is shown', async () => {
    const now = Date.now();
    const r1 = { id: 'r1', text: 'Call back', atMs: now + 600_000 };
    const r2 = { id: 'r2', text: 'Buy milk', atMs: now + 1_200_000 };
    const r1Snoozed = { id: 'r3', text: 'Call back', atMs: now + 1_800_000 };

    mockedList
      .mockResolvedValueOnce([r1, r2])
      .mockResolvedValueOnce([r2, r1Snoozed])
      .mockResolvedValueOnce([r1Snoozed])
      .mockResolvedValueOnce([]);
    mockedSnooze.mockResolvedValueOnce(r1Snoozed);
    mockedCancel.mockResolvedValue(true);

    render(<RemindersLine />);
    const lineButton = await screen.findByRole('button', { name: /2/ });
    fireEvent.click(lineButton);

    await screen.findByText('Call back');
    fireEvent.click(screen.getAllByRole('button', { name: /^(Відкласти|Snooze)$/i })[0]);
    fireEvent.click(await screen.findByRole('radio', { name: /\+10 (хв|min)/i }));
    fireEvent.click(screen.getByRole('button', { name: /Відкласти до|Snooze until/i }));

    await waitFor(() => expect(mockedSnooze).toHaveBeenCalledWith('r1', 10));
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(2));

    const cancelButtonsAfterSnooze = await screen.findAllByRole('button', { name: /Скасувати|Cancel/i });
    fireEvent.click(cancelButtonsAfterSnooze[0]);

    await waitFor(() => expect(mockedCancel).toHaveBeenCalledWith('r2'));
    await waitFor(() => expect(screen.queryByText('Buy milk')).toBeNull());

    const lastCancelButton = await screen.findByRole('button', { name: /Скасувати|Cancel/i });
    fireEvent.click(lastCancelButton);

    await waitFor(() => expect(mockedCancel).toHaveBeenCalledWith('r3'));
    expect(await screen.findByText(/Немає нагадувань|No reminders/i)).toBeTruthy();
  });
  it('re-reads the list when a reminder is set elsewhere (event) and shows the new count', async () => {
    const now = Date.now();
    mockedList.mockResolvedValueOnce([]);
    const { container } = render(<RemindersLine />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(1));
    expect(container.firstChild).toBeNull();

    mockedList.mockResolvedValue([{ id: 'r1', text: 'Call back', atMs: now + 600_000 }]);
    act(() => notifyRemindersChanged());
    expect(await screen.findByRole('button', { name: /1/ })).toBeTruthy();

    mockedList.mockResolvedValue([
      { id: 'r1', text: 'Call back', atMs: now + 600_000 },
      { id: 'r2', text: 'Buy milk', atMs: now + 1_200_000 },
    ]);
    act(() => (window as unknown as { __phantomRemindersChanged: () => void }).__phantomRemindersChanged());
    expect(await screen.findByRole('button', { name: /2/ })).toBeTruthy();
  });

  it('re-reads the list when the list regains focus after a chat (refreshKey changes)', async () => {
    const now = Date.now();
    mockedList.mockResolvedValueOnce([{ id: 'r1', text: 'Call back', atMs: now + 600_000 }]);
    const { rerender } = render(<RemindersLine refreshKey="assistant" />);
    await screen.findByRole('button', { name: /1/ });

    mockedList.mockResolvedValue([
      { id: 'r1', text: 'Call back', atMs: now + 600_000 },
      { id: 'r2', text: 'Buy milk', atMs: now + 1_200_000 },
    ]);
    rerender(<RemindersLine refreshKey="" />);
    expect(await screen.findByRole('button', { name: /2/ })).toBeTruthy();
  });

  it('stops listening after unmount', async () => {
    mockedList.mockResolvedValue([]);
    const { unmount } = render(<RemindersLine />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(1));
    unmount();
    notifyRemindersChanged();
    await new Promise((r) => setTimeout(r, 20));
    expect(mockedList).toHaveBeenCalledTimes(1);
  });
});
