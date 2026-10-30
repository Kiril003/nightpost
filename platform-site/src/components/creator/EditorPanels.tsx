import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { LOCAL } from '../../lib/creator/entry';
import {
  AlertTriangle, CalendarCheck, CalendarClock, Check, Copy, Eye, EyeOff, MapPin, MessageSquare, Package, PanelTop,
  Clock, QrCode, ShoppingBag, Split, Star, UserPlus, Users, Wallet, Store, Calendar, Type, X,
} from 'lucide-react';
import type { Block } from '../../lib/creator/arrange';
import { onSite } from '../../lib/creator/arrange';
import { LOOK, LOOKS, type Look } from '../../lib/creator/looks';
import {
  BOOK_MODES, CIRCLES, LIVE_SURFACES, SIZES, accessOf, blockSurfaces, isPublic, liveSurfaces, pageSurfaces, type SurfaceState, dependents, diff, publishable, sizable,
  type Action, type Change, type Check as CheckRow, type EditPage, type Fix, type Size,
} from '../../lib/creator/editor';
import { REGISTRY } from '../../lib/blocks/registry';
import { blockLabel, refusalLabel } from '../../i18n/blocks';
import type { EditorCopy } from '../../i18n/editor';
import { inspectorCopy } from '../../i18n/editorInspector';
import type { Lang } from '../../i18n/types';
import type { Dropped, VersionRow } from '../../lib/venuesApi';
import { Row, fieldLabel } from './EditorFields';
import { nameOf } from '../../lib/creator/pro';

export const KIND_ICON: Record<string, typeof Star> = {
  'id.hero': PanelTop, 'deal.drop': Package, 'book.slots': CalendarClock, 'cat.products': ShoppingBag, 'com.reviews': Star,
  'loc.message': MessageSquare, 'loc.meet_point': MapPin, 'pay.qr': QrCode, 'evt.rsvp': CalendarCheck, 'org.members': Users,
  'pay.split_cost': Split, 'org.join': UserPlus, 'pay.wallet': Wallet, 'cat.services': Store, 'evt.single': Calendar,
  'id.name': Type, 'loc.address': MapPin,
};

export const kindLabel = (kind: string, lang: Lang) => {
  const l = blockLabel(`block.${kind}`, lang);
  return l === `block.${kind}` ? kind : l;
};

export function blockTitle(b: Block): string {
  const d = b.data ?? {};
  for (const k of ['title', 'headline', 'name', 'label', 'text']) if (typeof d[k] === 'string' && d[k].trim()) return d[k];
  return '';
}

export const fieldDom = (block: string, field: string) => `ed-f-${block}-${field}`;

export function LookPicker({ look, lang, t, onPick }: { look: Look; lang: Lang; t: EditorCopy; onPick: (l: Look) => void }) {
  return (
    <div className="ed-looks" role="radiogroup" aria-label={t.pageLook}>
      {LOOKS.map((l) => (
        <button key={l} type="button" role="radio" aria-checked={look === l} className={`ed-look ${look === l ? 'on' : ''}`} onClick={() => onPick(l)}>
          <span className="ed-aa" style={{ fontFamily: LOOK[l].display, fontWeight: LOOK[l].displayWeight, letterSpacing: LOOK[l].tracking }} aria-hidden="true">Aa</span>
          <span>{t.looks[l] ?? blockLabel(`vocab.look.${l}`, lang)}</span>
        </button>
      ))}
    </div>
  );
}

export const BOOKING_CIRCLES = CIRCLES.filter((c) => c === 'everyone');

const SIZE_GLYPH: Record<Size, string> = { '1x1': '1×1', '2x1': '2×1', '2x2': '2×2', '3x1': '3×1' };

export function SizeChips({ block, look, lang, t, act, compact = false }: { block: Block; look: Look; lang: Lang; t: EditorCopy; act: (a: Action) => void; compact?: boolean }) {
  if (!sizable(block.kind, look)) return null;
  const cur = (block.size as Size | undefined) ?? null;
  return (
    <div className={`ed-sizes ${compact ? 'compact' : ''}`} role="radiogroup" aria-label={t.size}>
      {SIZES.map((s) => (
        <button key={s} type="button" role="radio" aria-checked={cur === s} title={blockLabel(`vocab.size.${s}`, lang)} className={`ed-size ${cur === s ? 'on' : ''}`} onClick={() => act({ t: 'size', id: block.id, size: s })}>
          {SIZE_GLYPH[s]}
        </button>
      ))}
    </div>
  );
}

