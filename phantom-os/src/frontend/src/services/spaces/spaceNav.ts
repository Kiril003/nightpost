import { create } from 'zustand';
import type { Chat } from '../../types/messenger';

export type SpaceRoute =
  | { s: 'home'; chatId: string }
  | { s: 'create' }
  | { s: 'channel'; chatId: string; channelId: string }
  | { s: 'forum'; chatId: string; channelId: string }
  | { s: 'thread'; chatId: string; channelId: string; topicId: string }
  | { s: 'members'; chatId: string }
  | { s: 'roles'; chatId: string }
  | { s: 'role'; chatId: string; roleId: string };

interface SpaceNavState {
  stack: SpaceRoute[];
  /** Росте після кожного підтягування журналу: екрани перечитують сховище. */
  tick: number;
  /** «Подивитися як роль»: ідентифікатор ролі, очима якої ми дивимось на головну. */
  previewRoleId: string | null;
  push: (r: SpaceRoute) => void;
  pop: () => void;
  /** Замінити верхній маршрут — перемикання простору не росте стеком. */
  swap: (r: SpaceRoute) => void;
  close: () => void;
  bump: () => void;
  setPreviewRole: (roleId: string | null) => void;
}

export const useSpaceNav = create<SpaceNavState>((set) => ({
  stack: [],
  tick: 0,
  previewRoleId: null,
  push: (r) => set((s) => ({ stack: [...s.stack, r] })),
  pop: () => set((s) => ({ stack: s.stack.slice(0, -1) })),
  swap: (r) => set((s) => ({ stack: [...s.stack.slice(0, -1), r] })),
  close: () => set({ stack: [], previewRoleId: null }),
  bump: () => set((s) => ({ tick: s.tick + 1 })),
  setPreviewRole: (previewRoleId) => set({ previewRoleId }),
}));

export const isSpaceChat = (chat: Pick<Chat, 'groupSettings'> | undefined): boolean => chat?.groupSettings?.kind === 'space';

export const openSpace = (chatId: string): void => useSpaceNav.getState().push({ s: 'home', chatId });
