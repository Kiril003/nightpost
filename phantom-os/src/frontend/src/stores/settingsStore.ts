import { create } from 'zustand';
import { SettingsCategory, isThemeId, type ThemeId } from '@shared/types';
import { settingsApi } from '../services/api';
import { DEFAULT_LOCALE, isLocale, type Locale } from '../i18n/locales';
import { readToken } from '../services/tokenStore';

/* Стара тема (ui_theme: sunrise-warm / amber-night / cyberdeck-cold).
 *
 * Тем у PHANTOM дві — Ніч і День, — і `data-theme` на <html> пише ЛИШЕ
 * services/theme.ts (за сонцем). До 04.10 `setTheme` ще й сам ставив
 * атрибут: вибір «Cyberdeck» у Налаштуваннях ПК перефарбовував корінь у
 * тему, якої в CSS більше немає, доки годинник теми не повертав Ніч.
 *
 * Тепер `setTheme` лише пам'ятає вибір (localStorage + store + best-effort
 * PUT, який вузол відхиляє 404 — ключ ui_theme знято) і НЕ чіпає DOM.
 * Підписи й експорти лишились, бо їх кличуть SettingsPanel і getActiveTheme
 * у кількох десктопних місцях; самий вибір прибирає власник ThemePicker.
 */
export const THEME_STORAGE_KEY = 'phantom_theme';
export const THEME_SETTING_KEY = 'ui_theme';

// Phase 22 — sticky operator preference for the SettingsPanel header.
// Persisted in localStorage so reload remembers the operator's choice.
export const ADVANCED_TOGGLE_KEY = 'phantom_settings_advanced';

/* i18n — the interface language rides the same rails as the legacy theme
 * choice: one canonical settings key, a localStorage mirror for pre-auth
 * paint, an optimistic store update, and a best-effort backend PUT. Unlike
 * the theme it DOES write the DOM — `<html lang="…">`, which is what screen
 * readers and hyphenation key off. */
export const LANGUAGE_STORAGE_KEY = 'phantom_language';
export const LANGUAGE_SETTING_KEY = 'ui_language';

export function applyLanguageToDom(locale: Locale): void {
  if (typeof document === 'undefined') return;
  document.documentElement.setAttribute('lang', locale);
}

interface SettingsStoreState {
  categories: SettingsCategory[];
  values: Record<string, unknown>;
  dirty: Set<string>;
  loaded: boolean;

  setCategories: (categories: SettingsCategory[]) => void;
  setValue: (key: string, value: unknown) => void;
  applyRemote: (key: string, value: unknown) => void;
  markClean: (key: string) => void;
  bulkSet: (values: Record<string, unknown>) => void;

  /** Read the active theme id from the in-memory settings, falling
   * back to the localStorage cache or the conservative default. */
  getActiveTheme: () => ThemeId;
  /** Remember the legacy theme choice: localStorage + store, backend best-effort. Never touches the DOM. */
  setTheme: (id: ThemeId) => Promise<void>;

  /** Read the active UI locale, falling back to the localStorage
   * cache or the source language. */
  getActiveLanguage: () => Locale;
  /** Flip language: DOM + localStorage immediately, backend best-effort. */
  setLanguage: (locale: Locale) => Promise<void>;

  /**
   * Phase 22 — IA controls for the settings header.
   *
   * `showAdvanced` toggles the "Показати розширені" switch — when
   * false, settings whose tier is 'advanced' are filtered out of the
   * rendered category list. Persisted in localStorage so the operator
   * keeps the same view across reloads.
   *
   * `query` is the live search-filter string for the sticky header
   * search box. Not persisted (transient per session).
   */
  showAdvanced: boolean;
  setShowAdvanced: (v: boolean) => void;
  query: string;
  setQuery: (v: string) => void;
}

function loadShowAdvanced(): boolean {
  if (typeof localStorage === 'undefined') return false;
  try {
    return localStorage.getItem(ADVANCED_TOGGLE_KEY) === '1';
  } catch {
    return false;
  }
}

