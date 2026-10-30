import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ErrorBoundary } from '../components/core/ErrorBoundary';

/**
 * Два старі екрани, які міст токенів сам не дістає: вони брали барву не
 * зі змінної, а з власного числа чи з імені, якого немає.
 *
 * 1. Сторінка помилки читала `var(--ink-base)` — такої змінної в дереві немає
 *    ніде, тож заголовок падав у колір батька, — і малювалась серифним
 *    курсивом на `--surface-void` (#221c10, брунатний). Кадр «до»:
 *    before-web-token-bridge/phone_night_error_boundary_mockcrash.png.
 * 2. Конфеті реакції летіло числами старої палітри (#F4AF25, #10B981, #528A4B,
 *    #FFB340). canvas-confetti розуміє лише hex, тож `var(--ph-accent)` туди не
 *    передаси — барви читаються з кореня в мить пострілу.
 */

const confetti = vi.fn();
vi.mock('canvas-confetti', () => ({ default: (opts: unknown) => confetti(opts) }));
vi.mock('../utils/messengerSound', () => ({ soundFx: { playSend: () => {} } }));

function Boom(): never {
  throw new Error('бах');
}

describe('сторінка помилки — у мові Nightpost', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => vi.restoreAllMocks());

  it('полотно --ph-canvas, заголовок ролі title-2 без курсиву, кнопка raised із межею line', () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    const alert = screen.getByRole('alert');
    expect(alert.getAttribute('style') ?? '').toContain('var(--ph-canvas)');
    expect(alert.outerHTML).not.toContain('--ink-base');
    expect(alert.outerHTML).not.toContain('--surface-void');

    const title = screen.getByText('Щось зламалось.');
    expect(title.className).toContain('text-title-2');
    expect(title.className).toContain('text-ink');
    expect(title.className).not.toMatch(/italic|font-serif/);

    expect(screen.getByText('Спробуйте ще раз — стан збережено.').className).toContain('text-ink-2');

    const button = screen.getByRole('button', { name: 'Спробувати ще раз' });
    for (const cls of ['bg-raised', 'text-ink', 'rounded-control', 'border', 'border-line']) {
      expect(button.className.split(/\s+/)).toContain(cls);
    }
    expect(button.getAttribute('style') ?? '').not.toContain('--glass-card');
  });
});

describe('конфеті реакції — барви токенів у мить пострілу', () => {
  beforeEach(() => {
    confetti.mockReset();
    const root = document.documentElement;
    root.style.setProperty('--ph-accent', '#F2A93B');
    root.style.setProperty('--ph-person-1', '#24855A');
    root.style.setProperty('--ph-person-3', '#3570C4');
    root.style.setProperty('--ph-success', '#5FD08A');
  });
  afterEach(() => {
    for (const v of ['--ph-accent', '--ph-person-1', '--ph-person-3', '--ph-success']) {
      document.documentElement.style.removeProperty(v);
    }
  });

  it('❤️ стріляє акцентом, двома тонами людей і success — не старими числами', async () => {
    const { ReactionPickerModal } = await import('../components/messenger/ReactionPickerModal');
    render(<ReactionPickerModal isOpen onClose={() => {}} onSelectEmoji={() => {}} />);
    // Серце не в типовій вкладці — шукаємо його так, як шукала б людина.
    fireEvent.change(screen.getByPlaceholderText('Пошук емодзі...'), { target: { value: 'серце любов' } });
    fireEvent.click(screen.getAllByRole('button', { name: '❤️' })[0]);
    await waitFor(() => expect(confetti).toHaveBeenCalledTimes(1));
    const { colors } = confetti.mock.calls[0][0] as { colors: string[] };
    expect(colors).toEqual(['#F2A93B', '#24855A', '#3570C4', '#5FD08A']);
  });

  it('пошук емодзі: підказка поля — роль ink-3, не число старої ночі', async () => {
    const { ReactionPickerModal } = await import('../components/messenger/ReactionPickerModal');
    render(<ReactionPickerModal isOpen onClose={() => {}} onSelectEmoji={() => {}} />);
    const input = screen.getByPlaceholderText('Пошук емодзі...');
    expect(input.className).toContain('placeholder:text-ink-3');
    expect(input.className).not.toMatch(/placeholder-\[#/);
  });
});
