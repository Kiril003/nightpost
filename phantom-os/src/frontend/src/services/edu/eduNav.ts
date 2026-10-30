import { create } from 'zustand';

export type EduRoute =
  | { s: 'home'; chatId: string }
  | { s: 'assign'; chatId: string }
  | { s: 'assignment'; chatId: string; id: string }
  | { s: 'attendance'; chatId: string }
  | { s: 'forum'; chatId: string }
  | { s: 'thread'; chatId: string; id: string };

interface EduNavState {
  stack: EduRoute[];
  push: (r: EduRoute) => void;
  pop: () => void;
  close: () => void;
}

export const useEduNav = create<EduNavState>((set) => ({
  stack: [],
  push: (r) => set((s) => ({ stack: [...s.stack, r] })),
  pop: () => set((s) => ({ stack: s.stack.slice(0, -1) })),
  close: () => set({ stack: [] }),
}));

/** Відкрити клас: головна класу поверх усього. */
export const openClass = (chatId: string): void => useEduNav.getState().push({ s: 'home', chatId });
