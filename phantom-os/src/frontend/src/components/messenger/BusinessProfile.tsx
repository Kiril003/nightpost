/**
 * Бізнес-профіль (рендер 6): обличчя закладу, послуги й оплати — лише зі
 * сторінки, яку власник склав у «Білдері». Бронювань, команди й цифр сервер
 * ще не тримає, тож там чесно порожньо.
 */
import React, { useEffect, useState } from 'react';
import { BarChart3, CalendarDays, ChevronLeft, MapPin, Tag, Wallet } from 'lucide-react';
import { meetupApi, type VenuePage } from '../../services/meetupApi';
import { businessFaceOf, shortWallet } from '../../services/businessFace';
import { formatAmount, toUnits } from '../../services/meetup/split';
import { kindLabel, useProfiles } from '../../stores/profilesStore';
import type { VenueSnap } from '../../types/meetup';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';
import { BusinessCardEditor, BusinessPublicView, BusinessServicesEditor } from './BusinessCardEditor';
import { BusinessStaff } from './BusinessStaff';

const SHOWN = 3;

const Section: React.FC<{ title: string; testId: string; children: React.ReactNode }> = ({ title, testId, children }) => (
  <section data-testid={testId} className="flex flex-col gap-1">
    <h3 className="px-1 text-caption text-ink-3">{title}</h3>
    <div className="rounded-card bg-raised overflow-hidden">{children}</div>
  </section>
);

const Empty: React.FC<{ icon: React.ReactNode; text: string; children?: React.ReactNode }> = ({ icon, text, children }) => (
  <div className="px-4 py-5 flex flex-col items-center gap-2 text-center">
    <span className="text-ink-3">{icon}</span>
    <span className="text-body text-ink-2">{text}</span>
    {children}
  </div>
);

const iconCls = 'w-6 h-6';

