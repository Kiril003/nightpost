import { stripMetadata } from '../stripMetadata';

/** Carrier v1: файл іде шматками по 1 МіБ, кожен запечатаний окремо; секрет — лише в E2E-листі. */
export const CARRIER_CHUNK = 1024 * 1024;
const INFO = new TextEncoder().encode('np-carrier-v1');

export interface CarrierManifest {
  v: 2;
  size: number;
  chunk: number;
  n: number;
  secret_hex: string;
  name: string;
  mime: string;
  dims?: { w: number; h: number };
}

export interface CarrierKeys {
  enc: CryptoKey;
  nonceBase: Uint8Array;
  tag: CryptoKey;
}

export class ChunkTampered extends Error {
  constructor(readonly index: number) {
    super(`chunk ${index} did not open`);
    this.name = 'ChunkTampered';
  }
}

const hex = (b: Uint8Array): string => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

const unhex = (s: string): Uint8Array => {
  if (!/^([0-9a-f]{2})+$/.test(s)) throw new Error('secret is not hex');
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(s.substr(i * 2, 2), 16);
  return out;
};

const be64 = (v: number): Uint8Array => {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, BigInt(v));
  return out;
};

export const chunkCount = (size: number, chunk = CARRIER_CHUNK): number => Math.max(1, Math.ceil(size / chunk));

export async function deriveKeys(secret: Uint8Array): Promise<CarrierKeys> {
  if (secret.length !== 32) throw new Error('secret must be 32 bytes');
  const base = await crypto.subtle.importKey('raw', secret as BufferSource, 'HKDF', false, ['deriveBits']);
  const bits = new Uint8Array(
    await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: INFO }, base, 76 * 8),
  );
  const enc = await crypto.subtle.importKey('raw', bits.slice(0, 32), 'AES-GCM', false, ['encrypt', 'decrypt']);
  const tag = await crypto.subtle.importKey('raw', bits.slice(44, 76), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return { enc, nonceBase: bits.slice(32, 44), tag };
}

export const keysOf = (m: Pick<CarrierManifest, 'secret_hex'>): Promise<CarrierKeys> => deriveKeys(unhex(m.secret_hex));

/** Назва шматка на ретрансляторі: однакова на обох кінцях, без звʼязку зі змістом. */
export async function chunkTag(keys: CarrierKeys, i: number): Promise<string> {
  const msg = new Uint8Array(9);
  msg[0] = 0x63;
  msg.set(be64(i), 1);
  return hex(new Uint8Array(await crypto.subtle.sign('HMAC', keys.tag, msg)).subarray(0, 16));
}

const nonceOf = (keys: CarrierKeys, i: number): Uint8Array => {
  const out = keys.nonceBase.slice();
  const ix = be64(i);
  for (let k = 0; k < 8; k += 1) out[4 + k] ^= ix[k];
  return out;
};

const aadOf = (i: number, n: number): Uint8Array => {
  const out = new Uint8Array(16);
  out.set(be64(i), 0);
  out.set(be64(n), 8);
  return out;
};

export async function sealChunk(keys: CarrierKeys, plain: Uint8Array, i: number, n: number): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: nonceOf(keys, i) as BufferSource, additionalData: aadOf(i, n) as BufferSource },
      keys.enc,
      plain as BufferSource,
    ),
  );
}

export async function openChunk(keys: CarrierKeys, sealed: Uint8Array, i: number, n: number): Promise<Uint8Array> {
  try {
    return new Uint8Array(
      await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: nonceOf(keys, i) as BufferSource, additionalData: aadOf(i, n) as BufferSource },
        keys.enc,
        sealed as BufferSource,
      ),
    );
  } catch {
    throw new ChunkTampered(i);
  }
}

