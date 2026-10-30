/**
 * Людина за @ніком — у ЗАГАЛЬНОМУ пошуку над розмовами, не окремим аркушем.
 *
 * Власник 28.09: «можна ж у той загальний пошук додати, а не ту фігуліну».
 * Довідник лишається точним: одна адреса — одна людина, переліку схожих він не
 * віддає. Три стани різні: знайшли, нікого, не спитали (немає мережі).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { messengerApi, type NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, peerNodeId: id,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const search = async (chats: Chat[], text: string, onSelectChat = vi.fn()) => {
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={onSelectChat}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );
  fireEvent.click(document.querySelector('[data-search-pill]') as HTMLElement);
  fireEvent.change(screen.getByLabelText('Пошук по всіх розмовах'), { target: { value: text } });
  await act(async () => new Promise((r) => setTimeout(r, 600)));
  return onSelectChat;
};

const people = () => screen.queryByRole('region', { name: 'Люди' });

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  useMessengerStore.setState({ typingStatus: {}, chats: [] });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prev, true);
});

describe('один пошук: люди за @ніком', () => {
  it('знайдений — у блоці «Люди»: імʼя й @нік, ключа нема; дотик відкриває розмову', async () => {
    vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([
      { id: 'u1', username: 'marta_kyiv', display_name: 'Марта', role: '', is_online: false, compact: 'PH2:ZTc2:483778' },
    ]);
    const start = vi.spyOn(messengerApi, 'startChatByUsername').mockResolvedValue({ id: 'conv1' } as NodeConversation);
    useMessengerStore.setState({ refreshConversations: vi.fn().mockResolvedValue(undefined) });
    const open = await search([chat('v', 'Володимир')], '@Marta_Kyiv');

    expect(messengerApi.listDirectoryUsers).toHaveBeenCalledWith('marta_kyiv');
    const block = people()!;
    expect(block.textContent).toContain('Марта');
    expect(block.textContent).toContain('@marta_kyiv');
    expect(document.body.textContent).not.toContain('PH2:');
    fireEvent.click(document.querySelector('[data-search-network="marta_kyiv"]')!);
    await waitFor(() => expect(open).toHaveBeenCalledWith('conv1'));
    expect(screen.queryByTestId('circle-multi-sheet')).toBeNull();
    expect(start).toHaveBeenCalledWith('marta_kyiv');
  });

  it('без «@» теж питає: «martakyiv» — це нік', async () => {
    vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([
      { id: 'u1', username: 'martakyiv', display_name: '', role: '', is_online: false },
    ]);
    await search([], 'martakyiv');
    expect(people()!.textContent).toContain('@martakyiv');
  });

  it('нікого — чесне «Нікого з таким ніком», схожих не підсовуємо', async () => {
    vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([
      { id: 'u2', username: 'martynenko', display_name: 'Мартиненко', role: '', is_online: false },
    ]);
    await search([], '@marta_k');
    expect(screen.getByText('Нікого з ніком @marta_k. Ніку треба ввести повністю')).toBeTruthy();
    expect(screen.queryByText('Мартиненко')).toBeNull();
  });

  it('без мережі — лише серед своїх, і не «нікого нема»', async () => {
    vi.spyOn(messengerApi, 'listDirectoryUsers').mockRejectedValue(new Error('offline'));
    await search([chat('k', 'Кирило', { handle: 'kyrylo_m1' })], '@kyr_nobody');
    expect(screen.getByText('Без мережі — шукаю лише серед ваших')).toBeTruthy();
    expect(screen.queryByText(/Нікого з ніком/)).toBeNull();
  });

  it('свою людину знаходить за початком ніка, без мережі', async () => {
    const ask = vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([]);
    await search([chat('k', 'Кирило', { handle: 'kyrylo_m1' })], '@kyrylo_m1');
    expect(people()!.textContent).toContain('Кирило');
    expect(people()!.textContent).toContain('@kyrylo_m1');
    expect(ask).not.toHaveBeenCalled();
  });

  it('кирилиця — не нік: довідник не питаємо, свої знаходяться за іменем', async () => {
    const ask = vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([]);
    await search([chat('k', 'Кирило')], 'кир');
    expect(people()!.textContent).toContain('Кирило');
    expect(ask).not.toHaveBeenCalled();
  });

  it('окремого входу «Знайти за @ніком» більше нема — ні в списку, ні в «Новій розмові»', () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: true, status: 200, data: [] }),
    };
    render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={() => {}} />);
    expect(screen.queryByText('Знайти за @ніком')).toBeNull();
    expect(screen.queryByTestId('new-directory-sheet')).toBeNull();
    expect(document.querySelector('[data-handle-search]')).toBeNull();
  });
});
