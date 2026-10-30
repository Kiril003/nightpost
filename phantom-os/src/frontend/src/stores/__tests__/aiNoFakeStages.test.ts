/**
 * Відповідь помічника на ПК — без вигаданих етапів.
 *
 * Доти стор клав порожню бульбашку «Сприймаю контекст…» і таймерами (350 і
 * 750 мс) міняв її на «Пригадую памʼять…» / «Формую відповідь…» — без жодної
 * події від моделі. Тут доводиться: поки запит у дорозі, є лише справжній
 * стан «відповідає» (typingStatus), жодного листа з `thinking`, і час сам
 * по собі нічого не міняє.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessengerStore } from '../messengerStore';
import { messengerApi } from '../../services/messengerApi';
import { chatApi } from '../../services/api';

describe('помічник: лише справжній стан', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'local' } as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_phantom_assistant', title: 'Nightpost', avatar: '', type: 'phantom', circle: 'all', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_phantom_assistant',
      typingStatus: {},
    });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('поки модель думає — «відповідає», без бульбашки з етапами; таймер нічого не вигадує', async () => {
    let answer!: (s: string) => void;
    vi.spyOn(chatApi, 'sendMessage').mockImplementation(
      () => new Promise((r) => { answer = (content) => r({ message: { content }, session_id: 's1' } as never); }),
    );

    const sending = useMessengerStore.getState().sendMessage('привіт') as unknown as Promise<void>;
    await vi.advanceTimersByTimeAsync(2000);

    const chat = () => useMessengerStore.getState().chats[0];
    expect(chat().messages.some((m) => m.thinking)).toBe(false);
    expect(chat().messages.filter((m) => m.senderId === 'assistant_phantom')).toEqual([]);
    expect(useMessengerStore.getState().typingStatus.chat_phantom_assistant).toBe('Nightpost відповідає…');

    answer('Ось відповідь');
    await vi.advanceTimersByTimeAsync(10);
    await sending;

    const reply = chat().messages.find((m) => m.senderId === 'assistant_phantom');
    expect(reply?.text).toBe('Ось відповідь');
    expect(reply?.thinking).toBeUndefined();
    expect(useMessengerStore.getState().typingStatus.chat_phantom_assistant).toBeNull();
  });
});
