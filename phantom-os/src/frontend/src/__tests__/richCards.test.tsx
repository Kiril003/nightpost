/**
 * Картки в розмові: одна мова, живий вміст, правка дотиком, створення з «+».
 *
 * Власник 26.09 про таблицю на A52: «не пропрацьовано ніяк, ні відображення,
 * ні створення». Там була форма у формі — стара зеленкувата поверхня на
 * бурштиновій заливці бульбашки, пояснення замість стану, розріджений капс і
 * жодного рядка без гортання.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { MessageCard, TheatreCard } from '../components/messenger/cards/MessageCard';
import { CardBuilder, sendableBuilders } from '../components/messenger/cards/CardBuilder';
import {
  checklistStatus,
  expensesStatus,
  pollOf,
  foldCards,
  pollStatus,
  tableStatus,
  vote,
} from '../components/messenger/cards/cardModel';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, TableData } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const TABLE: TableData = {
  id: 'tb1',
  title: 'План завдань та витрат',
  description: 'Інтерактивна таблиця з можливістю редагування клітинок',
  columns: [
    { key: 'item', label: 'Стаття витрат', type: 'text' },
    { key: 'category', label: 'Категорія', type: 'badge' },
    { key: 'amount', label: 'Сума (₴)', type: 'number' },
  ],
  rows: [
    { id: 'r1', item: 'Квитки', category: 'Дорога', amount: 3400 },
    { id: 'r2', item: 'Житло', category: 'Житло', amount: 5600 },
    { id: 'r3', item: 'Продукти', category: 'Їжа', amount: 2100 },
    { id: 'r4', item: 'Музей', category: 'Дозвілля', amount: 1300 },
  ],
};

const msg = (over: Partial<Message>, id = 'm1'): Message =>
  ({
    id,
    senderId: 'me',
    senderName: 'Кирило',
    senderAvatar: '',
    timestamp: '14:32',
    type: 'text',
    isSelf: true,
    ...over,
  }) as unknown as Message;

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

/** Лист живе в сторі — картка пише зміну туди ж, звідки її читає стрічка. */
function seed(m: Message) {
  useMessengerStore.setState({
    chats: [{ ...CHAT, messages: [m] }],
    activeChatId: 'chat_o',
    currentUser: { id: 'me', name: 'Кирило', avatar: '' },
  } as never);
}
/** Картка такою, як її малює стрічка: перша версія з найсвіжішим вмістом. */
const shownOf = (messages: Message[], id = 'm1') =>
  foldCards(messages).shown.get(id) ?? messages.find((m) => m.id === id)!;
const stored = (id = 'm1') => shownOf(useMessengerStore.getState().chats[0].messages, id);

function Live({ id = 'm1' }: { id?: string }) {
  const messages = useMessengerStore((s) => s.chats[0]?.messages ?? []);
  const m = messages.length ? shownOf(messages, id) : null;
  return m ? <MessageCard msg={m} meId="me" meName="Кирило" /> : null;
}

let seq = 0;
beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(
    async () => ({ id: `srv_${++seq}`, delivery_state: 'sent' }) as never,
  );
});

