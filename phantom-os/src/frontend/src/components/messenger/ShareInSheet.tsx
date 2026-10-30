import React, { useCallback, useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { clearShare, fetchPendingShare, installShareIn, shareFiles, type PendingShare } from '../../services/shareIn';
import { useMessengerStore } from '../../stores/messengerStore';
import { Avatar } from './Avatar';
import { listenLiving, useLiving } from '../../services/livingPulse';
import { FormSheet, PrimaryButton } from './FormSheet';

export const ShareInHost: React.FC = () => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const setActiveChat = useMessengerStore((s) => s.setActiveChat);
  const sendAttachments = useMessengerStore((s) => s.sendAttachments);
  const sendMessage = useMessengerStore((s) => s.sendMessage);
  const sendGeoPoint = useMessengerStore((s) => s.sendGeoPoint);

  const faces = useLiving((s) => s.faces);
  useEffect(() => listenLiving(), []);
  const [pending, setPending] = useState<PendingShare | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    const next = await fetchPendingShare();
    if (next) {
      setPending(next);
      setSelectedId(null);
      setFailed(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return installShareIn(() => {
      void load();
    });
  }, [load]);

  const handleClose = useCallback(() => {
    setPending(null);
    setSelectedId(null);
    setFailed(false);
    void clearShare();
  }, []);

  if (!pending) return null;

  const imageItems = pending.items.filter((i) => i.kind === 'image');
  const textItem = pending.items.find((i) => i.kind === 'text' && i.text);
  const placeItem = pending.items.find((i) => i.kind === 'place');
  const caption = textItem?.text ?? '';
  const previewLabel = placeItem
    ? placeItem.text || t('shareIn.place')
    : imageItems.length > 0
      ? t('shareIn.photos', { n: imageItems.length })
      : /^solana:/i.test(caption.trim())
        ? t('shareIn.paymentLink')
        : caption.slice(0, 80);
  const visibleChats = chats.filter((c) => !c.mine);
  const hasContent = Boolean(placeItem) || imageItems.length > 0 || caption.length > 0;

  const handleSend = async () => {
    if (!selectedId || busy) return;
    setBusy(true);
    setFailed(false);
    try {
      setActiveChat(selectedId);
      if (placeItem && placeItem.lat !== undefined && placeItem.lon !== undefined) {
        await sendGeoPoint({
          lat: placeItem.lat,
          lon: placeItem.lon,
          atMs: Date.now(),
          ...(placeItem.text ? { label: placeItem.text } : {}),
        });
      } else if (imageItems.length > 0) {
        const files = await shareFiles(pending);
        if (files.length === 0) throw new Error('no_files');
        const notSent = await sendAttachments(files, caption ? { caption } : undefined);
        if (Array.isArray(notSent) && notSent.length >= files.length) throw new Error('not_sent');
      } else if (caption) {
        await sendMessage(caption);
      }
      await clearShare();
      setPending(null);
      setSelectedId(null);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormSheet
      open
      tall
      testId="share-in-sheet"
      title={t('shareIn.title')}
      onClose={handleClose}
      footer={
        <PrimaryButton onClick={() => void handleSend()} disabled={!selectedId || busy || !hasContent}>
          {t('shareIn.send')}
        </PrimaryButton>
      }
    >
      <div className="px-4 py-3 border-b border-line flex items-center justify-between gap-3 text-footnote text-ink-2">
        <span className="truncate">{previewLabel}</span>
        {pending.skipped > 0 && (
          <span className="shrink-0 text-ink-3">{t('shareIn.skipped', { n: pending.skipped })}</span>
        )}
      </div>
      {failed && (
        <div className="px-4 py-2 text-footnote text-danger border-b border-line">{t('shareIn.failed')}</div>
      )}
      <div className="divide-y divide-line">
        {visibleChats.map((chat) => {
          const picked = chat.id === selectedId;
          return (
            <button
              key={chat.id}
              type="button"
              onClick={() => setSelectedId(chat.id)}
              className="w-full min-h-12 px-4 py-2 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick ease-standard"
            >
              <Avatar
                src={(chat.peerNodeId ? faces[chat.peerNodeId]?.avatar : null) ?? chat.avatar}
                name={chat.title}
                tone={chat.id}
                className="w-10 h-10 text-subhead"
                radius={chat.type === 'group' ? 'rounded-card' : 'rounded-full'}
              />
              <span className="flex-1 min-w-0 truncate text-subhead text-ink">{chat.title}</span>
              {picked && <Check className="w-5 h-5 shrink-0 text-accent" strokeWidth={2} />}
            </button>
          );
        })}
      </div>
    </FormSheet>
  );
};
