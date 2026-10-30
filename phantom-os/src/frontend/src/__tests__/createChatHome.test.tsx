/**
 * «Нова розмова» на телефоні (27.09): свої люди кулями, пошук, @нік.
 *
 * Досі тут стояли двоє дверей «Людина» / «Група», а щоб написати тому, хто вже
 * є у списку, треба було йти через запрошення. Тепер людина зі списку — один
 * дотик: її розмова, а якщо ще немає — нова з її ключем.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { messengerApi } from '../services/messengerApi';
import { openMeetupAsk, useMeetupAsk } from '../services/nativeMeetupDraft';
import { renderHook } from '@testing-library/react';
import type { NodeContact, NodeConversation } from '../services/messengerApi';

const contact = (id: string, name: string, ready = true): NodeContact => ({
  id,
  peer_node_id: `node-${id}`,
  display_name: name,
  peer_address: null,
  safety_number: '',
  safety_number_pretty: '',
  verified: true,
  session_ready: ready,
  created_at: '2026-09-05T10:00:00',
});

const conv = (id: string, contactId: string): NodeConversation =>
  ({ id, title: 'x', kind: 'direct', circle: 'all', handle: null, avatar: null, pinned: false, archived: false,
    created_at: '', updated_at: '', contact_id: contactId }) as NodeConversation;

let ready: ReturnType<typeof vi.fn>;
const open = () => render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={ready} />);

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
    call: () => JSON.stringify({ ok: true, status: 200, data: [] }),
  };
  ready = vi.fn();
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('c1', 'Оксана'), contact('c2', 'Тарас')]);
});
afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

describe('«Нова розмова» на телефоні', () => {
  it('свої люди стоять одразу, без дверей «Людина»', async () => {
    open();
    expect(await screen.findByText('Оксана')).toBeTruthy();
    expect(screen.getByText('Тарас')).toBeTruthy();
    expect(screen.queryByText('Людина')).toBeNull();
    expect(screen.getByText('Нова група')).toBeTruthy();
    // 08.10: «Запросити» і «Маю запрошення» злились у одні двері «Додати людину».
    expect(screen.getByRole('button', { name: /Додати людину/ })).toBeTruthy();
  });

  it('дотик по людині, з якою розмова вже є, — відкриває саме її', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([conv('dm-9', 'c2')]);
    const create = vi.spyOn(messengerApi, 'createConversation');
    open();
    fireEvent.click(await screen.findByText('Тарас'));
    await waitFor(() => expect(ready).toHaveBeenCalledWith('dm-9'));
    expect(create).not.toHaveBeenCalled();
  });

  it('розмови ще нема — нова з ключем саме цієї людини', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    const create = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue(conv('dm-new', 'c1'));
    open();
    fireEvent.click(await screen.findByText('Оксана'));
    await waitFor(() => expect(ready).toHaveBeenCalledWith('dm-new'));
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ contact_id: 'c1', title: 'Оксана' }));
  });

  it('пошук звужує список; @нік — у тому ж полі, без окремого аркуша', async () => {
    const lookup = vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([]);
    open();
    await screen.findByText('Оксана');
    fireEvent.change(screen.getByPlaceholderText('Імʼя або @нік'), { target: { value: 'тар' } });
    expect(screen.queryByText('Оксана')).toBeNull();
    expect(screen.getByText('Тарас')).toBeTruthy();

    fireEvent.change(screen.getByPlaceholderText('Імʼя або @нік'), { target: { value: '@marta' } });
    await waitFor(() => expect(lookup).toHaveBeenCalledWith('marta'));
    expect(await screen.findByText('Нікого з таким ніком')).toBeTruthy();
    expect(screen.queryByText('Знайти за @ніком')).toBeNull();
  });

  it('прийняте запрошення — без «вузла» й ключа, лише людина та її імʼя', async () => {
    open();
    fireEvent.click(await screen.findByRole('button', { name: /Додати людину/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Вставити запрошення' }));
    fireEvent.change(screen.getByLabelText('Запрошення'), { target: { value: 'PH2:ZxYwVuTs987:0f9e8d' } });
    expect(await screen.findByTestId('incoming-invite')).toBeTruthy();
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/Вузол|PH2:ZxYw/);
    expect(screen.getByLabelText('Імʼя людини')).toBeTruthy();
  });
});

/**
 * 03.10, v1.2: на ПК — той самий аркуш «Нова розмова», а не стара модалка з
 * вкладками Група/Діалог/Запрошення. Глобальний довідник, який жив лише у
 * вкладці «Діалог», має власний тихий рядок — без моста, бо на телефоні його
 * роль грає @нік у тому ж полі. Поверніть гілку з вкладками — тести червоніють.
 */
