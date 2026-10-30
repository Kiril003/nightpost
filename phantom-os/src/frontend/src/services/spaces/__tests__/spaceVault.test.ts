import { describe, it, expect, beforeEach } from 'vitest';
import type { SpaceEvent } from '@shared/types/space';
import {
  appendSpaceEvents,
  spacesOf,
  spaceLog,
  putTask,
  tasksOf,
  putSwap,
  swapsOf,
  forgetProfileSpaces,
  SPACES_PREFIX,
  WORK_PREFIX,
} from '../spaceVault';
import { newTask } from '../../work/taskFlow';
import { useProfiles } from '../../../stores/profilesStore';

const T0 = Date.UTC(2026, 9, 22, 9, 0);

const create = (id: string, actor = 'me'): SpaceEvent => ({
  id: `c-${id}`,
  spaceId: id,
  actor,
  lamport: 1,
  at: T0,
  body: { t: 'space.create', name: `Простір ${id}`, icon: null, template: 'team' },
});

const task = (id: string, chatId: string, assignee: string | null = null) => ({
  ...newTask({ id, chatId, spaceId: 'w1', sourceMsg: null, title: `Задача ${id}`, createdBy: 'me', createdAt: T0 }, { l: 1, by: 'me' }),
  assignee,
});

beforeEach(() => localStorage.clear());

describe('сховище просторів — окреме для кожного профілю', () => {
  it('простір видно лише в профілі, де його створено', () => {
    appendSpaceEvents('work', [create('w1')], T0);
    appendSpaceEvents('home', [create('h1')], T0);
    expect(spacesOf('work').map((s) => s.id)).toEqual(['w1']);
    expect(spacesOf('home').map((s) => s.id)).toEqual(['h1']);
  });

  it('биту й повторну подію не пише; час отримання ставить сховище', () => {
    const forged = { ...create('w1'), receivedAt: 1 };
    const out = appendSpaceEvents('work', [forged, create('w1'), { junk: true }], T0 + 5);
    expect(out).toEqual({ added: 1, dropped: 2 });
    expect(spaceLog('work', 'w1')[0].receivedAt).toBe(T0 + 5);
  });

  it('переживає перезавантаження і не падає від битого запису', () => {
    appendSpaceEvents('work', [create('w1')], T0);
    expect(spacesOf('work')).toHaveLength(1);
    localStorage.setItem(`${SPACES_PREFIX}work`, '{not json');
    expect(spacesOf('work')).toEqual([]);
    expect(appendSpaceEvents('work', [create('w2')], T0).added).toBe(1);
  });

  it('задачі зливаються за id, фільтр за розмовою й виконавцем', () => {
    putTask('work', task('t1', 'c1', 'ivo'));
    putTask('work', task('t2', 'c2'));
    const renamed = { ...task('t1', 'c1', 'ivo'), title: 'Новий заголовок', clock: { ...task('t1', 'c1').clock, title: { l: 9, by: 'ivo' } } };
    putTask('work', renamed);
    expect(tasksOf('work', { chatId: 'c1' }).map((t) => t.title)).toEqual(['Новий заголовок']);
    expect(tasksOf('work', { assignee: 'ivo' })).toHaveLength(1);
    expect(tasksOf('work')).toHaveLength(2);
    expect(putTask('work', { id: 'x' })).toBeNull();
    expect(tasksOf('home')).toEqual([]);
  });

  it('обміни зберігаються й зливаються', () => {
    const s = {
      id: 'sw1', shiftA: 'a', shiftB: null, from: 'jana', to: 'petr', state: 'OFFERED' as const,
      leadApproves: true, expiresAt: T0 + 9e6, log: [{ state: 'OFFERED' as const, by: 'jana', at: T0 }],
    };
    putSwap('work', s);
    putSwap('work', { ...s, state: 'ACCEPTED', log: [...s.log, { state: 'ACCEPTED', by: 'petr', at: T0 + 1 }] });
    expect(swapsOf('work').map((x) => x.state)).toEqual(['ACCEPTED']);
  });
});

describe('видалення профілю забирає його простори й роботу', () => {
  it('після remove() у сховищі не лишається нічого від профілю', () => {
    const work = useProfiles.getState().addFromTemplate('work', 'Робота');
    appendSpaceEvents(work.id, [create('w1')], T0);
    putTask(work.id, task('t1', 'c1'));
    const other = useProfiles.getState().profiles.find((p) => p.id !== work.id)!;
    appendSpaceEvents(other.id, [create('h1')], T0);

    expect(useProfiles.getState().remove(work.id)).toBe(true);

    expect(localStorage.getItem(`${SPACES_PREFIX}${work.id}`)).toBeNull();
    expect(localStorage.getItem(`${WORK_PREFIX}${work.id}`)).toBeNull();
    expect(spacesOf(other.id)).toHaveLength(1);
  });

  it('тимчасовий профіль, що минув, забирає свої простори', () => {
    const temp = useProfiles.getState().addFromTemplate('temp', 'На день');
    appendSpaceEvents(temp.id, [create('t1')], T0);
    useProfiles.getState().sweepExpired(temp.createdAt + 2 * 864e5);
    expect(useProfiles.getState().profiles.some((p) => p.id === temp.id)).toBe(false);
    expect(localStorage.getItem(`${SPACES_PREFIX}${temp.id}`)).toBeNull();
  });

  it('forgetProfileSpaces чіпає лише свій профіль', () => {
    appendSpaceEvents('a', [create('x')], T0);
    appendSpaceEvents('b', [create('y')], T0);
    forgetProfileSpaces('a');
    expect(spacesOf('a')).toEqual([]);
    expect(spacesOf('b')).toHaveLength(1);
  });
});
