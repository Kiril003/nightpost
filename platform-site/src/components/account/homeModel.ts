import type { Account, Build, Me } from '../../lib/account-api';
import type { Lang } from '../../i18n/types';
import type { Venue } from '../../lib/venuesApi';

export function placesFailure(err: unknown, notOpen: abstract new (...args: never[]) => unknown): 'notOpen' | 'failed' {
  return err instanceof notOpen ? 'notOpen' : 'failed';
}

export function displayName(a: Account): string {
  if (a.display_name) return a.display_name;
  if (a.email) return a.email;
  if (a.pub_key) return `${a.pub_key.slice(0, 8)}…${a.pub_key.slice(-6)}`;
  return '';
}

export function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return '';
  const words = trimmed.split(/\s+/).filter(Boolean);
  if (words.length >= 2) {
    return `${words[0][0] ?? ''}${words[1][0] ?? ''}`.toUpperCase();
  }
  const local = trimmed.split('@')[0] ?? '';
  return local.slice(0, 2).toUpperCase();
}

export function memberSince(iso: string, lang: Lang): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  if (lang === 'uk') {
    const month = String(date.getUTCMonth() + 1).padStart(2, '0');
    const year = date.getUTCFullYear();
    return `${month}.${year}`;
  }
  return date.toLocaleDateString('en-GB', {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

export function androidBuild(downloads: Build[]): Build | null {
  return downloads.find((b) => b.platform === 'android') ?? null;
}

export function canDownload(me: Me, staff: boolean): boolean {
  if (me.download_policy === 'open' || me.download_policy === 'account') return true;
  if (staff) return true;
  return me.licenses.some((lic) => !lic.revoked);
}

export function licenceSectionVisible(
  me: { licenses?: readonly unknown[] | null } | null | undefined,
): boolean {
  return Boolean(me && Array.isArray(me.licenses) && me.licenses.length > 0);
}

export function shaGroups(hex: string): string[] {
  const clean = hex.trim();
  const out: string[] = [];
  for (let i = 0; i < clean.length; i += 8) {
    out.push(clean.slice(i, i + 8));
  }
  return out;
}

export function sizeLabel(bytes: number | null | undefined, lang: Lang): string | null {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes <= 0) return null;
  const mb = (bytes / (1024 * 1024)).toFixed(1);
  if (lang === 'uk') {
    return `${mb.replace('.', ',')} МБ`;
  }
  return `${mb} MB`;
}

export function previewVenue(vs: Venue[]): Venue | null {
  const published = vs.find((v) => v.published && v.page !== null);
  if (published) return published;
  return vs.find((v) => v.page !== null) ?? null;
}
