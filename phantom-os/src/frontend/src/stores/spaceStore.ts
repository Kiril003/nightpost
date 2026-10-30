import { create } from 'zustand';

/**
 * Розгорнута гілка відповідей поверх розмови.
 *
 * Тут жили «Простори» (простори → топіки → гілки): localStorage
 * `phantom-spaces`, учасник — лише ти, нікому не передавалось. Прибрано
 * 24.09 разом з екраном і входами. Лишилось те, що справді працює: гілка
 * відповідей на лист, яку збирає ThreadView з листів розмови.
 */
interface ThreadViewState {
  /**
   * Корінь + відповіді на нього. Тримаємо розмову поруч — без неї корінь
   * нема де шукати, а гілка без кореня — вигадка.
   */
  threadView: { chatId: string; rootMessageId: string } | null;
  openThreadView: (v: { chatId: string; rootMessageId: string } | null) => void;
}

export const useSpaceStore = create<ThreadViewState>()((set) => ({
  threadView: null,
  openThreadView: (v) => set({ threadView: v }),
}));
