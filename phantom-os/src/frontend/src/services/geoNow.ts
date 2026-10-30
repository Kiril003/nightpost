import type { GeoPoint } from '../types/messenger';

/** Чому точки немає — ключ рядка messageComposer.*. */
export type GeoNowFail = 'geoUnsupported' | 'geoPermission' | 'geoTimeout' | 'geoFailed';

/**
 * Одна точка з пристрою, з часом ВИМІРУ. Немає дозволу, координат чи часу —
 * відмова з причиною, а не остання відома точка чи нуль на екваторі.
 */
export function geoNow(): Promise<GeoPoint> {
  return new Promise((resolve, reject: (why: GeoNowFail) => void) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      reject('geoUnsupported');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        resolve({
          lat: pos.coords.latitude,
          lon: pos.coords.longitude,
          atMs: pos.timestamp || Date.now(),
          ...(Number.isFinite(pos.coords.accuracy) ? { accuracyM: Math.round(pos.coords.accuracy) } : {}),
        }),
      (err) =>
        reject(err.code === err.PERMISSION_DENIED ? 'geoPermission' : err.code === err.TIMEOUT ? 'geoTimeout' : 'geoFailed'),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
    );
  });
}
