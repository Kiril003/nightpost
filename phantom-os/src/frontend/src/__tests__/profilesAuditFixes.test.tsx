import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MeScreen } from '../components/messenger/MeScreen';
import { UserProfileModal } from '../components/messenger/UserProfileModal';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { QuietNightsCard } from '../components/messenger/QuietNights';
import { Avatar } from '../components/messenger/kit/Avatar';
import { MomentComposer } from '../components/messenger/MomentsStrip';
import { MomentViewer, momentPeople } from '../components/messenger/MomentViewer';
import { DeskSettings } from '../components/shell/DeskSettings';
import { useMessengerStore } from '../stores/messengerStore';
import { useAuthStore } from '../stores/authStore';
import { useSettingsStore, LANGUAGE_SETTING_KEY } from '../stores/settingsStore';
import { loadProfiles, startProfiles, useProfiles, templateDefaults, PROFILES_KEY } from '../stores/profilesStore';
import { useMoments } from '../services/moments';
import { messengerApi } from '../services/messengerApi';
import { storagePersistence } from '../services/storagePersistence';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { deviceApi } from '../services/deviceApi';
import { whereApi } from '../services/peerWhere';
import * as api from '../services/api';
import QRCode from 'qrcode';
import type { Chat, UserProfile } from '../types/messenger';

const JPEG_B64 = ((): string => {
  const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);
  let s = '';
  bytes.forEach((b) => {
    s += String.fromCharCode(b);
  });
  return btoa(s);
})();

const DATA_JPEG = `data:image/jpeg;base64,${JPEG_B64}`;

