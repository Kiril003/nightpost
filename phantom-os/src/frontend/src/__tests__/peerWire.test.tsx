/**
 * Кадр меша не доходить до людини сирим — ні в бульбашці, ні в реєстрі.
 *
 * 04.09, збірка dbe6d05d: власник поділився маршрутом з мапи в розмову з живою
 * людиною й побачив `PH-RTE:1788517702898;337;258;FOOT;s~doHcdymBqKuB;g_eoH…`
 * замість картки. Формат живе в Kotlin і тільки там: міст (`WebMessengerKinds`,
 * коміт 61b3cc84) розбирає його `PeerLive.decodeRoute` / `PeerPayload
 * .decodePlace` і кладе ПЛАСКІ поля в лист, лишаючи `body` порожнім.
 *
 * Тут три зобовʼязання, і всі перевіряються на РЕНДЕРІ справжніх поверхонь
 * (`ChatArea`, `Sidebar`), а не пошуком підрядків у джерелі — саме так зелений
 * `PeerLiveReachFirewallTest` пропустив цю ваду: він перевіряв, що у ФАЙЛІ
 * Kotlin-екрана є `PeerLive.isRoute(said)`, а власник дивився інший екран.
 *
 *  1. поля моста стають карткою, а неповні — чесним реченням ХОСТА;
 *  2. керівний кадр зникає зі стрічки цілком, без порожньої бульбашки;
 *  3. заборона за замовчуванням: тіло з `PH-` ніколи не показується текстом —
 *     сторож на той випадок, коли Kotlin додасть рід, а міст його ще не вміє.
 *
 * Мутації (заміряні): прибрати `readMessageCard` з ChatArea — червоніють
 * картки; прибрати фільтр `isControlFrame` — червоніє порожня оболонка;
 * прибрати перехоплення у `FormattedMessageText` — червоніє заборона;
 * прибрати `peerWireLine` із Sidebar — червоніє реєстр.
 */
