declare const __BUILD__: string | undefined;

export const BUILD: string = typeof __BUILD__ === 'string' ? __BUILD__ : 'dev';
const GAP_MS = 60_000;
const TRIED = 'np-fresh-tried';

const holds = new Set<() => boolean>();

export function hold(busy: () => boolean): () => void {
  holds.add(busy);
  return () => {
    holds.delete(busy);
  };
}

export function newer(mine: string, latest: unknown): latest is string {
  return mine !== 'dev' && typeof latest === 'string' && latest !== '' && latest !== mine;
}

export function safe(o: { busy: boolean; typing: boolean; tried: string | null; latest: string }): boolean {
  return !o.busy && !o.typing && o.tried !== o.latest;
}

const busy = () => [...holds].some((b) => b());
const typing = () => {
  const el = typeof document === 'undefined' ? null : document.activeElement;
  return el instanceof HTMLElement && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));
};
const tried = () => {
  try {
    return sessionStorage.getItem(TRIED);
  } catch {
    return null;
  }
};

export function startFresh(onRoute: (fn: () => void) => void): void {
  if (BUILD === 'dev' || typeof window === 'undefined') return;
  let latest: string | null = null;
  let last = 0;
  let asking = false;

  const reload = (chunk = false): boolean => {
    if (!latest || !safe({ busy: busy(), typing: !chunk && typing(), tried: tried(), latest })) return false;
    try {
      sessionStorage.setItem(TRIED, latest);
    } catch {
      /* no storage: one reload per build is still the most */
    }
    window.location.reload();
    return true;
  };

  const check = async () => {
    if (asking || Date.now() - last < GAP_MS) return;
    asking = true;
    last = Date.now();
    try {
      const r = await fetch(`/version.json?t=${last}`, { cache: 'no-store' });
      const v = r.ok ? ((await r.json()) as { build?: unknown }).build : null;
      if (newer(BUILD, v)) latest = v;
    } catch {
      /* offline or no file: stay */
    } finally {
      asking = false;
    }
  };

  const settle = () => {
    if (latest) return void reload();
    void check().then(() => latest && reload());
  };

  document.addEventListener('visibilitychange', () => settle());
  window.addEventListener('focus', () => settle());
  window.addEventListener('vite:preloadError', (e) => {
    latest = latest ?? 'chunk';
    if (reload(true)) e.preventDefault();
  });
  onRoute(() => settle());
}
