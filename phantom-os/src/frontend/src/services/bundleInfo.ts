export interface BundleInfo {
  short: string;
  syncedAt: string | null;
}

/** BUNDLE_SOURCE — печатка бандла, яку пише sync_web_messenger.sh. */
export const parseBundleSource = (text: string): BundleInfo | null => {
  const field = (k: string) => new RegExp(`^${k}=(.+)$`, 'm').exec(text)?.[1]?.trim() ?? null;
  const short = field('web_short');
  if (!short || !/^[0-9a-f]{7,12}$/.test(short)) return null;
  return { short, syncedAt: field('synced_at') };
};

/** Поруч з index.html у застосунку; в браузері чи на ПК файла немає — тоді null. */
export async function readBundleInfo(): Promise<BundleInfo | null> {
  try {
    const res = await fetch(new URL('BUNDLE_SOURCE', document.baseURI).toString(), { cache: 'no-store' });
    if (!res.ok) return null;
    return parseBundleSource(await res.text());
  } catch {
    return null;
  }
}
