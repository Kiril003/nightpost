import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, Calendar, Check, LogIn, MapPin, Plus } from 'lucide-react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { Btn, Card, Field, Notice, Status, controlSkin } from '../components/account/Panels';
import { previewVenue } from '../components/account/homeModel';
import PhoneFrame from '../components/shell/PhoneFrame';
import VenueEditor from '../components/builder/VenueEditor';
import VenuePreview, { showPrice } from '../components/builder/VenuePreview';
import EventPreview from '../components/builder/EventPreview';
import { blankEvent, pageFromDraft, type Draft } from '../components/builder/draft';
import {
  dateTile,
  eventCount,
  findEvent,
  goingTotal,
  readiness,
  timeRange,
  type ReadinessKey,
} from '../components/builder/builderModel';
import {
  edited,
  hasStash,
  initialEditor,
  loaded,
  requestFor,
  saved,
  stashDraft,
  tabStore,
  takeStash,
  type EditorAction,
  type EditorState,
} from '../components/builder/editorState';
import { buildCopy, type BuildCopy } from '../i18n/build';
import { builderA, type BuilderACopy } from '../i18n/builderA';
import { useLanguage } from '../i18n';
import type { Lang } from '../i18n/types';
import { readToken } from '../lib/account-api';
import { usePageMeta } from '../lib/meta';
import { LIMITS, localIsoAt, type Problem } from '../lib/venueBlocks';
import {
  VenueRefusal,
  VenuesUnavailable,
  createVenue,
  deleteVenue,
  getVenue,
  myVenues,
  publishVenue,
  savePage,
  type Venue,
} from '../lib/venuesApi';
import {
  getVenueFacts,
  getVenueStats,
  setEventState,
  type PlaceEvent,
  type VenueFacts,
  type VenueStats,
} from '../lib/placesApi';
import { capacityInput, splitEvents, stateBody } from '../lib/places/eventsModel';
import { statsEmpty } from '../lib/places/statsModel';
import { placeRoute } from '../lib/creator/entry';
import { hold } from '../lib/fresh';

const ICON = { strokeWidth: 1.75 } as const;

function PlacesHeader({
  copy,
  action,
}: {
  copy: BuildCopy;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="eyebrow">{copy.hero.eyebrow}</p>
        <h1 className="mt-3 text-balance text-page-h1 text-ink">{copy.hero.title}</h1>
        <p className="mt-4 max-w-prose text-body text-ink-2">{copy.hero.sub}</p>
        <p className="mt-3 max-w-prose text-body text-ink-2">
          {copy.start.lead}{' '}
          <Link to="/build?new=1" className="font-semibold text-ink underline underline-offset-4">
            {copy.start.link}
          </Link>
        </p>
      </div>
      {action}
    </div>
  );
}

export default function Build() {
  const { lang } = useLanguage();
  const copy = buildCopy[lang];
  const a = builderA[lang];
  usePageMeta(copy.meta);
  const [params, setParams] = useSearchParams();
  const venueId = params.get('venue');
  const eventId = params.get('event');
  const [signedIn, setSignedIn] = useState(() => readToken() !== null);
  const [seedNewEvent, setSeedNewEvent] = useState<string | null>(null);
  const signedOut = useCallback(() => setSignedIn(false), []);
  const moved = placeRoute(params);

  const openVenue = (id: string | null, opts?: { addEvent?: boolean; scrollTo?: string }) => {
    const next = new URLSearchParams(params);
    next.delete('event');
    if (id) next.set('venue', id);
    else next.delete('venue');
    next.set('form', '1');
    if (opts?.addEvent && id) setSeedNewEvent(id);
    setParams(next);
    if (opts?.scrollTo) {
      window.setTimeout(() => {
        document.getElementById(opts.scrollTo!)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }, 60);
    }
  };

  const openEvent = (vId: string, evId: string) => {
    const next = new URLSearchParams(params);
    next.set('venue', vId);
    next.set('event', evId);
    next.set('form', '1');
    setParams(next);
  };

  const closeEvent = () => {
    const next = new URLSearchParams(params);
    next.delete('event');
    setParams(next);
  };

  if (moved) return <Navigate to={moved} replace />;
  if (!signedIn && venueId === null) return <Navigate to="/build" replace />;

  return (
    <div className="mx-auto w-full max-w-shell px-4 pb-16 pt-8 sm:px-6 lg:px-14">
      {!signedIn ? (
        <SignedOutCard copy={copy} venueId={venueId} kept={venueId !== null && hasStash(tabStore(), venueId)} />
      ) : venueId && eventId ? (
        <EventView
          key={`${venueId}:${eventId}`}
          venueId={venueId}
          eventId={eventId}
          copy={copy}
          a={a}
          lang={lang}
          onBack={closeEvent}
          onEditInPage={() => openVenue(venueId, { scrollTo: 'sec-events' })}
          onSignedOut={signedOut}
        />
      ) : venueId ? (
        <Editor
          key={venueId}
          id={venueId}
          copy={copy}
          a={a}
          lang={lang}
          seedNewEvent={seedNewEvent === venueId}
          onConsumedSeed={() => setSeedNewEvent(null)}
          onBack={() => openVenue(null)}
          onSignedOut={signedOut}
        />
      ) : (
        <VenueList
          copy={copy}
          a={a}
          lang={lang}
          onNewEvent={(id) => openVenue(id, { addEvent: true, scrollTo: 'sec-events' })}
          onOpenEvent={openEvent}
          onSignedOut={signedOut}
        />
      )}
    </div>
  );
}

