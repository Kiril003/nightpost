/**
 * Тема PHANTOM — полярність Ніч або День; за сонцем між ними шість точок
 * (варіант B, власник 28.10, `services/themeRamp.ts`).
 *
 * Звідки сонце:
 * - телефон — рідний бік (`PhantomBridge.getThemePhase()` / `getTheme()`), він
 *   знає координати. Сутінки й ніч — темні, світанок, ранок, полудень і золота
 *   година — світлі;
 * - ПК (моста немає) — `frameNow()` з `services/themeRamp.ts`, щохвилини.
 *
 * Ручний вибір («Завжди ніч» / «Завжди день») перекриває сонце й живе на
 * цьому пристрої (`phantom.themeMode`). «Як сонце» — типово.
 *
 * Корінь несе `data-theme="night|day"` (за ним живуть токени `--ph-*`) і, для
 * старих споживачів (мапа), той самий `data-phase`.
 */
import { applyRamp, frameNow } from './themeRamp';

export type PhantomTheme = 'day' | 'night';
/** Що обрала людина: сонце чи стала тема. */
export type ThemeMode = 'auto' | PhantomTheme;

/** Людина змінила вибір теми в налаштуваннях — екрани, що показують його, перечитують. */
export const THEME_MODE_EVENT = 'phantom-theme-mode';

const MODE_KEY = 'phantom.themeMode';

type ModeBridge = { getThemeMode?: () => unknown; setThemeMode?: (mode: string) => unknown };

/** Міст із вибором теми (телефон, збірка 16+); старий APK чи ПК — null. */
const modeBridge = (): ModeBridge | null => {
  if (typeof window === 'undefined') return null;
  const b = (window as unknown as { PhantomBridge?: ModeBridge }).PhantomBridge;
  return b && typeof b.getThemeMode === 'function' && typeof b.setThemeMode === 'function' ? b : null;
};

/**
 * Вибір людини. На телефоні він живе на РІДНОМУ боці (той самий, що в
 * Профілі): до збірки 16 «Я › Тема» писала лише в localStorage, і обраний тут
 * «День» лишав рідну оболонку навколо месенджера (смугу вкладок, ескіз старту,
 * заставку) у Ночі. localStorage — лише там, де мосту з вибором немає.
 */
export const readThemeMode = (): ThemeMode => {
  const bridge = modeBridge();
  if (bridge) {
    try {
      const v = bridge.getThemeMode?.();
      if (v === 'night' || v === 'day' || v === 'auto') return v;
    } catch {
      /* міст мовчить — падаємо на сховище */
    }
  }
  try {
    const v = typeof localStorage !== 'undefined' ? localStorage.getItem(MODE_KEY) : null;
    return v === 'night' || v === 'day' ? v : 'auto';
  } catch {
    return 'auto';
  }
};

/** Шість фаз рідного боку → полярність. Невідоме слово — null. */
export const themeOfPhase = (phase: unknown): PhantomTheme | null => {
  if (phase === 'day' || phase === 'dawn' || phase === 'morning' || phase === 'golden') return 'day';
  if (phase === 'night' || phase === 'dusk') return 'night';
  return null;
};

/** Тема за сонцем: міст, якщо він є; інакше — сонце над цим компʼютером. */
export const sunNow = (date: Date = new Date()): PhantomTheme => {
  if (typeof window === 'undefined') return 'night';
  const bridge = (window as unknown as {
    PhantomBridge?: { getTheme?: () => unknown; getThemePhase?: () => unknown };
  }).PhantomBridge;
  if (!bridge) return frameNow(date).theme;
  try {
    if (typeof bridge.getThemePhase === 'function') {
      const t = themeOfPhase(bridge.getThemePhase());
      if (t) return t;
    }
  } catch {
    /* падаємо на getTheme нижче */
  }
  try {
    return typeof bridge.getTheme === 'function' && bridge.getTheme() === 'day' ? 'day' : 'night';
  } catch {
    return 'night';
  }
};

/** Тема, яку треба показати зараз: ручний вибір або сонце. */
export const wantedTheme = (date: Date = new Date()): PhantomTheme => {
  const mode = readThemeMode();
  return mode === 'auto' ? sunNow(date) : mode;
};

