import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { messengerNetworkEngine } from '../services/messengerNetworkEngine';

/**
 * ДОРОГА ПОЗНАЧКИ. Власник, дослівно: «і реакції і взаємодію з повідомленнями
 * виправ, це калічно зараз».
 *
 * Що саме каліче, виміряно 18.09 і стережеться тут:
 *
 *  1. Кадр не виходив із апарата. Єдиним викликом був
 *     `messengerNetworkEngine.sendReaction`, у якого дві гілки і жодна не веде
 *     в меш телефона. Ручка мосту (POST …/reactions) існувала з 13.09 і не
 *     кликалась із веба НІ РАЗУ — слово «reactions» у `messengerApi.ts` не
 *     траплялось жодного разу.
 *  2. Мовчання виглядало успіхом. Метод був `void`, і поверхня казала
 *     «Реакцію надіслано» незалежно від того, чи щось поїхало.
 *  3. Смайл не казав правди про дорогу: у черзі й доставлений виглядали
 *     однаково, бо стан дороги на дроті не возився зовсім.
 *  4. Позначку шукали лише в активному чаті, а кадр слали навіть коли рядка
 *     не знайшлось.
 *
 * Тести НЕ перевіряють, що смайл перетинає справжній дріт — це доводять
 * справжніми сокетами `PeerMarkReachTest` у core-net. Тут доводиться те, що
 * було зламане: чи дотик у вкладці взагалі КУДИСЬ їде і чи екран каже про це
 * правду.
 */

const LINE = {
  id: 'm1',
  senderId: 'user_alex',
  senderName: 'Олександр',
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text' as const,
  text: 'Привіт',
  isSelf: false,
};

const seed = (activeChatId = 'chat_1') =>
  useMessengerStore.setState({
    chats: [
      {
        id: 'chat_1',
        title: 'Олександр',
        avatar: '',
        type: 'dm',
        circle: 'work',
        unreadCount: 0,
        messages: [LINE],
      },
      {
        id: 'chat_2',
        title: 'Інша',
        avatar: '',
        type: 'dm',
        circle: 'work',
        unreadCount: 0,
        messages: [],
      },
    ],
    activeChatId,
    currentUser: { id: 'me', name: 'Я', avatar: '', status: 'online' } as never,
  });

const reactionsOf = (chatId = 'chat_1') =>
  useMessengerStore.getState().chats.find((c) => c.id === chatId)?.messages.find((m) => m.id === 'm1')?.reactions;

/** Міст телефона. Його наявність і є вибір дороги. */
const withBridge = (on: boolean) => {
  const g = globalThis as { PhantomBridge?: unknown };
  if (on) g.PhantomBridge = { call: () => '{}' };
  else delete g.PhantomBridge;
};

