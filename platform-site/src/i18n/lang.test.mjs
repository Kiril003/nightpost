import { test } from 'node:test';
import assert from 'node:assert/strict';
import { STORAGE_KEY, initialLang } from './lang.ts';

function browser(languages, stored) {
  const box = new Map(stored ? [[STORAGE_KEY, stored]] : []);
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { language: languages[0], languages },
  });
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: (k) => box.get(k) ?? null, setItem: (k, v) => box.set(k, v) },
  });
}

test('a Ukrainian or Russian browser with no stored choice opens in English', () => {
  for (const langs of [['uk-UA', 'uk'], ['ru-RU'], ['en-US', 'uk-UA']]) {
    browser(langs, null);
    assert.equal(initialLang(), 'en', langs.join(','));
  }
});

test('a stored choice wins either way', () => {
  browser(['en-US'], 'uk');
  assert.equal(initialLang(), 'uk');
  browser(['uk-UA'], 'en');
  assert.equal(initialLang(), 'en');
});

test('blocked storage falls back to English', () => {
  browser(['uk-UA'], null);
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get() {
      throw new Error('SecurityError');
    },
  });
  assert.equal(initialLang(), 'en');
});
