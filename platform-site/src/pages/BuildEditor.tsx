import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as RPointerEvent } from 'react';
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, Clock, Eye, GripVertical, History as HistoryIcon, Monitor, Move, Palette, Pencil, Plus, Redo2, RotateCcw, Smartphone, Trash2, Undo2, X,
  ChevronUp, ChevronDown, EyeOff, Minus, MoreHorizontal,
} from 'lucide-react';
import '@fontsource-variable/source-serif-4/opsz.css';
import '@fontsource-variable/source-serif-4/opsz-italic.css';
import '@fontsource-variable/inter-tight';
import '@fontsource-variable/wix-madefor-display';
import '../styles/creator.css';
import '../styles/editor.css';
import CreatorPage from '../components/creator/CreatorPage';
import {
  Layers, LookPicker, PagePanel, PublishSheet, SizeChips, dropReason, fieldDom, kindLabel, removeBlockers, type PubState,
} from '../components/creator/EditorPanels';
import { useLanguage } from '../i18n';
import { editorCopy, type EditorCopy } from '../i18n/editor';
import { refusalLabel } from '../i18n/blocks';
import type { Lang } from '../i18n/types';
import { isLook, type Look, type Theme } from '../lib/creator/looks';
import type { Who } from '../lib/creator/start';
import {
  LOCAL, clearLocal, entryRoute, lastStart, localStart, openTarget, pageName, publishStep, readLocal, starterTemplate, writeLocal, type Local,
} from '../lib/creator/entry';
import { REGISTRY } from '../lib/blocks/registry';
import {
  apply, canAdd, checks, dependents, draftShape, draftVerdict, fixFor, fitsKeepalive, history, isPublic, redo, seal, sizable, starterPage, undo,
  type Action, type EditPage, type Fix, type History,
} from '../lib/creator/editor';
import { readToken, writeToken } from '../lib/account-api';
import { EmailDoor, QrDoor } from '../components/account/SignedOut';
import { accountCopy } from '../i18n/account';
import {
  cancelSchedule, createVenue, getAudience, getDraft, getPublishedPage, markDropsSeen, openMediaSession, getVersion, getVenue, listVersions, myVenues, publishV2, saveDraft, schedulePublish, unpublishVenue, VenueRefusal, VenuesUnavailable,
  type Dropped, type V2Page, type Venue, type VersionRow,
} from '../lib/venuesApi';
import { setMediaVenue } from '../lib/creator/samples';
import { upgradePage } from '../lib/creator/upgrade';
import { hold } from '../lib/fresh';
import { builderChrome, builderTheme } from '../lib/creator/chrome';
import { Inspector, type InspectorTab } from '../components/creator/EditorInspector';
import { useTrap } from '../components/creator/useTrap';
import { Library } from '../components/creator/EditorLibrary';
import { SizeSwatches, ToneSwatches } from '../components/creator/EditorFields';
import { toned } from '../lib/creator/inspector';
import { inspectorCopy } from '../i18n/editorInspector';
import { proCopy } from '../i18n/editorPro';
import { Pages, PreviewSwitch, ProInspector, ProLayers, ProLeft, ProStatus, ProTools, ProVersion, Suggested, ViewSwitch, type ProLeftTab } from '../components/creator/EditorPro';
import { ghostPage, pageFor, pageRows, readView, versionChip, writeView, type Preview, type EditorView, type PageRow } from '../lib/creator/pro';

type Owned = Venue;
const MEDIA_RENEW_MS = 45 * 60_000;
const MEDIA_SESSION_MS = 3600_000;
const keepSeen = (got: Dropped[]) => (ds: Dropped[]) => got.map((x) => ({ ...x, seen: x.seen || ds.some((y) => y.dropped_ms === x.dropped_ms && y.seen) }));

type Ready = { k: 'ready'; venue: Owned; live: EditPage | null; start: EditPage; base: number; restored: boolean; schedule: number | null; savedAt: number | null; dropped: Dropped[]; media: boolean; local: boolean; who: Who; edited: boolean };
type Load =
  | { k: 'loading' }
  | { k: 'signedOut' }
  | { k: 'notYours' }
  | { k: 'notFound' }
  | { k: 'unavailable' }
  | { k: 'network' }
  | { k: 'go'; to: string }
  | Ready;

const STASH = (id: string) => `np.editor.${id}`;

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function localReady(l: Local): Ready {
  const venue: Owned = { id: LOCAL, name: pageName(l.page), published: false, rev: 0, page: null, rating: null, editor: 2 };
  return { k: 'ready', venue, live: null, start: l.page, base: 0, restored: false, schedule: null, savedAt: null, dropped: [], media: false, local: true, who: l.who, edited: l.edited };
}

type Ask = { lang: Lang; who: string | null; look: string | null; fresh: boolean };

function readStash(id: string): { page: EditPage; base: number } | null {
  try {
    const raw = sessionStorage.getItem(STASH(id));
    const v = raw ? JSON.parse(raw) : null;
    return v && v.page?.v === 2 && Array.isArray(v.page.blocks) ? v : null;
  } catch {
    return null;
  }
}
function writeStash(id: string, page: EditPage, base: number) {
  try {
    sessionStorage.setItem(STASH(id), JSON.stringify({ page, base }));
  } catch {
    /* the edit stays in memory only */
  }
}
function clearStash(id: string) {
  try {
    sessionStorage.removeItem(STASH(id));
  } catch {
    /* nothing to clear */
  }
}

function canon(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(canon);
  if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon((v as Record<string, unknown>)[k])]));
  return v;
}
const same = (a: unknown, b: unknown) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));

function failure(e: unknown): Load {
  if (e instanceof VenuesUnavailable) return { k: 'unavailable' };
  if (e instanceof VenueRefusal) {
    if (e.status === 401) return { k: 'signedOut' };
    if (e.status === 403) return { k: 'notYours' };
    if (e.status === 404) return { k: 'notFound' };
  }
  return { k: 'network' };
}

async function load(id: string, look: Look, ask: Ask): Promise<Load> {
  if (id === LOCAL) {
    const store = storage();
    const kept = readLocal(store);
    if (readToken() && !ask.fresh && !kept?.edited) {
      const to = openTarget({ local: kept, signedIn: true, fresh: ask.fresh, venues: await myVenues().catch(() => null) });
      if (to !== LOCAL) return { k: 'go', to: `/build/edit/${encodeURIComponent(to)}` };
    }
    return localReady(localStart({ local: kept, who: ask.who, look: ask.look, last: lastStart(store), lang: ask.lang }));
  }
  if (!readToken()) return { k: 'signedOut' };
  let venue: Owned | undefined;
  try {
    venue = (await myVenues()).find((v) => v.id === id) as Owned | undefined;
  } catch (e) {
    return failure(e);
  }
  if (!venue) {
    try {
      await getVenue(id);
      return { k: 'notYours' };
    } catch (e) {
      return failure(e);
    }
  }
  try {
    const [view, draft, media] = await Promise.all([getPublishedPage(id), getDraft(id), openMediaSession(id)]);
    const stored = view.page as EditPage | null;
    const live = view.published && stored?.v === 2 ? stored : null;
    const stash = readStash(id);
    const saved = draft.page as EditPage | null;
    const landed = !!stash && !!saved && same(stash.page, saved);
    if (landed) clearStash(id);
    const kept = landed ? null : stash;
    const upgrade = venue.editor !== 2 && !saved ? upgradePage(venue) : null;
    const made = upgrade ? await saveDraft(id, upgrade as V2Page, draft.updated_ms ?? 0).catch(() => null) : null;
    const start = kept?.page ?? saved ?? upgrade ?? (stored?.v === 2 && stored.blocks.length ? stored : starterPage({ name: venue.name, look }));
    const updated = made?.updated_ms ?? draft.updated_ms;
    const base = kept && updated != null ? kept.base : updated ?? 0;
    return { k: 'ready', venue, live, start, base, restored: !!kept, schedule: draft.schedule?.at_ms ?? null, savedAt: (saved || made) && updated ? updated : null, dropped: draft.dropped ?? [], media, local: false, who: 'me', edited: true };
  } catch (e) {
    const f = failure(e);
    return f.k === 'notFound' ? { k: 'unavailable' } : f;
  }
}

