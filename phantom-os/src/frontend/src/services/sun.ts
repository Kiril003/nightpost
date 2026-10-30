/**
 * Висота сонця над обрієм — щоб тема йшла за справжнім сонцем у точці людини.
 * Вікна точок — `services/themeRamp.ts` (ті самі, що в рідного `SunLook`).
 *
 * Формули — NOAA (наближення Spencer/Meeus), похибка висоти < 0,5°, тобто
 * хвилина-дві на перемиканні. Більшого тут не треба.
 */

export interface SunPlace {
  lat: number;
  lon: number;
}

const RAD = Math.PI / 180;

/** Висота сонця, градуси, у момент `date` для точки `place`. */
export function solarElevation(date: Date, place: SunPlace): number {
  const ms = date.getTime();
  const dayMs = 86_400_000;
  const startOfYear = Date.UTC(date.getUTCFullYear(), 0, 1);
  const dayOfYear = Math.floor((ms - startOfYear) / dayMs); // 0-based
  const utcHours = date.getUTCHours() + date.getUTCMinutes() / 60 + date.getUTCSeconds() / 3600;
  const g = ((2 * Math.PI) / 365) * (dayOfYear + (utcHours - 12) / 24);
  const eqTime =
    229.18 *
    (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
  const decl =
    0.006918 -
    0.399912 * Math.cos(g) +
    0.070257 * Math.sin(g) -
    0.006758 * Math.cos(2 * g) +
    0.000907 * Math.sin(2 * g) -
    0.002697 * Math.cos(3 * g) +
    0.00148 * Math.sin(3 * g);
  const trueSolarMin = utcHours * 60 + eqTime + 4 * place.lon;
  const hourAngle = (trueSolarMin / 4 - 180) * RAD;
  const lat = place.lat * RAD;
  const cosZenith = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(hourAngle);
  const zenith = Math.acos(Math.max(-1, Math.min(1, cosZenith)));
  return 90 - zenith / RAD;
}

const PLACE_KEY = 'phantom.sunPlace';

/**
 * Де людина. Порядок: збережене місце (його кладе мапа або міст, коли знає
 * координати) → оцінка з годинного поясу цього пристрою: довгота з різниці
 * з UTC (15° на годину), широта — Київ. Для перемикання теми це дає хвилини
 * похибки, а не години: межа сутінків від широти залежить слабко.
 */
export function currentSunPlace(date: Date = new Date()): SunPlace {
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(PLACE_KEY) : null;
    if (raw) {
      const p = JSON.parse(raw) as Partial<SunPlace>;
      if (typeof p.lat === 'number' && typeof p.lon === 'number' && Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180) {
        return { lat: p.lat, lon: p.lon };
      }
    }
  } catch {
    /* немає сховища — оцінка нижче */
  }
  // Зимовий зсув (без літнього часу) — щоб годинник не тягнув захід на годину.
  const jan = new Date(date.getFullYear(), 0, 1).getTimezoneOffset();
  const jul = new Date(date.getFullYear(), 6, 1).getTimezoneOffset();
  const standardOffsetMin = Math.max(jan, jul);
  return { lat: 50.45, lon: Math.max(-180, Math.min(180, (-standardOffsetMin / 60) * 15)) };
}

/** Запамʼятати точку людини (мапа, міст) — тема далі рахує сонце саме тут. */
export function rememberSunPlace(place: SunPlace): void {
  try {
    localStorage.setItem(PLACE_KEY, JSON.stringify({ lat: +place.lat.toFixed(2), lon: +place.lon.toFixed(2) }));
  } catch {
    /* без сховища — оцінка з поясу */
  }
}
