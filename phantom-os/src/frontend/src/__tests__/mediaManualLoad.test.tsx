import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { SecureMediaBubble } from '../components/messenger/SecureMediaBubble';
import { UnifiedVoiceBubble } from '../components/messenger/UnifiedVoiceBubble';
import { useMessengerStore } from '../stores/messengerStore';
import { loadMedia, MediaNotArrived } from '../services/messengerMedia';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/messengerMedia', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerMedia')>();
  return {
    ...actual,
    loadMedia: vi.fn(),
    cachedMediaUrl: vi.fn(() => undefined),
  };
});

const photoMsg: Message = {
  id: 'm-photo-1',
  senderId: 'peer-1',
  senderName: 'Peer',
  senderAvatar: '',
  text: '',
  timestamp: '12:00',
  status: 'sent',
  type: 'image',
  media: {
    blobId: 'blob-photo-1',
    name: 'summer.jpg',
    mime: 'image/jpeg',
    size: Math.round(2.4 * 1024 * 1024),
    sha256: 'aa'.repeat(32),
    keyHex: 'bb'.repeat(32),
    nonceHex: 'cc'.repeat(12),
    width: 800,
    height: 600,
  },
};

const voiceMsg: Message = {
  id: 'm-voice-1',
  senderId: 'peer-1',
  senderName: 'Peer',
  senderAvatar: '',
  text: '',
  timestamp: '12:01',
  status: 'sent',
  type: 'voice',
  voiceData: {
    duration: 12,
    waveform: [1, 3, 5, 2, 8, 6, 4, 7, 3, 2],
    media: {
      blobId: 'blob-voice-1',
      name: 'voice.ogg',
      mime: 'audio/ogg',
      size: 48 * 1024,
      sha256: 'dd'.repeat(32),
      keyHex: 'ee'.repeat(32),
      nonceHex: 'ff'.repeat(12),
    },
  },
};

function setNet(type: 'wifi' | 'cellular', saveData = false): void {
  Object.defineProperty(navigator, 'connection', {
    value: { type, saveData },
    configurable: true,
    writable: true,
  });
}

function seedChat(circle: 'social' | 'close' | 'personal', messages: Message[]): void {
  const chat: Chat = {
    id: `chat-${circle}`,
    title: 'Test Chat',
    avatar: 'T',
    type: 'dm',
    unreadCount: 0,
    circle,
    circles: [circle],
    messages,
  };
  useMessengerStore.setState({ chats: [chat] });
}

describe('media manual load by ring and network', () => {
  const origIO = globalThis.IntersectionObserver;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadMedia).mockResolvedValue('blob:decrypted-test');
    globalThis.IntersectionObserver = class {
      private cb: IntersectionObserverCallback;
      constructor(cb: IntersectionObserverCallback) {
        this.cb = cb;
      }
      observe(target: Element) {
        this.cb(
          [{ isIntersecting: true, target } as IntersectionObserverEntry],
          this as unknown as IntersectionObserver,
        );
      }
      unobserve() {}
      disconnect() {}
      takeRecords(): IntersectionObserverEntry[] {
        return [];
      }
      readonly root = null;
      readonly rootMargin = '';
      readonly thresholds = [];
    } as unknown as typeof IntersectionObserver;
  });

  afterEach(() => {
    globalThis.IntersectionObserver = origIO;
    useMessengerStore.setState({ chats: [] });
  });

  it('shows manual load button with size for social photo on cellular and loads on tap', async () => {
    setNet('cellular');
    seedChat('social', [photoMsg]);

    render(<SecureMediaBubble msg={photoMsg} isSelf={false} />);

    const manual = screen.getByTestId('media-manual');
    expect(manual).toBeInTheDocument();
    expect(manual.textContent).toMatch(/2\.4/);
    expect(loadMedia).not.toHaveBeenCalled();

    fireEvent.click(within(manual).getByRole('button'));

    await waitFor(() => {
      expect(loadMedia).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(screen.getByTestId('media-image')).toBeInTheDocument();
    });
  });

  it('keeps waiting/error state after tapping Load fails once instead of resetting to manual', async () => {
    setNet('cellular');
    seedChat('social', [photoMsg]);
    vi.mocked(loadMedia).mockRejectedValueOnce(new MediaNotArrived());

    const { rerender } = render(<SecureMediaBubble msg={photoMsg} isSelf={false} />);

    const manual = screen.getByTestId('media-manual');
    fireEvent.click(within(manual).getByRole('button'));

    await waitFor(() => {
      expect(screen.getByTestId('media-waiting')).toBeInTheDocument();
    });

    rerender(
      <SecureMediaBubble
        msg={{ ...photoMsg, media: { ...photoMsg.media! } }}
        isSelf={false}
      />,
    );

    expect(screen.getByTestId('media-waiting')).toBeInTheDocument();
    expect(screen.queryByTestId('media-manual')).toBeNull();
  });

  it('loads automatically for close chat on cellular without a click', async () => {
    setNet('cellular');
    seedChat('close', [photoMsg]);

    render(<SecureMediaBubble msg={photoMsg} isSelf={false} />);

    await waitFor(() => {
      expect(loadMedia).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId('media-manual')).toBeNull();
  });

  it('decides allowed from media.mime (image/* -> photo) rather than isImage', async () => {
    setNet('cellular');
    const rawImageMsg: Message = {
      ...photoMsg,
      id: 'm-photo-raw',
      type: 'file',
      media: {
        ...photoMsg.media!,
        blobId: 'blob-photo-raw',
        name: 'scan.tiff',
        mime: 'image/tiff',
      },
    };
    seedChat('close', [rawImageMsg]);

    render(<SecureMediaBubble msg={rawImageMsg} isSelf={false} />);

    await waitFor(() => {
      expect(loadMedia).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId('media-manual')).toBeNull();
  });

  it('loads automatically for social chat on wifi', async () => {
    setNet('wifi');
    seedChat('social', [photoMsg]);

    render(<SecureMediaBubble msg={photoMsg} isSelf={false} />);

    await waitFor(() => {
      expect(loadMedia).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId('media-manual')).toBeNull();
  });

  it('does not fetch voice on mount for social chat on mobile, renders neutral label without danger class, and fetches on play tap', async () => {
    setNet('cellular');
    seedChat('social', [voiceMsg]);

    const { container } = render(<UnifiedVoiceBubble msg={voiceMsg} />);

    expect(loadMedia).not.toHaveBeenCalled();
    expect(container.querySelector('.text-danger')).toBeNull();
    expect(screen.getByText(/48/)).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('voice-play'));

    await waitFor(() => {
      expect(loadMedia).toHaveBeenCalledTimes(1);
    });
  });
});
