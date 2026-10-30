/**
 * Опитування, вікторина й список справ — як у Telegram (власник 19.10):
 * анонімне чи з іменами, кілька відповідей, вікторина з правильною,
 * закриття автором; у списку видно, хто поставив галочку.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { CardBuilder } from '../components/messenger/cards/CardBuilder';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { canVote, foldCards, pollOf, vote } from '../components/messenger/cards/cardModel';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, VotingData } from '../types/messenger';

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
  type: 'group',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  members: [{ id: 'oksana', name: 'Оксана', handle: '', avatar: '', role: 'member' }],
  messages: [],
} as unknown as Chat;

function seed(...ms: Message[]) {
  useMessengerStore.setState({
    chats: [{ ...CHAT, messages: ms }],
    activeChatId: 'chat_o',
    currentUser: { id: 'me', name: 'Кирило', avatar: '' },
  } as never);
}
const shownOf = (messages: Message[], id = 'm1') =>
  foldCards(messages).shown.get(id) ?? messages.find((m) => m.id === id)!;
const stored = (id = 'm1') => shownOf(useMessengerStore.getState().chats[0].messages, id);

function Live({ id = 'm1' }: { id?: string }) {
  const messages = useMessengerStore((s) => s.chats[0]?.messages ?? []);
  const m = messages.length ? shownOf(messages, id) : null;
  return m ? <MessageCard msg={m} meId="me" meName="Кирило" /> : null;
}

const voting = (over: Partial<VotingData> = {}, voters: [string[], string[]] = [[], []]): VotingData => ({
  id: 'v1',
  question: 'Столиця Чехії?',
  totalVotes: voters[0].length + voters[1].length,
  options: [
    { id: 'o1', text: 'Брно', votes: voters[0].length, voters: voters[0] },
    { id: 'o2', text: 'Прага', votes: voters[1].length, voters: voters[1] },
    { id: 'o3', text: 'Острава', votes: 0, voters: [] },
  ],
  ...over,
});

const realSendCard = useMessengerStore.getState().sendCard;
let seq = 0;
beforeEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ sendCard: realSendCard } as never);
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(
    async () => ({ id: `srv_${++seq}`, delivery_state: 'sent' }) as never,
  );
});

describe('модель: вікторина й закрите опитування', () => {
  it('у вікторині голос остаточний, закрите не приймає голосів', () => {
    const quiz = pollOf(msg({ type: 'widget:voting', votingData: voting({ correctOptionId: 'o2' }) }))!;
    expect(quiz.correct).toBe('o2');
    expect(quiz.multiple).toBe(false);
    const after = vote(quiz, 'o1', 'me');
    expect(canVote(after, 'me')).toBe(false);
    expect(vote(after, 'o2', 'me')).toBe(after);
    const shut = pollOf(msg({ type: 'widget:voting', votingData: voting({ isClosed: true }) }))!;
    expect(vote(shut, 'o1', 'me')).toBe(shut);
  });

  it('закриття автора липке: голос, що прийшов після, не входить', () => {
    const feed = [
      msg({ type: 'widget:voting', votingData: voting() }, 'm1'),
      msg({ type: 'widget:voting', votingData: voting({}, [['me'], []]) }, 'm2'),
      msg({ type: 'widget:voting', votingData: voting({ isClosed: true }, [['me'], []]) }, 'm3'),
      msg({ type: 'widget:voting', senderId: 'oksana', isSelf: false, votingData: voting({}, [[], ['oksana']]) }, 'm4'),
    ];
    const shown = foldCards(feed).shown.get('m1')!;
    expect(shown.votingData!.isClosed).toBe(true);
    expect(shown.votingData!.options.map((o) => o.voters)).toEqual([['me'], [], []]);
  });

  it('вікторина: пізніша версія людини не переносить її голос', () => {
    const feed = [
      msg({ type: 'widget:voting', votingData: voting({ correctOptionId: 'o2' }) }, 'm1'),
      msg({ type: 'widget:voting', senderId: 'oksana', isSelf: false, votingData: voting({ correctOptionId: 'o2' }, [['oksana'], []]) }, 'm2'),
      msg({ type: 'widget:voting', senderId: 'oksana', isSelf: false, votingData: voting({ correctOptionId: 'o2' }, [[], ['oksana']]) }, 'm3'),
    ];
    const shown = foldCards(feed).shown.get('m1')!;
    expect(shown.votingData!.options.map((o) => o.voters)).toEqual([['oksana'], [], []]);
  });

  it('чужа версія не міняє правил автора', () => {
    const feed = [
      msg({ type: 'widget:voting', votingData: voting({ anonymous: true, correctOptionId: 'o2' }) }, 'm1'),
      msg({ type: 'widget:voting', senderId: 'oksana', isSelf: false, votingData: voting({}, [['oksana'], []]) }, 'm2'),
    ];
    const shown = foldCards(feed).shown.get('m1')!;
    expect(shown.votingData!.anonymous).toBe(true);
    expect(shown.votingData!.correctOptionId).toBe('o2');
  });
});

describe('картка опитування', () => {
  it('до голосу результатів не видно; після — відсотки й імена', () => {
    seed(msg({ type: 'widget:voting', isSelf: false, senderId: 'oksana', votingData: voting({}, [['oksana'], []]) }));
    render(<Live />);
    expect(screen.queryByText('100%')).toBeNull();
    expect(screen.queryByTestId('poll-voters')).toBeNull();
    fireEvent.click(screen.getByText('Прага'));
    expect(screen.getAllByText('50%')).toHaveLength(2);
    const names = screen.getAllByTestId('poll-voters').map((n) => n.textContent);
    expect(names).toEqual(['Оксана', 'Кирило']);
  });

  it('анонімне — без імен', () => {
    seed(msg({ type: 'widget:voting', isSelf: false, senderId: 'oksana', votingData: voting({ anonymous: true }, [['oksana'], []]) }));
    render(<Live />);
    fireEvent.click(screen.getByText('Брно'));
    expect(screen.getByText('100%')).toBeTruthy();
    expect(screen.queryByTestId('poll-voters')).toBeNull();
    expect(screen.getByText('Анонімне опитування')).toBeTruthy();
  });

  it('вікторина: неправильна моя, правильна підсвічена, змінити не можна', () => {
    seed(msg({ type: 'widget:voting', isSelf: false, senderId: 'oksana', votingData: voting({ correctOptionId: 'o2' }) }));
    render(<Live />);
    expect(screen.getByText('Вікторина')).toBeTruthy();
    fireEvent.click(screen.getByText('Брно'));
    const row = (text: string) => screen.getByText(text).closest('button')!;
    expect(row('Брно').getAttribute('data-wrong')).toBe('true');
    expect(row('Прага').getAttribute('data-correct')).toBe('true');
    expect(row('Острава').getAttribute('data-correct')).toBeNull();
    expect((row('Прага') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByText('Прага'));
    expect(stored().votingData!.options[1].voters).toEqual([]);
  });

  it('автор закриває — опитування закрите в обох, голосувати не можна', () => {
    seed(msg({ type: 'widget:voting', votingData: voting({}, [['oksana'], []]) }));
    render(<Live />);
    fireEvent.click(screen.getByText('Закрити'));
    expect(stored().votingData!.isClosed).toBe(true);
    expect(screen.getByTestId('card-status').textContent).toMatch(/^Закрито/);
    expect(screen.queryByText('Закрити')).toBeNull();
    expect(screen.getByText('100%')).toBeTruthy();
    fireEvent.click(screen.getByText('Прага'));
    expect(stored().votingData!.options[1].voters).toEqual([]);
  });

  it('не автор не бачить «Закрити»', () => {
    seed(msg({ type: 'widget:voting', isSelf: false, senderId: 'oksana', votingData: voting() }));
    render(<Live />);
    expect(screen.queryByText('Закрити')).toBeNull();
  });
});

describe('будівельник опитування з «+»', () => {
  beforeEach(() => seed(msg({ text: 'привіт' })));

  it('анонімне за замовчуванням; кілька відповідей їде прапорцем', async () => {
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    render(<CardBuilder kind="poll" onClose={() => {}} />);
    fireEvent.change(screen.getByTestId('builder-title'), { target: { value: 'Коли?' } });
    fireEvent.change(screen.getByTestId('poll-option-0'), { target: { value: 'Пʼятниця' } });
    fireEvent.change(screen.getByTestId('poll-option-1'), { target: { value: 'Субота' } });
    expect(screen.getByTestId('poll-anonymous').getAttribute('aria-checked')).toBe('true');
    fireEvent.click(screen.getByTestId('poll-multiple'));
    await act(async () => {
      fireEvent.click(screen.getByText('Надіслати'));
    });
    const [, data] = sendCard.mock.calls[0];
    expect(data.anonymous).toBe(true);
    expect(data.allowMultiple).toBe(true);
    expect(data.correctOptionId).toBeUndefined();
  });

  it('вікторина без правильної не надсилається; з нею — їде correctOptionId', async () => {
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    render(<CardBuilder kind="poll" onClose={() => {}} />);
    fireEvent.change(screen.getByTestId('builder-title'), { target: { value: 'Столиця?' } });
    fireEvent.change(screen.getByTestId('poll-option-0'), { target: { value: 'Брно' } });
    fireEvent.change(screen.getByTestId('poll-option-1'), { target: { value: 'Прага' } });
    fireEvent.click(screen.getByTestId('poll-anonymous'));
    fireEvent.click(screen.getByTestId('poll-quiz'));
    expect((screen.getByText('Надіслати').closest('button') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByTestId('poll-correct-1'));
    await act(async () => {
      fireEvent.click(screen.getByText('Надіслати'));
    });
    const [kind, data] = sendCard.mock.calls[0];
    expect(kind).toBe('widget:voting');
    expect(data.correctOptionId).toBe('o2');
    expect(data.anonymous).toBeUndefined();
    expect(data.allowMultiple).toBeUndefined();
  });
});

describe('список справ: галочку ставить будь-хто, видно хто', () => {
  const list = (items: Array<{ id: string; done: boolean; doneBy?: string; at?: number }>) => ({
    id: 'fc1',
    title: 'В дорогу',
    items: items.map((i) => ({ text: i.id === 'a' ? 'Квитки' : 'Ключі', category: '', addedBy: 'Кирило', ...i })),
  });

  it('дотик пише, хто поставив, і це видно в картці', () => {
    seed(msg({ type: 'widget:family-checklist', isSelf: false, senderId: 'oksana', familyChecklistData: list([{ id: 'a', done: false }, { id: 'b', done: false }]) }));
    render(<Live />);
    fireEvent.click(screen.getByText('Квитки'));
    const item = stored().familyChecklistData!.items[0];
    expect(item.done).toBe(true);
    expect(item.doneBy).toBe('Кирило');
    expect(screen.getByTestId('checklist-done-by').textContent).toBe('Кирило');
  });

  it('дві галочки різних людей одночасно — обидві лишаються', () => {
    const feed = [
      msg({ type: 'widget:family-checklist', familyChecklistData: list([{ id: 'a', done: false }, { id: 'b', done: false }]) }, 'm1'),
      msg({ type: 'widget:family-checklist', familyChecklistData: list([{ id: 'a', done: true, doneBy: 'Кирило', at: 10 }, { id: 'b', done: false }]) }, 'm2'),
      msg(
        { type: 'widget:family-checklist', senderId: 'oksana', isSelf: false, familyChecklistData: list([{ id: 'a', done: false }, { id: 'b', done: true, doneBy: 'Оксана', at: 11 }]) },
        'm3',
      ),
    ];
    const items = foldCards(feed).shown.get('m1')!.familyChecklistData!.items;
    expect(items.map((i) => [i.done, i.doneBy])).toEqual([
      [true, 'Кирило'],
      [true, 'Оксана'],
    ]);
  });

  it('склад списку міняє лише автор: у чужому аркуші немає «Додати»', () => {
    seed(msg({ type: 'widget:family-checklist', isSelf: false, senderId: 'oksana', familyChecklistData: list([{ id: 'a', done: false }]) }));
    render(<Live />);
    fireEvent.click(screen.getByText('Відкрити'));
    const sheet = screen.getByTestId('sheet-checklist');
    expect(within(sheet).queryByTestId('checklist-add')).toBeNull();
  });

  it('автор додає пункт зі свого аркуша — і він їде новою версією', () => {
    seed(msg({ type: 'widget:family-checklist', familyChecklistData: list([{ id: 'a', done: false }]) }));
    render(<Live />);
    fireEvent.click(screen.getByText('Змінити'));
    const sheet = screen.getByTestId('sheet-checklist');
    const add = within(sheet).getByTestId('checklist-add');
    fireEvent.change(add, { target: { value: 'Зарядка' } });
    fireEvent.keyDown(add, { key: 'Enter' });
    expect(stored().familyChecklistData!.items.map((i) => i.text)).toEqual(['Квитки', 'Зарядка']);
  });
});

describe('досяжність з композера', () => {
  const props = {
    onSendMessage: () => {},
    onSendVoiceMessage: () => {},
    onOpenActions: () => {},
    onOpenScheduler: () => {},
    replyingTo: null,
    onCancelReply: () => {},
    editingMessage: null,
    onCancelEdit: () => {},
    onSaveEdit: () => {},
    selectedMessagesForQuote: [],
    onSynthesizeMultiQuote: () => {},
    onClearSelectedQuotes: () => {},
    chatId: 'chat_o',
  };
  const flush = () => act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });

  it('«+ → Ще → Опитування» відкриває будівельник із вікториною', async () => {
    seed(msg({ text: 'привіт' }));
    render(<MessageComposer {...props} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    fireEvent.click(screen.getByTestId('send-poll'));
    await flush();
    const sheet = screen.getByTestId('builder-poll');
    expect(within(sheet).getByTestId('poll-quiz')).toBeTruthy();
    expect(within(sheet).getByTestId('poll-anonymous')).toBeTruthy();
  });
});

describe('анонімне: імена не їдуть дротом', () => {
  const anon = (over: Partial<VotingData> = {}): VotingData => {
    const { options, ...rest } = voting({ anonymous: true, ...over });
    return { ...rest, options: options.map(({ voters: _v, ...o }) => o) };
  };
  const ver = (sender: string, pick: string[], id: string, over: Partial<VotingData> = {}) =>
    msg({ type: 'widget:voting', isSelf: sender === 'me', senderId: sender, votingData: { ...anon(over), pick } }, id);

  it('мій голос — лише лічильники й мій вибір, жодного списку людей', async () => {
    const sent: unknown[] = [];
    useMessengerStore.setState({ sendCard: vi.fn(async (_k: string, d: unknown) => { sent.push(d); return null; }) } as never);
    seed(ver('oksana', ['o1'], 'm1'));
    render(<Live />);
    fireEvent.click(screen.getByText('Прага'));
    const wire = JSON.stringify(sent[0]);
    expect(wire).not.toContain('voters');
    expect(wire).not.toContain('oksana');
    expect(sent[0]).toMatchObject({ pick: ['o2'], totalVotes: 2, options: [{ id: 'o1', votes: 1 }, { id: 'o2', votes: 1 }, { id: 'o3', votes: 0 }] });
  });

  it('злиття: кожен рахується за своїм останнім вибором, одночасні не губляться', () => {
    const shown = foldCards([
      ver('oksana', [], 'm1'),
      ver('petro', ['o1'], 'm2'),
      ver('ana', ['o2'], 'm3'),
      ver('petro', ['o2'], 'm4'),
    ]).shown.get('m1')!;
    expect(shown.votingData!.options.map((o) => o.votes)).toEqual([0, 2, 0]);
    expect(shown.votingData!.totalVotes).toBe(2);
  });

  it('мій вибір видно мені після злиття, а змінити голос можна', () => {
    seed(ver('oksana', [], 'm1'), ver('me', ['o1'], 'm2'), ver('petro', ['o1'], 'm3'));
    render(<Live />);
    expect(screen.getByText('100%')).toBeTruthy();
    expect(pollOf(stored())!.options[0].voters).toContain('me');
  });

  it('будівельник: анонімне їде без списків людей', async () => {
    seed(msg({ text: 'привіт' }));
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    render(<CardBuilder kind="poll" onClose={() => {}} />);
    fireEvent.change(screen.getByTestId('builder-title'), { target: { value: 'Коли?' } });
    fireEvent.change(screen.getByTestId('poll-option-0'), { target: { value: 'Пʼятниця' } });
    fireEvent.change(screen.getByTestId('poll-option-1'), { target: { value: 'Субота' } });
    await act(async () => {
      fireEvent.click(screen.getByText('Надіслати'));
    });
    expect(JSON.stringify(sendCard.mock.calls[0][1])).not.toContain('voters');
  });
});
