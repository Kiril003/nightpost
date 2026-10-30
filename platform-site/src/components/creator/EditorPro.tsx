import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Check, ChevronDown, ChevronRight, ChevronUp, Copy, Eye, EyeOff, FileText, Globe, Home, Map as MapIcon, MessageCircle, PanelTop, Pencil, Plus, Smartphone, X, Zap } from 'lucide-react';
import { REGISTRY } from '../../lib/blocks/registry';
import { LIVE_SURFACES, canAdd, type Action, type EditPage } from '../../lib/creator/editor';
import { PREVIEWS, PRO_CIRCLES, PRO_TABS, acceptSets, suggested, surfaceOn, type Preview, layerTree, readiness, styleParts, weekOf, type EditorView, type Hours, type PageRow, type VersionChip } from '../../lib/creator/pro';
import { weekday } from '../../lib/creator/format';
import { refusalLabel } from '../../i18n/blocks';
import { TONE_VAR } from '../../lib/creator/inspector';
import type { Look } from '../../lib/creator/looks';
import { inspectorCopy } from '../../i18n/editorInspector';
import type { InspectorTab } from './EditorInspector';
import type { Check as CheckRow } from '../../lib/creator/editor';
import type { EditorCopy } from '../../i18n/editor';
import { proCopy } from '../../i18n/editorPro';
import type { Lang } from '../../i18n/types';
import { KIND_ICON, LookPicker, WhoSees, blockTitle, fieldDom, kindLabel } from './EditorPanels';
import { FieldForm, SizeSwatches, ToneSwatches, fieldLabel } from './EditorFields';
import '../../styles/editor-pro.css';

export function ViewSwitch({ view, lang, onPick }: { view: EditorView; lang: Lang; onPick: (v: EditorView) => void }) {
  const p = proCopy[lang];
  return (
    <div className="ed-seg ep-view" role="radiogroup" aria-label={p.view}>
      {(['canvas', 'pro'] as const).map((v) => (
        <button key={v} type="button" role="radio" aria-checked={view === v} className={view === v ? 'on' : ''} onClick={() => view !== v && onPick(v)}>
          {p[v]}
        </button>
      ))}
    </div>
  );
}

export type ProLeftTab = 'layers' | 'add';

export function ProLeft({ page, sel, lang, t, onSelect, act, pages, library, tab, setTab, onPick }: {
  page: EditPage; sel: string | null; lang: Lang; t: EditorCopy; onSelect: (id: string, field?: string) => void; act: (a: Action) => void;
  pages: PageRow[] | null; library: ReactNode; tab: ProLeftTab; setTab: (t: ProLeftTab) => void; onPick: (kind: string) => void;
}) {
  const p = proCopy[lang];
  return (
    <aside className="ed-left ep-left" aria-label={p.layers}>
      <div className="ed-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'layers'} className={tab === 'layers' ? 'on' : ''} onClick={() => setTab('layers')}>{p.layers}</button>
        <button type="button" role="tab" aria-selected={tab === 'add'} className={tab === 'add' ? 'on' : ''} onClick={() => setTab('add')}>{p.add}</button>
      </div>
      <div key={tab} className="ed-scroll ep-scroll">
        {tab === 'layers' ? <ProLayers page={page} sel={sel} lang={lang} t={t} onSelect={onSelect} act={act} /> : (
          <>
            <Suggested page={page} lang={lang} onPick={onPick} />
            {library}
          </>
        )}
      </div>
      <Pages rows={pages} lang={lang} t={t} />
    </aside>
  );
}

