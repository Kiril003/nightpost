import React, { useEffect, useState } from 'react';
import { Tag } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { EmptyState } from '../kit/EmptyState';
import { meetupApi } from '../../../services/meetupApi';
import { businessFaceOf, type BusinessService } from '../../../services/businessFace';
import { formatAmount, toUnits } from '../../../services/meetup/split';
import { useActiveBusiness } from './useBusiness';

export const BusinessServices: React.FC<{ onLink: () => void }> = ({ onLink }) => {
  const { t } = useTranslation();
  const profile = useActiveBusiness();
  const venueId = profile?.venueId ?? null;
  const [services, setServices] = useState<BusinessService[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setServices(null);
    setFailed(false);
    if (!venueId) return;
    let live = true;
    meetupApi.venue(venueId).then(
      (p) => live && setServices(businessFaceOf(p).services),
      () => live && setFailed(true),
    );
    return () => {
      live = false;
    };
  }, [venueId]);

  if (!venueId) {
    return (
      <EmptyState
        testId="services-no-page"
        icon={<Tag className="w-6 h-6" strokeWidth={1.5} />}
        label={t('bizDir.services.noPage')}
        action={{ label: t('bizDir.services.link'), onClick: onLink }}
      />
    );
  }
  if (failed) return <p data-testid="services-error" className="px-6 pt-10 text-center text-body text-danger">{t('business.retry')}</p>;
  if (services === null) return null;
  if (services.length === 0) {
    return <EmptyState testId="services-empty" icon={<Tag className="w-6 h-6" strokeWidth={1.5} />} label={t('bizDir.services.none')} />;
  }
  return (
    <ul data-testid="business-services-list" className="flex-1 overflow-y-auto">
      {services.map((s) => (
        <li key={s.id} className="min-h-14 px-4 py-2 flex items-center justify-between gap-3 border-b border-line">
          <span className="text-body text-ink truncate">{s.name}</span>
          <span className="text-callout text-ink tnum shrink-0">{formatAmount(toUnits(s.price, s.token), s.token)}</span>
        </li>
      ))}
    </ul>
  );
};
