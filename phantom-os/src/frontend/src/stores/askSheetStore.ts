import { create } from 'zustand';
import type { AskContext } from '../services/askContext';

interface AskSheetState {
  ctx: AskContext | null;
  /** Лише чернетка: куди «Вставити» кладе текст (композер, що відкрив аркуш). */
  onInsert: ((text: string) => void) | null;
  /** Кожне відкриття — новий аркуш, навіть із тим самим контекстом. */
  seq: number;
  openAsk: (ctx: AskContext, opts?: { onInsert?: (text: string) => void }) => void;
  close: () => void;
}

export const useAskSheet = create<AskSheetState>((set) => ({
  ctx: null,
  onInsert: null,
  seq: 0,
  openAsk: (ctx, opts) => set((s) => ({ ctx, onInsert: opts?.onInsert ?? null, seq: s.seq + 1 })),
  close: () => set({ ctx: null, onInsert: null }),
}));

export const openAsk = (ctx: AskContext, opts?: { onInsert?: (text: string) => void }): void =>
  useAskSheet.getState().openAsk(ctx, opts);