function SignedOutCard({ copy, venueId, kept }: { copy: BuildCopy; venueId: string | null; kept: boolean }) {
  const back = venueId ? `/build/place?form=1&venue=${encodeURIComponent(venueId)}` : '/build/place';
  return (
    <div data-build-view="signedout" className="max-w-[760px] space-y-10">
      <PlacesHeader copy={copy} />
      <Card className="max-w-xl">
        <h2 className="text-title-2 text-ink">{copy.signedOut.title}</h2>
        <p className="mt-2 text-body text-ink-2">{copy.signedOut.body}</p>
        {kept && <p className="mt-3 text-body text-ink">{copy.signedOut.kept}</p>}
        <Link to={`/account?next=${encodeURIComponent(back)}`} className="btn-primary mt-5">
          <LogIn className="h-4 w-4" {...ICON} aria-hidden />
          {copy.signedOut.cta}
        </Link>
      </Card>
    </div>
  );
}

type Load<T> =
  | { state: 'loading' }
  | { state: 'ready'; value: T }
  | { state: 'unavailable' }
  | { state: 'error'; err: unknown };

type Said = { tone: 'error' | 'success'; say: (copy: BuildCopy, lang: Lang) => string };

function refusalText(err: unknown, copy: BuildCopy, lang: Lang): string {
  if (err instanceof VenueRefusal) {
    if (err.status === 0) return copy.problems.network;
    if (err.status === 403) return copy.problems.forbidden;
    if (err.status === 404) return copy.problems.notFound;
    const fromServer = lang === 'uk' ? err.messageUk : err.messageEn;
    if (fromServer) return fromServer;
    if (err.refused && err.refused in copy.problems) return copy.problems[err.refused as Problem];
  }
  return copy.problems.generic;
}

function formatWeekdayLine(starts: string, ends: string | undefined, lang: Lang): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(starts);
  const range = timeRange(starts, ends);
  if (!m) return range;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  if (Number.isNaN(d.getTime())) return range;
  const wd = d.toLocaleDateString(lang === 'uk' ? 'uk-UA' : 'en-GB', { weekday: 'short', timeZone: 'UTC' });
  return `${wd} · ${range}`;
}

