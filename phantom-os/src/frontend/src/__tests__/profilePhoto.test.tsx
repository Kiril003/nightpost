/**
 * Фото профілю наскрізь: телефон віддає адресу фото — її бачать список,
 * шапка, постер; своє фото обирається колом і їде обома розмірами.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { Avatar } from '../components/messenger/Avatar';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { cropOf, clampOffset } from '../components/messenger/PhotoCropSheet';
import { messengerApi, type NodeContact } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { readFaces } from '../services/livingPulse';
import { isFacePhotoUrl, readPeerProfile, avatarSrc } from '../services/peerProfile';
import { openImage, closeImage, profilePhotoFrom, AVATAR_BUDGET_BYTES, PHOTO_BUDGET_BYTES, type DrawAttempt } from '../services/avatarCompress';

const PEER = 'a1b2c3d4e5f60718';
const PHOTO = '/webmsgr/_faces/0123456789abcdef0123456789abcdef?v=7';
const THUMB = 'data:image/webp;base64,UklGRgAAAABXRUJQ';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('адреса фото', () => {
  it('лише адреса свого телефона — фото', () => {
    expect(isFacePhotoUrl(PHOTO)).toBe(true);
    expect(isFacePhotoUrl('/webmsgr/_faces/me?v=3')).toBe(true);
    expect(isFacePhotoUrl('https://evil.example/x.jpg')).toBe(false);
    expect(isFacePhotoUrl('/webmsgr/_faces/../index.html?v=1')).toBe(false);
  });

  it('обличчя в списку — велике фото, а без нього мініатюра', () => {
    const faces = readFaces([
      { peer_node_id: 'a', avatar_state: 'set', avatar_thumb: THUMB, photo: PHOTO },
      { peer_node_id: 'b', avatar_state: 'set', avatar_thumb: THUMB, photo: null },
      { peer_node_id: 'c', avatar_state: 'set', avatar_thumb: THUMB, photo: 'https://evil.example/x.jpg' },
    ]);
    expect(faces.a.avatar).toBe(PHOTO);
    expect(faces.b.avatar).toBe(THUMB);
    expect(faces.c.avatar).toBe(THUMB);
  });

  it('профіль людини бере фото, навіть коли мініатюри ще нема', () => {
    expect(avatarSrc(readPeerProfile({ peer_node_id: PEER, photo: PHOTO }))).toBe(PHOTO);
  });

  it('аватар з фото — це img, а не літера', () => {
    render(<Avatar src={PHOTO} name="Оксана" tone={PEER} />);
    const img = screen.getByRole('img');
    expect(img.getAttribute('src')).toBe(PHOTO);
    expect(screen.queryByText('О')).toBeNull();
  });
});

describe('постер з фото', () => {
  /**
   * 03.10, v1.2: текст ніколи не стоїть на градієнті (DS §1), а кнопка над
   * фото — темна пігулка `.ph-on-photo`, не `bg-black/35`. Імʼя переїхало під
   * знімок на полотно, у фото твердий нижній край. Поверніть затінок чи скло —
   * тест червоніє.
   */
  it('сам знімок без затінку й без великої літери; імʼя поруч, «назад» — у смузі', async () => {
    const contact = {
      id: 'ct_1',
      peer_node_id: PEER,
      display_name: 'Оксана Мельник',
      peer_address: null,
      safety_number: '',
      safety_number_pretty: '',
      verified: true,
      session_ready: true,
      created_at: '2026-09-01T10:00:00',
      avatar_state: 'set',
      avatar_thumb: THUMB,
      photo: PHOTO,
    } as NodeContact;
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана Мельник" onClose={() => {}} />);
    const poster = screen.getByTestId('peer-profile-poster');
    await waitFor(() => expect(poster.querySelector('img')?.getAttribute('src')).toBe(PHOTO));
    expect(screen.queryByTestId('peer-profile-initial')).toBeNull();
    const sheet = screen.getByTestId('peer-profile');
    expect(sheet.querySelector('[class*="gradient"]')).toBeNull();
    expect(sheet.querySelector('[class*="bg-black"]')).toBeNull();
    expect(sheet.querySelector('[class*="text-white"]')).toBeNull();
    // Імʼя — не на фото: поза рамкою знімка.
    const photoFrame = screen.getByTestId('peer-profile-photo');
    expect(photoFrame.contains(screen.getByTestId('peer-profile-name'))).toBe(false);
    expect(photoFrame.parentElement!.contains(screen.getByTestId('peer-profile-name'))).toBe(false);
    // «Назад» — у смузі над обличчям, не на фото.
    expect(photoFrame.contains(screen.getByRole('button', { name: 'Назад' }))).toBe(false);
  });
});

describe('обрізка до кола', () => {
  it('без зсуву й наближення — центральний квадрат', () => {
    const view = 300;
    const base = view / 600;
    const { ox, oy } = clampOffset(800, 600, view, 1, (view - 800 * base) / 2, (view - 600 * base) / 2);
    expect(cropOf(800, 600, view, 1, ox, oy)).toEqual({ sx: 100, sy: 0, side: 600 });
  });

  it('наближення вдвічі — вдвічі менший квадрат, і знімок не виходить за коло', () => {
    const c = cropOf(800, 600, 300, 2, 0, 0);
    expect(c.side).toBe(300);
    expect(clampOffset(800, 600, 300, 2, 50, 50)).toEqual({ ox: 0, oy: 0 });
    expect(clampOffset(800, 600, 300, 1, -9999, -9999)).toEqual({ ox: -100, oy: 0 });
  });

  it('один вибір — два розміри: мініатюра й фото до 512, обидва під стелею', () => {
    const sides: number[] = [];
    const draw: DrawAttempt = (side, mime) => {
      sides.push(side);
      if (mime !== 'image/webp') return null;
      const n = Math.round(side * side * 0.08);
      const out = new Uint8Array(n);
      out.set([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50]);
      return out;
    };
    const res = profilePhotoFrom(draw);
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.thumb.side).toBe(96);
    expect(res.thumb.bytes.length).toBeLessThanOrEqual(AVATAR_BUDGET_BYTES);
    expect(res.photo.side).toBeGreaterThanOrEqual(256);
    expect(res.photo.bytes.length).toBeLessThanOrEqual(PHOTO_BUDGET_BYTES);
    expect(sides[0]).toBe(96);
    expect(sides).toContain(512);
  });
});

describe('знімок в обрізці', () => {
  it('адреса знімка живе, доки обрізка відкрита, і гасне після', async () => {
    const revoke = vi.fn();
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:x', revokeObjectURL: revoke });
    class FakeImage {
      onload: (() => void) | null = null;
      naturalWidth = 800;
      naturalHeight = 600;
      width = 800;
      height = 600;
      private s = '';
      get src() { return this.s; }
      set src(v: string) { this.s = v; setTimeout(() => this.onload?.(), 0); }
    }
    vi.stubGlobal('Image', FakeImage);
    const res = await openImage(new Blob(['x']));
    expect(res.ok).toBe(true);
    expect(revoke).not.toHaveBeenCalled();
    if (res.ok) closeImage(res.img);
    expect(revoke).toHaveBeenCalledWith('blob:x');
    vi.unstubAllGlobals();
  });
});
