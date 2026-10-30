import { useRef, useState, type KeyboardEvent, type PointerEvent as RPointerEvent, type ReactNode } from 'react';
import { Check, ChevronDown, ImagePlus, MapPin, Minus, Plus, RotateCcw, Trash2 } from 'lucide-react';
import type { Block, Json } from '../../lib/creator/arrange';
import type { Look } from '../../lib/creator/looks';
import { imageUrl } from '../../lib/creator/samples';
import { LIVE_SURFACES, SIZES, blockSurfaces, canAdd, pageSurfaces, rowId, sizable, type Action, type EditPage, type Size } from '../../lib/creator/editor';
import {
  TONE_VAR, alsoSurfaces, dropItems, dropStockSets, focusAt, focusOf, isTimeline, listCount, nameKey, nextCircle, nudgeFocus, objectPosition, rowMissing, stageOpen, stepValue, stepperKey,
} from '../../lib/creator/inspector';
import { REGISTRY, type FieldSpec } from '../../lib/blocks/registry';
import { blockLabel, refusalLabel } from '../../i18n/blocks';
import type { EditorCopy } from '../../i18n/editor';
import { inspectorCopy } from '../../i18n/editorInspector';
import type { Lang } from '../../i18n/types';
import { uploadVenueImage, VenueRefusal } from '../../lib/venuesApi';
import { blockTitle, fieldDom, kindLabel } from './EditorPanels';
import { moreInput } from './EditorFieldsMore';
import '../../styles/editor-inspector.css';

const HIDDEN_FIELDS = new Set(['access', 'id.name.kind', 'book.slots.who', 'book.slots.auto']);
const IMAGE_TYPES = ['image/webp', 'image/jpeg', 'image/png', 'image/avif'];

export function fieldLabel(kind: string, path: string, lang: Lang): string {
  const l = blockLabel(`field.${kind}.${path}`, lang);
  return l.startsWith('field.') ? path.split('.').pop()! : l;
}

function editable(kind: string, key: string, f: FieldSpec): boolean {
  return f.p !== 'derived' && !HIDDEN_FIELDS.has(key) && !HIDDEN_FIELDS.has(`${kind}.${key}`);
}

type Ctx = {
  page: EditPage;
  block: Block;
  lang: Lang;
  t: EditorCopy;
  venue: string;
  act: (a: Action) => void;
  add: (kind: string, stay?: boolean) => string | null;
  error?: { field: string; refused: string };
};

export function Row({ label, htmlFor, children, error }: { label: string; htmlFor?: string; children: ReactNode; error?: string }) {
  return (
    <div className="ed-field">
      <label className="ed-label" htmlFor={htmlFor}>{label}</label>
      {children}
      {error && <p className="ed-err" role="alert">{error}</p>}
    </div>
  );
}

function radioKeys(e: KeyboardEvent<HTMLElement>) {
  const dir = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
  if (!dir) return;
  const items = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>('[role=radio]:not(:disabled)'));
  const at = items.indexOf(document.activeElement as HTMLButtonElement);
  if (at < 0) return;
  e.preventDefault();
  const next = items[(at + dir + items.length) % items.length];
  next.focus();
  next.click();
}

function Stepper({ id, value, f, set, label, lang }: { id: string; value: unknown; f: FieldSpec; set: (v: number | undefined, typed?: boolean) => void; label: string; lang: Lang }) {
  const it = inspectorCopy[lang];
  const down = stepValue(value, -1, f);
  const up = stepValue(value, 1, f);
  const [typed, setTyped] = useState<string | null>(null);
  return (
    <span className="ei-step" role="group" aria-label={label}>
      <button type="button" aria-label={`${it.less}: ${label}`} disabled={down === value} onClick={() => set(down)}><Minus size={14} aria-hidden="true" /></button>
      <input
        id={id}
        className="ei-step-n"
        type="number"
        inputMode="numeric"
        min={f.min}
        max={f.max}
        step={f.step ?? 1}
        value={typed !== null && (typed === '' || Number(typed) === value) ? typed : typeof value === 'number' ? value : ''}
        onChange={(e) => {
          setTyped(e.target.value);
          set(e.target.value === '' ? undefined : Number(e.target.value), true);
        }}
        onBlur={() => setTyped(null)}
      />
      <button type="button" aria-label={`${it.more}: ${label}`} disabled={up === value} onClick={() => set(up)}><Plus size={14} aria-hidden="true" /></button>
    </span>
  );
}