describe('«Нова розмова» на ПК — той самий аркуш', () => {
  beforeEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('без моста — аркуш new-chat-sheet, без вкладок «Група/Діалог/Запрошення»', async () => {
    open();
    expect(await screen.findByTestId('new-chat-sheet')).toBeTruthy();
    expect(await screen.findByText('Оксана')).toBeTruthy();
    expect(screen.getByText('Нова група')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Додати людину/ })).toBeTruthy();
    for (const tab of ['Група', 'Діалог', 'Запрошення']) expect(screen.queryByRole('button', { name: tab })).toBeNull();
    expect(screen.queryByText(/Користувачі вузла|Категорія контакту/)).toBeNull();
  });

  it('@нік шукається й на ПК — у тому ж полі', async () => {
    const lookup = vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([]);
    open();
    await screen.findByText('Оксана');
    fireEvent.change(screen.getByPlaceholderText('Імʼя або @нік'), { target: { value: '@marta' } });
    await waitFor(() => expect(lookup).toHaveBeenCalledWith('marta'));
    expect(await screen.findByText('Нікого з таким ніком')).toBeTruthy();
  });

  it('глобальний довідник — окремим рядком і кроком із «Назад»', async () => {
    open();
    await screen.findByText('Оксана');
    fireEvent.click(screen.getByRole('button', { name: 'Глобальний довідник' }));
    expect(await screen.findByTestId('directory-search')).toBeTruthy();
    expect(screen.getByTestId('new-directory-sheet')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    expect(await screen.findByTestId('new-chat-sheet')).toBeTruthy();
  });

  it('на телефоні рядка довідника немає', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: true, status: 200, data: [] }),
    };
    open();
    await screen.findByText('Оксана');
    expect(screen.queryByRole('button', { name: 'Глобальний довідник' })).toBeNull();
  });
});

describe('мої пристрої — не «Ваші люди»', () => {
  it('ноутбук, ПК-вузол і позначений рідним боком пристрій не стоять серед людей; у групі теж', async () => {
    const { useMessengerStore } = await import('../stores/messengerStore');
    useMessengerStore.setState({
      chats: [{ id: 'c5', title: 'Робочий', type: 'dm', circle: 'all', mine: true, avatar: '', unreadCount: 0, pinned: false, messages: [] }],
    } as never);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      contact('c1', 'Оксана'),
      contact('c3', 'Ноутбук'),
      contact('c4', 'ПК-вузол'),
      contact('c5', 'Робочий'),
    ]);
    open();
    expect(await screen.findByText('Оксана')).toBeTruthy();
    for (const name of ['Ноутбук', 'ПК-вузол', 'Робочий']) expect(screen.queryByText(name)).toBeNull();
    fireEvent.click(screen.getByText('Нова група'));
    expect(await screen.findByText('Оксана')).toBeTruthy();
    for (const name of ['Ноутбук', 'ПК-вузол', 'Робочий']) expect(screen.queryByText(name)).toBeNull();
    useMessengerStore.setState({ chats: [] } as never);
  });

  it('«Новий план» — двері до плану просто з «Нової розмови»: аркуш закривається, відкривається чернетка зустрічі', async () => {
    openMeetupAsk(null);
    const onClose = vi.fn();
    render(<CreateChatModal isOpen onClose={onClose} onConversationReady={ready} />);
    await screen.findByText('Оксана');
    const ask = renderHook(() => useMeetupAsk());
    fireEvent.click(screen.getByTestId('door-plan'));
    expect(onClose).toHaveBeenCalled();
    await waitFor(() => expect(ask.result.current.ask).toEqual({ kind: 'meetup', chatId: null }));
    openMeetupAsk(null);
  });
});
