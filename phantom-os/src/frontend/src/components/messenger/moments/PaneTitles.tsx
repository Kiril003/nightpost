/** «Чати · Моменти» — два заголовки однієї вкладки «Розмови» (20-moments §1). */
import React from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import { soundFx } from '../../../utils/messengerSound';

export type Pane = 'chats' | 'moments';

export const PaneTitles: React.FC<{ pane: Pane; onPick: (p: Pane) => void; dot?: boolean }> = ({ pane, onPick, dot = false }) => {
  const { t } = useTranslation();
  const tab = (p: Pane, label: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={pane === p}
      data-pane-tab={p}
      onClick={() => {
        if (p === pane) return;
        soundFx.playTap();
        onPick(p);
      }}
      className={`relative shrink-0 min-h-0 min-w-0 h-11 p-0 text-[24px] leading-8 font-semibold ${pane === p ? 'text-ink' : 'text-ink-3'}`}
    >
      {label}
      {dot && p === 'moments' && pane !== 'moments' && <span data-pane-dot aria-hidden className="absolute -right-2.5 top-2 w-2 h-2 rounded-full bg-accent" />}
    </button>
  );
  return (
    <div role="tablist" aria-label={t('momentsF.panes')} data-pane-titles className="min-w-0 flex items-center gap-4">
      {tab('chats', t('momentsF.chats'))}
      {tab('moments', t('momentsF.moments'))}
    </div>
  );
};
