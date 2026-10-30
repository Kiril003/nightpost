/**
 * MSG-SYS (29.10, _briefs/msg-sys/AUDIT.md): роди без власного виду малювали
 * порожню бульбашку з самим часом — `event`, `canvas-doc`, `transit-arrivals`,
 * `widget:transit-arrivals`. Тепер — чесна картка «не вдалося показати», а подія
 * з даними показує свої дані.
 */
import { beforeAll, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';
import { pcRowTail, TheatreCard } from '../components/messenger/cards/MessageCard';
import { kindLine } from '../components/messenger/chatListFormat';
import { speakText } from '../components/messenger/feed/useMessageAssist';
import { indexShared } from '../services/peerShared';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const CHAT = {
  id: 'chat_o', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: 0, messages: [],
} as unknown as Chat;

export const kindMsg = (over: Partial<Message>, id: string): Message =>
  ({ id, senderId: 'p1', senderName: 'Оксана', senderAvatar: '', timestamp: '12:40', type: 'text', isSelf: false, ...over }) as unknown as Message;

export const renderChat = (messages: Message[]) => {
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

const bubble = (id: string) => document.querySelector(`[data-msg-id="${id}"]`) as HTMLElement;

describe('роди без виду: чесна картка замість порожньої бульбашки', () => {
  for (const [kind, id] of [
    ['event', 'e1'],
    ['canvas-doc', 'e2'],
    ['transit-arrivals', 'e3'],
    ['widget:transit-arrivals', 'e4'],
  ] as const) {
    it(`${kind} без даних — «Повідомлення не вдалося показати»`, () => {
      renderChat([kindMsg({ type: kind as Message['type'], text: '' }, id)]);
      const b = bubble(id);
      expect(b).toBeTruthy();
      const card = within(b).getByTestId('peer-broken');
      expect(card.textContent).toContain('Повідомлення не вдалося показати');
      expect(card.textContent).toContain('Попросіть надіслати ще раз.');
    });
  }

  it('подія з даними показує назву, день, час і місце — не «не вдалося»', () => {
    renderChat([
      kindMsg(
        { type: 'event', text: '', eventData: { title: 'Сніданок', date: '2026-11-01', time: '10:00', location: 'Сад', attendees: [] } },
        'e5',
      ),
    ]);
    const b = bubble('e5');
    expect(within(b).queryByTestId('peer-broken')).toBeNull();
    const card = within(b).getByTestId('card-event');
    expect(within(card).getByTestId('card-title').textContent).toBe('Сніданок');
    const status = within(card).getByTestId('card-status').textContent ?? '';
    expect(status).toContain('10:00');
    expect(status).toContain('Сад');
  });

  it('рід без виду, але з текстом — лишається текстом', () => {
    renderChat([kindMsg({ type: 'canvas-doc', text: 'План на суботу' }, 'e6')]);
    expect(within(bubble('e6')).queryByTestId('peer-broken')).toBeNull();
    expect(screen.getByText('План на суботу')).toBeDefined();
  });
});

describe('11 показових родів — один короткий рядок, не ціла картка', () => {
  const PC_ONLY: Array<[string, string]> = [
    ['widget:canvas', 'Документ'],
    ['widget:kanban', 'Дошка завдань'],
    ['widget:raci', 'Ролі в завданнях'],
    ['widget:timeline', 'План-графік'],
    ['widget:code-runner', 'Код'],
    ['widget:study-flashcards', 'Навчальні картки'],
    ['snippet:async', 'Відеоогляд'],
    ['embed:mermaid', 'Схема'],
    ['embed:diff', 'Зміни в коді'],
    ['embed:svg', 'Макет'],
    ['webhook:event', 'Подія ззовні'],
  ];

  it('кожен рід: значок + слово + «не показується», без шапки картки', () => {
    renderChat(PC_ONLY.map(([kind], i) => kindMsg({ type: kind as Message['type'], text: '' }, `p${i}`)));
    PC_ONLY.forEach(([kind, word], i) => {
      const row = within(bubble(`p${i}`)).getByTestId('card-pc-row');
      expect(row.getAttribute('data-kind')).toBe(kind);
      expect(row.querySelector('svg')).not.toBeNull();
      expect(row.textContent).toBe(`${word}не показується`);
      expect(row.querySelector('[data-card-kind]')).toBeNull();
      expect(within(bubble(`p${i}`)).queryByTestId('card-title')).toBeNull();
    });
  });

  it('названий віджет показує свою назву замість слова роду', () => {
    renderChat([kindMsg({ type: 'widget:kanban', kanbanData: { title: 'Спринт', columns: [] } } as never, 'p20')]);
    const row = within(bubble('p20')).getByTestId('card-pc-row');
    expect(row.textContent).toBe('Спринтне показується');
  });

  it('подія ззовні — завжди слово роду: назву ставить сервіс, не людина', () => {
    renderChat([kindMsg({ type: 'webhook:event', webhookEventData: { source: 'github', eventType: 'push', title: 'Push', timestamp: '2026-10-29' } } as never, 'p21')]);
    expect(within(bubble('p21')).getByTestId('card-pc-row').textContent).toBe('Подія ззовніне показується');
  });

  it('документ: назва до двох рядків, щоб «відкрити на ПК» не зʼїдало її', () => {
    render(<TheatreCard msg={kindMsg({ type: 'widget:canvas', canvasData: { id: 'c', title: 'Нотатки до поїздки в Карпати', blocks: [] } } as never, 'p22')} />);
    const word = screen.getByTestId('card-pc-row').querySelector('[data-row-word]')!;
    expect(word.textContent).toBe('Нотатки до поїздки в Карпати');
    expect(word.className).toContain('line-clamp-2');
    expect(word.className).not.toContain('truncate');
  });

  it('«відкрити на ПК» — лише документ і лише на телефоні: решта не відкривається ніде', () => {
    expect(pcRowTail('widget:canvas', true)).toBe('відкрити на ПК');
    expect(pcRowTail('widget:canvas', false)).toBe('не показується');
    expect(pcRowTail('widget:kanban', true)).toBe('не показується');
    expect(pcRowTail('embed:mermaid', true)).toBe('не показується');
  });

  it('з даними — що всередині (тривалість, файл, мова), а не «не показується»', () => {
    renderChat([
      kindMsg({ type: 'snippet:async', asyncSnippetData: { id: 'v', title: 'Звіт', durationSeconds: 135, transcripts: [], authorName: 'О' } } as never, 'p33'),
      kindMsg({ type: 'embed:diff', codeDiffData: { filename: 'src/app.ts', oldCode: '', newCode: '' } } as never, 'p34'),
      kindMsg({ type: 'widget:code-runner', codeRunnerData: { id: 'c', language: 'python', code: 'print(1)' } } as never, 'p35'),
    ]);
    const row = (id: string) => within(bubble(id)).getByTestId('card-pc-row').textContent;
    expect(row('p33')).toBe('Звіт2:15');
    expect(row('p34')).toBe('Зміни в кодіapp.ts');
    expect(row('p35')).toBe('Кодpython');
  });
});

describe('дошка, ролі, план-графік, навчальні картки — справжній вид, лише читання', () => {
  const items = (n: number) => Array.from({ length: n }, (_, i) => ({ id: `i${i}`, title: `Пункт ${i + 1}` }));
  const BOARD = { id: 'k', title: 'Ремонт', columns: [{ id: 'a', title: 'Треба', items: items(3) }, { id: 'b', title: 'Готово', items: [{ id: 'd', title: 'Двері' }] }] };
  const PLAN = {
    id: 't', title: 'Переїзд',
    milestones: [
      { id: 'm1', title: 'Коробки', status: 'completed', progress: 100 },
      { id: 'm2', title: 'Фургон', status: 'in_progress', progress: 40 },
      { id: 'm3', title: 'Ключі', status: 'blocked', progress: 0 },
    ],
  };
  const STUDY = { id: 's', topic: 'Хімія', cards: [{ id: 'c1', question: 'H2O?', answer: 'Вода', reviewed: false }, { id: 'c2', question: 'NaCl?', answer: 'Сіль', reviewed: false }] };
  const ROLES = { id: 'r', title: 'Хто що', roles: [], rows: [{ id: '1', task: 'Квитки', r: 'Оксана', a: 'Кирило', c: '', i: 'Марта' }] };

  it('дошка: пункти з прапорцем, останній стовпець — зроблено, «готово 1 з 4», час усередині', () => {
    renderChat([kindMsg({ type: 'widget:kanban', kanbanData: BOARD, text: '' } as never, 'w1')]);
    const card = within(bubble('w1')).getByTestId('card-board');
    expect(within(card).getByTestId('card-title').textContent).toBe('Ремонт');
    expect(within(card).getByTestId('card-status').textContent).toBe('готово 1 з 4');
    expect(within(card).getAllByTestId('work-row').map((r) => r.textContent)).toEqual(['Пункт 1Треба', 'Пункт 2Треба', 'Пункт 3Треба']);
    expect(card.querySelector('[data-card-meta]')?.textContent).toContain('12:40');
    expect(within(bubble('w1')).queryByTestId('card-pc-row')).toBeNull();
    fireEvent.click(within(card).getByRole('button', { name: 'Відкрити' }));
    const sheet = screen.getByTestId('sheet-board');
    const done = within(sheet).getAllByTestId('work-row').filter((r) => r.hasAttribute('data-done'));
    expect(done.map((r) => r.textContent)).toEqual(['ДверіГотово']);
  });

  it('дошка й план із ключем: прапорець справжній — кнопка з aria-pressed, а не намальоване коло', () => {
    renderChat([
      kindMsg({ type: 'widget:kanban', kanbanData: BOARD, text: '' } as never, 'w5'),
      kindMsg({ type: 'widget:timeline', timelineData: PLAN, text: '' } as never, 'w6'),
    ]);
    for (const id of ['w5', 'w6']) {
      const rows = within(bubble(id)).getAllByTestId('work-row');
      for (const r of rows) {
        expect(r.tagName).toBe('BUTTON');
        expect(r.getAttribute('aria-pressed')).toBe(r.hasAttribute('data-done') ? 'true' : 'false');
        expect(r.querySelector('[data-testid="work-mark"]')).toBeNull();
      }
    }
  });

  it('план-графік: кроки з поступом — зроблено, «40 %», «стоїть»', () => {
    renderChat([kindMsg({ type: 'widget:timeline', timelineData: PLAN, text: '' } as never, 'w2')]);
    const card = within(bubble('w2')).getByTestId('card-plan');
    expect(within(card).getByTestId('card-status').textContent).toBe('готово 1 з 3');
    const rows = within(card).getAllByTestId('work-row');
    expect(rows.map((r) => r.textContent)).toEqual(['Коробки', 'Фургон40 %', 'Ключістоїть']);
    expect(rows[0].hasAttribute('data-done')).toBe(true);
  });

  it('ролі: завдання і хто виконує; в аркуші — усі ролі словами', () => {
    renderChat([kindMsg({ type: 'widget:raci', raciData: ROLES, text: '' } as never, 'w3')]);
    const card = within(bubble('w3')).getByTestId('card-roles');
    expect(within(card).getByTestId('work-row').textContent).toBe('КвиткиОксана');
    fireEvent.click(within(card).getByRole('button', { name: 'Відкрити' }));
    expect(within(screen.getByTestId('sheet-roles')).getByTestId('work-role').textContent).toBe('КвиткиВиконує · ОксанаВідповідає · КирилоУ курсі · Марта');
  });

  it('навчальні картки: питання → торк → відповідь → «Далі» → наступне питання', () => {
    renderChat([kindMsg({ type: 'widget:study-flashcards', studyFlashcardsData: STUDY, text: '' } as never, 'w4')]);
    const card = within(bubble('w4')).getByTestId('card-study');
    const face = () => within(card).getByTestId('study-face');
    expect(within(card).getByTestId('card-status').textContent).toBe('картка 1 з 2');
    expect(face().textContent).toBe('ПитанняH2O?');
    fireEvent.click(face());
    expect(face().textContent).toBe('ВідповідьВода');
    fireEvent.click(within(card).getByRole('button', { name: 'Далі' }));
    expect(face().textContent).toBe('ПитанняNaCl?');
    expect(within(card).getByTestId('card-status').textContent).toBe('картка 2 з 2');
  });

  it('без пунктів — чесний рядок «не показується», не порожня картка', () => {
    renderChat([kindMsg({ type: 'widget:timeline', timelineData: { id: 't', title: 'Порожньо', milestones: [] }, text: '' } as never, 'w5')]);
    expect(within(bubble('w5')).queryByTestId('card-plan')).toBeNull();
    expect(within(bubble('w5')).getByTestId('card-pc-row').textContent).toBe('Порожньоне показується');
  });
});

describe('одна анатомія картки: час і ✓✓ — усередині, бурштин — лише на головній дії', () => {
  const TABLE = {
    id: 'tb', title: 'Хто що бере',
    columns: [{ key: 'c1', label: 'Що', type: 'text' }, { key: 'c2', label: 'Сума', type: 'number' }],
    rows: [{ c1: 'Кава', c2: 90 }, { c1: 'Круасани', c2: 120 }],
  };
  const TASKS = { title: 'План', tasks: ['Квитки', 'Стіл', 'Таксі', 'Квіти', 'Торт'].map((t, i) => ({ id: `t${i}`, title: t, completed: false })) };
  const POLL = { id: 'pl', question: 'Куди йдемо?', options: [{ id: 'o1', text: 'Кіно', votes: 0, voters: [] }, { id: 'o2', text: 'Скелі', votes: 0, voters: [] }], totalVotes: 0 };
  const EVENT = { title: 'Сніданок', date: '2026-11-01', time: '10:00', location: 'Сад', attendees: [] };

  const CASES: Array<[string, Partial<Message>, string]> = [
    ['table', { type: 'table', tableData: TABLE } as never, 'card-table'],
    ['task-list', { type: 'task-list', taskListData: TASKS } as never, 'card-checklist'],
    ['poll', { type: 'poll', pollData: POLL } as never, 'card-poll'],
    ['event', { type: 'event', eventData: EVENT } as never, 'card-event'],
  ];

  for (const [kind, over, testId] of CASES) {
    it(`${kind}: час і ✓ у рядку мети всередині картки, під карткою — нічого`, () => {
      renderChat([kindMsg({ ...over, text: '', senderId: 'me', isSelf: true, status: 'sent', readAt: '2026-10-29T09:00:00Z' } as never, `a-${kind}`)]);
      const b = bubble(`a-${kind}`);
      const card = within(b).getByTestId(testId);
      const meta = card.querySelector('[data-card-meta]') as HTMLElement;
      expect(meta).not.toBeNull();
      expect(meta.textContent).toContain('12:40');
      expect(meta.querySelector('[data-msg-tick="read"]')).not.toBeNull();
      expect(b.querySelector('[data-msg-meta-chip]')).toBeNull();
    });
  }

  it('чужа картка: час усередині, без галочок', () => {
    renderChat([kindMsg({ type: 'table', tableData: TABLE, text: '' } as never, 'a-peer')]);
    const meta = within(bubble('a-peer')).getByTestId('card-table').querySelector('[data-card-meta]')!;
    expect(meta.textContent).toBe('12:40');
    expect(meta.querySelector('[data-msg-tick]')).toBeNull();
  });

  it('тіло — не більше трьох рядків: зі списку в пʼять справ видно три', () => {
    renderChat([kindMsg({ type: 'task-list', taskListData: TASKS, text: '' } as never, 'a-peek')]);
    const card = within(bubble('a-peek')).getByTestId('card-checklist');
    expect(within(card).queryByText('Торт')).toBeNull();
    expect(within(card).queryByText('Квіти')).toBeNull();
    expect(within(card).getByText('Таксі')).toBeDefined();
  });

  it('бурштин — лише на головній дії: «Підтвердити» в опитуванні про час так, «Відкрити» в таблиці ні', () => {
    renderChat([
      kindMsg({ type: 'widget:voting', votingData: { question: 'Коли?', kind: 'time', options: [{ id: 'o1', text: '18:00', voters: [] }, { id: 'o2', text: '19:00', voters: [] }] }, text: '' } as never, 'a-p'),
      kindMsg({ type: 'table', tableData: TABLE, text: '' } as never, 'a-t'),
    ]);
    const pollBtn = within(bubble('a-p')).getByTestId('card-poll').querySelector('[data-card-actions] button')!;
    const tableBtn = within(bubble('a-t')).getByTestId('card-table').querySelector('[data-card-actions] button')!;
    expect(pollBtn.className).toContain('bg-accent');
    expect(tableBtn.className).not.toContain('bg-accent');
  });
});

describe('дублі: одна пара — одне слово, один вигляд', () => {
  it('список справ і сімейний список — одне слово в списку розмов, як у шапці картки', () => {
    expect(kindLine('task-list')).toBe('Список справ');
    expect(kindLine('widget:family-checklist')).toBe('Список справ');
  });

  it('озвучка: опитування з віджета читається так само, як звичайне', () => {
    const q = { question: 'Коли?', options: [] };
    expect(speakText(kindMsg({ type: 'widget:voting', text: '', votingData: q } as never, 'v1'))).toBe('Опитування: Коли?');
    expect(speakText(kindMsg({ type: 'poll', text: '', pollData: { ...q, totalVotes: 0 } } as never, 'v2'))).toBe('Опитування: Коли?');
  });

  const LOC = { name: 'Кавʼярня «Зерно»', category: 'cafe', address: 'Хрещатик, 5', distance: '300 м', walkingTime: '4 хв', coords: { lat: 50.45, lng: 30.52 } };

  it('заклад (location) — картка місця: «Місце» в шапці, назва, адреса, відстань, час усередині', () => {
    renderChat([kindMsg({ type: 'location', text: '', locationData: LOC } as never, 'pl1')]);
    const b = bubble('pl1');
    const card = within(b).getByTestId('card-place');
    expect(card.querySelector('[data-card-kind]')!.textContent).toBe('Місце');
    expect(within(card).getByTestId('card-title').textContent).toBe('Кавʼярня «Зерно»');
    expect(within(card).getByTestId('card-status').textContent).toBe('Хрещатик, 5 · 300 м · 4 хв');
    expect(card.querySelector('[data-card-meta]')!.textContent).toBe('12:40');
    expect(b.querySelector('[data-msg-meta-chip]')).toBeNull();
  });

  it('точка «я тут» — та сама картка місця, кнопка «Маршрут»', () => {
    renderChat([kindMsg({ type: 'geo:point', text: '', geoPoint: { lat: 50.45, lon: 30.52, atMs: Date.now(), label: 'Вокзал' } } as never, 'pl2')]);
    const b = bubble('pl2');
    const card = within(b).getByTestId('geo-point-bubble');
    expect(card.getAttribute('data-kit')).toBe('ticket-card');
    expect(card.querySelector('[data-card-kind]')!.textContent).toBe('Місце');
    expect(within(card).getByTestId('card-title').textContent).toBe('Вокзал');
    expect(within(card).getByRole('button', { name: 'Маршрут' })).toBeTruthy();
    expect(card.querySelector('[data-card-meta]')!.textContent).toBe('12:40');
  });

  it('старий рахунок (split-bill) — та сама анатомія, що й рахунок: «Рахунок» у шапці, сума, примітка', () => {
    const bill = { title: 'Піца', totalAmount: 30, currency: 'UAH', participants: [{ id: 'p1', name: 'Петро', avatar: '', share: 15, paid: false }, { id: 'p2', name: 'Ія', avatar: '', share: 15, paid: true }] };
    renderChat([kindMsg({ type: 'split-bill', text: '', splitBillData: bill } as never, 'sb1')]);
    const b = bubble('sb1');
    const card = within(b).getByTestId('card-bill-legacy');
    expect(card.querySelector('[data-card-kind]')!.textContent).toBe('Рахунок');
    expect(within(card).getByTestId('card-title').textContent).toBe('Піца');
    expect(within(card).getByTestId('card-status').textContent).toContain('30');
    expect(within(card).getByTestId('legacy-bill-note')).toBeTruthy();
    expect(card.querySelector('[data-card-meta]')!.textContent).toBe('12:40');
  });

  it('свій рядок — «Ви» і в старому рахунку, і у витратах, як у новому рахунку', () => {
    const bill = { title: 'Вечеря', totalAmount: 300, currency: 'UAH', participants: [{ id: 'p1', name: 'Оксана', avatar: '', share: 150, paid: true }, { id: 'me', name: 'Я', avatar: '', share: 150, paid: false }] };
    const exp = { id: 'fe1', title: 'Вечеря', currency: '₴', items: [{ id: 'e1', title: 'Піца', amountUAH: 420, category: '', paidBy: 'Оксана' }, { id: 'e2', title: 'Сік', amountUAH: 90, category: '', paidBy: 'Кирило' }] };
    renderChat([
      kindMsg({ type: 'split-bill', text: '', splitBillData: bill } as never, 'sb2'),
      kindMsg({ type: 'widget:family-expense', text: '', familyExpenseData: exp } as never, 'fe2'),
    ]);
    const legacy = within(bubble('sb2')).getByTestId('card-bill-legacy');
    expect(legacy.textContent).toContain('Ви');
    expect(legacy.textContent).not.toContain('Я150');
    expect(legacy.textContent).toContain('Оксана');
    const expense = within(bubble('fe2')).getByTestId('card-expense');
    expect(expense.textContent).toContain('Ви');
    expect(expense.textContent).not.toContain('Кирило');
  });

  it('вкладка «Місця» бере й заклади, не лише точки', () => {
    const idx = indexShared([kindMsg({ type: 'location', text: '', locationData: LOC } as never, 'pl3')], 'me');
    expect(idx.places.map((p) => [p.msgId, p.label, p.lat, p.lon])).toEqual([['pl3', 'Кавʼярня «Зерно»', 50.45, 30.52]]);
  });
});
