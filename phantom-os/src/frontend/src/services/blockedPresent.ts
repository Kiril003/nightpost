import { useEffect, useSyncExternalStore } from 'react';
import { messengerApi } from './messengerApi';

// Локальна перевірка: нікуди нічого не надсилає.
export function blockedPresent(
  memberIds: readonly string[],
  blocked: ReadonlySet<string> | readonly string[],
): boolean {
  if (memberIds.length === 0) return false;
  const set: ReadonlySet<string> = isSet(blocked) ? blocked : new Set(blocked);
  if (set.size === 0) return false;
  return memberIds.some((id) => set.has(id));
}

function isSet(v: ReadonlySet<string> | readonly string[]): v is ReadonlySet<string> {
  return typeof (v as ReadonlySet<string>).has === 'function';
}

let ids: ReadonlySet<string> = new Set();
const listeners = new Set<() => void>();
let inFlight: Promise<void> | null = null;

function normalizeIds(raw: unknown): string[] {
  const list = Array.isArray(raw)
    ? raw
    : raw && typeof raw === 'object' && Array.isArray((raw as { blocked?: unknown }).blocked)
      ? (raw as { blocked: unknown[] }).blocked
      : [];
  return list.filter((item): item is string => typeof item === 'string' && item.length > 0);
}

export const subscribeBlocked = (fn: () => void): (() => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};

export const getBlockedSnapshot = (): ReadonlySet<string> => ids;

// Одночасні виклики ділять один запит; наступний виклик читає свіжий список.
export function loadBlocked(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = (async () => {
    try {
      const next = new Set(normalizeIds(await messengerApi.listBlocked()));
      if (next.size !== ids.size || [...next].some((id) => !ids.has(id))) {
        ids = next;
        listeners.forEach((l) => l());
      }
    } catch {
      // Немає списку — лишаємо попередній.
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

export function resetBlockedForTests(): void {
  ids = new Set();
  inFlight = null;
}

export function useBlockedIds(enabled = true): ReadonlySet<string> {
  const snapshot = useSyncExternalStore(subscribeBlocked, getBlockedSnapshot);
  useEffect(() => {
    if (enabled) void loadBlocked();
  }, [enabled]);
  return snapshot;
}