function surfaceList(on: string[] | undefined, lang: Lang, t: EditorCopy): string {
  const live = liveSurfaces(on);
  return live.length ? live.map((s) => blockLabel(`vocab.surface.${s}`, lang)).join(', ') : t.notPublic;
}

function Surfaces({ items, lang, label, hint, toggle }: { items: SurfaceState[]; lang: Lang; label: string; hint?: string; toggle: (s: string, v: boolean) => void }) {
  const hid = useId();
  return (
    <>
      <div className="ed-surfaces" role="group" aria-label={label} aria-describedby={hint ? hid : undefined}>
        {items.map(({ s, active, disabled }) => (
          <button key={s} type="button" aria-pressed={active} aria-disabled={disabled} className={`ed-surface ${active ? 'on' : ''}`} onClick={() => !disabled && toggle(s, !active)}>
            {active && <Check size={14} aria-hidden="true" />}
            {blockLabel(`vocab.surface.${s}`, lang)}
          </button>
        ))}
      </div>
      {hint && <p className="ed-hint" id={hid}>{hint}</p>}
    </>
  );
}

const mapHint = (on: string[] | undefined, t: EditorCopy) => (pageSurfaces(on).some((x) => x.s === 'M' && x.disabled) ? t.mapNeedsSite : undefined);

