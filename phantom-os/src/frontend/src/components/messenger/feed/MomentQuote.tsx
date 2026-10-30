import React from 'react';
import { CircleDashed } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMoments } from '../../../services/moments';
import type { MomentQuoteRef } from '../../../services/momentReply';
import { openMomentInHost } from '../MomentsStrip';

/** Цитата моменту над відповіддю; живий момент відкривається дотиком, зниклий — чесний підпис. */
export const MomentQuote: React.FC<{ quote: MomentQuoteRef; skin: React.CSSProperties }> = ({ quote, skin }) => {
  const { t } = useTranslation();
  const live = useMoments((s) => !!s.list?.some((m) => m.id === quote.id && (m.keep || m.untilMs > Date.now())));
  const label = quote.caption || (quote.kind === 'video' ? t('momentsX.video') : quote.kind === 'photo' ? t('momentsX.photo') : t('momentsX.quote'));
  return (
    <button
      type="button"
      data-testid="msg-moment-quote"
      disabled={!live}
      onClick={(e) => {
        e.stopPropagation();
        if (live) openMomentInHost(quote.id);
      }}
      className="mb-1 w-full min-h-0 py-1.5 pl-2.5 pr-3 rounded-control border-l-2 text-left disabled:cursor-default"
      style={skin}
    >
      <span className="flex items-center gap-1.5 text-footnote font-semibold" style={{ color: skin.color }}>
        <CircleDashed className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden />
        <span>{t('momentsX.quote')}</span>
      </span>
      <span className="block truncate text-footnote text-ink-2">{live ? label : `${label} · ${t('momentsX.gone')}`}</span>
    </button>
  );
};
