/** «Мої моменти» у профілі: сітка живих і залишених, вхід у архів, налаштування й новий момент. */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Archive, Image as ImageIcon, Plus, Settings } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { momentHighlights, useMoments, watchMoments, type Moment } from '../../services/moments';
import { useEdgeStretch } from '../../feel/useEdgeStretch';
import { TopBar } from './kit/TopBar';
import { TextCard, isVideo, momentPeople, useMomentMedia, MomentViewer } from './MomentViewer';
import { MomentComposer } from './MomentsStrip';
import { MomentsArchive } from './MomentsArchive';
import { MomentsSettings } from './MomentsSettings';
import { MomentUndoToast } from './MomentUndoToast';

const OwnTile: React.FC<{ m: Moment; onOpen: () => void }> = ({ m, onOpen }) => {
  const { t } = useTranslation();
  const { url } = useMomentMedia(m);
  const src = url ?? m.media[0]?.thumb ?? null;
  return (
    <li>
      <button
        type="button"
        data-own-tile={m.id}
        onClick={onOpen}
        aria-label={t('moments.open', { name: t('moments.you') })}
        className="relative block w-full aspect-[3/4] min-h-0 min-w-0 p-0 rounded-[12px] overflow-hidden bg-raised"
      >
        {m.media.length === 0 && <TextCard text={m.text} tone={m.tone} small />}
        {src && !isVideo(m) && <img src={src} alt="" draggable={false} className="absolute inset-0 w-full h-full object-cover" />}
        {url && isVideo(m) && <video src={url} muted playsInline preload="metadata" className="absolute inset-0 w-full h-full object-cover" />}
      </button>
    </li>
  );
};

export const MomentsProfile: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t } = useTranslation();
  const stretchRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(stretchRef);
  const list = useMoments((s) => s.list);
  const chats = useMessengerStore((s) => s.chats);
  const me = useMessengerStore((s) => s.currentUser);
  const [kept, setKept] = useState<Moment[]>([]);
  const [composing, setComposing] = useState(false);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [viewing, setViewing] = useState<string | null>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('moments:profile', true, () => closeRef.current()), []);
  useEffect(() => watchMoments(), []);
  useEffect(() => {
    let alive = true;
    momentHighlights('me').then((rows) => alive && setKept(Array.isArray(rows) ? rows : []), () => undefined);
    return () => {
      alive = false;
    };
  }, [list]);

  const mine = useMemo(() => (list ?? []).filter((m) => m.mine).sort((a, b) => b.atMs - a.atMs), [list]);
  const keptOnly = useMemo(() => kept.filter((k) => !mine.some((m) => m.id === k.id)).sort((a, b) => b.atMs - a.atMs), [kept, mine]);
  const all = useMemo(() => [...mine, ...keptOnly], [mine, keptOnly]);
  const people = useMemo(() => momentPeople(chats), [chats]);

  const iconBtn = 'w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink';
  const grid = (rows: Moment[]) => (
    <ul className="grid grid-cols-3 gap-2">
      {rows.map((m) => (
        <OwnTile key={m.id} m={m} onOpen={() => setViewing(m.id)} />
      ))}
    </ul>
  );

  return createPortal(
    <div data-moments-profile role="dialog" aria-modal="true" aria-label={t('momentsR.mine')} className="fixed inset-0 z-[60] bg-canvas flex flex-col" style={{ backgroundColor: 'var(--ph-canvas)' }}>
      <TopBar
        title={t('momentsR.mine')}
        onBack={onClose}
        backLabel={t('moments.back')}
        backProps={{ 'data-moments-profile-back': true }}
        safeTop
        trailing={
          <span className="ml-auto flex items-center">
            <button type="button" data-profile-archive aria-label={t('momentsR.archive')} onClick={() => setArchiveOpen(true)} className={iconBtn}>
              <Archive className="w-6 h-6" strokeWidth={1.75} aria-hidden />
            </button>
            <button type="button" data-profile-settings aria-label={t('momentsR.settings')} onClick={() => setSettingsOpen(true)} className={iconBtn}>
              <Settings className="w-6 h-6" strokeWidth={1.75} aria-hidden />
            </button>
            <button type="button" data-profile-compose aria-label={t('moments.newTitle')} onClick={() => setComposing(true)} className={`${iconBtn} bg-ink text-canvas mr-2`}>
              <Plus className="w-6 h-6" strokeWidth={2} aria-hidden />
            </button>
          </span>
        }
      />
      <div ref={stretchRef} className="flex-1 min-h-0 overflow-y-auto px-4 pb-6">
        {all.length === 0 && (
          <div data-profile-empty className="pt-[18vh] flex flex-col items-center gap-3 text-center">
            <span className="w-12 h-12 rounded-[12px] bg-raised flex items-center justify-center">
              <ImageIcon className="w-6 h-6 text-ink-2" strokeWidth={1.75} aria-hidden />
            </span>
            <p className="text-headline text-ink">{t('momentsR.empty')}</p>
            <button type="button" data-profile-first onClick={() => setComposing(true)} className="mt-2 h-12 px-6 rounded-full bg-accent text-on-accent text-headline">
              {t('momentsR.first')}
            </button>
          </div>
        )}
        {mine.length > 0 && (
          <section className="mt-3">
            <h3 className="mb-2 text-subhead text-ink-2">{t('momentsR.live')}</h3>
            {grid(mine)}
          </section>
        )}
        {keptOnly.length > 0 && (
          <section className="mt-4">
            <h3 className="mb-2 text-subhead text-ink-2">{t('momentsR.kept')}</h3>
            {grid(keptOnly)}
          </section>
        )}
      </div>
      {viewing && (
        <MomentViewer
          moments={all}
          startId={viewing}
          nameOf={() => t('moments.you')}
          avatarOf={() => me.avatar || undefined}
          chatOf={() => undefined}
          onOpenChat={() => setViewing(null)}
          onClose={() => setViewing(null)}
        />
      )}
      {composing && <MomentComposer file={null} people={people} chats={chats} onClose={() => setComposing(false)} />}
      {archiveOpen && <MomentsArchive onClose={() => setArchiveOpen(false)} />}
      {settingsOpen && <MomentsSettings onClose={() => setSettingsOpen(false)} />}
      <MomentUndoToast />
    </div>,
    document.body,
  );
};

export default MomentsProfile;