describe('рядок стану — числа, а не пояснення', () => {
  it('таблиця: рядки й сума стовпця чисел з одиницею з назви', () => {
    expect(tableStatus(TABLE)).toBe('4 рядки · сума 12 400 ₴');
    expect(tableStatus({ ...TABLE, rows: [] })).toBe('Поки порожньо');
  });
  it('список: «3 з 5 зроблено», «Усе зроблено»', () => {
    const items = [true, true, true, false, false].map((done, i) => ({ id: `i${i}`, text: 'x', done }));
    expect(checklistStatus({ title: '', items })).toBe('3 з 5 зроблено');
    expect(checklistStatus({ title: '', items: items.map((i) => ({ ...i, done: true })) })).toBe('Усе зроблено');
  });
  it('опитування рахує людей, а не позначки; одиницю — голосом, без роду', () => {
    const poll = pollOf(
      msg({
        type: 'widget:voting',
        votingData: {
          id: 'v',
          question: 'Коли?',
          allowMultiple: true,
          totalVotes: 3,
          options: [
            { id: 'a', text: 'А', votes: 2, voters: ['x', 'y'] },
            { id: 'b', text: 'Б', votes: 1, voters: ['x'] },
          ],
        },
      }),
    )!;
    expect(pollStatus(poll)).toBe('голосували 2');
    expect(pollStatus({ ...poll, options: [{ id: 'a', text: 'А', voters: ['x'] }] })).toBe('1 голос');
  });
  it('витрати: кількість і сума', () => {
    expect(
      expensesStatus({
        title: '',
        currency: '₴',
        items: [
          { id: '1', title: 'a', amount: 3400, paidBy: '' },
          { id: '2', title: 'b', amount: 9000, paidBy: '' },
        ],
      }),
    ).toBe('2 витрати · 12 400 ₴');
  });
  it('голос в одиночному опитуванні переноситься, повторний дотик знімає', () => {
    const p = { question: '', multiple: false, options: [{ id: 'a', text: '', voters: ['me'] }, { id: 'b', text: '', voters: [] }] };
    const moved = vote(p, 'b', 'me');
    expect(moved.options.map((o) => o.voters)).toEqual([[], ['me']]);
    expect(vote(moved, 'b', 'me').options.map((o) => o.voters)).toEqual([[], []]);
  });
});

