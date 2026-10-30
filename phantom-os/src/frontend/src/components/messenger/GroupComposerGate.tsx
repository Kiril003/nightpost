import React from 'react';
import { Bell, BellOff } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { Chat } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { isChannel, slowWord } from './GroupSettingsPanel';

const staff = (chat: Chat): boolean => chat.myRole === 'owner' || chat.myRole === 'admin';

/** Підписник каналу не пише — замість композера лише тиша/звук. Щабель невідомий — теж читання, доки склад не скаже «адмін». */
export const readOnlyChannel = (chat: Chat): boolean =>
  ((chat.type === 'group' && isChannel(chat)) || chat.type === 'channel') && !staff(chat);

/** Щабель потрібен композеру одразу, а не лише коли відкрили склад. */
export const GroupRoleProbe: React.FC<{ chat: Chat }> = ({ chat }) => {
  const known = chat.myRole !== undefined;
  React.useEffect(() => {
    if (chat.type === 'group' && chat.groupSettings && !known) void useMessengerStore.getState().loadGroupMembers(chat.id);
  }, [chat.id, chat.type, chat.groupSettings, known]);
  return null;
};

export const ChannelReadBar: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const muted = chat.muted ?? false;
  return (
    <div className="px-4 py-2 border-t border-line bg-canvas" data-testid="channel-read-bar">
      <button
        type="button"
        onClick={() => {
          soundFx.playTap();
          useMessengerStore.getState().toggleMuteChat(chat.id);
        }}
        className="w-full h-12 rounded-full bg-raised text-headline text-ink inline-flex items-center justify-center gap-2"
      >
        {muted ? <Bell className="w-5 h-5" strokeWidth={1.75} /> : <BellOff className="w-5 h-5" strokeWidth={1.75} />}
        {muted ? t('groupProfileSheet.unmute') : t('groupProfileSheet.mute')}
      </button>
    </div>
  );
};

export const SlowModeNote: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const slow = chat.groupSettings?.slow ?? 0;
  if (chat.type !== 'group' || slow <= 0 || staff(chat)) return null;
  return (
    <p className="px-4 pt-1 text-caption text-ink-3 text-center" data-testid="slow-mode-note">
      {t('groupsTg.slowNote', { t: slowWord(slow, t) })}
    </p>
  );
};
