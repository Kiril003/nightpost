/**
 * СМАЙЛ ДОСЯЖНИЙ ПАЛЬЦЕМ — і три його стани видно без жодного дотику.
 *
 * Виміряно 18.09 на телефонній оболонці (`isPhoneShell()` = є `PhantomBridge`):
 *
 *  - панель наведення (`hoveredMessageId`) на дотику не існує взагалі;
 *  - рядок швидких смайлів в аркуші довгого натискання стояв за
 *    `!__PHONE_BUILD__ && !phone` (ChatArea.tsx:2973, коміт ab0eded2, чиїм
 *    предметом були дзвінки — причини для реакцій там не названо);
 *  - «+» біля наявних смайлів ховався тією ж перевіркою (ChatArea.tsx:2767);
 *  - «Редагувати» — так само (ChatArea.tsx:3248), при живих обох половинах.
 *
 * Тобто на телефоні вибрати смайл було НІЧИМ: лишався подвійний дотик, який
 * ставить ❤️ і нічого більше. Саме це стереже цей файл.
 *
 * Перевіряється на РЕНДЕРІ, а не пошуком підрядка в джерелі: сторож, який
 * читає файл, зеленіє й тоді, коли гілку обходить інша умова вище.
 *
 * Мутації (заміряні): повернути `{!phone && (` на рядок швидких смайлів —
 * червоніє «аркуш довгого натискання несе смайли»; повернути його на «+» —
 * червоніє ««+» під рядком»; повернути на «Редагувати» — червоніє
 * «редагувати своє»; злити три вигляди дороги в один — червоніє «три стани».
 */
import { beforeAll, afterEach, describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message, Reaction } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

/** Телефонна оболонка — це наявність мосту, а не ширина вікна. */
const asPhone = () => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
};

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.useRealTimers();
});

const CHAT = {
  id: 'chat_dm',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const line = (over: Partial<Message> = {}): Message =>
  ({
    id: 'm1',
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    sentAt: new Date(Date.now() - 60_000).toISOString(),
    type: 'text',
    text: 'Виїжджаємо за десять хвилин',
    isSelf: false,
    ...over,
  }) as unknown as Message;

const noop = () => {};

function feed(messages: Message[], onAddReaction: ChatAreaReact = noop) {
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={CHAT}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={onAddReaction}
        onReplyMessage={noop}
        onEditMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );
}

type ChatAreaReact = (messageId: string, emoji: string) => Promise<string | null> | void;

/** Довге натискання — єдина дотикова дорога до дій над рядком. */
function longPress(node: Element) {
  vi.useFakeTimers();
  fireEvent.touchStart(node, { touches: [{ clientX: 10, clientY: 10 }] });
  act(() => {
    vi.advanceTimersByTime(500);
  });
  vi.useRealTimers();
}

const bubble = () => screen.getByText('Виїжджаємо за десять хвилин').closest('div[class*="msg-bubble"]')
  ?? screen.getByText('Виїжджаємо за десять хвилин');

