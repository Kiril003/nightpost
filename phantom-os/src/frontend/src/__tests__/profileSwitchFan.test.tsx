/**
 * Віяло профілів (утримати фото в «Я» на ПК). У шапці «Розмов» віяла немає:
 * там перемикач — сама назва профілю.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { ProfileFan, FAN_HOLD_MS } from '../components/messenger/ProfileFan';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useUIStore } from '../stores/uiStore';
import type { Chat, UserProfile } from '../types/messenger';

vi.mock('../services/dayApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/dayApi')>();
  return {
    ...actual,
    listPhoneReminders: () => Promise.resolve([]),
    dayApi: {
      ...actual.dayApi,
      today: () => Promise.resolve({ items: [], calendar: { connected: false, permission: false, busy: [] } }),
      listPhoneReminders: () => Promise.resolve([]),
    },
  };
});

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const haptics: string[] = [];
const bridge = window as unknown as { PhantomBridge?: { haptic: (k: string) => void } };

let ids: Record<string, string> = {};

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  const s = useProfiles.getState();
  const work = s.addFromTemplate('work');
  const family = s.addFromTemplate('family');
  const hobby = s.addFromTemplate('hobby');
  const study = s.addFromTemplate('study');
  const uses: Record<string, number> = { [work.id]: 9, [family.id]: 5, [hobby.id]: 3, [study.id]: 1 };
  useProfiles.setState({
    profiles: useProfiles.getState().profiles.map((p) => ({ ...p, uses: uses[p.id] ?? 0 })),
  });
  ids = { personal: s.activeId, work: work.id, family: family.id, hobby: hobby.id, study: study.id };
  haptics.length = 0;
  bridge.PhantomBridge = { haptic: (k) => haptics.push(k) };
  useUIStore.setState({ toasts: [] });
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  delete bridge.PhantomBridge;
  vi.restoreAllMocks();
});

const noop = () => {};
const USER = { id: 'me', name: 'Олег', avatar: '' } as unknown as UserProfile;

const showList = () =>
  render(
    <PhoneChatList
      chats={[] as Chat[]}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

const hold = (el: Element) => {
  fireEvent.pointerDown(el, { pointerId: 1, clientX: 20, clientY: 20 });
  act(() => {
    vi.advanceTimersByTime(FAN_HOLD_MS + 20);
  });
};

const pointAt = (id: string | null) => {
  document.elementFromPoint = () => (id ? document.querySelector(`[data-fan-id="${id}"]`) : null);
};

describe('шапка «Розмов»: один перемикач', () => {
  it('назва профілю — кнопка, утримання не відкриває віяла', () => {
    showList();
    const title = screen.getByTestId('chats-profile-title');
    expect(screen.queryByTestId('chats-profile-avatar')).toBeNull();
    expect(title.closest('[data-profile-fan-anchor]')).toBeNull();
    hold(title);
    expect(screen.queryByTestId('profile-fan')).toBeNull();
  });

  it('немає, коли профіль один', () => {
    const only = useProfiles.getState().profiles[0];
    useProfiles.setState({ profiles: [only], activeId: only.id });
    showList();
    expect(screen.queryByTestId('chats-profile-title')).toBeNull();
  });
});

const showFan = () =>
  render(
    <ProfileFan>
      <span data-testid="fan-face">О</span>
    </ProfileFan>,
  );

describe('віяло профілів', () => {
  it('утримання відкриває віяло з трьох найуживаніших, без активного, і дає відгук', () => {
    showFan();
    hold(screen.getByTestId('fan-face'));
    const fan = screen.getByTestId('profile-fan');
    const shown = Array.from(fan.querySelectorAll('[data-fan-id]')).map((n) => n.getAttribute('data-fan-id'));
    expect(shown).toEqual([ids.work, ids.family, ids.hobby]);
    expect(haptics).toContain('long');
  });

  it('провести до профілю й відпустити = перемкнуто, з відгуком і назвою', () => {
    showFan();
    const avatar = screen.getByTestId('fan-face');
    hold(avatar);
    pointAt(ids.family);
    fireEvent.pointerMove(avatar, { pointerId: 1, clientX: 80, clientY: 90 });
    expect(document.querySelector(`[data-fan-id="${ids.family}"]`)?.getAttribute('data-hot')).toBe('true');
    fireEvent.pointerUp(avatar, { pointerId: 1, clientX: 80, clientY: 90 });
    expect(useProfiles.getState().activeId).toBe(ids.family);
    expect(screen.queryByTestId('profile-fan')).toBeNull();
    expect(haptics).toContain('confirm');
    expect(useUIStore.getState().toasts.at(-1)?.message).toBe('Сімʼя');
  });

  it('відпустити поза віялом = нічого не міняється', () => {
    showFan();
    const avatar = screen.getByTestId('fan-face');
    hold(avatar);
    pointAt(null);
    fireEvent.pointerUp(avatar, { pointerId: 1, clientX: 300, clientY: 600 });
    expect(useProfiles.getState().activeId).toBe(ids.personal);
    expect(screen.queryByTestId('profile-fan')).toBeNull();
  });

  it('тап робить те, що й раніше, і віяла немає', () => {
    const onTap = vi.fn();
    render(
      <ProfileFan onTap={onTap}>
        <span data-testid="face">О</span>
      </ProfileFan>,
    );
    const face = screen.getByTestId('face');
    fireEvent.pointerDown(face, { pointerId: 1 });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    fireEvent.pointerUp(face, { pointerId: 1 });
    fireEvent.click(face);
    expect(onTap).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('profile-fan')).toBeNull();
    expect(useProfiles.getState().activeId).toBe(ids.personal);
  });

  it('після утримання клік не відкриває того, що відкриває тап', () => {
    const onTap = vi.fn();
    render(
      <ProfileFan onTap={onTap}>
        <span data-testid="face">О</span>
      </ProfileFan>,
    );
    const face = screen.getByTestId('face');
    hold(face);
    pointAt(ids.work);
    fireEvent.pointerUp(face, { pointerId: 1 });
    fireEvent.click(face);
    expect(onTap).not.toHaveBeenCalled();
    expect(useProfiles.getState().activeId).toBe(ids.work);
  });
});

describe('«Я»: те саме віяло на фото', () => {
  it('фото в «Я» сидить у віялі', async () => {
    const { MeScreen } = await import('../components/messenger/MeScreen');
    render(<MeScreen onClose={noop} onOpen={noop} />);
    const photo = screen.getByTestId('me-photo');
    expect(photo.closest('[data-profile-fan-anchor]')).toBeTruthy();
    hold(photo);
    expect(screen.getByTestId('profile-fan')).toBeTruthy();
  });
});
