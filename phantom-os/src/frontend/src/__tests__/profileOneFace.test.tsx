/**
 * Свій профіль на телефоні — один: рідна вкладка «Профіль». Кожні двері до
 * себе (аватар у налаштуваннях, своє імʼя в групі, @згадка, бокова панель)
 * ідуть через `UserProfileModal`, тож він не малює другу форму, а передає
 * рідній оболонці. Без мосту (ПК) форма лишається — там рідної вкладки нема.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { UserProfileModal } from '../components/messenger/UserProfileModal';
import type { UserProfile } from '../types/messenger';

const ME: UserProfile = {
  id: 'me',
  name: 'Ira',
  handle: '@ira',
  avatar: '',
  status: '',
  statusEmoji: '',
  bio: '',
  phone: '',
  locationName: '',
  batteryLevel: 80,
  isMutedAlerts: false,
};

type W = { PhantomBridge?: unknown };

afterEach(() => {
  cleanup();
  delete (window as unknown as W).PhantomBridge;
});

describe('один свій профіль', () => {
  it('на телефоні свій профіль відкриває рідну вкладку, а не форму', () => {
    const navigate = vi.fn(() => true);
    (window as unknown as W).PhantomBridge = { navigate };
    const onClose = vi.fn();
    render(<UserProfileModal isOpen onClose={onClose} currentUser={ME} />);
    expect(navigate).toHaveBeenCalledWith('profile', '{}');
    expect(onClose).toHaveBeenCalled();
    expect(screen.queryByTestId('profile-own-avatar')).toBeNull();
  });

  it('своє імʼя серед учасників групи теж веде в рідну вкладку', () => {
    const navigate = vi.fn(() => true);
    (window as unknown as W).PhantomBridge = { navigate };
    render(<UserProfileModal isOpen onClose={() => {}} currentUser={ME} targetUser={{ ...ME }} />);
    expect(navigate).toHaveBeenCalledWith('profile', '{}');
  });

  it('без мосту форма лишається', () => {
    render(<UserProfileModal isOpen onClose={() => {}} currentUser={ME} />);
    expect(screen.getByTestId('profile-own-avatar')).toBeTruthy();
  });
});
