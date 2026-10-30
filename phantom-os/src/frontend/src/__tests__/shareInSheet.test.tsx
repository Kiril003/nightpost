import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('../services/shareIn', () => ({
  fetchPendingShare: vi.fn(),
  shareFiles: vi.fn(),
  clearShare: vi.fn(),
  installShareIn: vi.fn((open: () => void) => {
    (window as unknown as { __phantomShareIn?: () => boolean }).__phantomShareIn = () => {
      open();
      return true;
    };
    return () => {
      delete (window as unknown as { __phantomShareIn?: () => boolean }).__phantomShareIn;
    };
  }),
}));

import { ShareInHost } from '../components/messenger/ShareInSheet';
import { clearShare, fetchPendingShare, shareFiles, type PendingShare } from '../services/shareIn';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const makeChat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    peerNodeId: `n_${id}`,
    messages: [],
    lastSnippet: 'hi',
    lastAt: new Date().toISOString(),
    ...extra,
  }) as unknown as Chat;

const setActiveChat = vi.fn();
const sendAttachments = vi.fn();
const sendMessage = vi.fn();
const sendGeoPoint = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  setActiveChat.mockImplementation((id: string) => {
    useMessengerStore.setState({ activeChatId: id } as never);
  });
  sendAttachments.mockResolvedValue(undefined);
  sendMessage.mockResolvedValue(undefined);
  sendGeoPoint.mockResolvedValue(undefined);
  vi.mocked(clearShare).mockResolvedValue(undefined);
  useMessengerStore.setState({
    chats: [
      makeChat('self', 'Saved', { mine: true } as Partial<Chat>),
      makeChat('c1', 'Olena'),
      makeChat('c2', 'Taras'),
    ],
    activeChatId: null,
    setActiveChat,
    sendAttachments,
    sendMessage,
    sendGeoPoint,
  } as never);
});