function VenueList({
  copy,
  a,
  lang,
  onNewEvent,
  onOpenEvent,
  onSignedOut,
}: {
  copy: BuildCopy;
  a: BuilderACopy;
  lang: Lang;
  onNewEvent: (id: string) => void;
  onOpenEvent: (venueId: string, eventId: string) => void;
  onSignedOut: () => void;
}) {
  const navigate = useNavigate();
  const l = copy.list;
  const p = a.places;
  const [venues, setVenues] = useState<Load<Venue[]>>({ state: 'loading' });
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [stats, setStats] = useState<VenueStats | null>(null);
  const [facts, setFacts] = useState<VenueFacts | null>(null);

  const load = useCallback(() => {
    setVenues({ state: 'loading' });
    myVenues()
      .then((value) => setVenues({ state: 'ready', value }))
      .catch((err) => {
        if (err instanceof VenuesUnavailable) setVenues({ state: 'unavailable' });
        else if (err instanceof VenueRefusal && err.status === 401) onSignedOut();
        else setVenues({ state: 'error', err });
      });
  }, [onSignedOut]);

  useEffect(load, [load]);

  const focus = venues.state === 'ready' ? previewVenue(venues.value) : null;

  useEffect(() => {
    setStats(null);
    setFacts(null);
    if (!focus) return;
    let live = true;
    getVenueStats(focus.id)
      .then((s) => {
        if (live) setStats(s);
      })
      .catch(() => {
        if (live) setStats(null);
      });
    if (focus.published) {
      getVenueFacts(focus.id)
        .then((f) => {
          if (live) setFacts(f);
        })
        .catch(() => {
          if (live) setFacts(null);
        });
    }
    return () => {
      live = false;
    };
  }, [focus?.id, focus?.published]);

  const create = async (ev: FormEvent) => {
    ev.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const venue = await createVenue(name.trim());
      navigate(`/build/edit/${encodeURIComponent(venue.id)}`);
    } catch (err) {
      if (err instanceof VenueRefusal && err.status === 401) onSignedOut();
      else if (err instanceof VenuesUnavailable) setVenues({ state: 'unavailable' });
      else setError(err);
    } finally {
      setBusy(false);
    }
  };

  if (venues.state === 'loading') {
    return (
      <div data-build-view="places" className="max-w-[760px] space-y-10">
        <PlacesHeader copy={copy} />
        <p className="text-subhead text-ink-2" aria-busy="true">
          {copy.loading}
        </p>
      </div>
    );
  }
  if (venues.state === 'unavailable') {
    return (
      <div data-build-view="places" className="max-w-[760px] space-y-10">
        <PlacesHeader copy={copy} />
        <Unavailable copy={copy} />
      </div>
    );
  }
  if (venues.state === 'error') {
    return (
      <div data-build-view="places" className="max-w-[760px] space-y-6">
        <PlacesHeader copy={copy} />
        <Notice tone="error">{refusalText(venues.err, copy, lang)}</Notice>
        <Btn tone="secondary" onClick={load}>
          {copy.retry}
        </Btn>
      </div>
    );
  }

  const list = venues.value;
  const showForm = creating || list.length === 0;
  const nowIso = localIsoAt();
  const upcoming = facts ? splitEvents(facts.events, nowIso).upcoming.slice(0, 5) : [];
  const upcomingStats = stats ? splitEvents(stats.events, nowIso).upcoming : [];

  return (
    <div data-build-view="places" className="max-w-[760px] space-y-10">
      <PlacesHeader
        copy={copy}
        action={
          list.length > 0 && !showForm ? (
            <Btn tone="secondary" onClick={() => setCreating(true)}>
              <Plus className="h-4 w-4" {...ICON} aria-hidden />
              {p.newPlace}
            </Btn>
          ) : undefined
        }
      />

      {showForm && (
        <form onSubmit={create} className="flex flex-wrap items-end gap-3">
          <div className="min-w-[220px] flex-1">
            <Field
              label={l.newName}
              value={name}
              onChange={setName}
              placeholder={l.newNamePlaceholder}
              maxLength={LIMITS.title}
              required
            />
          </div>
          <Btn type="submit" tone="primary" busy={busy} disabled={!name.trim()}>
            {p.create}
          </Btn>
          {list.length > 0 && (
            <Btn
              tone="ghost"
              onClick={() => {
                setCreating(false);
                setError(null);
              }}
            >
              {p.cancel}
            </Btn>
          )}
          {error !== null && (
            <div className="w-full">
              <Notice tone="error">{refusalText(error, copy, lang)}</Notice>
            </div>
          )}
        </form>
      )}

      {list.length > 0 && (
        <div>
          {list.map((v, i) => {
            const evs = eventCount(v.page);
            const addr =
              v.location?.address ??
              v.page?.blocks.find((b): b is Extract<typeof b, { type: 'address' }> => b.type === 'address')?.text ??
              null;
            const metaParts: string[] = [];
            if (v.rating) metaParts.push(`★ ${v.rating.mean.toFixed(1)}`);
            if (evs > 0) metaParts.push(`${evs} ${p.events(evs)}`);
            return (
              <div key={v.id}>
                {i > 0 && <div className="ml-14 border-t border-line" />}
                <button
                  type="button"
                  onClick={() => navigate(`/build/edit/${encodeURIComponent(v.id)}`)}
                  className="flex min-h-[64px] w-full items-center gap-4 py-2 text-left transition-colors hover:text-ink"
                >
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                    <MapPin className="h-5 w-5" {...ICON} aria-hidden />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-headline text-ink">{v.name}</p>
                    {addr && <p className="truncate text-subhead text-ink-2">{addr}</p>}
                  </div>
                  {hasStash(tabStore(), v.id) && (
                    <span className="inline-flex shrink-0 items-center rounded-chip bg-overlay px-2.5 py-0.5 text-caption text-ink-2">
                      {copy.actions.unsaved}
                    </span>
                  )}
                  {metaParts.length > 0 && (
                    <span className="tnum shrink-0 text-footnote text-ink-3">{metaParts.join(' · ')}</span>
                  )}
                </button>
              </div>
            );
          })}
        </div>
      )}

      {focus && stats && (
        <div className="space-y-3">
          <h2 className="text-title-2 text-ink">{p.last28Days(focus.name)}</h2>
          {statsEmpty(stats) ? (
            <p className="text-subhead text-ink-2">{p.noVisits}</p>
          ) : (
            <div className="grid grid-cols-2 divide-y divide-line overflow-hidden rounded-card border border-line bg-surface sm:grid-cols-4 sm:divide-x sm:divide-y-0">
              <div className="p-5">
                <b className="tnum block text-title-1 text-ink">{stats.visitors}</b>
                <span className="mt-2 block text-subhead text-ink-3">{p.visitors}</span>
              </div>
              <div className="p-5">
                <b className="tnum block text-title-1 text-ink">{stats.repeat}</b>
                <span className="mt-2 block text-subhead text-ink-3">{p.repeat}</span>
              </div>
              <div className="p-5">
                <b className="tnum block text-title-1 text-ink">
                  {stats.rating ? stats.rating.mean.toFixed(1) : '—'}
                </b>
                <span className="mt-2 block text-subhead text-ink-3">{p.rating}</span>
              </div>
              <div className="p-5">
                <b className="tnum block text-title-1 text-ink">{goingTotal(upcomingStats)}</b>
                <span className="mt-2 block text-subhead text-ink-3">{p.going}</span>
              </div>
            </div>
          )}
        </div>
      )}

      {focus && facts && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-title-2 text-ink">{p.nextEvents(focus.name)}</h2>
            <Btn tone="secondary" onClick={() => onNewEvent(focus.id)}>
              <Calendar className="h-4 w-4" {...ICON} aria-hidden />
              {p.newEvent}
            </Btn>
          </div>
          {upcoming.length === 0 ? (
            <p className="text-subhead text-ink-2">{p.noUpcoming}</p>
          ) : (
            <div>
              {upcoming.map((ev, i) => {
                const tile = dateTile(ev.starts, lang);
                return (
                  <div key={ev.event_id}>
                    {i > 0 && <div className="ml-14 border-t border-line" />}
                    <button
                      type="button"
                      onClick={() => onOpenEvent(focus.id, ev.event_id)}
                      className="flex min-h-[64px] w-full items-center gap-4 py-2 text-left transition-colors hover:text-ink"
                    >
                      <div className="flex h-10 w-10 shrink-0 flex-col items-center justify-center rounded-control bg-raised">
                        <span className="text-caption text-ink-2">{tile.mon}</span>
                        <b className="tnum text-footnote font-semibold leading-none text-ink">{tile.day}</b>
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-headline text-ink">{ev.title}</p>
                        <p className="tnum truncate text-subhead text-ink-2">
                          {formatWeekdayLine(ev.starts, ev.ends, lang)}
                        </p>
                      </div>
                      <div className="shrink-0">
                        {ev.cancelled ? (
                          <Status tone="danger">{p.cancelled}</Status>
                        ) : (
                          <span className="tnum text-footnote text-ink-3">
                            {ev.capacity !== null ? p.goingOf(ev.going, ev.capacity) : p.goingCount(ev.going)}
                          </span>
                        )}
                      </div>
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function Unavailable({ copy }: { copy: BuildCopy }) {
  return (
    <Card className="max-w-xl">
      <h2 className="text-title-2 text-ink">{copy.unavailable.title}</h2>
      <p className="mt-2 text-body text-ink-2">{copy.unavailable.body}</p>
    </Card>
  );
}

const SECTION_ID: Record<ReadinessKey, string> = {
  basics: 'sec-basics',
  place: 'sec-location',
  hours: 'sec-hours',
  menu: 'sec-menu',
  wallet: 'sec-wallet',
};

function Editor({
  id,
  copy,
  a,
  lang,
  seedNewEvent,
  onConsumedSeed,
  onBack,
  onSignedOut,
}: {
  id: string;
  copy: BuildCopy;
  a: BuilderACopy;
  lang: Lang;
  seedNewEvent: boolean;
  onConsumedSeed: () => void;
  onBack: () => void;
  onSignedOut: () => void;
}) {
  const act = copy.actions;
  const [state, setState] = useState<EditorState>(initialEditor);
  const [failure, setFailure] = useState<unknown>(null);
  const [busy, setBusy] = useState<null | EditorAction | 'remove'>(null);
  const [notice, setNotice] = useState<Said | null>(null);
  const [showFields, setShowFields] = useState(false);
  const [removing, setRemoving] = useState(false);
  const signedOutRef = useRef(onSignedOut);
  signedOutRef.current = onSignedOut;
  const { venue: current, draft, dirty } = state;

  useEffect(() => {
    let live = true;
    getVenue(id)
      .then((v) => {
        if (!live) return;
        const stash = takeStash(tabStore(), id);
        setState((s) => loaded(s, v, stash));
      })
      .catch((err) => {
        if (!live) return;
        if (err instanceof VenueRefusal && err.status === 401) signedOutRef.current();
        else setFailure(err);
      });
    return () => {
      live = false;
    };
  }, [id]);

  useEffect(() => {
    if (!seedNewEvent || !draft) return;
    onConsumedSeed();
    setState((s) => (s.draft ? edited(s, { ...s.draft, events: [...(s.draft.events ?? []), blankEvent()] }) : s));
  }, [seedNewEvent, draft, onConsumedSeed]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (ev: BeforeUnloadEvent) => ev.preventDefault();
    window.addEventListener('beforeunload', warn);
    const free = hold(() => true);
    return () => {
      window.removeEventListener('beforeunload', warn);
      free();
    };
  }, [dirty]);

  const leaveRef = useRef({ dirty, draft });
  leaveRef.current = { dirty, draft };
  const goneRef = useRef(false);
  useEffect(
    () => () => {
      const left = leaveRef.current;
      if (!goneRef.current && left.dirty && left.draft) stashDraft(tabStore(), id, left.draft);
    },
    [id],
  );

  const built = useMemo(() => (draft ? pageFromDraft(draft) : null), [draft]);

  if (failure !== null) {
    return (
      <div data-build-view="editor" className="space-y-4">
        <button type="button" onClick={onBack} aria-label={a.editor.back} className="btn-ghost -ml-3 !min-h-[44px]">
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden />
          {copy.editor.back}
        </button>
        <Notice tone="error">{refusalText(failure, copy, lang)}</Notice>
      </div>
    );
  }
  if (!current || !draft || !built) {
    return (
      <div data-build-view="editor">
        <p className="text-subhead text-ink-2" aria-busy="true">
          {copy.loading}
        </p>
      </div>
    );
  }

  const change = (next: Draft) => {
    setState((s) => edited(s, next));
    setNotice(null);
    setRemoving(false);
  };

  const run = async (kind: EditorAction) => {
    const req = requestFor(kind, draft, current);
    if ('problem' in req) {
      setShowFields(true);
      const { problem } = req;
      setNotice({ tone: 'error', say: (c) => c.problems[problem] });
      return;
    }
    setBusy(kind);
    setNotice(null);
    try {
      const venue =
        kind === 'publish' ? await publishVenue(current.id, req.page) : await savePage(current.id, req.page, req.published);
      setState((s) => saved(s, venue, kind, req.page));
      if (kind !== 'unpublish') setShowFields(false);
      setNotice({
        tone: 'success',
        say: (c) =>
          kind === 'publish'
            ? c.actions.publishedNow
            : kind === 'unpublish'
              ? c.actions.unpublishedNow
              : c.actions.saved,
      });
    } catch (err) {
      if (err instanceof VenueRefusal && err.status === 401) {
        if (dirty) stashDraft(tabStore(), current.id, draft);
        onSignedOut();
      } else {
        setNotice({ tone: 'error', say: (c, l) => refusalText(err, c, l) });
      }
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    setBusy('remove');
    setNotice(null);
    try {
      await deleteVenue(current.id);
      goneRef.current = true;
      onBack();
    } catch (err) {
      if (err instanceof VenueRefusal && err.status === 401) {
        if (dirty) stashDraft(tabStore(), current.id, draft);
        onSignedOut();
      } else {
        setNotice({ tone: 'error', say: (c, l) => refusalText(err, c, l) });
      }
      setRemoving(false);
      setBusy(null);
    }
  };

  const items = readiness(draft);
  const doneCount = items.filter((i) => i.done).length;
  const readyLabel = (item: (typeof items)[number]) => {
    switch (item.key) {
      case 'basics':
        return a.editor.readyBasics;
      case 'place':
        return a.editor.readyPlace;
      case 'hours':
        return a.editor.readyHours;
      case 'menu':
        return a.editor.readyMenu(item.count ?? 0);
      case 'wallet':
        return a.editor.readyWallet;
    }
  };

  return (
    <div data-build-view="editor">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <button type="button" onClick={onBack} aria-label={a.editor.back} className="btn-ghost !min-h-[44px] !px-2.5">
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden />
        </button>
        <h1 className="min-w-0 break-words text-page-h1 text-ink">{current.name}</h1>
        <Status tone={current.published ? 'success' : 'neutral'}>
          {current.published ? copy.list.published : copy.list.draft}
        </Status>
        {dirty && (
          <span className="inline-flex items-center rounded-chip bg-overlay px-2.5 py-0.5 text-caption text-ink-2">
            {a.editor.unsaved}
          </span>
        )}
        {current.rev > 0 && <span className="text-footnote text-ink-3">{act.rev(current.rev)}</span>}
        {current.published && (
          <Link to={`/p/${encodeURIComponent(current.id)}`} className="text-footnote font-semibold text-accent-text underline underline-offset-4">
            {copy.start.openPage}
          </Link>
        )}

        <div className="ml-auto flex flex-wrap items-center gap-2.5">
          {current.published ? (
            <>
              <Btn tone="secondary" onClick={() => run('unpublish')} busy={busy === 'unpublish'} disabled={busy !== null}>
                {act.unpublish}
              </Btn>
              <Btn tone="primary" onClick={() => run('save')} busy={busy === 'save'} disabled={busy !== null || !dirty}>
                {act.saveChanges}
              </Btn>
            </>
          ) : (
            <>
              <Btn
                tone="ghost"
                onClick={() => (removing ? remove() : setRemoving(true))}
                busy={busy === 'remove'}
                disabled={busy !== null}
              >
                {removing ? act.removeSure : act.remove}
              </Btn>
              <Btn tone="secondary" onClick={() => run('save')} busy={busy === 'save'} disabled={busy !== null || !dirty}>
                {act.save}
              </Btn>
              <Btn tone="primary" onClick={() => run('publish')} busy={busy === 'publish'} disabled={busy !== null}>
                {act.publish}
              </Btn>
            </>
          )}
        </div>
      </div>

      {notice ? (
        <div className="mb-6">
          <Notice tone={notice.tone}>{notice.say(copy, lang)}</Notice>
        </div>
      ) : (
        state.restored && (
          <div className="mb-6">
            <Notice tone="success">{act.restored}</Notice>
          </div>
        )
      )}

      <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_320px] lg:grid-rows-[auto_1fr] lg:items-start lg:gap-x-10 lg:gap-y-5">
        <div className="order-2 min-w-0 lg:order-none lg:col-start-1 lg:row-span-2 lg:row-start-1">
          <VenueEditor
            draft={draft}
            onChange={change}
            fields={showFields ? built.fields : {}}
            copy={copy}
            disabled={busy !== null}
          />
        </div>

        <aside data-chrome="aside" className="order-3 min-w-0 space-y-5 lg:order-none lg:col-start-2 lg:row-start-1 lg:sticky lg:top-24">
          <PhoneFrame>
            <VenuePreview page={built.page} rating={current.rating} copy={copy} lang={lang} />
          </PhoneFrame>
        </aside>

        {!(current.published && doneCount === items.length) && (
        <div className="order-1 rounded-card border border-line bg-surface p-5 lg:order-none lg:col-start-2 lg:row-start-2">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-caption text-ink-3">{current.published ? a.editor.finishTitle : a.editor.readyTitle}</p>
            <span className="tnum text-subhead font-semibold text-ink">
              {a.editor.readyOf(doneCount, items.length)}
            </span>
          </div>
          <ul className="divide-y divide-line">
            {items.map((item) => (
              <li key={item.key} className="flex items-center gap-3 py-2.5 text-subhead text-ink">
                {item.done ? (
                  <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-chip bg-success/15 text-success">
                    <Check className="h-4 w-4" {...ICON} aria-hidden />
                  </span>
                ) : (
                  <span className="h-6 w-6 shrink-0 rounded-chip border border-ink-3" aria-hidden />
                )}
                <span className="min-w-0 flex-1">{readyLabel(item)}</span>
                {!item.done && (
                  <button
                    type="button"
                    onClick={() =>
                      document
                        .getElementById(SECTION_ID[item.key])
                        ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                    }
                    className="btn-ghost !min-h-[32px] !px-2 text-subhead"
                  >
                    {a.editor.add}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
        )}
      </div>
    </div>
  );
}

function formatLongHeading(starts: string, lang: Lang): { dayNum: string; heading: string; sub: string } {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(starts);
  const range = timeRange(starts);
  if (!m) return { dayNum: '—', heading: starts, sub: range };
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  if (Number.isNaN(d.getTime())) return { dayNum: m[3], heading: starts, sub: range };
  const locale = lang === 'uk' ? 'uk-UA' : 'en-GB';
  const weekday = d.toLocaleDateString(locale, { weekday: 'long', timeZone: 'UTC' });
  const month = d.toLocaleDateString(locale, { month: 'long', timeZone: 'UTC' });
  return {
    dayNum: m[3],
    heading: `${weekday}, ${month}`,
    sub: m[1],
  };
}

function EventView({
  venueId,
  eventId,
  copy,
  a,
  lang,
  onBack,
  onEditInPage,
  onSignedOut,
}: {
  venueId: string;
  eventId: string;
  copy: BuildCopy;
  a: BuilderACopy;
  lang: Lang;
  onBack: () => void;
  onEditInPage: () => void;
  onSignedOut: () => void;
}) {
  const e = a.event;
  const [venue, setVenue] = useState<Venue | null>(null);
  const [facts, setFacts] = useState<VenueFacts | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [capText, setCapText] = useState('');
  const [busy, setBusy] = useState<null | 'save' | 'toggle'>(null);
  const [notice, setNotice] = useState<Said | null>(null);

  useEffect(() => {
    let live = true;
    getVenue(venueId)
      .then((v) => {
        if (!live) return;
        setVenue(v);
        if (v.published) {
          getVenueFacts(v.id)
            .then((f) => {
              if (!live) return;
              setFacts(f);
              const found = f.events.find(
                (ev) => ev.event_id === eventId || ev.event_id === `${v.id}.${eventId}`,
              );
              if (found) setCapText(found.capacity !== null ? String(found.capacity) : '');
            })
            .catch(() => {
              if (live) setFacts(null);
            });
        }
      })
      .catch((err) => {
        if (!live) return;
        if (err instanceof VenueRefusal && err.status === 401) onSignedOut();
        else setFailure(err);
      });
    return () => {
      live = false;
    };
  }, [venueId, eventId, onSignedOut]);

  if (failure !== null) {
    return (
      <div data-build-view="event" className="space-y-4">
        <button type="button" onClick={onBack} aria-label={e.back} className="btn-ghost -ml-3 !min-h-[44px]">
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden />
          {e.back}
        </button>
        <Notice tone="error">{refusalText(failure, copy, lang)}</Notice>
      </div>
    );
  }

  if (!venue) {
    return (
      <div data-build-view="event">
        <p className="text-subhead text-ink-2" aria-busy="true">
          {copy.loading}
        </p>
      </div>
    );
  }

  const block = findEvent(venue.page, eventId, venue.id);
  if (!block) {
    return (
      <div data-build-view="event" className="space-y-4">
        <button type="button" onClick={onBack} aria-label={e.back} className="btn-ghost -ml-3 !min-h-[44px]">
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden />
          {e.back}
        </button>
        <Notice tone="error">{copy.problems.notFound}</Notice>
      </div>
    );
  }

  const qualifiedId = `${venue.id}.${block.id}`;
  const factRow: PlaceEvent | null =
    facts?.events.find((x) => x.event_id === qualifiedId || x.event_id === eventId) ?? null;
  const going = factRow?.going ?? 0;
  const cancelled = factRow?.cancelled ?? false;
  const capacity = factRow?.capacity ?? null;

  const nowIso = localIsoAt();
  const isPast = (block.ends ?? block.starts) < nowIso;
  const statusTone = cancelled ? 'danger' : isPast ? 'neutral' : 'success';
  const statusLabel = cancelled ? e.cancelled : isPast ? e.past : e.upcoming;

  const address =
    venue.location?.address ??
    venue.page?.blocks.find((b): b is Extract<typeof b, { type: 'address' }> => b.type === 'address')?.text ??
    null;

  const dateHero = formatLongHeading(block.starts, lang);
  const rangeStr = timeRange(block.starts, block.ends);
  const capCheck = capacityInput(capText);
  const barPct =
    capCheck.ok && capCheck.value && capCheck.value > 0
      ? Math.min(100, Math.round((going / capCheck.value) * 100))
      : 0;

  const applyState = async (nextCancelled: boolean, kind: 'save' | 'toggle') => {
    const body = stateBody(capText, nextCancelled);
    if (!body) return;
    setBusy(kind);
    setNotice(null);
    try {
      const updated = await setEventState(qualifiedId, body);
      setFacts((prev) =>
        prev
          ? {
              ...prev,
              events: prev.events.map((row) =>
                row.event_id === qualifiedId
                  ? { ...row, capacity: updated.capacity, cancelled: updated.cancelled }
                  : row,
              ),
            }
          : prev,
      );
      setCapText(updated.capacity !== null ? String(updated.capacity) : '');
    } catch (err) {
      if (err instanceof VenueRefusal && err.status === 401) onSignedOut();
      else setNotice({ tone: 'error', say: (c, l) => refusalText(err, c, l) });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div data-build-view="event">
      <div className="mb-8 flex flex-wrap items-center gap-3">
        <button type="button" onClick={onBack} aria-label={e.back} className="btn-ghost !min-h-[44px] !px-2.5">
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden />
        </button>
        <h1 className="min-w-0 break-words text-page-h1 text-ink">{block.title}</h1>
        <Status tone={statusTone}>{statusLabel}</Status>

        <div className="ml-auto flex flex-wrap items-center gap-2.5">
          <Btn tone="ghost" onClick={onEditInPage}>
            {e.editInPage}
          </Btn>
          {venue.published ? (
            <>
              <Btn
                tone={cancelled ? 'secondary' : 'danger'}
                busy={busy === 'toggle'}
                disabled={busy !== null || !capCheck.ok}
                onClick={() => void applyState(!cancelled, 'toggle')}
              >
                {cancelled ? e.restoreEvent : e.cancelEvent}
              </Btn>
              <Btn
                tone="primary"
                busy={busy === 'save'}
                disabled={busy !== null || !capCheck.ok}
                onClick={() => void applyState(cancelled, 'save')}
              >
                {e.save}
              </Btn>
            </>
          ) : (
            <span className="text-subhead text-ink-3">{e.publishFirst}</span>
          )}
        </div>
      </div>

      {notice && (
        <div className="mb-6">
          <Notice tone={notice.tone}>{notice.say(copy, lang)}</Notice>
        </div>
      )}

      <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_320px] lg:items-start">
        <div className="min-w-0 space-y-9">
          <div className="flex flex-wrap items-end gap-6">
            <span className="tnum text-display text-ink">{dateHero.dayNum}</span>
            <div className="pb-1">
              <p className="text-title-1 text-ink">{dateHero.heading}</p>
              <p className="tnum mt-1 text-title-2 text-ink-3">
                {dateHero.sub} · {rangeStr}
              </p>
            </div>
          </div>

          <div className="divide-y divide-line overflow-hidden rounded-card border border-line bg-surface">
            <div className="grid min-h-[60px] grid-cols-[160px_minmax(0,1fr)] items-center gap-4 px-5 py-3">
              <span className="text-subhead text-ink-3">{e.starts}</span>
              <span className="tnum text-title-2 text-ink">{block.starts.slice(11, 16)}</span>
            </div>

            {block.ends && (
              <div className="grid min-h-[60px] grid-cols-[160px_minmax(0,1fr)] items-center gap-4 px-5 py-3">
                <span className="text-subhead text-ink-3">{e.ends}</span>
                <span className="tnum text-title-2 text-ink">{block.ends.slice(11, 16)}</span>
              </div>
            )}

            <div className="grid min-h-[60px] grid-cols-[160px_minmax(0,1fr)] items-center gap-4 px-5 py-3">
              <span className="text-subhead text-ink-3">{e.place}</span>
              <div className="min-w-0">
                <span className="block break-words text-title-2 text-ink">{venue.name}</span>
                {address && <span className="block break-words text-subhead text-ink-3">{address}</span>}
              </div>
            </div>

            <div className="grid min-h-[60px] grid-cols-[160px_minmax(0,1fr)] items-center gap-4 px-5 py-3">
              <span className="text-subhead text-ink-3">{e.capacity}</span>
              {venue.published ? (
                <div className="flex flex-wrap items-center gap-4">
                  <input
                    type="number"
                    min={1}
                    max={10000}
                    aria-label={e.capacity}
                    placeholder={e.noLimit}
                    value={capText}
                    onChange={(ev) => setCapText(ev.target.value)}
                    aria-invalid={!capCheck.ok ? true : undefined}
                    className={`${controlSkin} tnum h-10 !w-36 px-3`}
                  />
                  {capCheck.ok && capCheck.value !== null && (
                    <div className="h-2 min-w-[120px] flex-1 overflow-hidden rounded-chip bg-overlay">
                      <span className="block h-full rounded-chip bg-ink" style={{ width: `${barPct}%` }} />
                    </div>
                  )}
                </div>
              ) : (
                <span className="text-subhead text-ink-3">{e.publishFirst}</span>
              )}
            </div>

            <div className="grid min-h-[60px] grid-cols-[160px_minmax(0,1fr)] items-center gap-4 px-5 py-3">
              <span className="text-subhead text-ink-3">{e.price}</span>
              <span className="tnum text-title-2 text-ink">
                {block.price && block.token ? showPrice(block.price, block.token, lang) : e.free}
              </span>
            </div>

            <div className="grid min-h-[60px] grid-cols-[160px_minmax(0,1fr)] items-center gap-4 px-5 py-3">
              <span className="text-subhead text-ink-3">{e.going}</span>
              <span className="tnum text-title-2 text-ink">{e.goingValue(going)}</span>
            </div>
          </div>
        </div>

        <aside className="lg:sticky lg:top-24">
          <PhoneFrame>
            <EventPreview
              venueName={venue.name}
              address={address}
              event={block}
              going={going}
              capacity={capCheck.ok ? capCheck.value : capacity}
              copy={a}
              lang={lang}
            />
          </PhoneFrame>
        </aside>
      </div>
    </div>
  );
}
