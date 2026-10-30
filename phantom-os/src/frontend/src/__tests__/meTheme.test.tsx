/**
 * Вибір теми в профілі (рішення власника 01.10): «Як сонце» — типово;
 * «Ніч» і «День» — стала тема, що перекриває сонце. Вибір живе на пристрої.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ThemeChoice } from '../components/messenger/MeSections';
import { readThemeMode } from '../services/theme';

afterEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

describe('тема в профілі', () => {
  it('типово — «Як сонце»; «День» ставить денну палітру й запамʼятовується; «Як сонце» повертає сонце', () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { getThemePhase: () => 'night' };
    render(<ThemeChoice />);
    const pick = (k: string) => document.querySelector(`[data-theme-mode="${k}"]`) as HTMLElement;
    expect(pick('auto').getAttribute('aria-checked')).toBe('true');
    expect(screen.queryByText(/на сході й заході сонця/)).toBeNull();
    fireEvent.click(pick('day'));
    expect(readThemeMode()).toBe('day');
    expect(document.documentElement.dataset.theme).toBe('day');
    fireEvent.click(pick('auto'));
    expect(readThemeMode()).toBe('auto');
    expect(document.documentElement.dataset.theme).toBe('night');
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });
});
