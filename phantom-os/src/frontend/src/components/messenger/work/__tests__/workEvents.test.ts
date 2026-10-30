import { describe, expect, it } from 'vitest';
import { parseTag, reduceWork, shiftHours, weekLoad, withTag } from '../../../../services/workEvents';
import { IVO, MARA, ME, PETRA, msg, shiftMsg, taskMsg, workChat } from './workFixtures';

const state = (messages: ReturnType<typeof msg>[]) => reduceWork(workChat(messages), ME, 'Me');

describe('workEvents: рядки з позначкою', () => {
  it('позначка лягає в кінець і читається назад, а перший рядок лишається людським', () => {
    const text = withTag('☑ Cap image → Mara', 'task', { id: 't1', to: 'a b]' });
    expect(text.split('\n')[0]).toBe('☑ Cap image → Mara');
    expect(parseTag(text)).toMatchObject({ kind: 'task', f: { id: 't1', to: 'a b]' }, line: '☑ Cap image → Mara' });
    expect(parseTag('звичайний лист')).toBeNull();
  });

  it('завдання: статус міняють лише автор, виконавець чи лід', () => {
    const s = state([
      taskMsg('t1', 'Cap image', MARA),
      msg('s2', MARA, withTag('x', 'task-status', { id: 't1', s: 'doing' })),
      msg('s1', 'stranger', withTag('x', 'task-status', { id: 't1', s: 'done' })),
    ]);
    expect(s.tasks).toHaveLength(1);
    expect(s.tasks[0]).toMatchObject({ title: 'Cap image', status: 'doing', assigneeName: 'Mara' });
  });

  it('зміни: вільну беруть, обмін діє лише після відповіді того, кому запропонували, і ліда', () => {
    const base = [shiftMsg('a', ME, '2026-10-24', '10:00', '18:00'), shiftMsg('b', MARA, '2026-10-25', '10:00', '18:00')];
    const offer = msg('w', ME, withTag('swap', 'swap', { id: 'w1', a: 'a', b: 'b', to: MARA }), true);
    let s = state([...base, offer]);
    expect(s.swaps[0]).toMatchObject({ state: 'offered', lead: PETRA });
    s = state([...base, offer, msg('x1', IVO, withTag('x', 'swap-ack', { id: 'w1', s: 'accepted' }))]);
    expect(s.swaps[0].state).toBe('offered');
    s = state([...base, offer, msg('x2', MARA, withTag('x', 'swap-ack', { id: 'w1', s: 'accepted' }))]);
    expect(s.swaps[0].state).toBe('accepted');
    s = state([...base, offer, msg('x2', MARA, withTag('x', 'swap-ack', { id: 'w1', s: 'accepted' })), msg('x3', PETRA, withTag('x', 'swap-ack', { id: 'w1', s: 'approved' }))]);
    expect(s.swaps[0].state).toBe('applied');
    expect(s.shifts.find((x) => x.id === 'a')?.who).toBe(MARA);
    expect(s.shifts.find((x) => x.id === 'b')?.who).toBe(ME);
  });

  it('годи й відпочинок рахуються зі справжніх змін, нічні переходять північ', () => {
    expect(shiftHours({ from: '22:00', to: '06:00' })).toBe(8);
    const s = state([shiftMsg('a', ME, '2026-10-20', '10:00', '18:00'), shiftMsg('b', ME, '2026-10-21', '06:00', '14:00')]);
    expect(weekLoad(s.shifts, ME, ['2026-10-20', '2026-10-21'])).toEqual({ hours: 16, minRestH: 12 });
  });
});

describe('workEvents: показ і збої', () => {
  it('у бульбашці лишається людський рядок; невідправлений лист не міняє стан', async () => {
    const { stripWorkTag } = await import('../../../../services/workEvents');
    expect(stripWorkTag(withTag('☑ A → Mara', 'task', { id: 't1', to: MARA }))).toBe('☑ A → Mara');
    expect(stripWorkTag('hi')).toBe('hi');
    const bad = { ...msg('s', MARA, withTag('x', 'task-status', { id: 't1', s: 'done' })), status: 'failed' } as never;
    expect(state([taskMsg('t1', 'A', MARA), bad]).tasks[0].status).toBe('todo');
  });
});