export const BusinessProfile: React.FC<{ profileId: string; onBack: () => void }> = ({ profileId, onBack }) => {
  const { t } = useTranslation();
  const profile = useProfiles((s) => s.profiles.find((p) => p.id === profileId) ?? null);
  const venueId = profile?.venueId ?? null;
  const [page, setPage] = useState<VenuePage | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const [near, setNear] = useState<VenueSnap[] | null>(null);
  const [nearError, setNearError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [allServices, setAllServices] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    setPage(null);
    setPageError(null);
    if (!venueId) return;
    let live = true;
    meetupApi
      .venue(venueId)
      .then((p) => live && setPage(p))
      .catch((e: unknown) => live && setPageError(e instanceof Error ? e.message : String(e)));
    return () => {
      live = false;
    };
  }, [venueId, attempt]);

  if (!profile) return null;

  const face = page ? businessFaceOf(page) : null;
  const services = face?.services ?? [];
  const cardServices = profile.biz.services.length;
  const shown = allServices ? services : services.slice(0, SHOWN);

  const pickNear = async () => {
    soundFx.playTap();
    setBusy(true);
    setNearError(null);
    try {
      setNear(await meetupApi.near());
    } catch (e) {
      setNearError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const link = (id: string | null) => {
    soundFx.playTap();
    useProfiles.getState().update(profile.id, { venueId: id });
    setNear(null);
    setAllServices(false);
  };

  const title = face?.name ?? profile.name;
  const sub = [kindLabel(profile.kind), face?.address].filter(Boolean).join(' · ');

  return (
    <div data-testid="business-profile" className="px-4 pb-4 flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="business-back"
          aria-label={t('formSheet.back')}
          onClick={() => {
            soundFx.playTap();
            onBack();
          }}
          className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center text-ink"
        >
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
        </button>
        <span className="text-subhead text-ink">{t('business.open')}</span>
      </div>

      <div className="flex items-center gap-4">
        <span
          aria-hidden="true"
          className="w-16 h-16 shrink-0 rounded-card bg-overlay flex items-center justify-center text-title text-ink"
        >
          {title.slice(0, 1).toUpperCase()}
        </span>
        <div className="min-w-0 flex flex-col gap-1">
          <span data-testid="business-name" className="text-headline text-ink truncate">{title}</span>
          <span className="text-caption text-ink-2 truncate">{sub}</span>
          {face?.today && (
            <span data-testid="business-hours" className="text-caption text-ink-2">
              {face.openNow ? t('business.openUntil', { time: face.today.close }) : t('business.closed')}
            </span>
          )}
        </div>
      </div>

      <Section title={t('business.page')} testId="business-page">
        {venueId ? (
          <div className="min-h-12 px-4 py-2 flex items-center gap-3">
            <MapPin className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <div className="flex-1 min-w-0 flex flex-col">
              <span className="text-body text-ink truncate">{face?.name ?? t('business.page')}</span>
              {pageError ? (
                <span data-testid="business-page-error" className="text-caption text-danger">{pageError}</span>
              ) : (
                face && page?.lat !== null && <span className="text-caption text-ink-2">{t('business.onMap')}</span>
              )}
            </div>
            {pageError && (
              <button
                type="button"
                data-testid="business-retry"
                onClick={() => setAttempt((n) => n + 1)}
                className="h-11 min-h-0 px-3 rounded-control text-subhead text-ink"
              >
                {t('business.retry')}
              </button>
            )}
            <button
              type="button"
              data-testid="business-unlink"
              onClick={() => link(null)}
              className="h-11 min-h-0 px-3 rounded-control text-subhead text-ink-2"
            >
              {t('business.unlink')}
            </button>
          </div>
        ) : near ? (
          near.length === 0 ? (
            <Empty icon={<MapPin className={iconCls} strokeWidth={1.5} />} text={t('business.nearNone')} />
          ) : (
            <ul data-testid="business-near">
              {near.map((v) => (
                <li key={v.id}>
                  <button
                    type="button"
                    data-testid={`business-near-${v.id}`}
                    onClick={() => link(v.id)}
                    className="w-full min-h-12 px-4 py-2 flex flex-col text-left active:bg-overlay"
                  >
                    <span className="text-body text-ink">{v.name}</span>
                    {v.address && <span className="text-caption text-ink-2">{v.address}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )
        ) : (
          <Empty icon={<MapPin className={iconCls} strokeWidth={1.5} />} text={t('business.pageNone')}>
            {nearError && <span data-testid="business-near-error" className="text-caption text-danger">{nearError}</span>}
            <button
              type="button"
              data-testid="business-pick"
              disabled={busy}
              onClick={pickNear}
              className="h-11 min-h-0 px-4 rounded-control bg-accent text-on-accent text-subhead disabled:opacity-60"
            >
              {t('business.pick')}
            </button>
          </Empty>
        )}
      </Section>

      <Section title={t('business.services')} testId="business-services">
        {shown.length === 0 && cardServices === 0 && (
          <Empty icon={<Tag className={iconCls} strokeWidth={1.5} />} text={t('business.servicesNone')} />
        )}
        {shown.length > 0 && (
          <>
            {shown.map((s) => (
              <div key={s.id} className="min-h-12 px-4 py-2 flex items-center justify-between gap-3">
                <span className="text-body text-ink truncate">{s.name}</span>
                <span className="text-callout text-ink tnum shrink-0">{formatAmount(toUnits(s.price, s.token), s.token)}</span>
              </div>
            ))}
            {services.length > SHOWN && (
              <button
                type="button"
                data-testid="business-services-all"
                onClick={() => setAllServices((v) => !v)}
                className="w-full h-11 min-h-0 px-4 text-left text-subhead text-ink-2"
              >
                {allServices ? t('business.less') : t('business.all', { n: services.length })}
              </button>
            )}
          </>
        )}
        <BusinessServicesEditor profileId={profile.id} />
      </Section>

      <Section title={t('business.bookings')} testId="business-bookings">
        <Empty icon={<CalendarDays className={iconCls} strokeWidth={1.5} />} text={t('business.bookingsNone')} />
      </Section>

      <Section title={t('profilesR.biz.card')} testId="business-card">
        <BusinessCardEditor profileId={profile.id} />
      </Section>

      <Section title={t('profilesR.biz.view')} testId="business-view">
        <BusinessPublicView profileId={profile.id} />
      </Section>

      <Section title={t('profilesR.staff.title')} testId="business-team">
        <BusinessStaff profileId={profile.id} />
      </Section>

      <Section title={t('business.payments')} testId="business-payments">
        {face?.wallet ? (
          <div className="min-h-12 px-4 py-2 flex items-center gap-3">
            <Wallet className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <span className="flex-1 text-body text-ink">{t('business.wallet')}</span>
            <span className="text-callout text-ink-2 tnum">{shortWallet(face.wallet)}</span>
          </div>
        ) : (
          <Empty icon={<Wallet className={iconCls} strokeWidth={1.5} />} text={t('business.paymentsNone')} />
        )}
      </Section>

      <Section title={t('business.numbers')} testId="business-numbers">
        <Empty icon={<BarChart3 className={iconCls} strokeWidth={1.5} />} text={t('business.numbersNone')} />
      </Section>
    </div>
  );
};
