/**
 * Дошка й план-графік живі, як «Список справ»: учасник відмічає пункт, правка
 * їде новою версією того самого ключа, і стрічка обох складає її з першою.
 * Склад (стовпчики, пункти, кроки) — лише від автора; стан пункту — з
 * найсвіжішого дотику будь-кого.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { foldCards } from '../components/messenger/cards/cardModel';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, KanbanData, Message, TimelineData } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const CHAT = {
  id: 'chat_o', title: 'Оксана', avatar: '', type: 'group', circle: 'all', pinned: false, unreadCount: 0, messages: [],
} as unknown as Chat;

const m = (over: Partial<Message>, id: string): Message =>
  ({ id, senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '12:40', type: 'text', isSelf: false, ...over }) as unknown as Message;

const realSendCard = useMessengerStore.getState().sendCard;
let seq = 0;
beforeEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ sendCard: realSendCard } as never);
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async () => ({ id: `srv_${++seq}`, delivery_state: 'sent' }) as never);
});

const seed = (messages: Message[]) =>
  useMessengerStore.setState({
    chats: [{ ...CHAT, messages }],
    activeChatId: CHAT.id,
    currentUser: { id: 'me', name: 'Кирило', avatar: '' },
  } as never);

const feed = () => useMessengerStore.getState().chats[0].messages;

/** Стрічка як у застосунку: ChatArea з тим, що зараз у сховищі. */
function Live() {
  const messages = useMessengerStore((s) => s.chats[0]?.messages ?? []);
  const noop = () => {};
  return (
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
    </MemoryRouter>
  );
}

const board = (todo: Array<{ id: string; at?: number }>, done: Array<{ id: string; at?: number }>, extra: string[] = []): KanbanData => ({
  id: 'kb1',
  title: 'Ремонт',
  columns: [
    { id: 'todo', title: 'Треба', items: [...todo.map((i) => ({ title: `П-${i.id}`, ...i })), ...extra.map((id) => ({ id, title: id }))] },
    { id: 'done', title: 'Готово', items: done.map((i) => ({ title: `П-${i.id}`, ...i })) },
  ],
});

const plan = (states: Array<['pending' | 'completed', number?]>): TimelineData => ({
  id: 'tl1',
  title: 'Переїзд',
  milestones: states.map(([status, at], i) => ({
    id: `s${i}`,
    title: `Крок ${i + 1}`,
    status,
    progress: status === 'completed' ? 100 : 0,
    ...(at ? { at } : {}),
  })),
});

const colIds = (d: KanbanData | undefined) => d!.columns.map((c) => c.items.map((i) => i.id));