import { beforeAll, describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { Sidebar } from '../components/messenger/Sidebar';
import { chatFromNode, messageFromNode } from '../services/messengerApi';
import type { Chat, Message, UserProfile } from '../types/messenger';

// jsdom не вміє гортати: ChatArea сама доводить стрічку до низу.
beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

/* ── що кладе міст у рядок листа ─────────────────────────────────────────── */

const SHAPE: [number, number][] = [
  [49.83802, 18.15634],
  [49.83861, 18.1566],
  [49.83925, 18.15672],
  [49.84003, 18.15693],
];

/** Рядок вузла точно тієї форми, яку описав міст: пласкі поля, порожнє тіло. */
const routeRow = (over: Record<string, unknown> = {}) => ({
  id: 'r1',
  conversation_id: 'c1',
  client_id: 'cl1',
  seq: 1,
  author_id: 'peer',
  author_name: 'Оксана',
  kind: 'route',
  body: '',
  ciphertext: null,
  transport: 'direct',
  sent_at: '2026-09-04T11:32:00.000Z',
  edited_at: null,
  deleted_at: null,
  route_profile: 'FOOT',
  route_distance_m: 337,
  route_duration_s: 258,
  route_expires_at_ms: Date.now() + 6 * 60 * 60_000,
  route_waypoints: [SHAPE[0], SHAPE[3]],
  route_shape: SHAPE,
  ...over,
});

const placeRow = (over: Record<string, unknown> = {}) => ({
  ...routeRow(),
  id: 'p1',
  kind: 'place',
  route_profile: undefined,
  route_distance_m: undefined,
  route_duration_s: undefined,
  route_waypoints: undefined,
  route_shape: undefined,
  place_lat: 50.4501,
  place_lon: 30.5234,
  place_accuracy_m: 12,
  ...over,
});

/* ── листи для стрічки ───────────────────────────────────────────────────── */

const letter = (over: Partial<Message>, id = 'm1'): Message =>
  ({
    id,
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    sentAt: '2026-09-04T11:32:00.000Z',
    type: 'text',
    isSelf: false,
    ...over,
  }) as unknown as Message;

/** Лист, зібраний тим самим розбором, що й у житті: рядок моста → Message. */
const fromBridge = (row: Record<string, unknown>, id = 'm1'): Message => ({
  ...messageFromNode({ ...row, id } as never, 'me', 'peer'),
});

const ROUTE_MSG = fromBridge(routeRow());
const PLACE_MSG = fromBridge(placeRow(), 'm2');

/* ── сирий дріт (сім родів, оголошених у Kotlin, і восьмий з майбутнього) ── */

const RAW_ROUTE = 'PH-RTE:1788517702898;337;258;FOOT;s~doHcdymBqKuB;g_eoHccymBZh@GNKTMTo@cAa@';
const RAW_PLACE = 'PH-LOC:50.45010,30.52340;12';
const RAW_FIX = 'PH-LIV:50.45010,30.52340;12;1788517702898;1788517802898;7';
const RAW_STOP = 'PH-LVX:1788517702898';
const RAW_CHUNK = 'PH-FCH:9f2ac1d0e4b7460fa1c25d8e3b7c0a11:3:AAAAB3NzaC1yc2EAAAA';
const RAW_GROUP = 'PH-GRP:a1b2c3d4e5f6:0JTQstGW0YA:me,b91f4c\nбуду за десять хвилин';
const RAW_ACT = 'PH-SYS:add:b91f4c';
const RAW_FUTURE = 'PH-XYZ:1788517702898;щось із завтрашньої збірки';

/**
 * Усі роди, які ходять цим полем: шість дротових плюс те, що шле композер.
 * Заслін ловить їх префіксом `PH-`, а не переліком — вісімнадцятий рід теж
 * не потрапить на скло сирим.
 */
const EVERY_PREFIX = [
  RAW_ROUTE,
  RAW_PLACE,
  RAW_FIX,
  RAW_STOP,
  RAW_CHUNK,
  RAW_GROUP,
  RAW_ACT,
  RAW_FUTURE,
  'PH-VOICE:0;2400;AAAA',
  'PH-TABLE:{"cols":3}',
  'PH-TASKLIST:1;купити хліб',
  'PH-POLL:коли виїжджаємо?;6;7',
  'PH-BILL:1200;4',
  'PH-CHART:line;3;4;5',
  'PH-MULTIQUOTE:2;abc',
  'PH-EVENT:1788517702898;зустріч',
  'PH-CODE:kotlin;fun main()',
  'PH-FILE:9f2ac1d0;map.pmtiles;4096',
  'PH-IMAGE:9f2ac1d0;підпис',
  'PH-KEY:ZTc2483778',
];

const CONTROL_FRAMES: [string, string][] = [
  ['жива позначка', RAW_FIX],
  ['зупинка показу', RAW_STOP],
  ['шматок файла', RAW_CHUNK],
  ['обгортка групи', RAW_GROUP],
  ['системна дія', RAW_ACT],
];

const CHAT = {
  id: 'chat_dm_peer',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const noop = () => {};

function conversation(messages: Message[]) {
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={CHAT}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={noop}
        onReplyMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );
}

const said = (body: string, id = 'mx') => letter({ type: 'text', text: body }, id);
const bubbles = (container: HTMLElement) => container.querySelectorAll('.msg-bubble-fluid').length;

/* ── реєстр розмов ───────────────────────────────────────────────────────── */

const USER = {
  id: 'me',
  name: 'Я',
  handle: 'me',
  avatar: '',
  status: '',
} as unknown as UserProfile;


function roster(over: Partial<Chat>) {
  const chat = {
    ...CHAT,
    lastAuthor: 'Оксана',
    lastAt: '2026-09-04T11:32:00.000Z',
    ...over,
  } as unknown as Chat;
  return render(
    <MemoryRouter>
      <Sidebar
        chats={[chat]}
        activeChatId=""
        onSelectChat={noop}
        currentUser={USER}
        onNewChat={noop}
        onOpenUserProfile={noop}
      />
    </MemoryRouter>,
  );
}

/* ── розбір рядка моста ──────────────────────────────────────────────────── */

describe('пласкі поля моста стають листом', () => {
  it('маршрут: профіль, числа, точки й ламана — з полів, не з тіла', () => {
    expect(ROUTE_MSG.route).toEqual({
      profile: 'FOOT',
      distanceM: 337,
      durationS: 258,
      expiresAtMs: expect.any(Number),
      waypoints: [
        { lat: 49.83802, lon: 18.15634 },
        { lat: 49.84003, lon: 18.15693 },
      ],
      shape: SHAPE.map(([lat, lon]) => ({ lat, lon })),
    });
    expect(ROUTE_MSG.text).toBeUndefined();
  });

  it('місце: точність null означає «не сказали», а не нуль', () => {
    expect(PLACE_MSG.place).toEqual({ lat: 50.4501, lon: 30.5234, accuracyM: 12 });
    expect(fromBridge(placeRow({ place_accuracy_m: null })).place).toEqual({
      lat: 50.4501,
      lon: 30.5234,
    });
  });

  it('неповні поля не стають половиною картки', () => {
    expect(fromBridge(routeRow({ route_distance_m: null })).route).toBeUndefined();
    expect(fromBridge(routeRow({ route_distance_m: null })).broken?.of).toBe('route');
    expect(fromBridge(placeRow({ place_lat: null })).broken?.of).toBe('place');
    // Хибна точка вбиває весь список: пів-ламаної не буває.
    expect(fromBridge(routeRow({ route_shape: [[91, 0]] })).route).toBeUndefined();
  });

  it('рід «broken» несе речення хоста дослівно', () => {
    const msg = fromBridge({
      ...routeRow(),
      kind: 'broken',
      broken_kind: 'route',
      broken_reason: 'строк кадру вийшов ще в дорозі',
    });
    expect(msg.broken).toEqual({ of: 'route', reason: 'строк кадру вийшов ще в дорозі' });
  });
});

/* ── бульбашка ───────────────────────────────────────────────────────────── */

describe('кадр меша в бульбашці', () => {
  it('маршрут стає карткою: відстань, час, профіль словом — і силует шляху', () => {
    const { container } = conversation([ROUTE_MSG]);
    expect(container.textContent).not.toContain('PH-');
    expect(container.textContent).not.toContain('route_distance_m');
    expect(screen.getByTestId('peer-route-card')).toBeTruthy();
    expect(screen.getByTestId('peer-route-facts').textContent).toBe(
      '337 м · 4 хв · пішки',
    );
    expect(screen.getByTestId('peer-route-spark')).toBeTruthy();
    // Ні координат, ні «за словами відправника» — техніка людині не показується.
    expect(screen.getByTestId('peer-route-card').textContent).not.toMatch(/\d+\.\d{4,}/);
    expect(screen.getByTestId('peer-route-card').textContent).not.toContain('відправника');
  });

  it('своя картка маршруту не лежить на бурштині: бульбашка — поверхня', () => {
    const { container } = conversation([{ ...ROUTE_MSG, senderId: 'me', isSelf: true }]);
    const bubble = screen.getByTestId('peer-route-card').closest('.msg-bubble-fluid')!;
    expect(bubble.classList.contains('msg-bubble-self')).toBe(false);
    expect(bubble.classList.contains('msg-bubble-other')).toBe(true);
    expect(container.querySelectorAll('.msg-bubble-self').length).toBe(0);
  });

  it('свій звичайний текст лишається бурштиновим', () => {
    const { container } = conversation([letter({ text: 'привіт', senderId: 'me', isSelf: true })]);
    expect(container.querySelectorAll('.msg-bubble-self').length).toBe(1);
  });

  it('маршрут зі скінченим строком каже про це в заголовку', () => {
    conversation([
      fromBridge(
        routeRow({
          route_profile: 'CAR',
          route_distance_m: 12400,
          route_duration_s: 1500,
          route_expires_at_ms: Date.now() - 60_000,
        }),
      ),
    ]);
    expect(screen.getByTestId('peer-route-card').textContent).toContain('строк минув');
    expect(screen.getByTestId('peer-route-facts').textContent).toBe(
      '12,4 км · 25 хв · автом',
    );
  });

  it('порожня ламана — не шлях: картка є, силует не вигадується', () => {
    conversation([fromBridge(routeRow({ route_shape: [] }))]);
    expect(screen.getByTestId('peer-route-card')).toBeTruthy();
    expect(screen.queryByTestId('peer-route-spark')).toBeNull();
  });

  it('незнайомий профіль не отримує вигаданого слова', () => {
    conversation([fromBridge(routeRow({ route_profile: 'HOVERCRAFT' }))]);
    expect(screen.getByTestId('peer-route-facts').textContent).toBe(
      '337 м · 4 хв',
    );
  });

  it('місце стає карткою: точність словом, без координат і метрів', () => {
    const { container } = conversation([PLACE_MSG]);
    expect(container.textContent).not.toContain('place_lat');
    expect(screen.getByTestId('peer-place-card').textContent).not.toContain('50.4501');
    expect(screen.getByTestId('peer-place-card').textContent).toContain('точно');
    expect(screen.getByTestId('peer-place-card').textContent).not.toMatch(/±|\d+ м/);
  });

  it('картка місця від людини — без тіні (стиль D)', () => {
    conversation([PLACE_MSG]);
    expect(screen.getByTestId('peer-place-card').className).not.toMatch(/shadow/);
  });

  // 26.09 (власник): причину хоста («ламана обірвалась на 12-й точці») людині
  // не читаємо — це нутрощі. Лишається головне: вигаданого маршруту немає.
  it('нечитаний кадр — одне людське речення, без нутрощів хоста', () => {
    conversation([
      fromBridge({
        ...routeRow(),
        kind: 'broken',
        broken_kind: 'route',
        broken_reason: 'ламана обірвалась на 12-й точці',
      }),
    ]);
    const card = screen.getByTestId('peer-route-broken');
    expect(card.textContent).toContain('Маршрут не вдалося показати');
    expect(card.textContent).not.toContain('ламана обірвалась на 12-й точці');
    expect(screen.queryByTestId('peer-route-card')).toBeNull();
  });

  it('коли хост не сказав причини — те саме речення, але не вигаданий маршрут', () => {
    conversation([fromBridge(routeRow({ route_duration_s: null }))]);
    expect(screen.getByTestId('peer-route-broken').textContent).toContain(
      'Попросіть надіслати ще раз',
    );
    expect(screen.queryByTestId('peer-route-card')).toBeNull();
  });

  it('фото з телефона, якого ПК не відкриває, — «Фото», а не «не вдалося» чи ключ', () => {
    const { container } = conversation([
      fromBridge({
        ...routeRow(),
        kind: 'broken',
        broken_kind: 'photo',
        broken_reason: 'Фото відкривається на телефоні',
      }),
    ]);
    const card = screen.getByTestId('peer-attachment-on-phone');
    expect(card.textContent).toContain('Фото');
    expect(card.textContent).toContain('на телефоні');
    expect(container.textContent).not.toContain('Попросіть надіслати ще раз');
    expect(container.textContent).not.toContain('не вдалося');
  });
});

/* ── керівні кадри ───────────────────────────────────────────────────────── */

describe('керівний кадр у стрічці', () => {
  it.each(CONTROL_FRAMES)('%s зникає цілком — без порожньої бульбашки', (_name, body) => {
    const { container } = conversation([said(body, 'k1'), said('виїжджаю о шостій', 'k2')]);
    expect(container.textContent).not.toContain('PH-');
    expect(container.textContent).toContain('виїжджаю о шостій');
    // Оболонка з аватаром і часом — теж «щось показане». Її бути не має.
    expect(bubbles(container)).toBe(1);
  });

  it('розмова лише з керівних кадрів виглядає порожньою, а не битою', () => {
    const { container } = conversation(CONTROL_FRAMES.map(([, body], i) => said(body, `c${i}`)));
    expect(bubbles(container)).toBe(0);
    // 07.09: слова порожнього чату змінились із «Тут ще тихо» на
    // «Напишіть перше повідомлення» — порожнеча тепер каже, що робити далі,
    // а не просто констатує тишу. Намір перевірки той самий: розмова з самих
    // керівних кадрів мусить виглядати ПОРОЖНЬОЮ, а не битою.
    // 24.09 (форма): одне речення й одне дієслово — «Листів ще немає.» +
    // «Написати першим».
    expect(container.textContent).toContain('Повідомлень ще немає');
  });
});

/* ── заборона за замовчуванням ───────────────────────────────────────────── */

describe('сирий дріт у бульбашці', () => {
  it('нерозібраний маршрут не показується текстом — видно факт, не протокол', () => {
    const { container } = conversation([said(RAW_ROUTE)]);
    expect(container.textContent).not.toContain('PH-RTE');
    expect(container.textContent).not.toContain('s~doHcdymBqKuB');
    expect(screen.getByTestId('peer-route-raw').textContent).toContain('Маршрут');
  });

  it('нерозібране місце — так само', () => {
    const { container } = conversation([said(RAW_PLACE)]);
    expect(container.textContent).not.toContain('PH-LOC');
    expect(screen.getByTestId('peer-place-raw')).toBeTruthy();
  });

  it('рід, якого ця збірка ще не знає, теж не показується сирим', () => {
    const { container } = conversation([said(RAW_FUTURE)]);
    expect(container.textContent).not.toContain('PH-XYZ');
    expect(screen.getByTestId('peer-unknown-line').textContent).toContain(
      'Це повідомлення відкриється в новішій версії',
    );
  });

  it('жоден із двадцяти родів не лишає на склі підрядка «PH-»', () => {
    const { container } = conversation(EVERY_PREFIX.map((body, i) => said(body, `w${i}`)));
    expect(container.textContent).not.toContain('PH-');
    // І жоден не протягнув свого вмісту в обхід заслону.
    expect(container.textContent).not.toContain('купити хліб');
    expect(container.textContent).not.toContain('map.pmtiles');
  });

  it('звичайні слова лишаються словами', () => {
    const { container } = conversation([said('виїжджаю о шостій')]);
    expect(container.textContent).toContain('виїжджаю о шостій');
    expect(container.querySelector('[data-testid^="peer-"]')).toBeNull();
  });
});

/* ── реєстр ──────────────────────────────────────────────────────────────── */

describe('пласкі поля РЯДКА РОЗМОВИ стають рядком переліку', () => {
  const convRow = (over: Record<string, unknown> = {}) =>
    chatFromNode({
      id: 'c1',
      title: 'Оксана',
      kind: 'direct',
      circle: 'friends',
      handle: null,
      avatar: null,
      pinned: false,
      archived: false,
      created_at: '2026-09-04T11:00:00.000Z',
      updated_at: '2026-09-04T11:32:00.000Z',
      last_kind: 'route',
      last_snippet: null,
      last_author: 'Оксана',
      last_at: '2026-09-04T11:32:00.000Z',
      last_route_profile: 'FOOT',
      last_route_distance_m: 3437,
      last_route_duration_s: 2580,
      ...over,
    } as never);

  it('числа беруть із рядка розмови, а не з ненайденої стрічки', () => {
    const chat = convRow();
    expect(chat.lastCard).toEqual({ kind: 'route', profile: 'FOOT', distanceM: 3437, durationS: 2580 });
    const { container } = roster(chat);
    expect(container.textContent).not.toContain('PH-');
    expect(screen.getByTestId('chat-preview-line').textContent).toContain(
      'Маршрут · пішки · 3,4 км',
    );
  });

  it('порожній снипет службового роду не лишає порожнечі під назвою', () => {
    const { container } = roster(convRow({ last_route_distance_m: null, last_route_profile: null }));
    const line = screen.getByTestId('chat-preview-line');
    expect(line.textContent).toContain('Маршрут');
    // Слово, а не порожній рядок і не самий лише автор.
    expect(line.textContent?.replace('Оксана:', '').trim()).toBe('Маршрут');
    expect(container.textContent).not.toContain('undefined');
  });

  it('місце з рядка розмови — «Місце»', () => {
    const chat = convRow({
      last_kind: 'place',
      last_route_profile: null,
      last_route_distance_m: null,
      last_route_duration_s: null,
      last_place_lat: 50.4501,
      last_place_lon: 30.5234,
    });
    expect(chat.lastCard).toEqual({ kind: 'place', lat: 50.4501, lon: 30.5234 });
    roster(chat);
    expect(screen.getByTestId('chat-preview-line').textContent).toContain('Місце');
  });

  it('без чисел від моста — рід словом, без вигаданих кілометрів', () => {
    roster(convRow({ last_route_distance_m: null }));
    const line = screen.getByTestId('chat-preview-line').textContent ?? '';
    expect(line).toContain('Маршрут · пішки');
    expect(line).not.toContain('км');
  });
});

describe('кадр меша в реєстрі розмов', () => {
  it('маршрут із завантаженою стрічкою — «Маршрут · пішки · 337 м»', () => {
    const { container } = roster({ lastKind: 'route', messages: [ROUTE_MSG] });
    expect(container.textContent).not.toContain('PH-');
    expect(screen.getByTestId('chat-preview-line').textContent).toContain(
      'Маршрут · пішки · 337 м',
    );
  });

  it('маршрут без завантаженої стрічки — «Маршрут», без вигаданих чисел', () => {
    roster({ lastKind: 'route', lastSnippet: '' });
    expect(screen.getByTestId('chat-preview-line').textContent).toContain('Маршрут');
    expect(screen.getByTestId('chat-preview-line').textContent).not.toContain('км');
  });

  it('місце — «Місце»', () => {
    roster({ lastKind: 'place', messages: [PLACE_MSG] });
    expect(screen.getByTestId('chat-preview-line').textContent).toContain('Місце');
  });

  it('нечитаний кадр — чесний рядок', () => {
    roster({ lastKind: 'broken', lastSnippet: '' });
    expect(screen.getByTestId('chat-preview-line').textContent).toContain(
      'Лист, який не вдалося прочитати',
    );
  });

  it('нерозібраний дріт у снипеті — «Маршрут», а не протокол', () => {
    const { container } = roster({ lastKind: 'text', lastSnippet: RAW_ROUTE.slice(0, 90) });
    expect(container.textContent).not.toContain('PH-RTE');
    expect(screen.getByTestId('chat-preview-line').textContent).toContain('Маршрут');
  });

  it.each(CONTROL_FRAMES)('керівний кадр (%s) не дає рядка зовсім', (_name, body) => {
    const { container } = roster({ lastKind: 'text', lastSnippet: body.slice(0, 90) });
    expect(container.textContent).not.toContain('PH-');
    expect(screen.queryByTestId('chat-preview-line')).toBeNull();
  });

  it('незнайомий рід — чесний рядок замість протоколу', () => {
    const { container } = roster({ lastKind: 'text', lastSnippet: RAW_FUTURE });
    expect(container.textContent).not.toContain('PH-XYZ');
    expect(screen.getByTestId('chat-preview-line').textContent).toContain(
      'Лист у форматі, якого ця збірка ще не знає',
    );
  });

  it('звичайний рядок лишається собою', () => {
    roster({ lastKind: 'text', lastSnippet: 'виїжджаю о шостій' });
    expect(screen.getByTestId('chat-preview-line').textContent).toContain('виїжджаю о шостій');
  });
});
