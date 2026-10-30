/** «Архів»: власні моменти старші за добу, лише на цьому пристрої. Медіа вузол не зберігає — лишається мініатюра. */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Archive, Lock, Play } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { archived, useArchive, type ArchivedMoment } from '../../services/momentsArchive';
import { TopBar } from './kit/TopBar';
import { circleLabel } from './MomentViewer';

const monthKey = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth()).padStart(2, '0')}`;
};

const ArchiveTile: React.FC<{ m: ArchivedMoment; onOpen: () => void }> = ({ m, onOpen }) => (
  <li>
    <button
      type="button"
      data-archive-tile={m.id}
      onClick={onOpen}
      aria-label={new Date(m.atMs).toLocaleDateString()}
      className="relative block w-full aspect-[3/4] min-h-0 min-w-0 p-0 rounded-[12px] overflow-hidden bg-raised"
    >
      {m.thumb && <img src={m.thumb} alt="" draggable={false} className="absolute inset-0 w-full h-full object-cover" />}
      {!m.thumb && m.text && <span className="absolute inset-0 p-2 text-footnote text-ink-2 text-left overflow-hidden">{m.text}</span>}
      <span className="absolute left-1.5 top-1.5 min-w-6 h-6 px-1.5 rounded-full bg-canvas text-footnote text-ink flex items-center justify-center">{new Date(m.atMs).getDate()}</span>
      {m.kind === 'video' && <Play className="absolute right-1.5 top-1.5 w-4 h-4 text-white" strokeWidth={2} aria-hidden />}
    </button>
  </li>
);

export const MomentsArchive: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t, locale } = useTranslation();
  const items = useArchive((s) => s.items);
  const forget = useArchive((s) => s.forget);
  const [open, setOpen] = useState<string | null>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('moments:archive', true, () => closeRef.current()), []);
  useEffect(() => useArchive.getState().load(), []);
  const list = useMemo(() => archived(items), [items]);
  const groups = useMemo(() => {
    const out: { key: string; label: string; rows: ArchivedMoment[] }[] = [];
    for (const m of list) {
      const key = monthKey(m.atMs);
      let g = out.find((x) => x.key === key);
      if (!g) {
        g = { key, label: new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(new Date(m.atMs)), rows: [] };
        out.push(g);
      }
      g.rows.push(m);
    }
    return out;
  }, [list, locale]);
  const current = open ? list.find((x) => x.id === open) ?? null : null;
  const audience = (m: ArchivedMoment) =>
    m.reach === 'me' ? t('moments.rungMe')
      : m.reach === 'circles' && m.circles.length > 0 ? m.circles.map((c) => circleLabel(t, c)).join(', ')
        : m.reach === 'contacts' ? t('moments.rungContacts') : t('moments.rungPeople');

  return createPortal(
    <div data-moments-archive role="dialog" aria-modal="true" aria-label={t('momentsR.archive')} className="fixed inset-0 z-[70] bg-canvas flex flex-col" style={{ backgroundColor: 'var(--ph-canvas)' }}>
      <TopBar
        title={t('momentsR.archive')}
        onBack={onClose}
        backLabel={t('moments.back')}
        safeTop
        trailing={
          <span className="ml-auto mr-3 h-9 px-3 rounded-full bg-raised inline-flex items-center gap-1.5 text-footnote text-ink-2">
            <Lock className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            {t('momentsR.deviceOnly')}
          </span>
        }
      />
      <div className="flex-1 min-h-0 overflow-y-auto px-4 pb-6">
        {groups.length === 0 && (
          <div data-archive-empty className="pt-[18vh] flex flex-col items-center gap-3 text-center">
            <span className="w-12 h-12 rounded-[12px] bg-raised flex items-center justify-center">
              <Archive className="w-6 h-6 text-ink-2" strokeWidth={1.75} aria-hidden />
            </span>
            <p className="text-headline text-ink">{t('momentsR.archiveEmpty')}</p>
          </div>
        )}
        {groups.map((g) => (
          <section key={g.key} className="mt-3">
            <h3 className="mb-2 text-subhead text-ink-2 capitalize">{g.label}</h3>
            <ul className="grid grid-cols-3 gap-2">
              {g.rows.map((m) => (
                <ArchiveTile key={m.id} m={m} onOpen={() => setOpen(m.id)} />
              ))}
            </ul>
          </section>
        ))}
      </div>
      {current && (
        <div className="absolute inset-0 z-10 flex flex-col justify-end" style={{ backgroundColor: 'var(--np-moment-label)' }} onClick={() => setOpen(null)}>
          <div
            role="dialog"
            aria-label={t('momentsR.archive')}
            data-archive-sheet
            className="rounded-t-[16px] px-4 pt-4"
            style={{ backgroundColor: 'var(--ph-canvas)', paddingBottom: 'max(16px, env(safe-area-inset-bottom))' }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-title-2 text-ink">{new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long' }).format(new Date(current.atMs))}</h3>
            <p className="mt-1 text-subhead font-normal text-ink-3">{audience(current)}</p>
            {current.text && <p className="mt-3 text-body text-ink">{current.text}</p>}
            {current.kind !== 'text' && <p className="mt-2 text-footnote text-ink-3">{t('momentsR.thumbOnly')}</p>}
            <button
              type="button"
              data-archive-forget
              onClick={() => {
                forget(current.id);
                setOpen(null);
              }}
              className="mt-4 w-full h-12 min-h-0 rounded-full bg-raised text-body text-danger"
            >
              {t('momentsR.forget')}
            </button>
          </div>
        </div>
      )}
    </div>,
    document.body,
  );
};

export default MomentsArchive;
