import { useMessengerStore } from '../../stores/messengerStore';
import { postSpaceEvent } from './spaceWire';
import type { MessageKey } from '../../i18n/messages/uk';
import { seedEvents, type SeedTemplate } from './spaceTemplates';

export interface SpacePerson {
  /** Ключ у ростері мосту — ним людину кличуть у гілку. */
  id: string;
  /** Ключ підписанта — ним людина є в журналі простору. */
  nodeId: string;
}

/**
 * Простір = гілка роду `space` + журнал, наповнений шаблоном. Гілку без людей
 * міст не створює, тож без жодної людини простору теж немає.
 */
export async function createSpace(
  profileId: string,
  name: string,
  template: SeedTemplate,
  people: readonly SpacePerson[],
  t: (key: MessageKey) => string,
  now: number = Date.now(),
): Promise<string> {
  const chatId = await useMessengerStore.getState().createGroup(name, 'all', '', '', people.map((p) => p.id), 'space');
  let n = 0;
  const mkId = () => `${now.toString(36)}${(n += 1).toString(36)}`;
  for (const body of seedEvents(template, name, people.map((p) => p.nodeId), t, mkId)) {
    await postSpaceEvent(profileId, chatId, body, now + n);
  }
  return chatId;
}
