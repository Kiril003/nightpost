/**
 * Службові листи, яких людина не мусить бачити (власник на A52, 02.10:
 * «Ви: Цей застосунок ще не вміє показати цей …» на половині списку о 00:24).
 *
 * Це не слова людини, а речення рідного боку про рід, якого ця збірка не
 * малює (обмін адресами гаманця, службові кадри, картка без вмісту). Вони не
 * стають ні превʼю в списку, ні бульбашкою в стрічці; превʼю бере останній
 * ЛЮДСЬКИЙ лист. Рахунок непрочитаного веде рідний бік — вимога до нього в
 * docs/messenger/NAV_CONTRACT.md («Службові листи»).
 *
 * Речення — WebMessengerBridge.notice() (strings.xml `msgr_say_unknown`,
 * `msgr_say_empty`): збіг за початком, бо в дужках — назва роду.
 */
import type { Message } from '../types/messenger';
import { isControlFrame } from './peerWire';

const NOTICES = [
  /^Цей застосунок ще не вміє показати цей вміст/,
  /^Картка «[^»]*» приїхала без вмісту/,
];

/** Роди, які ніколи не є словами людини. */
const SERVICE_KINDS = new Set(['system', 'service', 'control', 'notice', 'wallet', 'solana', 'address-swap']);

export const isServiceText = (text?: string | null): boolean =>
  typeof text === 'string' && (isControlFrame(text) || NOTICES.some((re) => re.test(text.trim())));

export const isServiceKind = (kind?: string | null): boolean =>
  typeof kind === 'string' && (SERVICE_KINDS.has(kind) || kind.startsWith('sys:') || kind.startsWith('wallet:'));

/** Лист, якого в стрічці й превʼю не буває. Вкладення з таким підписом — лишається. */
export const isHiddenMessage = (m?: (Pick<Message, 'type' | 'text'> & { media?: unknown }) | null): boolean => {
  if (!m) return false;
  if (isServiceKind(m.type)) return true;
  return isServiceText(m.text) && !m.media;
};

/**
 * Що ховає саме СТРІЧКА. Рядок строку зникнення — службовий (превʼю списку
 * його не бере), але в стрічці він мусить бути: це чесне «відтепер листи
 * зникають», і без нього людина не знала б, чому вони зникли.
 */
export const isFeedHidden = (
  m?: (Pick<Message, 'type' | 'text' | 'timerSeconds'> & { media?: unknown }) | null,
): boolean => (m?.timerSeconds !== undefined ? false : isHiddenMessage(m));

/** Останній лист, який людина справді написала чи надіслала. */
export const lastHumanMessage = (messages?: Message[] | null): Message | null => {
  if (!messages) return null;
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    if (!isHiddenMessage(messages[i])) return messages[i];
  }
  return null;
};
