/**
 * Документ розмови (CanvasSplitView) і показові картки на компʼютері.
 *
 * 04.10: дев'ять демо-рендерерів робочої ОС (дошка, RACI, план-графік,
 * «Wasm»-код, картки навчання, відеоогляд, diff, SVG, подія) віднято — живих
 * даних у них не було, створити їх людина не могла. На ПК вони тепер, як і на
 * телефоні, коротка картка без обіцянки дверей. Документ лишився: у нього є
 * справжній виробник («У чат») і справжні двері.
 */
import { beforeAll, describe, it, expect, vi, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CanvasSplitView } from '../components/messenger/CanvasSplitView';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  localStorage.clear();
});

const OLD_SKIN = /#5F6A60|#202E25|\b(text|bg|hover:bg|hover:text)-(red|emerald|cyan)-\d{3}|placeholder-\[/;

describe('документ розмови', () => {
  // Нова дошка порожня: засіяні «Фінальні домовленості та архітектура» з
  // дорученнями на двох людей були вигаданим вмістом у чужій розмові.
  it('новий документ порожній і каже про це словами', () => {
    render(<CanvasSplitView chatTitle="Оксана" onClose={vi.fn()} />);
    expect(screen.getByText(/Документ порожній/)).toBeDefined();
    expect(screen.queryByDisplayValue('Фінальні домовленості та архітектура')).toBeNull();
    // Двері, щоб його наповнити, лишились на місці.
    expect(screen.getByText('Додати блок:')).toBeDefined();
    expect(screen.getAllByText('Документ').length).toBeGreaterThan(0);
  });

  it('каже правду про себе: лише на цьому пристрої, без «Синхронізовано» і «Wasm Live Code»', () => {
    const { container } = render(<CanvasSplitView chatTitle="Оксана" onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /Код/ }));
    fireEvent.click(screen.getByRole('button', { name: /Рішення/ }));
    const text = container.textContent ?? '';
    expect(text).toContain('Лише на цьому пристрої');
    expect(text).not.toContain('Синхронізовано');
    expect(text).not.toContain('Wasm Live Code');
    // Новий блок — порожній: підказку несе placeholder, а не вигаданий вміст.
    expect(screen.queryByDisplayValue('Ухвалене рішення...')).toBeNull();
    // Мова — людей: «Закрити документ», а не «Закрити Canvas»; види — словами.
    expect(screen.getByRole('button', { name: 'Закрити документ' })).toBeDefined();
    fireEvent.click(screen.getByTitle('Схема'));
    expect(container.textContent).not.toMatch(/action-item|вузол/);
    // Шкіра — токени, а не лісова палітра.
    expect(container.innerHTML).not.toMatch(OLD_SKIN);
  });

  it('фрагмент зі скриньки лягає в документ рівно раз і зберігається на пристрої', () => {
    const taken = vi.fn();
    const inbox = [{ id: 'i1', text: 'Зустріч о 19:40 біля поштамту' }];
    const { rerender } = render(
      <CanvasSplitView chatTitle="Оксана" chatId="c1" onClose={vi.fn()} inbox={inbox} onInboxTaken={taken} />,
    );
    expect(screen.getAllByDisplayValue('Зустріч о 19:40 біля поштамту')).toHaveLength(1);
    expect(taken).toHaveBeenCalledWith(['i1']);
    rerender(<CanvasSplitView chatTitle="Оксана" chatId="c1" onClose={vi.fn()} inbox={inbox} onInboxTaken={taken} />);
    expect(screen.getAllByDisplayValue('Зустріч о 19:40 біля поштамту')).toHaveLength(1);
    expect(localStorage.getItem('phantom_canvas_v3_c1_root_thread')).toContain('Зустріч о 19:40');
  });
});

const CHAT = {
  id: 'chat_o',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const msg = (over: Partial<Message>, id: string): Message =>
  ({ id, senderId: 'p1', senderName: 'Оксана', senderAvatar: '', timestamp: '12:40', type: 'text', isSelf: false, ...over }) as unknown as Message;

const renderChat = (messages: Message[]) => {
  useMessengerStore.setState({
    chats: [{ ...CHAT, messages }],
    activeChatId: CHAT.id,
    currentUser: { id: 'me', name: 'Кирило', avatar: '' },
  } as never);
  const noop = () => {};
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={{ ...CHAT, messages }}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={noop}
        onReplyMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );
};

describe('показові картки на компʼютері', () => {
  it('дошка, схема — один рядок без демо-начинки і без обіцянки ПК', () => {
    renderChat([
      msg({ type: 'widget:kanban', kanbanData: { id: 'k', title: 'Спринт', columns: [{ id: 'c', title: 'Беклог', cards: [{ id: 'x', title: 'Вигадана картка' }] }] } as never }, 'm1'),
      msg({ type: 'embed:mermaid', text: '' }, 'm2'),
    ]);
    const rows = screen.getAllByTestId('card-pc-row');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toBe('Спринтне показується');
    for (const r of rows) expect(r.textContent).not.toContain('ПК');
    expect(screen.queryByText('Вигадана картка')).toBeNull();
  });

  it('документ, надісланий «У чат», на компʼютері лишається справжньою карткою з «Відкрити»', () => {
    renderChat([
      msg(
        {
          type: 'widget:canvas',
          canvasData: { id: 'cv', title: 'План суботи', blocks: [{ id: 'b', type: 'decision', content: 'Їдемо о 9' }] } as never,
        },
        'm3',
      ),
    ]);
    expect(screen.queryByTestId('card-pc-row')).toBeNull();
    expect(screen.getByText('План суботи')).toBeDefined();
    expect(screen.getByText('1 рішення')).toBeDefined();
    expect(screen.getByRole('button', { name: /Відкрити/ })).toBeDefined();
  });

  it('«У документ» у закритий документ не губиться: документ відкривається з фрагментом', async () => {
    renderChat([msg({ text: 'Зустріч о 19:40' }, 'm4')]);
    await act(async () => {
      window.dispatchEvent(new CustomEvent('phantom:add-to-canvas', { detail: { text: 'Зустріч о 19:40', type: 'text' } }));
    });
    expect(await screen.findByDisplayValue('Зустріч о 19:40')).toBeDefined();
  });
});