export function WhoSees({ block, lang, t, act, circles = BOOKING_CIRCLES }: { block: Block; lang: Lang; t: EditorCopy; act: (a: Action) => void; circles?: readonly (typeof CIRCLES)[number][] }) {
  const allowed = REGISTRY.blocks[block.kind]?.surfaces.filter((s) => LIVE_SURFACES.includes(s)) ?? [];
  return (
    <div className="ed-form">
      <div className="ed-field ed-inline">
        <span className="ed-label" id={`${block.id}-hidden`}>{t.hidden}</span>
        <button type="button" role="switch" aria-labelledby={`${block.id}-hidden`} aria-checked={!!block.hidden} className={`ed-switch ${block.hidden ? 'on' : ''}`} onClick={() => act({ t: 'hide', id: block.id, hidden: !block.hidden })}>
          <i aria-hidden="true" />
        </button>
      </div>
      {allowed.length > 0 && (
        <div className="ed-field">
          <span className="ed-label">{t.showsOn}</span>
          <Surfaces items={blockSurfaces(block.on as string[] | undefined, allowed)} lang={lang} label={t.showsOn} toggle={(s, v) => act({ t: 'blockOn', id: block.id, surface: s, on: v, allowed })} />
        </div>
      )}
      {block.kind === 'book.slots' && (
        <div className="ed-field">
          <span className="ed-label">{t.whoCanBook}</span>
          <div className="ed-circles">
            {circles.map((c) => (
              <label key={c} className="ed-circle">
                <span>{blockLabel(`vocab.circle.${c}`, lang)}</span>
                <select className="ed-input" value={accessOf(block, c)} onChange={(e) => act({ t: 'access', id: block.id, circle: c, mode: e.target.value as (typeof BOOK_MODES)[number] })}>
                  {BOOK_MODES.map((m) => <option key={m} value={m}>{blockLabel(`vocab.book_mode.${m}`, lang)}</option>)}
                </select>
              </label>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export function PagePanel({ page, look, lang, t, act, published, venue }: { page: EditPage; look: Look; lang: Lang; t: EditorCopy; act: (a: Action) => void; published: boolean; venue: string }) {
  const name = page.blocks.find((b) => b.kind === 'id.name');
  return (
    <div className="ed-form">
      {name && (
        <Row label={t.pageName} htmlFor={fieldDom(name.id, 'name')}>
          <input id={fieldDom(name.id, 'name')} className="ed-input" maxLength={80} value={String(name.data.name ?? '')} onChange={(e) => act({ t: 'field', id: name.id, path: ['name'], value: e.target.value })} />
        </Row>
      )}
      <div className="ed-field">
        <span className="ed-label">{t.pageLook}</span>
        <LookPicker look={look} lang={lang} t={t} onPick={(l) => act({ t: 'look', look: l })} />
      </div>
      <div className="ed-field">
        <span className="ed-label">{t.pageShowsOn}</span>
        <Surfaces items={pageSurfaces(page.on)} hint={mapHint(page.on, t)} lang={lang} label={t.pageShowsOn} toggle={(s, v) => act({ t: 'pageOn', surface: s, on: v })} />
      </div>
      {published && (
        <a className="ed-link" href={`/p/${encodeURIComponent(venue)}`} target="_blank" rel="noreferrer">{t.openPage}</a>
      )}
      {venue !== LOCAL && <Link className="ed-link quiet" to={`/build/place?form=1&venue=${encodeURIComponent(venue)}`}>{t.openOld}</Link>}
    </div>
  );
}

export function Layers({ page, sel, lang, t, onSelect, act }: { page: EditPage; sel: string | null; lang: Lang; t: EditorCopy; onSelect: (id: string) => void; act: (a: Action) => void }) {
  return (
    <ol className="ed-layers" aria-label={t.layers}>
      {page.blocks.map((b, i) => {
        const Icon = KIND_ICON[b.kind] ?? PanelTop;
        const away = !onSite(b) ? ((b.on as string[]) ?? []).map((s) => blockLabel(`vocab.surface.${s}`, lang)).join(' · ') : '';
        return (
          <li key={b.id} className={`ed-layer ${sel === b.id ? 'on' : ''} ${b.hidden ? 'off' : ''}`}>
            <button type="button" className="ed-layer-main" aria-current={sel === b.id} onClick={() => onSelect(b.id)}>
              <Icon size={16} aria-hidden="true" />
              <span className="ed-layer-text">
                <span>{kindLabel(b.kind, lang)}</span>
                {(blockTitle(b) || away) && <span className="ed-hint">{[blockTitle(b), away].filter(Boolean).join(' · ')}</span>}
              </span>
            </button>
            <span className="ed-layer-acts">
              <button type="button" className="ed-btn icon s" aria-label={`${t.moveUp}: ${kindLabel(b.kind, lang)}`} disabled={i === 0} onClick={() => act({ t: 'nudge', id: b.id, by: -1 })}>↑</button>
              <button type="button" className="ed-btn icon s" aria-label={`${t.moveDown}: ${kindLabel(b.kind, lang)}`} disabled={i === page.blocks.length - 1} onClick={() => act({ t: 'nudge', id: b.id, by: 1 })}>↓</button>
              <button type="button" className="ed-btn icon s" aria-label={`${b.hidden ? t.show : t.hide}: ${kindLabel(b.kind, lang)}`} aria-pressed={!!b.hidden} onClick={() => act({ t: 'hide', id: b.id, hidden: !b.hidden })}>
                {b.hidden ? <EyeOff size={15} aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
              </button>
            </span>
          </li>
        );
      })}
    </ol>
  );
}

export function removeBlockers(page: EditPage, id: string, lang: Lang): string {
  return dependents(page, id).map((b) => kindLabel(b.kind, lang)).join(', ');
}

const MARK: Record<string, string> = { added: '+', removed: '−', moved: '~', changed: '~', hidden: '~', shown: '~', look: '~', on: '~' };

export type PubState = { k: 'idle' } | { k: 'busy' } | { k: 'done' } | { k: 'refused'; text: string } | { k: 'network' };

const toLocalInput = (ms: number) => {
  const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000);
  return d.toISOString().slice(0, 16);
};

export const dropReason = (reason: string, t: EditorCopy): string => (reason === 'bad_image' ? t.dropImage : t.dropInvalid);

export function PublishSheet({
  page, base, cs, versions, published, venue, lang, t, online, pub, act, onFix, onPublish, onRestore, onClose,
  scheduled = null, onSchedule, onCancelSchedule, followers = null, onUnpublish, dropped = [],
}: {
  page: EditPage; base: EditPage | null; cs: CheckRow[]; versions: VersionRow[] | null; published: boolean; venue: string; lang: Lang; t: EditorCopy;
  online: boolean; pub: PubState; act: (a: Action) => void; onFix: (f: Fix) => void; onPublish: () => void; onRestore: (n: number) => void; onClose: () => void;
  scheduled?: number | null; onSchedule?: (atMs: number) => void; onCancelSchedule?: () => void; followers?: number | null; onUnpublish?: () => void; dropped?: Dropped[];
}) {
  const [picking, setPicking] = useState(false);
  const [sure, setSure] = useState(false);
  const [at, setAt] = useState(() => toLocalInput(Date.now() + 3600_000));
  const fmt = (ms: number) => new Date(ms).toLocaleString(lang === 'uk' ? 'uk-UA' : 'en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const changes = diffRows(base, page, lang, t);
  const okN = cs.filter((c) => c.ok).length;
  const blocked = !publishable(cs);
  const nothing = published && changes.length === 0;
  const next = (versions?.[0]?.n ?? 0) + 1;
  const host = typeof window === 'undefined' ? '' : window.location.host;
  const url = `${host}/p/${venue}`;
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const tm = setTimeout(() => setCopied(false), 1600);
    return () => clearTimeout(tm);
  }, [copied]);
  return (
    <div className="ed-pub">
      <header className="ed-pub-head">
        <div className="ed-grow">
          <h2 className="ed-h">{t.publish} {t.version(next)}</h2>
          <p className="ed-hint">
            {changes.length ? t.changesN(changes.length) : t.noChanges}
            {versions?.[0] ? ` ${t.sinceV(versions[0].n, new Date(versions[0].at_ms).toLocaleDateString(lang === 'uk' ? 'uk-UA' : 'en-GB', { day: 'numeric', month: 'short' }))}` : ''}
          </p>
        </div>
        <button type="button" className="ed-btn icon" aria-label={t.close} onClick={onClose}><X size={18} aria-hidden="true" /></button>
      </header>
      <section className="ed-pub-sec" aria-label={t.changes}>
        <h3 className="ed-label">{t.changes}</h3>
        {changes.length === 0 ? (
          <p className="ed-hint">{t.noChanges}</p>
        ) : (
          <ul className="ed-list">
            {changes.slice(0, 12).map((c, i) => (
              <li key={i} className="ed-change">
                <span className="ed-mark" aria-hidden="true">{MARK[c.what]}</span>
                <span className="ed-grow">{c.name}</span>
                <span className="ed-hint">{c.detail}</span>
              </li>
            ))}
            {changes.length > 12 && <li className="ed-hint">+{changes.length - 12}</li>}
          </ul>
        )}
      </section>
      <section className="ed-pub-sec" aria-label={t.checks}>
        <h3 className="ed-label ed-row sp">
          <span>{t.checks}</span>
          <span>{t.checksOf(okN, cs.length)}</span>
        </h3>
        <ul className="ed-list">
          {cs.map((c) => (
            <li key={c.key} className={`ed-check ${c.ok ? 'ok' : c.key === 'alt' ? 'warn' : 'bad'}`}>
              {c.ok ? <Check size={15} aria-hidden="true" /> : <AlertTriangle size={15} aria-hidden="true" />}
              <span className="ed-grow">
                {c.key === 'alt' ? (c.ok ? t.check.altOk : t.check.alt(c.count ?? 1)) : t.check[c.key as 'name' | 'place' | 'sample' | 'valid' | 'wallet']}
                {!c.ok && c.refused && <span className="ed-hint"> · {refusalLabel(c.refused, lang)}</span>}
                {!c.ok && c.blocks && (
                  <span className="ed-hint"> · {c.blocks.map((id) => page.blocks.find((b) => b.id === id)).filter(Boolean).map((b) => kindLabel(b!.kind, lang)).join(', ')}</span>
                )}
              </span>
              {!c.ok && c.fix && (c.fix.block || c.fix.add) && (
                <button type="button" className="ed-link" onClick={() => onFix(c.fix!)}>{t.fix}</button>
              )}
            </li>
          ))}
        </ul>
      </section>
      <section className="ed-pub-sec" aria-label={t.goesTo}>
        <h3 className="ed-label">{t.goesTo}</h3>
        {isPublic(page.on) && (
          <div className="ed-url">
            <span className="ed-grow ed-mono">{url}</span>
            <button
              type="button"
              className="ed-btn icon s"
              aria-label={t.copyLink}
              onClick={() => navigator.clipboard?.writeText(`https://${url}`).then(() => setCopied(true), () => undefined)}
            >
              {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
            </button>
          </div>
        )}
        <Surfaces items={pageSurfaces(page.on)} hint={mapHint(page.on, t)} lang={lang} label={t.goesTo} toggle={(s, v) => act({ t: 'pageOn', surface: s, on: v })} />
        {followers !== null && (
          <p className="ed-hint ed-row g8"><Users size={14} aria-hidden="true" />{followers > 0 ? t.followersN(followers) : t.noFollowers}</p>
        )}
      </section>
      <div className="ed-pub-go">
        {scheduled !== null && (
          <p className="ed-sched">
            <Clock size={15} aria-hidden="true" />
            <span className="ed-grow">{t.scheduled(fmt(scheduled))}</span>
            {onCancelSchedule && <button type="button" className="ed-link" onClick={onCancelSchedule}>{t.cancelSchedule}</button>}
          </p>
        )}
        {picking && onSchedule && (
          <div className="ed-field">
            <label className="ed-label" htmlFor="ed-sched-at">{t.scheduleAt}</label>
            <div className="ed-row g8">
              <input id="ed-sched-at" className="ed-input ed-grow" type="datetime-local" value={at} min={toLocalInput(Date.now() + 60_000)} max={toLocalInput(Date.now() + 365 * 86_400_000)} onChange={(e) => setAt(e.target.value)} />
              <button
                type="button"
                className="ed-btn ink"
                disabled={!at || new Date(at).getTime() <= Date.now() || blocked || !online}
                onClick={() => { onSchedule(new Date(at).getTime()); setPicking(false); }}
              >
                {t.schedule}
              </button>
            </div>
          </div>
        )}
        <div className="ed-row g8">
          {onSchedule && !picking && (
            <button type="button" className="ed-btn ed-half" disabled={blocked || nothing || !online} onClick={() => setPicking(true)}>
              <Clock size={16} aria-hidden="true" />{t.schedule}
            </button>
          )}
          <button type="button" className="ed-btn primary wide ed-half" disabled={blocked || nothing || !online || pub.k === 'busy'} onClick={onPublish}>
            {pub.k === 'busy' ? t.publishing : t.publishNow}
          </button>
        </div>
        <p className="ed-status" role="status">
          {pub.k === 'done' ? t.published : pub.k === 'refused' ? pub.text : pub.k === 'network' || !online ? t.network : ''}
        </p>
      </div>
      <section className="ed-pub-sec" aria-label={t.history}>
        <h3 className="ed-label">{t.history}</h3>
        {versions === null && !dropped.length ? null : versions?.length === 0 && !dropped.length ? (
          <p className="ed-hint">{t.noVersions}</p>
        ) : (
          <ul className="ed-list">
            {[
              ...(versions ?? []).slice(0, 10).map((v, i) => ({ at: v.at_ms, v, i })),
              ...dropped.map((d) => ({ at: d.dropped_ms, d })),
            ].sort((a, b) => b.at - a.at).map((r) => 'v' in r ? (
              <li key={`v${r.v.n}`} className="ed-version">
                <span className="ed-mono">{t.version(r.v.n)}</span>
                <span className="ed-grow ed-hint">{new Date(r.v.at_ms).toLocaleString(lang === 'uk' ? 'uk-UA' : 'en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</span>
                {r.i === 0 && published ? <span className="ed-tag">{t.live}</span> : <button type="button" className="ed-link" onClick={() => onRestore(r.v.n)}>{t.restore}</button>}
              </li>
            ) : (
              <li key={`d${r.d.dropped_ms}`} className="ed-version">
                <Clock size={14} aria-hidden="true" />
                <span className="ed-grow ed-hint">{fmt(r.d.at_ms)} · {t.dropRow} · {dropReason(r.d.reason, t)}</span>
              </li>
            ))}
          </ul>
        )}
        {published && onUnpublish && (
          <div className="ed-row g8">
            {sure ? (
              <>
                <button type="button" className="ed-btn danger" onClick={() => { setSure(false); onUnpublish(); }}>{t.unpublish}</button>
                <button type="button" className="ed-link" onClick={() => setSure(false)}>{t.cancelSchedule}</button>
              </>
            ) : (
              <button type="button" className="ed-link danger" onClick={() => setSure(true)}>{t.unpublish}</button>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

function diffRows(base: EditPage | null, page: EditPage, lang: Lang, t: EditorCopy): { what: string; name: string; detail: string }[] {
  return diff(base, page).map((c: Change) => {
    if (c.kind === 'page') {
      const detail = c.what === 'look' ? (c.detail ?? []).map((l) => t.looks[l] ?? l).join(' → ') : surfaceList(c.detail, lang, t);
      return { what: c.what, name: c.what === 'look' ? t.pageLook : t.pageShowsOn, detail };
    }
    const b = page.blocks.find((x) => x.id === c.id) ?? base?.blocks.find((x) => x.id === c.id);
    const name = nameOf(kindLabel(c.kind, lang), b ? blockTitle(b) : '');
    const fields = (c.detail ?? []).map((k) => (k === 'size' ? t.size : k === 'on' ? t.showsOn : k === 'tone' ? inspectorCopy[lang].cardTone : fieldLabel(c.kind, k, lang)));
    const detail = c.what === 'changed' && fields.length ? fields.slice(0, 2).join(', ') + (fields.length > 2 ? ` +${fields.length - 2}` : '') : t.what[c.what];
    return { what: c.what, name, detail };
  });
}

