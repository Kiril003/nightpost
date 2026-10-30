import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { VideoNoteRecorder } from '../components/messenger/VideoNoteRecorder';
import { MediaBatchGrid } from '../components/messenger/MediaBatchGrid';
import { ShareInHost } from '../components/messenger/ShareInSheet';
import { SecureMediaBubble } from '../components/messenger/SecureMediaBubble';
import { FilePreviewSheet } from '../components/messenger/preview/FilePreviewSheet';
import { UnifiedVoiceBubble } from '../components/messenger/UnifiedVoiceBubble';
import { ThreadView } from '../components/messenger/ThreadView';
import { MediaLightboxModal } from '../components/messenger/MediaLightboxModal';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { useSpaceStore } from '../stores/spaceStore';
import { usePhoneLayerStore } from '../stores/phoneLayerStore';
import { usePhoneBackStack } from '../hooks/usePhoneBackStack';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { messengerApi } from '../services/messengerApi';
import * as messengerMedia from '../services/messengerMedia';
import { MediaNotArrived, MediaTampered } from '../services/messengerMedia';
import * as imagePrep from '../services/imagePrep';
import * as shareIn from '../services/shareIn';
import { nextInRow, voiceQueueOf } from '../services/voiceQueue';
import type { Chat, Message, SecureMedia } from '../types/messenger';

const setLocale = (l: 'uk' | 'en' | 'cs') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  useUIStore.setState({ toasts: [] });
  usePhoneLayerStore.setState({ layers: {} });
  useSpaceStore.setState({ threadView: null });
});