describe('картка таблиці', () => {
  beforeEach(() => seed(msg({ type: 'table', tableData: TABLE, text: 'Створено структуровану таблицю: «План»' })));

  it('рядки видно одразу (три, решта — «і ще»), без капсу, пошуку й кнопок CSV', () => {
    render(<Live />);
    const card = screen.getByTestId('card-table');
    expect(within(card).getByTestId('card-title').textContent).toBe('План завдань та витрат');
    expect(within(card).getByTestId('card-status').textContent).toBe('4 рядки · сума 12 400 ₴');
    for (const r of ['Квитки', 'Житло', 'Продукти']) expect(within(card).getByText(r)).toBeTruthy();
    expect(within(card).queryByText('Музей')).toBeNull();
    expect(within(card).getByText('і ще 1')).toBeTruthy();
    expect(within(card).getByText('3 400')).toBeTruthy();
    expect(card.className).toContain('bg-surface');
    expect(card.innerHTML).not.toMatch(/uppercase|tracking-/);
    expect(card.textContent).not.toMatch(/CSV|Пошук|Інтерактивна/);
    expect(card.querySelector('input')).toBeNull();
  });

  it('«Відкрити» → аркуш: клітинка правиться дотиком, рядок додається одним дотиком', async () => {
    render(<Live />);
    fireEvent.click(screen.getByText('Відкрити'));
    const sheet = screen.getByTestId('sheet-table');
    expect(within(sheet).getByText('Сума, ₴')).toBeTruthy();

    fireEvent.click(within(sheet).getByTestId('cell-1-amount'));
    const input = within(sheet).getByTestId('cell-1-amount-input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '6000' } });
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(stored().tableData!.rows[1].amount).toBe(6000);
    // Правка поїхала людині новою версією картки того самого ключа.
    expect(useMessengerStore.getState().chats[0].messages).toHaveLength(2);
    expect(vi.mocked(messengerApi.appendMessage).mock.calls[0][1]).toMatchObject({ kind: 'table' });
    expect(within(sheet).getByText('4 рядки · сума 12 800 ₴')).toBeTruthy();

    await act(async () => {
      fireEvent.click(within(sheet).getByTestId('table-add-row'));
    });
    expect(stored().tableData!.rows).toHaveLength(5);
    // Новий рядок одразу відкриває першу клітинку — друкувати, а не шукати.
    expect(within(sheet).getByTestId('cell-4-item-input')).toBeTruthy();
  });
});

describe('список справ і опитування — дія прямо в стрічці', () => {
  it('галочка в картці пише в поле, з якого список приїхав', () => {
    seed(
      msg({
        type: 'widget:family-checklist',
        familyChecklistData: {
          id: 'fc',
          title: 'Що взяти',
          items: [
            { id: 'a', text: 'Павербанк', category: '', done: false, addedBy: 'Оксана' },
            { id: 'b', text: 'Термос', category: '', done: true, addedBy: 'Оксана' },
          ],
        },
      }),
    );
    render(<Live />);
    expect(screen.getByTestId('card-status').textContent).toBe('1 з 2 зроблено');
    fireEvent.click(screen.getByText('Павербанк'));
    expect(stored().familyChecklistData!.items[0]).toMatchObject({ done: true, addedBy: 'Оксана' });
    expect(screen.getByTestId('card-status').textContent).toBe('Усе зроблено');
  });

  it('старий task-list читається тією ж карткою', () => {
    seed(msg({ type: 'task-list', taskListData: { title: 'Справи', tasks: [{ id: 't', title: 'Заправити', completed: false }] } }));
    render(<Live />);
    fireEvent.click(screen.getByText('Заправити'));
    expect(stored().taskListData!.tasks[0].completed).toBe(true);
  });

  it('варіанти опитування видно одразу, дотик голосує', () => {
    seed(
      msg({
        type: 'widget:voting',
        votingData: {
          id: 'v',
          question: 'Коли виїжджаємо?',
          totalVotes: 1,
          options: [
            { id: 'o1', text: 'Пʼятниця', votes: 1, voters: ['x'] },
            { id: 'o2', text: 'Субота', votes: 0, voters: [] },
          ],
        },
      }),
    );
    render(<Live />);
    expect(screen.getByTestId('card-status').textContent).toBe('1 голос');
    fireEvent.click(screen.getByText('Субота'));
    expect(stored().votingData!.options[1].voters).toEqual(['me']);
    expect(stored().votingData!.totalVotes).toBe(2);
    expect(screen.getByTestId('card-status').textContent).toBe('голосували 2');
  });
});

describe('у стрічці картка — власна поверхня, не форма в бурштиновій бульбашці', () => {
  it('своя таблиця: бульбашка без заливки, пояснення старого листа не показується', () => {
    const m = msg({ type: 'table', tableData: TABLE, text: 'Створено структуровану таблицю: «План завдань та витрат»' });
    seed(m);
    const noop = () => {};
    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={CHAT}
          messages={[m]}
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
    const card = screen.getByTestId('card-table');
    const bubble = card.closest('.msg-bubble-fluid')!;
    expect(bubble.className).not.toContain('msg-bubble-self');
    expect(bubble.className).toContain('bg-transparent!');
    expect(container.textContent).not.toContain('Створено структуровану таблицю');
  });
});

describe('показові віджети', () => {
  it('назва й чесний хвіст в одному рядку, без демо-начинки і без обіцянки дверей', () => {
    render(
      <TheatreCard
        msg={msg({ type: 'widget:kanban', kanbanData: { title: 'Спринт', columns: [] } as never })}
      />,
    );
    const row = screen.getByTestId('card-pc-row');
    expect(row.textContent).toBe('Спринтне показується');
    expect(row.getAttribute('title')).toBe('Дошка завдань');
    expect(screen.queryByTestId('card-title')).toBeNull();
  });
});

describe('створення: «+» → будівельник на одному екрані', () => {
  beforeEach(() => seed(msg({ text: 'привіт' })));

  it('таблиця пропонується, бо дріт везе рід table', () => {
    expect(sendableBuilders()).toEqual(['checklist', 'poll', 'expense', 'table']);
  });

  it('таблиця: стовпці й рядки заповнюються до надсилання і їдуть родом table', async () => {
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    render(<CardBuilder kind="table" onClose={() => {}} />);
    fireEvent.change(screen.getByTestId('builder-title'), { target: { value: 'Поїздка' } });
    fireEvent.click(screen.getByTestId('table-add-row'));
    const cell = screen.getByTestId('cell-0-c1-input');
    fireEvent.change(cell, { target: { value: 'Квитки' } });
    fireEvent.keyDown(cell, { key: 'Enter' });
    await act(async () => {
      fireEvent.click(screen.getByText('Надіслати'));
    });
    const [kind, data] = sendCard.mock.calls[0];
    expect(kind).toBe('table');
    expect(data.title).toBe('Поїздка');
    expect(data.rows[0].c1).toBe('Квитки');
    expect(data.id).toMatch(/^tb_/);
  });

  it('список справ їде живим родом widget:family-checklist', async () => {
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    const onClose = vi.fn();
    render(<CardBuilder kind="checklist" onClose={onClose} />);
    const send = screen.getByText('Надіслати').closest('button')!;
    expect(send.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('builder-title'), { target: { value: 'У дорогу' } });
    const add = screen.getByTestId('checklist-add');
    fireEvent.change(add, { target: { value: 'Павербанк' } });
    fireEvent.keyDown(add, { key: 'Enter' });
    fireEvent.change(add, { target: { value: 'Термос' } });
    fireEvent.keyDown(add, { key: 'Enter' });
    await act(async () => {
      fireEvent.click(send);
    });
    expect(sendCard).toHaveBeenCalledTimes(1);
    const [kind, data] = sendCard.mock.calls[0];
    expect(kind).toBe('widget:family-checklist');
    expect(data.title).toBe('У дорогу');
    expect(data.items.map((i: { text: string }) => i.text)).toEqual(['Павербанк', 'Термос']);
    expect(onClose).toHaveBeenCalled();
  });

  it('опитування: питання й два варіанти — і наступне поле з\'являється саме', async () => {
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    render(<CardBuilder kind="poll" onClose={() => {}} />);
    fireEvent.change(screen.getByTestId('builder-title'), { target: { value: 'Коли?' } });
    fireEvent.change(screen.getByTestId('poll-option-0'), { target: { value: 'Пʼятниця' } });
    fireEvent.change(screen.getByTestId('poll-option-1'), { target: { value: 'Субота' } });
    expect(screen.getByTestId('poll-option-2')).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByText('Надіслати'));
    });
    const [kind, data] = sendCard.mock.calls[0];
    expect(kind).toBe('widget:voting');
    expect(data.options.map((o: { text: string }) => o.text)).toEqual(['Пʼятниця', 'Субота']);
  });

  it('відмова дроту лишає аркуш відкритим із набраним', async () => {
    useMessengerStore.setState({ sendCard: vi.fn().mockResolvedValue('не поїхало') } as never);
    const onClose = vi.fn();
    render(<CardBuilder kind="expense" onClose={onClose} />);
    fireEvent.change(screen.getByTestId('expense-title'), { target: { value: 'Квитки' } });
    fireEvent.change(screen.getByTestId('expense-amount'), { target: { value: '3400' } });
    fireEvent.click(screen.getByLabelText('Додати витрату'));
    await act(async () => {
      fireEvent.click(screen.getByText('Надіслати'));
    });
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByText('Квитки')).toBeTruthy();
  });
});

