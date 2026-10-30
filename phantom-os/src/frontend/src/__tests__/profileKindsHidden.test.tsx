import { beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { loadProfiles, useProfiles } from '../stores/profilesStore';

const click = (id: string) => fireEvent.click(screen.getByTestId(id));

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
});

describe('profile kind picker', () => {
  it('offers neither dev nor author while their tabs have no screens', () => {
    render(<ProfilesHost />);
    click('me-profiles-open');
    for (const k of ['dev', 'author']) expect(screen.queryByTestId(`profile-chip-${k}`)).toBeNull();
    click('profile-add-open');
    for (const k of ['dev', 'author']) expect(screen.queryByTestId(`profile-template-${k}`)).toBeNull();
    for (const k of ['personal', 'family', 'work', 'business', 'study', 'trip', 'temp', 'unnamed']) {
      expect(screen.getByTestId(`profile-template-${k}`)).toBeInTheDocument();
    }
  });
});
