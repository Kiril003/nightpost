import type { ReactNode } from 'react';
import type { Json } from '../../lib/creator/arrange';
import { WEEK } from '../../lib/creator/editor';
import { REGISTRY, type FieldSpec } from '../../lib/blocks/registry';
import { blockLabel } from '../../i18n/blocks';
import type { Lang } from '../../i18n/types';
import '../../styles/editor-pro.css';

const COPY = {
  en: { url: 'Link (https://)', label: 'Shown as', from: 'From', to: 'To' },
  uk: { url: 'Посилання (https://)', label: 'Підпис', from: 'Від', to: 'До' },
};

const dayName = (d: string, lang: Lang) =>
  new Date(2026, 0, 5 + WEEK.indexOf(d as (typeof WEEK)[number])).toLocaleDateString(lang === 'uk' ? 'uk-UA' : 'en-GB', { weekday: 'short' });

function Toggles({ id, opts, value, set, name }: { id: string; opts: readonly string[]; value: unknown; set: (v: unknown) => void; name: (o: string) => string }) {
  const on = new Set(Array.isArray(value) ? (value as string[]) : []);
  const flip = (o: string) => {
    const next = opts.filter((x) => (x === o ? !on.has(x) : on.has(x)));
    set(next.length ? next : undefined);
  };
  return (
    <span id={id} className="ed-tags" role="group">
      {opts.map((o) => (
        <button key={o} type="button" className={`ed-tag ${on.has(o) ? 'on' : ''}`} aria-pressed={on.has(o)} onClick={() => flip(o)}>
          {name(o)}
        </button>
      ))}
    </span>
  );
}

/** Inputs for the primitives the first 20 kinds never needed: phone, email, link, tags, days, date range. */
export function moreInput(f: FieldSpec, value: unknown, set: (v: unknown) => void, id: string, lang: Lang): ReactNode {
  const c = COPY[lang];
  const str = typeof value === 'string' ? value : '';
  switch (f.p) {
    case 'phone':
      return <input id={id} className="ed-input" type="tel" inputMode="tel" autoComplete="off" placeholder="+" value={str} onChange={(e) => set(e.target.value.replace(/[\s()-]/g, '') || undefined)} />;
    case 'email':
      return <input id={id} className="ed-input" type="email" autoComplete="off" value={str} onChange={(e) => set(e.target.value.trim() || undefined)} />;
    case 'link': {
      const v = (value as Json) ?? {};
      const put = (k: 'url' | 'label', x: string) => {
        const next: Json = Object.fromEntries(Object.entries({ url: v.url, label: v.label, [k]: x.trim() || undefined }).filter(([, s]) => s !== undefined));
        set(next.url || next.label ? next : undefined);
      };
      return (
        <span className="ed-col">
          <input id={id} className="ed-input" type="url" inputMode="url" placeholder={c.url} aria-label={c.url} value={v.url ?? ''} onChange={(e) => put('url', e.target.value)} />
          <input className="ed-input" placeholder={c.label} aria-label={c.label} value={v.label ?? ''} onChange={(e) => put('label', e.target.value)} />
        </span>
      );
    }
    case 'tagset': {
      const vocab = f.vocab ?? '';
      return <Toggles id={id} opts={REGISTRY.vocab[vocab] ?? []} value={value} set={set} name={(o) => blockLabel(`vocab.${vocab}.${o}`, lang)} />;
    }
    case 'dayset':
      return <Toggles id={id} opts={WEEK} value={value} set={set} name={(d) => dayName(d, lang)} />;
    case 'daterange': {
      const v = (value as Json) ?? {};
      const put = (k: 'from' | 'to', x: string) => {
        const next = { from: v.from ?? '', to: v.to ?? '', [k]: x };
        set(next.from || next.to ? next : undefined);
      };
      return (
        <span className="ed-pair">
          <input id={id} className="ed-input" type="date" aria-label={c.from} value={v.from ?? ''} onChange={(e) => put('from', e.target.value)} />
          <input className="ed-input" type="date" aria-label={c.to} value={v.to ?? ''} onChange={(e) => put('to', e.target.value)} />
        </span>
      );
    }
    default:
      return null;
  }
}