const readSlice = (blob: Blob): Promise<Uint8Array> =>
  typeof blob.arrayBuffer === 'function'
    ? blob.arrayBuffer().then((b) => new Uint8Array(b))
    : new Promise((ok, fail) => {
        const r = new FileReader();
        r.onload = () => ok(new Uint8Array(r.result as ArrayBuffer));
        r.onerror = () => fail(r.error);
        r.readAsArrayBuffer(blob);
      });

export interface SealedChunk {
  i: number;
  tag: string;
  bytes: Uint8Array;
}

/** Шматки по черзі через Blob.slice: у памʼяті щонайбільше відкритий і запечатаний шматок. */
export async function* sealChunks(blob: Blob, keys: CarrierKeys, chunk = CARRIER_CHUNK): AsyncGenerator<SealedChunk> {
  const n = chunkCount(blob.size, chunk);
  for (let i = 0; i < n; i += 1) {
    const plain = await readSlice(blob.slice(i * chunk, Math.min(blob.size, (i + 1) * chunk)));
    yield { i, tag: await chunkTag(keys, i), bytes: await sealChunk(keys, plain, i, n) };
  }
}

export interface CarrierSeal {
  manifest: CarrierManifest;
  keys: CarrierKeys;
  chunks: AsyncGenerator<SealedChunk>;
}

/** Єдиний шлях печатки: спершу без метаданих, потім шматки. */
export async function sealCarrier(file: File, dims?: { w: number; h: number }): Promise<CarrierSeal> {
  const clean = await stripMetadata(file);
  const secret = crypto.getRandomValues(new Uint8Array(32));
  const keys = await deriveKeys(secret);
  const manifest: CarrierManifest = {
    v: 2,
    size: clean.size,
    chunk: CARRIER_CHUNK,
    n: chunkCount(clean.size),
    secret_hex: hex(secret),
    name: file.name,
    mime: file.type,
    ...(dims ? { dims } : {}),
  };
  return { manifest, keys, chunks: sealChunks(clean, keys) };
}

/** `maxBytes` — стеля, яку ми самі оголосили (`max_attachment_bytes`): більший маніфест не приймаємо. */
export function isCarrierManifest(x: unknown, maxBytes = Number.POSITIVE_INFINITY): x is CarrierManifest {
  const m = x as CarrierManifest;
  return (
    !!m &&
    m.v === 2 &&
    Number.isSafeInteger(m.size) &&
    m.size >= 0 &&
    m.size <= maxBytes &&
    m.chunk === CARRIER_CHUNK &&
    m.n === chunkCount(m.size, m.chunk) &&
    typeof m.secret_hex === 'string' &&
    /^[0-9a-f]{64}$/.test(m.secret_hex) &&
    typeof m.name === 'string' &&
    typeof m.mime === 'string'
  );
}

/** Більший файл цілим у памʼять WebView не збираємо: лише `openChunks` у файл. */
export const CARRIER_OPEN_MAX = 64 * 1024 * 1024;

/** Відкриті шматки по черзі; довжина кожного звіряється з маніфестом. */
export async function* openChunks(
  m: CarrierManifest,
  fetchChunk: (i: number, tag: string) => Promise<Uint8Array>,
): AsyncGenerator<Uint8Array> {
  const keys = await keysOf(m);
  for (let i = 0; i < m.n; i += 1) {
    const plain = await openChunk(keys, await fetchChunk(i, await chunkTag(keys, i)), i, m.n);
    const want = i < m.n - 1 ? m.chunk : m.size - m.chunk * (m.n - 1);
    if (plain.length !== want) throw new ChunkTampered(i);
    yield plain;
  }
}

export async function openCarrier(
  m: CarrierManifest,
  fetchChunk: (i: number, tag: string) => Promise<Uint8Array>,
): Promise<Blob> {
  if (m.size > CARRIER_OPEN_MAX) throw new RangeError('file too large to open in memory');
  const parts: Uint8Array[] = [];
  for await (const plain of openChunks(m, fetchChunk)) parts.push(plain);
  return new Blob(parts as BlobPart[], { type: m.mime || 'application/octet-stream' });
}
