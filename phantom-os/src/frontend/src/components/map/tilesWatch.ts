import { tNow } from '../chat/tNow';
export const TILES_OK_TO_CLEAR = 3;

interface Evented {
  on(evt: string, cb: (e: any) => void): unknown;
  off(evt: string, cb: (e: any) => void): unknown;
}

export function tilesReason(msg: string): string {
  const m = (msg || '').toLowerCase();
  if (m.includes('failed to fetch') || m.includes('networkerror') || m.includes('load failed')) return tNow('tiles.noLink');
  if (m.includes('content security') || m.includes('csp') || m.includes('refused')) return tNow('tiles.blocked');
  const code = /\b(4\d\d|5\d\d)\b/.exec(m)?.[1];
  if (code) return tNow('tiles.status', { code });
  return tNow('tiles.silent');
}

/** Only the detailed base source counts; the banner flips once each way. */
export function watchBaseTiles(map: Evented, onChange: (down: string | null) => void): () => void {
  let down: string | null = null;
  let okInRow = 0;
  const onError = (e: any) => {
    if (e?.sourceId !== 'openmaptiles') return;
    okInRow = 0;
    if (down !== null) return;
    down = tilesReason(e?.error?.message ?? '');
    onChange(down);
  };
  const onData = (e: any) => {
    if (e?.sourceId !== 'openmaptiles' || !e?.tile || down === null) return;
    okInRow += 1;
    if (okInRow < TILES_OK_TO_CLEAR) return;
    down = null;
    okInRow = 0;
    onChange(null);
  };
  map.on('error', onError);
  map.on('data', onData);
  return () => {
    map.off('error', onError);
    map.off('data', onData);
  };
}
