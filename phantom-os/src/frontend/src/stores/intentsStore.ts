/**
 * Журнал «Мої прохання» у вебі: копія того, що знає телефон. Оновлюється
 * подією мосту `__phantomIntentsChanged` і на першому показі — без таймерів.
 */
import { create } from 'zustand';
import { intentsApi, type IntentRow } from '../services/intentsApi';

interface IntentsState {
  /** null — міст не відповів (не телефон або старша збірка): немає частини. */
  rows: IntentRow[] | null;
  refresh: () => Promise<void>;
}

export const useIntentsStore = create<IntentsState>((set) => ({
  rows: null,
  refresh: async () => {
    try {
      const res = await intentsApi.list();
      set({ rows: Array.isArray(res?.intents) ? res.intents : null });
    } catch {
      set({ rows: null });
    }
  },
}));

export function installIntentsHook(): void {
  const w = globalThis as { __phantomIntentsChanged?: () => void };
  w.__phantomIntentsChanged = () => void useIntentsStore.getState().refresh();
}
