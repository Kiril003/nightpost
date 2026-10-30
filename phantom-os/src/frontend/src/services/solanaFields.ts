/**
 * Поля `payment` і `seal` з мосту телефона → форма для картки стрічки.
 *
 * Контракт — `docs/solana/PAYMENT_CARD_SPEC.md`; пише їх
 * `WebMessengerKinds.solana` (форма) і `WebMessengerSolana.decorate` (стан
 * мережі, при кожній віддачі рядка). Сирий дріт (`PH-PAY:` …) сюди не їде.
 *
 * Перевірка сувора навмисно: картка з сумою чи підписом, які не мають форми,
 * була б упевненою неправдою. Криве поле дає `undefined` — і стрічка показує
 * запасне речення `body`, яке склав сам телефон.
 */
import type { MessagePayment, MessageSeal, SolanaCluster, SolanaPaymentStatus } from '../types/messenger';

const TOKENS = new Set(['SOL', 'USDC', 'EURC', 'SKR']);
const CLUSTERS = new Set(['devnet', 'mainnet']);
const PAY_STATUS = new Set(['pending', 'confirmed', 'failed', 'mismatch', 'unknown']);
/** base58: без 0, O, I, l. Підпис ~88 знаків, адреса 32–44. */
const BASE58 = /^[1-9A-HJ-NP-Za-km-z]+$/;
const DECIMAL = /^\d+(\.\d+)?$/;
const SKR_TIERS = new Set(['staker', '1k', '10k']);
const SHA256 = /^[0-9a-f]{64}$/;
/** Id рахунку з `PaymentFrame` v2 — дзеркало межі кадру (≤ 36 знаків). */
const REF = /^[A-Za-z0-9_-]{1,36}$/;

const obj = (v: unknown): Record<string, unknown> | null =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;

const b58 = (v: unknown, min: number, max: number): string | null =>
  typeof v === 'string' && v.length >= min && v.length <= max && BASE58.test(v) ? v : null;

/** Незнане слово стану — «невідомо», а не найкраще з можливих. */
const statusOf = (v: unknown): SolanaPaymentStatus =>
  typeof v === 'string' && PAY_STATUS.has(v) ? (v as SolanaPaymentStatus) : 'unknown';

export function readPayment(raw: unknown): MessagePayment | undefined {
  const p = obj(raw);
  if (!p) return undefined;
  const amount = typeof p.amount === 'string' && DECIMAL.test(p.amount) ? p.amount : null;
  const token = typeof p.token === 'string' && TOKENS.has(p.token) ? (p.token as MessagePayment['token']) : null;
  const cluster = typeof p.cluster === 'string' && CLUSTERS.has(p.cluster) ? (p.cluster as SolanaCluster) : null;
  const to = b58(p.to, 32, 44);
  const signature = b58(p.signature, 32, 100);
  if (!amount || !token || !cluster || !to || !signature) return undefined;
  return {
    amount,
    token,
    cluster,
    to,
    signature,
    offlineCash: p.offline_cash === true,
    seekerVerified: p.seeker_verified === true,
    seekerSkrTier: typeof p.seeker_skr_tier === 'string' && SKR_TIERS.has(p.seeker_skr_tier) ? (p.seeker_skr_tier as MessagePayment['seekerSkrTier']) : null,
    seekerLabel: typeof p.seeker_label === 'string' && p.seeker_label.trim() && p.seeker_label.length <= 120 ? p.seeker_label.trim() : null,
    status: statusOf(p.status),
    toMine: p.to_mine === true ? true : p.to_mine === false ? false : null,
    // Переказ v1 без `ref` ні до чого не привʼязаний: частку рахунку він не оплачує.
    ref: typeof p.ref === 'string' && REF.test(p.ref) ? p.ref : null,
  };
}

export function readSeal(raw: unknown): MessageSeal | undefined {
  const s = obj(raw);
  if (!s) return undefined;
  const sha256 = typeof s.sha256 === 'string' && SHA256.test(s.sha256.toLowerCase()) ? s.sha256.toLowerCase() : null;
  const cluster = typeof s.cluster === 'string' && CLUSTERS.has(s.cluster) ? (s.cluster as SolanaCluster) : null;
  const signature = b58(s.signature, 32, 100);
  if (!sha256 || !cluster || !signature) return undefined;
  const status = statusOf(s.status);
  const t = typeof s.block_time === 'number' && Number.isFinite(s.block_time) && s.block_time > 0 ? s.block_time : null;
  return { sha256, signature, cluster, status, blockTime: status === 'confirmed' ? t : null };
}

/** «4WVS…mZHT»: початок і кінець довгого ідентифікатора. */
export const shortChainId = (s: string, head = 4, tail = 4): string =>
  s.length > head + tail + 1 ? `${s.slice(0, head)}…${s.slice(-tail)}` : s;

/** Посилання на оглядач: devnet — з параметром, mainnet — без (специфікація). */
export function explorerTxUrl(signature: string, cluster: SolanaCluster): string {
  const base = `https://explorer.solana.com/tx/${encodeURIComponent(signature)}`;
  return cluster === 'devnet' ? `${base}?cluster=devnet` : base;
}
