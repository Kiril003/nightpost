/**
 * K1: an honest conversation header.
 *  - a group or channel header offers no call or video button (guard);
 *  - "near" comes from a presence code, not from the Ukrainian word, so English marks it too;
 *  - the assistant chat's voice entry is an AudioLines icon labelled Voice (UI audit #5, Header part).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

const nav = vi.hoisted(() => ({ navigateTo: vi.fn((_to: string) => true) }));
vi.mock('../services/nav', async (orig) => {
  const real = await orig<typeof import('../services/nav')>();
  return { ...real, navigateTo: nav.navigateTo };
});

import { Header } from '../components/messenger/Header';
import { useLiving, type PulsePerson } from '../services/livingPulse';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const ME = { id: 'me', name: 'Max', avatar: '' } as unknown as UserProfile;

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

const chat = (extra: Partial<Chat> = {}): Chat =>
  ({
    id: 'chat_o',
    title: 'Oksana',
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    messages: [],
    peerNodeId: 'c1',
    ...extra,
  }) as unknown as Chat;

const group = (type: 'group' | 'channel'): Chat =>
  chat({
    id: `${type}_1`,
    title: 'Walk test',
    type,
    peerNodeId: 'g1',
    members: [
      { id: 'o', name: 'Olena' },
      { id: 'p', name: 'Petro' },
      { id: 'me', name: 'Max' },
    ] as never,
  } as Partial<Chat>);

const draw = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={c}
        currentUser={ME}
        onOpenSettings={() => {}}
        onOpenGroupDetails={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onToggleSearch={() => {}}
        isSearching={false}
        onBack={() => {}}
      />
    </MemoryRouter>,
  );

const person = (near: PulsePerson['near']): PulsePerson => ({
  peerId: 'c1',
  name: 'Oksana',
  near,
  precision: null,
  atMs: Date.now(),
});

const pulseWith = (p: PulsePerson) => ({
  pulse: { sky: null, near: [p], link: { online: null, direct: false } },
});

beforeEach(() => {
  useLiving.setState({ pulse: null, faces: {} });
  useMessengerStore.setState({ typingStatus: {} } as never);
  nav.navigateTo.mockClear();
});

afterEach(() => {
  cleanup();
  setLocale('uk');
});

describe('group header has no call buttons (guard)', () => {
  for (const type of ['group', 'channel'] as const) {
    it(`${type}: no audio, video or voice entry; More stays`, () => {
      const { container } = draw(group(type));
      expect(container.querySelector('[data-call-start]')).toBeNull();
      expect(screen.queryByLabelText('Аудіодзвінок')).toBeNull();
      expect(screen.queryByLabelText('Відеодзвінок')).toBeNull();
      expect(screen.getByLabelText('Ще')).toBeTruthy();
    });
  }
});

describe('presence near is a code, not a Ukrainian word', () => {
  it('en: seen on the local network just now reads "nearby" and is marked near', () => {
    setLocale('en');
    useLiving.setState({ faces: { c1: { avatar: null, heardAtMs: null, lanSeenAtMs: Date.now() } } });
    draw(chat());
    const line = screen.getByTestId('header-presence');
    expect(line.textContent).toBe('nearby');
    expect(line.getAttribute('data-near')).toBe('true');
  });

  it('uk: the same case reads "поруч" and is marked near', () => {
    useLiving.setState({ faces: { c1: { avatar: null, heardAtMs: null, lanSeenAtMs: Date.now() } } });
    draw(chat());
    const line = screen.getByTestId('header-presence');
    expect(line.textContent).toBe('поруч');
    expect(line.getAttribute('data-near')).toBe('true');
  });

  it('en: a shared location close by is near; far is not', () => {
    setLocale('en');
    useLiving.setState(pulseWith(person('near')));
    const { unmount } = draw(chat());
    expect(screen.getByTestId('header-presence').getAttribute('data-near')).toBe('true');
    unmount();
    useLiving.setState(pulseWith(person('far')));
    draw(chat());
    expect(screen.getByTestId('header-presence').hasAttribute('data-near')).toBe(false);
  });

  it('en: heard ten minutes ago is not near', () => {
    setLocale('en');
    useLiving.setState({ faces: { c1: { avatar: null, heardAtMs: Date.now() - 10 * 60_000, lanSeenAtMs: null } } });
    draw(chat());
    const line = screen.getByTestId('header-presence');
    expect(line.textContent).not.toBe('');
    expect(line.hasAttribute('data-near')).toBe(false);
  });
});

describe('assistant voice entry (UI audit #5, Header part)', () => {
  for (const [locale, label] of [['uk', 'Голос'], ['en', 'Voice']] as const) {
    it(`${locale}: AudioLines icon labelled "${label}", no phone icon`, () => {
      setLocale(locale);
      const { container } = draw(chat({ id: 'nightpost', title: 'Nightpost', type: 'ai', peerNodeId: undefined } as Partial<Chat>));
      const btn = container.querySelector('[data-call-start="phantom"]') as HTMLElement;
      expect(btn).toBeTruthy();
      expect(btn.getAttribute('aria-label')).toBe(label);
      expect(btn.getAttribute('title')).toBe(label);
      expect(btn.querySelector('svg.lucide-audio-lines')).toBeTruthy();
      expect(btn.querySelector('svg.lucide-phone')).toBeNull();
    });
  }

  it('a tap still opens the live voice entry', () => {
    const { container } = draw(chat({ id: 'nightpost', title: 'Nightpost', type: 'ai', peerNodeId: undefined } as Partial<Chat>));
    fireEvent.click(container.querySelector('[data-call-start="phantom"]') as HTMLElement);
    expect(nav.navigateTo).toHaveBeenCalledWith('call-phantom');
  });
});
