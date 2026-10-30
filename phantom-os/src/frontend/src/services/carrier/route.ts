import { messengerApi, type NodePeerProfile } from '../messengerApi';
import type { Chat } from '../../types/messenger';
import { isPhoneShell } from '../../components/messenger/phoneShell';

/** Carrier C2: якою дорогою їде файл до цієї людини — і чи їде взагалі. */

/** `PeerFile.MAX_BYTES`: стеля збірок, що ще нічого не оголошують. */
export const LEGACY_PEER_MAX = 32 * 1024 * 1024;

export const CARRIER_V1 = 1;

/** Шматки вміє класти лише вузол ПК (C5); міст телефона — ще ні. */
export const ownCarrierLevel = (): number => (isPhoneShell() ? 0 : CARRIER_V1);

export interface PeerCaps {
  carrier: number;
  maxBytes: number | null;
}

export type CarrierRoute = { kind: 'v1' } | { kind: 'v2' } | { kind: 'too_big'; who: 'peer' | 'own' };

const caps = new Map<string, PeerCaps>();
let profilesFetched = false;
let profilesInFlight: Promise<void> | null = null;
let capsEpoch = 0;

const positiveInt = (x: unknown): number | null =>
  typeof x === 'number' && Number.isSafeInteger(x) && x > 0 ? x : null;

/** Оголошення з кадру профілю (`/messenger/profiles`); без полів — людина нічого не оголошувала. */
export function rememberPeerCaps(rows: NodePeerProfile[]): void {
  for (const r of rows) {
    if (!r?.peer_node_id) continue;
    caps.set(r.peer_node_id, {
      carrier: Math.min(positiveInt(r.carrier) ?? 0, 255),
      maxBytes: positiveInt(r.max_attachment_bytes),
    });
  }
}

export const peerCaps = (peerId: string): PeerCaps | undefined => caps.get(peerId);

export function forgetPeerCaps(): void {
  capsEpoch += 1;
  caps.clear();
  profilesFetched = false;
  profilesInFlight = null;
  capsQuietUntil = 0;
}

/** Підтягує оголошення співрозмовників один раз на вимогу перед вибором дороги. */
export async function ensurePeerCaps(peerId: string): Promise<PeerCaps | undefined> {
  if (!peerId) return undefined;
  const known = caps.get(peerId);
  if (known) return known;
  if (profilesFetched) return undefined;
  if (!profilesInFlight) {
    const epoch = capsEpoch;
    const task = messengerApi
      .peerProfiles()
      .then((rows) => {
        if (epoch !== capsEpoch) return;
        if (Array.isArray(rows)) rememberPeerCaps(rows);
        profilesFetched = true;
      })
      .catch(() => {
        capsQuietUntil = Date.now() + CAPS_QUIET_MS;
      })
      .finally(() => {
        if (profilesInFlight === task) {
          profilesInFlight = null;
        }
      });
    profilesInFlight = task;
  }
  await profilesInFlight;
  return caps.get(peerId);
}

/**
 * v1 — один блоб, як досі; v2 — шматки, лише коли ОБИДВА кінці оголосили Carrier;
 * інакше чесна відмова до вивантаження, а не вічне «чекає» в старої збірки.
 */
export function carrierRoute(
  size: number,
  peers: Array<PeerCaps | undefined>,
  own: { v1Max: number; carrier: number },
): CarrierRoute {
  const peerMax = Math.min(...peers.map((p) => p?.maxBytes ?? LEGACY_PEER_MAX));
  if (size > peerMax) return { kind: 'too_big', who: 'peer' };
  if (size <= own.v1Max) return { kind: 'v1' };
  const bothCarry = own.carrier >= 1 && peers.length > 0 && peers.every((p) => (p?.carrier ?? 0) >= 1);
  if (bothCarry) return { kind: 'v2' };
  return { kind: 'too_big', who: 'own' };
}

const NOT_ONE_TO_ONE = new Set<Chat['type']>(['group', 'channel', 'saved', 'ai', 'phantom']);

/** Дорога для файла в цю розмову: людина 1:1 — за її оголошенням, решта — лише своя стеля. */
export function routeForChat(chat: Pick<Chat, 'type' | 'peerNodeId'> | undefined, size: number, v1Max: number): CarrierRoute {
  const peers = chat?.peerNodeId && !NOT_ONE_TO_ONE.has(chat.type) ? [peerCaps(chat.peerNodeId)] : [];
  return carrierRoute(size, peers, { v1Max, carrier: ownCarrierLevel() });
}

export const CAPS_WAIT_MS = 4000;
export const CAPS_QUIET_MS = 30_000;
// Після завислого чи невдалого запиту наступні надсилання не чекають знову.
let capsQuietUntil = 0;

export function chatCapsPending(chat: Pick<Chat, 'type' | 'peerNodeId'> | undefined): boolean {
  return Boolean(chat?.peerNodeId) && !NOT_ONE_TO_ONE.has(chat!.type) && !caps.has(chat!.peerNodeId!) && !profilesFetched && Date.now() >= capsQuietUntil;
}

export async function ensureChatCaps(chat: Pick<Chat, 'type' | 'peerNodeId'> | undefined): Promise<void> {
  if (!chat?.peerNodeId || NOT_ONE_TO_ONE.has(chat.type)) return;
  if (!caps.has(chat.peerNodeId) && Date.now() < capsQuietUntil) return;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let timedOut = false;
  await Promise.race([
    ensurePeerCaps(chat.peerNodeId),
    new Promise<void>((resolve) => {
      timer = setTimeout(() => {
        timedOut = true;
        resolve();
      }, CAPS_WAIT_MS);
    }),
  ]);
  clearTimeout(timer);
  if (timedOut) {
    capsQuietUntil = Date.now() + CAPS_QUIET_MS;
    profilesInFlight = null;
  }
}

export async function routeForChatEnsured(
  chat: Pick<Chat, 'type' | 'peerNodeId'> | undefined,
  size: number,
  v1Max: number,
): Promise<CarrierRoute> {
  await ensureChatCaps(chat);
  return routeForChat(chat, size, v1Max);
}