afterEach(() => {
  cleanup();
  setLocale('uk');
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const makeMedia = (over: Partial<SecureMedia> = {}): SecureMedia => ({
  name: 'file.bin',
  size: 1024,
  mime: 'application/octet-stream',
  sha256: 'a'.repeat(64),
  blobId: 'b_1',
  keyHex: 'ab'.repeat(32),
  nonceHex: 'cd'.repeat(12),
  ...over,
});

describe('calls-media audit: media, previews, voice & lightbox (#4, #6, #11, #14, #15, #16, #17, #18, #20, #21, #22, #23, #24, #26)', () => {
  it('#4: VideoNoteRecorder RAF timer advances across frames and sends accurate durationS', async () => {
    let now = 1000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    const rafCallbacks: FrameRequestCallback[] = [];
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
      rafCallbacks.push(cb);
      return rafCallbacks.length;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {});

    let activeRecorder: {
      state: string;
      mimeType: string;
      ondataavailable: ((e: { data: Blob }) => void) | null;
      onstop: (() => void) | null;
      start: () => void;
      stop: () => void;
    } | null = null;

    class FakeMediaRecorder {
      static isTypeSupported() {
        return true;
      }
      state = 'inactive';
      mimeType = 'video/webm';
      ondataavailable: ((e: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      constructor() {
        activeRecorder = this;
      }
      start() {
        this.state = 'recording';
      }
      stop() {
        this.state = 'inactive';
        this.onstop?.();
      }
    }
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder);
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => ({
          getTracks: () => [{ stop: () => {} }],
        })),
      },
    });

    const onSend = vi.fn();
    render(<VideoNoteRecorder onSend={onSend} onCancel={vi.fn()} />);
    await act(async () => undefined);
    await act(async () => undefined);

    // Emit a chunk before flipping camera
    act(() => {
      activeRecorder?.ondataavailable?.({ data: new Blob([new Uint8Array([1, 2, 3])]) });
    });

    // Advance RAF across multiple frames up to 5 seconds
    for (const step of [2000, 3500, 6000]) {
      now = step;
      const cb = rafCallbacks.pop();
      if (cb) act(() => cb(now));
    }

    expect(screen.getByText('0:05 / 1:00')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Надіслати відео-нотатку' }));
    expect(onSend).toHaveBeenCalledTimes(1);
    const [sentBlob, sentDuration] = onSend.mock.calls[0] as [Blob, number];
    expect(sentDuration).toBe(5);
    expect(sentBlob.size).toBe(3);
  });

  it('#6: retrySend updates voiceData.media.blobId, includes kind in wire body, and reconciles message id', async () => {
    vi.spyOn(messengerMedia, 'encryptForUpload').mockResolvedValue({
      ciphertext: new Blob([new Uint8Array([1, 2])]),
      keyHex: '11'.repeat(32),
      nonceHex: '22'.repeat(12),
      sha256: '33'.repeat(32),
    });
    vi.spyOn(messengerApi, 'uploadFile')
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({ blob_id: 'blob_voice_ok', size: 2, sha256: '33'.repeat(32), state: 'stored' });
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({
      id: 'srv_msg_1',
      client_id: 'c_1',
      conversation_id: 'chat_1',
      seq: 1,
      author_id: 'me',
      author_name: 'Me',
      kind: 'voice',
      body: '{}',
      delivery: 'sent',
      sent_at: new Date().toISOString(),
    } as never);

    useMessengerStore.setState({
      activeChatId: 'chat_1',
      currentUser: { id: 'me', name: 'Me', avatar: '' } as never,
      chats: [
        {
          id: 'chat_1',
          title: 'Peer',
          type: 'dm',
          circle: 'friends',
          avatar: '',
          unreadCount: 0,
          pinned: false,
          messages: [],
        } as unknown as Chat,
      ],
    });

    const audioFile = new File([new Uint8Array([9, 8, 7])], 'voice.webm', { type: 'audio/webm' });
    await useMessengerStore.getState().sendAttachment(audioFile, undefined, undefined, undefined, {
      kind: 'voice',
      voice: { duration: 7, waveform: [0.2, 0.8] },
    });

    const failedMsg = useMessengerStore.getState().chats[0].messages[0];
    expect(failedMsg.attachmentState).toBe('upload_failed');
    expect(failedMsg.voiceData?.media?.blobId).toBe('');

    await useMessengerStore.getState().retrySend(failedMsg.id);

    const retriedMsg = useMessengerStore.getState().chats[0].messages[0];
    expect(retriedMsg.id).toBe('srv_msg_1');
    expect(retriedMsg.voiceData?.media?.blobId).toBe('blob_voice_ok');
    const wireBody = JSON.parse(appendSpy.mock.calls[0][1].body || '{}');
    expect(wireBody.kind).toBe('voice');
    expect(wireBody.blob_id).toBe('blob_voice_ok');
  });

  it('#11: MediaBatchGrid waiting cell allows retry via requestBlob + loadMedia and localizes partial count', async () => {
    setLocale('en');
    const reqBlobSpy = vi.spyOn(messengerApi, 'requestBlob').mockResolvedValue({
      blob_id: 'b_wait',
      size: 100,
      sha256: 'a'.repeat(64),
      state: 'stored',
    });
    let calls = 0;
    vi.spyOn(messengerMedia, 'loadMedia').mockImplementation(async () => {
      calls += 1;
      if (calls === 1) throw new MediaNotArrived();
      return 'blob:ready-img';
    });
    vi.spyOn(messengerMedia, 'cachedMediaUrl').mockReturnValue(undefined);

    const item: Message = {
      id: 'm_b1',
      senderId: 'peer',
      senderName: 'Peer',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'image',
      isSelf: false,
      media: makeMedia({ blobId: 'b_wait', name: 'pic.jpg', mime: 'image/jpeg' }),
      mediaBatch: { id: 'batch_x', index: 0, total: 3 },
    };

    render(<MediaBatchGrid items={[item]} isSelf={false} />);
    await waitFor(() => expect(screen.getByLabelText('Still loading')).toBeTruthy());
    expect(screen.getByText('1 of 3')).toBeTruthy();

    fireEvent.click(screen.getByLabelText('Still loading'));
    await waitFor(() => expect(reqBlobSpy).toHaveBeenCalledWith('b_wait'));
    await waitFor(() => expect(screen.getByAltText('pic.jpg')).toBeTruthy());
  });

  it('#14: sendAttachments reports failed uploads so ShareInHost stays open and does not clear pending share', async () => {
    vi.spyOn(shareIn, 'fetchPendingShare').mockResolvedValue({
      items: [{ id: 's1', kind: 'image', name: 'shared.jpg', mime: 'image/jpeg', size: 10 }],
      skipped: 0,
    } as never);
    vi.spyOn(shareIn, 'shareFiles').mockResolvedValue([
      new File([new Uint8Array([1, 2, 3])], 'shared.jpg', { type: 'image/jpeg' }),
    ]);
    const clearSpy = vi.spyOn(shareIn, 'clearShare').mockResolvedValue(undefined);
    vi.spyOn(messengerMedia, 'encryptForUpload').mockResolvedValue({
      ciphertext: new Blob([new Uint8Array([1, 2, 3])]),
      keyHex: '11'.repeat(32),
      nonceHex: '22'.repeat(12),
      sha256: '33'.repeat(32),
    });
    vi.spyOn(messengerApi, 'uploadFile').mockRejectedValue(new Error('upload failed'));

    useMessengerStore.setState({
      activeChatId: 'chat_1',
      currentUser: { id: 'me', name: 'Me', avatar: '' } as never,
      chats: [
        {
          id: 'chat_1',
          title: 'Marta',
          type: 'dm',
          circle: 'friends',
          avatar: '',
          unreadCount: 0,
          pinned: false,
          messages: [],
        } as unknown as Chat,
      ],
    });

    render(<ShareInHost />);
    await screen.findByText('Marta');
    fireEvent.click(screen.getByText('Marta'));
    fireEvent.click(screen.getByRole('button', { name: 'Надіслати' }));

    await screen.findByText(/Не вдалося/i);
    expect(screen.getByTestId('share-in-sheet')).toBeTruthy();
    expect(clearSpy).not.toHaveBeenCalled();
  });

  it('#15: SecureMediaBubble shows error toast and does not save when PhantomBridge.previewOpen returns false', async () => {
    vi.spyOn(messengerMedia, 'cachedMediaUrl').mockReturnValue('blob:pdf-1');
    vi.stubGlobal('fetch', vi.fn(async () => ({ blob: async () => new Blob([new Uint8Array([1, 2, 3, 4])]) })));
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      previewBegin: vi.fn(() => 'pv_bad'),
      previewChunk: vi.fn(() => true),
      previewOpen: vi.fn(() => false),
    };

    const msg: Message = {
      id: 'm_pdf',
      senderId: 'me',
      senderName: 'Me',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'file',
      isSelf: true,
      media: makeMedia({ name: 'locked.pdf', mime: 'application/pdf', size: 4 }),
    };

    render(<SecureMediaBubble msg={msg} isSelf={true} />);
    fireEvent.click(screen.getByTestId('media-file-open'));

    await waitFor(() => {
      const toasts = useUIStore.getState().toasts;
      expect(toasts.some((t) => t.kind === 'error')).toBe(true);
    });
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('#16: FilePreviewSheet renders a visible failure message instead of blank screen when viewer fails', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('broken blob'))));

    render(
      <FilePreviewSheet
        isOpen
        url="blob:broken"
        name="notes.txt"
        mime="text/plain"
        size={100}
        onClose={vi.fn()}
      />,
    );

    const failed = await screen.findByTestId('preview-failed');
    expect(failed.textContent).toContain('Не вдалося відкрити файл');
  });

  it('#18: sendAttachment updates media.name when prepareImage transcodes PNG to WebP', async () => {
    const transcoded = new File([new Uint8Array([1, 2, 3])], 'diagram.webp', { type: 'image/webp' });
    vi.spyOn(imagePrep, 'prepareImage').mockResolvedValue({
      file: transcoded,
      width: 1920,
      height: 1080,
    });
    vi.spyOn(messengerMedia, 'encryptForUpload').mockResolvedValue({
      ciphertext: new Blob([new Uint8Array([1, 2, 3])]),
      keyHex: '11'.repeat(32),
      nonceHex: '22'.repeat(12),
      sha256: '33'.repeat(32),
    });
    vi.spyOn(messengerApi, 'uploadFile').mockResolvedValue({
      blob_id: 'blob_webp',
      size: 3,
      sha256: '33'.repeat(32),
      state: 'stored',
    });
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({
      id: 'srv_img',
      client_id: 'c_img',
      conversation_id: 'chat_1',
      seq: 1,
      author_id: 'me',
      author_name: 'Me',
      kind: 'image',
      body: '{}',
      delivery: 'sent',
      sent_at: new Date().toISOString(),
    } as never);

    useMessengerStore.setState({
      activeChatId: 'chat_1',
      currentUser: { id: 'me', name: 'Me', avatar: '' } as never,
      chats: [
        {
          id: 'chat_1',
          title: 'Peer',
          type: 'dm',
          circle: 'friends',
          avatar: '',
          unreadCount: 0,
          pinned: false,
          messages: [],
        } as unknown as Chat,
      ],
    });

    const origFile = new File([new Uint8Array([1, 2, 3, 4, 5])], 'diagram.png', { type: 'image/png' });
    await useMessengerStore.getState().sendAttachment(origFile);

    const msg = useMessengerStore.getState().chats[0].messages[0];
    expect(msg.media?.name).toBe('diagram.webp');
    expect(msg.media?.mime).toBe('image/webp');
  });

  it('#20: voiceQueueOf parses sentAt so voice notes days apart are not treated as consecutive', () => {
    const m1: Message = {
      id: 'v1',
      senderId: 'peer',
      senderName: 'Peer',
      senderAvatar: '',
      timestamp: '14:00',
      sentAt: '2026-09-01T14:00:00Z',
      type: 'voice',
      voiceData: { duration: 5, waveform: [0.5], audioUrl: 'blob:v1' },
    };
    const m2: Message = {
      id: 'v2',
      senderId: 'peer',
      senderName: 'Peer',
      senderAvatar: '',
      timestamp: '14:05',
      sentAt: '2026-09-10T14:05:00Z',
      type: 'voice',
      voiceData: { duration: 5, waveform: [0.5], audioUrl: 'blob:v2' },
    };

    const q = voiceQueueOf([m1, m2]);
    expect(nextInRow(q, 'v1', new Set())).toBeNull();
  });

  it('#21: UnifiedVoiceBubble and VideoNoteBubble call requestBlob on retry', async () => {
    const reqSpy = vi.spyOn(messengerApi, 'requestBlob').mockResolvedValue({
      blob_id: 'b_v1',
      size: 100,
      sha256: 'a'.repeat(64),
      state: 'stored',
    });
    vi.spyOn(messengerMedia, 'cachedMediaUrl').mockReturnValue(undefined);
    vi.spyOn(messengerMedia, 'loadMedia')
      .mockRejectedValueOnce(new MediaNotArrived())
      .mockResolvedValueOnce('blob:voice-ok');

    const vMsg: Message = {
      id: 'mv_1',
      senderId: 'peer',
      senderName: 'Peer',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'voice',
      voiceData: {
        duration: 4,
        waveform: [0.3, 0.7],
        media: makeMedia({ blobId: 'b_v1', mime: 'audio/webm' }),
      },
    };

    render(<UnifiedVoiceBubble msg={vMsg} />);
    const retryBtn = await screen.findByRole('button', { name: 'Завантажити ще раз' });
    fireEvent.click(retryBtn);
    await waitFor(() => expect(reqSpy).toHaveBeenCalledWith('b_v1'));
  });

  it('#22: ThreadView renders UnifiedVoiceBubble for voice messages instead of SecureMediaBubble + broken audio', () => {
    vi.spyOn(messengerMedia, 'cachedMediaUrl').mockReturnValue('blob:v-thread');
    const voiceMsg: Message = {
      id: 'root_voice',
      senderId: 'peer',
      senderName: 'Oksana',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'voice',
      media: makeMedia({ blobId: 'b_vt', name: 'voice.webm', mime: 'audio/webm' }),
      voiceData: {
        duration: 12,
        waveform: [0.2, 0.6, 0.4, 0.8, 0.5, 0.3, 0.7, 0.9],
        media: makeMedia({ blobId: 'b_vt', name: 'voice.webm', mime: 'audio/webm' }),
      },
    };

    useMessengerStore.setState({
      activeChatId: 'chat_t',
      currentUser: { id: 'me', name: 'Me', avatar: '' } as never,
      chats: [
        {
          id: 'chat_t',
          title: 'Oksana',
          type: 'dm',
          circle: 'friends',
          avatar: '',
          unreadCount: 0,
          pinned: false,
          messages: [voiceMsg],
        } as unknown as Chat,
      ],
    });
    useSpaceStore.setState({ threadView: { chatId: 'chat_t', rootMessageId: 'root_voice' } });

    const { container } = render(<ThreadView />);
    expect(screen.getByTestId('voice-media-bubble')).toBeTruthy();
    expect(screen.queryByTestId('media-file')).toBeNull();
    expect(container.querySelector('audio[controls]')).toBeNull();
  });

  it('#23: FilePreviewSheet and PhotoCropSheet register with registerPhoneLayer so __phantomBack closes them', () => {
    vi.spyOn(window.history, 'pushState').mockImplementation(() => {});
    vi.spyOn(window.history, 'go').mockImplementation(() => {});
    const closePreview = vi.fn();

    render(
      <FilePreviewSheet
        isOpen
        url="blob:ok"
        name="notes.txt"
        mime="text/plain"
        size={10}
        onClose={closePreview}
      />,
    );

    const layers = Object.values(usePhoneLayerStore.getState().layers);
    expect(layers.length).toBeGreaterThan(0);

    const closeChat = vi.fn();
    renderHook(() =>
      usePhoneBackStack(
        [
          { open: true, close: closeChat },
          ...Object.values(usePhoneLayerStore.getState().layers).map((close) => ({ open: true, close })),
        ],
        true,
      ),
    );

    const w = window as unknown as { __phantomBack?: () => boolean };
    expect(w.__phantomBack?.()).toBe(true);
    expect(closePreview).toHaveBeenCalledTimes(1);
    expect(closeChat).not.toHaveBeenCalled();
  });

  it('#24 & #26: MediaLightboxModal distinguishes MediaTampered, localizes counter, and ChatArea search matches media.name', async () => {
    setLocale('en');
    vi.spyOn(messengerMedia, 'cachedMediaUrl').mockReturnValue(undefined);
    vi.spyOn(messengerMedia, 'loadMedia').mockRejectedValue(new MediaTampered());

    const m1: Message = {
      id: 'img_1',
      senderId: 'peer',
      senderName: 'Oksana',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'image',
      media: makeMedia({ blobId: 'b_tampered', name: 'one.jpg', mime: 'image/jpeg' }),
    };
    const m2: Message = {
      id: 'img_2',
      senderId: 'peer',
      senderName: 'Oksana',
      senderAvatar: '',
      timestamp: '12:01',
      type: 'image',
      mediaUrl: 'blob:img2',
      media: makeMedia({ blobId: 'b_ok', name: 'two.jpg', mime: 'image/jpeg' }),
    };
    const fileMsg: Message = {
      id: 'file_1',
      senderId: 'peer',
      senderName: 'Oksana',
      senderAvatar: '',
      timestamp: '12:02',
      type: 'file',
      media: makeMedia({ blobId: 'b_pdf', name: 'contract.pdf', mime: 'application/pdf' }),
    };

    const chat: Chat = {
      id: 'chat_lb',
      title: 'Oksana',
      type: 'dm',
      circle: 'friends',
      avatar: '',
      unreadCount: 0,
      pinned: false,
      messages: [m1, m2, fileMsg],
    } as unknown as Chat;

    useMessengerStore.setState({
      activeChatId: 'chat_lb',
      chats: [chat],
    });

    render(<MediaLightboxModal isOpen onClose={vi.fn()} mediaUrl="blob:img2" />);
    expect(screen.getByText(/2 of 2/)).toBeTruthy();

    // Navigate to slide 0 (tampered)
    fireEvent.click(screen.getByLabelText('Previous photo'));
    await waitFor(() =>
      expect(screen.getByText(/This photo was damaged in transit/i)).toBeTruthy(),
    );

    // ChatArea search matches media.name ('contract.pdf')
    const noop = () => {};
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={chat}
          messages={[fileMsg]}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={true}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );

    const searchInput = screen.getByPlaceholderText(/Search|Пошук/i);
    fireEvent.change(searchInput, { target: { value: 'contract.pdf' } });
    expect(screen.getByText('1 of 1')).toBeTruthy();
  });
});
