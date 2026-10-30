import { create } from 'zustand';
import { SystemState, StateTransition, ContextSnapshot } from '@shared/types';
import { contextApi } from '../services/api';
import { tNow } from '../components/chat/tNow';

/**
 * Чи доїхала РУЧНА зміна режиму до вузла.
 *
 * ТІНЬ, ВАРТОВИЙ і ПРИВИД — режими приватності й безпеки. Інтерфейс
 * застосовує їх локально й одразу, а вузол дізнається окремим запитом.
 * Коли той запит не доїхав, на склі горить один режим, а на вузлі діє
 * інший — і найгірше це саме для ПРИВИДА: камера тут гасне сама
 * (`components/core/Overlays.tsx`, CameraOverlay), тож людина читає згаслу
 * камеру як захист, якого вузол не вмикав.
 *
 * Тому відмова не ковтається, а стає станом:
 *   'idle'    — ручних змін ще не було;
 *   'pending' — повідомлення в дорозі;
 *   'synced'  — вузол підтвердив;
 *   'failed'  — вузол НЕ знає: режим лишився лише на склі.
 */
export type StateSyncStatus = 'idle' | 'pending' | 'synced' | 'failed';

export interface StateSync {
  status: StateSyncStatus;
  /** Режим, який намагались повідомити вузлу. */
  target: SystemState | null;
  /** Причина відмови, як її назвали вузол або мережа. */
  reason: string | null;
  /** Коли запис востаннє змінився, мс. */
  at: number | null;
}

export const IDLE_STATE_SYNC: StateSync = {
  status: 'idle',
  target: null,
  reason: null,
  at: null,
};

/**
 * Лічильник ручних змін. Відповідь на попередню зміну приходить після
 * наступної — і без цього мовчки розповідала б про режим, якого вже немає.
 */
let manualSeq = 0;

/** Коротка причина людською мовою; порожньою вона не буває. */
function describeFailure(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  if (typeof err === 'string' && err.trim()) return err;
  return tNow('system.nodeSilent');
}

interface SystemStoreState {
  state: SystemState;
  previousState: SystemState | null;
  stateHistory: StateTransition[];
  context: ContextSnapshot | null;
  authenticated: boolean;
  wsConnected: boolean;
  /** Whether we are running inside a Tauri shell (desktop mode). */
  isTauri: boolean;
  /** Live mic amplitude [0, 1] — updated by useVoiceRecorder while listening. */
  voiceAmplitude: number;
  /** Чи знає вузол про останню ручну зміну режиму. */
  stateSync: StateSync;
  /** Digital Endocrine System state — updated by inner_monologue.stream events. */
  sentience: {
    cortisol: number;
    dopamine: number;
    oxytocin: number;
  };

  setState: (s: SystemState, transition?: Omit<StateTransition, 'from' | 'to'>) => void;
  setContext: (ctx: ContextSnapshot) => void;
  setAuthenticated: (v: boolean) => void;
  setWsConnected: (v: boolean) => void;
  setVoiceAmplitude: (amp: number) => void;
  setSentience: (s: { cortisol: number; dopamine: number; oxytocin: number }) => void;
  /** Прибрати тривогу з поверхні — лише за дотиком людини. */
  clearStateSync: () => void;

  // Лишились тільки ті два, що мають виклики. Решта (goFocus, goDialogue,
  // goGhost, goDream, goOperator) не мала жодного — і була пасткою: вони
  // міняють стан, але не ведуть на «/», тож із будь-якого маршруту дали б
  // рівно те, чим хворів goOperator — плашка нова, екран старий.
  // FloatingToolbar через це має власні версії з navigate('/').
  // DREAM і OPERATOR ставить ядро саме, вручну їх вмикати нема чого.
  goShadow: () => void;
  goSentinel: () => void;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
if (import.meta.env.DEV) {
  (window as any).__phantom = (window as any).__phantom ?? {};
}

export const useSystemStore = create<SystemStoreState>((set, get) => ({
  state: SystemState.SHADOW,
  previousState: null,
  stateHistory: [],
  context: null,
  authenticated: false,
  wsConnected: false,
  isTauri: typeof window !== 'undefined' && !!(window as any).__TAURI_INTERNALS__,
  voiceAmplitude: 0,
  stateSync: IDLE_STATE_SYNC,
  sentience: { cortisol: 0.2, dopamine: 0.5, oxytocin: 0.5 },

  setState: (to, extra) => {
    const from = get().state;
    if (from === to) return;

    const transition: StateTransition = {
      from,
      to,
      trigger: extra?.trigger ?? 'unknown',
      timestamp: extra?.timestamp ?? Date.now(),
      auto: extra?.auto ?? true,
    };

    set((s) => ({
      state: to,
      previousState: from,
      stateHistory: [...s.stateHistory.slice(-99), transition],
    }));

    // If it's a manual transition (e.g. from the UI), notify the backend
    // so the state machines stay in sync.
    //
    // Раніше тут стояло `void contextApi.setState(...)`. Проміс викидався,
    // тож відмова вузла не доходила НІКУДИ: людина бачила новий режим і не
    // дізнавалась, що вузол лишився в старому. Порожній `.catch(() => {})`
    // був би тим самим ковтанням, тільки тихішим, — тому відмова стає
    // станом, який видно на склі (`Overlays.tsx`, StateSyncNotice).
    if (!transition.auto) {
      const seq = ++manualSeq;
      set({
        stateSync: {
          status: 'pending',
          target: to,
          reason: null,
          at: transition.timestamp,
        },
      });

      const settle = (status: 'synced' | 'failed', reason: string | null) => {
        // Пізня відповідь на скасовану зміну не сміє переписати свіжу.
        if (seq !== manualSeq) return;
        set({ stateSync: { status, target: to, reason, at: Date.now() } });
      };

      try {
        // `.then(ok, fail)` замість `.catch` навмисно: обробник відмови
        // навішується тією ж миттю, що й проміс, тож необробленою вона не
        // буває жодної мікрозадачі.
        Promise.resolve(contextApi.setState(to, transition.trigger)).then(
          () => settle('synced', null),
          (err: unknown) => settle('failed', describeFailure(err)),
        );
      } catch (err) {
        // Виклик уміє зірватись СИНХРОННО — клієнта не підняли, модуль
        // підмінений, моста в меш немає. Промісу тоді не існує взагалі, і
        // будь-який `.catch` на ньому не спіймав би нічого.
        settle('failed', describeFailure(err));
      }
    }
  },

  setContext: (ctx) => set({ context: ctx }),
  setAuthenticated: (v) => set({ authenticated: v }),
  setWsConnected: (v) => set({ wsConnected: v }),
  setVoiceAmplitude: (amp) => {
    // Ignore sub-threshold jitter to avoid pointless rerenders when idle.
    const current = get().voiceAmplitude;
    if (Math.abs(current - amp) < 0.02 && amp < 0.02) return;
    set({ voiceAmplitude: amp });
  },

  setSentience: (s) => set({ sentience: s }),

  clearStateSync: () => set({ stateSync: IDLE_STATE_SYNC }),

  goShadow: () =>
    get().setState(SystemState.SHADOW, { trigger: 'go-shorthand', timestamp: Date.now(), auto: false }),
  goSentinel: () =>
    get().setState(SystemState.SENTINEL, { trigger: 'go-shorthand', timestamp: Date.now(), auto: false }),
}));

if (import.meta.env.DEV) {
  (window as any).__phantom.system = useSystemStore;
}
