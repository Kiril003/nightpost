/**
 * Особа — ключ, імʼя вільне, @нік — глобальна адреса (docs/IDENTITY.md).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { messengerApi, chatFromNode, type NodeConversation } from '../services/messengerApi';
import { ApiError } from '../services/api';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import { addedLine, checkNewHandle, namesakeHint, namesakeNotice, takenWord } from '../services/peopleIdentity';
import { displayTitle, selfWord } from '../components/messenger/chatListFormat';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, peerNodeId: id,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const renderList = (chats: Chat[], onSelectChat = vi.fn()) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={onSelectChat}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  useMessengerStore.setState({ typingStatus: {}, chats: [] });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prev, true);
});

describe('міст → розмова', () => {
  it('нік, «мій пристрій» і дата додавання доїжджають до рядка', () => {
    const row = {
      id: 'k1', title: 'Кирило', kind: 'dm', circle: 'all', handle: 'kyrylo_m1', mine: false, avatar: null,
      pinned: false, archived: false, created_at: '2026-09-06T03:45:11Z', updated_at: '2026-09-06T03:45:11Z',
    } as NodeConversation;
    const c = chatFromNode(row);
    expect(c.handle).toBe('kyrylo_m1');
    expect(c.mine).toBe(false);
    expect(c.addedAt).toBe('2026-09-06T03:45:11Z');
    expect(chatFromNode({ ...row, mine: true }).mine).toBe(true);
  });
});

describe('однакові імена', () => {
  const a = chat('a', 'Оля', { addedAt: '2026-09-06T00:49:37Z' });
  const b = chat('b', 'Оля', { addedAt: '2026-09-06T00:45:11Z' });
  const k = chat('k', 'Кирило', { addedAt: '2026-09-01T10:00:00Z', handle: 'kyrylo_m1' });
  const k2 = chat('k2', 'Кирило', { addedAt: '2026-09-18T10:00:00Z' });

  it('біля імені — лише нік; дата додавання НЕ клеїться до імені ніколи (A52, 02.10)', () => {
    const all = [a, b, k, k2];
    expect(namesakeHint(k, all)).toBe('@kyrylo_m1');
    // Була «додано 18 вер.» — у шапці це різалось у «Новий … · додано 27 …».
    expect(namesakeHint(k2, all)).toBeNull();
    expect(namesakeHint(a, all)).toBeNull();
    expect(namesakeHint(chat('z', 'Зоряна'), all)).toBeNull();
  });

  it('у списку двох Кирилів: у кого є нік — той із ніком, дати немає ні в кого', () => {
    renderList([k, k2]);
    const hints = [...document.querySelectorAll('[data-row-hint]')].map((e) => e.textContent);
    expect(hints).toEqual(['· @kyrylo_m1']);
    expect(document.body.textContent).not.toMatch(/додано/);
  });

  it('дата додавання — словом для картки людини', () => {
    const year = new Date().getFullYear();
    expect(addedLine(`${year}-09-27T00:49:00`)).toBe('Додано 27 вер.');
    expect(addedLine('2024-03-05T10:00:00Z')).toBe('Додано 5 бер. 2024 р.');
    expect(addedLine(undefined)).toBeNull();
    expect(addedLine('не дата')).toBeNull();
  });

  it('новий контакт з імʼям наявного — одна тиха примітка, у давнішого — жодної', () => {
    const all = [k, k2];
    expect(namesakeNotice(k2, all)).toBe('Це інша людина, ніж Кирило з ваших контактів');
    expect(namesakeNotice(k, all)).toBeNull();
  });
});

describe('жодного id на екрані й одне «Ви»', () => {
  it('контакт без імені — «Без імені», без дати в рядку, а не «Вузол 4d839df7» і не «Новий контакт»', () => {
    expect(displayTitle('Вузол 4d839df7')).toBe('Без імені');
    expect(displayTitle('d1d2·7b5f')).toBe('Без імені');
    expect(displayTitle('Вузол 192.168.1.5:8000')).toBe('Без імені');
    // Колишня заглушка, яку веб сам записував у вузол, — теж не імʼя.
    expect(displayTitle('Новий контакт')).toBe('Без імені');
    expect(displayTitle('Новий контакт · 27 вер., 00:49')).toBe('Без імені');
    expect(displayTitle('Вузолович')).toBe('Вузолович');
    const nc = chat('n', 'Вузол 4d839df7', { addedAt: '2026-09-06T10:00:00Z' });
    const nc2 = chat('n2', 'Новий контакт', { addedAt: '2026-09-27T00:49:00Z' });
    renderList([nc, nc2]);
    const row = document.querySelector('[data-chat-row="n"]') as HTMLElement;
    expect(row.textContent).toContain('Без імені');
    // Двоє без імені — і все одно без «додано …» біля імені.
    expect(document.querySelector('[data-row-hint]')).toBeNull();
    expect(document.body.textContent).not.toMatch(/4d839df7|Вузол|додано|Новий контакт/);
    expect(namesakeNotice(chat('n3', 'Вузол 1234abcd', { addedAt: '2026-09-07T10:00:00Z' }), [nc])).toBeNull();
  });

  it('свій останній лист у групі — «Ви:», хоч міст каже me, хоч стор каже «Я»; у розмові вдвох — без приписки', () => {
    expect(selfWord('Я')).toBe('Ви');
    expect(selfWord('me')).toBe('Ви');
    renderList([
      chat('a', 'Двір', { type: 'group', lastAuthor: 'Я', lastSnippet: 'йду' }),
      chat('b', 'Сімʼя', { type: 'group', lastAuthor: 'me', lastSnippet: 'ок' }),
      chat('c', 'Тарас', { lastAuthor: 'me', lastSnippet: 'буду' }),
    ]);
    const lines = [...document.querySelectorAll('[data-testid="chat-preview-line"]')].map((e) => e.textContent);
    expect(lines).toEqual(expect.arrayContaining(['Ви: йду', 'Ви: ок', 'буду']));
    expect(lines.join(' ')).not.toContain('Я:');
  });
});

describe('мої пристрої', () => {
  it('ПК і ноутбук не стоять серед людей — один рядок «Мої пристрої»', () => {
    const open = vi.fn();
    renderList(
      [
        chat('pc', 'ПК · Nightpost', { mine: true, lastAt: '2026-10-17T10:00:00Z' }),
        chat('nb', 'Ноутбук', { mine: true, lastSnippet: undefined, lastAt: '2026-10-17T09:00:00Z' }),
        chat('v', 'Володимир'),
      ],
      open,
    );
    expect(document.querySelector('[data-chat-row="pc"]')).toBeNull();
    const row = document.querySelector('[data-folder-row="devices"]') as HTMLElement;
    expect(row.textContent).toContain('Мої пристрої');
    expect(row.textContent).toContain('ПК · Nightpost, Ноутбук');
    fireEvent.click(row);
    // Свій пристрій відкривається вікном «Мій пристрій», листування — з нього.
    fireEvent.click(document.querySelector('[data-chat-row="pc"]') as HTMLElement);
    expect(open).not.toHaveBeenCalled();
    const sheet = screen.getByTestId('device-sheet');
    fireEvent.click(within(sheet).getByText('Листування'));
    expect(open).toHaveBeenCalledWith('pc');
    expect(screen.queryByTestId('device-sheet')).toBeNull();
    // Однакова назва пристрою з людиною — не «тезки».
    expect(namesakeHint(chat('x', 'Ноутбук'), [chat('nb', 'Ноутбук', { mine: true }), chat('x', 'Ноутбук')])).toBeNull();
  });
});

// Пошук «@нік» — у загальному пошуку, див. directoryPhone.test.tsx.

describe('«Я»: мій нік', () => {
  const openMe = () =>
    render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);

  beforeEach(() => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    // Нік задається під іменем; без імені «Я» спершу питає «Як вас звати?».
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, name: 'Кирило' } });
  });

  it('правила ніка — ті самі, що в довіднику', () => {
    expect(checkNewHandle('@Kyrylo_M1')).toEqual({ ok: true, handle: 'kyrylo_m1' });
    expect(checkNewHandle('kyrylo').ok).toBe(false);
    expect(checkNewHandle('кирило_м1').ok).toBe(false);
  });

  it('немає ніка — «Нікнейм — додати»; зайнятий — сказано, який саме', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    const publish = vi
      .spyOn(messengerApi, 'directoryPublish')
      .mockRejectedValue(new ApiError(409, 'taken', 'Імʼя вже зайняте'));
    openMe();
    fireEvent.click(await screen.findByText('Нікнейм — додати'));
    fireEvent.change(await screen.findByLabelText('Нікнейм'), { target: { value: 'kyrylo_m1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    await waitFor(() => expect(screen.getByTestId('handle-note').textContent).toBe(takenWord('kyrylo_m1')));
    expect(publish).toHaveBeenCalledWith('kyrylo_m1');
    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_m1 зайнятий');
  });

  it('є нік — видно @нік під імʼям', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: 'kyrylo_m1', published: true });
    openMe();
    await waitFor(() => expect(screen.getByTestId('me-handle').textContent).toBe('@kyrylo_m1'));
  });
});
