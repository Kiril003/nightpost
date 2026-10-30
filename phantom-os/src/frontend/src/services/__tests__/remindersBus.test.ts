import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { REMINDERS_CHANGED, notifyRemindersChanged } from '../remindersBus';
import { addReminder, disarmReminders } from '../letterReminders';

vi.mock('../api', () => ({ request: vi.fn() }));

describe('remindersBus', () => {
  const seen = vi.fn();

  beforeEach(() => {
    seen.mockReset();
    window.addEventListener(REMINDERS_CHANGED, seen);
    localStorage.clear();
  });

  afterEach(() => {
    window.removeEventListener(REMINDERS_CHANGED, seen);
    disarmReminders();
    delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('notifyRemindersChanged dispatches the window event', () => {
    notifyRemindersChanged();
    expect(seen).toHaveBeenCalledTimes(1);
  });

  it('the native hook __phantomRemindersChanged dispatches the same event', () => {
    const hook = (window as unknown as { __phantomRemindersChanged?: () => void }).__phantomRemindersChanged;
    expect(typeof hook).toBe('function');
    hook!();
    expect(seen).toHaveBeenCalledTimes(1);
  });

  it('a reminder added to the phone table notifies', async () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '' };
    const where = await addReminder({ at: Date.now() + 60_000, who: 'A', text: 'x', chatId: 'c' });
    expect(where).toBe('phone');
    expect(seen).toHaveBeenCalledTimes(1);
  });

  it('a reminder kept on the page notifies too', async () => {
    const where = await addReminder({ at: Date.now() + 60_000, who: 'A', text: 'x', chatId: 'c' });
    expect(where).toBe('page');
    expect(seen).toHaveBeenCalledTimes(1);
  });
});
