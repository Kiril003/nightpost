/**
 * Що Nightpost знає про звʼязок і де його розмова — для списку розмов на телефоні.
 *
 * Збірка 17: домівка помічника (02.10) знята, телефон відкривається списком
 * розмов (рішення власника 05.10). Звідси лишилось те, що потрібне списку:
 *
 *  - `phantomChatOf` — куди йде питання з поля «Спитати Nightpost» над списком;
 *  - `linkState` — стан звʼязку словами: без мережі список показує рядок-двері
 *    до «Без інтернету» і каже лише те, що справді працює.
 *
 * Джерела названо: `pulse.link` (міст) і `navigator.onLine` (WebView). Немає
 * жодного — «невідомо», і про мережу не кажемо нічого. Чисті функції.
 */
import type { Chat } from '../types/messenger';
import type { Pulse } from './livingPulse';
import type { IntentSetup } from './intentsApi';
import { pluralUa } from '../utils/format';
import { currentLocale, tNow } from '../components/chat/tNow';
import type { MessageKey } from '../i18n';
import { narrowLabel } from './livingPulse';
import { isNodeChat } from '../components/messenger/chatListFormat';

/** Розмова з Nightpost — та сама закріплена, що в списку. Немає — null. */
export function phantomChatOf(chats: Chat[]): Chat | null {
  return chats.find((c) => isNodeChat(c) && !c.archived) ?? null;
}

export type LinkKind = 'online' | 'narrow' | 'offline' | 'unknown';

export interface LinkState {
  kind: LinkKind;
  /** Що сказати одним рядком. */
  word: string;
  /** Що працює БЕЗ інтернету — лише те, що справді є. Порожньо — коли мережа є. */
  works: string[];
}

/**
 * Стан звʼязку словами. Джерела: `pulse.link.online` (міст), `navigator.onLine`
 * (браузер WebView). Бракує обох — «невідомо», і домівка про мережу мовчить.
 */
export function linkState(
  pulse: Pulse | null,
  navigatorOnline: boolean | null,
  setup: Pick<IntentSetup, 'sms_build' | 'permitted' | 'gateway_number' | 'pc_paired'> | null,
  queued: number,
): LinkState {
  const bridge = pulse?.link.online;
  const offline = bridge === false || (bridge == null && navigatorOnline === false);
  if (offline) {
    const works: string[] = [];
    works.push(
      queued > 0
        ? tNow(
            currentLocale() === 'en'
              ? queued === 1 ? 'link.queuedOne' : 'link.queuedMany'
              : pluralUa(queued, 'link.queuedOne', 'link.queuedFew', 'link.queuedMany') as MessageKey,
            { n: queued },
          )
        : tNow('link.queuedNone'),
    );
    if (pulse?.link.direct) works.push(tNow('link.direct'));
    if (setup?.sms_build && setup.permitted && typeof setup.gateway_number === 'string' && setup.gateway_number.trim()) {
      works.push(tNow('link.viaSms'));
    } else if (setup?.pc_paired) {
      works.push(tNow('link.viaPc'));
    }
    return { kind: 'offline', word: tNow('link.offline'), works };
  }
  if (pulse?.link.narrow) {
    const w = narrowLabel(pulse.link.narrowWord || tNow('pulse.narrowShort'));
    return { kind: 'narrow', word: w.charAt(0).toUpperCase() + w.slice(1), works: [] };
  }
  if (bridge === true || (bridge == null && navigatorOnline === true)) {
    return { kind: 'online', word: tNow('link.online'), works: [] };
  }
  return { kind: 'unknown', word: '', works: [] };
}
