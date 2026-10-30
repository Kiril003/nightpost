/**
 * Головна дія — бурштин у кожному профілі (лідер 28.10, A52: під «Роботою» «Надіслати», «Далі»,
 * «+ Зміна» були блакитні). Тон профілю живе лише на крапці профілю в заголовку.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { DEFAULT_LOOK, startLookWatch, useLook } from '../services/appearance';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { ChatsProfileTitle } from '../components/messenger/ProfileFan';

let stop: (() => void) | undefined;
beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  useLook.setState({ look: DEFAULT_LOOK });
  stop = startLookWatch();
});
afterEach(() => {
  stop?.();
  cleanup();
  localStorage.clear();
});

describe('акцент: бурштин головний, тон профілю — на крапці', () => {
  it('профіль «Робота» з тоном sea не фарбує головні дії: корінь лишається amber', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    useProfiles.getState().setProfileLook(work.id, { accent: 'sea' });
    act(() => useProfiles.getState().setActive(work.id));
    expect(document.documentElement.getAttribute('data-accent')).toBe('amber');
    expect(document.documentElement.getAttribute('data-profile-accent')).toBe('sea');
  });

  it('крапка профілю в заголовку несе свій тон локально', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    useProfiles.getState().setProfileLook(work.id, { accent: 'sea' });
    act(() => useProfiles.getState().setActive(work.id));
    render(<ChatsProfileTitle fallback="Розмови" />);
    expect(screen.getByTestId('chats-profile-dot').getAttribute('data-accent')).toBe('sea');
  });
});