function valueInput(f: FieldSpec, value: unknown, set: (v: unknown) => void, id: string, ctx: Ctx, path: (string | number)[], label: string) {
  const { t, lang, page, block } = ctx;
  switch (f.p) {
    case 'text':
      return <input id={id} className="ed-input" value={typeof value === 'string' ? value : ''} maxLength={f.max} onChange={(e) => set(e.target.value)} />;
    case 'richtext':
      return <textarea id={id} className="ed-input ed-area" value={typeof value === 'string' ? value : ''} maxLength={f.max} onChange={(e) => set(e.target.value)} />;
    case 'wallet':
      return <input id={id} className="ed-input ed-mono" value={typeof value === 'string' ? value : ''} spellCheck={false} autoComplete="off" onChange={(e) => set(e.target.value.trim())} />;
    case 'number':
      return <Stepper id={id} value={value} f={f} set={set} label={label} lang={lang} />;
    case 'percent':
    case 'duration':
      return (
        <span className="ed-unit">
          <input
            id={id}
            className="ed-input"
            type="number"
            inputMode="numeric"
            min={f.min}
            max={f.max}
            step={f.p === 'duration' ? 5 : f.step ?? 1}
            value={typeof value === 'number' ? value : ''}
            onChange={(e) => set(e.target.value === '' ? undefined : Number(e.target.value))}
          />
          {f.p === 'duration' && <span className="ed-suffix">{t.minutes}</span>}
        </span>
      );
    case 'money': {
      const token = (block.data.token as string) ?? 'USDC';
      const own = f.token === '$token';
      const amount = own ? (typeof value === 'string' ? value : '') : ((value as Json)?.amount ?? '');
      return (
        <span className="ed-unit">
          <input
            id={id}
            className="ed-input"
            inputMode="decimal"
            value={amount}
            onChange={(e) => {
              const v = e.target.value.replace(',', '.');
              set(own ? v : v === '' ? undefined : { amount: v, token: (value as Json)?.token ?? pageToken(page) });
            }}
          />
          <span className="ed-suffix">{own ? token : (value as Json)?.token ?? pageToken(page)}</span>
        </span>
      );
    }
    case 'token':
      return (
        <select id={id} className="ed-input" value={typeof value === 'string' ? value : ''} onChange={(e) => set(e.target.value)}>
          {(REGISTRY.vocab.token ?? []).map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
      );
    case 'toggle':
      return (
        <button id={id} type="button" role="switch" aria-checked={value === true} className={`ed-switch ${value === true ? 'on' : ''}`} onClick={() => set(!(value === true))}>
          <i aria-hidden="true" />
        </button>
      );
    case 'choice': {
      const opts = f.options ?? REGISTRY.vocab[f.vocab ?? ''] ?? [];
      const name = (o: string) => (f.vocab ? blockLabel(`vocab.${f.vocab}.${o}`, lang).replace(/^vocab\..*\./, '') : t.option[o] ?? o);
      return (
        <select id={id} className="ed-input" value={typeof value === 'string' ? value : ''} onChange={(e) => set(e.target.value || undefined)}>
          {!f.req && <option value="">{t.none}</option>}
          {f.req && typeof value !== 'string' && <option value="" disabled>{t.none}</option>}
          {opts.map((o) => <option key={o} value={o}>{name(o)}</option>)}
        </select>
      );
    }
    case 'datetime':
    case 'date':
    case 'time':
      return <input id={id} className="ed-input" type={f.p === 'datetime' ? 'datetime-local' : f.p} value={typeof value === 'string' ? value : ''} onChange={(e) => set(e.target.value || undefined)} />;
    case 'timerange': {
      const v = (value as Json) ?? {};
      const put = (k: 'open' | 'close', x: string) => {
        const next = { open: v.open ?? '', close: v.close ?? '', [k]: x };
        set(next.open || next.close ? next : undefined);
      };
      return (
        <span className="ed-pair">
          <input id={id} className="ed-input" type="time" aria-label={t.opens} value={v.open ?? ''} onChange={(e) => put('open', e.target.value)} />
          <input className="ed-input" type="time" aria-label={t.closes} value={v.close ?? ''} onChange={(e) => put('close', e.target.value)} />
        </span>
      );
    }
    case 'point':
      return <PointField id={id} value={value as Json | undefined} set={set} t={t} />;
    case 'ref': {
      const targets = page.blocks.filter((b) => b.id !== block.id && (!f.target || b.kind === f.target));
      const name = (b: Block) => [kindLabel(b.kind, lang), blockTitle(b)].filter(Boolean).join(' · ');
      return (
        <span className="ed-col">
          <select id={id} className="ed-input" value={typeof value === 'string' ? value : ''} onChange={(e) => set(e.target.value || (f.req ? '' : undefined))}>
            {(!f.req || !targets.some((b) => b.id === value)) && <option value="">{t.none}</option>}
            {targets.map((b) => <option key={b.id} value={b.id}>{name(b)}</option>)}
          </select>
          {f.target && !targets.length && canAdd(page, f.target, REGISTRY) && (
            <button type="button" className="ed-link" onClick={() => {
              const made = ctx.add(f.target!, true);
              if (made) set(made);
            }}>
              <Plus size={14} aria-hidden="true" />
              {t.addKind(kindLabel(f.target, lang))}
            </button>
          )}
        </span>
      );
    }
    case 'image':
      return <ImageField id={id} value={value as Json | undefined} set={set} ctx={ctx} />;
    case 'list':
      return <ListField id={id} f={f} value={value} ctx={ctx} path={path} />;
    default:
      return moreInput(f, value, set, id, lang);
  }
}

function PointField({ id, value, set, t }: { id: string; value: Json | undefined; set: (v: Json | undefined) => void; t: EditorCopy }) {
  const v = value ?? {};
  const [here, setHere] = useState<'idle' | 'busy' | 'failed'>('idle');
  const put = (k: 'lat' | 'lon', x: string) => {
    const next: Json = Object.fromEntries(
      Object.entries({ lat: v.lat, lon: v.lon, [k]: x === '' ? undefined : Number(x) }).filter(([, n]) => n !== undefined),
    );
    set(Object.keys(next).length ? next : undefined);
  };
  const locate = () => {
    if (!navigator.geolocation) return setHere('failed');
    setHere('busy');
    navigator.geolocation.getCurrentPosition(
      (p) => {
        set({ lat: Math.round(p.coords.latitude * 1e5) / 1e5, lon: Math.round(p.coords.longitude * 1e5) / 1e5 });
        setHere('idle');
      },
      () => setHere('failed'),
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  };
  return (
    <span className="ed-col">
      <span className="ed-pair">
        <input id={id} className="ed-input" type="number" step="0.0001" aria-label={t.lat} placeholder={t.lat} value={v.lat ?? ''} onChange={(e) => put('lat', e.target.value)} />
        <input className="ed-input" type="number" step="0.0001" aria-label={t.lon} placeholder={t.lon} value={v.lon ?? ''} onChange={(e) => put('lon', e.target.value)} />
      </span>
      <button type="button" className="ed-link" disabled={here === 'busy'} onClick={locate}>
        <MapPin size={14} aria-hidden="true" />
        {t.here}
      </button>
      {here === 'failed' && <span className="ed-err" role="status">{t.hereFailed}</span>}
    </span>
  );
}

function pageToken(p: EditPage): string {
  for (const b of p.blocks) if (typeof b.data?.token === 'string') return b.data.token;
  return 'USDC';
}

function newRow(page: EditPage, rows: Json[], f: FieldSpec, spec: Record<string, FieldSpec>): Json {
  const row: Json = {};
  for (const [k, rf] of Object.entries(spec)) {
    if (k === 'id') row.id = rowId(page, f.of === 'stage' ? 'st' : f.of ?? 'row');
    else if (!rf.req) continue;
    else if (rf.p === 'number') row[k] = Math.max(rf.min ?? 0, k === 'stock' ? 1 : 0);
    else if (rf.p === 'duration') row[k] = 60;
    else if (rf.p === 'choice' && rf.vocab === 'circle') row[k] = nextCircle(rows, REGISTRY.vocab.circle ?? []);
    else if (rf.p === 'choice') row[k] = (rf.options ?? REGISTRY.vocab[rf.vocab ?? ''] ?? [''])[0];
    else if (rf.p === 'datetime') row[k] = (rows[rows.length - 1]?.[k] as string) ?? '';
    else row[k] = '';
  }
  return row;
}

function useOpen(spec: Record<string, FieldSpec>) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const shown = useRef(new Set<string>());
  const key = (row: Json, i: number) => String(row.id ?? i);
  const isOpen = (row: Json, i: number) => {
    const k = key(row, i);
    if (open[k] !== undefined) return open[k];
    if (rowMissing(row, spec)) shown.current.add(k);
    return shown.current.has(k);
  };
  const toggle = (row: Json, i: number) => setOpen((o) => ({ ...o, [key(row, i)]: !isOpen(row, i) }));
  const opened = (id: string) => setOpen((o) => ({ ...o, [id]: true }));
  return { isOpen, toggle, opened };
}

function ListField({ id, f, value, ctx, path }: { id: string; f: FieldSpec; value: unknown; ctx: Ctx; path: (string | number)[] }) {
  const spec = REGISTRY.blocks[ctx.block.kind].records[f.of ?? ''] ?? {};
  return isTimeline(spec) ? <StageList id={id} f={f} value={value} ctx={ctx} path={path} spec={spec} /> : <RecordList id={id} f={f} value={value} ctx={ctx} path={path} spec={spec} />;
}

type ListProps = { id: string; f: FieldSpec; value: unknown; ctx: Ctx; path: (string | number)[]; spec: Record<string, FieldSpec> };

function ListHead({ label, count, canAddRow, onAdd, addLabel }: { label: string; count?: number; canAddRow: boolean; onAdd: () => void; addLabel: string }) {
  return (
    <div className="ei-head">
      <span className="ed-label">{count === undefined ? label : `${label} · ${count}`}</span>
      {canAddRow && (
        <button type="button" className="ed-link" onClick={onAdd}>
          <Plus size={14} aria-hidden="true" />
          {addLabel}
        </button>
      )}
    </div>
  );
}

function RecordList({ id, f, value, ctx, path, spec }: ListProps) {
  const { page, block, act, t, lang } = ctx;
  const rows: Json[] = Array.isArray(value) ? value : [];
  const max = f.max ?? 60;
  const min = f.min ?? 0;
  const step = stepperKey(spec);
  const nk = nameKey(spec);
  const thumb = spec.image?.p === 'image';
  const { isOpen, toggle, opened } = useOpen(spec);
  const it = inspectorCopy[lang];
  const label = fieldLabel(block.kind, String(path[path.length - 1]), lang);
  const put = (i: number, k: string, v: unknown) => act({ t: 'field', id: block.id, path: [...path, i, k], value: v });
  return (
    <div id={id} className="ei-list" tabIndex={-1}>
      <ListHead
        label={label}
        count={listCount(rows, step)}
        canAddRow={rows.length < max}
        addLabel={t.addRow}
        onAdd={() => {
          const row = newRow(page, rows, f, spec);
          opened(String(row.id));
          act({ t: 'field', id: block.id, path, value: [...rows, row] });
        }}
      />
      <ul className="ei-rows">
        {rows.map((row, i) => {
          const rid = `${id}-${i}`;
          const name = (nk && typeof row[nk] === 'string' && row[nk].trim()) || it.untitled;
          const src = thumb && row.image?.blob ? imageUrl(String(row.image.blob)) : null;
          const open = isOpen(row, i);
          return (
            <li key={String(row.id ?? i)} className={`ei-rec ${open ? 'open' : ''}`}>
              <div className={`ei-rec-row ${thumb ? 'th' : ''}`}>
                {thumb && (src
                  ? <img className="ei-rec-th" src={src} alt="" style={{ objectPosition: objectPosition(focusOf(row.image)) }} />
                  : <span className="ei-rec-th empty" aria-hidden="true"><ImagePlus size={14} /></span>)}
                <button type="button" className="ei-rec-name" aria-expanded={open} aria-controls={`${rid}-edit`} aria-label={it.editRow(name)} onClick={() => toggle(row, i)}>
                  <span className={`ed-clip ${name === it.untitled ? 'ei-faint' : ''}`}>{name}</span>
                  <ChevronDown size={16} aria-hidden="true" className="ei-chev" />
                </button>
                {step && <Stepper id={`${rid}-${step}`} value={row[step]} f={spec[step]} set={(v) => put(i, step, v)} label={`${fieldLabel(block.kind, `${f.of}.${step}`, lang)} · ${name}`} lang={lang} />}
              </div>
              {open && (
                <div id={`${rid}-edit`} className="ei-rec-edit">
                  {Object.entries(spec).filter(([k, rf]) => k !== 'id' && k !== step && editable(block.kind, k, rf)).map(([k, rf]) => {
                    const fid = `${rid}-${k}`;
                    const lbl = fieldLabel(block.kind, `${f.of}.${k}`, lang);
                    return (
                      <Row key={k} label={lbl} htmlFor={rf.p === 'image' || rf.p === 'list' ? undefined : fid}>
                        {valueInput(rf, row[k], (v) => put(i, k, v), fid, ctx, [...path, i, k], lbl)}
                      </Row>
                    );
                  })}
                  {rows.length > min && (
                    <button type="button" className="ed-link quiet" onClick={() => act({ t: 'field', id: block.id, path, value: rows.filter((_, j) => j !== i) })}>
                      <Trash2 size={14} aria-hidden="true" />
                      {t.removeRow}
                    </button>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function when(v: unknown, lang: Lang): string | null {
  if (typeof v !== 'string' || !v) return null;
  const d = new Date(v);
  if (!Number.isFinite(d.getTime())) return null;
  return d.toLocaleString(lang === 'uk' ? 'uk-UA' : 'en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function StageList({ id, f, value, ctx, path, spec }: ListProps) {
  const { page, block, act, t, lang } = ctx;
  const rows: Json[] = Array.isArray(value) ? value : [];
  const max = f.max ?? 60;
  const min = f.min ?? 0;
  const { isOpen, toggle, opened } = useOpen(spec);
  const it = inspectorCopy[lang];
  const circles = REGISTRY.vocab.circle ?? [];
  const now = new Date();
  const put = (i: number, k: string, v: unknown) => act({ t: 'field', id: block.id, path: [...path, i, k], value: v });
  return (
    <div id={id} className="ei-list" tabIndex={-1}>
      <ListHead
        label={fieldLabel(block.kind, String(path[path.length - 1]), lang)}
        canAddRow={rows.length < max}
        addLabel={it.addStage}
        onAdd={() => {
          const row = newRow(page, rows, f, spec);
          opened(String(row.id));
          act({ t: 'field', id: block.id, path, value: [...rows, row] });
        }}
      />
      <ol className="ei-stages">
        {rows.map((row, i) => {
          const rid = `${id}-${i}`;
          const circle = typeof row.circle === 'string' ? row.circle : '';
          const name = circle ? blockLabel(`vocab.circle.${circle}`, lang) : it.untitled;
          const at = when(row.opens, lang);
          const open = isOpen(row, i);
          const live = stageOpen(row.opens, now);
          return (
            <li key={String(row.id ?? i)} className={`ei-stage ${open ? 'open' : ''}`}>
              <button type="button" className="ei-stage-row" aria-expanded={open} aria-controls={`${rid}-edit`} onClick={() => toggle(row, i)}>
                <span className={`ei-dot ${live ? 'f' : ''}`} aria-hidden="true" />
                <span className={`ei-grow ed-clip ${i === 0 || live ? 'ei-strong' : ''}`}>{name}{live && <span className="ed-sr"> · {it.opened}</span>}</span>
                <span className={`ei-time ${at ? '' : 'ei-faint'}`}>{at ?? it.noTime}</span>
              </button>
              {open && (
                <div id={`${rid}-edit`} className="ei-stage-edit">
                  <div className="ei-chips" role="radiogroup" aria-label={fieldLabel(block.kind, `${f.of}.circle`, lang)} onKeyDown={radioKeys}>
                    {circles.map((c) => (
                      <button key={c} type="button" role="radio" aria-checked={circle === c} tabIndex={circle === c || (!circle && c === circles[0]) ? 0 : -1} className={`ei-chip ${circle === c ? 'on' : ''}`} onClick={() => put(i, 'circle', c)}>
                        {circle === c && <Check size={14} aria-hidden="true" />}
                        {blockLabel(`vocab.circle.${c}`, lang)}
                      </button>
                    ))}
                  </div>
                  <Row label={fieldLabel(block.kind, `${f.of}.opens`, lang)} htmlFor={`${rid}-opens`}>
                    <input id={`${rid}-opens`} className="ed-input" type="datetime-local" value={typeof row.opens === 'string' ? row.opens : ''} onChange={(e) => put(i, 'opens', e.target.value)} />
                  </Row>
                  {rows.length > min && (
                    <button type="button" className="ed-link quiet" onClick={() => act({ t: 'field', id: block.id, path, value: rows.filter((_, j) => j !== i) })}>
                      <Trash2 size={14} aria-hidden="true" />
                      {t.removeRow}
                    </button>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function measure(file: Blob): Promise<{ w: number; h: number }> {
  if (typeof createImageBitmap === 'function') {
    return createImageBitmap(file).then((b) => {
      const out = { w: b.width, h: b.height };
      b.close();
      return out;
    });
  }
  return new Promise((ok, no) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      ok({ w: img.naturalWidth, h: img.naturalHeight });
      URL.revokeObjectURL(url);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      no(new Error('decode'));
    };
    img.src = url;
  });
}

function FocusCover({ src, value, set, lang }: { src: string; value: Json; set: (v: unknown) => void; lang: Lang }) {
  const it = inspectorCopy[lang];
  const box = useRef<HTMLDivElement>(null);
  const drag = useRef(false);
  const f = focusOf(value);
  const at = (e: RPointerEvent) => {
    const r = box.current?.getBoundingClientRect();
    if (r) set({ ...value, focus: focusAt(r, e.clientX, e.clientY) });
  };
  return (
    <div
      ref={box}
      className="ei-cover"
      onPointerDown={(e) => {
        if (e.button !== 0) return;
        drag.current = true;
        e.currentTarget.setPointerCapture(e.pointerId);
        at(e);
      }}
      onPointerMove={(e) => drag.current && at(e)}
      onPointerUp={() => (drag.current = false)}
      onPointerCancel={() => (drag.current = false)}
    >
      <img src={src} alt="" draggable={false} style={{ objectPosition: objectPosition(f) }} />
      <button
        type="button"
        className="ei-fp"
        style={{ left: `${f.x * 100}%`, top: `${f.y * 100}%` }}
        aria-label={`${it.focusPoint}: ${it.focusAt(Math.round(f.x * 100), Math.round(f.y * 100))}`}
        onKeyDown={(e) => {
          const next = nudgeFocus(f, e.key, e.shiftKey);
          if (!next) return;
          e.preventDefault();
          set({ ...value, focus: next });
        }}
      />
    </div>
  );
}

function ImageField({ id, value, set, ctx }: { id: string; value: Json | undefined; set: (v: unknown) => void; ctx: Ctx }) {
  const { t, venue, lang } = ctx;
  const it = inspectorCopy[lang];
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [last, setLast] = useState<File | null>(null);
  const max = Number(REGISTRY.primitives.image.max_bytes);
  const maxAlt = Number(REGISTRY.primitives.image.max_alt);

  async function put(file: File) {
    setErr(null);
    setLast(file);
    if (!IMAGE_TYPES.includes(file.type)) return setErr(t.imageType);
    if (file.size > max) return setErr(t.imageBig);
    setBusy(true);
    try {
      const { w, h } = await measure(file);
      const { blob } = await uploadVenueImage(venue, file);
      set({ blob, w, h, alt: typeof value?.alt === 'string' ? value.alt : '' });
      setLast(null);
    } catch (e) {
      const why = e instanceof VenueRefusal && e.refused ? refusalLabel(e.refused, lang) : null;
      setErr(why && !why.startsWith('refused.') ? why : t.imageFailed);
    } finally {
      setBusy(false);
    }
  }

  const src = value?.blob ? imageUrl(String(value.blob)) : null;
  return (
    <div className="ed-image">
      {src ? (
        <>
          <FocusCover src={src} value={value!} set={set} lang={lang} />
          <p className="ed-hint">{it.dragFocus}</p>
        </>
      ) : (
        <button type="button" id={id} className="ei-drop" disabled={busy || !venue} onClick={() => input.current?.click()}>
          <ImagePlus size={20} aria-hidden="true" />
          <span>{busy ? t.uploading : venue ? t.upload : t.imageAfterSignIn}</span>
          <span className="ed-hint">{t.imageType} · {t.imageBig}</span>
        </button>
      )}
      {src && (
        <div className="ed-row g8">
          <button id={id} type="button" className="ed-btn s" disabled={busy || !venue} title={venue ? undefined : t.imageAfterSignIn} onClick={() => input.current?.click()}>
            <ImagePlus size={16} aria-hidden="true" />
            {busy ? t.uploading : t.replace}
          </button>
          {!busy && (
            <button type="button" className="ed-btn icon s" aria-label={t.remove} onClick={() => set(undefined)}>
              <Trash2 size={16} aria-hidden="true" />
            </button>
          )}
          <span className="ed-hint">{t.publicByLink}</span>
        </div>
      )}
      {err && last && !busy && (
        <button type="button" className="ed-link" onClick={() => put(last)}>
          <RotateCcw size={14} aria-hidden="true" />
          {t.retry}
        </button>
      )}
      <input
        ref={input}
        type="file"
        accept={IMAGE_TYPES.join(',')}
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (file) put(file);
        }}
      />
      {err && <p className="ed-err" role="alert">{err}</p>}
      {src && (
        <Row label={t.alt} htmlFor={`${id}-alt`}>
          <input id={`${id}-alt`} className="ed-input" maxLength={maxAlt} value={String(value?.alt ?? '')} onChange={(e) => set({ ...value, alt: e.target.value })} />
        </Row>
      )}
    </div>
  );
}

const rich = (f: FieldSpec) => f.p === 'list' || f.p === 'image';

const ORDER: Record<string, string[]> = { 'deal.drop': ['cover', 'products', 'total', 'stages', 'closes', 'title', 'note'] };

function DropItems({ ctx }: { ctx: Ctx }) {
  const { page, block, act, lang } = ctx;
  const it = inspectorCopy[lang];
  const items = dropItems(page, block);
  if (!items || !items.rows.length) return null;
  const spec = REGISTRY.blocks['cat.products'].records.product.stock;
  return (
    <div className="ei-list ei-drop-items">
      <ListHead label={it.dropItems} count={items.sum} canAddRow={false} onAdd={() => undefined} addLabel="" />
      <ul className="ei-rows">
        {items.rows.map((row, i) => {
          const name = row.name.trim() || it.untitled;
          const src = row.image?.blob ? imageUrl(String(row.image.blob)) : null;
          return (
            <li key={i} className="ei-rec">
              <div className="ei-rec-row th">
                {src
                  ? <img className="ei-rec-th" src={src} alt="" style={{ objectPosition: objectPosition(focusOf(row.image)) }} />
                  : <span className="ei-rec-th empty" aria-hidden="true"><ImagePlus size={14} /></span>}
                <span className={`ei-rec-name ed-clip ${name === it.untitled ? 'ei-faint' : ''}`}>{name}</span>
                <Stepper
                  id={`${block.id}-drop-${i}`} value={row.stock} f={spec} lang={lang}
                  label={`${fieldLabel('cat.products', 'product.stock', lang)} · ${name}`}
                  set={(v, typed) => typeof v === 'number' && act({ t: 'fields', sets: dropStockSets(page, block, i, v), burst: typed })}
                />
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function FieldForm({ ctx, touched, touch }: { ctx: Ctx; touched: Set<string>; touch: (k: string) => void }) {
  const { block, lang, act, error } = ctx;
  const spec = REGISTRY.blocks[block.kind];
  if (!spec) return null;
  const order = ORDER[block.kind];
  const rank = (k: string) => (order && order.includes(k) ? order.indexOf(k) : 99);
  const fields = Object.entries(spec.fields).filter(([k, f]) => editable(block.kind, k, f)).sort((a, b) => rank(a[0]) - rank(b[0]));
  const groups: [string, FieldSpec][][] = [];
  for (const entry of fields) {
    const prev = groups[groups.length - 1];
    if (prev && !rich(entry[1]) && !rich(prev[0][1])) prev.push(entry);
    else groups.push([entry]);
  }
  return (
    <>
      {groups.map((g) => (
        <section key={g[0][0]} className="ei-sec">
          {g.map(([k, f]) => {
            const id = fieldDom(block.id, k);
            const showErr = error && error.field === k && (touched.has(k) || touched.has('*'));
            const label = fieldLabel(block.kind, k, lang);
            const errText = showErr ? refusalLabel(error.refused, lang) : undefined;
            const set = (v: unknown) => act({ t: 'field', id: block.id, path: [k], value: v });
            return (
              <div key={k} onBlur={() => touch(k)}>
                {f.p === 'list' ? (
                  <div className="ed-field">
                    {valueInput(f, block.data[k], set, id, ctx, [k], label)}
                    {errText && <p className="ed-err" role="alert">{errText}</p>}
                  </div>
                ) : (
                  <Row label={label} htmlFor={f.p === 'image' ? undefined : id} error={errText}>
                    {valueInput(f, block.data[k], set, id, ctx, [k], label)}
                  </Row>
                )}
                {block.kind === 'deal.drop' && k === 'products' && <DropItems ctx={ctx} />}
              </div>
            );
          })}
        </section>
      ))}
    </>
  );
}

const SIZE_BOX: Record<Size, [number, number]> = { '1x1': [14, 14], '2x1': [30, 14], '2x2': [30, 30], '3x1': [44, 12] };

export function SizeSwatches({ block, look, lang, t, act }: { block: Block; look: Look; lang: Lang; t: EditorCopy; act: (a: Action) => void }) {
  if (!sizable(block.kind, look)) return null;
  const cur = (block.size as Size | undefined) ?? null;
  return (
    <div className="ei-sizes" role="radiogroup" aria-label={t.size} onKeyDown={radioKeys}>
      {SIZES.map((s, i) => {
        const name = blockLabel(`vocab.size.${s}`, lang);
        return (
          <button
            key={s}
            type="button"
            role="radio"
            aria-checked={cur === s}
            aria-label={`${name} ${s.replace('x', '×')}`}
            tabIndex={cur === s || (!cur && i === 0) ? 0 : -1}
            className={`ei-size ${cur === s ? 'on' : ''}`}
            onClick={() => act({ t: 'size', id: block.id, size: s })}
          >
            <i aria-hidden="true" style={{ width: SIZE_BOX[s][0], height: SIZE_BOX[s][1] }} />
          </button>
        );
      })}
    </div>
  );
}

export function ToneSwatches({ block, lang, act }: { block: Block; lang: Lang; act: (a: Action) => void }) {
  const it = inspectorCopy[lang];
  const cur: string | null = typeof (block as Json).tone === 'string' ? (block as Json).tone : null;
  const tones: (string | null)[] = [null, ...(REGISTRY.vocab.tone ?? []).filter((x) => TONE_VAR[x])];
  return (
    <div id={`ed-tone-${block.id}`} className="ei-tones" role="radiogroup" aria-label={it.cardTone} tabIndex={-1} onKeyDown={radioKeys}>
      {tones.map((x) => (
        <button
          key={x ?? 'none'}
          type="button"
          role="radio"
          aria-checked={cur === x}
          aria-label={x ? blockLabel(`vocab.tone.${x}`, lang) : it.noTone}
          title={x ? blockLabel(`vocab.tone.${x}`, lang) : it.noTone}
          tabIndex={cur === x ? 0 : -1}
          className={`ei-tone ${cur === x ? 'on' : ''}`}
          onClick={() => act({ t: 'tone', id: block.id, tone: x })}
        >
          <span style={x ? { background: `var(${TONE_VAR[x].bg})` } : undefined} className={x ? '' : 'none'} />
        </button>
      ))}
    </div>
  );
}

export function AlsoShows({ block, page, lang, act }: { block: Block; page: EditPage; lang: Lang; act: (a: Action) => void }) {
  const it = inspectorCopy[lang];
  const allowed = REGISTRY.blocks[block.kind]?.surfaces.filter((s) => LIVE_SURFACES.includes(s)) ?? [];
  const shown = alsoSurfaces(allowed, pageSurfaces(page.on).filter((x) => x.active).map((x) => x.s));
  if (shown.length < 2) return null;
  return (
    <section className="ei-sec">
      <span className="ed-label" id={`ed-also-${block.id}`}>{it.alsoShowsIn}</span>
      <div className="ed-surfaces" role="group" aria-labelledby={`ed-also-${block.id}`}>
        {blockSurfaces(block.on as string[] | undefined, shown).map(({ s, active, disabled }) => {
          return (
            <button key={s} type="button" aria-pressed={active} disabled={disabled} className={`ed-surface ei-surface ${active ? 'on' : ''}`} onClick={() => act({ t: 'blockOn', id: block.id, surface: s, on: !active, allowed })}>
              {active && <Check size={14} aria-hidden="true" />}
              {blockLabel(`vocab.surface.${s}`, lang)}
            </button>
          );
        })}
      </div>
    </section>
  );
}

export const SurfaceChips = AlsoShows;
