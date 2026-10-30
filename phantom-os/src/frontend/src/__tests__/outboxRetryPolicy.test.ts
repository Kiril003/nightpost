import { describe, expect, it } from 'vitest';
import {
  OUTBOX_RETRY_BASE_MS,
  OUTBOX_RETRY_LIMIT,
  OUTBOX_RETRY_MAX_MS,
  outboxRetryDelayMs,
  shouldRetryOutbox,
} from '../services/outboxRetryPolicy';

describe('outbox retry policy', () => {
  it('retries only failed messages', () => {
    expect(shouldRetryOutbox({ status: 'sent', retryCount: 0 }, Date.now())).toBe(false);
    expect(shouldRetryOutbox({ status: 'queued', retryCount: 0 }, Date.now())).toBe(false);
    expect(shouldRetryOutbox({ status: 'failed', retryCount: 0 }, Date.now())).toBe(true);
  });

  it('зв\'язок повернувся — повтор одразу, навіть посеред паузи', () => {
    const justFailed = { id: 'c_1', status: 'failed' as const, retryCount: 2, lastAttemptAtMs: 1_000 };
    expect(shouldRetryOutbox(justFailed, 1_001)).toBe(false);
    expect(shouldRetryOutbox(justFailed, 1_001, true)).toBe(true);
  });

  it('перший повтор — за секунди, а не за 15 с', () => {
    // Стара політика: 15 000 мс до першого повтору. Тепер — не більше BASE.
    for (const id of ['a', 'b', 'c_123', 'c_999']) {
      const d = outboxRetryDelayMs(0, id);
      expect(d).toBeGreaterThanOrEqual(OUTBOX_RETRY_BASE_MS / 2);
      expect(d).toBeLessThanOrEqual(OUTBOX_RETRY_BASE_MS);
      expect(d).toBeLessThan(15_000);
    }
  });

  it('пауза росте, має стелю і сталий для листа розкид', () => {
    expect(outboxRetryDelayMs(1, 'x')).toBeGreaterThan(outboxRetryDelayMs(0, 'x'));
    expect(outboxRetryDelayMs(20, 'x')).toBeLessThanOrEqual(OUTBOX_RETRY_MAX_MS);
    expect(outboxRetryDelayMs(2, 'same')).toBe(outboxRetryDelayMs(2, 'same'));
    const spread = new Set(['m1', 'm2', 'm3', 'm4', 'm5', 'm6'].map((id) => outboxRetryDelayMs(3, id)));
    expect(spread.size).toBeGreaterThan(1);
  });

  it('uses the backoff between scans', () => {
    const m = { id: 'c_7', status: 'failed' as const, retryCount: 1, lastAttemptAtMs: 1_000 };
    const d = outboxRetryDelayMs(1, 'c_7');
    expect(shouldRetryOutbox(m, 1_000 + d - 1)).toBe(false);
    expect(shouldRetryOutbox(m, 1_000 + d)).toBe(true);
  });

  it('stops after the bounded retry limit — навіть після повернення зв\'язку', () => {
    const m = { status: 'failed' as const, retryCount: OUTBOX_RETRY_LIMIT, lastAttemptAtMs: 1 };
    expect(shouldRetryOutbox(m, Number.MAX_SAFE_INTEGER)).toBe(false);
    expect(shouldRetryOutbox(m, Number.MAX_SAFE_INTEGER, true)).toBe(false);
  });
});
