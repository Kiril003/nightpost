import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { buildBridgePayload, loadProfiles, useProfiles, PROFILES_KEY } from '../stores/profilesStore';

const click = (id: string) => fireEvent.click(screen.getByTestId(id));

const bridge = globalThis as { PhantomBridge?: Record<string, unknown> };

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  bridge.PhantomBridge = { profileAlerts: () => true };
});

afterEach(() => {
  delete bridge.PhantomBridge;
});

describe('сигнал сповіщень у кожного профілю свій', () => {
  it('новий профіль дзвонить звуком; зіпсований запис теж падає у звук', () => {
    expect(useProfiles.getState().profiles[0].alert).toBe('sound');
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'a',
        profiles: [
          { id: 'a', name: 'А', kind: 'work', createdAt: 1, alert: 'vibrate' },
          { id: 'b', name: 'Б', kind: 'work', createdAt: 1, alert: 'loud!!' },
        ],
      }),
    );
    expect(loadProfiles(false).profiles.map((p) => p.alert)).toEqual(['vibrate', 'sound']);
  });

  it('«Вібрація» в налаштуваннях «Роботи» лягає в профіль і їде на телефон', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    render(<ProfilesHost />);
    click('me-profiles-open');
    click(`profile-settings-${work.id}`);
    expect(screen.getByTestId('profile-alert-sound').getAttribute('aria-checked')).toBe('true');
    click('profile-alert-vibrate');
    expect(screen.getByTestId('profile-alert-vibrate').getAttribute('aria-checked')).toBe('true');
    const state = useProfiles.getState();
    expect(state.profiles.find((p) => p.id === work.id)?.alert).toBe('vibrate');
    expect(state.profiles[0].alert).toBe('sound');
    const sent = buildBridgePayload(state).profiles.find((p) => p.id === work.id);
    expect(sent?.alert).toBe('vibrate');
  });

  it('телефон без підтримки сигналу — рядка «Сигнал» немає зовсім', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    bridge.PhantomBridge = { setProfiles: () => {} };
    render(<ProfilesHost />);
    click('me-profiles-open');
    click(`profile-settings-${work.id}`);
    expect(screen.queryByTestId('profile-alert-sound')).toBeNull();
    cleanup();
    delete bridge.PhantomBridge;
    render(<ProfilesHost />);
    click('me-profiles-open');
    click(`profile-settings-${work.id}`);
    expect(screen.queryByTestId('profile-alert-sound')).toBeNull();
  });
});
