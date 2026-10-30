import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeEach, afterEach, vi, type MockInstance } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi, messageFromNode } from '../services/messengerApi';
import { messengerNetworkEngine } from '../services/messengerNetworkEngine';
import { CARD_FIELD, cardWireChars, isCardKind, refuseCard } from '../services/composerCards';
import { BILL_FIELD, BILL_KIND, MAX_MARK_BYTES, MEETUP_FIELD, MEETUP_KIND } from '../types/meetup';

/**
 * Картка з плюсика ДОЇЖДЖАЄ — виміряно на дверях транспорту.
 *
 * **Вада, яку цей файл не дає повернути.** Тринадцять карток композера й ще
 * пʼять поверхонь (Canvas, Header, віджети голосування й RACI, Academy,
 * Automation) клали лист через `addCustomMessage` →
 * `messengerNetworkEngine.sendMessage`. Той шле у веб-сокет каналом
 * `messenger`. На вузлі зареєстровано рівно ОДИН канал — `chat`
 * (`hub.on("chat", …)` в `api/routes_chat.py:1268`, єдиний `hub.on` у всьому
 * backend). Тіло з невідомим каналом падає в `_handlers.get(channel, [])` →
 * порожній список → цикл не виконує жодного обробника. Усередині телефона
 * сокета немає взагалі — його глушить заглушка ще до першого скрипта бандла.
 * `sendMessage` при цьому мовчки повертав `'server'`.
 *
 * Тобто картка лягала у ВЛАСНУ стрічку відправника й не їхала нікуди й ніколи,
 * а жодна поверхня про це не казала. Це і є «все що додається у чат також не
 * працює».
 *
 * Тому тест дивиться саме на `appendMessage` — двері до вузла, ті самі, якими
 * їдуть слово й вкладення, — а не на локальну стрічку. Стрічка зеленіла й
 * тоді, коли не їхало нічого.
 */

const SELF = { id: 'user_self', name: 'Кирило', avatar: '' };

const KANBAN = {
  id: 'k_1',
  title: 'Командний Спринт',
  columns: [{ id: 'c1', title: 'Черга', items: [] }],
};

