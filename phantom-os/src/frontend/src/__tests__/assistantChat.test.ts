import { describe, expect, it } from 'vitest';
import { chatFromNode, type NodeConversation } from '../services/messengerApi';
import { normalizeAssistantRow } from '../services/assistantChat';

// Рядок рівно такий, яким його шле WebMessengerBridge.phantomConversation,
// коли листування з помічником ще не було.
const bridgeRow = (over: Partial<NodeConversation> = {}): NodeConversation => ({
  id: 'peer_phantom_agent',
  title: '⚡ Nightpost Symbiote',
  kind: 'phantom',
  circle: 'all',
  handle: '@PhantomSymbiote',
  avatar: '⚡',
  pinned: true,
  archived: false,
  created_at: '2024-01-01T00:00:00Z',
  updated_at: '2026-09-24T07:15:00Z',
  contact_id: 'peer_phantom_agent',
  peer_node_id: 'peer_phantom_agent',
  is_demo: false,
  contact_verified: true,
  contact_key_changed: false,
  unread_count: 0,
  last_kind: 'text',
  last_snippet: '⚡ Готовий до завдань. Запитай або надай доручення...',
  last_author: 'Nightpost',
  last_at: '2026-09-24T07:15:00Z',
  ...over,
});

describe('розмова з помічником', () => {
  it('без листа: назва Nightpost, аватар як у всіх, ні часу, ні превʼю, ні стану ключа', () => {
    const chat = chatFromNode(bridgeRow());
    expect(chat.title).toBe('Nightpost');
    expect(chat.avatar).toBe('');
    expect(chat.handle).toBeUndefined();
    expect(chat.lastSnippet).toBeUndefined();
    expect(chat.lastAt).toBeUndefined();
    expect(chat.lastKind).toBeUndefined();
    // «ключ звірено» про програму — неправда: ключа в помічника немає.
    expect(chat.contactVerified).toBeNull();
  });

  it('справжній лист лишається, декор з його початку знято', () => {
    const chat = chatFromNode(bridgeRow({ last_snippet: '⚡ Маршрут на завтра готовий', last_at: '2026-09-23T10:00:00Z' }));
    expect(chat.lastSnippet).toBe('Маршрут на завтра готовий');
    expect(chat.lastAt).toBe('2026-09-23T10:00:00Z');
  });

  it('звичайних розмов не чіпає', () => {
    const row = bridgeRow({ kind: 'dm', title: 'Оксана', avatar: null });
    expect(normalizeAssistantRow(row)).toBe(row);
  });

  it('сесія кількох агентів: без емодзі-обкладинки й без заглушки', () => {
    const chat = chatFromNode(
      bridgeRow({
        id: 'agency_council',
        kind: 'ai',
        title: '🏛️ Колегія Агентів',
        avatar: '🏛️',
        last_snippet: '🏛️ Мультиагентна сесія: System Designer, Red Team.',
      }),
    );
    expect(chat.title).toBe('Колегія Агентів');
    expect(chat.avatar).toBe('');
    expect(chat.lastSnippet).toBeUndefined();
  });
});