/** Тривалість перетікання кольорів; синхронно з form.css. */
export const PHASE_SHIFT_MS = 600;
let shiftTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * Ставить тему на корінь. Перша постановка — миттєва (перший кадр уже в
 * правильних кольорах); кожна ЗМІНА — через клас `phase-shift`, під яким
 * form.css на 600 мс вмикає transition кольорів.
 */
export const applyTheme = (theme: PhantomTheme): void => {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  const before = root.dataset.theme;
  if (before === theme && root.dataset.phase === theme) return;
  const animate = (before === 'day' || before === 'night') && before !== theme;
  if (animate) {
    root.classList.add('phase-shift');
    void root.offsetWidth; // transition мусить стояти ДО зміни значень
    if (shiftTimer !== undefined) clearTimeout(shiftTimer);
    shiftTimer = setTimeout(() => {
      root.classList.remove('phase-shift');
      shiftTimer = undefined;
    }, PHASE_SHIFT_MS + 50);
  }
  root.dataset.theme = theme;
  root.dataset.phase = theme;
};

export const currentTheme = (): PhantomTheme =>
  typeof document !== 'undefined' && document.documentElement.dataset.theme === 'day' ? 'day' : 'night';

/** Змінити вибір людини й одразу показати його. */
export const setThemeMode = (mode: ThemeMode): void => {
  const bridge = modeBridge();
  if (bridge) {
    // Рідний бік перефарбує всю оболонку й штовхне сюди нову тему
    // (`__phantomTheme`); тут — лише миттєвий відгук на дотик.
    try {
      bridge.setThemeMode?.(mode);
    } catch {
      /* не прийняв — показуємо вибір хоча б тут */
    }
    applyTheme(mode === 'auto' ? sunNow() : mode);
    applyRamp(new Date(), mode === 'auto');
    return;
  }
  try {
    if (mode === 'auto') localStorage.removeItem(MODE_KEY);
    else localStorage.setItem(MODE_KEY, mode);
  } catch {
    /* без сховища вибір живе до перезапуску */
  }
  applyTheme(wantedTheme());
  applyRamp(new Date(), mode === 'auto');
};

/** Тема до першого кадру: кличеться з входу ще до `createRoot().render`. */
export function applyThemeNow(): void {
  applyTheme(wantedTheme());
  applyRamp(new Date(), readThemeMode() === 'auto');
}
/** Стара назва входу — лишена для сумісності. */
export const applyBridgePhaseNow = applyThemeNow;

/** Ставить тему зараз і тримає її свіжою: фокус, видимість, поштовх моста, щохвилини. */
export function startThemeWatch(): () => void {
  // Рампа токенів за сонцем щохвилини — лише коли людина лишила «Як сонце».
  const sync = () => {
    applyTheme(wantedTheme());
    applyRamp(new Date(), readThemeMode() === 'auto');
  };
  sync();
  if (typeof window === 'undefined') return () => undefined;

  const onVisible = () => {
    if (document.visibilityState === 'visible') sync();
  };
  window.addEventListener('focus', sync);
  document.addEventListener('visibilitychange', onVisible);
  // Захід сонця настає й тоді, коли людина просто дивиться в екран.
  const tick = setInterval(sync, 60_000);

  const w = window as unknown as Record<string, unknown>;
  const previous = w.__phantomTheme;
  // Поштовх рідного боку: (полярність, фаза[, кадр]) або (полярність). Кадр веб читає з `getSunLook`. Ручний вибір сильніший.
  w.__phantomTheme = (theme: unknown, phase?: unknown) => {
    if (readThemeMode() !== 'auto') return sync();
    applyTheme(themeOfPhase(phase) ?? (theme === 'day' ? 'day' : 'night'));
    applyRamp(new Date(), true);
  };

  return () => {
    window.removeEventListener('focus', sync);
    document.removeEventListener('visibilitychange', onVisible);
    clearInterval(tick);
    if (previous === undefined) delete w.__phantomTheme;
    else w.__phantomTheme = previous;
  };
}
