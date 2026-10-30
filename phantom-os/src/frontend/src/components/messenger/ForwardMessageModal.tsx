import React, { useEffect, useState } from 'react';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { Chat, Message } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { RecipientAvatar } from './recipients/RecipientPicker';
import { ShareSheet, previewOfMessages } from './share/ShareSheet';
import { DoorRow, FormSheet, PrimaryButton } from './FormSheet';
import { displayTitle } from './chatListFormat';
import { isSkipped, markSkipped, needsGuard, redact, ringRank } from '../../services/ringGuard';

interface ForwardMessageModalProps {
  isOpen: boolean;
  onClose: () => void;
  chats: Chat[];
  currentChatId: string;
  messagesToForward: Message[];
  /** Один виклик на адресата; підпис — окремим листом поруч із пересланим. */
  onConfirmForward: (targetChatId: string, opts?: { redact?: boolean; caption?: string }) => void;
}

/** Що саме пересилаємо — одним рядком, словами людини. */
const previewOf = (m: Message, t: Translation['t']): string =>
  m.text ||
  (m.fileData ? m.fileData.name : '') ||
  (m.type === 'image' ? t('forwardMessageModal.photo') : '') ||
  (m.type === 'voice' ? t('forwardMessageModal.voice') : '') ||
  (m.type === 'video-note' ? t('forwardMessageModal.videoNote') : '') ||
  t('forwardMessageModal.message');

/**
 * Переслати кільком: аркуш «Надіслати» (сітка адресатів, підпис), далі
 * одна кнопка. Коли лист іде з ближчого кола в ширше й несе адреси чи
 * номери — крок охорони з переглядом для кожного адресата окремо.
 */
export const ForwardMessageModal: React.FC<ForwardMessageModalProps> = ({
  isOpen,
  onClose,
  chats,
  currentChatId,
  messagesToForward,
  onConfirmForward,
}) => {
  const { t } = useTranslation();
  const [picked, setPicked] = useState<string[]>([]);
  const [caption, setCaption] = useState('');
  const [guard, setGuard] = useState(false);
  useEffect(() => {
    if (!isOpen) {
      setGuard(false);
      setPicked([]);
      setCaption('');
    }
  }, [isOpen]);
  useEscapeClose(isOpen, onClose);
  if (!isOpen) return null;

  const currentChat = chats.find((c) => c.id === currentChatId);
  const fromRank = ringRank(currentChat?.circles, currentChat?.circle);
  const hasSensitive = messagesToForward.some(
    (m) => m.type === 'text' && Boolean(m.text) && redact(m.text!) !== m.text,
  );
  const rankOf = (id: string) => {
    const c = chats.find((x) => x.id === id);
    return ringRank(c?.circles, c?.circle);
  };
  const guarded = hasSensitive
    ? picked.filter((id) => needsGuard(fromRank, rankOf(id)) && !isSkipped(fromRank, rankOf(id)))
    : [];

  const sendAll = (hide: ReadonlySet<string>) => {
    soundFx.playSend();
    const cap = caption.trim();
    for (const id of picked) {
      const opts = { ...(hide.has(id) ? { redact: true } : {}), ...(cap ? { caption: cap } : {}) };
      if (Object.keys(opts).length) onConfirmForward(id, opts);
      else onConfirmForward(id);
    }
    setGuard(false);
    setPicked([]);
    setCaption('');
    onClose();
  };

  const first = messagesToForward[0];
  const more = messagesToForward.length - 1;

  if (guard) {
    const hidden = new Set(guarded);
    return (
      <FormSheet
        open
        tall
        onClose={() => {
          setGuard(false);
          onClose();
        }}
        onBack={() => setGuard(false)}
        title={t('ringGuard.title')}
        testId="forward-ring-guard"
        footer={<PrimaryButton onClick={() => sendAll(hidden)}>{t('ringGuard.sendRedacted')}</PrimaryButton>}
      >
        <ul className="pb-2">
          {picked.map((id) => {
            const c = chats.find((x) => x.id === id);
            if (!c) return null;
            const hide = hidden.has(id);
            return (
              <li key={id} data-preview-for={id} className="px-4 py-2 flex items-start gap-3">
                <RecipientAvatar chat={c} on={false} size="w-10 h-10 text-subhead" />
                <div className="flex-1 min-w-0">
                  <p className="text-subhead font-semibold text-ink truncate">
                    {displayTitle(c.title)}
                    <span className="ml-2 text-footnote font-normal text-ink-3">{hide ? t('pick.hidden') : t('pick.asIs')}</span>
                  </p>
                  {first && (
                    <p className="mt-1 w-fit max-w-full px-3.5 py-2 rounded-bubble bg-self text-callout text-ink line-clamp-2 break-words">
                      {first.type === 'text' && first.text && hide ? redact(first.text) : previewOf(first, t)}
                      {more > 0 && <span className="text-ink-3"> · {t('forwardMessageModal.more', { n: more })}</span>}
                    </p>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
        <DoorRow label={t('ringGuard.send')} onClick={() => sendAll(new Set())} />
        <DoorRow label={t('ringGuard.cancel')} onClick={() => setGuard(false)} />
        <DoorRow
          label={t('ringGuard.skip')}
          onClick={() => {
            for (const id of guarded) markSkipped(fromRank, rankOf(id));
            sendAll(new Set());
          }}
        />
      </FormSheet>
    );
  }

  return (
    <ShareSheet
      chats={chats}
      preview={previewOfMessages(messagesToForward, t)}
      selected={picked}
      onSelectedChange={setPicked}
      caption={caption}
      onCaptionChange={setCaption}
      exclude={currentChatId}
      testId="forward-message"
      onClose={onClose}
      onSend={() => {
        if (guarded.length) setGuard(true);
        else sendAll(new Set());
      }}
    />
  );
};
