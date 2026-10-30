import { whereApi, type WherePrecision, type WhereState } from './peerWhere';

export type WhereChip = {
  id: 'exact15' | 'area60' | 'meet' | 'notNow';
  precision?: WherePrecision;
  minutes?: number;
};

const PHRASES: ReadonlyArray<ReadonlyArray<string>> = [
  ['де', 'ти'],
  ['ти', 'де'],
  ['де', 'ви'],
  ['ви', 'де'],
  ['де', 'зараз'],
  ['where', 'are', 'you'],
  ["where're", 'you'],
  ['where', 'r', 'u'],
  ['where', 'you', 'at'],
  ['kde', 'teď', 'jsi'],
  ['kde', 'ted', 'jsi'],
  ['kde', 'jsi'],
  ['kde', 'jste'],
];

const FILLER_ALLOW_LIST = new Set([
  'зараз',
  'now',
  'teď',
  'ted',
  'вже',
  'уже',
  'already',
  'then',
  'там',
]);

/** Чи це пряме питання «де ти зараз» без сторонніх дієслів і додатків. */
export function isWhereAsk(text: string): boolean {
  if (!text) return false;
  const normalized = text
    .toLowerCase()
    .replace(/ё/g, 'е')
    .replace(/[ʼ’‘`']/g, "'");

  const sentences = normalized.split(/[?!.;,\n]+/);
  for (const sentence of sentences) {
    const tokens = sentence.match(/[\p{L}\p{N}]+(?:'[\p{L}\p{N}]+)*/gu);
    if (!tokens || tokens.length === 0) continue;

    for (const phrase of PHRASES) {
      if (tokens.length < phrase.length) continue;
      const matchesPrefix = phrase.every((word, idx) => tokens[idx] === word);
      if (!matchesPrefix) continue;
      const rest = tokens.slice(phrase.length);
      if (rest.every((word) => FILLER_ALLOW_LIST.has(word))) {
        return true;
      }
    }
  }
  return false;
}

export function whereChips(state: WhereState | null): WhereChip[] {
  if (!state || !state.eligible || state.mine !== null) return [];
  const exact15: WhereChip = { id: 'exact15', precision: 'exact', minutes: 15 };
  const area60: WhereChip = { id: 'area60', precision: 'quarter', minutes: 60 };
  const meet: WhereChip = { id: 'meet', precision: 'exact' };
  const notNow: WhereChip = { id: 'notNow' };
  if (state.theirs?.precision === 'exact') {
    return [exact15, area60, meet, notNow];
  }
  return [area60, exact15, meet, notNow];
}

export async function chipAction(
  chip: WhereChip | WhereChip['id'],
  peerId: string,
  now: number = Date.now(),
): Promise<WhereState | null> {
  const id = typeof chip === 'string' ? chip : chip.id;
  if (id === 'exact15') return whereApi.show(peerId, 'exact', 15);
  if (id === 'area60') return whereApi.show(peerId, 'quarter', 60);
  if (id === 'meet') return whereApi.showMeet(peerId, now + 2 * 3600_000);
  return null;
}
