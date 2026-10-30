/**
 * Розмова з помічником PHANTOM — такою, як вона є, а не як її підписав міст.
 *
 * Міст телефона (`WebMessengerBridge.phantomConversation`) віддає цей рядок із
 * декором і з вигаданим листом: назва «⚡ PHANTOM Symbiote», аватар «⚡»,
 * `contact_verified: true` (ключа в помічника немає — звіряти нічого), а коли
 * листування ще не було — превʼю «⚡ Готовий до завдань…» із `last_at` = час
 * запиту. Тобто список показував жовту блискавку і розмову «щойно», якої не
 * було, а шапка писала «ключ звірено» про програму.
 *
 * Тут рядок стає звичайним: назва «PHANTOM», однотонний аватар як у всіх,
 * без стану ключа, і без часу та превʼю, доки немає справжнього листа.
 */
import type { NodeConversation } from './messengerApi';

export const ASSISTANT_TITLE = 'Nightpost';

/** Автор листів помічника — дзеркало `AgencySessionManager.PHANTOM_AGENT_ID`. */
export const ASSISTANT_PEER_ID = 'peer_phantom_agent';

/** Рід, яким міст позначає помічника (`phantom`) і сесію кількох агентів (`ai`). */
export const isAssistantKind = (kind: string | null | undefined): boolean =>
  kind === 'phantom' || kind === 'ai';

/**
 * Превʼю-заглушки, які міст підставляє, коли листа НЕМАЄ.
 * Збіг лише з початком: хвіст рядка міст колись міняв, а початок — ні.
 */
const PLACEHOLDER_SNIPPET = [/^\W*Готовий до завдань/u, /^\W*Мультиагентна сесія/u];

export const isPlaceholderSnippet = (snippet: string | null | undefined): boolean =>
  Boolean(snippet) && PLACEHOLDER_SNIPPET.some((re) => re.test((snippet as string).trim()));

/** Емодзі на початку — декор вузла, не слова людини. */
const LEADING_DECOR = /^[\p{Extended_Pictographic}\u{FE0F}\u{200D}\s]+/u;

export function normalizeAssistantRow(row: NodeConversation): NodeConversation {
  if (!isAssistantKind(row.kind)) return row;
  // Картка помічника («Гроші», «День») уривка не має, але лист є: рід лишається.
  const kindOnly = !!row.last_kind && row.last_kind !== 'text';
  const noLetter = isPlaceholderSnippet(row.last_snippet) || (!row.last_snippet && !kindOnly);
  const title = row.kind === 'phantom' ? ASSISTANT_TITLE : (row.title || '').replace(LEADING_DECOR, '').trim();
  return {
    ...row,
    title: title || ASSISTANT_TITLE,
    avatar: null,
    handle: null,
    contact_verified: null,
    contact_key_changed: false,
    ...(noLetter
      ? { last_snippet: null, last_kind: null, last_author: null, last_at: null }
      : row.last_snippet
        ? { last_snippet: row.last_snippet.replace(LEADING_DECOR, '') }
        : {}),
  };
}
