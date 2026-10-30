/**
 * Телефон, 29.10: «Пошук у розмові» з меню відкривав рядок, але фокус забирала
 * кнопка меню — клавіатура не зʼявлялась, а в рядку стояла підказка для клавіш
 * компʼютера («Enter - далі, Esc - закрити»).
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { ChatSearchBar } from '../FeedBanners';

afterEach(cleanup);

/** Меню, яке після закриття повертає фокус своїй кнопці — як справжнє. */
const MenuThatRestoresFocus: React.FC = () => {
  const trigger = React.useRef<HTMLButtonElement>(null);
  React.useEffect(() => trigger.current?.focus(), []);
  return (
    <>
      <button ref={trigger} type="button">Ще</button>
      <ChatSearchBar query="" matchCount={0} matchIndex={0} onQuery={vi.fn()} onPrev={vi.fn()} onNext={vi.fn()} onClose={vi.fn()} />
    </>
  );
};

describe('пошук у розмові на телефоні', () => {
  it('рядок забирає фокус і після того, як меню повернуло його своїй кнопці', async () => {
    render(<MenuThatRestoresFocus />);
    const input = screen.getByTestId('chat-search-input');
    await waitFor(() => expect(document.activeElement).toBe(input));
  });

  it('підказка коротка, без клавіш компʼютера', () => {
    render(<ChatSearchBar query="" matchCount={0} matchIndex={0} onQuery={vi.fn()} onPrev={vi.fn()} onNext={vi.fn()} onClose={vi.fn()} />);
    const input = screen.getByTestId('chat-search-input') as HTMLInputElement;
    expect(input.placeholder).toBe('Пошук у розмові');
    expect(input.placeholder).not.toMatch(/Enter|Esc/);
  });
});
