/**
 * Картка композера — один словник на обидва боки дроту.
 *
 * **Вада, заради якої це існує.** Тринадцять карток плюсика (Canvas, Kanban,
 * голосування, RACI, Gantt, Code Runner, Mermaid, git-diff, сімейний список,
 * спільний бюджет, навчальні картки, SVG-макет, async-огляд) ішли
 * `messengerStore.addCustomMessage` → `messengerNetworkEngine.sendMessage`.
 * Той шле у ВЕБ-СОКЕТ каналом `messenger`, а на вузлі зареєстровано рівно один
 * канал — `chat` (`api/routes_chat.py:1268`, єдиний `hub.on` у всьому backend).
 * Тіло з невідомим каналом падає в `self._handlers.get(channel, [])` →
 * порожній список → цикл не робить НІЧОГО. Всередині телефона гірше: сокета
 * там немає взагалі. Функція при цьому мовчки повертала `'server'`.
 *
 * Тобто картка лягала у власну стрічку відправника й не їхала нікуди й ніколи,
 * а відправник не бачив жодної ознаки, що чогось не сталось. Це і є «все що
 * додається у чат також не працює».
 *
 * **Лікування.** Картка їде тією самою дорогою, що й слово й вкладення:
 * `messengerApi.appendMessage` з родом-словом веба в `kind` і JSON картки в
 * `body`. Далі її везе `PeerCard` (`PH-CRD:`) — рід у дроті вже є, і міст уже
 * вміє і скласти його, і розібрати назад.
 *
 * **Чому словник тут закритий і чому він саме такий.** Це дзеркало
 * `PeerCard.Card` у Kotlin, слово в слово: [CARD_FIELD] — рівно ті пари
 * `webType → webField`, які знає міст. Рід, якого тут немає, міст відмовить
 * (422 `kind_not_carried`), і краще, щоб відмова була ТУТ, до сокета, ніж
 * рамка з написом і порожнечею всередині в співрозмовника.
 */

import type { Message } from '../types/messenger';
import { BILL_FIELD, BILL_KIND, MEETUP_FIELD, MEETUP_KIND } from '../types/meetup';
import { tNow } from '../components/chat/tNow';

/**
 * Рід картки → поле, з якого стрічка бере її вміст.
 *
 * Стрічка малює віджет лише коли поруч із `type` лежить його ВЛАСНЕ поле
 * (`type === 'widget:kanban' && msg.kanbanData`). Без нього рядок стає порожньою
 * бульбашкою з годинником — карткою, яка доїхала й не видно.
 */
export const CARD_FIELD: Readonly<Record<string, keyof Message>> = Object.freeze({
  'widget:canvas': 'canvasData',
  'widget:kanban': 'kanbanData',
  'widget:voting': 'votingData',
  'widget:raci': 'raciData',
  'widget:timeline': 'timelineData',
  'widget:code-runner': 'codeRunnerData',
  'widget:family-checklist': 'familyChecklistData',
  'widget:family-expense': 'familyExpenseData',
  'widget:study-flashcards': 'studyFlashcardsData',
  'snippet:async': 'asyncSnippetData',
  'embed:mermaid': 'mermaidData',
  'embed:diff': 'codeDiffData',
  'embed:svg': 'svgPreviewData',
  table: 'tableData',
  // Зустріч і рахунок (збірка 18, `docs/scene/MEETUP_CONTRACT.md` §1) —
  // дзеркало `PeerCard.Card.MEETUP` / `BILL`. Стан людей на них їде не тут, а
  // позначками `PH-CST:`, які міст згортає в `card_marks`. Поля названо в
  // договорі (`types/meetup.ts`); у `Message` їх додає стрічка, що малює ці
  // картки, — до того приведення лише каже тайпчеку те, що дріт уже везе.
  [MEETUP_KIND]: MEETUP_FIELD as keyof Message,
  [BILL_KIND]: BILL_FIELD as keyof Message,
  'widget:person': 'personData',
  'widget:day': 'dayData',
  'widget:glance': 'glanceData',
  'widget:money': 'moneyData',
  'widget:gift': 'giftData',
} as const);

/**
 * Картки, які складає лише помічник на телефоні: День, погляд на людину й гроші.
 * Від людини вони були б підробкою фактів про мене, тож веб їх не шле й не пересилає.
 */
export const ASSISTANT_ONLY_KINDS: ReadonlySet<string> = new Set(['widget:day', 'widget:glance', 'widget:money']);

export const isAssistantOnlyKind = (kind: string | null | undefined): boolean => !!kind && ASSISTANT_ONLY_KINDS.has(kind);

/** Чи це рід, який дріт уміє повезти карткою. */
export function isCardKind(kind: string): boolean {
  return Object.prototype.hasOwnProperty.call(CARD_FIELD, kind);
}

/** Поле вмісту для цього роду, або undefined — дріт його не знає. */
export function cardFieldOf(kind: string): keyof Message | undefined {
  return CARD_FIELD[kind];
}

/**
 * Стеля тіла картки — дзеркало `PeerCard.MAX_CARD_CHARS`.
 *
 * Міст міряє base64 від UTF-8; тут міряємо те саме, щоб відмова була ДО
 * надсилання, з причиною, а не 413 після. Найбільша зі справжніх карток —
 * Kanban із трьома колонками — це одиниці кілобайтів, тож запас кратний.
 */
export const MAX_CARD_CHARS = 24_576;

/** Скільки знаків base64 вийде з цього тіла. Рівно як у мості: без padding. */
export function cardWireChars(payload: string): number {
  const bytes = new TextEncoder().encode(payload).length;
  return Math.ceil((bytes * 4) / 3);
}

/**
 * Чому ця картка не поїде, або null — поїде.
 *
 * Речення, а не код: людина читає причину, а не номер.
 */
export function refuseCard(kind: string, payload: string): string | null {
  if (!isCardKind(kind)) {
    return tNow('cards.unknownKind', { kind });
  }
  if (isAssistantOnlyKind(kind)) {
    return tNow('cards.assistantOnly');
  }
  if (!payload || payload === '{}') {
    return tNow('cards.empty');
  }
  const chars = cardWireChars(payload);
  if (chars > MAX_CARD_CHARS) {
    return tNow('cards.tooBig', { chars, max: MAX_CARD_CHARS });
  }
  return null;
}

/**
 * Вміст картки з рядка вузла.
 *
 * Міст віддає його ВЖЕ розібраним, окремим полем поруч із `kind` (`cardInto`
 * у `WebMessengerBridge`), а хост-вузол — тілом. Обидва шляхи сходяться тут, і
 * жоден не лишає `type` без його поля: рід без вмісту — це порожня бульбашка,
 * а не картка.
 */
export function cardFromRow(
  kind: string,
  body: string | null | undefined,
  row: Record<string, unknown>,
): Partial<Message> | null {
  const field = cardFieldOf(kind);
  if (!field) return null;
  // Міст уже поклав обʼєкт полем — беремо його, не розбираючи вдруге.
  const shipped = row[field as string];
  if (shipped && typeof shipped === 'object') {
    return { [field]: shipped } as Partial<Message>;
  }
  if (!body) return null;
  const parsed = (() => {
    try {
      return JSON.parse(body) as unknown;
    } catch {
      return null;
    }
  })();
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  return { [field]: parsed } as Partial<Message>;
}
