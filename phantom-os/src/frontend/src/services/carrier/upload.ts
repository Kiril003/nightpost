import { ApiError } from '../api';
import { messengerApi, type NodeCarrierRecord } from '../messengerApi';
import { sealCarrier, chunkTag, type CarrierManifest } from './seal';

export interface CarrierSent {
  transferId: string;
  manifest: CarrierManifest;
}

export interface CarrierUploadOptions {
  retries?: number;
  sleep?: (ms: number) => Promise<void>;
}

export class CarrierUploadFailed extends Error {
  constructor(
    readonly index: number,
    readonly transferId: string,
    readonly cause?: unknown,
  ) {
    super(`chunk ${index} did not arrive`);
    this.name = 'CarrierUploadFailed';
  }
}

export function isRetryable(err: unknown): boolean {
  if (err instanceof TypeError) return true;
  if (err instanceof ApiError) {
    return err.status >= 500 || err.status === 408 || err.status === 429;
  }
  return false;
}

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

async function putChunkWithRetry(
  transferId: string,
  index: number,
  bytes: Uint8Array,
  retries: number,
  sleep: (ms: number) => Promise<void>,
): Promise<NodeCarrierRecord> {
  let lastErr: unknown;
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    try {
      return await messengerApi.carrierPutChunk(transferId, index, bytes);
    } catch (err) {
      lastErr = err;
      if (!isRetryable(err) || attempt >= retries) {
        throw new CarrierUploadFailed(index, transferId, err);
      }
      await sleep(Math.min(500 * 2 ** attempt, 4000));
    }
  }
  throw new CarrierUploadFailed(index, transferId, lastErr);
}

/** v2: шматки на власний вузол по одному; на вузол іде лише розмір, n і теги, секрет — у маніфест листа. */
export async function sendCarrier(
  file: File,
  onProgress?: (percent: number) => void,
  dims?: { w: number; h: number },
  opts?: CarrierUploadOptions,
): Promise<CarrierSent> {
  const retries = opts?.retries ?? 3;
  const sleep = opts?.sleep ?? defaultSleep;
  const sealed = await sealCarrier(file, dims);
  const { manifest, keys } = sealed;
  const tags = await Promise.all(Array.from({ length: manifest.n }, (_, i) => chunkTag(keys, i)));
  let rec = await messengerApi.carrierCreate(manifest.size, manifest.n, tags);
  let have = new Set(rec.received);
  let done = 0;
  try {
    for await (const c of sealed.chunks) {
      if (!have.has(c.i)) {
        rec = await putChunkWithRetry(rec.transfer_id, c.i, c.bytes, retries, sleep);
        have = new Set(rec.received);
      }
      done += 1;
      onProgress?.(Math.round((done / manifest.n) * 100));
    }
    if (rec.count !== manifest.n) {
      let firstMissing = 0;
      while (firstMissing < manifest.n && have.has(firstMissing)) {
        firstMissing += 1;
      }
      throw new CarrierUploadFailed(firstMissing, rec.transfer_id);
    }
  } catch (err) {
    const tid = rec.transfer_id;
    void Promise.allSettled([...have].map((i) => messengerApi.carrierDropChunk(tid, i)));
    throw err;
  }
  return { transferId: rec.transfer_id, manifest };
}