describe('картка композера їде до вузла, а не в мертвий канал', () => {
  let append: MockInstance;
  let socket: MockInstance;

  beforeEach(() => {
    append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({
      id: 'srv_1',
      kind: 'widget:kanban',
      body: JSON.stringify(KANBAN),
      author_id: SELF.id,
      author_name: SELF.name,
      sent_at: new Date().toISOString(),
      delivery_state: 'sent',
    } as never);
    socket = vi.spyOn(messengerNetworkEngine, 'sendMessage').mockImplementation(() => 'p2p');
    useMessengerStore.setState({
      currentUser: SELF as never,
      chats: [
        {
          id: 'chat_cards',
          title: 'Олександр',
          avatar: '',
          type: 'dm',
          circle: 'work',
          unreadCount: 0,
          messages: [],
        },
      ] as never,
      activeChatId: 'chat_cards',
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('sendCard кладе рід і тіло в appendMessage', async () => {
    const refusal = await useMessengerStore.getState().sendCard('widget:kanban', KANBAN);

    expect(refusal).toBeNull();
    expect(append).toHaveBeenCalledTimes(1);
    const [conversationId, body] = append.mock.calls[0] as [string, Record<string, unknown>];
    expect(conversationId).toBe('chat_cards');
    expect(body.kind).toBe('widget:kanban');
    expect(JSON.parse(String(body.body))).toEqual(KANBAN);
    // Сокета тут бути не мусить: саме він і був дорогою в нікуди.
    expect(socket).not.toHaveBeenCalled();
  });

  it('addCustomMessage з карткою більше не йде в сокет — вісімнадцять поверхонь лікуються одними дверима', () => {
    useMessengerStore.getState().addCustomMessage({
      id: 'msg_kanban_1',
      senderId: SELF.id,
      senderName: SELF.name,
      senderAvatar: '',
      timestamp: '12:00',
      type: 'widget:kanban',
      isSelf: true,
      kanbanData: KANBAN,
    } as never);

    expect(append).toHaveBeenCalledTimes(1);
    expect(socket).not.toHaveBeenCalled();
  });

  it('вузол не взяв картку — це «failed», а не галочка', async () => {
    append.mockRejectedValueOnce(new Error('503'));
    const said = await useMessengerStore.getState().sendCard('widget:kanban', KANBAN);

    expect(said).toBeTruthy();
    const row = useMessengerStore.getState().chats[0].messages[0];
    expect(row.status).toBe('failed');
  });

  it('рід, якого дріт не везе, відмовляється ДО сокета', async () => {
    const said = await useMessengerStore.getState().sendCard('widget:teleport', { a: 1 });

    expect(said).toContain('widget:teleport');
    expect(append).not.toHaveBeenCalled();
  });
});

/**
 * Словник закритий і мусить збігатися з `PeerCard.Card` у Kotlin.
 *
 * Це не смак: рід, якого немає в мості, отримує 422 `kind_not_carried`, а рід,
 * чиє ПОЛЕ тут назване інакше, ніж у `PeerCard.Card.webField`, доїде й не
 * намалюється — стрічка малює віджет лише коли бачить своє поле поруч зі своїм
 * типом. Розбіжність тут коштує порожньої бульбашки з годинником.
 */
describe('словник карток — дзеркало PeerCard.Card', () => {
  it('двадцять один рід, кожен зі своїм полем', () => {
    expect(Object.keys(CARD_FIELD)).toHaveLength(21);
    expect(CARD_FIELD['widget:kanban']).toBe('kanbanData');
    expect(CARD_FIELD['embed:diff']).toBe('codeDiffData');
    expect(CARD_FIELD['snippet:async']).toBe('asyncSnippetData');
    // Кожне поле унікальне: два роди в одне поле склеїли б дві різні картки.
    expect(new Set(Object.values(CARD_FIELD)).size).toBe(21);
  });

  /**
   * Зустріч і рахунок (збірка 18). Поки їх тут немає, `isCardKind` дає false,
   * і веб чесно вимикає «Запросити» з причиною. Слова — з договору
   * (`types/meetup.ts`), а не набрані вдруге.
   */
  it('зустріч і рахунок їдуть словами договору', () => {
    expect(isCardKind(MEETUP_KIND)).toBe(true);
    expect(isCardKind(BILL_KIND)).toBe(true);
    expect(CARD_FIELD[MEETUP_KIND]).toBe(MEETUP_FIELD);
    expect(CARD_FIELD[BILL_KIND]).toBe(BILL_FIELD);
    expect(refuseCard(MEETUP_KIND, JSON.stringify({ v: 1, id: 'm1' }))).toBeNull();
    expect(refuseCard(BILL_KIND, JSON.stringify({ v: 1, id: 'b1' }))).toBeNull();
  });

  /**
   * Стан людини на картці — позначка `PH-CST:` у PeerMark.kt. Веб її не
   * складає (міст складає кадр і згортає його в `card_marks`), але межа
   * значення в договорі мусить бути тією, якою міряє дріт: `dish:<id>`, що
   * влазить у вебі й не влазить у Kotlin, відмовить уже після дотику.
   */
  it('позначка стану картки є в PeerMark.kt і з тією самою межею', () => {
    const kt = readFileSync(
      resolve(__dirname, '../../../../../phantom-companion/core-net/src/main/java/local/phantom/companion/core/net/peer/PeerMark.kt'),
      'utf8',
    );
    expect(kt).toMatch(/^\s+CARD_STATE\(\s*CARD_STATE_TAG\s*\),/m);
    expect(kt).toMatch(/const val CARD_STATE_TAG = "PH-CST:"/);
    const limit = kt.match(/const val MAX_VALUE_BYTES = (\d+)/);
    expect(Number(limit?.[1])).toBe(MAX_MARK_BYTES);
  });

  it('пари «рід → поле» — слово в слово ті, що в PeerCard.kt', () => {
    const kt = readFileSync(
      resolve(__dirname, '../../../../../phantom-companion/core-net/src/main/java/local/phantom/companion/core/net/peer/PeerCard.kt'),
      'utf8',
    );
    const pairs = Object.fromEntries(
      [...kt.matchAll(/^\s+[A-Z_]+\("\w+", "([^"]+)", "([^"]+)"\),/gm)].map((m) => [m[1], m[2]]),
    );
    expect(pairs).toEqual({ ...CARD_FIELD });
    // Таблиця з «Що надіслати» тепер їде, а не лишається в стрічці автора.
    expect(CARD_FIELD.table).toBe('tableData');
  });

  it('стеля кадру міряється так само, як у мості: base64 без padding', () => {
    // 3 байти → 4 знаки. Кирилиця — два байти на літеру, і саме через це
    // «довжина рядка» була б неправдою про вагу на дроті.
    expect(cardWireChars('abc')).toBe(4);
    expect(cardWireChars('ї')).toBe(3);
    const huge = JSON.stringify({ t: 'я'.repeat(20_000) });
    expect(refuseCard('widget:canvas', huge)).toContain('не влізе в кадр');
  });
});

/**
 * Зворотний бік: картка, що ПРИЇХАЛА, мусить прийти зі своїм полем.
 *
 * `messageFromNode` повертав `type: 'widget:kanban'` і не переносив `kanbanData`
 * ані з тіла, ані з плаского поля рядка — тобто навіть доїхавши, картка була
 * порожньою бульбашкою з годинником.
 */
describe('картка з вузла приходить разом зі своїм вмістом', () => {
  const row = (extra: Record<string, unknown>) => ({
    id: 'srv_9',
    kind: 'widget:kanban',
    author_id: 'peer_1',
    author_name: 'Олександр',
    sent_at: '2026-09-16T10:00:00',
    ...extra,
  });

  it('тілом — як віддає вузол-хост', () => {
    const msg = messageFromNode(row({ body: JSON.stringify(KANBAN) }) as never, 'user_self');
    expect(msg.type).toBe('widget:kanban');
    expect(msg.kanbanData).toEqual(KANBAN);
  });

  it('пласким полем — як віддає міст телефона (body порожній)', () => {
    const msg = messageFromNode(row({ body: '', kanbanData: KANBAN }) as never, 'user_self');
    expect(msg.type).toBe('widget:kanban');
    expect(msg.kanbanData).toEqual(KANBAN);
  });

  it('нечитане тіло стає «broken», а не родом із порожнечею', () => {
    const msg = messageFromNode(row({ body: 'не json' }) as never, 'user_self');
    expect(msg.type).toBe('broken');
    expect(msg.kanbanData).toBeUndefined();
    expect(msg.broken?.of).toBe('widget:kanban');
  });
});
