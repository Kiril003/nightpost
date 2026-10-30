import { create } from 'zustand';
import { messengerApi, type ContactRequest } from '../services/messengerApi';
import { useCirclesStore } from './circlesStore';
import { useMessengerStore } from './messengerStore';
import { selfAdded } from '../services/selfAdded';

export const UNDO_MS = 5000;

export type StagedKind = 'accept' | 'decline' | 'withdraw';
interface Staged {
  row: ContactRequest;
  kind: StagedKind;
  circle?: string;
  timer: ReturnType<typeof setTimeout>;
}

const staged = new Map<string, Staged>();

interface RequestsState {
  incoming: ContactRequest[];
  outgoing: ContactRequest[];
  loaded: boolean;
  error: boolean;
  load: () => Promise<void>;
  /** Рядок зникає одразу; сервер дізнається через UNDO_MS, якщо не скасували. */
  stage: (id: string, kind: StagedKind, circle?: string) => void;
  undo: (id: string) => ContactRequest | null;
  flush: () => void;
}

const commit = async (id: string, s: Staged, restore: (row: ContactRequest) => void) => {
  try {
    if (s.kind === 'accept') {
      const r = await messengerApi.acceptRequest(id);
      selfAdded.mark(r.conversation_id);
      if (s.circle) await useCirclesStore.getState().toggle(r.conversation_id, s.circle, true);
      void useMessengerStore.getState().refreshConversations();
    } else if (s.kind === 'decline') await messengerApi.declineRequest(id);
    else await messengerApi.withdrawRequest(id);
  } catch {
    restore(s.row);
  }
};

export const useRequestsStore = create<RequestsState>((set, get) => {
  const restore = (row: ContactRequest) =>
    set((st) =>
      row.direction === 'in'
        ? { incoming: st.incoming.some((r) => r.id === row.id) ? st.incoming : [row, ...st.incoming], error: true }
        : { outgoing: st.outgoing.some((r) => r.id === row.id) ? st.outgoing : [row, ...st.outgoing], error: true },
    );
  const run = (id: string) => {
    const s = staged.get(id);
    if (!s) return;
    clearTimeout(s.timer);
    staged.delete(id);
    void commit(id, s, restore);
  };
  return {
    incoming: [],
    outgoing: [],
    loaded: false,
    error: false,
    load: async () => {
      try {
        const r = await messengerApi.listRequests();
        set({
          incoming: r.incoming.filter((x) => !staged.has(x.id)),
          outgoing: r.outgoing.filter((x) => !staged.has(x.id)),
          loaded: true,
          error: false,
        });
      } catch {
        set({ error: true, loaded: true });
      }
    },
    stage: (id, kind, circle) => {
      const row = get().incoming.find((r) => r.id === id) ?? get().outgoing.find((r) => r.id === id);
      if (!row || staged.has(id)) return;
      set((st) => ({
        incoming: st.incoming.filter((r) => r.id !== id),
        outgoing: st.outgoing.filter((r) => r.id !== id),
      }));
      staged.set(id, { row, kind, circle, timer: setTimeout(() => run(id), UNDO_MS) });
    },
    undo: (id) => {
      const s = staged.get(id);
      if (!s) return null;
      clearTimeout(s.timer);
      staged.delete(id);
      set((st) =>
        s.row.direction === 'in'
          ? { incoming: [s.row, ...st.incoming] }
          : { outgoing: [s.row, ...st.outgoing] },
      );
      return s.row;
    },
    flush: () => [...staged.keys()].forEach(run),
  };
});

/** Додали людину за ніком чи QR: запит іде тихо, помилка не ламає додавання. */
export const sendContactRequest = (contactId: string): void => {
  try {
    void messengerApi
      .sendRequest(contactId)
      .then(() => useRequestsStore.getState().load())
      .catch(() => {});
  } catch {
    /* додавання людини важливіше за запит */
  }
};
