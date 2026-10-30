import { create } from 'zustand';
import * as circlesApi from '../services/circlesApi';
import { ApiError, type CircleInfo } from '../services/circlesApi';
import { CIRCLES, CIRCLE_KEY, isRealCircle } from '../components/messenger/todayFormat';
import type { MessageKey } from '../i18n/messages/uk';
import { useMessengerStore } from './messengerStore';
import type { ChatCircle } from '../types/messenger';

export const DEFAULT_CIRCLES: CircleInfo[] = CIRCLES.map((id) => ({
  id,
  name: null,
  builtin: true,
  count: 0,
  locationDefault: id === 'personal' || id === 'close',
  momentsDefault: id === 'personal' || id === 'close' || id === 'friends',
}));

export const primaryOf = (list: readonly string[], known: readonly CircleInfo[] = []): ChatCircle => {
  if (!list.length) return 'all';
  for (const b of CIRCLES) {
    if (list.includes(b)) return b;
  }
  for (const c of known) {
    if (!c.builtin && list.includes(c.id)) return c.id as ChatCircle;
  }
  return list[0] as ChatCircle;
};

export const circleName = (
  info: Pick<CircleInfo, 'id' | 'name'>,
  t: (key: MessageKey) => string,
): string => {
  if (isRealCircle(info.id as ChatCircle)) {
    return t(CIRCLE_KEY[info.id as keyof typeof CIRCLE_KEY]);
  }
  return info.name?.trim() || info.id;
};

interface CirclesState {
  circles: CircleInfo[];
  maxCustom: number;
  load: () => Promise<void>;
  create: (name: string) => Promise<'ok' | 'tooMany' | 'bad' | 'failed'>;
  remove: (id: string) => Promise<boolean>;
  toggle: (chatId: string, circleId: string, on: boolean) => Promise<boolean>;
}

export const useCirclesStore = create<CirclesState>((set, get) => ({
  circles: DEFAULT_CIRCLES,
  maxCustom: 8,
  load: async () => {
    const { circles, maxCustom } = await circlesApi.listCirclesWithMeta();
    set({
      circles: circles.length > 0 ? circles : DEFAULT_CIRCLES,
      maxCustom,
    });
  },
  create: async (name) => {
    try {
      const created = await circlesApi.createCircle(name.trim());
      set((s) => ({
        circles: s.circles.some((c) => c.id === created.id) ? s.circles : [...s.circles, created],
      }));
      return 'ok';
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409 || err.code === 'too_many_circles') return 'tooMany';
        if (err.status === 400 || err.code === 'bad_circle_name') return 'bad';
      }
      return 'failed';
    }
  },
  remove: async (id) => {
    const ok = await circlesApi.deleteCircle(id);
    if (ok) {
      set((s) => ({ circles: s.circles.filter((c) => c.id !== id) }));
      const left = get().circles;
      useMessengerStore.setState((s) => ({
        chats: s.chats.map((c) => {
          if (!c.circles?.includes(id) && c.circle !== id) return c;
          const circles = (c.circles ?? []).filter((x) => x !== id);
          return { ...c, circles, circle: primaryOf(circles, left) };
        }),
      }));
    }
    return ok;
  },
  toggle: async (chatId, circleId, on) => {
    const list = on ? await circlesApi.joinCircle(circleId, chatId) : await circlesApi.leaveCircle(circleId, chatId);
    if (!list) return false;
    const primary = primaryOf(list, get().circles);
    useMessengerStore.setState((s) => ({
      chats: s.chats.map((c) => (c.id === chatId ? { ...c, circles: list, circle: primary } : c)),
    }));
    return true;
  },
}));
