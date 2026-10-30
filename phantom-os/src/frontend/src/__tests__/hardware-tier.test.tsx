/**
 * `applyUISettings` hardware-tier branch (audit U2-ANIM-C2 + U8-PERF):
 * `ui_hardware_tier` з налаштувань стає `<html data-tier>`; сміття чи
 * відсутність — `mid`.
 *
 * Хук `useHardwareTier` пішов з дерева: імпортерів у нього не було жодного.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { applyUISettings } from '../services/settingsBootstrap';

beforeEach(() => {
  document.documentElement.removeAttribute('data-tier');
});

describe('applyUISettings hardware-tier branch', () => {
  it('writes data-tier="low" when ui_hardware_tier="low"', () => {
    applyUISettings({ ui_hardware_tier: 'low' });
    expect(document.documentElement.getAttribute('data-tier')).toBe('low');
  });

  it('writes data-tier="high" when ui_hardware_tier="high"', () => {
    applyUISettings({ ui_hardware_tier: 'high' });
    expect(document.documentElement.getAttribute('data-tier')).toBe('high');
  });

  it('falls back to data-tier="mid" on garbage', () => {
    applyUISettings({ ui_hardware_tier: 'ultra-mega' });
    expect(document.documentElement.getAttribute('data-tier')).toBe('mid');
  });

  it('falls back to data-tier="mid" on undefined', () => {
    applyUISettings({});
    expect(document.documentElement.getAttribute('data-tier')).toBe('mid');
  });
});
