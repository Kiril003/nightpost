/**
 * Шапка вибору розмов (list-fix C2): X · «Вибрано: N» · закріпити ·
 * видалити · надіслати · ⋮ (без звуку, прочитано, в архів, усі дії однієї розмови).
 * Дія, що не стосується вибраного, вимкнена, а не схована: місця сталі.
 */
import React, { useState } from 'react';
import { Archive, ArchiveRestore, Bell, BellOff, CheckCheck, MoreVertical, Pin, PinOff, Send, Settings2, Trash2, X } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { soundFx } from '../../../utils/messengerSound';
import type { SelectionCaps } from './listSelection';

const ICON = { strokeWidth: 1.75 } as const;
const BTN =
  'w-11 h-12 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink active:bg-overlay disabled:text-ink-3 disabled:opacity-45 disabled:active:bg-transparent focus-visible:outline focus-visible:outline-2 focus-visible:outline-ink';

export interface ListSelectBarProps {
  count: number;
  caps: SelectionCaps;
  onExit: () => void;
  onPin: () => void;
  onMute: () => void;
  onDelete: () => void;
  onSend: () => void;
  onRead: () => void;
  onArchive: () => void;
  /** Одна розмова вибрана — «Усі дії» відкривають її повне меню. */
  onAllActions?: () => void;
}

export const ListSelectBar: React.FC<ListSelectBarProps> = ({ count, caps, onExit, onPin, onMute, onDelete, onSend, onRead, onArchive, onAllActions }) => {
  const { t } = useTranslation();
  const [menu, setMenu] = useState(false);
  const run = (fn: () => void) => () => {
    soundFx.playTap();
    setMenu(false);
    fn();
  };
  const I = { className: 'w-[22px] h-[22px]', ...ICON };
  return (
    <div data-today-bar data-list-select-bar className="relative h-14 pl-1 pr-1 flex items-center">
      <button type="button" data-sel-exit aria-label={t('msgV2.sel.exit')} onClick={run(onExit)} className={BTN}>
        <X {...I} />
      </button>
      <h1 data-sel-count aria-live="polite" className="min-w-0 flex-1 pl-1 truncate text-title-2 font-semibold text-ink">
        {t('msgV2.sel.count', { n: count })}
      </h1>
      <button type="button" data-sel-do="pin" disabled={!caps.pin} aria-label={caps.pin === 'unpin' ? t('phoneChatList.unpin') : t('phoneChatList.pin')} onClick={run(onPin)} className={BTN}>
        {caps.pin === 'unpin' ? <PinOff {...I} /> : <Pin {...I} />}
      </button>
      <button type="button" data-sel-do="delete" disabled={!caps.remove} aria-label={t('msgV2.sel.delete')} onClick={run(onDelete)} className={BTN}>
        <Trash2 {...I} />
      </button>
      <button type="button" data-sel-do="send" aria-label={t('msgV2.sel.send')} onClick={run(onSend)} className={BTN}>
        <Send {...I} />
      </button>
      <button type="button" data-sel-do="more" aria-label={t('msgV2.sel.more')} aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((v) => !v)} className={BTN}>
        <MoreVertical {...I} />
      </button>
      {menu && (
        <>
          <button type="button" aria-hidden tabIndex={-1} data-sel-menu-scrim onClick={() => setMenu(false)} className="fixed inset-0 z-[40] cursor-default bg-transparent" />
          <div role="menu" data-sel-menu className="absolute right-2 top-12 z-[41] min-w-[200px] py-1 rounded-control bg-surface border border-line f-fade-in">
            <MenuItem
              icon={caps.mute === 'unmute' ? <Bell className="w-5 h-5" {...ICON} /> : <BellOff className="w-5 h-5" {...ICON} />}
              label={caps.mute === 'unmute' ? t('phoneChatList.unmute') : t('phoneChatList.mute')}
              disabled={!caps.mute}
              onClick={run(onMute)}
              testKey="mute"
            />
            <MenuItem icon={<CheckCheck className="w-5 h-5" {...ICON} />} label={t('phoneChatList.read')} disabled={!caps.read} onClick={run(onRead)} testKey="read" />
            <MenuItem
              icon={caps.archive === 'unarchive' ? <ArchiveRestore className="w-5 h-5" {...ICON} /> : <Archive className="w-5 h-5" {...ICON} />}
              label={caps.archive === 'unarchive' ? t('phoneChatList.unarchive') : t('phoneChatList.toArchive')}
              disabled={!caps.archive}
              onClick={run(onArchive)}
              testKey="archive"
            />
            {onAllActions && <MenuItem icon={<Settings2 className="w-5 h-5" {...ICON} />} label={t('msgV2.sel.allActions')} onClick={run(onAllActions)} testKey="all" />}
          </div>
        </>
      )}
    </div>
  );
};

const MenuItem: React.FC<{ icon: React.ReactNode; label: string; disabled?: boolean; onClick: () => void; testKey: string }> = ({ icon, label, disabled, onClick, testKey }) => (
  <button
    type="button"
    role="menuitem"
    data-sel-do={testKey}
    disabled={disabled}
    onClick={onClick}
    className="w-full h-12 px-4 flex items-center gap-3 text-left text-body text-ink active:bg-overlay disabled:text-ink-3 disabled:opacity-45"
  >
    <span className="shrink-0 text-ink-2">{icon}</span>
    <span className="min-w-0 truncate">{label}</span>
  </button>
);
