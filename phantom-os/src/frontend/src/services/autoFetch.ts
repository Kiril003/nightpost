import { currentNet } from './callPrefs';

export type FetchKind = 'photo' | 'voice' | 'file' | 'video';

export interface FetchInput {
  kind: FetchKind;
  sizeBytes: number;
  rings: readonly string[];
  net: 'wifi' | 'mobile';
  saveData: boolean;
}

const isTrustedRing = (rings: readonly string[]): boolean =>
  rings.includes('personal') || rings.includes('close');

export function shouldFetch(i: FetchInput): boolean {
  // Петля банера — прикраса, а не лист: лише Wi‑Fi без економії, інакше перший кадр.
  if (i.kind === 'video') {
    return i.net === 'wifi' && !i.saveData;
  }
  if (i.saveData) {
    return i.kind === 'voice' && isTrustedRing(i.rings);
  }
  if (i.net === 'wifi') {
    return true;
  }
  if (i.kind === 'file') {
    return false;
  }
  return isTrustedRing(i.rings);
}

export function readNet(): { net: 'wifi' | 'mobile'; saveData: boolean } {
  const conn =
    typeof navigator !== 'undefined'
      ? (navigator as Navigator & { connection?: { type?: unknown; saveData?: unknown } | null }).connection
      : null;
  return {
    net: currentNet(conn),
    saveData: Boolean(conn?.saveData),
  };
}