describe('дорога позначки', () => {
  beforeEach(() => seed());
  afterEach(() => {
    vi.restoreAllMocks();
    withBridge(false);
  });

  it('на телефоні смайл їде В МІСТ, а не в браузерний транспорт', async () => {
    withBridge(true);
    const react = vi.spyOn(messengerApi, 'react').mockResolvedValue({
      id: 'm1',
      reactions: [{ emoji: '❤️', count: 1, users: ['me'], names: ['Я'] }],
    } as never);
    const engine = vi.spyOn(messengerNetworkEngine, 'sendReaction');

    const refusal = await useMessengerStore.getState().toggleReaction('m1', '❤️');

    expect(refusal).toBeNull();
    expect(react).toHaveBeenCalledWith('chat_1', 'm1', '❤️');
    // Браузерний транспорт у телефонній збірці не веде нікуди — і тому його
    // тут не кличуть узагалі.
    expect(engine).not.toHaveBeenCalled();
  });

  it('правда про позначки береться з ВІДПОВІДІ вузла, а не з домальовка', async () => {
    withBridge(true);
    // Вузол каже, що позначку тримають ДВОЄ: журнал знає про кадр
    // співрозмовника, якого ця вкладка ще не бачила.
    vi.spyOn(messengerApi, 'react').mockResolvedValue({
      id: 'm1',
      reactions: [{ emoji: '❤️', count: 2, users: ['me', 'user_alex'], names: ['Я', 'Олександр'] }],
    } as never);

    await useMessengerStore.getState().toggleReaction('m1', '❤️');

    expect(reactionsOf()).toEqual([
      { emoji: '❤️', count: 2, users: ['me', 'user_alex'], names: ['Я', 'Олександр'] },
    ]);
  });

  it('домальовок каже «у дорозі», а не «поставлено», доки вузол не відповів', async () => {
    withBridge(true);
    let release: (v: unknown) => void = () => {};
    vi.spyOn(messengerApi, 'react').mockReturnValue(
      new Promise((res) => { release = res; }) as never,
    );

    const inFlight = useMessengerStore.getState().toggleReaction('m1', '❤️');
    // САМЕ ЦЕ власник називає «начебто поставилось»: доки відповіді немає,
    // смайл не має права виглядати як доставлений.
    expect(reactionsOf()?.[0].mine).toBe('sending');

    release({ id: 'm1', reactions: [{ emoji: '❤️', count: 1, users: ['me'] }] });
    await inFlight;
    expect(reactionsOf()?.[0].mine).toBeUndefined();
  });

  it('відмова вузла ВІДКОЧУЄ домальовок і повертає речення вузла', async () => {
    withBridge(true);
    vi.spyOn(messengerApi, 'react').mockRejectedValue(
      Object.assign(new Error('немає ключа співрозмовника'), { code: 'no_key' }),
    );

    const refusal = await useMessengerStore.getState().toggleReaction('m1', '❤️');

    expect(refusal).toBe('немає ключа співрозмовника');
    // Смайла, якого вузол не взяв, під рядком немає: інакше він лишився б
    // лежати назавжди і виглядати поставленим. Рядок повертається саме до
    // того, чим був — `undefined`, а не до порожнього масиву: «позначок
    // немає» і «вузол про них не казав» — різні речі.
    expect(reactionsOf()).toBeUndefined();
  });

  it('без жодного транспорту смайл ЧЕРВОНИЙ, а не тихо успішний', async () => {
    vi.spyOn(messengerNetworkEngine, 'sendReaction').mockReturnValue(null);

    const refusal = await useMessengerStore.getState().toggleReaction('m1', '🔥');

    expect(refusal).toBeTruthy();
    expect(reactionsOf()?.[0]).toMatchObject({ emoji: '🔥', mine: 'failed' });
  });

  it('відкритий канал — доставлено; брокер — лише черга', async () => {
    const engine = vi.spyOn(messengerNetworkEngine, 'sendReaction');

    engine.mockReturnValue('p2p');
    expect(await useMessengerStore.getState().toggleReaction('m1', '👍')).toBeNull();
    expect(reactionsOf()?.find((r) => r.emoji === '👍')?.mine).toBeUndefined();

    engine.mockReturnValue('server');
    expect(await useMessengerStore.getState().toggleReaction('m1', '🎉')).toBeNull();
    expect(reactionsOf()?.find((r) => r.emoji === '🎉')?.mine).toBe('queued');
  });

  it('позначка працює в НЕактивному чаті — і не летить у нікуди без рядка', async () => {
    seed('chat_2');
    const engine = vi.spyOn(messengerNetworkEngine, 'sendReaction').mockReturnValue('p2p');

    // Рядок лежить у chat_1, а активний — chat_2. До 18.09 мапилось лише
    // `c.id === state.activeChatId`, тож нічого не мінялось, і кадр усе одно
    // слався — на активний чат, якого він не стосується.
    expect(await useMessengerStore.getState().toggleReaction('m1', '❤️')).toBeNull();
    expect(engine).toHaveBeenCalledWith('chat_1', 'm1', '❤️', 'add');
    expect(reactionsOf('chat_1')?.[0].emoji).toBe('❤️');

    engine.mockClear();
    const refusal = await useMessengerStore.getState().toggleReaction('немає-такого', '❤️');
    expect(refusal).toBeTruthy();
    expect(engine).not.toHaveBeenCalled();
  });

  it('другий дотик знімає свою позначку', async () => {
    vi.spyOn(messengerNetworkEngine, 'sendReaction').mockReturnValue('p2p');
    const store = useMessengerStore.getState();

    await store.toggleReaction('m1', '❤️');
    expect(reactionsOf()?.length).toBe(1);
    await store.toggleReaction('m1', '❤️');
    expect(reactionsOf()?.length).toBe(0);
  });
});

describe('sendReaction каже, чи щось поїхало', () => {
  afterEach(() => vi.restoreAllMocks());

  /**
   * Обидві гілки вміють не зробити нічого, і до 18.09 обидві повертали
   * `undefined`. Той, хто кликав, не мав ЧИМ відрізнити успіх від тиші.
   */
  it('нуль відкритих каналів у режимі p2p — це null, а не тихий успіх', () => {
    const engine = messengerNetworkEngine as unknown as {
      transportMode: string;
      dataChannels: Map<string, { readyState: string; send: () => void }>;
      ws: unknown;
    };
    engine.transportMode = 'p2p';
    engine.dataChannels = new Map();
    expect(messengerNetworkEngine.sendReaction('c', 'm', '❤️', 'add')).toBeNull();

    engine.dataChannels = new Map([['p', { readyState: 'open', send: () => {} }]]);
    expect(messengerNetworkEngine.sendReaction('c', 'm', '❤️', 'add')).toBe('p2p');
  });

  it('мертвий сокет — це null', () => {
    const engine = messengerNetworkEngine as unknown as {
      transportMode: string;
      ws: { readyState: number; send: () => void } | null;
    };
    engine.transportMode = 'server';
    engine.ws = null;
    expect(messengerNetworkEngine.sendReaction('c', 'm', '❤️', 'add')).toBeNull();

    engine.ws = { readyState: WebSocket.OPEN, send: () => {} };
    expect(messengerNetworkEngine.sendReaction('c', 'm', '❤️', 'add')).toBe('server');
  });
});
