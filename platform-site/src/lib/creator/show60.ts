import { blockLabel } from '../../i18n/blocks.ts';

type Lang = 'en' | 'uk';
type Headed = { kind: string; data: { title?: unknown } };

/** The owner's title, or the kind's name in the page language when the title is empty. */
export function heading(b: Headed, lang: Lang): string {
  return typeof b.data.title === 'string' && b.data.title.trim() ? b.data.title : blockLabel(`block.${b.kind}`, lang);
}

const CC2 = new Set('20 27 30 31 32 33 34 36 39 40 41 43 44 45 46 47 48 49 51 52 53 54 55 56 57 58 60 61 62 63 64 65 66 81 82 84 86 90 91 92 93 94 95 98'.split(' '));

/** +12025550142 → +1 202 555 0142. Display only; links keep the raw number. */
export function phoneText(raw: string): string {
  const m = /^\+(\d{7,15})$/.exec(raw);
  if (!m) return raw;
  const d = m[1];
  const cc = d[0] === '1' || d[0] === '7' ? d.slice(0, 1) : CC2.has(d.slice(0, 2)) ? d.slice(0, 2) : d.slice(0, 3);
  const rest = d.slice(cc.length);
  const groups: string[] = [];
  let i = 0;
  while (rest.length - i > 4 || (rest.length - i === 4 && rest.length % 3 !== 1)) {
    groups.push(rest.slice(i, i + 3));
    i += 3;
  }
  groups.push(rest.slice(i));
  return `+${cc} ${groups.filter(Boolean).join(' ')}`;
}
