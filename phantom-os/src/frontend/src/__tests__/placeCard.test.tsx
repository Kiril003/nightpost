/**
 * Картка місця (LocationSheetModal) — 03.10, мовою v1.2 і без театру.
 *
 * До цього на картці стояли чотири дії, з яких жодна, крім «Маршруту», нічого
 * не робила: «Через» казав «додано до маршруту» (не додавав), «Зберегти»
 * перемикав місцевий прапорець, «Надіслати картку в бесіду» і «Збір тут»
 * лише закривали аркуш — MessengerRoot не передає їм обробників. «Маршрут»
 * при цьому щоразу казав «побудовано», хоч нічого не будував.
 *
 * Тепер — одна головна дія й посилання на мапу; порожні поля (DeviceSheet
 * відкриває картку з порожніми категорією й адресою) не малюють порожніх
 * рядків. Поверніть будь-яку з чотирьох дій, «НА КАРТІ» капсом чи рядок
 * «Адреса» без адреси — тест червоніє.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { LocationSheetModal } from '../components/messenger/LocationSheetModal';
import { useUIStore } from '../stores/uiStore';
import type { LocationData } from '../types/messenger';

const place = (over: Partial<LocationData> = {}): LocationData => ({
  name: 'Поштамт',
  category: 'Пошта',
  address: 'Хрещатик, 22',
  distance: '',
  walkingTime: '',
  coords: { lat: 50.4501, lng: 30.5234 },
  ...over,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('картка місця', () => {
  it('одна дія — «Маршрут»; жодної дії, що нічого не робить', () => {
    render(<LocationSheetModal location={place()} isOpen onClose={() => {}} />);
    expect(screen.getByTestId('place-sheet')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Маршрут/ })).toBeTruthy();
    const body = document.body.textContent ?? '';
    for (const fake of ['Через', 'Зберегти', 'Збережено', 'Збір тут', 'Надіслати картку']) expect(body, fake).not.toContain(fake);
  });

  it('підписи — звичайним регістром; амбра лише на «Маршруті»', () => {
    render(<LocationSheetModal location={place()} isOpen onClose={() => {}} />);
    const body = document.body.textContent ?? '';
    expect(body).not.toContain('НА КАРТІ');
    expect(screen.getByText('На карті')).toBeTruthy();
    const sheet = screen.getByTestId('place-sheet');
    const amber = [...sheet.querySelectorAll<HTMLElement>('[class*="accent"]')].filter(
      (el) => !el.closest('[data-place-route]') && !el.closest('a'),
    );
    expect(amber.map((el) => el.className)).toEqual([]);
    expect(sheet.innerHTML).not.toMatch(/text-xs|text-sm|text-xl|text-2xl|uppercase|tracking-/);
  });

  it('порожня адреса й категорія — ні рядка «Адреса», ні посилання, ні порожнього підпису', () => {
    render(<LocationSheetModal location={place({ category: '', address: '' })} isOpen onClose={() => {}} />);
    expect(screen.getByText('Поштамт')).toBeTruthy();
    expect(screen.queryByText('Адреса')).toBeNull();
    expect(screen.queryByText('На карті')).toBeNull();
    expect(screen.getByTestId('place-sheet').querySelector('a')).toBeNull();
    expect(screen.queryByTestId('place-category')).toBeNull();
  });

  it('«Маршрут» відкриває мапу мостом і не каже «побудовано»', () => {
    const openMap = vi.fn(() => true);
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { openMap };
    const toast = vi.spyOn(useUIStore.getState(), 'toast');
    render(<LocationSheetModal location={place()} isOpen onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /Маршрут/ }));
    expect(openMap).toHaveBeenCalledWith(50.4501, 30.5234, 'Поштамт');
    expect(toast).not.toHaveBeenCalled();
  });
});
