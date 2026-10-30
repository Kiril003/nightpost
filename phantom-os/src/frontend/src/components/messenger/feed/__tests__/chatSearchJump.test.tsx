import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ChatSearchBar } from '../FeedBanners';
import { phoneChatMenuDoors } from '../../PhoneChatMenu';
import type { Chat } from '../../../../types/messenger';

afterEach(cleanup);

describe('search bar jump to date', () => {
  it('opens the month window within the chat bounds and jumps to the tapped day', () => {
    const onJumpDate = vi.fn();
    render(
      <ChatSearchBar query="" matchCount={0} matchIndex={0} onQuery={vi.fn()} onPrev={vi.fn()} onNext={vi.fn()} onClose={vi.fn()}
        onJumpDate={onJumpDate} dateBounds={{ min: '2026-10-01', max: '2026-10-09' }} />,
    );
    expect(document.querySelector('input[type="date"]')).toBeNull();
    fireEvent.click(screen.getByTestId('jump-date'));
    const sheet = screen.getByTestId('jump-date-sheet');
    expect((sheet.querySelector('[data-day="2026-10-10"]') as HTMLButtonElement).disabled).toBe(true);
    expect((sheet.querySelector('[data-day="2026-10-01"]') as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole('button', { name: 'Попередній місяць' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Наступний місяць' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(sheet.querySelector('[data-day="2026-10-05"]')!);
    expect(onJumpDate).toHaveBeenCalledWith('2026-10-05');
    expect(screen.queryByTestId('jump-date-sheet')).toBeNull();
  });
  it('hides the picker without bounds', () => {
    render(<ChatSearchBar query="" matchCount={0} matchIndex={0} onQuery={vi.fn()} onPrev={vi.fn()} onNext={vi.fn()} onClose={vi.fn()} onJumpDate={vi.fn()} dateBounds={null} />);
    expect(screen.queryByTestId('jump-date')).toBeNull();
  });
});

describe('export door', () => {
  it('is offered for a person chat, not for the assistant', () => {
    expect(phoneChatMenuDoors({ id: 'c', type: 'direct', title: 'A' } as unknown as Chat).export).toBe(true);
    expect(phoneChatMenuDoors({ id: 'c', type: 'ai', title: 'A' } as unknown as Chat).export).toBe(false);
  });
});
