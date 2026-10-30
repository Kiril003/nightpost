import { fireEvent, screen, within } from '@testing-library/react';

/** Відкрити «Коли» з рядка (або з уже відкритого швидкого списку) й обрати день і час у своїх вікнах. */
export function pickWhen(opener: HTMLElement | null, date: string, hh: string, mm: string): void {
  if (opener) fireEvent.click(opener);
  fireEvent.click(screen.getByTestId('when-pick'));
  fireEvent.click(document.querySelector(`[data-day="${date}"]`)!);
  fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: hh }));
  fireEvent.click(within(screen.getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: mm }));
  fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
}

/** «Далі» (або «Далі · N») чернетки зустрічі. */
export function nextStep(): void {
  fireEvent.click(screen.getByRole('button', { name: /^Далі/ }));
}

/** Назва поточного кроку: «Крок N з M · …». */
export const stepNow = (): string => screen.getByTestId('draft-steps').querySelector('[aria-current="step"]')!.textContent ?? '';
