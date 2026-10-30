import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => vi.fn());
vi.mock('../services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  request: requestMock,
}));

import { messengerApi } from '../services/messengerApi';
import { __resetChatKeep, chatKeep } from '../services/chatKeep';

beforeEach(() => {
  __resetChatKeep();
  requestMock.mockReset();
});

describe('B1: нова розмова без листів лишається в списку на кожному шляху', () => {
  it('створення розмови', async () => {
    requestMock.mockResolvedValue({ id: 'c1' });
    await messengerApi.createConversation({ title: 'А', kind: 'dm', contact_id: 'x' });
    expect(chatKeep.has('c1')).toBe(true);
  });

  it('прийняття запиту (QR, запрошення)', async () => {
    requestMock.mockResolvedValue({ conversation_id: 'c2', contact_id: 'x' });
    await messengerApi.acceptRequest('r1');
    expect(chatKeep.has('c2')).toBe(true);
  });

  it('чат за іменем користувача', async () => {
    requestMock.mockResolvedValue({ id: 'c3' });
    await messengerApi.startChatByUsername('anna');
    expect(chatKeep.has('c3')).toBe(true);
  });
});
