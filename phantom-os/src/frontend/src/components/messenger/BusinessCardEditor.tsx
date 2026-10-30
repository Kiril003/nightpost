import React from 'react';
import { Plus, X } from 'lucide-react';
import { BIZ_MAX, isHhmm, useProfiles, type BizCard } from '../../stores/profilesStore';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';
import { HoursRows } from './HoursRows';

const INPUT = 'h-11 px-3 rounded-control bg-canvas border border-line text-body text-ink min-w-0';

export const BusinessCardEditor: React.FC<{ profileId: string }> = ({ profileId }) => {
  const { t } = useTranslation();
  const biz = useProfiles((s) => s.profiles.find((p) => p.id === profileId)?.biz);
  if (!biz) return null;
  const set = (patch: Partial<BizCard>) =>
    useProfiles.getState().update(profileId, { biz: { ...biz, ...patch } });
  const field = (key: 'category' | 'address' | 'phone' | 'email', label: string, type = 'text') => (
    <label className="flex flex-col gap-1" key={key}>
      <span className="text-caption text-ink-2">{label}</span>
      <input
        type={type}
        data-testid={`biz-${key}`}
        value={biz[key]}
        maxLength={BIZ_MAX[key]}
        onChange={(e) => set({ [key]: e.target.value } as Partial<BizCard>)}
        className={INPUT}
      />
    </label>
  );

  return (
    <div data-testid="business-card-editor" className="px-4 py-3 flex flex-col gap-3">
      {field('category', t('profilesR.biz.category'))}
      {field('address', t('profilesR.biz.address'))}
      <button
        type="button"
        role="switch"
        data-testid="biz-hours-toggle"
        aria-checked={Boolean(biz.hours)}
        onClick={() => {
          soundFx.playTap();
          set({ hours: biz.hours ? null : { from: '09:00', to: '18:00' } });
        }}
        className="min-h-11 px-3 rounded-control bg-canvas flex items-center justify-between text-left"
      >
        <span className="text-body text-ink">{t('profilesR.biz.hoursOn')}</span>
        <span className="text-subhead text-ink-2 tnum">
          {biz.hours ? `${biz.hours.from}–${biz.hours.to}` : '—'}
        </span>
      </button>
      {biz.hours && (
        <HoursRows
          testId="biz-hours"
          from={biz.hours.from}
          to={biz.hours.to}
          fromLabel={t('profilesR.biz.hoursFrom')}
          toLabel={t('profilesR.biz.hoursTo')}
          onChange={(from, to) => isHhmm(from) && isHhmm(to) && set({ hours: { from, to } })}
        />
      )}
      {field('phone', t('profilesR.biz.phone'), 'tel')}
      {field('email', t('profilesR.biz.email'), 'email')}

      <span data-testid="biz-device-only" className="text-caption text-ink-3">
        {t('profilesR.biz.deviceOnly')}
      </span>
    </div>
  );
};


/** Єдиний список послуг профілю: картка на пристрої; послуги сторінки закладу додає BusinessProfile зверху. */
export const BusinessServicesEditor: React.FC<{ profileId: string }> = ({ profileId }) => {
  const { t } = useTranslation();
  const biz = useProfiles((s) => s.profiles.find((p) => p.id === profileId)?.biz);
  if (!biz) return null;
  const set = (patch: Partial<BizCard>) =>
    useProfiles.getState().update(profileId, { biz: { ...biz, ...patch } });
  return (
      <div data-testid="biz-services-editor" className="px-4 py-3 flex flex-col gap-2">
        {biz.services.map((s) => (
          <div key={s.id} data-testid={`biz-svc-${s.id}`} className="flex gap-2">
            <input
              type="text"
              aria-label={t('profilesR.biz.svcName')}
              placeholder={t('profilesR.biz.svcName')}
              value={s.name}
              maxLength={BIZ_MAX.svcName}
              onChange={(e) =>
                set({ services: biz.services.map((x) => (x.id === s.id ? { ...x, name: e.target.value } : x)) })
              }
              className={`${INPUT} flex-1`}
            />
            <input
              type="text"
              inputMode="decimal"
              aria-label={t('profilesR.biz.svcPrice')}
              value={s.price}
              maxLength={BIZ_MAX.svcPrice}
              onChange={(e) =>
                set({ services: biz.services.map((x) => (x.id === s.id ? { ...x, price: e.target.value } : x)) })
              }
              className={`${INPUT} w-24`}
            />
            <button
              type="button"
              data-testid={`biz-svc-remove-${s.id}`}
              aria-label={t('profilesR.biz.svcRemove')}
              onClick={() => {
                soundFx.playTap();
                set({ services: biz.services.filter((x) => x.id !== s.id) });
              }}
              className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink-3"
            >
              <X className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
            </button>
          </div>
        ))}
        <button
          type="button"
          data-testid="biz-svc-add"
          onClick={() => {
            soundFx.playTap();
            set({
              services: [
                ...biz.services,
                { id: `s${Date.now().toString(36)}${biz.services.length}`, name: '', price: '' },
              ],
            });
          }}
          className="h-11 min-h-0 px-3 rounded-control bg-overlay text-subhead text-ink flex items-center justify-center gap-2"
        >
          <Plus className="w-4 h-4" strokeWidth={1.9} aria-hidden="true" />
          {t('profilesR.biz.svcAdd')}
        </button>
      </div>
  );
};

/** Перегляд картки: лише заповнені поля, порожнього не вигадуємо. */
export const BusinessPublicView: React.FC<{ profileId: string }> = ({ profileId }) => {
  const { t } = useTranslation();
  const profile = useProfiles((s) => s.profiles.find((p) => p.id === profileId));
  if (!profile) return null;
  const { biz } = profile;
  const lines = [
    biz.category,
    biz.address,
    biz.hours && isHhmm(biz.hours.from) && isHhmm(biz.hours.to) ? `${biz.hours.from}–${biz.hours.to}` : '',
    biz.phone,
    biz.email,
  ].filter(Boolean);
  const empty = lines.length === 0 && biz.services.length === 0;
  return (
    <div data-testid="business-public-view" className="px-4 py-3 flex flex-col gap-1">
      <span className="text-headline text-ink">{profile.name}</span>
      {empty && <span className="text-body text-ink-2">{t('profilesR.biz.viewEmpty')}</span>}
      {lines.map((l) => (
        <span key={l} className="text-body text-ink-2">{l}</span>
      ))}
      {biz.services.map((s) => (
        <div key={s.id} className="flex justify-between gap-3">
          <span className="text-body text-ink truncate">{s.name || t('profilesR.biz.svcName')}</span>
          <span className="text-callout text-ink tnum shrink-0">{s.price}</span>
        </div>
      ))}
    </div>
  );
};
