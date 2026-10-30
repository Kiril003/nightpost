import React, { useMemo } from 'react';
import { terrain, terrainSeed } from '../../services/terrain';

/**
 * Малий рельєф місця для картки «Місце» (ідентичність 02.10): ізолінії поля,
 * засіяного самою точкою, і знак шпильки в центрі. Це не мапа й не видає себе
 * за неї — це підпис місця.
 */
export const PlaceRelief: React.FC<{ lat: number; lon: number }> = ({ lat, lon }) => {
  const t = useMemo(() => terrain(terrainSeed(lat * 10, lon * 10), 280, 112, 7, 12), [lat, lon]);
  return (
    <div aria-hidden data-place-relief className="relative h-28 w-full overflow-hidden bg-raised text-ink">
      <svg viewBox={`0 0 ${t.width} ${t.height}`} preserveAspectRatio="xMidYMid slice" width="100%" height="100%">
        <path d={t.lines} fill="none" stroke="currentColor" strokeOpacity={0.14} strokeWidth={0.7} vectorEffect="non-scaling-stroke" />
        <path d={t.index} fill="none" stroke="currentColor" strokeOpacity={0.24} strokeWidth={1} vectorEffect="non-scaling-stroke" />
      </svg>
      <svg viewBox="0 0 24 24" className="absolute left-1/2 top-1/2 w-7 h-7 -translate-x-1/2 -translate-y-[85%] text-accent-line" aria-hidden>
        <path d="M12 22s-7-6.2-7-12a7 7 0 0 1 14 0c0 5.8-7 12-7 12z" fill="currentColor" />
        <circle cx="12" cy="10" r="2.6" fill="var(--ph-canvas)" />
      </svg>
    </div>
  );
};
