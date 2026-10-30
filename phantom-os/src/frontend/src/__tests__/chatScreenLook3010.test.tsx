import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Camera, FileText } from 'lucide-react';
import { DEFAULT_LOOK, normalizeLook, useLook } from '../services/appearance';
import { ChatWallpaper, useHasWallpaper } from '../components/messenger/ChatWallpaper';
import { Header } from '../components/messenger/Header';
import { AttachSheet, tileTone } from '../components/messenger/AttachSheet';
import type { Chat, UserProfile } from '../types/messenger';
import type { Message } from '../types/messenger';
import { useMessengerStore } from '../stores/messengerStore';
import { ChatArea } from '../components/messenger/ChatArea';

const read = (p: string) => readFileSync(join(process.cwd(), p), 'utf8');
const noop = () => {};
const CHAT = { id: 'chat_o', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: 0, messages: [] } as unknown as Chat;
const kindMsg = (over: Partial<Message>, id: string): Message =>
  ({ id, senderId: 'p1', senderName: 'Оксана', senderAvatar: '', timestamp: '12:40', type: 'text', isSelf: false, ...over }) as unknown as Message;
const renderChat = (messages: Message[]) => {
  useMessengerStore.setState({ chats: [{ ...CHAT, messages }], activeChatId: CHAT.id, currentUser: { id: 'me', name: 'Кирило', avatar: '' } } as never);
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={{ ...CHAT, messages }} messages={messages} currentUserId="me"
        onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
        selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
      />
    </MemoryRouter>,
  );
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(() => {
  cleanup();
  useLook.setState({ look: DEFAULT_LOOK });
});

describe('екран розмови 30.10 — стіна D, а не рівне полотно', () => {
  it('поки людина нічого не обирала, під стрічкою стіна з точками на токенах D', () => {
    render(<ChatWallpaper chatId="any" />);
    const layer = screen.getByTestId('chat-wallpaper');
    expect(layer.dataset.kind).toBe('wall');
    expect(layer.className).toContain('ph-wall');
    expect(layer.querySelector('.ph-wall-dots')).not.toBeNull();
    expect(renderHook(() => useHasWallpaper('any')).result.current).toBe(true);
  });

  it('свідомо обране «Без тла» лишається рівним', () => {
    useLook.setState({ look: normalizeLook({ wallpaper: { kind: 'none' }, wallpaperPicked: true }) });
    render(<ChatWallpaper chatId="any" />);
    expect(screen.queryByTestId('chat-wallpaper')).toBeNull();
    expect(renderHook(() => useHasWallpaper('any')).result.current).toBe(false);
  });

  it('стіна і точки беруть лише --ph-wall і --ph-wall-dot', () => {
    const css = read('src/styles/wallpaper.css');
    expect(css).toMatch(/\.ph-wall \{[^}]*background-color: var\(--ph-wall\)/);
    expect(css).toMatch(/\.ph-wall-dots \{[^}]*background-color: var\(--ph-wall-dot\)/);
  });
});

describe('екран розмови 30.10 — знак «Збереженого» і плитки вкладень', () => {
  it('знак «Збереженого» в шапці 40 px з іконкою 24 px, як аватар людини', () => {
    const { container } = render(
      <MemoryRouter>
        <Header
          currentChat={{ id: 'chat_saved_notes', title: 'Збережене', avatar: '', type: 'saved', circle: 'all', pinned: false, unreadCount: 0, messages: [] } as unknown as Chat}
          currentUser={{ id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile}
          onOpenSettings={noop} onOpenGroupDetails={noop} isSoundEnabled onToggleSound={noop}
          onToggleSearch={noop} isSearching={false} onBack={noop}
        />
      </MemoryRouter>,
    );
    const mark = container.querySelector('[data-header-saved]') as HTMLElement;
    expect(mark.className).toContain('w-10');
    expect(mark.className).toContain('h-10');
    expect(mark.querySelector('svg')?.getAttribute('class')).toContain('w-6');
  });

  it('кожна плитка вкладень залита своїм тоном людини, а не беж на бежі', () => {
    render(
      <AttachSheet
        tiles={[
          { icon: Camera, label: 'Камера', testId: 'send-camera', pick: noop },
          { icon: FileText, label: 'Файл', testId: 'send-file', pick: noop },
        ]}
        moreTiles={[]}
        onClose={noop}
      />,
    );
    for (const id of ['send-camera', 'send-file']) {
      const face = screen.getByTestId(id).querySelector('[data-kit-tile-face]') as HTMLElement;
      const n = face.style.backgroundColor.match(/^var\(--ph-person-([0-5])\)$/)?.[1];
      expect(n, id).toBeTruthy();
      expect(face.style.color).toBe(`var(--ph-person-${n}-ink)`);
      expect(face.className).not.toContain('bg-overlay');
    }
    expect(tileTone('send-camera', 0)).not.toBe(tileTone('send-file', 1));
    expect(tileTone(undefined, 7)).toBe(1);
  });
});

describe('екран розмови 30.10 — те, що людина помічає першим', () => {
  it('голосове, що не відкрилось, — один тихий рядок і значок «ще раз», без червоного', async () => {
    renderChat([kindMsg({ type: 'voice', text: '', voiceData: { duration: 12, waveform: [0.2, 0.6] } } as never, 'v1')]);
    const line = await waitFor(() => {
      const el = document.querySelector('[data-msg-id="v1"] [data-voice-failed]') as HTMLElement;
      expect(el).toBeTruthy();
      return el;
    });
    expect(line.className).toContain('text-ink-3');
    expect(line.className).not.toContain('text-danger');
    const retry = within(line).getByRole('button', { name: 'Завантажити ще раз' });
    expect(retry.textContent).toBe('');
    expect(retry.querySelector('svg')).toBeTruthy();
  });

  it('підпис до фото — звичайним текстом, а не дрібним сірим', () => {
    renderChat([kindMsg({ type: 'image', text: '', imageData: { url: 'data:image/png;base64,AA==', caption: 'Ось місце' } } as never, 'i1')]);
    const cap = document.querySelector('[data-msg-id="i1"] [data-image-caption]') as HTMLElement;
    expect(cap.textContent).toBe('Ось місце');
    expect(cap.className).toContain('text-body');
    expect(cap.className).not.toContain('text-xs');
  });

  it('уночі точки стіни більші, ніж удень, щоб читались фактурою', () => {
    const css = read('src/styles/wallpaper.css');
    const night = css.match(/\[data-theme='night'\] \.ph-wall-dots,[^{]*\{[^}]*\}/)?.[0] ?? '';
    expect(night).toContain("r='2'");
    expect(css).toMatch(/\.ph-wall-dots \{[^}]*r='1\.5'/);
  });
});
