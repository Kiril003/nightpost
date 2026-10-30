import { create } from 'zustand';

interface SettleUpState {
  chatId: string | null;
  open: (chatId: string) => void;
  close: () => void;
}

export const useSettleUp = create<SettleUpState>((set) => ({
  chatId: null,
  open: (chatId: string) => set({ chatId }),
  close: () => set({ chatId: null }),
}));
