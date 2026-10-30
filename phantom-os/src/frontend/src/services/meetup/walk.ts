/**
 * «Вийти за N хв» — лише з власної свіжої точки й точки місця, без вигадок.
 * Пішки 75 м/хв по прямій × 1,3 на вулиці; нема точки — нема рядка.
 */
import { useEffect, useState } from 'react';

export interface Fix {
  lat: number;
  lon: number;
  atMs: number;
}

const M_PER_MIN = 75;
const DETOUR = 1.3;
const FRESH_MS = 5 * 60_000;
/** Рядок з'являється за годину до виходу, не раніше. */
const SHOW_BEFORE_MS = 60 * 60_000;

export function metersBetween(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const r = 6_371_000;
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
}

export function walkMinutes(from: { lat: number; lon: number }, to: { lat: number; lon: number }): number {
  return Math.max(1, Math.round((metersBetween(from, to) * DETOUR) / M_PER_MIN));
}

/**
 * Коли виходити: `{ walk, leaveIn }` у хвилинах, або null — точка застара,
 * місце поруч (до 150 м), вже пора чи ще зарано.
 */
export function leavePlan(
  fix: Fix | null,
  venue: { lat: number | null; lon: number | null },
  at: number,
  now: number,
): { walk: number; leaveIn: number } | null {
  if (!fix || venue.lat === null || venue.lon === null) return null;
  if (now - fix.atMs > FRESH_MS) return null;
  const to = { lat: venue.lat, lon: venue.lon };
  if (metersBetween(fix, to) < 150) return null;
  const walk = walkMinutes(fix, to);
  const leaveAt = at - walk * 60_000;
  if (leaveAt - now > SHOW_BEFORE_MS) return null;
  return { walk, leaveIn: Math.max(0, Math.round((leaveAt - now) / 60_000)) };
}

/** Одна точка з пристрою, лише коли `active` (іду, до зустрічі ≤ 2 год). Без стеження. */
export function useOwnFix(active: boolean): Fix | null {
  const [fix, setFix] = useState<Fix | null>(null);
  useEffect(() => {
    if (!active || typeof navigator === 'undefined' || !navigator.geolocation) return;
    let live = true;
    navigator.geolocation.getCurrentPosition(
      (p) => live && setFix({ lat: p.coords.latitude, lon: p.coords.longitude, atMs: p.timestamp || Date.now() }),
      () => {},
      { enableHighAccuracy: false, timeout: 15_000, maximumAge: FRESH_MS },
    );
    return () => {
      live = false;
    };
  }, [active]);
  return fix;
}