function useWide(): boolean {
  const q = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(min-width: 900px)') : null;
  const [wide, setWide] = useState(q?.matches ?? true);
  useEffect(() => {
    if (!q) return;
    const on = (e: MediaQueryListEvent) => setWide(e.matches);
    q.addEventListener('change', on);
    return () => q.removeEventListener('change', on);
  }, [q]);
  return wide;
}

function useOnline(): boolean {
  const [on, setOn] = useState(typeof navigator === 'undefined' ? true : navigator.onLine !== false);
  useEffect(() => {
    const up = () => setOn(true);
    const down = () => setOn(false);
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, []);
  return on;
}

export default function BuildEditor() {
  const { id: raw } = useParams();
  const id = !raw || raw === 'new' ? LOCAL : raw;
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { lang } = useLanguage();
  const t = editorCopy[lang];
  const theme = builderTheme(params.get('theme'));
  const lookParam = params.get('look');
  const look: Look = isLook(lookParam) ? lookParam : 'shop';
  const whoParam = params.get('who');
  const fresh = params.get('new') === '1';
  const moved = raw ? null : entryRoute(params);
  const [state, setState] = useState<Load>({ k: 'loading' });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (moved) return;
    let alive = true;
    setState({ k: 'loading' });
    load(id, look, { lang, who: whoParam, look: lookParam, fresh }).then((s) => alive && setState(s));
    return () => {
      alive = false;
    };
  }, [id, look, tick, lang, whoParam, lookParam, fresh, moved]);

  useEffect(() => {
    if (state.k === 'go') navigate(state.to, { replace: true });
  }, [state, navigate]);

  useEffect(() => {
    document.title = t.title;
  }, [t]);

  const chrome = builderChrome(theme) as CSSProperties;
  if (moved) return <Navigate to={moved} replace />;
  if (state.k === 'ready') {
    return <Editor key={id} id={id} ready={state} lang={lang} t={t} theme={theme} chrome={chrome} openPublish={params.get('publish') === '1'} onSignedOut={() => setState({ k: 'signedOut' })} reload={() => setTick((n) => n + 1)} />;
  }
  return (
    <div className={`cx ed ed-state scheme-${theme}`} data-theme={theme} style={chrome}>
      <header className="ed-top">
        <Link to={readToken() ? '/account#places' : '/builder'} className="ed-btn icon" aria-label={t.back}>
          <ArrowLeft size={20} aria-hidden="true" />
        </Link>
      </header>
      <main className="ed-center">
        {(state.k === 'loading' || state.k === 'go') && <div className="ed-card ed-wait" aria-busy="true" />}
        {state.k === 'signedOut' && (
          <SignIn
            title={t.signedOutTitle}
            lang={lang}
            theme={theme}
            onDone={async (token) => {
              if (token) writeToken(token);
              setTick((n) => n + 1);
            }}
          />
        )}
        {state.k === 'notYours' && <Card title={t.notYours} />}
        {state.k === 'notFound' && <Card title={t.notFound} />}
        {state.k === 'unavailable' && <Card title={t.unavailable} />}
        {state.k === 'network' && (
          <Card title={t.network}>
            <button type="button" className="ed-btn" onClick={() => setTick((n) => n + 1)}>
              <RotateCcw size={16} aria-hidden="true" />
              {t.retry}
            </button>
          </Card>
        )}
      </main>
    </div>
  );
}

function Card({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="ed-card">
      <h1 className="ed-h">{title}</h1>
      {children}
    </div>
  );
}

function SignIn({ title, lang, theme, onDone }: { title: string; lang: Lang; theme: Theme; onDone: (token?: string) => Promise<void> }) {
  const d = accountCopy[lang].doors;
  const [email, setEmail] = useState(false);
  return (
    <div className={`ed-signin scheme-${theme}`}>
      <h1 className="ed-h">{title}</h1>
      {email ? (
        <EmailDoor onDone={onDone} onSwitchToQr={() => setEmail(false)} useQrLabel={d.qr.useQr} />
      ) : (
        <QrDoor onDone={onDone} onSwitchToEmail={() => setEmail(true)} emailLabel={d.emailDoor} labels={d.qr} />
      )}
    </div>
  );
}

type Save = { k: 'idle' } | { k: 'saving' } | { k: 'saved'; at: number } | { k: 'local' } | { k: 'failed' };
type Sheet = null | 'publish' | 'conflict' | 'stale' | 'add' | 'arrange' | 'style' | 'inspect';
type Tab = InspectorTab;
type Guide = { x: number; y: number; w: number; h: number } | null;

const hhmm = (ms: number) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const typing = (el: EventTarget | null) => el instanceof HTMLElement && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));
const firstId = (el: Element | null) => el?.getAttribute('data-block')?.split(' ')[0] ?? null;

