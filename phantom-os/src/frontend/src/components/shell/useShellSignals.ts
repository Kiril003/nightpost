/**
 * Живі сигнали оболонки ПК — усе, з чого складаються присутність PHANTOM і
 * картки Головної. Кожне поле — з вузла або зі стору, що його дзеркалить;
 * запит упав — поле порожнє, і відповідна картка просто не малюється.
 */
import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request, chatApi } from '../../services/api';
import { useMessengerStore } from '../../stores/messengerStore';
import { useSystemStore } from '../../stores/systemStore';
import type { Chat } from '../../types/messenger';

interface AgentLane {
  active: boolean;
  goal: string | null;
}
interface AgentStatus {
  foreground: AgentLane;
  background: AgentLane;
}

export interface CalendarEvent {
  id: string;
  title: string;
  start_at: string;
  end_at: string;
  all_day: boolean;
  location: string | null;
}

export interface Noticed {
  text: string;
  at: string;
}

/** Час вузла без поясу — UTC (так пише SQLite-шар), з поясом — як є. */
export function parseNodeTime(iso: string): number {
  const hasZone = /[zZ]|[+-]\d\d:?\d\d$/.test(iso);
  return Date.parse(hasZone ? iso : `${iso}Z`);
}

/** Найближча подія, що ще не закінчилась, у межах доби. */
export function nextEventOf(events: CalendarEvent[], now: number): (CalendarEvent & { inMinutes: number }) | null {
  const day = 24 * 60 * 60 * 1000;
  const upcoming = events
    .map((e) => ({ e, start: parseNodeTime(e.start_at), end: parseNodeTime(e.end_at) }))
    .filter(({ start, end }) => Number.isFinite(start) && end > now && start - now <= day)
    .sort((a, b) => a.start - b.start);
  if (!upcoming.length) return null;
  const { e, start } = upcoming[0];
  return { ...e, inMinutes: Math.max(0, Math.round((start - now) / 60_000)) };
}

/** Хто чекає відповіді: живі розмови (не показові) з непрочитаним. */
export function waitingOf(chats: Chat[]): Chat[] {
  return chats
    .filter((c) => !c.isDemo && !c.archived && !c.muted && c.type !== 'channel' && (c.unreadCount ?? 0) > 0)
    .sort((a, b) => Date.parse(b.lastAt ?? '') - Date.parse(a.lastAt ?? ''));
}

const HALF_DAY = 12 * 60 * 60 * 1000;

/** Один ключ — одна форма: оболонка й Головна ділять кеш календаря. */
export function useShellCalendar(enabled: boolean) {
  return useQuery({
    queryKey: ['shell', 'calendar'],
    queryFn: async () => calendarEventsOf(await request<unknown>('GET', '/tools/calendar')),
    enabled,
    refetchInterval: 60_000,
    retry: 0,
  });
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;

/** Відповідь вузла не тієї форми — ніби агента нема, а не падіння оболонки. */
export function agentStatusOf(raw: unknown): AgentStatus {
  const lane = (v: unknown): AgentLane => ({
    active: isObj(v) && v.active === true,
    goal: isObj(v) && typeof v.goal === 'string' ? v.goal : null,
  });
  return { foreground: lane(isObj(raw) ? raw.foreground : null), background: lane(isObj(raw) ? raw.background : null) };
}

export function calendarEventsOf(raw: unknown): CalendarEvent[] {
  const list = isObj(raw) && Array.isArray(raw.events) ? raw.events : [];
  return list.filter(
    (e): e is CalendarEvent =>
      isObj(e) && typeof e.id === 'string' && typeof e.title === 'string' && typeof e.start_at === 'string' && typeof e.end_at === 'string',
  );
}

export function useShellSignals() {
  const authenticated = useSystemStore((s) => s.authenticated);
  const chats = useMessengerStore((s) => s.chats);
  const hydrated = useMessengerStore((s) => s.hydrated);

  // Розмови потрібні Головній ще до того, як людина відкриє месенджер —
  // але лише список: відкрити розмову означало б розчитати її за людину.
  useEffect(() => {
    if (authenticated && !hydrated) void useMessengerStore.getState().hydrateFromNode({ openFirst: false });
  }, [authenticated, hydrated]);

  const agent = useQuery({
    queryKey: ['shell', 'agent-status'],
    queryFn: async () => agentStatusOf(await request<unknown>('GET', '/agent/status')),
    enabled: authenticated,
    refetchInterval: 5000,
    retry: 0,
  });

  const calendar = useShellCalendar(authenticated);

  // Що PHANTOM сказав сам, не питаний: проактивні слова лягають у його
  // розмову з позначкою origin='proactive' (agent/cognition/proactive/loop.py).
  const noticed = useQuery({
    queryKey: ['shell', 'noticed'],
    enabled: authenticated,
    refetchInterval: 60_000,
    retry: 0,
    queryFn: async (): Promise<Noticed | null> => {
      const sessions = (await chatApi.getSessions(1, 0))?.sessions;
      if (!Array.isArray(sessions) || !isObj(sessions[0]) || typeof sessions[0].id !== 'string') return null;
      const messages = (await chatApi.getMessages(sessions[0].id))?.messages;
      if (!Array.isArray(messages)) return null;
      const own = [...messages]
        .reverse()
        .find(
          (m) =>
            isObj(m) &&
            m.role === 'assistant' &&
            typeof m.content === 'string' &&
            typeof m.created_at === 'string' &&
            (m.metadata as unknown as { origin?: string })?.origin === 'proactive' &&
            Date.now() - parseNodeTime(m.created_at) < HALF_DAY,
        );
      return own ? { text: own.content, at: own.created_at } : null;
    },
  });

  const agentBusy = Boolean(agent.data?.foreground.active || agent.data?.background.active);
  const agentGoal = agent.data?.foreground.active ? agent.data.foreground.goal : agent.data?.background.goal ?? null;

  return {
    agentBusy,
    agentGoal,
    waiting: waitingOf(chats),
    nextEvent: calendar.data ? nextEventOf(calendar.data, Date.now()) : null,
    noticed: noticed.data ?? null,
  };
}
