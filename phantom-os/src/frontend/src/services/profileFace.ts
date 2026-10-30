import type { Profile } from '../stores/profilesStore';

export type FaceField = 'name' | 'status' | 'bio';
export interface Face { name: string; status: string; bio: string }

/** Основне обличчя, яке бачать усі без свого рядка. */
export function resolveFace(base: Face, profile: Pick<Profile, 'faces'>, ring: string): Face {
  const over = profile.faces[ring] ?? {};
  const pick = (f: FaceField) => (over[f] && over[f]!.trim() !== '' ? over[f]! : base[f]);
  return { name: pick('name'), status: pick('status'), bio: pick('bio') };
}

/** Порожнє значення знімає рядок: сфера знову бере основне. */
export function withFaceField(
  faces: Profile['faces'],
  ring: string,
  field: FaceField,
  value: string,
): Profile['faces'] {
  const next = { ...faces };
  const row = { ...(next[ring] ?? {}) };
  const v = value.trim();
  if (v === '') delete row[field];
  else row[field] = v;
  if (Object.keys(row).length === 0) delete next[ring];
  else next[ring] = row;
  return next;
}

export const faceChangeCount = (faces: Profile['faces'], ring: string): number =>
  Object.keys(faces[ring] ?? {}).length;
