import { describe, expect, it } from 'vitest';
import {
  balanceWith,
  nextPlanIn,
  openTasksIn,
  sharedMomentsWith,
  unitsText,
  type Moment,
} from '../services/peerBetween';
import type { Message } from '../types/messenger';

const makeTaskListMessage = (id: string, tasks: Array<{ id: string; title: string; completed: boolean; dueDate?: string; assigneeName?: string }>): Message => ({
  id,
  senderId: 'peer-1',
  senderName: 'Оксана',
  senderAvatar: '',
  text: '',
  timestamp: '2026-04-19T10:00:00.000Z',
  isSelf: false,
  type: 'task-list',
  taskListData: {
    title: 'Справи',
    tasks,
  },
});

describe('peerBetween profile-33', () => {
  it('sets dueAt for an ISO dueDate and null for free-text due strings', () => {
    const msg = makeTaskListMessage('m-1', [
      { id: 't-iso', title: 'Купити каву', completed: false, dueDate: '2026-05-10' },
      { id: 't-words', title: 'Зателефонувати', completed: false, dueDate: 'завтра ввечері' },
    ]);

    const out = openTasksIn([msg], 'Іван', 'Оксана');
    expect(out).toHaveLength(2);
    expect(out[0].title).toBe('Купити каву');
    expect(out[0].dueAt).toBe(new Date('2026-05-10').getTime());
    expect(out[1].title).toBe('Зателефонувати');
    expect(out[1].dueAt).toBeNull();
  });

  it('nextPlanIn picks nearest future due, ignores yesterday, keeps today date-only, and returns null without due dates', () => {
    const now = new Date(2026, 3, 19, 14, 30, 0).getTime();

    const msg = makeTaskListMessage('m-2', [
      { id: 't-yesterday', title: 'Вчорашня справа', completed: false, dueDate: '2026-04-18' },
      { id: 't-later', title: 'Через тиждень', completed: false, dueDate: '2026-04-26' },
      { id: 't-tomorrow', title: 'Завтра зранку', completed: false, dueDate: '2026-04-20' },
    ]);
    const tasks = openTasksIn([msg], 'Іван', 'Оксана');
    expect(nextPlanIn(tasks, now)?.title).toBe('Завтра зранку');

    const todayMsg = makeTaskListMessage('m-3', [
      { id: 't-today', title: 'Сьогоднішня справа', completed: false, dueDate: '2026-04-19' },
    ]);
    const todayTasks = openTasksIn([todayMsg], 'Іван', 'Оксана');
    expect(nextPlanIn(todayTasks, now)?.title).toBe('Сьогоднішня справа');

    const noDueMsg = makeTaskListMessage('m-4', [
      { id: 't-none', title: 'Без строку', completed: false, dueDate: 'завтра ввечері' },
    ]);
    const noDueTasks = openTasksIn([noDueMsg], 'Іван', 'Оксана');
    expect(nextPlanIn(noDueTasks, now)).toBeNull();
  });

  it('balanceWith nets open transfers, ignores third parties, separates pending SOL, and drops zero nets', () => {
    const ledger = {
      transfers: [
        { from: 'me', to: 'peer-1', token: 'USDC' as const, units: 5_000_000n, state: 'open' as const },
        { from: 'peer-1', to: 'me', token: 'USDC' as const, units: 2_000_000n, state: 'open' as const },
        { from: 'other', to: 'me', token: 'USDC' as const, units: 9_000_000n, state: 'open' as const },
        { from: 'peer-1', to: 'me', token: 'SOL' as const, units: 500_000_000n, state: 'pending' as const },
      ],
      names: {},
      bills: [],
    };

    expect(balanceWith(ledger, 'me', 'peer-1')).toEqual([
      { token: 'USDC', units: 3_000_000n, dir: 'owe', pending: false },
      { token: 'SOL', units: 500_000_000n, dir: 'owed', pending: true },
    ]);

    const evenLedger = {
      transfers: [
        { from: 'me', to: 'peer-1', token: 'USDC' as const, units: 4_000_000n, state: 'open' as const },
        { from: 'peer-1', to: 'me', token: 'USDC' as const, units: 4_000_000n, state: 'open' as const },
      ],
      names: {},
      bills: [],
    };
    expect(balanceWith(evenLedger, 'me', 'peer-1')).toEqual([]);
  });

  it('formats token units without trailing zeros', () => {
    expect(unitsText(1_500_000n, 'USDC')).toBe('1.5 USDC');
    expect(unitsText(3_000_000n, 'USDC')).toBe('3 USDC');
    expect(unitsText(250_000_000n, 'SOL')).toBe('0.25 SOL');
  });

  it('sharedMomentsWith keeps moments with both self and peer and sorts newest first', () => {
    const moments: Moment[] = [
      { id: 'mom-1', authorId: 'me', participants: ['peer-1'], createdAt: 1_000 },
      { id: 'mom-2', authorId: 'peer-1', participants: ['someone-else'], createdAt: 3_000 },
      { id: 'mom-3', authorId: 'peer-1', participants: ['me', 'peer-1'], createdAt: 2_000 },
    ];

    expect(sharedMomentsWith(moments, 'me', 'peer-1').map((m) => m.id)).toEqual(['mom-3', 'mom-1']);
  });
});
