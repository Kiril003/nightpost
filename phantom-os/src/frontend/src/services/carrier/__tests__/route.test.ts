/**
 * Carrier C2. Червоне без route.ts: файл понад стелю старої збірки ішов v1 і
 * в людини висів вічним «чекає»; стелю, яку людина оголосила, ніхто не читав.
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  LEGACY_PEER_MAX, carrierRoute, forgetPeerCaps, peerCaps, rememberPeerCaps, routeForChat,
} from '../route';
import type { NodePeerProfile } from '../../messengerApi';

const MB = 1024 * 1024;
const own = (v1Max: number, carrier: number) => ({ v1Max, carrier });
const row = (id: string, extra: Partial<NodePeerProfile> = {}) => ({ peer_node_id: id, ...extra }) as NodePeerProfile;

afterEach(() => {
  forgetPeerCaps();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('carrierRoute', () => {
  it('у межах своєї стелі — v1, як досі', () => {
    expect(carrierRoute(3 * MB, [undefined], own(25 * MB, 1))).toEqual({ kind: 'v1' });
  });

  it('людина нічого не оголошувала — стеля старої збірки 32 МіБ', () => {
    expect(carrierRoute(LEGACY_PEER_MAX + 1, [undefined], own(100 * MB, 1))).toEqual({ kind: 'too_big', who: 'peer' });
  });

  it('оголошена стеля людини нижча за мою — відмова до вивантаження', () => {
    const peer = { carrier: 0, maxBytes: 4 * MB };
    expect(carrierRoute(5 * MB, [peer], own(25 * MB, 1))).toEqual({ kind: 'too_big', who: 'peer' });
  });

  it('понад мою v1-стелю й обидва кінці везуть шматки — v2', () => {
    const peer = { carrier: 1, maxBytes: 2048 * MB };
    expect(carrierRoute(30 * MB, [peer], own(25 * MB, 1))).toEqual({ kind: 'v2' });
  });

  it('понад мою v1-стелю, людина шматків не везе — моя стеля, без «оновись» людині', () => {
    const peer = { carrier: 0, maxBytes: 2048 * MB };
    expect(carrierRoute(30 * MB, [peer], own(25 * MB, 1))).toEqual({ kind: 'too_big', who: 'own' });
    expect(carrierRoute(30 * MB, [undefined], own(25 * MB, 1))).toEqual({ kind: 'too_big', who: 'own' });
  });

  it('я шматків не везу (телефон) — моя стеля', () => {
    const peer = { carrier: 1, maxBytes: 2048 * MB };
    expect(carrierRoute(30 * MB, [peer], own(25 * MB, 0))).toEqual({ kind: 'too_big', who: 'own' });
  });

  it('група — лише своя стеля, v2 немає', () => {
    expect(carrierRoute(30 * MB, [], own(25 * MB, 1))).toEqual({ kind: 'too_big', who: 'own' });
    expect(carrierRoute(3 * MB, [], own(25 * MB, 1))).toEqual({ kind: 'v1' });
  });
});

describe('оголошення з профілів', () => {
  it('carrier і max_attachment_bytes із /messenger/profiles; сміття — «нічого не оголошено»', () => {
    rememberPeerCaps([
      row('a', { carrier: 1, max_attachment_bytes: 64 * MB }),
      row('b', { carrier: -3 as number, max_attachment_bytes: 'x' as unknown as number }),
      row('c'),
    ]);
    expect(peerCaps('a')).toEqual({ carrier: 1, maxBytes: 64 * MB });
    expect(peerCaps('b')).toEqual({ carrier: 0, maxBytes: null });
    expect(peerCaps('c')).toEqual({ carrier: 0, maxBytes: null });
    expect(peerCaps('zzz')).toBeUndefined();
  });

  it('routeForChat: 1:1 читає оголошення людини; група — ні; телефон шматків не везе', () => {
    rememberPeerCaps([row('p', { carrier: 1, max_attachment_bytes: 4 * MB })]);
    const direct = { type: 'direct' as const, peerNodeId: 'p' };
    const group = { type: 'group' as const, peerNodeId: 'p' };
    expect(routeForChat(direct, 5 * MB, 25 * MB)).toEqual({ kind: 'too_big', who: 'peer' });
    expect(routeForChat(group, 5 * MB, 25 * MB)).toEqual({ kind: 'v1' });

    rememberPeerCaps([row('p', { carrier: 1, max_attachment_bytes: 2048 * MB })]);
    expect(routeForChat(direct, 30 * MB, 25 * MB)).toEqual({ kind: 'v2' });
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    expect(routeForChat(direct, 30 * MB, 25 * MB)).toEqual({ kind: 'too_big', who: 'own' });
  });
});
