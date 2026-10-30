import { memo, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Search, X } from 'lucide-react';
import CreatorPage from './CreatorPage';
import { kindLabel } from './EditorPanels';
import { canAdd, type EditPage } from '../../lib/creator/editor';
import { FAMS, LIB_KINDS, famOf, librarySample, onPage, shown, type Chip } from '../../lib/creator/library';
import { isLook, type Look, type Theme } from '../../lib/creator/looks';
import { REGISTRY } from '../../lib/blocks/registry';
import { libraryCopy } from '../../i18n/editorLibrary';
import type { EditorCopy } from '../../i18n/editor';
import type { Lang } from '../../i18n/types';
import '../../styles/editor-library.css';

const WIDTH = 240;
const WIDE: Record<string, number> = { 'id.hero': 720 };
const TILED = new Set(['deal.drop', 'com.reviews', 'loc.message', 'pay.qr', 'pay.split_cost', 'org.join', 'loc.meet_point', 'loc.address']);

const CHIPS: Chip[] = ['you', ...FAMS.filter((f) => LIB_KINDS.some((k) => famOf(k) === f))];

const Preview = memo(function Preview({ kind, look, theme, lang }: { kind: string; look: Look; theme: Theme; lang: Lang }) {
  const box = useRef<HTMLDivElement>(null);
  const width = WIDE[kind] ?? WIDTH;
  const [scale, setScale] = useState(0);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    el.setAttribute('inert', '');
    const fit = () => setScale(el.clientWidth / width);
    fit();
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(fit);
    ro?.observe(el);
    if (typeof IntersectionObserver === 'undefined') {
      setSeen(true);
      return () => ro?.disconnect();
    }
    const io = new IntersectionObserver((es) => {
      if (es.some((e) => e.isIntersecting)) {
        setSeen(true);
        io.disconnect();
      }
    }, { rootMargin: '240px 0px' });
    io.observe(el);
    return () => {
      ro?.disconnect();
      io.disconnect();
    };
  }, [width]);
  const sample = useMemo(() => {
    const s = librarySample(kind, lang, look, new Date());
    if (!TILED.has(kind)) return s;
    return { ...s, page: { ...s.page, blocks: s.page.blocks.map((b) => (b.kind === kind ? { ...b, size: '2x1' } : b)) } };
  }, [kind, lang, look]);
  return (
    <div ref={box} className="elib-pv" aria-hidden="true">
      {seen && scale > 0 && (
        <div className="elib-pv-in" style={{ width, transform: `scale(${scale})` }}>
          <CreatorPage page={sample.page} live={sample.live} look={look} theme={theme} lang={lang} sample frame="preview" />
        </div>
      )}
    </div>
  );
});

function Card({ kind, page, look, theme, lang, t, onAdd }: { kind: string; page: EditPage; look: Look; theme: Theme; lang: Lang; t: EditorCopy; onAdd: (kind: string) => void }) {
  const L = libraryCopy[lang];
  const meta = useId();
  const ok = canAdd(page, kind, REGISTRY);
  const n = onPage(page, kind);
  const name = kindLabel(kind, lang);
  const said = [n > 0 ? L.onPage(n) : '', ok ? '' : t.atLimit].filter(Boolean).join(' · ');
  return (
    <li className="elib-item">
      <div className="elib-card" aria-disabled={!ok}>
        <Preview kind={kind} look={look} theme={theme} lang={lang} />
        {!ok && <span className="elib-badge" aria-hidden="true">{t.atLimit}</span>}
        <span className="elib-name" aria-hidden="true">
          <span className="elib-clip">{name}</span>
          {n > 0 && <span className="elib-n">{n}</span>}
        </span>
      </div>
      <button
        type="button"
        className="elib-hit"
        aria-label={name}
        aria-describedby={said ? meta : undefined}
        aria-disabled={!ok}
        onClick={() => ok && onAdd(kind)}
      />
      {said && <span id={meta} className="ed-sr">{said}</span>}
    </li>
  );
}

export function Library({ page, lang, t, onAdd, look, theme }: {
  page: EditPage; lang: Lang; t: EditorCopy; onAdd: (kind: string) => void; look?: Look; theme?: Theme;
}) {
  const L = libraryCopy[lang];
  const pageLook: Look = look ?? (isLook(page.look) ? page.look : 'shop');
  const [chip, setChip] = useState<Chip>('you');
  const [q, setQ] = useState('');
  const root = useRef<HTMLDivElement>(null);
  const [seenTheme, setSeenTheme] = useState<Theme>('day');
  useLayoutEffect(() => {
    if (theme) return;
    const v = root.current?.parentElement?.closest('[data-theme]')?.getAttribute('data-theme');
    if (v === 'day' || v === 'night') setSeenTheme(v);
  });
  const kinds = shown({ page, look: pageLook, chip, q, label: (k) => kindLabel(k, lang) });
  const query = q.trim();
  return (
    <div className="elib" ref={root}>
      <div className="elib-head">
        <div className="elib-search">
          <Search size={16} aria-hidden="true" />
          <input
            type="search"
            className="elib-input"
            value={q}
            placeholder={L.search(LIB_KINDS.length)}
            aria-label={L.search(LIB_KINDS.length)}
            autoComplete="off"
            spellCheck={false}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key !== 'Escape' || !q) return;
              e.stopPropagation();
              setQ('');
            }}
          />
          {q && (
            <button type="button" className="elib-clear" aria-label={L.clear} onClick={() => setQ('')}>
              <X size={16} aria-hidden="true" />
            </button>
          )}
        </div>
        <div className="elib-chips" role="group" aria-label={L.families}>
          {CHIPS.map((c) => (
            <button key={c} type="button" className="elib-chip" aria-pressed={chip === c} onClick={() => setChip(c)}>
              <span>{L.chips[c]}</span>
            </button>
          ))}
        </div>
      </div>
      {kinds.length === 0 ? (
        <p className="elib-empty" role="status">{query ? L.nothingFor(query) : L.pageFull}</p>
      ) : (
        <ul className="ed-lib elib-grid">
          {kinds.map((kind) => (
            <Card key={kind} kind={kind} page={page} look={pageLook} theme={theme ?? seenTheme} lang={lang} t={t} onAdd={onAdd} />
          ))}
        </ul>
      )}
    </div>
  );
}
