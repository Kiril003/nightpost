import React, { useEffect, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { useNavigate } from 'react-router-dom';
import type { GeoPoint } from '../../types/messenger';
import { ageLabel, isStale, mapHref } from '../../services/messengerGeo';
import { soundFx } from '../../utils/messengerSound';
import { PlaceCard } from './cards/PlaceCard';

interface GeoPointBubbleProps {
  point: GeoPoint;
  /** Кадр приїхав зі скриньки — за законом №3 точка застаріла завжди. */
  viaMailbox?: boolean;
  isSelf?: boolean;
}

/**
 * Точка «я тут» у стрічці — картка місця (як заклад): назва, чесний вік виміру і «Маршрут». Координат
 * на екрані немає — це сирі числа; вони їдуть у мапу, а не в очі.
 *
 * Свіжість тут не намальована один раз при завантаженні: бульбашка сама
 * перевіряє годинник, поки точка ще жива, і гасне рівно тоді, коли їй
 * виповнюється 90 секунд. Інакше залишена відкритою вкладка обіцяла б живе
 * положення годинами.
 *
 * Мініатюри мапи тут немає навмисно: полотно малює ATLAS, а друга жива мапа в
 * стрічці порушила б закон «одна поверхня». Кнопка веде на повну.
 */
export const GeoPointBubble: React.FC<GeoPointBubbleProps> = ({ point, viaMailbox, isSelf }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [now, setNow] = useState(() => Date.now());
  const stale = isStale(point, { nowMs: now, viaMailbox });

  useEffect(() => {
    if (stale) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 10_000);
    return () => window.clearInterval(timer);
  }, [stale]);

  const title = point.label?.trim() || (isSelf ? t('geoPointBubble.mine') : t('geoPointBubble.place'));
  const meta = ageLabel(point, { nowMs: now, viaMailbox });

  const open = () => {
    soundFx.playTap();
    const bridge = typeof window !== 'undefined' && (window as unknown as { PhantomBridge?: { openMap?: (lat: number, lon: number, label?: string) => boolean } }).PhantomBridge;
    if (bridge && typeof bridge.openMap === 'function') {
      bridge.openMap(point.lat, point.lon, title);
    } else {
      navigate(mapHref(point));
    }
  };

  return (
    <div className={stale ? 'opacity-70' : undefined}>
      <PlaceCard testId="geo-point-bubble" title={title} status={meta} action={{ label: t('geoPointBubble.route'), onClick: open }} />
    </div>
  );
};
