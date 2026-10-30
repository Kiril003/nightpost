/**
 * ВЗАЄМОДІЯ З РЯДКОМ: ані мертвих кнопок, ані позначок, що зникають.
 *
 * Власник: «і реакції і взаємодію з повідомленнями виправ, це калічно зараз».
 * Реакції — окремо (`reactionRoad`, `reactionReachOnPhone`). Тут три інші
 * речі, виміряні 18.09 у тому самому аркуші дій:
 *
 *  1. «AI Синтез ✦» у смузі вибору малювався ЗАВЖДИ, а обробник приходив із
 *     MessengerRoot.tsx:608 як `() => {}`. Дотик грав акорд і не робив нічого.
 *  2. «Закріпити в себе» зникало з наступним листом: `isPinned` живе лише у
 *     вкладці, а `loadMessagesForChat` заступає `c.messages` цілком тим, що
 *     дав вузол, і кличе це `__phantomMeshChanged` на кожну зміну журналу.
 *  3. «В Обране» обіцяло МІСЦЕ («Додано в «Збережене»»), якого немає:
 *     «Збережене» в реєстрі — фільтр РОЗМОВ за `circle`, куди рядки не
 *     потрапляють. Сама позначка при цьому була `useState` без диска, тобто
 *     гинула при переході в інший чат.
 *
 * Мутації (заміряні): повернути `onSynthesize` обовʼязковим і безумовним —
 * червоніє «смуга вибору»; прибрати перенос `isPinned` у
 * `loadMessagesForChat` — червоніє «закріплене переживає»; прибрати
 * `saveSetting` — червоніє «закладка лягає на диск».
 */
import { beforeEach, describe, it, expect, vi } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { storagePersistence } from '../services/storagePersistence';

describe('закріплене в себе переживає перечитування стрічки', () => {
  beforeEach(() => {
    useMessengerStore.setState({
      chats: [
        {
          id: 'chat_1',
          title: 'Оксана',
          avatar: '',
          type: 'dm',
          circle: 'work',
          unreadCount: 0,
          messages: [
            {
              id: 'm1',
              senderId: 'peer',
              senderName: 'Оксана',
              senderAvatar: '',
              timestamp: '12:00',
              type: 'text',
              text: 'Координати на завтра',
              isSelf: false,
            },
          ],
        },
      ],
      activeChatId: 'chat_1',
    } as never);
  });

  const row = {
    id: 'm1',
    conversation_id: 'chat_1',
    client_id: 'm1',
    seq: 1,
    author_id: 'peer',
    author_name: 'Оксана',
    kind: 'text',
    body: 'Координати на завтра',
    ciphertext: null,
    transport: 'direct',
    sent_at: '2026-09-18T09:00:00.000Z',
    edited_at: null,
    deleted_at: null,
  };

  const pinnedNow = () =>
    useMessengerStore.getState().chats[0].messages.find((m) => m.id === 'm1')?.isPinned;

  it('позначка не зникає з приходом стрічки від вузла', async () => {
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([row] as never);
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 } as never);

    useMessengerStore.getState().togglePinMessage('m1');
    expect(pinnedNow()).toBe(true);

    // Саме це й траплялось на кожну зміну журналу.
    await useMessengerStore.getState().loadMessagesForChat('chat_1');

    expect(pinnedNow()).toBe(true);
    vi.restoreAllMocks();
  });

  it('незакріплене не стає закріпленим саме по собі', async () => {
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([row] as never);
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 } as never);

    await useMessengerStore.getState().loadMessagesForChat('chat_1');

    expect(pinnedNow()).toBeFalsy();
    vi.restoreAllMocks();
  });
});

describe('«видалити для всіх» не бреше, коли вузол так не вміє', () => {
  beforeEach(() => {
    useMessengerStore.setState({
      chats: [
        {
          id: 'chat_1',
          title: 'Оксана',
          avatar: '',
          type: 'dm',
          circle: 'work',
          unreadCount: 0,
          messages: [
            {
              id: 'm1',
              senderId: 'me',
              senderName: 'Я',
              senderAvatar: '',
              timestamp: '12:00',
              type: 'text',
              text: 'Помилково',
              isSelf: true,
            },
          ],
        },
      ],
      activeChatId: 'chat_1',
    } as never);
  });

  /**
   * На телефоні обидві ручки видалення стирають рядок ЛОКАЛЬНО і чесно
   * відповідають `for_everyone: false` (WebMessengerBridge.kt:494 і :1151).
   * Поверхня казала «Повідомлення видалено» в усіх випадках, а в аркуші
   * «для всіх» стоїть УЖЕ ВИБРАНИМ — тобто типовий дотик обіцяв стерти в
   * співрозмовника й стирав лише в себе.
   */
  it('вузол стер лише тут — це сказано словами', async () => {
    vi.spyOn(messengerApi, 'deleteMessage').mockResolvedValue({
      deleted: true, for_everyone: false, blobs: 0, frame: 'local',
    } as never);

    const caveat = await useMessengerStore.getState().deleteMessage('m1', true);

    expect(caveat).toBe('Стерто лише в цьому апараті — у співрозмовника лист лишився.');
    vi.restoreAllMocks();
  });

  it('вузол стер у всіх — застереження немає', async () => {
    vi.spyOn(messengerApi, 'deleteMessage').mockResolvedValue({
      deleted: true, for_everyone: true, blobs: 0, frame: 'sent',
    } as never);

    expect(await useMessengerStore.getState().deleteMessage('m1', true)).toBeNull();
    vi.restoreAllMocks();
  });

  it('«лише в себе» не отримує застереження про співрозмовника', async () => {
    vi.spyOn(messengerApi, 'deleteMessage').mockResolvedValue({
      deleted: true, for_everyone: false, blobs: 0, frame: 'local',
    } as never);

    // Тут «for_everyone: false» — це САМЕ ТЕ, що просили.
    expect(await useMessengerStore.getState().deleteMessage('m1', false)).toBeNull();
    vi.restoreAllMocks();
  });

  it('вузол відмовив — приїжджає його речення, а не тиша', async () => {
    vi.spyOn(messengerApi, 'deleteMessage').mockRejectedValue(
      Object.assign(new Error('листа немає'), { code: 'no_message' }),
    );

    expect(await useMessengerStore.getState().deleteMessage('m1', true)).toBe('листа немає');
    vi.restoreAllMocks();
  });
});

describe('закладка лягає на диск цього апарата', () => {
  it('поставлена закладка читається назад, знята зникає з файла', () => {
    // Дзеркало того, що робить `handleToggleBookmark`: сховище одне, і саме
    // його перечитує наступний монтаж ChatArea.
    storagePersistence.saveSetting('messageBookmarks', { m1: true });
    expect(
      storagePersistence.loadSetting<Record<string, boolean>>('messageBookmarks', {}),
    ).toEqual({ m1: true });

    storagePersistence.saveSetting('messageBookmarks', {});
    // Знята закладка ЗНИКАЄ, а не стає `false`: інакше сховище росло б
    // списком того, чого в ньому немає.
    expect(
      storagePersistence.loadSetting<Record<string, boolean>>('messageBookmarks', {}),
    ).toEqual({});
  });
});