afterEach(() => {
  cleanup();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('ShareInHost', () => {
  it('opens when pending share exists, hides mine chats, disables Send until a chat is picked, sends images with caption', async () => {
    const pending: PendingShare = {
      id: 'b1',
      skipped: 2,
      items: [
        { index: 0, kind: 'image', text: null, mime: 'image/jpeg', name: 'a.jpg', size: 10 },
        { index: 1, kind: 'text', text: 'Look at this', mime: 'text/plain', name: null, size: 12 },
      ],
    };
    const file = new File([new Uint8Array([1, 2, 3])], 'a.jpg', { type: 'image/jpeg' });
    vi.mocked(fetchPendingShare).mockResolvedValue(pending);
    vi.mocked(shareFiles).mockResolvedValue([file]);

    render(<ShareInHost />);
    await screen.findByTestId('share-in-sheet');
    expect(screen.queryByText('Saved')).toBeNull();
    expect(screen.getByText(/Photos: 1/)).toBeTruthy();
    expect(screen.getByText(/Not added: 2/)).toBeTruthy();

    const sendBtn = screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement;
    expect(sendBtn.disabled).toBe(true);

    fireEvent.click(screen.getByText('Olena'));
    expect(sendBtn.disabled).toBe(false);

    fireEvent.click(sendBtn);
    await waitFor(() => expect(setActiveChat).toHaveBeenCalledWith('c1'));
    expect(useMessengerStore.getState().activeChatId).toBe('c1');
    expect(sendAttachments).toHaveBeenCalledWith([file], { caption: 'Look at this' });
    expect(clearShare).toHaveBeenCalled();
  });

  it('a map place lists groups and Saved, and goes out as a point into the picked chat', async () => {
    useMessengerStore.setState({
      chats: [
        makeChat('chat_saved_notes', 'Notes'),
        makeChat('g1', 'Hike crew', { type: 'group' } as Partial<Chat>),
        makeChat('c1', 'Olena'),
      ],
    } as never);
    vi.mocked(fetchPendingShare).mockResolvedValue({
      id: 'p1',
      skipped: 0,
      items: [{ index: 0, kind: 'place', text: 'Market square', mime: null, name: null, size: 0, lat: 49.84, lon: 24.03 }],
    });

    render(<ShareInHost />);
    await screen.findByTestId('share-in-sheet');
    expect(screen.getByText('Market square')).toBeTruthy();
    expect(screen.getByText('Notes')).toBeTruthy();
    const sendBtn = screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement;

    fireEvent.click(screen.getByText('Hike crew'));
    expect(sendBtn.disabled).toBe(false);
    fireEvent.click(sendBtn);
    await waitFor(() => expect(sendGeoPoint).toHaveBeenCalledTimes(1));
    expect(setActiveChat).toHaveBeenCalledWith('g1');
    expect(sendGeoPoint.mock.calls[0][0]).toMatchObject({ lat: 49.84, lon: 24.03, label: 'Market square' });
    expect(sendMessage).not.toHaveBeenCalled();
    expect(sendAttachments).not.toHaveBeenCalled();
    expect(clearShare).toHaveBeenCalled();
  });

  it('a place without a name still reads as a place', async () => {
    vi.mocked(fetchPendingShare).mockResolvedValue({
      id: 'p2',
      skipped: 0,
      items: [{ index: 0, kind: 'place', text: null, mime: null, name: null, size: 0, lat: 1, lon: 2 }],
    });
    render(<ShareInHost />);
    await screen.findByTestId('share-in-sheet');
    expect(screen.getByText('Place')).toBeTruthy();
  });

  it('sends text via sendMessage when there are no images', async () => {
    const pending: PendingShare = {
      id: 'b2',
      skipped: 0,
      items: [{ index: 0, kind: 'text', text: 'Plain shared note', mime: 'text/plain', name: null, size: 17 }],
    };
    vi.mocked(fetchPendingShare).mockResolvedValue(pending);
    vi.mocked(shareFiles).mockResolvedValue([]);

    render(<ShareInHost />);
    await screen.findByTestId('share-in-sheet');
    expect(screen.getByText('Plain shared note')).toBeTruthy();

    fireEvent.click(screen.getByText('Taras'));
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(setActiveChat).toHaveBeenCalledWith('c2'));
    expect(useMessengerStore.getState().activeChatId).toBe('c2');
    expect(sendMessage).toHaveBeenCalledWith('Plain shared note');
    expect(sendAttachments).not.toHaveBeenCalled();
    expect(clearShare).toHaveBeenCalled();
  });

  it('keeps the share and says so when every photo failed to send', async () => {
    const pending: PendingShare = {
      id: 'b4',
      skipped: 0,
      items: [{ index: 0, kind: 'image', text: null, mime: 'image/jpeg', name: 'a.jpg', size: 10 }],
    };
    const file = new File([new Uint8Array([1])], 'a.jpg', { type: 'image/jpeg' });
    vi.mocked(fetchPendingShare).mockResolvedValue(pending);
    vi.mocked(shareFiles).mockResolvedValue([file]);
    sendAttachments.mockResolvedValue(['not sent']);

    render(<ShareInHost />);
    await screen.findByTestId('share-in-sheet');
    fireEvent.click(screen.getByText('Olena'));
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('Couldn\u02bct send')).toBeTruthy();
    expect(clearShare).not.toHaveBeenCalled();
    expect(screen.getByTestId('share-in-sheet')).toBeTruthy();
  });

  it('keeps Send disabled when nothing was added', async () => {
    const pending: PendingShare = { id: 'b5', skipped: 1, items: [] };
    vi.mocked(fetchPendingShare).mockResolvedValue(pending);

    render(<ShareInHost />);
    await screen.findByTestId('share-in-sheet');
    expect(screen.getByText(/Not added: 1/)).toBeTruthy();
    fireEvent.click(screen.getByText('Olena'));
    expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('closing without sending calls clearShare and window.__phantomShareIn re-opens', async () => {
    const pending: PendingShare = {
      id: 'b3',
      skipped: 0,
      items: [{ index: 0, kind: 'text', text: 'Hello world', mime: 'text/plain', name: null, size: 11 }],
    };
    vi.mocked(fetchPendingShare).mockResolvedValueOnce(null).mockResolvedValueOnce(pending);

    render(<ShareInHost />);
    expect(screen.queryByTestId('share-in-sheet')).toBeNull();

    await act(async () => {
      (window as unknown as { __phantomShareIn: () => boolean }).__phantomShareIn();
    });

    await screen.findByTestId('share-in-sheet');
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(clearShare).toHaveBeenCalled());
  });
});
