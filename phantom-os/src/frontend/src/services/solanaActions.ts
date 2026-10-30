/**
 * Дії Solana з розмови — «Переказати» і «Засвідчити». Дзеркало
 * `WebMessengerSolana.handle` у Kotlin; контракт — docs/solana/PAYMENT_CARD_SPEC.md.
 *
 * Веб лише ПИТАЄ телефон: сума й підтвердження відбитком — на рідному екрані
 * гаманця, картку в розмову шле сам телефон (`SolanaLayer.payPeer` → `send`),
 * тож тут нічого не дописується в стрічку. Без мосту (ПК, вузол) дій немає:
 * вузол маршрутів `/messenger/solana/*` не має.
 */
import { useCallback, useEffect, useState } from 'react';
import { request } from './api';
import { phoneBridge } from './messengerApi';
import { loadMedia } from './messengerMedia';
import { wherePeerOf } from './peerWhere';
import type { Chat, Message, SolanaCluster } from '../types/messenger';

export type SolanaCoin = 'USDC' | 'EURC' | 'SOL' | 'SKR';

/** Відповідь `GET /messenger/solana/peer/{id}`. `address` — МІЙ активний гаманець. */
export interface SolanaPeer {
  address: string | null;
  can_pay: boolean;
  cluster: SolanaCluster;
  seeker: boolean;
}

/** Тіло `POST /messenger/solana/pay`: `token`/`amount` лише заповнюють рідний екран. */
export interface PayAsk {
  peer_id: string;
  token: SolanaCoin;
  amount: string;
  chat_id?: string;
  ref?: string;
}

/** Тіло `POST /messenger/solana/seal`: лише відбиток, ніколи не файл. */
export interface SealAsk {
  sha256: string;
  label: string;
  peer_id?: string;
}

const DECIMALS: Record<SolanaCoin, number> = { USDC: 6, EURC: 6, SOL: 9, SKR: 6 };

/** Монети, які телефон пропонує в цій мережі (`Coin.on`), у тому ж порядку: SKR є лише в mainnet. */
export function coinsFor(cluster: SolanaCluster): SolanaCoin[] {
  return cluster === 'mainnet' ? ['USDC', 'EURC', 'SOL', 'SKR'] : ['USDC', 'EURC', 'SOL'];
}

/**
 * Сума з поля → десятковий рядок або null. Як `Amounts.toUnits`: «1,5» = «1.5»,
 * нуль і мінус — відмова, більше знаків, ніж має монета, — відмова, а не округлення.
 */
export function amountOf(text: string, coin: SolanaCoin): string | null {
  const clean = text.trim().replace(',', '.').replace(/\s/g, '');
  if (!/^\d+(\.\d+)?$/.test(clean)) return null;
  const [whole, frac = ''] = clean.split('.');
  const fracTrim = frac.replace(/0+$/, '');
  if (fracTrim.length > DECIMALS[coin]) return null;
  if (!/[1-9]/.test(whole + fracTrim)) return null;
  const head = whole.replace(/^0+(?=\d)/, '');
  return fracTrim ? `${head}.${fracTrim}` : head;
}

const peerPath = (id: string) => `/messenger/solana/peer/${encodeURIComponent(id)}`;

export interface EscrowState {
  on: boolean;
  cluster: SolanaCluster | null;
  program: string | null;
}

const ESCROW_OFF: EscrowState = { on: false, cluster: null, program: null };

export async function escrow(): Promise<EscrowState> {
  if (!phoneBridge()) return ESCROW_OFF;
  try {
    const raw = await request<unknown>('GET', '/messenger/solana/escrow');
    if (!raw || typeof raw !== 'object') return ESCROW_OFF;
    const d = raw as Record<string, unknown>;
    if (d.on === true && d.cluster === 'devnet' && typeof d.program === 'string' && d.program !== '') {
      return { on: true, cluster: 'devnet', program: d.program };
    }
    return ESCROW_OFF;
  } catch {
    return ESCROW_OFF;
  }
}

export type EscrowBillStatus = 'open' | 'settled' | 'cancelled' | 'held' | 'released' | 'refunded' | 'forfeited';

export interface EscrowBill {
  status: EscrowBillStatus;
  paid: number;
  total: number;
  deadline: number | null;
}

const ESCROW_BILL_STATUSES: readonly EscrowBillStatus[] = [
  'open',
  'settled',
  'cancelled',
  'held',
  'released',
  'refunded',
  'forfeited',
];

