/**
 * 29.10 R-GAP #5: на телефоні веб-екрани «Мої люди» і «Мої сторінки» відкривались
 * лише з настільного ⋮ (`Header.tsx`, `!__PHONE_BUILD__`). Рідні Налаштування
 * відчиняють їх через `__phantomOpenPlace(key)`.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { HelpDoor } from '../HelpDoor';

vi.mock('../../messenger/PeopleScreen', () => ({
  PeopleScreen: ({ onClose }: { onClose: () => void }) => (
    <button type="button" data-testid="people-screen" onClick={onClose} />
  ),
}));
vi.mock('../../builder/BuilderPages', () => ({
  BuilderHub: ({ onClose }: { onClose: () => void }) => (
    <button type="button" data-testid="builder-hub" onClick={onClose} />
  ),
}));

type W = Window & { __phantomOpenPlace?: (key: string) => boolean };

afterEach(() => cleanup());

describe('рідні Налаштування відкривають веб-місця телефона', () => {
  it('«people» відкриває Мої люди', async () => {
    render(<HelpDoor />);
    let opened = false;
    act(() => {
      opened = (window as W).__phantomOpenPlace!('people');
    });
    expect(opened).toBe(true);
    expect(await screen.findByTestId('people-screen')).toBeTruthy();
  });

  it('«builder» відкриває Мої сторінки, «help» — Допомогу', async () => {
    render(<HelpDoor />);
    act(() => {
      (window as W).__phantomOpenPlace!('builder');
    });
    expect(await screen.findByTestId('builder-hub')).toBeTruthy();
    act(() => {
      (window as W).__phantomOpenPlace!('help');
    });
    expect(await screen.findByTestId('help-centre-sheet')).toBeTruthy();
    expect(screen.queryByTestId('builder-hub')).toBeNull();
  });

  it('незнайомий ключ — «ні», і двері зникають разом із хостом', () => {
    const { unmount } = render(<HelpDoor />);
    expect((window as W).__phantomOpenPlace!('wallet')).toBe(false);
    unmount();
    expect((window as W).__phantomOpenPlace).toBeUndefined();
  });
});
