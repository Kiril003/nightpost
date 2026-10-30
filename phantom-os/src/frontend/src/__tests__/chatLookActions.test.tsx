/**
 * Message long-press, design system 2.18 + mockup 05-chat-actions: the pressed
 * message lifted on `surface-2`; a reactions bar (6 emoji + "+", `surface-1`,
 * radius 24) right above it; an action menu (`surface-1`, radius 16, rows 48
 * with 24 icons in text-2) below it: Reply, Forward, Copy, Pin, then a 0.5
 * separator and Delete. Flat: no shadow, no amber, no route diagram.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import type { Chat, Message } from '../types/messenger';

const direct = { id: 'c1', title: 'Оксана', type: 'direct', peerNodeId: 'oksana' } as Chat;
const theirs = { id: 'm1', senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '16:58', type: 'text', text: 'booked the window table' } as Message;
const mine = { ...theirs, id: 'm2', senderId: 'me', isSelf: true, status: 'sent', transport: 'relay' } as Message;

const spyHandlers = () => {
  const calls: Record<string, ReturnType<typeof vi.fn>> = {};
  const on = new Proxy({}, { get: (_t, k: string) => (calls[k] ??= vi.fn()) }) as MessageSheetHandlers;
  return { on, calls };
};

const draw = (m: Message, on: MessageSheetHandlers = spyHandlers().on) =>
  render(<MessageActionSheet msg={m} currentChat={direct} currentUserId="me" speaking={false} onClose={() => {}} on={on} />);

afterEach(() => {
  cleanup();
  document.getElementById('message-m1')?.remove();
  vi.restoreAllMocks();
});

const dialog = () => screen.getByRole('dialog');

describe('long-press (2.18)', () => {
  it('the pressed message is lifted on surface-2, its own bubble shape, no shadow', () => {
    draw(theirs);
    const lifted = dialog().querySelector('[data-msg-lifted]') as HTMLElement;
    expect(lifted.className).toContain('bg-raised');
    expect(lifted.className).toContain('rounded-bubble');
    expect(lifted.textContent).toContain('booked the window table');
  });

  it('reactions bar: 6 emoji and a "+", on surface-1 with radius 24', () => {
    const { on, calls } = spyHandlers();
    draw(theirs, on);
    const bar = dialog().querySelector('[data-msg-reactions-bar]') as HTMLElement;
    expect(bar.className).toContain('bg-surface');
    expect(bar.className).toContain('rounded-[24px]');
    const emoji = within(bar)
      .getAllByRole('button')
      .map((b) => b.getAttribute('aria-label'));
    expect(emoji).toEqual(['Реакція ✅', 'Реакція ❤️', 'Реакція 😂', 'Реакція 😮', 'Реакція 😢', 'Реакція 🙏', 'Більше емодзі']);
    fireEvent.click(within(bar).getByLabelText('Реакція 😂'));
    expect(calls.react).toHaveBeenCalledWith('m1', '😂');
  });

  it('menu: surface-1 radius 16, rows 48 with 24 icons in text-2, in the mockup order', () => {
    draw(theirs);
    const menu = dialog().querySelector('[data-msg-menu]') as HTMLElement;
    expect(menu.className).toContain('bg-surface');
    expect(menu.className).toContain('rounded-bubble');
    const rows = [...menu.querySelectorAll('button')] as HTMLElement[];
    const labels = rows.map((r) => r.textContent);
    expect(labels.slice(0, 4)).toEqual(['Відповісти', 'Переслати', 'Копіювати', 'Закріпити в себе']);
    for (const r of rows.slice(0, 4)) {
      expect(r.className).toContain('h-12');
      const icon = r.querySelector('svg') as SVGElement;
      expect(icon.getAttribute('class')).toContain('w-6');
      expect(icon.getAttribute('class')).toContain('text-ink-2');
    }
    // T14: no separator gap before Delete; T13/T17: stack lets taps fall through to the scrim (T15)
    expect(menu.querySelector('[data-msg-menu-sep]')).toBeNull();
    const del = within(menu).getByText('Видалити').closest('button')!;
    expect(del.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect((dialog() as HTMLElement).className).toContain('pointer-events-none');
    expect(menu.className).toContain('pointer-events-auto');
  });

  it('my message: Edit right after Copy, and no route diagram or grid tiles', () => {
    draw(mine);
    const labels = [...dialog().querySelectorAll('[data-msg-menu] button')].map((b) => b.textContent);
    expect(labels.slice(0, 5)).toEqual(['Відповісти', 'Переслати', 'Копіювати', 'Редагувати', 'Закріпити в себе']);
    expect(dialog().querySelector('[data-route]')).toBeNull();
    expect(dialog().querySelector('.h-16')).toBeNull();
  });

  it('2.18 rows + «Select many» (msg-x), then Delete, Report and one More with an icon; Thread lives inside More', () => {
    draw(theirs);
    const labels = () => [...dialog().querySelectorAll('[data-msg-menu] button')].map((b) => b.textContent);
    expect(labels()).toEqual(['Відповісти', 'Переслати', 'Копіювати', 'Закріпити в себе', 'Вибрати кілька', 'Видалити', 'Поскаржитися', 'Ще']);
    const more = dialog().querySelector('[data-msg-more]') as HTMLElement;
    expect(more.querySelector('svg')).toBeTruthy();
    expect(more.className).toMatch(/(^|\s)text-ink(\s|$)/);
    fireEvent.click(more);
    expect(labels()).toEqual(expect.arrayContaining(['Перекласти англійською', 'Коротко про головне', 'Справи з повідомлення', 'Гілка']));
  });

  it('flat and calm: no shadow, no amber anywhere in the sheet', () => {
    draw(mine);
    expect(dialog().outerHTML).not.toMatch(/shadow-e|bg-accent|text-accent/);
  });

  it('Pin and Delete keep their handlers', () => {
    const { on, calls } = spyHandlers();
    draw(theirs, on);
    fireEvent.click(screen.getByText('Закріпити в себе'));
    expect(calls.togglePin).toHaveBeenCalledWith('m1');
    fireEvent.click(screen.getByText('Видалити'));
    expect(calls.remove).toHaveBeenCalledWith(theirs);
  });

  it('anchors to the pressed message: reactions 8 above it, at its left edge', () => {
    const row = document.createElement('div');
    row.id = 'message-m1';
    document.body.appendChild(row);
    vi.spyOn(row, 'getBoundingClientRect').mockReturnValue({ top: 400, bottom: 440, left: 56, right: 300, height: 40, width: 244, x: 56, y: 400, toJSON: () => ({}) } as DOMRect);
    draw(theirs);
    const stack = dialog();
    expect(stack.hasAttribute('data-msg-stack')).toBe(true);
    expect(stack.getAttribute('data-anchored')).toBe('true');
    expect(stack.style.top).toBe('332px');
    expect(stack.style.left).toBe('56px');
  });

  it('without a visible message it falls back to the bottom', () => {
    draw(theirs);
    expect(dialog().getAttribute('data-anchored')).toBe('false');
  });
});
