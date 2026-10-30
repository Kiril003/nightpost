import React from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { soundFx } from '../../utils/messengerSound';
import type { Message } from '../../types/messenger';
import { keepMomentMedia, type Moment } from '../../services/moments';
import { ShareSheet, type SharePreview } from './share/ShareSheet';
import { isVideo } from './MomentViewer';

/** Чужий момент для вужчого кола чи вибраних людей далі не йде; свій — на розсуд автора. */
export const canShareMoment = (m: Moment): boolean => m.mine || m.reach === 'contacts' || m.reach === 'everyone';

export const momentAsMessage = (m: Moment, caption: string): Message => {
  const media = m.media[0];
  const text = [m.text, caption].filter(Boolean).join('\n');
  const base = { id: m.id, senderId: m.fromId, senderName: m.fromName, timestamp: '', isSelf: true } as const;
  if (!media) return { ...base, type: 'text', text } as Message;
  return { ...base, type: media.mime.startsWith('image/') ? 'image' : 'file', text, media } as unknown as Message;
};

export const MomentShare: React.FC<{ m: Moment; onClose: () => void; layerClassName?: string }> = ({ m, onClose, layerClassName }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats).filter((c) => !c.mine && !c.archived);
  const first = m.media[0];
  const preview: SharePreview = m.media.length === 0
    ? { kind: 'text', label: m.text }
    : { kind: isVideo(m) ? 'video' : 'photo', label: m.text || t(isVideo(m) ? 'momentsR.video' : 'forwardMessageModal.photo'), thumb: first?.thumb };
  const send = async (ids: string[], caption: string) => {
    const forward = useMessengerStore.getState().forwardMessage;
    onClose();
    await keepMomentMedia(m);
    for (const id of ids) await forward(momentAsMessage(m, caption), id);
    soundFx.playSend();
    useUIStore.getState().toast({ kind: 'success', message: t('pick.sent', { n: ids.length }) });
  };
  return <ShareSheet chats={chats} preview={preview} onSend={(ids, caption) => void send(ids, caption)} onClose={onClose} testId="moment-share" layerClassName={layerClassName} />;
};
