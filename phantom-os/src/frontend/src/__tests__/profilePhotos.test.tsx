import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ProfileFan, FAN_HOLD_MS } from '../components/messenger/ProfileFan';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { isFacePhotoUrl, profilePhotosOf } from '../services/peerProfile';

let ids: Record<string, string> = {};

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  const s = useProfiles.getState();
  const work = s.addFromTemplate('work');
  const family = s.addFromTemplate('family');
  useProfiles.getState().setActive(s.profiles[0].id);
  ids = { personal: useProfiles.getState().profiles[0].id, work: work.id, family: family.id };
  useMessengerStore.setState({ profilePhotos: {} });
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  vi.restoreAllMocks();
});

describe('фото кожного профілю', () => {
  it('своє фото з телефона — адреса, яку веб приймає, з профілем і без', () => {
    expect(isFacePhotoUrl('/webmsgr/_faces/me?v=3&p=p_work-1')).toBe(true);
    expect(isFacePhotoUrl('/webmsgr/_faces/me-p_work-1?v=1730170000000')).toBe(true);
    expect(isFacePhotoUrl('/webmsgr/_faces/me?v=3')).toBe(true);
    expect(isFacePhotoUrl('/webmsgr/_faces/me-a/b?v=1')).toBe(false);
    expect(isFacePhotoUrl('/webmsgr/_faces/me?v=3&p=../x')).toBe(false);
    expect(isFacePhotoUrl('https://evil.example/_faces/me?v=1')).toBe(false);
  });

  it('мапа фото профілів бере лише адреси свого телефона', () => {
    expect(
      profilePhotosOf({ a: '/webmsgr/_faces/me-a?v=1', b: 'https://x.example/p.jpg', c: 7, d: '/webmsgr/_faces/me-d?v=2' }),
    ).toEqual({ a: '/webmsgr/_faces/me-a?v=1', d: '/webmsgr/_faces/me-d?v=2' });
    expect(profilePhotosOf(null)).toEqual({});
    expect(profilePhotosOf('x')).toEqual({});
  });

  it('після /identity «Я» має своє фото, а профілі — свої', async () => {
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({
      node_id: 'n1',
      display_name: 'Олег',
      avatar: '/webmsgr/_faces/me?v=3&p=' + ids.personal,
      profile_photos: { [ids.work]: `/webmsgr/_faces/me-${ids.work}?v=9` },
    } as never);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    await useMessengerStore.getState().hydrateFromNode({ openFirst: false });
    expect(useMessengerStore.getState().currentUser.avatar).toBe('/webmsgr/_faces/me?v=3&p=' + ids.personal);
    expect(useMessengerStore.getState().profilePhotos).toEqual({ [ids.work]: `/webmsgr/_faces/me-${ids.work}?v=9` });
  });

  it('у віялі профіль із фото показує фото, без фото — літери', () => {
    vi.useFakeTimers();
    useMessengerStore.setState({ profilePhotos: { [ids.work]: `/webmsgr/_faces/me-${ids.work}?v=9` } });
    render(
      <ProfileFan>
        <button type="button" data-testid="anchor">
          a
        </button>
      </ProfileFan>,
    );
    fireEvent.pointerDown(screen.getByTestId('anchor'), { pointerId: 1, clientX: 20, clientY: 20 });
    act(() => {
      vi.advanceTimersByTime(FAN_HOLD_MS + 20);
    });
    const work = document.querySelector(`[data-fan-id="${ids.work}"]`)!;
    expect(work.querySelector('img')?.getAttribute('src')).toBe(`/webmsgr/_faces/me-${ids.work}?v=9`);
    const family = document.querySelector(`[data-fan-id="${ids.family}"]`)!;
    expect(family.querySelector('img')).toBeNull();
  });
});
