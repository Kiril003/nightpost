import type { Look } from './looks.ts';

export const WHO = ['me', 'group', 'place'] as const;
export type Who = (typeof WHO)[number];

export const WHO_SAMPLE: Record<Who, string> = { me: 'alma-brandt', group: 'marigold-hours', place: 'ember-coffee' };
export const WHO_SAMPLE_UK: Partial<Record<Who, string>> = { me: 'olha-melnyk' };

export function sampleFor(who: Who, lang?: string): string {
  return (lang === 'uk' && WHO_SAMPLE_UK[who]) || WHO_SAMPLE[who];
}

export const WHO_IMAGE: Record<Who, string> = { me: 'sample-mug-01', group: 'sample-vinyl-01', place: 'sample-coffee-01' };
export const WHO_LOOK: Record<Who, Look> = { me: 'shop', group: 'studio', place: 'journal' };

export type Start = { step: 1 | 2; who: Who; look: Look };

export function startState(): Start {
  return { step: 1, who: 'me', look: WHO_LOOK.me };
}

export function pickWho(s: Start, who: Who): Start {
  return { ...s, who, look: WHO_LOOK[who] };
}

export function next(s: Start): Start {
  return s.step === 1 ? { ...s, step: 2 } : s;
}

export function back(s: Start): Start {
  return s.step === 2 ? { ...s, step: 1 } : s;
}

export function previewHref(s: Start, theme?: string, lang?: string): string {
  const q = new URLSearchParams({ look: s.look });
  if (theme) q.set('theme', theme);
  return `/p/${sampleFor(s.who, lang)}?${q.toString()}`;
}

export function editorHref(s: Start, theme?: string): string {
  const q = new URLSearchParams({ new: '1', who: s.who, look: s.look });
  if (theme) q.set('theme', theme);
  return `/build?${q.toString()}`;
}
