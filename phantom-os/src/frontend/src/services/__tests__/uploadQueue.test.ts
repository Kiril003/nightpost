import { describe, expect, it } from 'vitest';
import { createTaskQueue, QueueCancelled, UPLOAD_CONCURRENCY, attachmentUploads } from '../uploadQueue';

const deferred = () => {
  let resolve!: () => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<void>((r, j) => { resolve = r; reject = j; });
  return { promise, resolve, reject };
};
const tick = () => new Promise((r) => setTimeout(r, 0));

describe('createTaskQueue', () => {
  it('не більше трьох одночасно, решта — у порядку надходження', async () => {
    const q = createTaskQueue(3);
    const gates = Array.from({ length: 5 }, deferred);
    const started: number[] = [];
    let peak = 0;
    const all = gates.map((g, i) =>
      q.run(async () => {
        started.push(i);
        peak = Math.max(peak, q.active());
        await g.promise;
        return i;
      }),
    );
    await tick();
    expect(started).toEqual([0, 1, 2]);
    expect(q.waiting()).toBe(2);
    gates[1].resolve();
    await tick();
    expect(started).toEqual([0, 1, 2, 3]);
    gates.forEach((g) => g.resolve());
    expect(await Promise.all(all)).toEqual([0, 1, 2, 3, 4]);
    expect(peak).toBe(3);
    expect(q.active()).toBe(0);
  });

  it('падіння задачі звільняє місце і не валить сусідів', async () => {
    const q = createTaskQueue(1);
    const a = q.run(async () => { throw new Error('бах'); });
    const b = q.run(async () => 'ok');
    await expect(a).rejects.toThrow('бах');
    await expect(b).resolves.toBe('ok');
  });

  it('скасована до старту задача не стартує взагалі', async () => {
    const q = createTaskQueue(1);
    const g = deferred();
    let cancelled = false;
    let ran = false;
    const first = q.run(() => g.promise);
    const second = q.run(async () => { ran = true; }, { cancelled: () => cancelled });
    cancelled = true;
    g.resolve();
    await first;
    await expect(second).rejects.toBeInstanceOf(QueueCancelled);
    expect(ran).toBe(false);
  });

  it('спільна черга вкладень — на три місця', () => {
    expect(UPLOAD_CONCURRENCY).toBe(3);
    expect(attachmentUploads.active()).toBe(0);
  });
});
