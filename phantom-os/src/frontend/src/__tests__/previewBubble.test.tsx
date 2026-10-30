import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Message, SecureMedia } from '../types/messenger';
import { SecureMediaBubble } from '../components/messenger/SecureMediaBubble';

vi.mock('../services/messengerMedia', async () => {
  const actual = await vi.importActual<typeof import('../services/messengerMedia')>(
    '../services/messengerMedia',
  );
  return {
    ...actual,
    cachedMediaUrl: vi.fn(() => 'blob:fixture'),
  };
});

function buildFileMessage(overrides: Partial<SecureMedia>): Message {
  const media = {
    blobId: 'blob-fixture-id',
    key: 'k',
    nonce: 'n',
    sha256: 'h',
    mime: 'text/plain',
    size: 512,
    name: 'notes.txt',
    ...overrides,
  } as unknown as SecureMedia;
  return {
    id: `msg-${media.name}`,
    senderId: 'self',
    text: '',
    timestamp: '12:00',
    status: 'sent',
    type: 'file',
    media,
  } as Message;
}

describe('SecureMediaBubble file preview', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('log line 1'))),
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows Open for notes.txt and opens the preview sheet with Save and Close', async () => {
    const msg = buildFileMessage({ name: 'notes.txt', mime: 'text/plain', size: 256 });
    render(<SecureMediaBubble msg={msg} isSelf={true} />);

    const openBtn = screen.getByTestId('media-file-open');
    expect(openBtn.textContent).toBe('Відкрити');

    fireEvent.click(openBtn);

    const sheet = await screen.findByTestId('file-preview-sheet');
    expect(sheet).toBeTruthy();
    expect(within(sheet).getByLabelText('Зберегти')).toBeTruthy();
    expect(within(sheet).getByLabelText('Закрити')).toBeTruthy();
    await waitFor(() => {
      expect(sheet.querySelector('pre')?.textContent).toBe('log line 1');
    });
  });

  it('renders only Save and no Open button for unknown file types', () => {
    const msg = buildFileMessage({
      name: 'a.xyz',
      mime: 'application/octet-stream',
      size: 256,
    });
    render(<SecureMediaBubble msg={msg} isSelf={true} />);

    expect(screen.queryByTestId('media-file-open')).toBeNull();
    expect(screen.getByLabelText('Зберегти')).toBeTruthy();
  });

  it('hides Open and shows the too-big notice for a 20 MiB text file', () => {
    const msg = buildFileMessage({
      name: 'big.txt',
      mime: 'text/plain',
      size: 20 * 1024 * 1024,
    });
    render(<SecureMediaBubble msg={msg} isSelf={true} />);

    expect(screen.queryByTestId('media-file-open')).toBeNull();
    expect(screen.getByText('Завеликий для перегляду')).toBeTruthy();
    expect(screen.getByLabelText('Зберегти')).toBeTruthy();
  });

  it('does not refetch or remount the text preview when the bubble re-renders', async () => {
    const msg = buildFileMessage({ name: 'notes.txt', mime: 'text/plain', size: 256 });
    const { rerender } = render(<SecureMediaBubble msg={msg} isSelf={true} />);

    fireEvent.click(screen.getByTestId('media-file-open'));

    const sheet = await screen.findByTestId('file-preview-sheet');
    await waitFor(() => {
      expect(sheet.querySelector('pre')?.textContent).toBe('log line 1');
    });

    const preBefore = sheet.querySelector('pre');
    expect(fetch).toHaveBeenCalledTimes(1);

    rerender(<SecureMediaBubble msg={{ ...msg, status: 'sent', text: ' ' }} isSelf={true} />);

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(sheet.querySelector('pre')).toBe(preBefore);
    expect(sheet.querySelector('pre')?.textContent).toBe('log line 1');
  });

  it('stops click, pointerdown, and contextmenu events in the sheet from bubbling to bubble ancestors', async () => {
    const msg = buildFileMessage({ name: 'notes.txt', mime: 'text/plain', size: 256 });
    const onParentClick = vi.fn();
    const onParentPointerDown = vi.fn();
    const onParentContextMenu = vi.fn();

    render(
      <div
        onClick={onParentClick}
        onPointerDown={onParentPointerDown}
        onContextMenu={onParentContextMenu}
      >
        <SecureMediaBubble msg={msg} isSelf={true} />
      </div>,
    );

    fireEvent.click(screen.getByTestId('media-file-open'));

    const sheet = await screen.findByTestId('file-preview-sheet');
    await waitFor(() => {
      expect(sheet.querySelector('pre')?.textContent).toBe('log line 1');
    });

    onParentClick.mockClear();
    onParentPointerDown.mockClear();
    onParentContextMenu.mockClear();

    const pre = sheet.querySelector('pre')!;
    fireEvent.pointerDown(pre);
    fireEvent.contextMenu(pre);
    fireEvent.click(pre);

    const saveBtn = within(sheet).getByLabelText('Зберегти');
    fireEvent.pointerDown(saveBtn);
    fireEvent.click(saveBtn);

    const closeBtn = within(sheet).getByLabelText('Закрити');
    fireEvent.pointerDown(closeBtn);
    fireEvent.click(closeBtn);

    expect(onParentPointerDown).not.toHaveBeenCalled();
    expect(onParentContextMenu).not.toHaveBeenCalled();
    expect(onParentClick).not.toHaveBeenCalled();
    expect(screen.queryByTestId('file-preview-sheet')).toBeNull();
  });
});
