import { clearMediaCache, mediaCacheSize } from './mediaStorageCache';
import { tNow } from '../components/chat/tNow';

export interface StorageUsage {
  /** Усе, що сторінка тримає (оцінка браузера); null — браузер не каже. */
  used: number | null;
  quota: number | null;
  /** Кеш розшифрованих медіа; null — сховища немає. */
  media: number | null;
}

export const formatBytes = (
  n: number,
  units: readonly string[] = [tNow('units.b'), tNow('units.kb'), tNow('units.mb'), tNow('units.gb')],
): string => {
  if (n < 1024) return `${Math.round(n)} ${units[0]}`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} ${units[1]}`;
  const mb = n / (1024 * 1024);
  if (mb < 1024) return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} ${units[2]}`;
  return `${(mb / 1024).toFixed(1)} ${units[3]}`;
};

export async function readStorageUsage(): Promise<StorageUsage> {
  let used: number | null = null;
  let quota: number | null = null;
  try {
    const est = await navigator.storage?.estimate?.();
    if (est) {
      used = typeof est.usage === 'number' ? est.usage : null;
      quota = typeof est.quota === 'number' ? est.quota : null;
    }
  } catch {
    /* браузер не каже — рядка не буде */
  }
  return { used, quota, media: await mediaCacheSize() };
}

/** Стирає лише кеш медіа: його можна завантажити знову, розмови й ключі не чіпає. */
export const clearStorageCache = (): Promise<void> => clearMediaCache();
