/**
 * «Моменти» у вкладці «Розмови»: шапка «Чати · Моменти», ряд історій зверху,
 * під ним стрічка. Бурштиновий «+» — єдина головна дія; порожньо — вона в тілі.
 */
import React, { useEffect, useRef, useState } from 'react';
import { Plus, SlidersHorizontal } from 'lucide-react';
import type { UserProfile } from '../../../types/messenger';
import { useTranslation } from '../../../i18n/useTranslation';
import { registerPhoneLayer } from '../../../stores/phoneLayerStore';
import { soundFx } from '../../../utils/messengerSound';
import { useEdgeStretch } from '../../../feel/useEdgeStretch';
import { ListMomentsStrip, hasListMoments, type ListMomentsData } from '../list/ListMoments';
import { composeMomentInHost } from '../MomentsStrip';
import { MomentsFeed } from '../MomentsFeed';
import { MomentsSettings } from '../MomentsSettings';
import { PaneTitles, type Pane } from './PaneTitles';

type Me = Pick<UserProfile, 'id' | 'name' | 'avatar'>;

const ICON_BTN = 'w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink active:bg-overlay';

export const MomentsHome: React.FC<{ data: ListMomentsData; me: Me; onPane: (p: Pane) => void; onOpenChat: (chatId: string) => void }> = ({ data, me, onPane, onOpenChat }) => {
  const { t } = useTranslation();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const stretchRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(stretchRef);
  const back = useRef(onPane);
  back.current = onPane;
  useEffect(() => registerPhoneLayer('moments:pane', true, () => back.current('chats')), []);
  const any = hasListMoments(data);
  const compose = () => {
    soundFx.playTap();
    composeMomentInHost();
  };
  return (
    <div data-moments-home className="flex-1 min-h-0 flex flex-col bg-canvas">
      <header className="shrink-0 h-14 pl-4 pr-2 flex items-center gap-1 bg-canvas">
        <PaneTitles pane="moments" onPick={onPane} />
        <span aria-hidden className="flex-1" />
        <button type="button" data-feed-settings aria-label={t('momentsR.settings')} onClick={() => setSettingsOpen(true)} className={ICON_BTN}>
          <SlidersHorizontal className="w-[22px] h-[22px]" strokeWidth={1.75} aria-hidden />
        </button>
        {any && (
          <button type="button" data-feed-compose aria-label={t('momentsF.new')} onClick={compose} className="touch-floor-inline w-10 h-10 mx-1 rounded-full flex items-center justify-center bg-accent text-on-accent">
            <Plus className="w-[22px] h-[22px]" strokeWidth={2} aria-hidden />
          </button>
        )}
      </header>
      <div ref={stretchRef} data-moments-scroll className="flex-1 min-h-0 overflow-y-auto no-scrollbar" style={{ paddingBottom: 'var(--sab)' }}>
        <ListMomentsStrip data={data} me={me} quiet />
        <MomentsFeed onCompose={compose} onOpenChat={onOpenChat} />
      </div>
      {settingsOpen && <MomentsSettings onClose={() => setSettingsOpen(false)} />}
    </div>
  );
};
