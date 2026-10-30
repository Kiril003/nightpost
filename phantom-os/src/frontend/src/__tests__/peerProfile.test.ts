/**
 * Чотири стани поля профілю — і жодні два не однакові на екрані.
 *
 * Це і є та вимога контракту, яку неможливо довести оком: «ще не приїхало» і
 * «людина прибрала» виглядають однаково, якщо обидва звести в порожній рядок,
 * і тоді поверхня бреше про людину в один бік або в другий. Тут вони — тип, і
 * тест звіряє, що написи різні, а не лише що вони є.
 */
import { describe, it, expect } from 'vitest';
import {
  avatarSrc,
  profileIndex,
  readPeerProfile,
  statusLine,
  valueOf,
} from '../services/peerProfile';

const row = (over: Record<string, unknown> = {}) => ({
  peer_node_id: 'p1',
  display_name: 'Кирило',
  profile_rev: 3,
  profile_at: '2026-09-16T10:00:00Z',
  avatar_state: 'set' as const,
  avatar_thumb: 'data:image/webp;base64,UklGRg',
  avatar_bytes: 4096,
  status_emoji_state: 'set' as const,
  status_emoji: '🇺🇦',
  status_text_state: 'set' as const,
  status_text: 'на звʼязку',
  bio_state: 'set' as const,
  bio: 'Роблю Nightpost.',
  ...over,
});

describe('readPeerProfile — чотири стани', () => {
  it('усе приїхало — показуємо значення', () => {
    const v = readPeerProfile(row());
    expect(v.rev).toBe(3);
    expect(v.everArrived).toBe(true);
    expect(v.avatar).toEqual({ state: 'set', value: 'data:image/webp;base64,UklGRg' });
    expect(valueOf(v.statusText)).toBe('на звʼязку');
    expect(valueOf(v.bio)).toBe('Роблю Nightpost.');
    expect(v.avatarBytes).toBe(4096);
  });

  it('«не приїхало» і «прибрано» — РІЗНІ написи, не обидва порожні', () => {
    const absent = readPeerProfile(row({ bio_state: 'absent', bio: null }));
    const cleared = readPeerProfile(row({ bio_state: 'cleared', bio: null }));
    expect(absent.bio.state).toBe('absent');
    expect(cleared.bio.state).toBe('cleared');
    const sayA = absent.bio.state === 'absent' ? absent.bio.say : '';
    const sayC = cleared.bio.state === 'cleared' ? cleared.bio.say : '';
    expect(sayA).not.toBe('');
    expect(sayC).not.toBe('');
    expect(sayA).not.toBe(sayC);
  });

  it('аватар має ЧОТИРИ різні речення, і жодне не збігається', () => {
    const says = (['absent', 'cleared', 'unreadable'] as const).map((state) => {
      const v = readPeerProfile(row({ avatar_state: state, avatar_thumb: null })).avatar;
      expect(v.state).toBe(state);
      return v.state === 'set' ? '' : v.say;
    });
    expect(new Set(says).size).toBe(3);
    says.forEach((s) => expect(s.length).toBeGreaterThan(0));
    // Четвертий стан — саме зображення; воно не «речення», а адреса.
    expect(avatarSrc(readPeerProfile(row()))).toBe('data:image/webp;base64,UklGRg');
  });

  it('зображення немає у ВСІХ трьох не-set станах', () => {
    (['absent', 'cleared', 'unreadable'] as const).forEach((state) => {
      expect(avatarSrc(readPeerProfile(row({ avatar_state: state, avatar_thumb: null })))).toBeNull();
    });
  });

  /**
   * Хост старої збірки не має полів узагалі. Це те саме незнання, що й тег,
   * якого не було в кадрі, — і показати його треба так само, а не порожнечею.
   */
  it('хост, який ще не знає про профіль, — це «не приїхало», не «прибрано»', () => {
    const v = readPeerProfile({ peer_node_id: 'p9', display_name: 'Хтось' });
    expect(v.everArrived).toBe(false);
    expect(v.rev).toBe(0);
    expect(v.avatar.state).toBe('absent');
    expect(v.bio.state).toBe('absent');
    expect(v.statusText.state).toBe('absent');
  });

  /** Кадр, у якому все стерте, — це ВИБІР людини, а не наше незнання. */
  it('кадр із усім стертим усе одно рахується як «приїхало»', () => {
    const v = readPeerProfile(
      row({
        avatar_state: 'cleared',
        avatar_thumb: null,
        status_emoji_state: 'cleared',
        status_text_state: 'cleared',
        bio_state: 'cleared',
      }),
    );
    expect(v.everArrived).toBe(true);
    expect(v.bio.state).toBe('cleared');
    expect(statusLine(v)).toBeNull();
  });

  /** Хост сказав «set», а значення порожнє — віримо очам, не слову. */
  it('порожнє значення під станом set читається як «прибрано»', () => {
    expect(readPeerProfile(row({ bio: '   ' })).bio.state).toBe('cleared');
    expect(readPeerProfile(row({ avatar_thumb: '' })).avatar.state).toBe('unreadable');
  });
});

describe('statusLine', () => {
  it('емодзі й текст стають одним рядком', () => {
    expect(statusLine(readPeerProfile(row()))).toBe('🇺🇦 на звʼязку');
  });

  it('лише емодзі — теж рядок', () => {
    expect(statusLine(readPeerProfile(row({ status_text_state: 'cleared', status_text: null }))))
      .toBe('🇺🇦');
  });

  /** Нема чого казати — мовчимо, а не підставляємо «в мережі». */
  it('нічого немає — null, щоб поверхня не малювала порожнього рядка', () => {
    const v = readPeerProfile(
      row({ status_emoji_state: 'absent', status_emoji: null, status_text_state: 'absent', status_text: null }),
    );
    expect(statusLine(v)).toBeNull();
  });
});

describe('profileIndex', () => {
  it('складається за ідентифікатором вузла', () => {
    const idx = profileIndex([row(), row({ peer_node_id: 'p2', display_name: 'Друга' })]);
    expect(idx.size).toBe(2);
    expect(idx.get('p2')!.name).toBe('Друга');
  });

  it('рядок без ідентифікатора не потрапляє в мапу', () => {
    expect(profileIndex([row({ peer_node_id: undefined, id: undefined })]).size).toBe(0);
  });
});
