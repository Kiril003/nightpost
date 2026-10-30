import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

const listContacts = vi.fn();

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      listContacts: (...a: unknown[]) => listContacts(...a),
      listConversations: vi.fn().mockResolvedValue([]),
    },
  };
});

vi.mock('../services/phantomSelfApi', () => ({ phantomSelfApi: { facts: vi.fn().mockResolvedValue([]) } }));

vi.mock('../services/peerWhere', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/peerWhere')>();
  return {
    ...actual,
    whereApi: { ...actual.whereApi, get: vi.fn().mockRejectedValue(new Error('offline')) },
    onWhereChanged: vi.fn(() => () => {}),
  };
});

import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { verifiedTwin } from '../services/nameTwin';
import type { NodeContact } from '../services/messengerApi';

const row = (peer: string, name: string, verified: boolean, extra: Partial<NodeContact> = {}): NodeContact =>
  ({
    id: `c-${peer}`,
    peer_node_id: peer,
    display_name: name,
    peer_address: null,
    safety_number: '',
    safety_number_pretty: '',
    verified,
    session_ready: true,
    ...extra,
  }) as NodeContact;

const REAL = row('node-a', 'Iva Novak', true);
const FAKE = row('node-b', 'Iva Nоvak', false);

afterEach(() => {
  cleanup();
  listContacts.mockReset();
});

describe('lookalike of a verified contact', () => {
  it('Cyrillic о in an unverified name matches the verified one; same key or verified → no match', () => {
    expect(verifiedTwin('node-b', FAKE.display_name, [REAL, FAKE])).toBe('Iva Novak');
    expect(verifiedTwin('node-a', REAL.display_name, [REAL, FAKE])).toBeNull();
    expect(verifiedTwin('node-b', FAKE.display_name, [{ ...REAL, key_changed: true }, FAKE])).toBeNull();
    expect(verifiedTwin('node-b', 'Iva Novak', [REAL, row('node-b', 'Iva Novak', false)])).toBe('Iva Novak');
    expect(verifiedTwin('node-b', 'Petro', [REAL, row('node-b', 'Petro', false)])).toBeNull();
  });

  it('opening the unverified person shows the warning under the name', async () => {
    listContacts.mockResolvedValue([REAL, FAKE]);
    render(<PeerProfileSheet inline peerNodeId="node-b" name={FAKE.display_name} onClose={() => {}} />);
    expect((await screen.findByTestId('peer-profile-twin')).textContent).toContain('Ще не підтверджено, що це Iva Novak з ваших контактів');
  });

  it('the verified person sees no warning', async () => {
    listContacts.mockResolvedValue([REAL, FAKE]);
    render(<PeerProfileSheet inline peerNodeId="node-a" name={REAL.display_name} onClose={() => {}} />);
    await vi.waitFor(() => expect(listContacts).toHaveBeenCalled());
    await Promise.resolve();
    expect(screen.queryByTestId('peer-profile-twin')).toBeNull();
  });
});
