import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  vi.doUnmock('../i18n/messages/parts/l1');
  vi.doUnmock('../i18n/messages/parts/l3');
  vi.resetModules();
});

describe('i18n lane parts wiring', () => {
  it('ключ із частини l1 потрапляє в uk і en, а відсутній en падає на uk', async () => {
    vi.resetModules();
    vi.doMock('../i18n/messages/parts/l1', () => ({
      uk: { 'lane.probe': 'Проба {n}' },
      en: { 'lane.probe': 'Probe {n}' },
    }));
    vi.doMock('../i18n/messages/parts/l3', () => ({
      uk: { 'lane.only.uk': 'Лише українською' },
      en: {},
    }));
    const { translate } = await import('../i18n');
    const k = (s: string) => s as never;
    expect(translate(k('lane.probe'), 'uk', { n: 1 })).toBe('Проба 1');
    expect(translate(k('lane.probe'), 'en', { n: 1 })).toBe('Probe 1');
    expect(translate(k('lane.only.uk'), 'en')).toBe('Лише українською');
  });
});
