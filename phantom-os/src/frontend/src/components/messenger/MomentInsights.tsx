/** «Хто бачив» для власного моменту: ті, кого вузол записав як глядачів, їхні смайли й час. Без вигаданих лічильників. */
import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Trash2 } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { queueRemoval } from '../../services/momentsUndo';
import type { Moment } from '../../services/moments';
import { Avatar } from './Avatar';
import { TopBar } from './kit/TopBar';
import { audienceLine } from './MomentViewer';
import { todayTimeLabel } from './todayFormat';

export const MomentInsights: React.FC<{ m: Moment; avatarOf: (id: string) => string | undefined; onClose: () => void }> = ({ m, avatarOf, onClose }) => {
  const { t } = useTranslation();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('moments:insights', true, () => closeRef.current()), []);
  const reacted = Object.values(m.reactions).reduce((a, b) => a + b, 0) || (m.myReaction ? 1 : 0);
  const thumb = m.media[0]?.thumb;
  return createPortal(
    <div data-moment-insights={m.id} role="dialog" aria-modal="true" aria-label={t('moments.seenBy')} className="fixed inset-0 z-[1250] bg-canvas flex flex-col" style={{ backgroundColor: 'var(--ph-canvas)', border: 0, boxShadow: 'none' }}>
      <TopBar title={t('momentsR.insightsTitle')} onBack={onClose} backLabel={t('moments.back')} safeTop />
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="px-4 py-3 flex items-center gap-3">
          {thumb && <img src={thumb} alt="" className="w-14 h-14 rounded-[12px] object-cover bg-raised" />}
          <div className="min-w-0">
            {m.text && <p className="truncate text-headline text-ink">{m.text}</p>}
            <p className="truncate text-footnote text-ink-3">{audienceLine(t, m)}</p>
          </div>
        </div>
        <div className="px-4 grid grid-cols-2 gap-3">
          <div data-insight-seen className="rounded-[16px] border border-line-strong px-4 py-3">
            <p className="text-title-2 text-ink tnum">{m.toCount > 0 ? t('momentsR.seenOf', { n: m.viewers.length, total: m.toCount }) : m.viewers.length}</p>
            <p className="text-footnote text-ink-3">{t('moments.seenBy')}</p>
          </div>
          <div data-insight-reactions className="rounded-[16px] border border-line-strong px-4 py-3">
            <p className="text-title-2 text-ink tnum">{reacted}</p>
            <p className="text-footnote text-ink-3">{t('momentsR.reactionsWord')}</p>
          </div>
        </div>
        {m.viewers.length === 0 ? (
          <p data-insight-empty className="px-4 pt-6 text-subhead font-normal text-ink-3">{t('moments.noViewers')}</p>
        ) : (
          <ul data-insight-viewers className="mt-3">
            {m.viewers.map((v) => (
              <li key={v.id} data-insight-viewer={v.id} className="h-16 px-4 flex items-center gap-3">
                <Avatar src={avatarOf(v.id)} name={v.name || t('chatList.unnamed')} tone={v.id} className="w-10 h-10 text-subhead" />
                <span className="flex-1 min-w-0 truncate text-body text-ink">{v.name || t('chatList.unnamed')}</span>
                {v.emoji && <span className="text-[20px] leading-none" aria-label={v.emoji}>{v.emoji}</span>}
                <span className="shrink-0 text-footnote text-ink-3 tnum">{todayTimeLabel(new Date(v.atMs).toISOString())}</span>
              </li>
            ))}
          </ul>
        )}
        <button
          type="button"
          data-insight-delete
          onClick={() => {
            void queueRemoval(m, false);
            onClose();
          }}
          className="mt-4 w-full h-14 min-h-0 px-4 flex items-center gap-3 text-body text-danger"
        >
          <Trash2 className="w-6 h-6" strokeWidth={1.75} aria-hidden />
          {t('moments.delete')}
        </button>
      </div>
    </div>,
    document.body,
  );
};
