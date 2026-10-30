import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerNetworkEngine } from '../services/messengerNetworkEngine';

/**
 * Сторож ВІДПРАВКИ, не показу.
 *
 * 05.09.2026 у дереві месенджера знайдено девʼять поверхонь, що клали у
 * СПРАВЖНІЙ транспорт лист від імені сторони, якої не існує:
 * «github-actions[bot]» із заявою про успішну збірку, «Escrow Protocol
 * Engine» із сумою 45 000 ₴ і «підписами обох сторін валідовані (Ed25519)»,
 * «Planning Poker Engine» з оцінками чотирьох людей, які не голосували,
 * «AI Chronicler» із числом стиснутих повідомлень, якого ніхто не рахував.
 *
 * `addCustomMessage` — це не локальний стан: `messengerStore` віддає лист у
 * `messengerNetworkEngine.sendMessage`, тобто в P2P або веб-сокет. Лист
 * лишався в історії співрозмовника як слова третьої сторони.
 *
 * ЧОМУ САМЕ ТУТ, А НЕ ВЗІРЕЦЬ НА РЯДОК. Взірець на «45 000 ₴» позеленів би
 * того дня, коли хтось перефразує текст, лишивши передачу. Замок стоїть на
 * дверях: жоден лист із чужим підписом не потрапляє в транспорт, хай там
 * що написано в тілі.
 *
 * Той самий клас прибрали з `sendMessage` 30.08.2026 («слова, вкладені в
 * чужі вуста… гірше за все інше, знайдене в месенджері») — і сусідня
 * функція лишилась відчиненою на шість днів.
 */

const SELF = {
  id: 'user_self',
  name: 'Кирило',
  avatar: '',
};

const baseMessage = () => ({
  id: `m_${Date.now()}`,
  senderId: SELF.id,
  senderName: SELF.name,
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text' as const,
  text: 'звичайний лист',
  isSelf: true,
});

const spyOnSend = () => vi.spyOn(messengerNetworkEngine, 'sendMessage');

describe('транспорт не приймає листа від чужого імені', () => {
  let sendSpy: ReturnType<typeof spyOnSend>;

  beforeEach(() => {
    sendSpy = spyOnSend().mockImplementation(() => 'p2p');
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    useMessengerStore.setState({
      currentUser: SELF as never,
      chats: [
        {
          id: 'chat_guard',
          title: 'Олександр',
          avatar: '',
          type: 'dm',
          circle: 'work',
          unreadCount: 0,
          messages: [],
        },
      ] as never,
      activeChatId: 'chat_guard',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('пропускає власний лист', () => {
    useMessengerStore.getState().addCustomMessage(baseMessage() as never);
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(useMessengerStore.getState().chats[0].messages).toHaveLength(1);
  });

  it('відмовляє листу з isSelf: false (підроблений вхідний)', () => {
    useMessengerStore.getState().addCustomMessage({
      ...baseMessage(),
      senderId: 'bot_ci',
      senderName: 'github-actions[bot]',
      isSelf: false,
    } as never);

    expect(sendSpy).not.toHaveBeenCalled();
    expect(useMessengerStore.getState().chats[0].messages).toHaveLength(0);
  });

  it('відмовляє листу від назви неіснуючого «рушія» попри isSelf: true', () => {
    for (const forged of [
      'Escrow Protocol Engine',
      'AI Chronicler (Living Spec)',
      'Planning Poker Engine',
      'LoRa 868MHz Mesh',
      'Sovereign E-Commerce Engine',
      'Whiteboard Task Converter',
      'Academy Quiz Master',
      'Async Stand-up Bot',
    ]) {
      useMessengerStore.getState().addCustomMessage({
        ...baseMessage(),
        senderName: forged,
        isSelf: true,
      } as never);
    }

    expect(sendSpy).not.toHaveBeenCalled();
    expect(useMessengerStore.getState().chats[0].messages).toHaveLength(0);
  });
});

/**
 * Друге око: замок вище ловить під час виконання, а цей взірець не дає
 * ЗАПИСАТИ підробку заново. Разом вони закривають і «перефразували текст»,
 * і «додали ще один рушій».
 */
describe('у дереві немає нових підроблених відправників', () => {
  const MESSENGER_DIR = join(__dirname, '..', 'components', 'messenger');

  const walk = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
      e.isDirectory()
        ? walk(join(dir, e.name))
        : e.name.endsWith('.tsx') || e.name.endsWith('.ts')
        ? [join(dir, e.name)]
        : [],
    );

  it('жоден файл не будує лист із isSelf: false', () => {
    const offenders = walk(MESSENGER_DIR).filter((path) => {
      const src = readFileSync(path, 'utf8');
      // Рядки в коментарях не рахуємо — вони описують ПРИБРАНЕ.
      const code = src
        .split('\n')
        .filter((l) => !l.trimStart().startsWith('//') && !l.trimStart().startsWith('*'))
        .join('\n');
      return /isSelf:\s*false/.test(code);
    });

    expect(offenders.map((p) => p.split('/').pop())).toEqual([]);
  });

  it('жоден файл не підписує лист рядком-літералом замість currentUser.name', () => {
    const offenders = walk(MESSENGER_DIR).filter((path) => {
      const src = readFileSync(path, 'utf8');
      const code = src
        .split('\n')
        .filter((l) => !l.trimStart().startsWith('//') && !l.trimStart().startsWith('*'))
        .join('\n');
      // `senderName: 'щось'` із літералом у лапках — саме так виглядала
      // кожна з девʼяти підробок. Живі виклики беруть імʼя зі стору.
      return /senderName:\s*['"`]/.test(code);
    });

    expect(offenders.map((p) => p.split('/').pop())).toEqual([]);
  });
});