describe('смайл на телефоні', () => {
  it('аркуш довгого натискання НЕСЕ смайли', () => {
    asPhone();
    feed([line()]);

    longPress(bubble());

    // Аркуш відкрився — це видно по діях, які в ньому були й до правки.
    expect(screen.getByText('Відповісти')).toBeTruthy();
    // А оце було відсутнє: жодного смайла на дотиковій дорозі.
    expect(screen.getByLabelText('Реакція ❤️')).toBeTruthy();
    expect(screen.getByLabelText('Реакція 😂')).toBeTruthy();
    expect(screen.getByLabelText('Більше емодзі')).toBeTruthy();
  });

  it('дотик по смайлу в аркуші кличе позначку саме тим смайлом', () => {
    asPhone();
    const react = vi.fn();
    feed([line()], react);

    longPress(bubble());
    fireEvent.click(screen.getByLabelText('Реакція 😮'));

    expect(react).toHaveBeenCalledWith('m1', '😮');
  });

  it('під бульбашкою — лише наявні смайли (пігулка `raised` 28, «приземляється»); новий ставиться довгим дотиком', () => {
    asPhone();
    feed([line({ reactions: [{ emoji: '❤️', count: 1, users: ['peer'] }] })]);
    // Пунктирного «+» біля смайлів немає (як у Telegram): дорога до нового — аркуш.
    expect(screen.queryByLabelText('Додати реакцію')).toBeNull();
    const pill = screen.getByLabelText(/❤️/);
    expect(pill.className).toContain('h-7');
    expect(pill.className).toContain('ph-land');
    longPress(bubble());
    expect(screen.getByLabelText('Реакція ✅')).toBeTruthy();
  });

  it('макет 05: головне — рядками з іконками, решта тихими рядками за «Ще»', () => {
    asPhone();
    feed([line({ senderId: 'me', isSelf: true })]);

    longPress(bubble());

    for (const t of ['Відповісти', 'Копіювати', 'Переслати', 'Редагувати']) expect(screen.getByText(t)).toBeTruthy();
    expect(screen.queryByText('Подробиці')).toBeNull();
    expect(screen.queryByText(/Картка:|Видалити повідомлення/)).toBeNull();
    fireEvent.click(screen.getByText('Ще'));
    expect(screen.getByText('Подробиці')).toBeTruthy();
    expect(screen.getByText('Видалити')).toBeTruthy();
  });

  it('редагувати СВОЄ можна й на телефоні: обидві половини дороги живі', () => {
    asPhone();
    feed([line({ senderId: 'me', isSelf: true })]);

    longPress(bubble());

    expect(screen.getByText('Редагувати')).toBeTruthy();
  });
});

describe('три стани позначки — три різні вигляди', () => {
  /** Пігулка смайла, знайдена за підписом, який вона зобовʼязана мати. */
  const pill = (r: Reaction) => {
    const { unmount } = feed([line({ reactions: [r] })]);
    const found = screen.getAllByLabelText(new RegExp(`^${r.emoji},`))[0];
    const shot = { label: found.getAttribute('aria-label') || '', cls: found.className };
    unmount();
    return shot;
  };

  it('доставлене, у дорозі, у черзі й невдале не виглядають однаково', () => {
    const delivered = pill({ emoji: '❤️', count: 1, users: ['me'] });
    const sending = pill({ emoji: '❤️', count: 1, users: ['me'], mine: 'sending' });
    const queued = pill({ emoji: '❤️', count: 1, users: ['me'], mine: 'queued' });
    const failed = pill({ emoji: '❤️', count: 1, users: ['me'], mine: 'failed' });

    // Кожен стан має СВОЄ речення — не лише колір: колір нічого не каже тому,
    // хто читає екран уголос, і тоне на сірому склі.
    const said = [delivered.label, sending.label, queued.label, failed.label];
    expect(new Set(said).size).toBe(4);

    // І свій вигляд: доставлене не має права виглядати як те, що в черзі.
    expect(delivered.cls).not.toBe(queued.cls);
    expect(delivered.cls).not.toBe(failed.cls);
    expect(failed.cls).not.toBe(queued.cls);
  });

  it('підпис називає ЛЮДЕЙ, а не ключі вузлів', () => {
    const mine = pill({ emoji: '👍', count: 2, users: ['me', 'node_8f3ac1d0e4b7'], names: ['Я', 'Оксана'] });

    expect(mine.label).toContain('Я');
    expect(mine.label).toContain('Оксана');
    // Ключ вузла на склі — це те, що екран відповідав на питання «хто?».
    expect(mine.label).not.toContain('node_8f3ac1d0e4b7');
  });

  it('вузол не назвав імен — коротка форма ключа, а не вигадане імʼя', () => {
    const shot = pill({ emoji: '👍', count: 1, users: ['node_8f3ac1d0e4b7'] });

    expect(shot.label).toContain('node_8f3');
    expect(shot.label).not.toContain('node_8f3ac1d0e4b7');
  });
});
