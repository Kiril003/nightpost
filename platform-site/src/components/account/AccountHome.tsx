import { useCallback, useEffect, useState } from 'react';
import { Check, Copy, Download, MapPin, MonitorSmartphone } from 'lucide-react';
import { Link } from 'react-router-dom';
import {
  api,
  ApiError,
  atLeast,
  post,
  revokeSession,
  type Me,
  type SessionItem,
} from '../../lib/account-api';
import { myVenues, VenuesUnavailable, type Venue } from '../../lib/venuesApi';
import { homeCopy, type HomeCopy } from '../../i18n/accountHome';
import { pricingCopy } from '../../i18n/commerce';
import type { Lang } from '../../i18n/types';
import type { PlanId } from '../../lib/plans';
import { Btn, fmtDate, Notice } from './Panels';
import ConnectPhone from './ConnectPhone';
import { androidBuild, canDownload, licenceSectionVisible, placesFailure, sizeLabel } from './homeModel';

const ICON = { strokeWidth: 1.75 } as const;

function SkeletonRows() {
  return (
    <div className="mt-2">
      <div className="flex min-h-[64px] items-center gap-4 py-2">
        <div className="h-10 w-10 shrink-0 rounded-control bg-raised" />
        <div className="h-4 w-40 rounded-control bg-raised" />
      </div>
      <div className="ml-14 border-t border-line" />
      <div className="flex min-h-[64px] items-center gap-4 py-2">
        <div className="h-10 w-10 shrink-0 rounded-control bg-raised" />
        <div className="h-4 w-32 rounded-control bg-raised" />
      </div>
    </div>
  );
}

function SessionRow({
  session,
  h,
  lang,
  onRevoke,
  revoking,
}: {
  session: SessionItem;
  h: HomeCopy;
  lang: Lang;
  onRevoke?: (s: SessionItem) => void;
  revoking?: boolean;
}) {
  const viaLabel =
    session.via === 'qr' ? h.via.qr : session.via === 'email' ? h.via.email : session.via;
  const title = session.label || viaLabel || h.devices;
  const seen = fmtDate(session.created_at, lang);
  const meta = session.current ? `${h.thisBrowser} · ${seen}` : seen;

  return (
    <div className="flex min-h-[64px] items-center gap-4 py-2">
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
        <MonitorSmartphone className="h-5 w-5" {...ICON} aria-hidden />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-headline text-ink">{title}</p>
      </div>
      <span className="tnum shrink-0 text-footnote text-ink-3">{meta}</span>
      {onRevoke && session.id && (
        <Btn tone="ghost" busy={revoking} onClick={() => onRevoke(session)}>
          {h.signOutDevice}
        </Btn>
      )}
    </div>
  );
}

export function AccountDevices({
  lang,
  onSignOut,
}: {
  lang: Lang;
  onSignOut: () => void;
}) {
  const h = homeCopy[lang];
  const [sessions, setSessions] = useState<SessionItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const loadSessions = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const list = await api<SessionItem[]>('/api/me/sessions');
      setSessions(list);
    } catch {
      setSessions(null);
      setError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  const handleRevoke = async (session: SessionItem) => {
    setRevokingId(session.id);
    try {
      await revokeSession(session.id);
      if (session.current) {
        onSignOut();
        return;
      }
      await loadSessions();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onSignOut();
        return;
      }
    } finally {
      setRevokingId(null);
    }
  };

  return (
    <section data-home-section="devices" className="max-w-[760px]">
      <h2 className="text-title-2 text-ink">{h.devices}</h2>
      {loading ? (
        <SkeletonRows />
      ) : error ? (
        <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
            <MonitorSmartphone className="h-5 w-5" {...ICON} aria-hidden />
          </div>
          <p className="min-w-0 flex-1 text-headline text-ink-2">{h.devicesFailed}</p>
          <button type="button" onClick={() => void loadSessions()} className="btn-ghost inline-flex min-h-[44px] items-center">
            {h.retry}
          </button>
        </div>
      ) : sessions && sessions.length > 0 ? (
        <div className="mt-2">
          {sessions.map((s, i) => (
            <div key={s.id || s.created_at}>
              {i > 0 && <div className="ml-14 border-t border-line" />}
              <SessionRow
                session={s}
                h={h}
                lang={lang}
                onRevoke={(item) => void handleRevoke(item)}
                revoking={revokingId === s.id}
              />
            </div>
          ))}
        </div>
      ) : (
        <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
            <MonitorSmartphone className="h-5 w-5" {...ICON} aria-hidden />
          </div>
          <p className="min-w-0 flex-1 text-headline text-ink-2">{h.devicesEmpty}</p>
        </div>
      )}
    </section>
  );
}

