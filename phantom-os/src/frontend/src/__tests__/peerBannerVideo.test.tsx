/**
 * T53/T63: відео-банер у чужому профілі. Доти людям їхав лише перший кадр.
 *
 * Червоніти вміє: приберіть прохання `pullBannerVideo` — падає «просить і
 * грає»; приберіть перевірку мережі — падає «мобільні дані»; покажіть відео
 * одразу, без `playing`, — падає «перший кадр до відтворення».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi } from '../services/messengerApi';
import type { NodeContact, PeerBannerRow } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const net = vi.hoisted(() => ({ value: { net: 'wifi' as 'wifi' | 'mobile', saveData: false } }));
vi.mock('../services/autoFetch', async (orig) => ({
  ...(await orig<typeof import('../services/autoFetch')>()),
  readNet: () => net.value,
}));

const PEER = 'b1b2c3d4e5f60718';
const BLOB = 'ab'.repeat(16);
const STILL = `/webmsgr/_banners/${'a'.repeat(32)}`;

const banner = (ready: boolean): PeerBannerRow => ({
  video_still: true,
  items: [{ url: STILL, fx: 128, fy: 128 }],
  video: { blob_id: BLOB, size: 3_000_000, ready },
});

const contact = (b: PeerBannerRow): NodeContact => ({
  id: 'ct_2',
  peer_node_id: PEER,
  display_name: 'Олег Бондар',
  peer_address: null,
  safety_number: '1'.repeat(30),
  safety_number_pretty: '11111 11111 11111 11111 11111 11111',
  verified: true,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
  banner_state: 'set',
  banner: b,
});

const open = () => {
  useMessengerStore.setState({
    chats: [{ id: 'conv_o', title: 'Олег Бондар', type: 'dm', peerNodeId: PEER, messages: [], muted: false } as unknown as Chat],
  });
  return render(<PeerProfileSheet peerNodeId={PEER} name="Олег Бондар" chatId="conv_o" onClose={() => {}} />);
};

let fetchMock: ReturnType<typeof vi.fn>;
const blobFetches = () => fetchMock.mock.calls.filter((c) => String(c[0]).includes(BLOB)).length;

beforeEach(() => {
  net.value = { net: 'wifi', saveData: false };
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  fetchMock = vi.fn(async () => new Response(new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112]), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('відео-банер людини', () => {
  it('просить петлю, складену телефоном бере з _files і грає поверх першого кадру', async () => {
    const pull = vi.spyOn(messengerApi, 'pullBannerVideo').mockResolvedValue({ state: 'asked' });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(banner(false))]);
    open();
    const video = (await screen.findByTestId('peer-banner-video')) as HTMLVideoElement;
    expect(pull).toHaveBeenCalledWith(PEER);
    expect(blobFetches()).toBeGreaterThan(0);
    expect(video.src).toMatch(/^blob:/);
    expect(video.muted).toBe(true);
    expect(video.loop).toBe(true);
    expect(screen.getAllByTestId('peer-banner-photo')[0].getAttribute('src')).toBe(STILL);
  });

  it('перший кадр до відтворення: відео невидиме, доки не заграло', async () => {
    vi.spyOn(messengerApi, 'pullBannerVideo').mockResolvedValue({ state: 'ready' });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(banner(true))]);
    open();
    const video = await screen.findByTestId('peer-banner-video');
    expect(video.className).toContain('opacity-0');
    fireEvent.playing(video);
    expect(video.className).toContain('opacity-100');
  });

  it('уже складена петля — без прохання', async () => {
    const pull = vi.spyOn(messengerApi, 'pullBannerVideo').mockResolvedValue({ state: 'ready' });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(banner(true))]);
    open();
    await screen.findByTestId('peer-banner-video');
    expect(pull).not.toHaveBeenCalled();
  });

  it('мобільні дані чи економія трафіку — лише перший кадр, нічого не просимо', async () => {
    for (const value of [{ net: 'mobile' as const, saveData: false }, { net: 'wifi' as const, saveData: true }]) {
      net.value = value;
      const pull = vi.spyOn(messengerApi, 'pullBannerVideo').mockResolvedValue({ state: 'asked' });
      vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(banner(false))]);
      const { unmount } = open();
      await waitFor(() => expect(screen.getByTestId('peer-banner')).toBeTruthy());
      await new Promise((r) => setTimeout(r, 0));
      expect(screen.queryByTestId('peer-banner-video')).toBeNull();
      expect(pull).not.toHaveBeenCalled();
      expect(blobFetches()).toBe(0);
      unmount();
      vi.restoreAllMocks();
      vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
      vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    }
  });
});
