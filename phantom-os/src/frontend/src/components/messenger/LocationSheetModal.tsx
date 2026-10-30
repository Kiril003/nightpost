import React, { useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { ChevronDown, ChevronUp, Clock, ExternalLink, MapPin, Navigation } from 'lucide-react';
import type { LocationData } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { blockedPresent, useBlockedIds } from '../../services/blockedPresent';
import { FormSheet, PrimaryButton } from './FormSheet';

/**
 * Картка місця — аркуш форми (DS §7.7), 03.10 мовою v1.2.
 *
 * Одна дія — «Маршрут»: відкриває мапу мостом на телефоні або OSM на ПК.
 * Амбра — лише на ній; решта — чорнило й поверхні теми.
 *
 * Чого тут більше немає і чому (CLAUDE.md: «нічого не вигадувати»):
 * «Через» казав «додано до маршруту» й нічого не додавав; «Зберегти»
 * перемикав місцевий прапорець, що зникав разом з аркушем; «Надіслати
 * картку в бесіду» і «Збір тут» лише закривали аркуш — MessengerRoot не
 * передає їм обробників; «Маршрут побудовано» звучав щоразу, хоч нічого не
 * будувалось.
 *
 * Порожні поля не малюють порожніх рядків: «Де він» з картки пристрою
 * (DeviceSheet) відкриває місце з порожніми категорією й адресою.
 */
interface LocationSheetModalProps {
  location: LocationData | null;
  isOpen: boolean;
  onClose: () => void;
  groupMemberIds?: string[];
}

type MapBridge = { openMap?: (lat: number, lon: number, label?: string) => boolean };

const openRoute = (loc: LocationData) => {
  const { lat, lng } = loc.coords;
  const bridge =
    typeof window !== 'undefined' ? (window as unknown as { PhantomBridge?: MapBridge }).PhantomBridge : undefined;
  if (bridge && typeof bridge.openMap === 'function') {
    bridge.openMap(lat, lng, loc.name);
    return;
  }
  window.open(
    `https://www.openstreetmap.org/directions?engine=fossgis_osrm_car&route=${encodeURIComponent(lat)},${encodeURIComponent(lng)}`,
    '_blank',
    'noopener',
  );
};

const InfoRow: React.FC<{ icon: React.ReactNode; label: string; children: React.ReactNode }> = ({ icon, label, children }) => (
  <div className="min-h-12 py-2 flex items-center justify-between gap-4 border-t border-line first:border-t-0">
    <span className="flex items-center gap-2 text-callout text-ink-2 shrink-0">
      {icon}
      {label}
    </span>
    <span className="text-callout text-ink text-right min-w-0 truncate">{children}</span>
  </div>
);

export const LocationSheetModal: React.FC<LocationSheetModalProps> = ({ location, isOpen, onClose, groupMemberIds }) => {
  const { t } = useTranslation();
  const [dossierOpen, setDossierOpen] = useState(true);
  const blockedIds = useBlockedIds(isOpen && !!groupMemberIds?.length);

  // Escape виводить із шару так само, як хрестик.
  useEscapeClose(isOpen, onClose);

  if (!isOpen || !location) return null;

  const blockedHere = !!groupMemberIds?.length && blockedPresent(groupMemberIds, blockedIds);
  const category = location.category?.trim() ?? '';
  const address = location.address?.trim() ?? '';
  const hours = (location.dossier?.bestHours || location.hours || '').trim();
  const lat = location.coords?.lat;
  const lng = location.coords?.lng;
  // Без координат «Маршрут» нічого б не відкрив — тоді його й нема.
  const canRoute = Number.isFinite(lat) && Number.isFinite(lng) && !(lat === 0 && lng === 0);

  const dossier = location.dossier;
  const tags = (dossier?.atmosphere ?? []).filter((t) => t.trim());
  const hasDossier =
    !!dossier && !!(dossier.vibe || dossier.crowdLevel || dossier.transitTips || dossier.recommendations || tags.length > 0);

  const ico = 'w-5 h-5 text-ink-3 shrink-0';

  return (
    <FormSheet
      open
      onClose={onClose}
      title={location.name}
      testId="place-sheet"
      footer={
        canRoute ? (
          <div data-place-route>
            <PrimaryButton
              onClick={() => {
                soundFx.playTap();
                openRoute(location);
              }}
              className="flex items-center justify-center gap-2"
            >
              <Navigation className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
              {t('locationSheetModal.route')}
            </PrimaryButton>
          </div>
        ) : undefined
      }
    >
      <div className="px-4 pb-4">
        {blockedHere && (
          <p data-blocked-present className="pb-3 text-footnote text-ink-2">
            {t('chats24.blockedPresent')}
          </p>
        )}
        {category && (
          <div className="flex items-center gap-3" data-testid="place-category">
            <span className="w-12 h-12 rounded-card bg-surface flex items-center justify-center shrink-0" aria-hidden="true">
              <MapPin className="w-6 h-6 text-ink-2" strokeWidth={1.75} />
            </span>
            <p className="min-w-0 text-callout text-ink-2 break-words">{category}</p>
          </div>
        )}

        {(hours || address) && (
          <div className={category ? 'mt-4' : ''}>
            {hours && (
              <InfoRow icon={<Clock className={ico} strokeWidth={1.75} aria-hidden="true" />} label={t('locationSheetModal.hours')}>
                {hours}
              </InfoRow>
            )}
            {address && (
              <InfoRow icon={<MapPin className={ico} strokeWidth={1.75} aria-hidden="true" />} label={t('locationSheetModal.address')}>
                {address}
              </InfoRow>
            )}
          </div>
        )}

        {/* Про місце — лише поля, що прийшли разом із карткою. */}
        {hasDossier && (
          <section className="mt-4 rounded-card bg-surface">
            <button
              type="button"
              onClick={() => setDossierOpen((v) => !v)}
              aria-expanded={dossierOpen}
              className="w-full min-h-12 px-4 flex items-center justify-between gap-3 text-left"
            >
              <span className="text-headline text-ink">{t('locationSheetModal.about')}</span>
              {dossierOpen ? (
                <ChevronUp className="w-5 h-5 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
              ) : (
                <ChevronDown className="w-5 h-5 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
              )}
            </button>
            {dossierOpen && (
              <div className="px-4 pb-4 space-y-3">
                {dossier?.vibe && <p className="text-body text-ink">{dossier.vibe}</p>}
                {tags.length > 0 && (
                  <ul className="flex flex-wrap gap-1.5">
                    {tags.map((tag, i) => (
                      <li key={`${tag}-${i}`} className="px-2.5 py-1 rounded-full bg-raised text-footnote text-ink-2">
                        {tag}
                      </li>
                    ))}
                  </ul>
                )}
                {dossier?.crowdLevel && (
                  <div>
                    <p className="text-footnote text-ink-3">{t('locationSheetModal.crowd')}</p>
                    <p className="text-callout text-ink-2">{dossier.crowdLevel}</p>
                  </div>
                )}
                {dossier?.transitTips && (
                  <div>
                    <p className="text-footnote text-ink-3">{t('locationSheetModal.access')}</p>
                    <p className="text-callout text-ink-2">{dossier.transitTips}</p>
                  </div>
                )}
                {dossier?.recommendations && (
                  <p className="p-3 rounded-control bg-raised text-callout text-ink-2">
                    <span className="text-ink font-semibold">{t('locationSheetModal.tip')} </span>
                    {dossier.recommendations}
                  </p>
                )}
              </div>
            )}
          </section>
        )}

        {/* Посилання на карту — лише коли є адреса, за якою шукати. */}
        {address && (
          <div className="mt-4">
            <p className="text-footnote text-ink-3">{t('locationSheetModal.onMap')}</p>
            <a
              href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${location.name} ${address}`)}`}
              target="_blank"
              rel="noreferrer"
              className="mt-1 inline-flex items-center gap-1.5 min-h-11 text-callout text-ink underline decoration-line-strong underline-offset-4"
            >
              <span className="break-words">{address}</span>
              <ExternalLink className="w-4 h-4 text-ink-3 shrink-0" strokeWidth={1.75} aria-hidden="true" />
            </a>
          </div>
        )}
      </div>
    </FormSheet>
  );
};
