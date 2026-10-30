/**
 * T75: рядки змін у групі — мовою читача. Англомовний телефон слав
 * «Open shift · Mon 26 · 09:00–17:00», і український читач бачив англійське.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { stripWorkTag, withTag } from '../services/workEvents';
import { plainText } from '../services/plainText';

const lang = (l: string) => useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));
afterEach(() => lang('uk'));

const shiftFields = { id: 's1', who: '-', day: '2026-10-26', from: '09:00', to: '17:00', role: '' };

describe('рядок зміни мовою читача', () => {
  it('вільна зміна з англійського телефона читається українською', () => {
    lang('uk');
    const wire = withTag('Open shift · Mon 26 · 09:00–17:00', 'shift', shiftFields);
    const line = stripWorkTag(wire);
    expect(line.startsWith('Вільна зміна · ')).toBe(true);
    expect(line).not.toMatch(/Open shift|Mon/);
    expect(line.endsWith('09:00–17:00')).toBe(true);
    expect(plainText(wire)).toBe(line);
  });

  it('імʼя людини лишається, день — мовою читача', () => {
    lang('cs');
    const line = stripWorkTag(withTag('Оля · пн, 26 · 09:00–17:00 · каса', 'shift', { ...shiftFields, who: 'peer', role: 'каса' }));
    expect(line.startsWith('Оля · ')).toBe(true);
    expect(line).not.toContain('пн');
    expect(line.endsWith('09:00–17:00 · каса')).toBe(true);
  });

  it('«Take:» з полями дня — «Взяти:» і день мовою читача', () => {
    lang('uk');
    const line = stripWorkTag(withTag('Take: Mara · Mon 26 · 09:00–17:00', 'take', { id: 's1', day: '2026-10-26', from: '09:00', to: '17:00' }));
    expect(line.startsWith('Взяти: Mara · ')).toBe(true);
    expect(line).not.toMatch(/Take|Mon/);
  });

  it('стара «Take:» без полів — як прийшла', () => {
    expect(stripWorkTag(withTag('Take: Mara · Mon 26 · 09:00–17:00', 'take', { id: 's1' }))).toBe('Take: Mara · Mon 26 · 09:00–17:00');
  });
});
