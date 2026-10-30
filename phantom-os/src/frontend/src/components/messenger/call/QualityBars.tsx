/**
 * Чотири смужки якості й «через посередника» — те, що людина бачить про
 * канал. Число смужок — головний знак; колір лише підтримує (одна смужка —
 * червона, дві — бурштин). Цифр RTT/втрат тут немає й не буде: вони живуть
 * у журналі рушія (закон власника 26.09 — «показувати щось гарне»).
 */

import React from 'react';
import type { CallStats } from '../../../services/callEngine';
import { useTranslation } from '../../../i18n/useTranslation';
import { readQuality } from './quality';

const HEIGHTS = [6, 9, 12, 15];

export const QualityBars: React.FC<{ stats: CallStats | null | undefined; className?: string }> = ({
  stats,
  className = '',
}) => {
  const { t } = useTranslation();
  const q = readQuality(stats);
  if (!q) return null;
  const lit = q.bars <= 1 ? 'var(--ph-danger)' : q.bars === 2 ? 'var(--ph-accent)' : 'currentColor';
  return (
    <span className={`inline-flex items-center gap-2 ${className}`}>
      <span
        data-call-quality
        data-bars={String(q.bars)}
        role="img"
        aria-label={t('call.quality.label', { bars: q.bars })}
        className="inline-flex items-end gap-[3px] h-[15px]"
      >
        {HEIGHTS.map((h, i) => {
          const on = i < q.bars;
          return (
            <span
              key={h}
              data-bar-on={on ? 'yes' : 'no'}
              className="w-[3px] rounded-full transition-colors duration-standard"
              style={{
                height: h,
                background: on ? lit : 'color-mix(in srgb, currentColor 28%, transparent)',
              }}
            />
          );
        })}
      </span>
      {q.relay && (
        <span data-call-relay className="text-footnote opacity-80">
          {t('call.quality.relay')}
        </span>
      )}
    </span>
  );
};
