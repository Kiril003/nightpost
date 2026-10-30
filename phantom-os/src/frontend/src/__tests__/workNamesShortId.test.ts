/**
 * Зміни в робочій групі: «Взяти: 5102·2e11» — коротке id замість імені
 * (обхід A52 28.10). Заглушка зі списку учасників не перекриває імʼя профілю
 * й підпис листа.
 */
import { describe, expect, it } from 'vitest';
import { reduceWork, withTag } from '../services/workEvents';
import type { Chat, Message } from '../types/messenger';

const msg = (over: Partial<Message>): Message =>
  ({ id: 'm1', senderId: 'peer', senderName: 'Оля', senderAvatar: '', timestamp: '09:00', type: 'text', text: '', ...over }) as Message;

describe('імена в робочому стані', () => {
  it('моє імʼя з профілю, а не «5102·2e11» зі списку учасників', () => {
    const chat = { messages: [], members: [{ id: 'me', name: '5102·2e11' }] } as unknown as Chat;
    expect(reduceWork(chat, 'me', 'Кирило').names.me).toBe('Кирило');
  });

  it('чуже імʼя з підпису листа, коли в списку лише коротке id', () => {
    const chat = {
      members: [{ id: 'peer', name: '4330·b161' }],
      messages: [msg({ text: withTag('Оля · пн, 26 · 09:00–17:00', 'shift', { id: 's1', who: 'peer', day: '2026-10-26', from: '09:00', to: '17:00', role: '' }) })],
    } as unknown as Chat;
    expect(reduceWork(chat, 'me', 'Кирило').names.peer).toBe('Оля');
  });

  it('справжнє імʼя учасника лишається', () => {
    const chat = { messages: [], members: [{ id: 'peer', name: 'Марко' }] } as unknown as Chat;
    expect(reduceWork(chat, 'me', 'Кирило').names.peer).toBe('Марко');
  });

  it('без імені профілю лишається те, що є', () => {
    const chat = { messages: [], members: [{ id: 'me', name: '5102·2e11' }] } as unknown as Chat;
    expect(reduceWork(chat, 'me', '').names.me).toBe('5102·2e11');
  });
});
