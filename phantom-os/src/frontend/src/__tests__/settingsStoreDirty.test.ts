import { afterEach, describe, expect, it } from 'vitest';
import { useSettingsStore } from '../stores/settingsStore';

const cats = (value: unknown) => [{ id: 'ui', settings: [{ key: 'ui_font', value }] }] as never;

afterEach(() => useSettingsStore.setState({ categories: [], values: {}, dirty: new Set(), loaded: false }));

describe('settingsStore.setCategories', () => {
  it('a reload keeps an unsaved edit instead of showing the old value under a dirty mark', () => {
    useSettingsStore.getState().setCategories(cats('small'));
    useSettingsStore.getState().setValue('ui_font', 'large');
    useSettingsStore.getState().setCategories(cats('small'));
    expect(useSettingsStore.getState().values.ui_font).toBe('large');
    expect(useSettingsStore.getState().dirty.has('ui_font')).toBe(true);
  });

  it('a clean key takes the fresh server value', () => {
    useSettingsStore.getState().setCategories(cats('small'));
    useSettingsStore.getState().setCategories(cats('medium'));
    expect(useSettingsStore.getState().values.ui_font).toBe('medium');
  });
});
