/**
 * Профіль людини в новому вигляді: постер, чотири рівні дії, «Між вами» з
 * чек-листів розмови, «Памʼятати» зі згадок у памʼяті PHANTOM, «Спільне»
 * одним рядком, другорядне — тихо внизу.
 *
 * Червоніти вміє: покажіть виконані пункти в «Між вами» — падає «лише
 * відкриті»; приберіть фільтр `factsAbout` — падає «чужі факти не йдуть»;
 * поверніть значки на рядки блокування — падає «тихі рядки».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi } from '../services/messengerApi';
import type { NodeContact } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import type { KnownFact } from '../services/phantomSelfApi';
import { dueWords, factsAbout, openTasksIn, sharedSummary } from '../services/peerBetween';
import { indexShared } from '../services/peerShared';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const PEER = 'a1b2c3d4e5f60718';

const contact = (over: Partial<NodeContact> = {}): NodeContact => ({
  id: 'ct_1',
  peer_node_id: PEER,
  display_name: 'Оксана Мельник',
  peer_address: null,
  safety_number: '111112222233333444445555566666',
  safety_number_pretty: '11111 22222 33333 44444 55555 66666',
  verified: true,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
  ...over,
});

const fact = (id: string, text: string, over: Partial<KnownFact> = {}): KnownFact => ({
  id,
  text,
  from: 'self',
  from_label: 'ви розповіли',
  skill: false,
  at: '2026-09-20T10:00:00Z',
  ...over,
});

const taskMsg = (id: string, tasks: Array<{ id: string; title: string; completed: boolean; assigneeName?: string; dueDate?: string }>): Message =>
  ({
    id,
    senderId: 'me',
    senderName: 'Я',
    timestamp: '10:00',
    type: 'task-list',
    isSelf: true,
    taskListData: { title: 'Справи', tasks },
  }) as unknown as Message;

const seedChat = (messages: Message[]) => {
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: 'me', name: 'Кирило' },
    chats: [{ id: 'conv_ok', title: 'Оксана Мельник', type: 'dm', peerNodeId: PEER, messages, muted: false } as unknown as Chat],
  });
};

const open = () =>
  render(<PeerProfileSheet peerNodeId={PEER} name="Оксана Мельник" chatId="conv_ok" onClose={() => {}} onWhere={() => {}} />);

beforeEach(() => {
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact()]);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('постер і дії', () => {
  it('фото немає — монограма тоном людини, імʼя поруч; одна бурштинова «Написати», решта — круглі', async () => {
    seedChat([]);
    open();
    expect(screen.getByTestId('peer-profile-initial').textContent).toBe('О');
    expect(screen.getByTestId('peer-profile-name').textContent).toBe('Оксана Мельник');
    await screen.findByTestId('peer-action-where');
    const actions = screen.getByTestId('info-actions');
    expect(actions.querySelectorAll('.bg-accent')).toHaveLength(1);
    expect(screen.getByTestId('peer-action-write').className).toContain('bg-accent');
    expect(screen.getByTestId('peer-action-mute').className).toContain('rounded-full');
    expect(screen.getByTestId('peer-action-mute').className).not.toContain('bg-accent');
  });

  it('людина в тій самій мережі — «поруч» у рядку під імʼям, а не «на звʼязку»', async () => {
    seedChat([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ lan_seen_at: new Date().toISOString() })]);
    open();
    await waitFor(() => expect(screen.getByTestId('info-kind').textContent).toContain('поруч'));
  });
});

describe('«Між вами»', () => {
  it('лише відкриті пункти чек-листів розмови, з тим, чия справа', async () => {
    seedChat([
      taskMsg('m1', [
        { id: 't1', title: 'Забрати посилку на пошті', completed: false, assigneeName: 'Кирило' },
        { id: 't2', title: 'Купити квитки', completed: true, assigneeName: 'Оксана' },
        { id: 't3', title: 'Скинути фото з весілля', completed: false, assigneeName: 'Оксана' },
      ]),
    ]);
    open();
    const section = screen.getByTestId('peer-profile-between');
    expect(section.textContent).toContain('Забрати посилку на пошті');
    expect(section.textContent).toContain('Ви');
    expect(section.textContent).toContain('Скинути фото з весілля');
    expect(section.textContent).toContain('Оксана');
    expect(section.textContent).not.toContain('Купити квитки');
  });

  it('відкритих справ немає — розділу немає', () => {
    seedChat([taskMsg('m1', [{ id: 't1', title: 'Готово', completed: true }])]);
    open();
    expect(screen.queryByTestId('peer-profile-between')).toBeNull();
  });
});

describe('«Памʼятати»', () => {
  it('лише факти, де людину згадано, із замочком «бачите лише ви»', async () => {
    seedChat([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([
      fact('f1', 'День народження Оксани — 14 жовтня'),
      fact('f2', 'Люблю каву без цукру'),
      fact('f3', 'Оксана не їсть горіхи', { old: true }),
    ]);
    open();
    const section = await screen.findByTestId('peer-profile-remember');
    expect(section.textContent).toContain('бачите лише ви');
    expect(section.textContent).toContain('День народження Оксани — 14 жовтня');
    expect(section.textContent).not.toContain('каву');
    expect(section.textContent).not.toContain('горіхи');
  });

  it('памʼять мовчить — розділу немає', async () => {
    seedChat([]);
    vi.spyOn(phantomSelfApi, 'facts').mockRejectedValue(new Error('нема'));
    open();
    await screen.findByTestId('peer-action-where');
    expect(screen.queryByTestId('peer-profile-remember')).toBeNull();
  });
});

describe('другорядне', () => {
  it('блок, очищення й видалення — один блок рядків зі значками, після дій', async () => {
    seedChat([]);
    open();
    await screen.findByRole('button', { name: 'Заблокувати' });
    const danger = screen.getByTestId('peer-profile-danger');
    expect(within(danger).getByRole('button', { name: 'Видалити розмову' }).querySelector('svg')).not.toBeNull();
    // У DOM рядки стоять після дій — не на рівні головного.
    expect(screen.getByTestId('info-actions').compareDocumentPosition(danger) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe('peerBetween', () => {
  it('імʼя ловиться у відмінках, коротке — лише цілим словом', () => {
    const facts = [fact('a', 'Подарувати Оксані книжку'), fact('b', 'Янтарний браслет'), fact('c', 'Ян любить шахи')];
    expect(factsAbout(facts, 'Оксана Мельник').map((f) => f.id)).toEqual(['a']);
    expect(factsAbout(facts, 'Ян').map((f) => f.id)).toEqual(['c']);
  });

  it('строк людськими словами', () => {
    const now = new Date(2026, 8, 26, 12, 0);
    expect(dueWords('2026-09-26T20:00', now)).toBe('сьогодні до 20:00');
    expect(dueWords('2026-09-27', now)).toBe('до завтра');
    expect(dueWords('в неділю', now)).toBe('в неділю');
    expect(dueWords('', now)).toBeNull();
  });

  it('стерті листи в «Між вами» не йдуть', () => {
    const m = { ...taskMsg('m1', [{ id: 't', title: 'x', completed: false }]), isDeleted: true } as Message;
    expect(openTasksIn([m], 'Я', 'Оксана')).toEqual([]);
  });

  it('підсумок «Спільного» — лише непорожнє, з відмінюванням', () => {
    const idx = indexShared(
      [
        { id: '1', senderId: 'p', senderName: 'О', timestamp: '', type: 'text', text: 'https://a.ua https://b.ua' } as Message,
        { id: '2', senderId: 'p', senderName: 'О', timestamp: '', type: 'geo:point', geoPoint: { lat: 50, lon: 30 } } as unknown as Message,
      ],
      'me',
    );
    expect(sharedSummary(idx)).toBe('1 місце · 2 посилання');
  });
});

describe('помилки не ламають екран', () => {
  it('вузол мовчить — імʼя й «Написати» на місці', async () => {
    seedChat([]);
    vi.spyOn(messengerApi, 'listContacts').mockRejectedValue(new Error('x'));
    open();
    await waitFor(() => expect(screen.getByTestId('peer-action-write')).toBeTruthy());
    expect(screen.getByTestId('peer-profile-name').textContent).toBe('Оксана Мельник');
  });
});

describe('порожній профіль (A52, 26.09)', () => {
  it('«Перевірити особу» — коротко, без повного імені; без фото — монограма 88 без постера (§10.10)', async () => {
    seedChat([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ verified: false })]);
    open();
    const row = await screen.findByRole('button', { name: 'Перевірити особу' });
    expect(row.textContent).not.toContain('Оксана');
    expect(screen.getByTestId('peer-profile-poster').className).not.toMatch(/h-\[\d+px\]/);
    expect(screen.getByTestId('peer-profile-initial').className).toContain('w-20');
  });

  it('без фото голова профілю та сама, хоч є що показати під нею — постер лише з фото', async () => {
    seedChat([taskMsg('m1', [{ id: 't1', title: 'Забрати посилку', completed: false }])]);
    open();
    expect(screen.getByTestId('peer-profile-poster').className).not.toMatch(/h-\[\d+px\]/);
  });
});
