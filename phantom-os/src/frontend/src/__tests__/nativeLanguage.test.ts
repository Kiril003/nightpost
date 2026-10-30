/**
 * Мова месенджера на телефоні — та сама, що обрала людина в рідному застосунку
 * (перший екран або Профіль › Мова). До 09.10 власник обирав English, а розмови
 * лишались українською: веб брав мову лише зі свого `ui_language`, і ніхто
 * його не ставив.
 *
 * Рідний бік каже мову двома шляхами, як і тему: `PhantomBridge.getLanguage()`
 * на завантаженні й `window.__phantomLanguage(tag)` на зміну.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { startNativeLanguageWatch } from '../services/nativeLanguage';
import { useTranslation } from '../i18n/useTranslation';
import { cs } from '../i18n/messages/cs';
import {
  LANGUAGE_SETTING_KEY,
  LANGUAGE_STORAGE_KEY,
  useSettingsStore,
} from '../stores/settingsStore';

type Bridge = { getLanguage?: () => unknown };
const setBridge = (bridge?: Bridge) => {
  const w = window as unknown as { PhantomBridge?: Bridge };
  if (bridge) w.PhantomBridge = bridge;
  else delete w.PhantomBridge;
};
const push = (tag: unknown) =>
  (window as unknown as { __phantomLanguage: (t: unknown) => void }).__phantomLanguage(tag);

let stop: (() => void) | null = null;

afterEach(() => {
  stop?.();
  stop = null;
  setBridge(undefined);
  localStorage.clear();
  useSettingsStore.setState({ values: {} });
  document.documentElement.removeAttribute('lang');
});

describe('мова з рідного боку', () => {
  it('на завантаженні веб бере мову з моста: English — і рядки англійські', () => {
    setBridge({ getLanguage: () => 'en' });
    stop = startNativeLanguageWatch();
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBe('en');
    expect(document.documentElement.getAttribute('lang')).toBe('en');
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('en');
    const { result } = renderHook(() => useTranslation());
    expect(result.current.locale).toBe('en');
    expect(result.current.t('chat.sessions.title')).toBe('Sessions');
  });

  it('Čeština з моста: месенджер читає чеські рядки, а поштовх повертає українську', () => {
    setBridge({ getLanguage: () => 'cs' });
    stop = startNativeLanguageWatch();
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBe('cs');
    expect(document.documentElement.getAttribute('lang')).toBe('cs');
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('cs');
    const { result } = renderHook(() => useTranslation());
    expect(result.current.locale).toBe('cs');
    expect(result.current.t('chat.sessions.title')).toBe(cs['chat.sessions.title']);
    expect(result.current.t('chat.sessions.title')).not.toBe('Sessions');
    act(() => push('uk'));
    expect(result.current.locale).toBe('uk');
    act(() => push('cs'));
    expect(result.current.t('chat.sessions.title')).toBe(cs['chat.sessions.title']);
  });

  it('людина змінила мову в Профілі — поштовх перемикає вже відкритий месенджер', () => {
    setBridge({ getLanguage: () => 'en' });
    stop = startNativeLanguageWatch();
    const { result } = renderHook(() => useTranslation());
    expect(result.current.locale).toBe('en');
    act(() => push('uk'));
    expect(result.current.locale).toBe('uk');
    expect(result.current.t('chat.sessions.title')).toBe('Сесії');
    expect(document.documentElement.getAttribute('lang')).toBe('uk');
  });

  it('невідома мова й мовчазний міст нічого не ламають', () => {
    useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
    setBridge({
      getLanguage: () => {
        throw new Error('bridge gone');
      },
    });
    stop = startNativeLanguageWatch();
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBe('uk');
    push('ru');
    push(undefined);
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBe('uk');
  });

  it('без моста (ПК) мова лишається своєю, а поштовх усе одно приймається', () => {
    stop = startNativeLanguageWatch();
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBeUndefined();
    push('en');
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBe('en');
  });

  it('вхід застосунку вмикає це до першого кадру', () => {
    const main = readFileSync(resolve(__dirname, '../main.tsx'), 'utf8');
    const call = main.indexOf('startNativeLanguageWatch()');
    expect(call).toBeGreaterThan(-1);
    expect(call).toBeLessThan(main.indexOf('ReactDOM.createRoot'));
  });
});