describe('profiles logic audit fixes', () => {
  let stopProfiles: (() => void) | undefined;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('nightpost.moments.audienceChosen', '1');
    document.documentElement.lang = 'uk';
    useSettingsStore.setState((s) => ({
      ...s,
      loaded: true,
      values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' },
    }));
    useProfiles.setState(loadProfiles());
  });

  afterEach(() => {
    stopProfiles?.();
    stopProfiles = undefined;
    cleanup();
    vi.restoreAllMocks();
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('Finding #1: MeScreen registers profiles opener so window.__phantomProfiles.open() opens the profile switcher', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('away'));
    vi.spyOn(messengerApi, 'quiet').mockRejectedValue(new Error('no'));
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);

    stopProfiles = startProfiles();
    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);

    const win = window as unknown as { __phantomProfiles?: { open: () => boolean; setActive: (id: string) => boolean } };
    let opened = false;
    act(() => {
      opened = Boolean(win.__phantomProfiles?.open());
    });
    expect(opened).toBe(true);
    const sheet = await screen.findByTestId('profiles-sheet');
    expect(sheet).toBeTruthy();
  });

  it('Finding #2: hydrateFromNode calls messengerApi.identity() once and preserves saved data: avatar', async () => {
    useMessengerStore.setState((s) => ({
      ...s,
      hydrated: false,
      currentUser: { ...s.currentUser, id: 'me', name: 'Олена', avatar: DATA_JPEG },
    }));
    const idSpy = vi.spyOn(messengerApi, 'identity').mockResolvedValue({
      node_id: 'node_1',
      bundle: { name: 'Олена' },
      compact: 'x',
      max_attachment_bytes: 8 * 1024 * 1024,
    } as never);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);

    await useMessengerStore.getState().hydrateFromNode();

    expect(idSpy).toHaveBeenCalledTimes(1);
    expect(useMessengerStore.getState().currentUser.avatar).toBe(DATA_JPEG);
  });

  it('Finding #6: MeScreen saveName persists updated name via storagePersistence.saveUserProfile', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('away'));
    vi.spyOn(messengerApi, 'quiet').mockRejectedValue(new Error('no'));
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'renameSelf').mockResolvedValue({ bundle: { name: 'Оксана' } } as never);
    const saveSpy = vi.spyOn(storagePersistence, 'saveUserProfile').mockResolvedValue(undefined as never);

    useMessengerStore.setState((s) => ({
      currentUser: { ...s.currentUser, id: 'me', name: 'Nightpost-A52', avatar: '' },
    }));

    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
    fireEvent.click(screen.getByTestId('me-ask-name'));
    const input = screen.getByLabelText('Ваше імʼя');
    fireEvent.change(input, { target: { value: 'Оксана' } });
    fireEvent.submit(input.closest('form')!);

    await waitFor(() => expect(saveSpy).toHaveBeenCalledWith(expect.objectContaining({ name: 'Оксана' })));
  });

  it('Finding #7: UserProfileModal resets avatarThumbB64 across close/reopen so cancelling clear does not wipe avatar later', async () => {
    vi.spyOn(messengerApi, 'identity').mockRejectedValue(new Error('offline'));
    vi.spyOn(storagePersistence, 'saveUserProfile').mockResolvedValue(undefined as never);
    const pubSpy = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue({
      rev: 2,
      frame_bytes: 100,
      sent: ['p1'],
      sent_count: 1,
      skipped: [],
      skipped_count: 0,
    });

    const user: UserProfile = {
      id: 'me',
      name: 'Олена',
      handle: '@olena',
      avatar: DATA_JPEG,
      status: 'Тут',
      statusEmoji: '',
      bio: '',
      phone: '',
      locationName: '',
      batteryLevel: 100,
      isMutedAlerts: false,
    };

    const { rerender } = render(<UserProfileModal isOpen={true} onClose={vi.fn()} currentUser={user} />);
    fireEvent.click(screen.getByTestId('profile-avatar-clear'));

    rerender(<UserProfileModal isOpen={false} onClose={vi.fn()} currentUser={user} />);
    rerender(<UserProfileModal isOpen={true} onClose={vi.fn()} currentUser={user} />);

    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    await waitFor(() => expect(pubSpy).toHaveBeenCalledTimes(1));
    expect(pubSpy.mock.calls[0][0].avatar).toBe(JPEG_B64);
  });

  it('Finding #8: PhotoCropSheet in UserProfileModal defers publishProfile until Save is clicked', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ bundle: { name: 'Олена' } } as never);
    const pubSpy = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue({
      rev: 1,
      frame_bytes: 100,
      sent: [],
      sent_count: 0,
      skipped: [],
      skipped_count: 0,
    });
    const onUpdate = vi.fn();

    const user: UserProfile = {
      id: 'me',
      name: 'Олена',
      handle: '@olena',
      avatar: '',
      status: '',
      statusEmoji: '',
      bio: '',
      phone: '',
      locationName: '',
      batteryLevel: 100,
      isMutedAlerts: false,
    };

    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:crop-test', revokeObjectURL: () => {} });
    class FakeImage {
      onload: (() => void) | null = null;
      naturalWidth = 256;
      naturalHeight = 256;
      width = 256;
      height = 256;
      private s = '';
      get src() {
        return this.s;
      }
      set src(v: string) {
        this.s = v;
        setTimeout(() => this.onload?.(), 0);
      }
    }
    vi.stubGlobal('Image', FakeImage);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      clearRect: () => {},
      drawImage: () => {},
    } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(DATA_JPEG);

    const { rerender } = render(
      <UserProfileModal isOpen={true} onClose={vi.fn()} currentUser={user} onUpdateCurrentUser={onUpdate} />,
    );
    const fileInput = document.querySelector('[data-testid="profile-sheet"] input[type="file"]') as HTMLInputElement;
    fireEvent.change(fileInput, { target: { files: [new File(['x'], 'a.jpg', { type: 'image/jpeg' })] } });

    const doneBtn = await screen.findByTestId('photo-crop-done');
    fireEvent.click(doneBtn);

    await waitFor(() => expect(screen.queryByTestId('photo-crop')).toBeNull());
    expect(pubSpy).not.toHaveBeenCalled();
    expect(onUpdate).not.toHaveBeenCalled();

    rerender(<UserProfileModal isOpen={false} onClose={vi.fn()} currentUser={user} onUpdateCurrentUser={onUpdate} />);
    expect(pubSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('Finding #12: PeerProfileSheet renders peer-between-moments from momentHighlights when kept moments exist', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(whereApi, 'get').mockRejectedValue(new Error('offline'));
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/messenger/moments/highlights/peer-1') {
        return [
          {
            id: 'kept-1',
            from_id: 'peer-1',
            from_name: 'Оксана',
            mine: false,
            at_ms: Date.now() - 10_000,
            until_ms: 0,
            keep: true,
            audience: { kind: 'contacts' },
            text: 'Ранок у горах',
            seen: true,
            to_count: 1,
            media: [],
          },
        ] as never;
      }
      return [] as never;
    });

    useMessengerStore.setState((s) => ({
      currentUser: { ...s.currentUser, id: 'me', name: 'Іван' },
      chats: [{ id: 'chat-1', title: 'Оксана', type: 'dm', peerNodeId: 'peer-1', messages: [], unreadCount: 0 } as unknown as Chat],
    }));

    render(<PeerProfileSheet inline chatId="chat-1" peerNodeId="peer-1" name="Оксана" onClose={vi.fn()} />);
    const momentsRow = await screen.findByTestId('peer-between-moments');
    expect(momentsRow.textContent).toContain('1');
  });

  it('Finding #13: DeskSettings <Me /> updates useMessengerStore.currentUser.name so <Account /> shows the new name', async () => {
    useAuthStore.setState({
      user: {
        id: 'u1',
        username: 'phantom',
        role: 'ROOT' as never,
        preferences: { display_name: 'Старе' } as never,
      } as never,
    });
    useMessengerStore.setState((s) => ({
      currentUser: { ...s.currentUser, id: 'node-1', name: 'Без імені', avatar: '' },
    }));
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('away'));
    vi.spyOn(api.authApi, 'patchMe').mockResolvedValue({
      id: 'u1',
      username: 'phantom',
      preferences: { display_name: 'Кирило' },
    } as never);
    vi.spyOn(storagePersistence, 'saveUserProfile').mockResolvedValue(undefined as never);

    render(
      <MemoryRouter>
        <DeskSettings />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText('Як до вас звертатись');
    fireEvent.change(input, { target: { value: 'Кирило' } });
    fireEvent.blur(input);

    await waitFor(() => expect(screen.getByTestId('desk-account').textContent).toContain('Кирило'));
  });

  it('Finding #14: leaving out Work under All contacts does not switch sticky default reach to people on reopen', async () => {
    let savedPrefs: Record<string, unknown> = {
      audience: { kind: 'contacts' },
      to: [],
      keep: false,
      everyone: false,
      muted: [],
    };
    vi.spyOn(api, 'request').mockImplementation(async (method: string, path: string, body?: unknown) => {
      if (path === '/messenger/moments/prefs' && method === 'GET') {
        return savedPrefs as never;
      }
      if (path === '/messenger/moments' && method === 'POST') {
        const b = body as { text: string; audience: { kind: string }; to?: string[]; keep: boolean };
        savedPrefs = {
          audience: b.audience,
          to: b.to ?? [],
          keep: Boolean(b.keep),
          everyone: false,
          muted: [],
        };
        return {
          id: 'm1',
          from_id: 'me',
          from_name: '',
          mine: true,
          at_ms: Date.now(),
          until_ms: Date.now() + 86_400_000,
          keep: false,
          audience: b.audience,
          text: b.text,
          seen: true,
          to_count: 1,
          media: [],
        } as never;
      }
      return {} as never;
    });

    const chats: Chat[] = [
      { id: 'c1', title: 'Олена', type: 'dm', peerNodeId: 'olena', circle: 'close', messages: [], unreadCount: 0 } as unknown as Chat,
      { id: 'c2', title: 'Тарас', type: 'dm', peerNodeId: 'taras', circle: 'work', messages: [], unreadCount: 0 } as unknown as Chat,
    ];
    useMoments.setState({ list: [], prefs: null });

    const { unmount } = render(
      <MomentComposer file={null} people={momentPeople(chats)} chats={chats} onClose={vi.fn()} />,
    );
    const sheet1 = await screen.findByRole('dialog');
    fireEvent.click(sheet1.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-leave-work"]')).not.toBeNull());
    fireEvent.click(document.querySelector('[data-testid="aud-leave-work"]')!);
    fireEvent.change(sheet1.querySelector('[data-moment-caption]')!, { target: { value: 'Привіт' } });
    fireEvent.click(sheet1.querySelector('[data-moment-share]')!);
    await waitFor(() => expect(savedPrefs.to).toEqual(['olena']));
    unmount();

    render(<MomentComposer file={null} people={momentPeople(chats)} chats={chats} onClose={vi.fn()} />);
    const sheet2 = await screen.findByRole('dialog');
    fireEvent.click(sheet2.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-contacts"]')?.getAttribute('aria-checked')).toBe('true'));
    expect(document.querySelector('[data-testid="aud-people"]')?.getAttribute('aria-checked')).toBe('false');
  });

  it('Finding #18: Avatar resets broken state when src changes after an error', () => {
    const { rerender } = render(<Avatar src="https://bad.invalid/1.jpg" name="Олена" />);
    const img1 = screen.getByRole('img');
    fireEvent.error(img1);
    expect(screen.queryByRole('img')).toBeNull();

    rerender(<Avatar src={DATA_JPEG} name="Олена" />);
    expect(screen.getByRole('img').getAttribute('src')).toBe(DATA_JPEG);
  });

  it('Finding #20 & #21: untouched personal profile updates label on locale switch and startProfiles sweeps on visibilitychange', () => {
    const now = Date.now();
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'p-personal',
        profiles: [
          { ...templateDefaults('personal', now - 10_000), id: 'p-personal', name: 'Особисте', passGroups: [] },
          { ...templateDefaults('temp', now - 10_000), id: 'p-temp', name: 'Тимчасовий', expiresAt: now - 100 },
        ],
      }),
    );
    useProfiles.setState(loadProfiles(false));
    useSettingsStore.setState((s) => ({
      ...s,
      values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' },
    }));
    expect(useProfiles.getState().profiles.find((p) => p.id === 'p-personal')?.name).toBe('Personal');

    stopProfiles = startProfiles();
    useProfiles.setState((s) => ({
      ...s,
      profiles: [
        ...s.profiles,
        { ...templateDefaults('temp', now - 10_000), id: 'p-temp-late', name: 'Temp', expiresAt: Date.now() - 50 },
      ],
    }));
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(useProfiles.getState().profiles.some((p) => p.id === 'p-temp-late')).toBe(false);
  });

  it('Finding #22: desktop FindMe fetches PH2 invite via phoneInvite when identity has no PH2 link', async () => {
    vi.spyOn(QRCode, 'toDataURL').mockResolvedValue('data:image/png;base64,AA==' as never);
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({
      node_id: 'n1',
      bundle: { name: 'Олена' },
      compact: 'eyJlegacy',
    } as never);
    const phoneInviteSpy = vi
      .spyOn(messengerApi as unknown as { phoneInvite: (n?: string) => Promise<{ code: string }> }, 'phoneInvite')
      .mockResolvedValue({ code: 'PH2:b2xlbmE:999' });

    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('away'));
    vi.spyOn(messengerApi, 'quiet').mockRejectedValue(new Error('no'));
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);

    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
    fireEvent.click(screen.getByTestId('me-code'));
    await screen.findByTestId('me-qr-full');
    expect(phoneInviteSpy).toHaveBeenCalled();
    expect(vi.mocked(QRCode.toDataURL).mock.calls.at(-1)?.[0]).toBe('PH2:b2xlbmE:999');
  });

  it('Finding #23 & #24: momentPeople deduplicates by peerNodeId and MomentViewer renders own avatar', () => {
    const dupChats: Chat[] = [
      { id: 'c1', title: 'Анна', type: 'dm', peerNodeId: 'peer-1', messages: [], unreadCount: 0 } as unknown as Chat,
      { id: 'c2', title: 'Анна 2', type: 'dm', peerNodeId: 'peer-1', messages: [], unreadCount: 0 } as unknown as Chat,
    ];
    expect(momentPeople(dupChats)).toHaveLength(1);

    useMessengerStore.setState((s) => ({
      currentUser: { ...s.currentUser, id: 'me', name: 'Олег', avatar: DATA_JPEG },
    }));
    render(
      <MomentViewer
        moments={[
          {
            id: 'm-own',
            fromId: 'me',
            fromName: 'Олег',
            mine: true,
            atMs: Date.now(),
            editedMs: 0,
            untilMs: Date.now() + 60_000,
            keep: false,
            reach: 'contacts',
            circles: [],
            text: 'Привіт',
            seen: true,
            toCount: 2,
            media: [],
            viewers: [],
            reactions: {},
            myReaction: '',
          },
        ]}
        startId="m-own"
        nameOf={() => 'Ви'}
        avatarOf={() => undefined}
        chatOf={() => undefined}
        onOpenChat={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    const viewer = screen.getByRole('dialog');
    expect(viewer.querySelector(`img[src="${DATA_JPEG}"]`)).not.toBeNull();
  });

  it('Finding #25 & #26: MeScreen shows both status and bio when both are present, and renders personal circle', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('away'));
    vi.spyOn(messengerApi, 'quiet').mockRejectedValue(new Error('no'));
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);

    useMessengerStore.setState((s) => ({
      currentUser: { ...s.currentUser, id: 'me', name: 'Олена', status: 'На звʼязку', bio: 'Інженер', avatar: '' },
      chats: [
        { id: 'c1', title: 'Мама', type: 'dm', peerNodeId: 'p1', circle: 'personal', circles: ['personal'], messages: [], unreadCount: 0 } as unknown as Chat,
      ],
    }));

    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
    const me = screen.getByTestId('me-screen');
    expect(me.textContent).toContain('На звʼязку');
    expect(me.textContent).toContain('Інженер');
    expect(screen.getByTestId('me-circles').textContent).toContain('Особисте');
  });

  it('Finding #27 & #28: QuietNightsCard excludes own paired devices and PeerProfileSheet renders nameless silhouette for Без імені', async () => {
    vi.spyOn(messengerApi, 'quiet').mockResolvedValue({
      enabled: true,
      from: '23:00',
      to: '07:00',
      except: [],
    });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      { id: '1', peer_node_id: 'p1', display_name: 'Ноутбук · 9b10', mine: true } as never,
      { id: '2', peer_node_id: 'p2', display_name: 'Марія', mine: false, photo: DATA_JPEG } as never,
    ]);

    render(<QuietNightsCard />);
    fireEvent.click(await screen.findByTestId('me-quiet-open'));
    const sheet = await screen.findByTestId('quiet-sheet');
    expect(sheet.textContent).not.toContain('Ноутбук · 9b10');
    expect(sheet.textContent).toContain('Марія');
    expect(sheet.querySelector(`img[src="${DATA_JPEG}"]`)).not.toBeNull();

    cleanup();
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    render(<PeerProfileSheet inline peerNodeId="peer-nameless" name="Без імені" onClose={vi.fn()} />);
    const poster = screen.getByTestId('peer-profile-poster');
    expect(poster.querySelector('[data-nameless]')).not.toBeNull();
  });
});
