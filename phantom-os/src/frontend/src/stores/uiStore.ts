import { create } from 'zustand';

// ─── Спільний стан інтерфейсу: тости, згортання хрому, фокус ─────────────
//
// Тут жив і віконний менеджер скла ПК (`windows`, ключ localStorage
// `phantom.ui.windows.v3`): Overlays/FloatingWindow монтувались у App для
// обох збірок і їхали в APK, хоча телефон їх не показував ніколи. Разом з
// ними пішли й прапорці панелей, які відкривали видалені поверхні
// (FloatingToolbar, AgentVault, Studio, місії, Intelligence Hub). Сторож —
// `overlays-floating-windows` у bundlePatterns.ts.

export type FocusedAgent =
  | 'foreground'
  | 'background'
  | 'standing_orders'
  | 'proactive'
  | 'council'
  | 'horizons'
  | 'org_chart'
  | 'team_chat';

export type ToastKind = 'error' | 'warn' | 'info' | 'success';

export interface ToastAction {
  label: string;
  run: () => void;
}

export interface Toast {
  id: string;
  kind: ToastKind;
  message: string;
  ts: number;
  action?: ToastAction;
}

export type DeskPane = 'day' | 'map' | 'wallet' | 'info' | null;

export type ChromeKey = 'statusBar' | 'roster' | 'hud' | 'toolbar';

export interface ChromeState {
  /** true = compact (top-mounted) or collapsed (bottom-mounted). */
  statusBar: boolean;
  roster: boolean;
  hud: boolean;
  toolbar: boolean;
}

// Bumped to v2 so first-run after the discoverability fix lands the new
// default (HUD expanded so Stop/Side-Channel/Vault are visible without
// hunting). Existing v1 localStorage rows are ignored.
const CHROME_KEY = 'phantom.chrome.v3';
const CHROME_DEFAULT: ChromeState = {
  statusBar: false,
  roster: true,
  // Розгорнуті: тулбар — це головна навігація продукту, а HUD тримає
  // Stop і доступ до сховища. Згорнуті за замовчуванням, вони читались
  // як «кнопки зникли».
  hud: false,
  toolbar: false,
};

function loadChromePersisted(): ChromeState {
  if (typeof localStorage === 'undefined') return CHROME_DEFAULT;
  try {
    const raw = localStorage.getItem(CHROME_KEY);
    if (!raw) return CHROME_DEFAULT;
    const parsed = JSON.parse(raw) as Partial<ChromeState>;
    return { ...CHROME_DEFAULT, ...parsed };
  } catch {
    return CHROME_DEFAULT;
  }
}

function persistChrome(state: ChromeState): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.setItem(CHROME_KEY, JSON.stringify(state));
    localStorage.setItem('phantom.chrome.v1', JSON.stringify(state));
  } catch {
    /* quota — ignore */
  }
}

interface UIStoreState {
  deskPane: DeskPane;
  setDeskPane: (pane: DeskPane) => void;

  /**
   * Вихід із сесії: нічого перехідного від попереднього входу не лишається —
   * ні тостів, ні відкладеного ввімкнення мікрофона. Кличе DeskShell при
   * «Вийти».
   */
  closeAll: () => void;

  /**
   * Phase 9.5 — transient flag: a voice entry point sets this when routing
   * to DIALOGUE, and ChatWindow consumes it on mount to auto-fire
   * the mic toggle. Cleared immediately after consumption so subsequent
   * Dialogue visits don't auto-record.
   */
  pendingVoiceActivation: boolean;
  setPendingVoiceActivation: (v: boolean) => void;

  // ─── OperatorLayout v3 slices ───────────────────────────────────────────
  focusedAgent: FocusedAgent;
  setFocusedAgent: (a: FocusedAgent) => void;

  toasts: Toast[];
  toast: (t: Omit<Toast, 'id' | 'ts'>) => string;
  dismissToast: (id: string) => void;

  chrome: ChromeState;
  setChromeCollapsed: (key: ChromeKey, collapsed: boolean) => void;
  toggleChrome: (key: ChromeKey) => void;
}

export const useUIStore = create<UIStoreState>((set) => ({
  deskPane: null,
  setDeskPane: (pane) => set({ deskPane: pane }),

  closeAll: () => set({ toasts: [], pendingVoiceActivation: false, deskPane: null }),

  pendingVoiceActivation: false,
  setPendingVoiceActivation: (v) => set({ pendingVoiceActivation: v }),

  // ─── OperatorLayout v3 slices ───────────────────────────────────────────
  focusedAgent: 'foreground',
  setFocusedAgent: (a) => set({ focusedAgent: a }),

  toasts: [],
  toast: (t) => {
    const id = `t-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    set((s) => ({ toasts: [...s.toasts, { ...t, id, ts: Date.now() }] }));
    return id;
  },
  dismissToast: (id) =>
    set((s) => ({ toasts: s.toasts.filter((x) => x.id !== id) })),

  chrome: loadChromePersisted(),
  setChromeCollapsed: (key, collapsed) =>
    set((s) => {
      const next = { ...s.chrome, [key]: collapsed };
      persistChrome(next);
      return { chrome: next };
    }),
  toggleChrome: (key) =>
    set((s) => {
      const next = { ...s.chrome, [key]: !s.chrome[key] };
      persistChrome(next);
      return { chrome: next };
    }),
}));
