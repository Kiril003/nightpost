import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as circlesApi from '../services/circlesApi';
import { messengerApi, type ContactRequest } from '../services/messengerApi';
import { DEFAULT_CIRCLES, useCirclesStore } from '../stores/circlesStore';
import { UNDO_MS, sendContactRequest, useRequestsStore } from '../stores/requestsStore';
import { useMessengerStore } from '../stores/messengerStore';
import { PeopleScreen } from '../components/messenger/PeopleScreen';

const inReq = (id: string, name: string, text = ''): ContactRequest => ({
  id, direction: 'in', peer_node_id: `n_${id}`, name, text, created_at: '2026-10-23T10:00:00Z', delivery: 'sent',
});
const outReq = (id: string, name: string): ContactRequest => ({ ...inReq(id, name), direction: 'out' });

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(circlesApi, 'listCirclesWithMeta').mockResolvedValue({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useCirclesStore.setState({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useRequestsStore.setState({ incoming: [], outgoing: [], loaded: false, error: false });
  vi.spyOn(useMessengerStore.getState(), 'refreshConversations').mockResolvedValue();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const openRequests = async () => {
  render(<PeopleScreen onClose={() => {}} />);
  fireEvent.click(await screen.findByTestId('people-requests-row'));
};

describe('Люди › Запити', () => {
  it('shows the incoming count on the row and the requests in the tab', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [inReq('a', 'Олена', 'привіт'), inReq('b', 'Макс')], outgoing: [outReq('c', 'Іра')] });
    render(<PeopleScreen onClose={() => {}} />);
    expect((await screen.findByTestId('people-requests-count')).textContent).toBe('2');
    fireEvent.click(screen.getByTestId('people-requests-row'));
    expect(await screen.findByTestId('request-row-a')).toBeTruthy();
    expect(screen.getByTestId('request-sent-c')).toBeTruthy();
  });

  it('stays an honest empty state without requests', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [], outgoing: [] });
    await openRequests();
    expect(await screen.findByTestId('requests-empty')).toBeTruthy();
  });

  it('accept picks a circle, can be undone, and commits after the undo window', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [inReq('a', 'Олена')], outgoing: [] });
    const accept = vi.spyOn(messengerApi, 'acceptRequest').mockResolvedValue({ conversation_id: 'conv1', contact_id: 'k1' });
    const join = vi.spyOn(circlesApi, 'joinCircle').mockResolvedValue(['friends']);
    await openRequests();
    fireEvent.click(await screen.findByTestId('request-accept-a'));
    fireEvent.click(screen.getByTestId('request-circle-a-friends'));
    expect(screen.queryByTestId('request-row-a')).toBeNull();
    expect(accept).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Скасувати'));
    expect(await screen.findByTestId('request-row-a')).toBeTruthy();
    expect(accept).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('request-accept-a'));
    fireEvent.click(screen.getByTestId('request-circle-a-friends'));
    await act(async () => {
      vi.advanceTimersByTime(UNDO_MS + 50);
    });
    await waitFor(() => expect(accept).toHaveBeenCalledWith('a'));
    await waitFor(() => expect(join).toHaveBeenCalledWith('friends', 'conv1'));
  });

  it('decline commits quietly and the row returns if the server refuses', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [inReq('a', 'Олена')], outgoing: [] });
    const decline = vi.spyOn(messengerApi, 'declineRequest').mockRejectedValue(new Error('x'));
    await openRequests();
    fireEvent.click(await screen.findByTestId('request-decline-a'));
    expect(screen.queryByTestId('request-row-a')).toBeNull();
    await act(async () => {
      vi.advanceTimersByTime(UNDO_MS + 50);
    });
    await waitFor(() => expect(decline).toHaveBeenCalledWith('a'));
    expect(await screen.findByTestId('request-row-a')).toBeTruthy();
  });

  it('the sender can withdraw a sent request', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [], outgoing: [outReq('c', 'Іра')] });
    const withdraw = vi.spyOn(messengerApi, 'withdrawRequest').mockResolvedValue({});
    await openRequests();
    fireEvent.click(await screen.findByTestId('request-withdraw-c'));
    await act(async () => {
      vi.advanceTimersByTime(UNDO_MS + 50);
    });
    await waitFor(() => expect(withdraw).toHaveBeenCalledWith('c'));
  });

  it('leaving the screen commits what is still waiting', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [inReq('a', 'Олена')], outgoing: [] });
    const decline = vi.spyOn(messengerApi, 'declineRequest').mockResolvedValue({});
    await openRequests();
    fireEvent.click(await screen.findByTestId('request-decline-a'));
    cleanup();
    await waitFor(() => expect(decline).toHaveBeenCalledWith('a'));
  });

  it('adding a person sends a request and survives a failing send', async () => {
    const send = vi.spyOn(messengerApi, 'sendRequest').mockRejectedValue(new Error('offline'));
    expect(() => sendContactRequest('k1')).not.toThrow();
    expect(send).toHaveBeenCalledWith('k1');
  });
  it('on the phone shell requests load and send like on the node', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    try {
      const list = vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [inReq('a', 'Олена')], outgoing: [] });
      const send = vi.spyOn(messengerApi, 'sendRequest').mockResolvedValue(outReq('z', 'Іра'));
      await useRequestsStore.getState().load();
      expect(list).toHaveBeenCalled();
      expect(useRequestsStore.getState().incoming.map((r) => r.id)).toEqual(['a']);
      sendContactRequest('k1');
      expect(send).toHaveBeenCalledWith('k1');
    } finally {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    }
  });
});
