/**
 * Рядок «Профілі» на телефоні кличе window.__phantomProfiles.open() у корені
 * месенджера, а не в «Я». Без аркуша в корені двері відповідали 'refused'.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { loadProfiles, startProfiles, useProfiles } from '../stores/profilesStore';

type Door = { __phantomProfiles?: { open: () => boolean } };

let stop: (() => void) | undefined;

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  useMessengerStore.setState({ chats: [], activeChatId: undefined, hydrateFromNode: async () => {} });
  stop = startProfiles();
});

afterEach(() => {
  stop?.();
  stop = undefined;
  cleanup();
  vi.restoreAllMocks();
});

describe('двері «Профілі» з нативного рядка', () => {
  it('корінь месенджера відкриває аркуш, створює профіль із шаблону і перемикає на нього', async () => {
    render(<MessengerRoot />);
    let opened = false;
    act(() => {
      opened = Boolean((window as unknown as Door).__phantomProfiles?.open());
    });
    expect(opened).toBe(true);
    await screen.findByTestId('profiles-sheet');

    const before = useProfiles.getState().profiles.length;
    fireEvent.click(screen.getByTestId('profile-add-open'));
    fireEvent.click(screen.getByTestId('profile-template-work'));
    fireEvent.click(screen.getByTestId('profile-template-create'));
    const { profiles, activeId } = useProfiles.getState();
    expect(profiles.length).toBe(before + 1);
    expect(profiles.find((p) => p.id === activeId)?.kind).toBe('work');
  });
});