export const useSettingsStore = create<SettingsStoreState>((set, get) => ({
  categories: [],
  values: {},
  dirty: new Set(),
  loaded: false,
  showAdvanced: loadShowAdvanced(),
  query: '',

  setCategories: (categories) => {
    const { dirty, values: prev } = get();
    const values: Record<string, unknown> = {};
    for (const cat of categories) {
      for (const def of cat.settings) {
        values[def.key] = dirty.has(def.key) && def.key in prev ? prev[def.key] : def.value;
      }
    }
    set({ categories, values, loaded: true });
  },

  setValue: (key, value) =>
    set((s) => {
      const next = new Set(s.dirty);
      next.add(key);
      return { values: { ...s.values, [key]: value }, dirty: next };
    }),

  applyRemote: (key, value) =>
    set((s) => ({ values: { ...s.values, [key]: value } })),

  markClean: (key) =>
    set((s) => {
      const next = new Set(s.dirty);
      next.delete(key);
      return { dirty: next };
    }),

  bulkSet: (values) =>
    set((s) => ({ values: { ...s.values, ...values } })),

  getActiveTheme: () => {
    const fromValues = get().values[THEME_SETTING_KEY];
    if (isThemeId(fromValues)) return fromValues;
    if (typeof localStorage !== 'undefined') {
      const stored = localStorage.getItem(THEME_STORAGE_KEY);
      if (isThemeId(stored)) return stored;
    }
    return 'sunrise-warm';
  },

  setTheme: async (id) => {
    if (!isThemeId(id)) return;
    // 1. localStorage cache — survives reload, no auth required.
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(THEME_STORAGE_KEY, id);
      }
    } catch {
      /* private mode / storage full — skip cache */
    }
    // 2. Optimistic store update + clear dirty marker so SettingsPanel
    //    doesn't show a "save needed" pill after a successful flip.
    set((s) => {
      const dirty = new Set(s.dirty);
      dirty.delete(THEME_SETTING_KEY);
      return {
        values: { ...s.values, [THEME_SETTING_KEY]: id },
        dirty,
      };
    });
    // 3. Backend persist — best-effort. The `phantom_token` gate in
    //    settingsApi.set keeps this a no-op pre-auth so the picker
    //    still works on the LoginScreen if we ever surface it there.
    try {
      if (
        typeof sessionStorage === 'undefined' ||
        readToken()
      ) {
        await settingsApi.set(THEME_SETTING_KEY, id);
      }
    } catch {
      /* network blip — cache is durable; sync happens next save. */
    }
  },

  getActiveLanguage: () => {
    const fromValues = get().values[LANGUAGE_SETTING_KEY];
    if (isLocale(fromValues)) return fromValues;
    if (typeof localStorage !== 'undefined') {
      const stored = localStorage.getItem(LANGUAGE_STORAGE_KEY);
      if (isLocale(stored)) return stored;
    }
    return DEFAULT_LOCALE;
  },

  setLanguage: async (locale) => {
    if (!isLocale(locale)) return;
    applyLanguageToDom(locale);
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(LANGUAGE_STORAGE_KEY, locale);
      }
    } catch {
      /* private mode / storage full — DOM still updated, skip cache */
    }
    set((s) => {
      const dirty = new Set(s.dirty);
      dirty.delete(LANGUAGE_SETTING_KEY);
      return {
        values: { ...s.values, [LANGUAGE_SETTING_KEY]: locale },
        dirty,
      };
    });
    try {
      if (
        typeof sessionStorage === 'undefined' ||
        readToken()
      ) {
        await settingsApi.set(LANGUAGE_SETTING_KEY, locale);
      }
    } catch {
      /* network blip — cache is durable; sync happens next save. */
    }
  },

  setShowAdvanced: (v) => {
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem(ADVANCED_TOGGLE_KEY, v ? '1' : '0');
      } catch {
        /* private mode / quota — best-effort */
      }
    }
    set({ showAdvanced: v });
  },

  setQuery: (v) => set({ query: v }),
}));
