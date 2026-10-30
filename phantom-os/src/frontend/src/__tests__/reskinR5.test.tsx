/**
 * R5 · look D sheets: one frame for every bottom sheet — canvas paper with a top line,
 * grabber, title-1 row with a round ×, rows grouped on surface with inset dividers,
 * one amber primary. Focus stays inside; Esc and the scrim close.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Image as ImageIcon } from 'lucide-react';
import { FormSheet, PrimaryButton } from '../components/messenger/FormSheet';
import { AttachSheet } from '../components/messenger/AttachSheet';
import { CallSettingsSheet } from '../components/messenger/CallSettingsSheet';
import { ReactionPickerModal } from '../components/messenger/ReactionPickerModal';
import { ChatActionsSheet } from '../components/messenger/ChatActionsSheet';
import { PhotoCropSheet } from '../components/messenger/PhotoCropSheet';
import { Toast } from '../components/messenger/Toast';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
});

const noop = () => {};
const cls = (el: Element | null) => (el?.getAttribute('class') ?? '').split(/\s+/);
const frameIn = (root: ParentNode) => root.querySelector('[data-kit="sheet-frame"]') as HTMLElement;
const scrimOf = (frame: HTMLElement) => frame.parentElement!.querySelector(':scope > [aria-hidden="true"]') as HTMLElement;

const CHAT = { id: 'g1', title: 'Flat 4B', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [] } as unknown as Chat;

describe('R5 · the D sheet frame', () => {
  it('is canvas paper with a strong top line, grabber, title-1 row without a rule, and a round × in a 44 hit', () => {
    const onClose = vi.fn();
    render(
      <FormSheet open onClose={onClose} title="Flat 4B" testId="s" footer={<PrimaryButton>Save</PrimaryButton>}>
        <p>body</p>
      </FormSheet>,
    );
    const frame = frameIn(document.body);
    expect(cls(frame)).toEqual(expect.arrayContaining(['bg-canvas', 'border-t', 'border-line-strong', 'rounded-t-sheet']));
    expect(cls(frame)).not.toContain('bg-raised');
    expect(frame.getAttribute('role')).toBe('dialog');
    expect(frame.getAttribute('aria-modal')).toBe('true');
    const h2 = frame.querySelector('h2')!;
    expect(cls(h2)).toContain('text-title-1');
    expect(cls(h2.parentElement)).not.toContain('border-b');
    const close = frame.querySelector('[data-sheet-close]') as HTMLElement;
    expect(close.getAttribute('aria-label')).toBe('Закрити');
    expect(cls(close)).toEqual(expect.arrayContaining(['w-11', 'h-11']));
    expect(cls(close.firstElementChild)).toEqual(expect.arrayContaining(['w-8', 'h-8', 'rounded-full', 'bg-raised']));
    fireEvent.click(close);
    expect(onClose).toHaveBeenCalledTimes(1);
    const save = screen.getByText('Save');
    expect(cls(save)).toEqual(expect.arrayContaining(['bg-accent', 'text-on-accent', 'min-h-12']));
  });

  it('traps focus: opens on the first control, Tab wraps both ways, and focus returns to the opener', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    const { rerender } = render(
      <FormSheet open onClose={noop} title="T">
        <button type="button">one</button>
        <button type="button">two</button>
      </FormSheet>,
    );
    const frame = frameIn(document.body);
    expect(frame.contains(document.activeElement)).toBe(true);
    const two = screen.getByText('two');
    two.focus();
    fireEvent.keyDown(two, { key: 'Tab' });
    expect(frame.contains(document.activeElement)).toBe(true);
    expect(document.activeElement).not.toBe(two);
    const first = document.activeElement as HTMLElement;
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(two);
    rerender(
      <FormSheet open={false} onClose={noop} title="T">
        <button type="button">one</button>
      </FormSheet>,
    );
    expect(document.activeElement).toBe(opener);
    opener.remove();
  });
});

describe('R5 · every sheet sits on the same frame and closes by Esc and scrim', () => {
  const cases: Array<[string, (close: () => void) => React.ReactElement]> = [
    ['AttachSheet', (close) => <AttachSheet tiles={[{ icon: ImageIcon, label: 'Фото', pick: noop }]} moreTiles={[]} onClose={close} />],
    ['CallSettingsSheet', (close) => <CallSettingsSheet open onClose={close} readDataUsedBytes={() => null} />],
    ['ReactionPickerModal', (close) => <ReactionPickerModal isOpen onClose={close} onSelectEmoji={noop} />],
    ['ChatActionsSheet', (close) => (
      <ChatActionsSheet chat={CHAT} onClose={close} onMarkRead={noop} onTogglePin={noop} onToggleMute={noop} onToggleArchive={noop} onRename={noop} onClear={noop} onDelete={noop} />
    )],
  ];

  it.each(cases)('%s', (_name, make) => {
    const onClose = vi.fn();
    render(make(onClose));
    const frame = frameIn(document.body);
    expect(frame).toBeTruthy();
    expect(frame.closest('[role="dialog"][aria-modal="true"]')).toBeTruthy();
    expect(frame.contains(document.activeElement)).toBe(true);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(scrimOf(frame));
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});

describe('R5 · row anatomy', () => {
  it('ChatActionsSheet groups its rows on surface with inset dividers, no full-width rules', () => {
    render(
      <ChatActionsSheet chat={CHAT} onClose={noop} onMarkRead={noop} onTogglePin={noop} onToggleMute={noop} onToggleArchive={noop} onRename={noop} onClear={noop} onDelete={noop} />,
    );
    const frame = frameIn(document.body);
    const groups = frame.querySelectorAll('[data-sheet-group]');
    expect(groups.length).toBeGreaterThanOrEqual(2);
    groups.forEach((g) => expect(cls(g)).toEqual(expect.arrayContaining(['bg-surface', 'rounded-card'])));
    expect(frame.querySelectorAll('[data-sheet-divider]').length).toBeGreaterThan(0);
    expect(frame.querySelector('.h-px.bg-line:not([data-sheet-divider])')).toBeNull();
    for (const label of ['Закріпити', 'Вимкнути звук', 'Перейменувати']) {
      expect(screen.getAllByText(new RegExp(label, 'i')).length).toBeGreaterThan(0);
    }
  });
});

describe('R5 · toast and photo crop', () => {
  it('Toast carries a line instead of a shadow', () => {
    render(<Toast message="Збережено" onDone={noop} />);
    const box = screen.getByRole('status');
    expect(cls(box)).toEqual(expect.arrayContaining(['border', 'border-line-strong']));
    expect(cls(box).some((c) => c.startsWith('shadow-'))).toBe(false);
  });

  it('PhotoCropSheet closes on Esc and keeps focus inside', () => {
    const img = { src: 'data:,', naturalWidth: 400, naturalHeight: 300, width: 400, height: 300 } as unknown as HTMLImageElement;
    const onClose = vi.fn();
    render(<PhotoCropSheet img={img} onClose={onClose} onDone={noop} />);
    const dialog = screen.getByTestId('photo-crop');
    expect(dialog.contains(document.activeElement)).toBe(true);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