describe('правка картки доходить до обох — новою версією того самого ключа', () => {
  const poll = (voters: [string[], string[]]) => ({
    id: 'v1',
    question: 'Коли?',
    totalVotes: voters[0].length + voters[1].length,
    options: [
      { id: 'a', text: 'Пʼятниця', votes: voters[0].length, voters: voters[0] },
      { id: 'b', text: 'Субота', votes: voters[1].length, voters: voters[1] },
    ],
  });

  it('два одночасні голоси не затирають один одного', () => {
    // Я проголосував за пʼятницю, Оксана — за суботу, кожен зі своєї старої копії.
    const feed = [
      msg({ type: 'widget:voting', votingData: poll([[], []]) }, 'm1'),
      msg({ type: 'widget:voting', votingData: poll([['me'], []]) }, 'm2'),
      msg({ type: 'widget:voting', senderId: 'oksana', isSelf: false, votingData: poll([[], ['oksana']]) }, 'm3'),
    ];
    const fold = foldCards(feed);
    expect([...fold.later]).toEqual(['m2', 'm3']);
    const shown = fold.shown.get('m1')!;
    expect(shown.votingData!.options.map((o) => o.voters)).toEqual([['me'], ['oksana']]);
    expect(shown.votingData!.totalVotes).toBe(2);
  });

  it('галочка людини з того боку оновлює картку на її першому місці', () => {
    const list = (done: boolean) => ({
      id: 'fc1',
      title: 'Що взяти',
      items: [{ id: 'a', text: 'Термос', category: '', done, addedBy: 'Кирило' }],
    });
    const feed = [
      msg({ type: 'widget:family-checklist', familyChecklistData: list(false), timestamp: '10:00' }, 'm1'),
      msg({ type: 'text', text: 'ок', senderId: 'oksana', isSelf: false }, 'm2'),
      msg({ type: 'widget:family-checklist', familyChecklistData: list(true), senderId: 'oksana', isSelf: false, timestamp: '10:05' }, 'm3'),
    ];
    const fold = foldCards(feed);
    const shown = fold.shown.get('m1')!;
    expect(shown.familyChecklistData!.items[0].done).toBe(true);
    expect(shown.timestamp).toBe('10:00');
    expect(shown.isSelf).toBe(true);
    expect(fold.later.has('m3')).toBe(true);
  });

  it('невдала правка не видається за стан, який є в людини', () => {
    const exp = (amount: number) => ({
      id: 'fe1',
      title: 'Витрати',
      currency: '₴',
      items: [{ id: 'x', title: 'Квитки', amountUAH: amount, category: '', paidBy: 'Кирило' }],
    });
    const feed = [
      msg({ type: 'widget:family-expense', familyExpenseData: exp(100) }, 'm1'),
      msg({ type: 'widget:family-expense', familyExpenseData: exp(900), status: 'failed' }, 'm2'),
    ];
    const fold = foldCards(feed);
    expect(fold.later.has('m2')).toBe(true);
    expect(fold.shown.get('m1')!.familyExpenseData!.items[0].amountUAH).toBe(100);
  });

  it('у стрічці видно ОДНУ картку, а не по бульбашці на кожну галочку', () => {
    const list = (done: boolean) => ({ id: 'fc1', title: 'Що взяти', items: [{ id: 'a', text: 'Термос', category: '', done, addedBy: '' }] });
    const feed = [
      msg({ type: 'widget:family-checklist', familyChecklistData: list(false) }, 'm1'),
      msg({ type: 'widget:family-checklist', familyChecklistData: list(true), senderId: 'oksana', isSelf: false }, 'm2'),
    ];
    seed(feed[0]);
    const noop = () => {};
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={CHAT}
          messages={feed}
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
    expect(screen.getAllByTestId('card-checklist')).toHaveLength(1);
    expect(screen.getByTestId('card-status').textContent).toBe('Усе зроблено');
  });
});

describe('тіло з дроту — від іншої людини, і воно може бути будь-яким', () => {
  it('таблиця без стовпців не валить стрічку, а каже, що не прочиталась', () => {
    render(<MessageCard msg={msg({ type: 'table', tableData: { __msg: {} } as never })} meId="me" meName="Кирило" />);
    expect(screen.getByTestId('card-unreadable')).toBeTruthy();
    expect(screen.getByTestId('card-title').textContent).toBe('Таблиця');
  });

  it('перелік, що не масив, — порожній список, а не падіння', () => {
    render(
      <MessageCard
        msg={msg({ type: 'widget:family-checklist', familyChecklistData: { id: 'x', title: 'Т', items: 'ой' } as never })}
        meId="me"
        meName="Кирило"
      />,
    );
    expect(screen.getByTestId('card-status').textContent).toBe('Поки порожньо');
  });
});