describe('дошка й план-графік: дві людини, одна картка', () => {
  it('дошка: двоє одночасно переносять різні пункти в «Готово» — лишаються обидва; чужий пункт не додається', () => {
    const all = [
      m({ type: 'widget:kanban', kanbanData: board([{ id: 'a' }, { id: 'b' }], []) }, 'v1'),
      m({ type: 'widget:kanban', senderId: 'me', isSelf: true, kanbanData: board([{ id: 'b' }], [{ id: 'a', at: 10 }]) }, 'v2'),
      m({ type: 'widget:kanban', senderId: 'marta', kanbanData: board([{ id: 'a' }], [{ id: 'b', at: 11 }], ['x']) }, 'v3'),
    ];
    const fold = foldCards(all);
    expect([...fold.later]).toEqual(['v2', 'v3']);
    expect(colIds(fold.shown.get('v1')!.kanbanData)).toEqual([[], ['a', 'b']]);
  });

  it('план-графік: двоє відмічають різні кроки — обидва «готово»; пізніший дотик того самого кроку перемагає', () => {
    const all = [
      m({ type: 'widget:timeline', timelineData: plan([['pending'], ['pending'], ['completed']]) }, 'v1'),
      m({ type: 'widget:timeline', senderId: 'me', isSelf: true, timelineData: plan([['completed', 10], ['pending'], ['pending', 12]]) }, 'v2'),
      m({ type: 'widget:timeline', senderId: 'marta', timelineData: plan([['pending'], ['completed', 11], ['completed']]) }, 'v3'),
    ];
    const ms = foldCards(all).shown.get('v1')!.timelineData!.milestones;
    expect(ms.map((s) => [s.status, s.progress])).toEqual([
      ['completed', 100],
      ['completed', 100],
      ['pending', 0],
    ]);
  });

  it('учасник торкається пункту дошки — правка їде, стрічка показує одну картку з пунктом у «Готово»', async () => {
    seed([m({ type: 'widget:kanban', kanbanData: board([{ id: 'a' }, { id: 'b' }], []) }, 'v1')]);
    const view = render(<Live />);
    const card = within(view.container).getByTestId('card-board');
    const row = within(card).getAllByTestId('work-row')[0];
    expect(row.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(row);
    await vi.waitFor(() => expect(feed()).toHaveLength(2));
    const sent = feed()[1];
    expect(sent.type).toBe('widget:kanban');
    expect(colIds(sent.kanbanData)).toEqual([['b'], ['a']]);
    expect(view.container.querySelectorAll('[data-testid="card-board"]')).toHaveLength(1);
    const after = within(view.container).getByTestId('card-board');
    expect(within(after).getByTestId('card-status').textContent).toBe('готово 1 з 2');
  });

  it('рядок не тікає з-під пальця: після дотику порядок пунктів той самий, змінюється лише знак', async () => {
    seed([m({ type: 'widget:kanban', kanbanData: board([{ id: 'a' }, { id: 'b' }, { id: 'c' }], []) }, 'v1')]);
    const view = render(<Live />);
    const titles = () => within(within(view.container).getByTestId('card-board')).getAllByTestId('work-row').map((r) => r.textContent);
    const before = titles();
    fireEvent.click(within(within(view.container).getByTestId('card-board')).getAllByTestId('work-row')[0]);
    await vi.waitFor(() => expect(feed()).toHaveLength(2));
    const rows = within(within(view.container).getByTestId('card-board')).getAllByTestId('work-row');
    expect(rows.map((r) => r.textContent?.replace(/Готово|Треба/, ''))).toEqual(before.map((t) => t?.replace(/Готово|Треба/, '')));
    expect(rows.map((r) => r.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'false']);
  });

  it('відмічений пункт дошки виглядає точнісінько як у «Списку справ»: той самий рядок і той самий знак', () => {
    seed([
      m({ type: 'widget:kanban', kanbanData: board([{ id: 'b' }], [{ id: 'a' }]) }, 'v1'),
      m({ type: 'widget:family-checklist', familyChecklistData: { id: 'cl', title: 'Купити', items: [{ id: 'x', text: 'Хліб', done: true }] } } as never, 'v2'),
    ]);
    const view = render(<Live />);
    const doneBoard = within(within(view.container).getByTestId('card-board')).getAllByTestId('work-row').find((r) => r.getAttribute('aria-pressed') === 'true')!;
    const doneList = view.container.querySelector('[data-testid="card-checklist"] button[aria-pressed="true"]') as HTMLElement;
    expect(doneList).toBeTruthy();
    expect(doneBoard.className).toBe(doneList.className);
    expect(doneBoard.firstElementChild!.outerHTML).toBe(doneList.firstElementChild!.outerHTML);
  });

  it('учасник знімає «готово» з кроку плану — крок знову відкритий', async () => {
    seed([m({ type: 'widget:timeline', timelineData: plan([['completed'], ['pending']]) }, 'v1')]);
    const view = render(<Live />);
    const row = within(within(view.container).getByTestId('card-plan')).getAllByTestId('work-row')[0];
    expect(row.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(row);
    await vi.waitFor(() => expect(feed()).toHaveLength(2));
    const s0 = feed()[1].timelineData!.milestones[0];
    expect([s0.status, s0.progress]).toEqual(['pending', 0]);
    expect(within(view.container).getByTestId('card-plan').querySelector('[data-testid="card-status"]')?.textContent).toBe('готово 0 з 2');
  });

  it('дошка з одним стовпчиком і ролі лишаються знаком — відмічати нема куди', () => {
    seed([
      m({ type: 'widget:kanban', kanbanData: { id: 'k2', title: 'Ідеї', columns: [{ id: 'c', title: 'Усе', items: [{ id: 'i', title: 'Сад' }] }] } }, 'v1'),
      m({ type: 'widget:raci', raciData: { id: 'r', title: 'Хто', roles: [], rows: [{ id: '1', task: 'Квитки', r: 'Оксана', a: '', c: '', i: '' }] } } as never, 'v2'),
    ]);
    const view = render(<Live />);
    for (const r of within(view.container).getAllByTestId('work-row')) {
      expect(r.tagName).not.toBe('BUTTON');
      expect(r.hasAttribute('aria-pressed')).toBe(false);
    }
  });
});
