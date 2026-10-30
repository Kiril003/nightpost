import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WhereSheet } from '../components/messenger/WhereCard';
import { useMessengerStore } from '../stores/messengerStore';

const NOW = 1_790_000_000_000;
const oksana = { id: 'oksana', name: 'Оксана' };
let state: Record<string, unknown> = { now_ms: NOW, eligible: true, why_not: null, mine: null, theirs: null };
const posts: string[] = [];

function bridge(m: string, p: string): string {
  if (m === 'POST') posts.push(p);
  if (p.startsWith('/messenger/where/')) return JSON.stringify({ ok: true, status: 200, data: state });
  return JSON.stringify({ ok: false, status: 404 });
}

type Geo = { getCurrentPosition: (ok: (p: unknown) => void, bad: (e: unknown) => void) => void };
const setGeo = (geo: Geo | undefined) => Object.defineProperty(navigator, 'geolocation', { value: geo, configurable: true });
const flush = () => act(async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); });
const messages = () => useMessengerStore.getState().chats.find((c) => c.id === 'dm-oksana')?.messages ?? [];

beforeEach(() => {
  posts.length = 0;
  state = { now_ms: NOW, eligible: true, why_not: null, mine: null, theirs: null };
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: bridge };
  useMessengerStore.setState({
    activeChatId: 'dm-oksana',
    chats: [{ id: 'dm-oksana', type: 'direct', title: 'Оксана', peerNodeId: 'oksana', members: [], messages: [] }] as never,
  });
});
afterEach(() => {
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  setGeo(undefined);
});

describe('T10: «Місце» в розмові вдвох надсилає точку повідомленням', () => {
  it('«Надіслати моє місце» кладе geo:point у розмову і закриває аркуш, показ наживо не вмикається', async () => {
    setGeo({ getCurrentPosition: (ok) => ok({ coords: { latitude: 49.84, longitude: 24.03, accuracy: 7.6 }, timestamp: NOW }) });
    const closed: string[] = [];
    render(<WhereSheet peer={oksana} onClose={() => closed.push('x')} />);
    await flush();
    fireEvent.click(screen.getByTestId('where-send-point'));
    await flush();
    const [msg] = messages();
    expect(msg?.type).toBe('geo:point');
    expect(msg?.geoPoint).toEqual({ lat: 49.84, lon: 24.03, atMs: NOW, accuracyM: 8 });
    expect(closed).toEqual(['x']);
    expect(posts.filter((p) => p.startsWith('/messenger/where/'))).toEqual([]);
  });

  it('людина ще не на звʼязку для показу наживо — разова точка все одно є', async () => {
    state = { ...state, eligible: false, why_not: 'no_key' };
    render(<WhereSheet peer={oksana} onClose={() => {}} />);
    await flush();
    expect(screen.getByTestId('where-why-not')).toBeTruthy();
    expect(screen.getByTestId('where-send-point').textContent).toBe('Надіслати моє місце');
  });

  it('без дозволу — чесне речення, нічого не надіслано, аркуш лишається', async () => {
    setGeo({ getCurrentPosition: (_ok, bad) => bad({ code: 1, PERMISSION_DENIED: 1, TIMEOUT: 3 }) });
    const closed: string[] = [];
    render(<WhereSheet peer={oksana} onClose={() => closed.push('x')} />);
    await flush();
    fireEvent.click(screen.getByTestId('where-send-point'));
    await flush();
    expect(messages()).toEqual([]);
    expect(closed).toEqual([]);
    expect(screen.getByTestId('where-error').textContent).toMatch(/доступ|дозв/i);
  });
});
