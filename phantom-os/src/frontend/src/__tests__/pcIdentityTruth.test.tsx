import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi, type NodeIdentity } from '../services/messengerApi';
import { globalP2PMesh } from '../services/globalP2PMesh';

const NODE_IDENTITY: NodeIdentity = {
  node_id: 'node_86a15538f0c24e91',
  bundle: { name: 'Олена Коваль' },
  compact: 'phantom1node86a15538',
  max_attachment_bytes: 10 * 1024 * 1024,
};

describe('PC identity truth in MessengerRoot', () => {
  const hadBridge = 'PhantomBridge' in window;
  const prevBridge = (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;

  beforeEach(() => {
    vi.stubGlobal('__PHONE_BUILD__', false);
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;

    vi.spyOn(messengerApi, 'identity').mockResolvedValue(NODE_IDENTITY);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);

    useMessengerStore.setState((s) => ({
      currentUser: {
        ...s.currentUser,
        id: 'self',
        name: '',
        handle: '',
        avatar: '',
        status: '',
      },
      chats: [],
      activeChatId: '',
      hydrated: false,
    }));
  });

  afterEach(() => {
    if (hadBridge) {
      (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = prevBridge;
    } else {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    }
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('retains node identity from hydrateFromNode() and does not initialize globalP2PMesh or overwrite with @kiril', async () => {
    const meshInitSpy = vi.spyOn(globalP2PMesh, 'init').mockImplementation(() => undefined);

    await act(async () => {
      render(
        <MemoryRouter>
          <MessengerRoot />
        </MemoryRouter>,
      );
    });

    await waitFor(() => {
      expect(useMessengerStore.getState().hydrated).toBe(true);
    });

    expect(meshInitSpy).not.toHaveBeenCalled();
    const user = useMessengerStore.getState().currentUser;
    expect(user.id).toBe(NODE_IDENTITY.node_id);
    expect(user.name).toBe('Олена Коваль');
    expect(user.handle).not.toBe('@kiril');
  });
});
