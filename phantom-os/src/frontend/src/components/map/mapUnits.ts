import { dateTag } from '../../i18n/dateTag';

export type MapUnit = 'second' | 'meter' | 'kilometer' | 'minute' | 'kilometer-per-hour';

export function fmtUnit(locale: string, value: number, unit: MapUnit, digits = 0): string {
  return new Intl.NumberFormat(dateTag(locale), {
    style: 'unit',
    unit,
    unitDisplay: 'short',
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value);
}

/** Metres below 950, otherwise kilometres with one decimal. */
export function fmtDistance(locale: string, m: number): string {
  return m < 950 ? fmtUnit(locale, Math.round(m / 10) * 10, 'meter') : fmtUnit(locale, m / 1000, 'kilometer', 1);
}
