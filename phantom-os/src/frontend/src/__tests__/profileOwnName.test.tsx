import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { ProfileLookEditor } from '../components/messenger/ProfileLookEditor';
import { buildBridgePayload, useProfiles } from '../stores/profilesStore';

beforeEach(() => {
  localStorage.clear();
});
afterEach(cleanup);

describe('U5: імʼя в профілі', () => {
  it('імʼя профілю B не потрапляє в профіль A, а міст несе його окремо', () => {
    const st = useProfiles.getState();
    const a = st.profiles[0];
    const b = st.addFromTemplate('work');
    const { getByTestId } = render(<ProfileLookEditor profileId={b.id} onBack={() => {}} />);
    fireEvent.change(getByTestId('profile-own-name'), { target: { value: 'Олег Робочий' } });
    const now = useProfiles.getState();
    expect(now.profiles.find((p) => p.id === b.id)?.ownName).toBe('Олег Робочий');
    expect(now.profiles.find((p) => p.id === a.id)?.ownName).toBe('');
    const payload = buildBridgePayload(now);
    expect(payload.profiles.find((p) => p.id === b.id)?.ownName).toBe('Олег Робочий');
    expect(payload.profiles.find((p) => p.id === a.id)?.ownName).toBe('');
  });
});
