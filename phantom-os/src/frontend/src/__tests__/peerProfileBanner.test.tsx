/**
 * Банер на сторінці людини (profile-r1 P3).
 *
 * Червоніти вміє: приберіть гілку `bannerShown` у PeerProfileSheet — падає
 * «банер над аватаром»; покажіть фото в дорозі сірою заглушкою — падає
 * «в дорозі — порожниста крапка»; приберіть перевірку адреси — падає «чужа адреса».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { bannerShown, focalPosition } from '../components/messenger/BannerCarousel';
import { messengerApi } from '../services/messengerApi';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { NodeContact, PeerBannerRow } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const PEER = 'b1b2c3d4e5f60718';
const U = (h: string) => `/webmsgr/_banners/${h.repeat(32)}`;

const contact = (banner: PeerBannerRow | null, state: 'set' | 'cleared' | 'absent' = 'set'): NodeContact => ({
  id: 'ct_2',
  peer_node_id: PEER,
  display_name: 'Олег Бондар',
  peer_address: null,
  safety_number: '1'.repeat(30),
  safety_number_pretty: '11111 11111 11111 11111 11111 11111',
  verified: true,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
  banner_state: state,
  banner,
});

const open = () => {
  useMessengerStore.setState({
    chats: [{ id: 'conv_o', title: 'Олег Бондар', type: 'dm', peerNodeId: PEER, messages: [], muted: false } as unknown as Chat],
  });
  return render(<PeerProfileSheet peerNodeId={PEER} name="Олег Бондар" chatId="conv_o" onClose={() => {}} />);
};

beforeEach(() => {
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
});
afterEach(() => vi.restoreAllMocks());

describe('банер людини', () => {
  it('банер над аватаром: фото з фокусом, крапки, без лічильника коли все приїхало', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      contact({ video_still: false, items: [{ url: U('a'), fx: 128, fy: 77 }, { url: U('b'), fx: 0, fy: 255 }] }),
    ]);
    open();
    await waitFor(() => expect(screen.getByTestId('peer-banner')).toBeTruthy());
    const photos = screen.getAllByTestId('peer-banner-photo') as HTMLImageElement[];
    expect(photos).toHaveLength(2);
    expect(photos[0].style.objectPosition).toBe('50% 30%');
    expect(screen.getByTestId('peer-banner-dots').querySelectorAll('i')).toHaveLength(2);
    expect(screen.queryByTestId('peer-banner-count')).toBeNull();
    expect(screen.getByTestId('peer-profile-poster').hasAttribute('data-banner')).toBe(true);
  });

  it('в дорозі — порожниста крапка і «2/6», без заглушки', async () => {
    const items = [U('a'), U('b'), null, null, null, null].map((url) => ({ url, fx: 128, fy: 128 }));
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ video_still: false, items })]);
    open();
    await waitFor(() => expect(screen.getByTestId('peer-banner-count').textContent).toBe('2/6'));
    expect(screen.getAllByTestId('peer-banner-photo')).toHaveLength(2);
    expect(screen.getByTestId('peer-banner-dots').querySelectorAll('i[data-wait]')).toHaveLength(4);
  });

  it('банера немає або прибрано — жодного місця під нього', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(null, 'cleared')]);
    open();
    await waitFor(() => expect(screen.getByTestId('peer-profile-name')).toBeTruthy());
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByTestId('peer-banner')).toBeNull();
    expect(screen.getByTestId('peer-profile-poster').hasAttribute('data-banner')).toBe(false);
  });

  it('чужа адреса фото не показується', () => {
    expect(bannerShown({ video_still: false, items: [{ url: 'https://evil.example/x.jpg', fx: 0, fy: 0 }] })).toBe(false);
    expect(bannerShown({ video_still: false, items: [{ url: null, fx: 0, fy: 0 }] })).toBe(false);
    expect(bannerShown({ video_still: false, items: [{ url: '//evil.example/x.jpg', fx: 0, fy: 0 }] })).toBe(false);
    expect(bannerShown({ video_still: false, items: [{ url: U('c'), fx: 0, fy: 0 }] })).toBe(true);
    expect(focalPosition(255, 0)).toBe('100% 0%');
  });
});

describe('банер людини: досяжність', () => {
  const src = (p: string) => readFileSync(join(process.cwd(), 'src/components/messenger', p), 'utf8');
  it('шапка розмови й картка людини відкривають сторінку, а сторінка малює банер', () => {
    for (const p of ['Header.tsx', 'cards/PersonCard.tsx']) {
      expect(src(p)).toMatch(/import \{ PeerProfileSheet \} from '\.\.?\/(\.\.\/)?PeerProfileSheet'/);
      expect(src(p)).toContain('<PeerProfileSheet');
    }
    const sheet = src('PeerProfileSheet.tsx');
    expect(sheet).toContain("from './BannerCarousel'");
    expect(sheet).toContain('<BannerCarousel');
  });
});
