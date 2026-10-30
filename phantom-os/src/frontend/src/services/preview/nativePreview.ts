export type NativePreviewKind = 'pdf' | 'heic';

interface PreviewBridge {
  previewBegin: (kind: string, name: string, mime: string, size: number) => string;
  previewChunk: (id: string, seq: number, b64: string) => boolean;
  previewOpen: (id: string) => boolean;
}

const MIB = 1024 * 1024;
export const NATIVE_PREVIEW_MAX: Record<NativePreviewKind, number> = { pdf: 200 * MIB, heic: 50 * MIB };
export const NATIVE_CHUNK_RAW = 600 * 1024;

function bridge(): PreviewBridge | null {
  if (typeof window === 'undefined') return null;
  const b = (window as unknown as { PhantomBridge?: Partial<PreviewBridge> }).PhantomBridge;
  if (!b || typeof b.previewBegin !== 'function' || typeof b.previewChunk !== 'function' || typeof b.previewOpen !== 'function') {
    return null;
  }
  return b as PreviewBridge;
}

function extOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
}

export function nativeKindFor(name: string, mime: string): NativePreviewKind | null {
  const ext = extOf(name || '');
  const m = (mime || '').toLowerCase().split(';')[0].trim();
  if (ext === 'pdf' || m === 'application/pdf') return 'pdf';
  if (ext === 'heic' || ext === 'heif' || m === 'image/heic' || m === 'image/heif') return 'heic';
  return null;
}

export function nativePreviewFor(name: string, mime: string, size: number): NativePreviewKind | null {
  const kind = nativeKindFor(name, mime);
  if (!kind || !bridge()) return null;
  if (!Number.isFinite(size) || size <= 0 || size > NATIVE_PREVIEW_MAX[kind]) return null;
  return kind;
}

function toBase64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(bin);
}

function readBytes(blob: Blob): Promise<Uint8Array> {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer().then((buf) => new Uint8Array(buf));
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
}

let lastOpenRejected = false;

export function wasNativeOpenRejected(): boolean {
  return lastOpenRejected;
}

export async function openInNativeViewer(kind: NativePreviewKind, blob: Blob, name: string, mime: string): Promise<boolean> {
  lastOpenRejected = false;
  const b = bridge();
  if (!b || blob.size <= 0 || blob.size > NATIVE_PREVIEW_MAX[kind]) return false;
  try {
    const id = b.previewBegin(kind, name, mime, blob.size);
    if (!id) return false;
    for (let seq = 0, at = 0; at < blob.size; seq += 1, at += NATIVE_CHUNK_RAW) {
      const piece = await readBytes(blob.slice(at, at + NATIVE_CHUNK_RAW));
      if (!b.previewChunk(id, seq, toBase64(piece))) return false;
    }
    const opened = b.previewOpen(id) === true;
    if (!opened) lastOpenRejected = true;
    return opened;
  } catch {
    return false;
  }
}
