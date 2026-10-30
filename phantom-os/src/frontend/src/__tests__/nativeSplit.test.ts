import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { installNativeMeetupDraft, useMeetupAsk } from '../services/nativeMeetupDraft';
import { BILL_KIND, MEETUP_KIND } from '../types/meetup';

type Win = { __phantomOpenMeetupDraft?: (json: unknown) => boolean };
const split = () => (window as unknown as Win).__phantomOpenMeetupDraft?.(JSON.stringify({ split: 'claimed' }));
const msg = (id: string, type: string, at: string) => ({ id, type, timestamp: at, content: '' });

describe('«Розділити» з гаманця веде в наявний рахунок', () => {
  beforeEach(() => {
    useMessengerStore.setState({
      chats: [],
      activeChatId: null,
      loadMessagesForChat: vi.fn(async () => {}),
      loadGroupMembers: vi.fn(async () => {}),
    } as never);
  });

  it('гаманець не в розмові — відкриває найновіший рахунок з усіх розмов', () => {
    useMessengerStore.setState({
      chats: [
        { id: 'a', type: 'group', title: 'A', messages: [msg('m1', MEETUP_KIND, '2026-10-01T10:00:00Z')] },
        { id: 'b', type: 'group', title: 'B', messages: [msg('b1', BILL_KIND, '2026-10-09T10:00:00Z')] },
      ],
    } as never);
    const off = installNativeMeetupDraft();
    const ask = renderHook(() => useMeetupAsk());
    expect(split()).toBe(true);
    expect(useMessengerStore.getState().activeChatId).toBe('b');
    ask.rerender();
    expect(ask.result.current.ask).toEqual({ kind: 'bill', messageId: 'b1' });
    off();
  });

  it('розмова ще не відкривалась — береться за рядком переліку й чекає листів', () => {
    useMessengerStore.setState({
      chats: [{ id: 'g', type: 'group', title: 'G', messages: [], lastKind: MEETUP_KIND, lastAt: '2026-10-09T10:00:00Z' }],
    } as never);
    const off = installNativeMeetupDraft();
    const ask = renderHook(() => useMeetupAsk());
    expect(split()).toBe(true);
    expect(useMessengerStore.getState().activeChatId).toBe('g');
    useMessengerStore.setState({
      chats: [{ id: 'g', type: 'group', title: 'G', messages: [msg('g1', MEETUP_KIND, '2026-10-09T10:00:00Z')] }],
    } as never);
    ask.rerender();
    expect(ask.result.current.ask).toEqual({ kind: 'bill-draft', messageId: 'g1' });
    off();
  });

  it('ні зустрічі, ні рахунку — чесна підказка, не порожній аркуш', () => {
    const toast = vi.spyOn(useUIStore.getState(), 'toast');
    useMessengerStore.setState({ chats: [{ id: 'x', type: 'direct', title: 'X', messages: [] }] } as never);
    const off = installNativeMeetupDraft();
    expect(split()).toBe(false);
    expect(toast).toHaveBeenCalled();
    off();
  });
});
