import type { Moment } from './moments';
import { parseTag, withTag } from './workEvents';

export type MomentKind = 'photo' | 'video' | 'text';

export interface MomentQuoteRef {
  id: string;
  caption: string;
  kind: MomentKind;
  said: string;
}

const kindOf = (m: Pick<Moment, 'media'>): MomentKind =>
  m.media.length === 0 ? 'text' : m.media[0].mime.startsWith('video/') ? 'video' : 'photo';

/** Відповідь на момент: слова людини + позначка `[np:moment …]`, яку розмова малює цитатою. */
export function momentReplyBody(said: string, m: Pick<Moment, 'id' | 'text' | 'media'>): string {
  return withTag(said, 'moment', { id: m.id, c: m.text.replace(/\s+/g, ' ').trim().slice(0, 60), k: kindOf(m) });
}

export function momentQuoteOf(text: string | undefined): MomentQuoteRef | null {
  const tag = parseTag(text);
  if (!tag || tag.kind !== 'moment' || !tag.f.id) return null;
  const k = tag.f.k === 'photo' || tag.f.k === 'video' ? tag.f.k : 'text';
  return { id: tag.f.id, caption: tag.f.c ?? '', kind: k, said: tag.line };
}