export function ProLayers({ page, sel, lang, t, onSelect, act }: {
  page: EditPage; sel: string | null; lang: Lang; t: EditorCopy; onSelect: (id: string, field?: string) => void; act: (a: Action) => void;
}) {
  const p = proCopy[lang];
  const [shut, setShut] = useState<Set<string>>(new Set());
  const name = String(page.blocks.find((b) => b.kind === 'id.name')?.data.name ?? '').trim();
  const flip = (id: string) => setShut((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    return n;
  });
  return (
    <div className="ep-tree" role="tree" aria-label={p.layers}>
      <div className="ep-root"><FileText size={15} aria-hidden="true" /><span className="ed-clip">{name || t.page}</span></div>
      {layerTree(page, REGISTRY).map(({ node, kids }) => {
        const b = page.blocks.find((x) => x.id === node.block)!;
        const Icon = KIND_ICON[node.kind] ?? PanelTop;
        const open = kids.length > 0 && !shut.has(node.block);
        const label = kindLabel(node.kind, lang);
        const sub = blockTitle(b) !== label ? blockTitle(b) : '';
        return (
          <div key={node.block} role="treeitem" aria-expanded={kids.length ? open : undefined} aria-selected={sel === node.block}>
            <div className={`ep-row ${sel === node.block ? 'on' : ''} ${node.hidden ? 'off' : ''}`}>
              {kids.length ? (
                <button type="button" className="ep-caret" aria-label={`${open ? p.collapse : p.expand}: ${label}`} onClick={() => flip(node.block)}>
                  {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
                </button>
              ) : <span className="ep-caret" aria-hidden="true" />}
              <button type="button" className="ep-main" aria-current={sel === node.block} onClick={() => onSelect(node.block)}>
                <Icon size={15} aria-hidden="true" />
                <span className="ed-clip">{label}{sub ? <span className="ep-sub"> · {sub}</span> : null}</span>
              </button>
              <button type="button" className={`ep-eye ${node.hidden ? 'shown' : ''}`} aria-label={`${node.hidden ? t.show : t.hide}: ${label}`} aria-pressed={node.hidden} onClick={() => act({ t: 'hide', id: node.block, hidden: !node.hidden })}>
                {node.hidden ? <EyeOff size={15} aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
              </button>
            </div>
            {open && (
              <div role="group">
                {kids.map((k) => (
                  <div key={k.field} role="treeitem" className="ep-row kid">
                    <span className="ep-caret" aria-hidden="true" />
                    <button type="button" className="ep-main" onClick={() => onSelect(node.block, k.t === 'list' ? k.field : undefined)}>
                      {k.t === 'live' ? <Zap size={14} aria-hidden="true" /> : <span className="ep-dot" aria-hidden="true" />}
                      <span className="ed-clip">{fieldLabel(node.kind, k.field, lang)}{k.t === 'list' ? ` · ${k.count}` : ''}</span>
                    </button>
                    {k.t === 'live' && <span className="ep-live">{p.live}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function Pages({ rows, lang, t }: { rows: PageRow[] | null; lang: Lang; t: EditorCopy }) {
  const p = proCopy[lang];
  const state = (r: PageRow) => (r.state === 'live' ? `/p/${r.id}` : r.state === 'draft' ? t.draft : t.notPublic);
  return (
    <section className="ep-pages" aria-label={p.pages}>
      <div className="ep-pages-head">
        <span className="ed-label">{p.pages}</span>
        <Link to="/build/start" className="ed-btn icon s" aria-label={p.newPage} title={p.newPage}><Plus size={15} aria-hidden="true" /></Link>
      </div>
      {rows === null ? (
        <p className="ep-page"><Home size={15} aria-hidden="true" /><span className="ed-grow ed-clip">{p.thisPage}</span><span className="ep-state">{t.onlyHere}</span></p>
      ) : (
        <ul className="ep-page-list">
          {rows.map((r) => (
            <li key={r.id}>
              {r.current ? (
                <span className="ep-page on" aria-current="page"><Home size={15} aria-hidden="true" /><span className="ed-grow ed-clip">{r.name}</span><span className={`ep-state ${r.state}`}>{state(r)}</span></span>
              ) : (
                <Link className="ep-page" to={`/build/edit/${encodeURIComponent(r.id)}`}><FileText size={15} aria-hidden="true" /><span className="ed-grow ed-clip">{r.name}</span><span className={`ep-state ${r.state}`}>{state(r)}</span></Link>
              )}
            </li>
          ))}
          {rows.length === 1 && <li className="ed-hint ep-none">{p.noPages}</li>}
        </ul>
      )}
    </section>
  );
}

export function ProStatus({ cs, saved, bad, lang, center, onOpen }: { cs: CheckRow[]; saved: string; bad: boolean; lang: Lang; center: boolean; onOpen: () => void }) {
  const p = proCopy[lang];
  const r = readiness(cs);
  return (
    <div className={`ep-status ${center ? 'center' : ''}`} role="status">
      <button type="button" className={`ep-ready ${r.ok ? 'ok' : 'warn'}`} onClick={onOpen}>
        {r.ok ? <Check size={15} aria-hidden="true" /> : <AlertTriangle size={15} aria-hidden="true" />}
        {r.ok ? p.ready : p.toFix(r.left)}
      </button>
      <span className="ep-bar" aria-hidden="true" />
      <span className={`ep-saved ${bad ? 'ed-bad' : ''}`}>{saved}</span>
    </div>
  );
}

export function ProVersion({ chip, lang, t, open, onOpen }: { chip: VersionChip; lang: Lang; t: EditorCopy; open: boolean; onOpen: () => void }) {
  if (!chip) return null;
  const p = proCopy[lang];
  return (
    <button type="button" className="ep-chip" aria-expanded={open} onClick={onOpen}>
      <span className={`ep-chip-dot ${chip.live ? 'live' : ''}`} aria-hidden="true" />
      {chip.live ? p.liveV(chip.v) : p.notLive}
      {chip.live && <span className="ep-chip-n">{chip.changes ? t.changesN(chip.changes) : t.noChanges}</span>}
    </button>
  );
}

export function ProInspector({ block, page, look, lang, t, tab, setTab, act, venue, add, error, touched, touch, onClose, go }: {
  block: EditPage['blocks'][number]; page: EditPage; look: Look; lang: Lang; t: EditorCopy; tab: InspectorTab; setTab: (t: InspectorTab) => void; act: (a: Action) => void; venue: string;
  add: (kind: string, stay?: boolean) => string | null; error?: { field: string; refused: string }; touched: Set<string>; touch: (k: string) => void; onClose: () => void;
  go: (id: string) => void;
}) {
  const p = proCopy[lang];
  const Icon = KIND_ICON[block.kind] ?? Pencil;
  const tone = block.tone && TONE_VAR[block.tone];
  const names: Record<InspectorTab, string> = { content: p.content, look: p.style, who: p.visibility };
  const parts = styleParts(block.kind, look);
  return (
    <div className="ed-insp ep-insp">
      <header className="ed-insp-head">
        <span className="ed-kind" aria-hidden="true" style={tone ? { background: `var(${tone.bg})`, color: `var(${tone.ink})` } : undefined}><Icon size={18} /></span>
        <div className="ed-grow">
          <h2 className="ed-h">{kindLabel(block.kind, lang)}</h2>
          {blockTitle(block) && <p className="ed-hint ed-clip">{blockTitle(block)}</p>}
        </div>
        <button type="button" className="ed-btn icon" aria-label={t.close} onClick={onClose}><X size={18} aria-hidden="true" /></button>
      </header>
      <div className="ed-tabs" role="tablist">
        {PRO_TABS.map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{names[k]}</button>
        ))}
      </div>
      <div className="ed-insp-body">
        {tab === 'content' && (
          <div className="ei-form">
            {block.kind === 'book.slots' && <ProBooking block={block} page={page} lang={lang} t={t} act={act} add={add} go={go} error={error} />}
            {block.kind !== 'book.slots' && <FieldForm ctx={{ page, block, lang, t, venue, act, add, error }} touched={touched} touch={touch} />}
          </div>
        )}
        {tab === 'look' && (
          <div className="ed-form">
            {parts.includes('size') && (
              <div className="ed-field">
                <span className="ed-label">{t.size}</span>
                <SizeSwatches block={block} look={look} lang={lang} t={t} act={act} />
              </div>
            )}
            {parts.includes('tone') && (
              <div className="ed-field">
                <span className="ed-label">{inspectorCopy[lang].cardTone}</span>
                <ToneSwatches block={block} lang={lang} act={act} />
              </div>
            )}
            <div className="ed-field">
              <span className="ed-label">{t.pageLook}</span>
              <LookPicker look={look} lang={lang} t={t} onPick={(l) => act({ t: 'look', look: l })} />
            </div>
          </div>
        )}
        {tab === 'who' && <WhoSees block={block} lang={lang} t={t} act={act} circles={PRO_CIRCLES} />}
      </div>
    </div>
  );
}

const NO_HOURS: Hours = { open: '10:00', close: '18:00' };
const dayName = (lang: Lang, i: number) => weekday(lang, `2026-01-${String(5 + i).padStart(2, '0')}`);

function Num({ block, field, value, min, max, step, unit, label, act, wide = false }: {
  block: EditPage['blocks'][number]; field: string; value: unknown; min: number; max: number; step: number; unit: string; label: string; act: (a: Action) => void; wide?: boolean;
}) {
  return (
    <label className={`ep-num ${wide ? 'wide' : ''}`}>
      <input
        id={fieldDom(block.id, field)} className="ed-input" type="number" inputMode="numeric" min={min} max={max} step={step} aria-label={label}
        value={typeof value === 'number' ? value : ''}
        onChange={(e) => act({ t: 'field', id: block.id, path: [field], value: e.target.value === '' ? undefined : Number(e.target.value) })}
      />
      <span className="ep-unit" aria-hidden="true">{unit}</span>
    </label>
  );
}

export function ProBooking({ block, page, lang, t, act, add, go, error }: {
  block: EditPage['blocks'][number]; page: EditPage; lang: Lang; t: EditorCopy; act: (a: Action) => void;
  add: (kind: string, stay?: boolean) => string | null; go: (id: string) => void; error?: { field: string; refused: string };
}) {
  const p = proCopy[lang];
  const d = block.data;
  const lists = page.blocks.filter((b) => b.kind === 'cat.services');
  const svc = lists.find((b) => b.id === d.services) ?? null;
  const items: { id: string; name: string }[] = svc?.data.items ?? [];
  const week = weekOf(page);
  const hrs = page.blocks.find((b) => b.kind === 'hrs.week');
  const open = week.days.filter((x) => x.hours).map((x) => x.day);
  const hours = week.hours ?? NO_HOURS;
  const setWeek = (days: string[], h: Hours = hours) => act({ t: 'week', days, hours: h, after: block.id });
  const err = (f: string) => (error?.field === f ? <p className="ed-err" role="alert">{refusalLabel(error.refused, lang)}</p> : null);
  return (
    <div className="ep-book">
      <section className="ep-sec">
        <div className="ep-sec-head">
          <span className="ed-label">{p.services}</span>
          {svc && <button type="button" className="ed-link" onClick={() => go(svc.id)}>{p.editServices}</button>}
        </div>
        {lists.length > 1 && (
          <select id={fieldDom(block.id, 'services')} className="ed-input" value={svc?.id ?? ''} onChange={(e) => act({ t: 'field', id: block.id, path: ['services'], value: e.target.value })}>
            {!svc && <option value="">{t.none}</option>}
            {lists.map((b) => <option key={b.id} value={b.id}>{[kindLabel(b.kind, lang), blockTitle(b)].filter(Boolean).join(' · ')}</option>)}
          </select>
        )}
        {svc ? (
          <ul className="ep-chips" id={lists.length > 1 ? undefined : fieldDom(block.id, 'services')} tabIndex={-1}>
            {items.map((it) => <li key={it.id} className="ep-tag">{it.name || t.none}</li>)}
          </ul>
        ) : (
          <button type="button" id={fieldDom(block.id, 'services')} className="ed-btn s" onClick={() => {
            const made = lists[0]?.id ?? add('cat.services', true);
            if (made) act({ t: 'field', id: block.id, path: ['services'], value: made });
          }}>
            <Plus size={14} aria-hidden="true" />{t.addKind(kindLabel('cat.services', lang))}
          </button>
        )}
        {err('services')}
      </section>
      <section className="ep-sec">
        <span className="ed-label">{p.slots}</span>
        <div className="ep-grid">
          <span className="ep-k">{p.lengthGap}</span>
          <Num block={block} field="slot" value={d.slot} min={5} max={1440} step={5} unit={p.min} label={fieldLabel(block.kind, 'slot', lang)} act={act} />
          <Num block={block} field="buffer" value={d.buffer} min={5} max={1440} step={5} unit={p.min} label={fieldLabel(block.kind, 'buffer', lang)} act={act} />
          <span className="ep-k" id={`${block.id}-lead`}>{p.notSooner}</span>
          <Num block={block} field="lead" value={d.lead} min={0} max={168} step={1} unit={p.hours} label={p.notSooner} act={act} wide />
          <span className="ep-k" id={`${block.id}-horizon`}>{p.notLater}</span>
          <Num block={block} field="horizon" value={d.horizon} min={1} max={90} step={1} unit={p.days} label={p.notLater} act={act} wide />
          <span className="ep-k" id={`${block.id}-accept`}>{p.accept}</span>
          <div className="ed-seg ep-accept" role="radiogroup" aria-labelledby={`${block.id}-accept`}>
            {([true, false] as const).map((v) => (
              <button key={String(v)} type="button" role="radio" aria-checked={!!d.auto === v} className={!!d.auto === v ? 'on' : ''} onClick={() => !!d.auto !== v && act({ t: 'fields', sets: acceptSets(block, v) })}>
                {v ? p.auto : p.askMe}
              </button>
            ))}
          </div>
        </div>
        {error?.field && ['slot', 'buffer', 'lead', 'horizon'].includes(error.field) && err(error.field)}
      </section>
      <section className="ep-sec">
        <div className="ep-sec-head">
          <span className="ed-label" id={`${block.id}-week`}>{p.openHours}</span>
          {hrs && !week.simple && <button type="button" className="ed-link" onClick={() => go(hrs.id)}>{p.editHours}</button>}
        </div>
        <div className="ep-week" role="group" aria-labelledby={`${block.id}-week`}>
          {week.days.map((x, i) => (
            <button
              key={x.day} type="button" aria-pressed={!!x.hours} disabled={!week.simple} className={`ep-day ${x.hours ? 'on' : ''}`}
              aria-label={`${dayName(lang, i)}: ${x.hours ? `${x.hours.open}–${x.hours.close}` : p.closed}`}
              onClick={() => setWeek(x.hours ? open.filter((o) => o !== x.day) : [...open, x.day])}
            >
              {dayName(lang, i)}
            </button>
          ))}
        </div>
        {week.simple && (
          <div className="ep-hours">
            <label className="ep-k" htmlFor={`${block.id}-opens`}>{p.opens}</label>
            <input id={`${block.id}-opens`} className="ed-input" type="time" value={hours.open} disabled={!open.length} onChange={(e) => e.target.value && setWeek(open, { ...hours, open: e.target.value })} />
            <label className="ep-k" htmlFor={`${block.id}-closes`}>{p.closes}</label>
            <input id={`${block.id}-closes`} className="ed-input" type="time" value={hours.close} disabled={!open.length} onChange={(e) => e.target.value && setWeek(open, { ...hours, close: e.target.value })} />
          </div>
        )}
      </section>
    </div>
  );
}

export function Suggested({ page, lang, onPick }: { page: EditPage; lang: Lang; onPick: (kind: string) => void }) {
  const kinds = suggested(page);
  if (!kinds.length) return null;
  const p = proCopy[lang];
  return (
    <section className="ep-suggest" aria-label={p.suggested}>
      <span className="ed-label">{p.suggested}</span>
      <ul className="ep-suggest-list">
        {kinds.map((k) => {
          const Icon = KIND_ICON[k] ?? PanelTop;
          return (
            <li key={k}>
              <button type="button" className="ep-suggest-item" onClick={() => onPick(k)}>
                <Icon size={16} aria-hidden="true" /><span className="ed-grow">{kindLabel(k, lang)}</span><Plus size={15} aria-hidden="true" />
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

const PREVIEW_ICON = { W: Globe, A: Smartphone, M: MapIcon, C: MessageCircle } as const;

export function PreviewSwitch({ value, page, lang, onPick }: { value: Preview; page: EditPage; lang: Lang; onPick: (s: Preview) => void }) {
  const p = proCopy[lang];
  return (
    <div className="ed-seg ep-preview" role="radiogroup" aria-label={p.previewOn}>
      {PREVIEWS.map((s) => {
        const Icon = PREVIEW_ICON[s];
        const live = LIVE_SURFACES.includes(s);
        return (
          <button key={s} type="button" role="radio" aria-checked={value === s} className={value === s ? 'on' : ''} disabled={!live} title={live ? undefined : p.notLive} onClick={() => onPick(s)}>
            <Icon size={14} aria-hidden="true" />{p.surface[s]}
          </button>
        );
      })}
      {value !== 'W' && !surfaceOn(page, value) && <span className="ep-off">{p.notOn(p.surface[value])}</span>}
    </div>
  );
}

export function ProTools({ block, page, lang, t, act, onNudge, onAdd }: {
  block: EditPage['blocks'][number]; page: EditPage; lang: Lang; t: EditorCopy; act: (a: Action) => void; onNudge: (id: string, by: -1 | 1) => void; onAdd: () => void;
}) {
  const p = proCopy[lang];
  const i = page.blocks.indexOf(block);
  return (
    <>
      <button type="button" className="ed-btn icon s" aria-label={t.moveUp} title={t.moveUp} disabled={i <= 0} onClick={() => onNudge(block.id, -1)}><ChevronUp size={16} aria-hidden="true" /></button>
      <button type="button" className="ed-btn icon s" aria-label={t.moveDown} title={t.moveDown} disabled={i >= page.blocks.length - 1} onClick={() => onNudge(block.id, 1)}><ChevronDown size={16} aria-hidden="true" /></button>
      <button type="button" className="ed-btn icon s" aria-label={p.duplicate} title={p.duplicate} disabled={!canAdd(page, block.kind, REGISTRY)} onClick={() => act({ t: 'duplicate', id: block.id })}><Copy size={16} aria-hidden="true" /></button>
      <button type="button" className="ed-btn icon s" aria-label={p.addBelow} title={p.addBelow} onClick={onAdd}><Plus size={16} aria-hidden="true" /></button>
    </>
  );
}
