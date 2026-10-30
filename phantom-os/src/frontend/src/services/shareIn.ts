import { request } from './api';

export interface ShareItem {
  index: number;
  kind: 'text' | 'image' | 'place';
  text: string | null;
  mime: string | null;
  name: string | null;
  size: number;
  lat?: number;
  lon?: number;
}

export interface PendingShare {
  id: string;
  items: ShareItem[];
  skipped: number;
}

interface WirePendingShare {
  id?: unknown;
  items?: unknown;
  skipped?: unknown;
}

interface WireShareItem {
  index?: unknown;
  kind?: unknown;
  text?: unknown;
  mime?: unknown;
  name?: unknown;
  size?: unknown;
  lat?: unknown;
  lon?: unknown;
}

interface WireItemBlob {
  mime?: unknown;
  name?: unknown;
  base64?: unknown;
}

declare global {
  interface Window {
    __phantomShareIn?: () => boolean;
  }
}

export async function fetchPendingShare(): Promise<PendingShare | null> {
  try {
    const raw = await request<WirePendingShare>('GET', '/messenger/share/pending');
    if (!raw || typeof raw.id !== 'string' || !raw.id) return null;
    const list = Array.isArray(raw.items) ? (raw.items as WireShareItem[]) : [];
    const items: ShareItem[] = list
      .filter((r): r is WireShareItem & { index: number; kind: ShareItem['kind'] } =>
        Boolean(r) &&
        typeof r.index === 'number' &&
        (r.kind === 'text' ||
          r.kind === 'image' ||
          (r.kind === 'place' && Number.isFinite(r.lat) && Number.isFinite(r.lon))),
      )
      .map((r) => ({
        index: r.index,
        kind: r.kind,
        text: typeof r.text === 'string' ? r.text : null,
        mime: typeof r.mime === 'string' ? r.mime : null,
        name: typeof r.name === 'string' ? r.name : null,
        size: typeof r.size === 'number' ? r.size : 0,
        ...(r.kind === 'place' ? { lat: r.lat as number, lon: r.lon as number } : {}),
      }));
    const skipped = typeof raw.skipped === 'number' ? raw.skipped : 0;
    if (items.length === 0 && skipped === 0) return null;
    return { id: raw.id, items, skipped };
  } catch {
    return null;
  }
}

function base64ToBytes(b64: string): Uint8Array<ArrayBuffer> {
  const bin = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i += 1) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

export async function shareFiles(pending: PendingShare): Promise<File[]> {
  const images = pending.items.filter((i) => i.kind === 'image');
  const files: File[] = [];
  for (const item of images) {
    const res = await request<WireItemBlob>('GET', `/messenger/share/item/${encodeURIComponent(String(item.index))}`);
    if (!res || typeof res.base64 !== 'string') continue;
    const mime = typeof res.mime === 'string' && res.mime ? res.mime : item.mime ?? 'image/jpeg';
    const name = typeof res.name === 'string' && res.name ? res.name : item.name ?? `${item.index}.jpg`;
    const bytes = base64ToBytes(res.base64);
    files.push(new File([bytes], name, { type: mime }));
  }
  return files;
}

export async function clearShare(): Promise<void> {
  await request('DELETE', '/messenger/share/pending').catch(() => undefined);
}

export function installShareIn(open: () => void): () => void {
  window.__phantomShareIn = () => {
    open();
    return true;
  };
  return () => {
    if (window.__phantomShareIn) {
      delete window.__phantomShareIn;
    }
  };
}