export async function escrowBill(address: string): Promise<EscrowBill | null> {
  if (!phoneBridge()) return null;
  try {
    const raw = await request<unknown>('GET', `/messenger/solana/escrow/bill/${encodeURIComponent(address)}`);
    if (!raw || typeof raw !== 'object') return null;
    const d = raw as Record<string, unknown>;
    if (typeof d.status !== 'string' || !ESCROW_BILL_STATUSES.includes(d.status as EscrowBillStatus)) return null;
    if (typeof d.paid !== 'number' || !Number.isInteger(d.paid) || d.paid < 0) return null;
    if (typeof d.total !== 'number' || !Number.isInteger(d.total) || d.total < 1) return null;
    if (d.paid > d.total) return null;
    if (d.deadline !== null && (typeof d.deadline !== 'number' || !Number.isFinite(d.deadline))) return null;
    return {
      status: d.status as EscrowBillStatus,
      paid: d.paid,
      total: d.total,
      deadline: d.deadline,
    };
  } catch {
    return null;
  }
}

export const solanaApi = {
  peer: (peerId: string) => request<SolanaPeer>('GET', peerPath(peerId)),
  pay: (ask: PayAsk) => request<{ opened: boolean }>('POST', '/messenger/solana/pay', ask),
  seal: (ask: SealAsk) => request<{ opened: boolean }>('POST', '/messenger/solana/seal', ask),
};

/** Людина з розмови, якій телефон може щось показати: лише телефон, лише вдвох. */
export const solanaPeerOf = wherePeerOf;

/**
 * Чи можна переказати цій людині просто зараз. null — пункту немає: немає мосту,
 * міст не знає маршруту (старий APK), немає мого гаманця або її адреси.
 */
export function usePayPeer(peer: { id: string; name: string } | null): {
  pay: SolanaPeer | null;
  refresh: () => void;
} {
  const [pay, setPay] = useState<SolanaPeer | null>(null);
  const [tick, setTick] = useState(0);
  const id = peer?.id ?? null;
  useEffect(() => {
    setPay(null);
    if (!id || !phoneBridge()) return;
    let alive = true;
    solanaApi.peer(id).then(
      (p) => alive && setPay(p && p.can_pay === true && p.address ? p : null),
      () => alive && setPay(null),
    );
    return () => {
      alive = false;
    };
  }, [id, tick]);
  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { pay, refresh };
}

/** «Засвідчити» є лише в листа з файлом, що вже тут, і лише в розмові вдвох на телефоні. */
export function canSeal(m: Message, chat: Pick<Chat, 'peerNodeId' | 'type' | 'title'> | undefined): boolean {
  if (!phoneBridge() || !solanaPeerOf(chat)) return false;
  if (m.isDeleted || !m.media?.blobId) return false;
  return m.attachmentState !== 'upload_failed';
}

export async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(d, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Відбиток РОЗШИФРОВАНОГО файла: його й перевірить будь-хто, хто має файл. */
export async function fileOf(m: Message): Promise<{ sha256: string; name: string }> {
  if (!m.media) throw new Error('no_file');
  const url = await loadMedia(m.media);
  const bytes = await (await fetch(url)).arrayBuffer();
  return { sha256: await sha256Hex(bytes), name: m.media.name.slice(0, 80) };
}

export async function sealAskFor(m: Message, peerId: string): Promise<SealAsk> {
  const f = await fileOf(m);
  return { sha256: f.sha256, label: f.name, peer_id: peerId };
}

// ── хто що попросив: аркуші живуть поза меню, яке закривається першим ──

export type SolanaAsk =
  | { kind: 'pay'; peer: { id: string; name: string }; cluster: SolanaCluster; chat?: { id: string; ref?: string; amount?: string; token?: SolanaCoin; note?: string } }
  | { kind: 'seal'; peer: { id: string; name: string }; msg: Message }
  | { kind: 'group'; chatId: string; file?: { sha256: string; name: string }; peer?: { id: string; name: string } };

let current: SolanaAsk | null = null;
const listeners = new Set<() => void>();

export function askSolana(a: SolanaAsk | null): void {
  current = a;
  listeners.forEach((fn) => fn());
}

export function useSolanaAsk(): SolanaAsk | null {
  const [a, setA] = useState<SolanaAsk | null>(current);
  useEffect(() => {
    const fn = () => setA(current);
    listeners.add(fn);
    fn();
    return () => {
      listeners.delete(fn);
    };
  }, []);
  return a;
}
