/**
 * Де вузол відносно сторінки.
 *
 * У браузері й у телефоні сторінку віддає той самий хост, що й API, тож
 * адреси відносні (`/api/v1`, `/ws`). У настільній оболонці Tauri сторінка
 * живе на `tauri://localhost` (Linux, macOS) або `http://tauri.localhost`
 * (Windows) — відносний `/api/v1` пішов би туди, а не до вузла. Там вузол —
 * sidecar на петлі :8000; ту саму адресу пускає CSP вікна
 * (`src-tauri/tauri.conf.json`: connect-src) і CORS вузла (`config.py`).
 */
export const DESK_NODE_ORIGIN = 'http://127.0.0.1:8000';

export function inDeskShell(loc: Pick<Location, 'protocol' | 'hostname'> | undefined =
  typeof window !== 'undefined' ? window.location : undefined): boolean {
  if (!loc) return false;
  return loc.protocol === 'tauri:' || loc.hostname === 'tauri.localhost';
}

/** '' — той самий хост, що й сторінка; інакше абсолютне походження вузла. */
export function nodeOrigin(): string {
  return inDeskShell() ? DESK_NODE_ORIGIN : '';
}

/** Адреса WebSocket вузла для шляху (`/ws`). */
export function nodeWsUrl(path: string): URL {
  if (inDeskShell()) {
    const url = new URL(path, DESK_NODE_ORIGIN);
    url.protocol = 'ws:';
    return url;
  }
  const url = new URL(path, window.location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url;
}
