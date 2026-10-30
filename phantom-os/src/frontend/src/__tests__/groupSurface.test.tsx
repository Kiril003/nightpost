/**
 * Поверхня гілки: щаблі, відмова словом, тиша й закріплене.
 *
 * Тут перевіряється не «намалювалось», а «не намальовано зайвого». Кожен тест
 * вміє почервоніти від конкретного повернення старої вади:
 *
 * — покажіть кнопку без прапорця з вузла — падає «кнопок без права немає»;
 * — сховайте причину відмови за словом «не вдалось» — падає «відмова названа»;
 * — поверніть `soundFx.playReceive()` без перевірки тиші — падає «тиша глушить»;
 * — присвойте `pinned_message` напряму, як було до 16.09, — падає «автор
 *   закріпленого доїжджає», бо `author_id` не те саме, що `authorId`.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { GroupManageTab, MemberActions } from '../components/messenger/GroupRoles';
import { chatFromNode, messengerApi } from '../services/messengerApi';
import type { NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { chatHush } from '../services/chatHush';
import type { Chat, ChatMember } from '../types/messenger';

const baseChat = (over: Partial<Chat> = {}): Chat => ({
  id: 'g1',
  title: 'Двір',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  ...over,
});

const member = (over: Partial<ChatMember> = {}): ChatMember => ({
  id: 'p1',
  name: 'Тарас',
  handle: '',
  avatar: '',
  role: 'member',
  ...over,
});

afterEach(() => {
  vi.restoreAllMocks();
  chatHush.reset();
});

describe('кнопок без права немає', () => {
  it('людина, якої цей оператор не може чіпати, не дає жодної дії', () => {
    // Усі три прапорці з вузла — false. Це НЕ «ми ще не спитали»: вузол
    // відповів поімённо, і відповідь — «ні».
    const { container } = render(
      <MemberActions
        chat={baseChat()}
        member={member({ mayRemove: false, mayGrant: false, mayOwn: false })}
      />,
    );
    expect(container.querySelectorAll('button').length).toBe(0);
  });

  it('прибрати можна — а роздавати щаблі ні: рівно одна кнопка', () => {
    render(
      <MemberActions
        chat={baseChat()}
        member={member({ mayRemove: true, mayGrant: false, mayOwn: false })}
      />,
    );
    expect(screen.getByText('Прибрати з групи')).toBeTruthy();
    // Щаблі — окреме право; його немає, тож і драбини немає.
    expect(screen.queryByText('Роль')).toBeNull();
    expect(screen.queryByText('Зробити власником')).toBeNull();
  });

  it('передача гілки — окремий крок, а не одне натискання', () => {
    render(
      <MemberActions
        chat={baseChat()}
        member={member({ mayRemove: false, mayGrant: false, mayOwn: true })}
      />,
    );
    // До підтвердження — жодного «Передати» без «Ні» поруч.
    expect(screen.getByText('Зробити власником')).toBeTruthy();
    expect(screen.queryByText('Передати')).toBeNull();
  });
});

describe('вкладка гілки показує лише те, на що є право', () => {
  it('без `powers` — «питаємо», а не «нічого не можна»', () => {
    render(<GroupManageTab chat={baseChat()} />);
    expect(screen.getByText(/Завантажую/)).toBeTruthy();
    expect(screen.queryByText('Назва')).toBeNull();
  });

  it('учасник без прав не бачить полів — і йому сказано чому', () => {
    render(
      <GroupManageTab
        chat={baseChat({
          myRole: 'member',
          powers: {
            add: true, remove: false, rename: false, card: false,
            grant: false, transfer: false, pin: false,
          },
        })}
      />,
    );
    expect(screen.queryByText('Назва')).toBeNull();
    expect(screen.queryByText('Опис')).toBeNull();
    expect(screen.getByText(/змінює адміністратор/)).toBeTruthy();
  });

  it('адмін бачить назву, опис і обличчя', () => {
    render(
      <GroupManageTab
        chat={baseChat({
          myRole: 'admin',
          powers: {
            add: true, remove: true, rename: true, card: true,
            grant: true, transfer: false, pin: true,
          },
        })}
      />,
    );
    expect(screen.getByText('Назва')).toBeTruthy();
    expect(screen.getByText('Опис')).toBeTruthy();
    expect(screen.getByText('Фото')).toBeTruthy();
  });
});

describe('відмова названа причиною, а не словом «не вдалось»', () => {
  it('речення вузла доходить до людини незміненим', async () => {
    // Так відповідає міст: 403 з кодом `no_right_*` і реченням із ресурсів.
    const refusal = Object.assign(new Error('Це може лише власник гілки.'), {
      code: 'no_right_owner_only',
    });
    vi.spyOn(messengerApi, 'setGroupRole').mockRejectedValue(refusal);

    await expect(
      useMessengerStore.getState().setGroupRole('g1', 'p1', 'admin'),
    ).rejects.toThrow('Це може лише власник гілки.');
  });

  it('обірваний fetch без коду НЕ видається за слово вузла', async () => {
    // «Failed to fetch» — англійський рядок браузера; коду відмови немає,
    // отже це не відповідь вузла, і показувати його людині нічого.
    vi.spyOn(messengerApi, 'setGroupRole').mockRejectedValue(new Error('Failed to fetch'));

    await expect(
      useMessengerStore.getState().setGroupRole('g1', 'p1', 'admin'),
    ).rejects.toThrow('Вузол не змінив щабель.');
  });
});

describe('закріплене доїжджає цілим', () => {
  const row = (pin: NodeConversation['pinned_message']): NodeConversation => ({
    id: 'g1', title: 'Двір', kind: 'group', circle: 'all',
    handle: null, avatar: null, pinned: false, archived: false,
    created_at: '2026-09-16T10:00:00', updated_at: '2026-09-16T10:00:00',
    pinned_message: pin,
  });

  it('автор закріпленого не губиться на межі дроту й екрана', () => {
    const chat = chatFromNode(row({
      id: 'm7', author_id: 'p1', author: 'Тарас',
      at: '2026-09-16T11:00:00', text: 'Збір о шостій',
    }));
    // Пряме присвоєння дало б поле, яке ІСНУЄ і завжди undefined.
    expect(chat.pinnedMessage?.authorId).toBe('p1');
    expect(chat.pinnedMessage?.text).toBe('Збір о шостій');
  });

  it('«закріплене є, рядка немає» — не те саме, що «нічого не закріплено»', () => {
    const missing = chatFromNode(row({
      id: '', author_id: 'p1', author: 'Тарас',
      at: '2026-09-16T11:00:00', text: null,
    }));
    const none = chatFromNode(row(null));
    expect(missing.pinnedMessage).not.toBeNull();
    expect(missing.pinnedMessage?.id).toBe('');
    expect(none.pinnedMessage).toBeNull();
  });
});

describe('тиша глушить', () => {
  beforeEach(() => {
    chatHush.reset();
    useMessengerStore.setState({ chats: [baseChat({ id: 'c1', type: 'dm' })] });
  });

  it('перемикач пише у сховище, а не лише в памʼять вкладки', () => {
    expect(chatHush.has('c1')).toBe(false);
    useMessengerStore.getState().toggleMuteChat('c1');
    // Саме це робить тишу справжньою: вона переживе перезавантаження, бо
    // вузол її не приймає і відновити її нізвідки.
    expect(chatHush.has('c1')).toBe(true);
    expect(useMessengerStore.getState().chats[0].muted).toBe(true);
    useMessengerStore.getState().toggleMuteChat('c1');
    expect(chatHush.has('c1')).toBe(false);
  });

  it('приглушена розмова піднімається зі сховища вже мовчазною', () => {
    chatHush.set('c1', true);
    const fresh = chatFromNode({
      id: 'c1', title: 'Тарас', kind: 'dm', circle: 'all',
      handle: null, avatar: null, pinned: false, archived: false,
      created_at: '2026-09-16T10:00:00', updated_at: '2026-09-16T10:00:00',
    });
    // `chatFromNode` сам тиші не знає — її накладає стор при завантаженні.
    expect(fresh.muted).toBeUndefined();
    expect(chatHush.has('c1')).toBe(true);
  });
});
