import { beforeEach, describe, expect, it, vi } from 'vitest';
import { appendSpaceEvents, spaceLog } from '../spaceVault';

const ev = (spaceId: string) => ({
  id: `${spaceId}-c`,
  spaceId,
  actor: 'yana',
  lamport: 1,
  at: 1,
  body: { t: 'space.create', name: spaceId, icon: null, template: 'club' },
});

vi.mock('../../phonePort', () => ({ onPhone: () => true }));
vi.mock('../../messengerApi', () => ({
  messengerApi: {
    listConversations: async () => [
      { id: 'work', settings: { kind: 'space' } },
      { id: 'club', settings: { kind: 'space' } },
    ],
    spaceEvents: async (id: string) => ({
      events: [{ actor: 'yana', event: JSON.stringify(ev(id)) }],
    }),
  },
}));

beforeEach(() => localStorage.clear());

describe('синхронізація просторів не змішує профілі', () => {
  it('простір іншого профілю в активний не тягнеться', async () => {
    appendSpaceEvents('work-profile', [ev('work')], 1);
    appendSpaceEvents('personal', [ev('club')], 1);
    const { syncSpaces } = await import('../spaceWire');

    await syncSpaces('personal');

    expect(spaceLog('personal', 'work')).toHaveLength(0);
    expect(spaceLog('personal', 'club')).toHaveLength(1);
  });
});
