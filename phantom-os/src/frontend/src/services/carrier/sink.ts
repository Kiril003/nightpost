import { openChunks, type CarrierManifest } from './seal';

export interface CarrierSink {
  write(part: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

/** Carrier C6: потокове розшифрування шматків у приймач без збирання всього файла в памʼяті. */
export async function saveCarrierTo(
  m: CarrierManifest,
  fetchChunk: (i: number) => Promise<Uint8Array>,
  sink: CarrierSink,
  onProgress?: (done: number, total: number) => void,
): Promise<void> {
  let i = 0;
  try {
    for await (const part of openChunks(m, fetchChunk)) {
      await sink.write(part);
      i += 1;
      onProgress?.(i, m.n);
    }
    await sink.close();
  } catch (err) {
    await sink.abort().catch(() => undefined);
    throw err;
  }
}

interface SaveFileWritable {
  write(data: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

interface SaveFileHandle {
  createWritable(): Promise<SaveFileWritable>;
}

type ShowSaveFilePicker = (options?: {
  suggestedName?: string;
  types?: Array<{ accept: Record<string, string[]> }>;
}) => Promise<SaveFileHandle>;

export async function pickFileSink(name: string, mime: string): Promise<CarrierSink | null> {
  const picker = (globalThis as { showSaveFilePicker?: ShowSaveFilePicker }).showSaveFilePicker;
  if (typeof picker !== 'function') return null;
  const cleanMime = (mime || '').split(';')[0].trim();
  const handle = await picker({
    suggestedName: name || 'file',
    ...(cleanMime && cleanMime.includes('/') ? { types: [{ accept: { [cleanMime]: [] } }] } : {}),
  });
  const writable = await handle.createWritable();
  return {
    write: (part: Uint8Array) => writable.write(part),
    close: () => writable.close(),
    abort: () => writable.abort(),
  };
}