function Editor({ id, ready, lang, t, theme, chrome, openPublish, onSignedOut, reload }: {
  id: string; ready: Ready; lang: Lang; t: EditorCopy; theme: Theme; chrome: CSSProperties; openPublish: boolean; onSignedOut: () => void; reload: () => void;
}) {
  const navigate = useNavigate();
  const local = ready.local;
  const wide = useWide();
  const online = useOnline();
  const [h, setH] = useState<History>(() => history(ready.start));
  const page = h.now;
  const look: Look = isLook(page.look) ? page.look : 'shop';
  const [sel, setSel] = useState<string | null>(null);
  const [mode, setMode] = useState<'edit' | 'preview'>('edit');
  const [frame, setFrame] = useState<'phone' | 'desktop'>('desktop');
  const [tab, setTab] = useState<Tab>('content');
  const [left, setLeft] = useState<'blocks' | 'layers'>('blocks');
  const [proTab, setProTab] = useState<ProLeftTab>('layers');
  const [preview, setPreview] = useState<Preview>('W');
  const [view, setView] = useState<EditorView>(() => readView(storage()));
  const pro = view === 'pro';
  const pickView = (v: EditorView) => {
    setView(v);
    writeView(storage(), v);
  };
  const [pages, setPages] = useState<PageRow[] | null>(local ? null : pageRows([ready.venue], id));
  useEffect(() => {
    if (local || view !== 'pro') return;
    let alive = true;
    myVenues().then((vs) => alive && setPages(pageRows(vs.some((v) => v.id === id) ? vs : [ready.venue, ...vs], id)), () => undefined);
    return () => {
      alive = false;
    };
  }, [id, local, view, ready.venue]);
  const [sheet, setSheet] = useState<Sheet>(openPublish ? 'publish' : null);
  const [who, setWho] = useState<Who>(ready.who);
  const whoRef = useRef(ready.who);
  const tpl = useRef<EditPage | null>(local && !ready.edited ? ready.start : null);
  const fresh = useMemo(() => local && !!tpl.current && same(page, tpl.current), [local, page]);
  const handedOff = useRef(false);
  const made = useRef<Owned | null>(null);
  const [save, setSave] = useState<Save>(ready.restored ? { k: 'local' } : ready.savedAt ? { k: 'saved', at: ready.savedAt } : { k: 'idle' });
  const [live, setLive] = useState<EditPage | null>(ready.live);
  const [rev, setRev] = useState(ready.venue.rev);
  const [published, setPublished] = useState(ready.venue.published);
  const [scheduled, setScheduled] = useState<number | null>(ready.schedule);
  const [followers, setFollowers] = useState<number | null>(null);
  const [versions, setVersions] = useState<VersionRow[] | null>(null);
  const [dropped, setDropped] = useState<Dropped[]>(ready.dropped);
  const missed = dropped.find((d) => !d.seen) ?? null;
  const seenDrops = () => {
    setDropped((ds) => ds.map((d) => ({ ...d, seen: true })));
    markDropsSeen(id).catch(() => undefined);
  };

  const [mediaGen, setMediaGen] = useState(ready.media ? 1 : 0);
  const media = useRef({ at: ready.media ? Date.now() : 0, next: 0 });
  setMediaVenue(mediaGen ? id : null, mediaGen);
  useEffect(() => {
    setMediaVenue(mediaGen ? id : null, mediaGen);
    return () => setMediaVenue(null);
  }, [id, mediaGen]);
  useEffect(() => {
    if (local) return;
    let gone = false;
    let busy = false;
    const renew = () => {
      const m = media.current;
      const now = Date.now();
      if (busy || now < m.next || (m.at && now - m.at < MEDIA_RENEW_MS)) return;
      busy = true;
      const lapsed = !m.at || now - m.at >= MEDIA_SESSION_MS - 5 * 60_000;
      openMediaSession(id).then((ok) => {
        if (gone) return;
        if (!ok) { m.next = Date.now() + 30_000; return; }
        Object.assign(m, { at: Date.now(), next: 0 });
        if (lapsed) setMediaGen((g) => g + 1);
      }).finally(() => { busy = false; });
    };
    const online = () => {
      media.current.next = 0;
      renew();
    };
    renew();
    const iv = setInterval(renew, 30_000);
    document.addEventListener('visibilitychange', renew);
    window.addEventListener('online', online);
    window.addEventListener('focus', renew);
    window.addEventListener('pageshow', renew);
    return () => {
      gone = true;
      clearInterval(iv);
      document.removeEventListener('visibilitychange', renew);
      window.removeEventListener('online', online);
      window.removeEventListener('focus', renew);
      window.removeEventListener('pageshow', renew);
    };
  }, [id, local]);
  const [pub, setPub] = useState<PubState>({ k: 'idle' });
  const [touched, setTouched] = useState<Set<string>>(new Set());
  const [focus, setFocus] = useState<{ block: string; field?: string } | null>(null);
  const [said, setSaid] = useState('');
  const [twin, setTwin] = useState(false);
  const [zoom, setZoom] = useState<number | null>(null);
  const [more, setMore] = useState(false);

  const baseRef = useRef(ready.base);
  const savedRef = useRef<EditPage | null>(ready.restored ? null : ready.start);
  const pageRef = useRef(page);
  pageRef.current = page;
  const inflight = useRef(false);
  const catching = useRef(false);
  const publishing = useRef(false);
  const again = useRef(false);
  const fails = useRef(0);

  const sheetRef = useRef(sheet);
  sheetRef.current = sheet;
  useEffect(() => hold(() => pageRef.current !== savedRef.current || inflight.current || publishing.current || catching.current || sheetRef.current === 'publish' || sheetRef.current === 'conflict'), []);

  const act = useCallback((a: Action) => setH((cur) => apply(cur, a, REGISTRY)), []);

  const keepLocal = useCallback((p: EditPage) => {
    if (handedOff.current) return;
    const edited = !(tpl.current && same(p, tpl.current));
    writeLocal(storage(), { page: p, who: whoRef.current, edited });
    savedRef.current = p;
    setSave(edited ? { k: 'local' } : { k: 'idle' });
  }, []);

  const flush = useCallback(async (): Promise<'ok' | 'conflict' | 'failed' | 'deferred'> => {
    if (local) {
      keepLocal(pageRef.current);
      return 'ok';
    }
    if (inflight.current || publishing.current || catching.current) {
      again.current = true;
      return 'deferred';
    }
    const p = pageRef.current;
    if (p === savedRef.current) {
      clearStash(id);
      setSave((s) => (s.k === 'local' || s.k === 'failed' ? { k: 'idle' } : s));
      return 'ok';
    }
    writeStash(id, p, baseRef.current);
    if (!draftShape(p, REGISTRY)) {
      setSave({ k: 'local' });
      return 'failed';
    }
    inflight.current = true;
    setSave({ k: 'saving' });
    try {
      const d = await saveDraft(id, p as V2Page, baseRef.current);
      baseRef.current = d.updated_ms ?? 0;
      savedRef.current = p;
      fails.current = 0;
      if (pageRef.current === p) clearStash(id);
      setSave({ k: 'saved', at: Date.now() });
      return 'ok';
    } catch (e) {
      const conflict = e instanceof VenueRefusal && e.status === 409;
      if (e instanceof VenueRefusal && e.status === 401) onSignedOut();
      else if (conflict) setSheet('conflict');
      else fails.current += 1;
      setSave({ k: 'failed' });
      return conflict ? 'conflict' : 'failed';
    } finally {
      inflight.current = false;
      if (again.current) {
        again.current = false;
        void flush();
      }
    }
  }, [id, local, keepLocal, onSignedOut]);

  useEffect(() => {
    setPub((s) => (s.k === 'done' || s.k === 'refused' ? { k: 'idle' } : s));
    if (page === savedRef.current) {
      void flush();
      return;
    }
    const tm = setTimeout(flush, 1500);
    return () => clearTimeout(tm);
  }, [page, flush]);

  useEffect(() => {
    if (save.k !== 'failed' || sheet === 'conflict' || !online) return;
    const tm = setTimeout(flush, Math.min(30000, 2000 * 2 ** Math.min(fails.current, 4)));
    return () => clearTimeout(tm);
  }, [save, sheet, online, flush]);

  useEffect(() => {
    if (online) void flush();
  }, [online, flush]);

  useEffect(() => {
    const away = () => {
      const p = pageRef.current;
      if (local) return keepLocal(p);
      if (p === savedRef.current) return;
      writeStash(id, p, baseRef.current);
      if (!inflight.current && !publishing.current && draftShape(p, REGISTRY) && fitsKeepalive(p, baseRef.current)) void saveDraft(id, p as V2Page, baseRef.current, true).catch(() => undefined);
    };
    window.addEventListener('pagehide', away);
    return () => {
      window.removeEventListener('pagehide', away);
      away();
    };
  }, [id, local, keepLocal]);

  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const ch = new BroadcastChannel(`np-editor-${id}`);
    ch.onmessage = (e) => {
      if (e.data === 'hello') ch.postMessage('here');
      if (e.data === 'hello' || e.data === 'here') setTwin(true);
    };
    ch.postMessage('hello');
    return () => ch.close();
  }, [id]);

  const cs = useMemo(() => checks(page, REGISTRY), [page]);
  const verdict = useMemo(() => draftVerdict(page, REGISTRY), [page]);
  const selected = page.blocks.find((b) => b.id === sel) ?? null;

  useEffect(() => {
    if (sel && !page.blocks.some((b) => b.id === sel)) setSel(null);
  }, [page, sel]);

  const openVersions = useCallback(() => {
    if (local) return;
    listVersions(id).then((r) => {
      setVersions(r.versions);
      const got = r.dropped;
      if (got) setDropped(keepSeen(got));
    }, () => setVersions([]));
  }, [id, local]);

  useEffect(() => {
    if (view === 'pro' && !local && versions === null) openVersions();
  }, [view, local, versions, openVersions]);
  const chip = useMemo(() => versionChip({ local, published, versions, base: live, page }), [local, published, versions, live, page]);

  useEffect(() => {
    if (sheet !== 'publish' || local) return;
    openVersions();
    getAudience(id).then((a) => setFollowers(a.followers), () => setFollowers(null));
  }, [sheet, openVersions, id]);

  const select = useCallback((bid: string | null, opts: { field?: string; tab?: Tab } = {}) => {
    setSel(bid);
    setH(seal);
    if (opts.tab) setTab(opts.tab);
    if (bid && opts.field) setFocus({ block: bid, field: opts.field });
  }, []);

  useEffect(() => {
    if (!focus) return;
    const tm = setTimeout(() => {
      const el = focus.field ? document.getElementById(fieldDom(focus.block, focus.field)) : null;
      el?.focus();
      el?.scrollIntoView({ block: 'center' });
      setFocus(null);
    }, 50);
    return () => clearTimeout(tm);
  }, [focus, sel, tab, sheet]);

  function addKind(kind: string, stay = false): string | null {
    if (!canAdd(page, kind, REGISTRY)) return null;
    const next = apply(h, { t: 'add', kind, after: sel, now: new Date() }, REGISTRY);
    if (next === h) return null;
    const before = new Set(page.blocks.map((b) => b.id));
    const made = next.now.blocks.filter((b) => !before.has(b.id));
    setH(next);
    const target = made.find((b) => b.kind === kind) ?? made[0];
    if (target && !stay) {
      setSel(target.id);
      setTab('content');
      if (!wide) setSheet('inspect');
    }
    return target?.id ?? null;
  }

  function fix(f: Fix) {
    if (f.add) {
      const existing = page.blocks.find((b) => b.kind === f.add);
      if (existing) {
        if (existing.hidden) act({ t: 'hide', id: existing.id, hidden: false });
        select(existing.id, { tab: 'content' });
        setSheet(wide ? null : 'inspect');
      } else {
        addKind(f.add);
        if (wide) setSheet(null);
      }
      return;
    }
    if (f.block) {
      const b = page.blocks.find((x) => x.id === f.block);
      if (b?.hidden) act({ t: 'hide', id: b.id, hidden: false });
      setTouched(new Set(['*']));
      select(f.block, { tab: 'content', field: f.field });
      setSheet(wide ? null : 'inspect');
    }
  }

  useEffect(() => {
    if (scheduled === null) return;
    let gone = false;
    let tm: ReturnType<typeof setTimeout> | undefined;
    const arm = (ms: number) => {
      clearTimeout(tm);
      tm = setTimeout(check, Math.min(Math.max(ms, 0), 2 ** 31 - 1));
    };
    const check = async () => {
      if (gone || catching.current) return;
      if (Date.now() < scheduled) return arm(scheduled - Date.now() + 1500);
      catching.current = true;
      try {
        while (inflight.current) await new Promise((r) => setTimeout(r, 50));
        const d = await getDraft(id);
        const v = await getPublishedPage(id);
        if (gone) return;
        if (d.dropped) {
          const got = d.dropped;
          setDropped(keepSeen(got));
        }
        if (d.schedule) {
          if (d.schedule.at_ms !== scheduled) setScheduled(d.schedule.at_ms);
          else arm(5000);
          return;
        }
        setScheduled(null);
        if (!v.published || v.rev === rev) return;
        const p = v.page as EditPage | null;
        setRev(v.rev);
        setLive(p?.v === 2 ? p : null);
        setPublished(true);
      } catch {
        arm(15000);
      } finally {
        catching.current = false;
        if (again.current) {
          again.current = false;
          void flush();
        }
      }
    };
    arm(scheduled - Date.now() + 1500);
    const seen = () => document.visibilityState === 'visible' && void check();
    document.addEventListener('visibilitychange', seen);
    window.addEventListener('online', seen);
    return () => {
      gone = true;
      clearTimeout(tm);
      document.removeEventListener('visibilitychange', seen);
      window.removeEventListener('online', seen);
    };
  }, [scheduled, id, rev, flush]);

  function pickTemplate(w: Who) {
    const tp = starterTemplate(w, lang);
    tpl.current = tp;
    whoRef.current = w;
    setWho(w);
    setSel(null);
    act({ t: 'replace', page: tp });
  }

  function askPublish() {
    if (publishOpen) return setSheet(null);
    const step = publishStep(local, !!readToken());
    if (step === 'upload') return void upload();
    setSheet('publish');
  }

  async function upload() {
    if (pub.k === 'busy') return;
    const p = pageRef.current;
    const name = pageName(p);
    if (!name) {
      const nb = p.blocks.find((b) => b.kind === 'id.name');
      setSheet(null);
      return fix(nb ? { block: nb.id, field: 'name' } : { add: 'id.name' });
    }
    setPub({ k: 'busy' });
    try {
      const venue = made.current ?? (await createVenue(name));
      made.current = venue;
      await saveDraft(venue.id, p as V2Page, 0);
      handedOff.current = true;
      clearLocal(storage());
      navigate(`/build/edit/${encodeURIComponent(venue.id)}?publish=1`, { replace: true });
    } catch (e) {
      setSheet('publish');
      if (e instanceof VenueRefusal && e.status === 401) return setPub({ k: 'idle' });
      if (e instanceof VenueRefusal && (e.messageEn || e.refused)) return setPub({ k: 'refused', text: (lang === 'uk' ? e.messageUk : e.messageEn) ?? refusalLabel(e.refused!, lang) });
      setPub({ k: 'network' });
    }
  }

  async function publish() {
    if (pub.k === 'busy') return;
    setPub({ k: 'busy' });
    publishing.current = true;
    while (inflight.current) await new Promise((r) => setTimeout(r, 50));
    const p = pageRef.current;
    try {
      const v = await publishV2(id, p as V2Page, rev);
      setRev(v.rev);
      setLive(p);
      setPublished(true);
      setScheduled(null);
      baseRef.current = 0;
      savedRef.current = p;
      if (pageRef.current === p) clearStash(id);
      setSave({ k: 'saved', at: Date.now() });
      setPub({ k: 'done' });
      openVersions();
    } catch (e) {
      refused(e, p);
    } finally {
      publishing.current = false;
      if (again.current) {
        again.current = false;
        void flush();
      }
    }
  }

  function refused(e: unknown, p: EditPage) {
    if (e instanceof VenueRefusal && e.status === 401) return onSignedOut();
    if (e instanceof VenueRefusal && e.status === 409) {
      setPub({ k: 'idle' });
      setSheet('stale');
      return;
    }
    if (e instanceof VenueRefusal && e.status === 422) {
      const text = (lang === 'uk' ? e.messageUk : e.messageEn) ?? (e.refused ? refusalLabel(e.refused, lang) : t.notSaved);
      setPub({ k: 'refused', text });
      const f = e.at ? fixFor(p, e.at) : {};
      if (f.block) {
        setTouched(new Set(['*']));
        select(f.block, { tab: 'content', field: f.field });
      }
      return;
    }
    setPub({ k: 'network' });
  }

  async function schedule(at: number) {
    if (pub.k === 'busy') return;
    setPub({ k: 'busy' });
    let p = pageRef.current;
    if (baseRef.current === 0) savedRef.current = null;
    try {
      for (let tries = 0; ; tries++) {
        while (inflight.current) await new Promise((r) => setTimeout(r, 50));
        p = pageRef.current;
        if (p !== savedRef.current) {
          if (tries > 2) return setPub({ k: 'network' });
          if ((await flush()) === 'conflict') return setPub({ k: 'idle' });
          continue;
        }
        try {
          const r = await schedulePublish(id, at, baseRef.current);
          setScheduled(r.schedule.at_ms);
          setPub({ k: 'idle' });
          return;
        } catch (e) {
          if (!(e instanceof VenueRefusal && e.status === 409 && e.refused === 'stale_draft' && tries < 2)) throw e;
        }
      }
    } catch (e) {
      refused(e, p);
    }
  }

  async function unschedule() {
    try {
      await cancelSchedule(id);
      setScheduled(null);
    } catch (e) {
      refused(e, pageRef.current);
    }
  }

  async function unpublish() {
    setPub({ k: 'busy' });
    try {
      const v = await unpublishVenue(id);
      setRev(v.rev);
      setPublished(false);
      setLive(null);
      setScheduled(null);
      setPub({ k: 'idle' });
    } catch (e) {
      refused(e, pageRef.current);
    }
  }

  async function restore(n: number) {
    try {
      const v = await getVersion(id, n);
      act({ t: 'replace', page: v.page as unknown as EditPage });
      setSel(null);
      setSheet(null);
    } catch {
      setPub({ k: 'network' });
    }
  }

  async function resolve(theirs: boolean) {
    try {
      const d = await getDraft(id);
      baseRef.current = d.updated_ms ?? 0;
      if (theirs) {
        const p = (d.page as EditPage | null) ?? (await getPublishedPage(id).then((v) => { const lp = v.page as unknown as EditPage | null; return v.published && lp?.v === 2 ? lp : null; })) ?? live ?? page;
        savedRef.current = p;
        act({ t: 'replace', page: p });
        clearStash(id);
        setSave({ k: 'saved', at: Date.now() });
      } else {
        savedRef.current = null;
        void flush();
      }
      setSheet(null);
    } catch {
      setSave({ k: 'failed' });
    }
  }

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (e.key === 'Escape') {
        if (sheet === 'conflict' || sheet === 'stale') return;
        if (sheet) setSheet(null);
        else if (!typing(e.target)) setSel(null);
        return;
      }
      if (typing(e.target)) return;
      if (mod && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        setH((cur) => (e.shiftKey ? redo(cur) : undo(cur)));
      } else if (mod && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        setH(redo);
      } else if (e.altKey && sel && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
        e.preventDefault();
        nudge(sel, e.key === 'ArrowUp' ? -1 : 1);
      }
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  });

  function announce(bid: string, blocks = pageRef.current.blocks) {
    const i = blocks.findIndex((b) => b.id === bid);
    if (i >= 0) setSaid(t.moved(i + 1, blocks.length));
  }

  function nudge(bid: string, by: -1 | 1) {
    const next = apply(h, { t: 'nudge', id: bid, by });
    if (next === h) return;
    setH(next);
    announce(bid, next.now.blocks);
  }

  function move(bid: string, to: string, place: 'before' | 'after') {
    const next = apply(h, { t: 'move', id: bid, to, place });
    if (next === h) return;
    setH(next);
    announce(bid, next.now.blocks);
  }

  const changed = published && live !== null && JSON.stringify(canon(live)) !== JSON.stringify(canon(page));
  const state = local ? (fresh ? t.template : t.onlyHere) : !published ? t.draft : live && !isPublic(live.on) ? (changed ? `${t.notPublic} · ${t.notLive}` : t.notPublic) : changed ? t.notLive : t.live;
  const status = local ? state
    : save.k === 'saving' ? `${state} · ${t.saving}`
      : save.k === 'saved' ? `${state} · ${t.savedShort(hhmm(save.at))}`
        : save.k === 'local' ? t.onlyHere
          : save.k === 'failed' ? t.notSaved
            : state;
  const staged = page.blocks.some((b) => !b.hidden && b.kind === 'deal.drop' && Array.isArray(b.data.stages) && (b.data.stages as { circle?: string }[]).some((st) => st.circle && st.circle !== 'everyone'));
  const name = String(page.blocks.find((b) => b.kind === 'id.name')?.data.name ?? ready.venue.name);
  const canUndo = h.past.length > 0;
  const canRedo = h.future.length > 0;
  const showFrame = wide ? frame : 'phone';
  const publishOpen = sheet === 'publish';
  const exit = local && !readToken() ? '/builder' : '/account#places';
  const savedText = save.k === 'saving' ? t.saving : save.k === 'saved' ? t.savedAt(hhmm(save.at)) : save.k === 'failed' ? t.notSaved : save.k === 'local' ? t.onlyHere : state;
  const error = verdict.ok || verdict.fix.block !== sel || !verdict.fix.field ? undefined : { field: verdict.fix.field, refused: verdict.refused };

  const inspector = selected ? (view === 'pro' ? (
    <ProInspector
      block={selected} page={page} look={look} lang={lang} t={t} tab={tab} setTab={setTab} act={act} venue={local ? '' : id}
      add={addKind} error={error} touched={touched} touch={(k) => setTouched((s) => new Set(s).add(k))}
      onClose={() => (wide ? setSel(null) : setSheet(null))}
      go={(bid) => select(bid, { tab: 'content' })}
    />
  ) : (
    <Inspector
      block={selected} page={page} look={look} lang={lang} t={t} tab={tab} setTab={setTab} act={act} venue={local ? '' : id}
      add={addKind} error={error} touched={touched} touch={(k) => setTouched((s) => new Set(s).add(k))}
      onClose={() => (wide ? setSel(null) : setSheet(null))}
    />
  )) : (
    <div className="ed-insp">
      <header className="ed-insp-head">
        <h2 className="ed-h">{t.page}</h2>
      </header>
      <div className="ed-insp-body">
        <PagePanel page={page} look={look} lang={lang} t={t} act={act} published={published && !!live && isPublic(live.on)} venue={id} />
      </div>
    </div>
  );

  const publishSheet = local ? (
    <LocalPublish lang={lang} t={t} pub={pub} onClose={() => setSheet(null)} onSignedIn={(token) => { if (token) writeToken(token); void upload(); }} />
  ) : (
    <PublishSheet
      page={page} base={live} cs={cs} versions={versions} published={published} venue={id} lang={lang} t={t} online={online} pub={pub}
      act={act} onFix={fix} onPublish={publish} onRestore={restore} onClose={() => setSheet(null)}
      scheduled={scheduled} onSchedule={schedule} onCancelSchedule={unschedule} followers={followers} onUnpublish={unpublish} dropped={dropped}
    />
  );

  return (
    <div className={`cx ed scheme-${theme}`} data-theme={theme} data-wide={wide ? '1' : '0'} style={chrome}>
      <header className="ed-top">
        {wide ? (
          <Link to={exit} className="ed-btn icon" aria-label={t.back}><ArrowLeft size={20} aria-hidden="true" /></Link>
        ) : (
          <Link to={exit} className="ed-btn icon" aria-label={t.close}><X size={20} aria-hidden="true" /></Link>
        )}
        {wide && <span className="ed-avatar" aria-hidden="true">{initials(name)}</span>}
        <div className="ed-title">
          <b>{wide ? name || t.editing : t.editing}</b>
          <span className={`ed-hint ${save.k === 'failed' ? 'ed-bad' : ''}`} role="status">
            {status}
            {save.k === 'failed' && sheet !== 'conflict' && (
              <button type="button" className="ed-link inline" onClick={() => void flush()}>{t.retry}</button>
            )}
          </span>
        </div>
        {wide && view === 'pro' && <ProVersion chip={chip} lang={lang} t={t} open={publishOpen} onOpen={() => setSheet(publishOpen ? null : 'publish')} />}
        {wide && <ViewSwitch view={view} lang={lang} onPick={pickView} />}
        {wide && (
          <div className="ed-seg" role="radiogroup" aria-label={t.preview}>
            <button type="button" role="radio" aria-checked={frame === 'phone'} className={frame === 'phone' ? 'on' : ''} onClick={() => setFrame('phone')}>
              <Smartphone size={16} aria-hidden="true" />{t.phone}
            </button>
            <button type="button" role="radio" aria-checked={frame === 'desktop'} className={frame === 'desktop' ? 'on' : ''} onClick={() => setFrame('desktop')}>
              <Monitor size={16} aria-hidden="true" />{t.desktop}
            </button>
          </div>
        )}
        <div className="ed-acts">
          {wide && staged && <span className="ed-note">{t.closeFirst}</span>}
          <button type="button" className="ed-btn icon" aria-label={t.undo} disabled={!canUndo} onClick={() => setH(undo)}><Undo2 size={18} aria-hidden="true" /></button>
          {wide && <button type="button" className="ed-btn icon" aria-label={t.redo} disabled={!canRedo} onClick={() => setH(redo)}><Redo2 size={18} aria-hidden="true" /></button>}
          {wide && (
            <>
              <span className="ed-sep" aria-hidden="true" />
              {!local && (
                <button type="button" className="ed-btn" onClick={() => setSheet(publishOpen ? null : 'publish')}>
                  <HistoryIcon size={16} aria-hidden="true" />{t.history}
                </button>
              )}
              <button type="button" className="ed-btn" aria-pressed={mode === 'preview'} onClick={() => setMode(mode === 'edit' ? 'preview' : 'edit')}>
                {mode === 'edit' ? <Eye size={16} aria-hidden="true" /> : <Pencil size={16} aria-hidden="true" />}
                {mode === 'edit' ? t.preview : t.edit}
              </button>
            </>
          )}
          <button type="button" className={`ed-btn ${publishOpen ? '' : mode === 'preview' ? 'ink-line' : 'primary'}`} aria-expanded={publishOpen} disabled={pub.k === 'busy' && local} onClick={askPublish}>
            {pub.k === 'busy' && local ? t.moving : t.publish}
          </button>
        </div>
      </header>
      {twin && <p className="ed-banner">{t.otherTab}</p>}
      {fresh && (
        <div className="ed-banner tpl">
          <b>{t.template}</b>
          <StartFrom who={who} t={t} onPick={pickTemplate} />
        </div>
      )}
      <div className="ed-body">
        {wide && mode === 'edit' && (view === 'pro' ? (
          <ProLeft
            page={page} sel={sel} lang={lang} t={t} act={act} pages={pages} tab={proTab} setTab={setProTab} onPick={(k) => { addKind(k); }}
            onSelect={(b, field) => select(b, field ? { field, tab: 'content' } : {})}
            library={<Library page={page} lang={lang} t={t} onAdd={addKind} look={look} theme={theme} />}
          />
        ) : (
          <aside className="ed-left" aria-label={t.blocks}>
            <div className="ed-tabs" role="tablist">
              <button type="button" role="tab" aria-selected={left === 'blocks'} className={left === 'blocks' ? 'on' : ''} onClick={() => setLeft('blocks')}>{t.blocks}</button>
              <button type="button" role="tab" aria-selected={left === 'layers'} className={left === 'layers' ? 'on' : ''} onClick={() => setLeft('layers')}>{t.layers}</button>
            </div>
            <div className="ed-scroll">
              {left === 'blocks' ? (
                <Library page={page} lang={lang} t={t} onAdd={addKind} look={look} theme={theme} />
              ) : (
                <Layers page={page} sel={sel} lang={lang} t={t} onSelect={(b) => select(b)} act={act} />
              )}
            </div>
          </aside>
        ))}
        <Canvas
          page={page} look={look} theme={theme} lang={lang} t={t} mode={mode} frame={showFrame} wide={wide} sel={sel}
          onSelect={(b) => select(b)}
          onMove={move} onNudge={nudge} act={act} onEdit={() => setSheet('inspect')}
          onAdd={() => (!wide ? setSheet('add') : view === 'pro' ? setProTab('add') : setLeft('blocks'))}
          draw={pro ? pageFor(page, preview) : page}
          cap={pro && wide ? <PreviewSwitch value={preview} page={page} lang={lang} onPick={setPreview} /> : null}
          tools={pro ? (b) => <ProTools block={b} page={page} lang={lang} t={t} act={act} onNudge={nudge} onAdd={() => (wide ? setProTab('add') : setSheet('add'))} /> : undefined}
          zoom={zoom} setZoom={setZoom} more={more} setMore={setMore}
          onStyle={(bid) => {
            if (wide) {
              select(bid, { tab: 'content' });
              setTimeout(() => document.getElementById(`ed-tone-${bid}`)?.querySelector<HTMLElement>('[aria-checked=true]')?.focus(), 60);
            } else setSheet('style');
          }}
        />
        {wide && mode === 'edit' && <aside className="ed-right" aria-label={t.content}>{inspector}</aside>}
        {wide && view === 'pro' && (
          <ProStatus cs={cs} saved={savedText} bad={save.k === 'failed'} lang={lang} center={mode === 'preview'} onOpen={() => (local ? askPublish() : setSheet('publish'))} />
        )}
        {wide && publishOpen && (
          <Pop label={t.publish} onClose={() => setSheet(null)}>
            {publishSheet}
          </Pop>
        )}
      </div>
      {!wide && (
        <nav className="ed-bottom" aria-label={t.editing}>
          <button type="button" className={sheet === 'add' ? 'on' : ''} onClick={() => setSheet(sheet === 'add' ? null : 'add')}><Plus size={22} aria-hidden="true" /><span>{t.add}</span></button>
          <button type="button" className={sheet === 'arrange' ? 'on' : ''} onClick={() => setSheet(sheet === 'arrange' ? null : 'arrange')}><Move size={22} aria-hidden="true" /><span>{t.arrange}</span></button>
          <button type="button" className={sheet === 'style' ? 'on' : ''} onClick={() => setSheet(sheet === 'style' ? null : 'style')}><Palette size={22} aria-hidden="true" /><span>{t.style}</span></button>
          <button type="button" className={mode === 'preview' ? 'on' : ''} aria-pressed={mode === 'preview'} onClick={() => setMode(mode === 'edit' ? 'preview' : 'edit')}>
            {mode === 'edit' ? <Eye size={22} aria-hidden="true" /> : <Pencil size={22} aria-hidden="true" />}<span>{mode === 'edit' ? t.preview : t.edit}</span>
          </button>
        </nav>
      )}
      {!wide && sheet && sheet !== 'conflict' && sheet !== 'stale' && (
        <BottomSheet label={sheetLabel(sheet, t)} close={t.close} bare={sheet === 'inspect' || sheet === 'publish'} onClose={() => setSheet(null)}>
          {sheet === 'add' && (
            <>
              {pro && <Suggested page={page} lang={lang} onPick={(k) => { addKind(k); }} />}
              <Library page={page} lang={lang} t={t} onAdd={addKind} look={look} theme={theme} />
            </>
          )}
          {sheet === 'arrange' && (view === 'pro'
            ? (
              <>
                <ProLayers page={page} sel={sel} lang={lang} t={t} onSelect={(b, field) => { select(b, field ? { field, tab: 'content' } : {}); setSheet(field ? 'inspect' : null); }} act={act} />
                <Pages rows={pages} lang={lang} t={t} />
              </>
            )
            : <Layers page={page} sel={sel} lang={lang} t={t} onSelect={(b) => { select(b); setSheet(null); }} act={act} />)}
          {sheet === 'style' && (
            <div className="ed-form">
              <div className="ed-field">
                <span className="ed-label">{proCopy[lang].view}</span>
                <ViewSwitch view={view} lang={lang} onPick={pickView} />
              </div>
              <div className="ed-field">
                <span className="ed-label">{t.pageLook}</span>
                <LookPicker look={look} lang={lang} t={t} onPick={(l) => act({ t: 'look', look: l })} />
              </div>
              {selected && sizable(selected.kind, look) && (
                <div className="ed-field">
                  <span className="ed-label">{t.size}</span>
                  <SizeSwatches block={selected} look={look} lang={lang} t={t} act={act} />
                </div>
              )}
              {selected && toned(selected.kind) && (
                <div className="ed-field">
                  <span className="ed-label">{inspectorCopy[lang].cardTone}</span>
                  <ToneSwatches block={selected} lang={lang} act={act} />
                </div>
              )}
            </div>
          )}
          {sheet === 'inspect' && inspector}
          {sheet === 'publish' && publishSheet}
        </BottomSheet>
      )}
      {sheet === 'conflict' && (
        <Dialog title={t.conflictTitle}>
          <button type="button" className="ed-btn" onClick={() => resolve(true)}>{t.loadTheirs}</button>
          <button type="button" className="ed-btn ink" onClick={() => resolve(false)}>{t.keepMine}</button>
        </Dialog>
      )}
      {sheet === 'stale' && (
        <Dialog title={t.staleTitle}>
          <button type="button" className="ed-btn ink" onClick={() => { writeStash(id, page, baseRef.current); reload(); }}>{t.reload}</button>
        </Dialog>
      )}
      {missed && (
        <div className="ed-toast" role="status">
          <Clock size={16} aria-hidden="true" />
          <span className="ed-grow">
            {t.dropToast(new Date(missed.at_ms).toLocaleString(lang === 'uk' ? 'uk-UA' : 'en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }))}
            <span className="ed-hint"> · {dropReason(missed.reason, t)}</span>
          </span>
          <button type="button" className="ed-link" onClick={seenDrops}>{t.ok}</button>
        </div>
      )}
      <p className="ed-sr" aria-live="polite">{said}</p>
    </div>
  );
}

const STARTS: Who[] = ['me', 'group', 'place'];

function StartFrom({ who, t, onPick }: { who: Who; t: EditorCopy; onPick: (w: Who) => void }) {
  return (
    <div className="ed-seg ed-starts" role="radiogroup" aria-label={t.template}>
      {STARTS.map((w) => (
        <button key={w} type="button" role="radio" aria-checked={who === w} className={who === w ? 'on' : ''} onClick={() => who !== w && onPick(w)}>
          {t.starts[w]}
        </button>
      ))}
    </div>
  );
}

function LocalPublish({ lang, t, pub, onClose, onSignedIn }: { lang: Lang; t: EditorCopy; pub: PubState; onClose: () => void; onSignedIn: (token?: string) => void }) {
  const d = accountCopy[lang].doors;
  const [email, setEmail] = useState(false);
  const done = async (token?: string) => onSignedIn(token);
  return (
    <div className="ed-insp ed-local-pub">
      <header className="ed-insp-head">
        <h2 className="ed-h">{t.signInToPublish}</h2>
        <button type="button" className="ed-btn icon" aria-label={t.close} onClick={onClose}><X size={18} aria-hidden="true" /></button>
      </header>
      <div className="ed-insp-body">
        {pub.k === 'busy' ? (
          <p className="ed-hint" role="status">{t.moving}</p>
        ) : email ? (
          <EmailDoor onDone={done} onSwitchToQr={() => setEmail(false)} useQrLabel={d.qr.useQr} />
        ) : (
          <QrDoor onDone={done} onSwitchToEmail={() => setEmail(true)} emailLabel={d.emailDoor} labels={d.qr} />
        )}
        {pub.k === 'refused' && <p className="ed-err" role="alert">{pub.text}</p>}
        {pub.k === 'network' && <p className="ed-err" role="alert">{t.network}</p>}
      </div>
    </div>
  );
}

function sheetLabel(s: Sheet, t: EditorCopy): string {
  return s === 'add' ? t.blocks : s === 'arrange' ? t.layers : s === 'style' ? t.style : s === 'publish' ? t.publish : t.content;
}

function BottomSheet({ label, close, bare, onClose, children }: { label: string; close: string; bare: boolean; onClose: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useTrap(ref, true, onClose, label, 'root');
  return (
    <div className="ed-sheet-wrap" onClick={onClose}>
      <div className="ed-sheet" role="dialog" aria-modal="true" aria-label={label} ref={ref} tabIndex={-1} onClick={(e) => e.stopPropagation()}>
        {!bare && (
          <div className="ed-sheet-head">
            <b>{label}</b>
            <button type="button" className="ed-btn icon" aria-label={close} onClick={onClose}><X size={18} aria-hidden="true" /></button>
          </div>
        )}
        <div className={`ed-sheet-body ${bare ? 'bare' : ''}`}>{children}</div>
      </div>
    </div>
  );
}

function Dialog({ title, children }: { title: string; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useTrap(ref, true);
  return (
    <div className="ed-sheet-wrap center">
      <div className="ed-card" role="alertdialog" aria-modal="true" aria-label={title} ref={ref} tabIndex={-1}>
        <h2 className="ed-h">{title}</h2>
        <div className="ed-row g8">{children}</div>
      </div>
    </div>
  );
}

const ZOOMS = [0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1, 1.25, 1.5];

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return (parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : (parts[0] ?? '').slice(0, 2)).toUpperCase();
}

function Pop({ label, onClose, children }: { label: string; onClose: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useTrap(ref, true, onClose, undefined, 'root');
  return (
    <div className="ed-pop" role="dialog" aria-label={label} ref={ref} tabIndex={-1}>
      {children}
    </div>
  );
}

function MoreMenu({ label, onClose, children }: { label: string; onClose: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useTrap(ref, true, onClose);
  useEffect(() => {
    const out = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node) && !(e.target as Element).closest('.ed-more-btn')) onClose();
    };
    document.addEventListener('pointerdown', out);
    return () => document.removeEventListener('pointerdown', out);
  }, [onClose]);
  return (
    <div className="ed-menu" role="menu" aria-label={label} ref={ref} tabIndex={-1}>
      {children}
    </div>
  );
}

function Canvas({ page, look, theme, lang, t, mode, frame, wide, sel, onSelect, onMove, onNudge, act, onEdit, onAdd, zoom, setZoom, more, setMore, onStyle, draw = page, cap = null, tools }: {
  page: EditPage; look: Look; theme: Theme; lang: Lang; t: EditorCopy; mode: 'edit' | 'preview'; frame: 'phone' | 'desktop'; wide: boolean; sel: string | null;
  onSelect: (id: string | null) => void; onMove: (id: string, to: string, place: 'before' | 'after') => void; onNudge: (id: string, by: -1 | 1) => void;
  act: (a: Action) => void; onEdit: () => void; onAdd: () => void;
  zoom: number | null; setZoom: (z: number | null) => void; more: boolean; setMore: (v: boolean) => void; onStyle: (id: string) => void;
  draw?: EditPage; cap?: React.ReactNode; tools?: (block: EditPage["blocks"][number]) => React.ReactNode;
}) {
  const ground = useRef<HTMLDivElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  const [avail, setAvail] = useState(0);
  const [innerH, setInnerH] = useState(0);
  const [box, setBox] = useState<{ x: number; y: number; w: number; h: number } | null>(null);
  const [guide, setGuide] = useState<Guide>(null);
  const drag = useRef<{ id: string; el: HTMLElement; x: number; y: number; to: string | null; place: 'before' | 'after' } | null>(null);

  const width = !wide ? Math.max(320, avail - 24) : frame === 'phone' ? 390 : 1232;
  const room = Math.max(0, avail - (wide ? 64 : 0));
  const fit = !wide ? 1 : room && width > room ? room / width : 1;
  const scale = !wide ? 1 : zoom ?? fit;
  const stepZoom = (dir: 1 | -1) => {
    const cur = Math.round(scale * 100) / 100;
    const next = dir > 0 ? ZOOMS.find((z) => z > cur + 0.001) : [...ZOOMS].reverse().find((z) => z < cur - 0.001);
    if (next) setZoom(next);
  };

  useLayoutEffect(() => {
    const g = ground.current;
    const i = inner.current;
    if (!g || !i) return;
    const ro = new ResizeObserver(() => {
      setAvail(g.clientWidth);
      setInnerH(i.offsetHeight);
    });
    ro.observe(g);
    ro.observe(i);
    setAvail(g.clientWidth);
    setInnerH(i.offsetHeight);
    return () => ro.disconnect();
  }, []);

  const measure = useCallback(() => {
    const s = stage.current;
    const i = inner.current;
    if (!s || !i || !sel || mode !== 'edit') return setBox(null);
    const el = i.querySelector<HTMLElement>(`[data-block~="${CSS.escape(sel)}"]`);
    if (!el) return setBox(null);
    const r = el.getBoundingClientRect();
    const o = s.getBoundingClientRect();
    setBox({ x: r.left - o.left, y: r.top - o.top, w: r.width, h: r.height });
  }, [sel, mode]);

  useLayoutEffect(() => {
    measure();
  }, [measure, page, scale, innerH, width]);

  useEffect(() => {
    if (!sel || mode !== 'edit') return;
    const el = inner.current?.querySelector<HTMLElement>(`[data-block~="${CSS.escape(sel)}"]`);
    if (!el || !ground.current) return;
    const r = el.getBoundingClientRect();
    const g = ground.current.getBoundingClientRect();
    if (!wide) {
      if (r.top < g.top || r.top > g.bottom - 280) ground.current.scrollBy({ top: r.top - g.top - 56 });
    } else if (r.top < g.top || r.top > g.bottom - 80) el.scrollIntoView({ block: 'center', behavior: 'auto' });
  }, [sel, mode, wide]);

  function click(e: React.MouseEvent) {
    const a = (e.target as Element).closest('a');
    if (mode === 'preview') {
      if (a && !a.getAttribute('href')?.startsWith('#')) e.preventDefault();
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    const hit = firstId((e.target as Element).closest('[data-block]'));
    if (!wide && hit && hit === sel) onEdit();
    else onSelect(hit);
  }

  function down(e: RPointerEvent) {
    if (!sel || e.button !== 0) return;
    const el = inner.current?.querySelector<HTMLElement>(`[data-block~="${CSS.escape(sel)}"]`);
    if (!el) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = { id: sel, el, x: e.clientX, y: e.clientY, to: null, place: 'after' };
    el.classList.add('ed-lift');
  }

  function moveP(e: RPointerEvent) {
    const d = drag.current;
    if (!d || !stage.current || !inner.current) return;
    d.el.style.transform = `translate(${(e.clientX - d.x) / scale}px, ${(e.clientY - d.y) / scale}px)`;
    const under = document.elementsFromPoint(e.clientX, e.clientY).find((n) => inner.current!.contains(n) && !d.el.contains(n) && n.closest('[data-block]') && !d.el.contains(n.closest('[data-block]')));
    const target = under?.closest<HTMLElement>('[data-block]') ?? null;
    const to = firstId(target);
    if (!target || !to || to === d.id) {
      d.to = null;
      setGuide(null);
      return;
    }
    const r = target.getBoundingClientRect();
    const o = stage.current.getBoundingClientRect();
    const across = target.classList.contains('cx-tile');
    const place = across ? (e.clientX < r.left + r.width / 2 ? 'before' : 'after') : e.clientY < r.top + r.height / 2 ? 'before' : 'after';
    d.to = to;
    d.place = place;
    setGuide(across
      ? { x: (place === 'before' ? r.left : r.right) - o.left - 1, y: r.top - o.top, w: 2, h: r.height }
      : { x: r.left - o.left, y: (place === 'before' ? r.top : r.bottom) - o.top - 1, w: r.width, h: 2 });
  }

  function up() {
    const d = drag.current;
    if (!d) return;
    d.el.style.transform = '';
    d.el.classList.remove('ed-lift');
    drag.current = null;
    setGuide(null);
    if (d.to) onMove(d.id, d.to, d.place);
  }

  const block = page.blocks.find((b) => b.id === sel) ?? null;
  const shown = page.blocks.filter((b) => !b.hidden && b.kind !== 'id.name' && b.kind !== 'pay.wallet');
  const blockers = block ? dependents(page, block.id) : [];
  const idx = block ? page.blocks.indexOf(block) : -1;
  const closeMore = useCallback(() => setMore(false), [setMore]);
  useEffect(() => setMore(false), [sel, setMore]);
  const bar = block && mode === 'edit' && (
    <div className={`ed-bar ${wide ? '' : 'docked'}`} style={wide && box ? { left: Math.max(0, box.x), top: box.y > 56 ? box.y - 52 : box.y + box.h + 8 } : undefined} role="toolbar" aria-label={kindLabel(block.kind, lang)}>
      {wide && (
        <button type="button" className="ed-btn icon s ed-grip" aria-label={t.drag} title={t.drag} onPointerDown={down} onPointerMove={moveP} onPointerUp={up} onPointerCancel={up}>
          <GripVertical size={16} aria-hidden="true" />
        </button>
      )}
      {tools?.(block)}
      {tools && <span className="ed-sep" aria-hidden="true" />}
      <SizeChips block={block} look={look} lang={lang} t={t} act={act} compact />
      {sizable(block.kind, look) && <span className="ed-sep" aria-hidden="true" />}
      <button type="button" className="ed-btn icon s" aria-label={t.style} title={t.style} onClick={() => onStyle(block.id)}><Palette size={16} aria-hidden="true" /></button>
      <button type="button" className="ed-btn icon s ed-more-btn" aria-label={t.more} title={t.more} aria-haspopup="menu" aria-expanded={more} onClick={() => setMore(!more)}><MoreHorizontal size={16} aria-hidden="true" /></button>
      {more && (
        <MoreMenu label={t.more} onClose={closeMore}>
          {!wide && <button type="button" role="menuitem" className="ed-menu-item" onClick={() => { setMore(false); onEdit(); }}><Pencil size={16} aria-hidden="true" />{t.edit}</button>}
          <button type="button" role="menuitem" className="ed-menu-item" disabled={idx <= 0} onClick={() => onNudge(block.id, -1)}><ChevronUp size={16} aria-hidden="true" />{t.moveUp}</button>
          <button type="button" role="menuitem" className="ed-menu-item" disabled={idx >= page.blocks.length - 1} onClick={() => onNudge(block.id, 1)}><ChevronDown size={16} aria-hidden="true" />{t.moveDown}</button>
          <button type="button" role="menuitem" className="ed-menu-item" onClick={() => { setMore(false); act({ t: 'hide', id: block.id, hidden: true }); }}><EyeOff size={16} aria-hidden="true" />{t.hide}</button>
          <button
            type="button"
            role="menuitem"
            className="ed-menu-item danger"
            title={blockers.length ? t.usedBy(removeBlockers(page, block.id, lang)) : undefined}
            disabled={blockers.length > 0}
            onClick={() => { setMore(false); act({ t: 'remove', id: block.id }); }}
          >
            <Trash2 size={16} aria-hidden="true" />
            <span className="ed-grow">{t.remove}</span>
            {blockers.length > 0 && <span className="ed-hint">{t.usedBy(removeBlockers(page, block.id, lang))}</span>}
          </button>
        </MoreMenu>
      )}
    </div>
  );

  return (
    <div className="ed-ground" ref={ground} data-mode={mode} data-frame={frame} onClick={(e) => e.target === e.currentTarget && onSelect(null)}>
      {wide && (
        <div className="ed-frame-cap" style={{ maxWidth: Math.max(width * scale, 360) }}>
          <span className="ed-hint">{frame === 'phone' ? `${t.phone} · 390` : `${t.desktop} · 1232`}</span>
          {cap}
          <div className="ed-zoom" role="group" aria-label={t.zoomFit}>
            <button type="button" className="ed-btn icon s" aria-label={t.zoomOut} disabled={scale <= ZOOMS[0] + 0.001} onClick={() => stepZoom(-1)}><Minus size={14} aria-hidden="true" /></button>
            <button type="button" className="ed-zoom-n" aria-label={t.zoomFit} title={t.zoomFit} aria-pressed={zoom === null} onClick={() => setZoom(null)}>{Math.round(scale * 100)}%</button>
            <button type="button" className="ed-btn icon s" aria-label={t.zoomIn} disabled={scale >= ZOOMS[ZOOMS.length - 1] - 0.001} onClick={() => stepZoom(1)}><Plus size={14} aria-hidden="true" /></button>
          </div>
        </div>
      )}
      <div className="ed-stage" ref={stage} style={{ width: width * scale, height: innerH * scale }}>
        <div className="ed-page" ref={inner} style={{ width, transform: scale !== 1 ? `scale(${scale})` : undefined }} onClickCapture={click}>
          <CreatorPage page={ghostPage(draw, mode === 'edit' ? sel : null)} live={{ network: 'devnet' }} look={look} theme={theme} lang={lang} frame="preview" />
        </div>
        {box && (
          <div className={`ed-sel ${block?.hidden ? 'ghost' : ''}`} style={{ left: box.x, top: box.y, width: box.w, height: box.h }}>
            <i aria-hidden="true" /><i aria-hidden="true" /><i aria-hidden="true" /><i aria-hidden="true" />
            {block?.hidden && <span className="ed-ghost-tag"><EyeOff size={13} aria-hidden="true" />{t.hidden}</span>}
          </div>
        )}
        {guide && <div className="ed-guide" style={{ left: guide.x, top: guide.y, width: guide.w, height: guide.h }} aria-hidden="true" />}
        {wide && bar}
      </div>
      {mode === 'edit' && shown.length === 0 && (
        <div className="ed-empty">
          <p>{t.emptyPage}</p>
          <button type="button" className="ed-btn ink" onClick={onAdd}><Plus size={16} aria-hidden="true" />{t.addFirst}</button>
        </div>
      )}
      {!wide && bar}
    </div>
  );
}
