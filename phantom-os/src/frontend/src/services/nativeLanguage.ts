/**
 * Мова месенджера на телефоні йде за рідним застосунком.
 *
 * Людина обирає мову один раз — на першому екрані Nightpost або в Профілі ›
 * Мова. До 09.10 веб про це не знав: каталог (`i18n/`) читав `ui_language`,
 * а його на телефоні не ставив ніхто, тож після English розмови лишались
 * українською.
 *
 * Шлях той самий, що в теми (`services/theme.ts`): на завантаженні веб питає
 * міст (`PhantomBridge.getLanguage()`), а на зміну рідний бік штовхає
 * `window.__phantomLanguage(tag)`. Вибір живе на рідному боці, тож тут — лише
 * дзеркало: стор (`applyRemote`, без PUT на вузол), `<html lang>` і кеш у
 * localStorage для першого кадру. Без моста (ПК) нічого не міняється.
 */
import { matchLocale, type Locale } from '../i18n/locales';
import {
  LANGUAGE_SETTING_KEY,
  LANGUAGE_STORAGE_KEY,
  applyLanguageToDom,
  useSettingsStore,
} from '../stores/settingsStore';

type LanguageBridge = { getLanguage?: () => unknown };

/** Мова рідного застосунку; старий APK, ПК чи мовчазний міст — null. */
export function readNativeLanguage(): Locale | null {
  if (typeof window === 'undefined') return null;
  const bridge = (window as unknown as { PhantomBridge?: LanguageBridge }).PhantomBridge;
  if (!bridge || typeof bridge.getLanguage !== 'function') return null;
  try {
    return matchLocale(bridge.getLanguage());
  } catch {
    return null;
  }
}

/** Ставить мову, яку сказав рідний бік. Невідоме слово — нічого не міняє. */
export function applyNativeLanguage(raw: unknown): boolean {
  const tag = matchLocale(raw);
  if (!tag) return false;
  applyLanguageToDom(tag);
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(LANGUAGE_STORAGE_KEY, tag);
  } catch {
    /* приватний режим — стор і DOM усе одно оновлені */
  }
  const store = useSettingsStore.getState();
  if (store.values[LANGUAGE_SETTING_KEY] !== tag) store.applyRemote(LANGUAGE_SETTING_KEY, tag);
  return true;
}

/** Бере мову з моста зараз і тримає її свіжою: поштовх рідного боку й повернення на екран. */
export function startNativeLanguageWatch(): () => void {
  const sync = () => {
    const tag = readNativeLanguage();
    if (tag) applyNativeLanguage(tag);
  };
  sync();
  if (typeof window === 'undefined') return () => undefined;

  const w = window as unknown as Record<string, unknown>;
  const previous = w.__phantomLanguage;
  w.__phantomLanguage = (tag: unknown) => {
    applyNativeLanguage(tag);
  };
  const onVisible = () => {
    if (document.visibilityState === 'visible') sync();
  };
  document.addEventListener('visibilitychange', onVisible);

  return () => {
    document.removeEventListener('visibilitychange', onVisible);
    if (previous === undefined) delete w.__phantomLanguage;
    else w.__phantomLanguage = previous;
  };
}

type LanguageSetter = { getLanguage?: () => unknown; setLanguage?: (tag: string) => unknown };

const languageBridge = (): LanguageSetter | undefined =>
  typeof window === 'undefined' ? undefined : (window as unknown as { PhantomBridge?: LanguageSetter }).PhantomBridge;

/**
 * Чи може екран міняти мову. У телефоні вибір живе на рідному боці, а веб лише
 * дзеркалить його (див. вище): без `setLanguage` у мості перемикач у вебі
 * відкотився б при наступному поверненні на екран. Браузер і ПК моста не мають —
 * там мову веде сам веб.
 */
export function languageSwitchable(): boolean {
  const bridge = languageBridge();
  if (!bridge || typeof bridge.getLanguage !== 'function') return true;
  return typeof bridge.setLanguage === 'function';
}

/** Ставить мову: через міст, якщо вибір там, інакше в сторі вебу. */
export function switchLanguage(locale: Locale): void {
  const bridge = languageBridge();
  if (bridge && typeof bridge.getLanguage === 'function') {
    if (typeof bridge.setLanguage === 'function') {
      try {
        bridge.setLanguage(locale);
      } catch {
        /* рідний бік відмовив — лишається те, що він віддає */
      }
    }
    return;
  }
  void useSettingsStore.getState().setLanguage(locale);
}