export default function AccountHome({
  me,
  lang,
  onTab,
}: {
  me: Me;
  lang: Lang;
  onTab: (id: 'devices' | 'downloads') => void;
  onSignOut: () => void;
}) {
  const h = homeCopy[lang];

  const [venues, setVenues] = useState<Venue[] | null>(null);
  const [venuesLoading, setVenuesLoading] = useState(true);
  const [venuesError, setVenuesError] = useState(false);
  const [venuesNotOpen, setVenuesNotOpen] = useState(false);

  const [sessions, setSessions] = useState<SessionItem[] | null>(null);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState(false);

  const [downloading, setDownloading] = useState(false);
  const [downloadFailed, setDownloadFailed] = useState(false);
  const [copied, setCopied] = useState(false);

  const loadVenues = useCallback(async () => {
    setVenuesLoading(true);
    setVenuesError(false);
    setVenuesNotOpen(false);
    try {
      const list = await myVenues();
      setVenues(list);
    } catch (err) {
      setVenues(null);
      if (placesFailure(err, VenuesUnavailable) === 'notOpen') setVenuesNotOpen(true);
      else setVenuesError(true);
    } finally {
      setVenuesLoading(false);
    }
  }, []);

  const loadSessions = useCallback(async () => {
    setSessionsLoading(true);
    setSessionsError(false);
    try {
      const list = await api<SessionItem[]>('/api/me/sessions');
      setSessions(list);
    } catch {
      setSessions(null);
      setSessionsError(true);
    } finally {
      setSessionsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadVenues();
    void loadSessions();
  }, [loadVenues, loadSessions]);

  const handleDownload = async () => {
    setDownloading(true);
    setDownloadFailed(false);
    try {
      const { url } = await post<{ url: string }>('/api/download/link', { platform: 'android' });
      window.location.href = url;
    } catch {
      setDownloadFailed(true);
    } finally {
      setDownloading(false);
    }
  };

  const role = me.account.role;
  const build = androidBuild(me.downloads);
  const allowed = canDownload(me, atLeast(role, 'moderator'));
  const size = sizeLabel(build?.size, lang);
  const released = build?.published_at ? fmtDate(build.published_at, lang) : null;
  const metaLine = [build?.version, size, released].filter(Boolean).join(' · ');

  return (
    <div className="grid grid-cols-1 gap-12 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] lg:items-start">
      <div className="min-w-0 space-y-10">
        <section data-home-section="hero" className="rounded-card bg-surface p-6">
          <p className="text-caption text-ink-2">{h.appTitle}</p>
          <h2 className="mt-2 text-display text-ink">
            {build?.build !== undefined
              ? h.build(build.build)
              : build?.version
                ? build.version
                : h.noBuild}
          </h2>

          {build && (
            <>
              {metaLine && <p className="tnum mt-2 text-footnote text-ink-3">{metaLine}</p>}

              <div className="mt-6 flex flex-wrap items-center gap-3">
                {allowed ? (
                  <Btn tone="primary" busy={downloading} onClick={() => void handleDownload()}>
                    {!downloading && <Download className="h-4 w-4" {...ICON} aria-hidden />}
                    {downloading ? h.preparing : h.download}
                  </Btn>
                ) : (
                  <p className="text-subhead text-ink-2">
                    {h.needLicence}
                  </p>
                )}

                {build.sha256 && (
                  <button
                    type="button"
                    onClick={() => {
                      if (!build.sha256) return;
                      void navigator.clipboard?.writeText(build.sha256);
                      setCopied(true);
                      window.setTimeout(() => setCopied(false), 1600);
                    }}
                    className="btn-ghost inline-flex min-h-[44px] items-center"
                  >
                    {copied ? (
                      <Check className="h-4 w-4" {...ICON} aria-hidden />
                    ) : (
                      <Copy className="h-4 w-4" {...ICON} aria-hidden />
                    )}
                    {copied ? h.copied : h.copyChecksum}
                  </button>
                )}
              </div>

              {downloadFailed && (
                <Notice tone="error" className="mt-3">
                  {h.downloadFailed}
                </Notice>
              )}
            </>
          )}
        </section>

        {licenceSectionVisible(me) && (
          <section>
            <h2 className="text-title-2 text-ink">{h.licence}</h2>
            <div className="mt-2">
              {me.licenses.map((lic, i) => {
                const tier = pricingCopy[lang].plans[lic.tier as PlanId]?.name ?? lic.tier;
                const until = fmtDate(lic.updates_until, lang);
                const seats = `${lic.devices.length}/${lic.max_activations}`;
                return (
                  <div key={lic.id}>
                    {i > 0 && <div className="border-t border-line" />}
                    <div className="flex min-h-[64px] items-center justify-between gap-4 py-2">
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-headline text-ink">{tier}</p>
                        {lic.updates_until && (
                          <p className="tnum text-subhead text-ink-2">{until}</p>
                        )}
                      </div>
                      <span className="tnum shrink-0 text-footnote text-ink-3">{seats}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        )}
      </div>

      <div className="min-w-0 space-y-10">
        <section data-home-section="devices">
          <h2 className="text-title-2 text-ink">{h.devices}</h2>
          {sessionsLoading ? (
            <SkeletonRows />
          ) : sessionsError ? (
            <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                <MonitorSmartphone className="h-5 w-5" {...ICON} aria-hidden />
              </div>
              <p className="min-w-0 flex-1 text-headline text-ink-2">{h.devicesFailed}</p>
              <button type="button" onClick={() => void loadSessions()} className="btn-ghost inline-flex min-h-[44px] items-center">
                {h.retry}
              </button>
            </div>
          ) : sessions && sessions.length > 0 ? (
            <div className="mt-2">
              {sessions.slice(0, 3).map((s, i) => (
                <div key={s.id || s.created_at}>
                  {i > 0 && <div className="ml-14 border-t border-line" />}
                  <SessionRow session={s} h={h} lang={lang} />
                </div>
              ))}
              <div className="mt-2">
                <button type="button" onClick={() => onTab('devices')} className="btn-ghost -ml-3 inline-flex min-h-[44px] items-center">
                  {h.allDevices}
                </button>
              </div>
            </div>
          ) : (
            <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                <MonitorSmartphone className="h-5 w-5" {...ICON} aria-hidden />
              </div>
              <p className="min-w-0 flex-1 text-headline text-ink-2">{h.devicesEmpty}</p>
            </div>
          )}
        </section>

        <section id="places" data-home-section="places">
          <h2 className="text-title-2 text-ink">{h.places}</h2>
          {venuesLoading ? (
            <SkeletonRows />
          ) : venuesNotOpen ? (
            <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                <MapPin className="h-5 w-5" {...ICON} aria-hidden />
              </div>
              <p className="min-w-0 flex-1 text-headline text-ink-2">{h.stageNotOpen}</p>
            </div>
          ) : venuesError ? (
            <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                <MapPin className="h-5 w-5" {...ICON} aria-hidden />
              </div>
              <p className="min-w-0 flex-1 text-headline text-ink-2">{h.stageFailed}</p>
              <button type="button" onClick={() => void loadVenues()} className="btn-ghost inline-flex min-h-[44px] items-center">
                {h.retry}
              </button>
            </div>
          ) : venues && venues.length > 0 ? (
            <div className="mt-2">
              {venues.map((v, i) => {
                const addr =
                  v.location?.address ??
                  v.page?.blocks.find(
                    (b): b is Extract<typeof b, { type: 'address' }> => b.type === 'address',
                  )?.text ??
                  null;
                return (
                  <div key={v.id}>
                    {i > 0 && <div className="ml-14 border-t border-line" />}
                    <Link
                      to={`/build/edit/${encodeURIComponent(v.id)}`}
                      className="flex min-h-[64px] items-center gap-4 py-2 transition-colors hover:text-ink"
                    >
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                        <MapPin className="h-5 w-5" {...ICON} aria-hidden />
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-headline text-ink">{v.name}</p>
                        {addr && <p className="truncate text-subhead text-ink-2">{addr}</p>}
                      </div>
                      <span className="shrink-0 text-footnote text-ink-3">
                        {v.published ? h.published : h.draft}
                      </span>
                    </Link>
                  </div>
                );
              })}
              {venues.some((v) => v.published) && <ConnectPhone h={h} />}
            </div>
          ) : (
            <div className="mt-2 flex min-h-[64px] items-center gap-4 py-2">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
                <MapPin className="h-5 w-5" {...ICON} aria-hidden />
              </div>
              <p className="min-w-0 flex-1 text-headline text-ink-2">{h.stageEmpty}</p>
              <Link to="/build?new=1&who=place" className="btn-ghost inline-flex min-h-[44px] items-center">
                {h.createPlace}
              </Link>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
