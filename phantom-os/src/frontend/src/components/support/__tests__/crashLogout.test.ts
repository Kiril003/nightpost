import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../../stores/authStore';
import { CRASH_STORAGE_KEY, readPendingCrash } from '../../../lib/crashReport';

afterEach(() => localStorage.clear());

describe('crash report and sign-out', () => {
  const seed = () =>
    localStorage.setItem(
      CRASH_STORAGE_KEY,
      JSON.stringify({ id: 'c1', name: 'TypeError', stackHash: 'deadbeef', appVersion: 'web', at: 1 }),
    );

  it('an explicit sign-out drops the pending crash note', () => {
    seed();
    expect(readPendingCrash()).not.toBeNull();
    useAuthStore.getState().signOut();
    expect(readPendingCrash()).toBeNull();
  });

  it('a lost session (expired token, 401) keeps the crash note for the next sign-in', () => {
    seed();
    useAuthStore.getState().clearAuth();
    expect(readPendingCrash()).not.toBeNull();
  });
});
