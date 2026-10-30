import { create } from 'zustand';
import { messengerApi, type StaffPassRow } from '../services/messengerApi';

/** Пропуски, які власники дали цьому телефону. Лише в памʼяті: відкликання стирає все. */
export const useStaffInbox = create<{ passes: StaffPassRow[]; load: () => Promise<void> }>((set) => ({
  passes: [],
  load: async () => {
    try {
      const r = await messengerApi.staffInbox();
      set({ passes: Array.isArray(r?.passes) ? r.passes.filter((p) => p.threads?.length) : [] });
    } catch {
      set({ passes: [] });
    }
  },
}));
