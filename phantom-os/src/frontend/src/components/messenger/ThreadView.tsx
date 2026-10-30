import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronLeft, CornerDownRight, Plus, FileText, Download, MessageSquare } from 'lucide-react';
import { useSpaceStore } from '../../stores/spaceStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { Message } from '../../types/messenger';
import { Avatar } from './Avatar';
import { SecureMediaBubble } from './SecureMediaBubble';
import { UnifiedVoiceBubble } from './UnifiedVoiceBubble';
import { VideoNoteBubble } from './VideoNoteBubble';
import { MediaLightboxModal } from './MediaLightboxModal';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { COMPOSER_GHOST_BUTTON, ComposerField, ComposerSheet, SendButton } from './MessageComposer';
import { useTranslation } from '../../i18n/useTranslation';
import { useEdgeStretch } from '../../feel/useEdgeStretch';
import { parseSticker } from '../../stickers/stickerWire';
import { StickerView } from '../../stickers/StickerView';

/**
 * Внутрішній вміст листа у гілці: підтримує текст, наскрізне фото/файли SecureMedia,
 * звичайні зображення, аудіо, кружечки, стікери та завантаження файлів.
 */
export const ThreadMessageBody: React.FC<{
  msg: Message;
  isSelf: boolean;
  onOpenLightbox: (url: string, title?: string) => void;
}> = ({ msg, isSelf, onOpenLightbox }) => {
  const { t } = useTranslation();
  const isVoice = msg.type === 'voice' || Boolean(msg.voiceData);
  const isVideoNote = msg.type === 'video-note' || Boolean(msg.videoNoteData);
  const hasSecureFileOrImage = Boolean(msg.media) && !isVoice && !isVideoNote;
  const sticker = !msg.media ? parseSticker(msg.text) : null;
  return (
    <div className="mt-1.5 space-y-2">
      {/* 1. SecureMedia (якщо є наскрізний зашифрований блоб фото/файла) */}
      {hasSecureFileOrImage ? (
        <SecureMediaBubble msg={msg} isSelf={isSelf} onOpenLightbox={onOpenLightbox} />
      ) : null}

      {/* 2. Звичайне фото або URL медіа (відкритий доступ або кеш) */}
      {!msg.media && !isVoice && !isVideoNote && (msg.type === 'image' || msg.mediaUrl) && (
        <div
          onClick={() => {
            soundFx.playTap();
            onOpenLightbox(msg.mediaUrl || msg.imageData?.url || '', msg.fileName || t('threadView.image'));
          }}
          className="rounded-control overflow-hidden border border-line max-w-[280px] max-h-[240px] cursor-pointer group relative bg-surface"
        >
          <img
            src={msg.mediaUrl || msg.imageData?.url}
            alt={msg.fileName || t('threadView.image')}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform"
          />
        </div>
      )}

      {/* 3. Голосові та аудіо */}
      {isVoice ? (
        msg.voiceData ? (
          <UnifiedVoiceBubble msg={msg} />
        ) : msg.audioUrl ? (
          <div className="flex items-center gap-2 p-2 rounded-control bg-surface border border-line max-w-[280px]">
            <audio controls src={msg.audioUrl} className="w-full h-8" />
          </div>
        ) : null
      ) : null}

      {/* 4. Відео-нотатки (кружечки) */}
      {isVideoNote && <VideoNoteBubble msg={msg} />}

      {/* 5. Стікер */}
      {msg.type === 'sticker' && msg.stickerData && (
        <div className="p-1">
          {msg.stickerData.url ? (
            <img src={msg.stickerData.url} alt={msg.stickerData.title || t('threadView.sticker')} className="w-24 h-24 object-contain" />
          ) : (
            <span className="text-4xl">{msg.stickerData.emojiAlt || '✨'}</span>
          )}
        </div>
      )}

      {/* 6. Звичайне вкладення файлу */}
      {!msg.media && msg.type === 'file' && (
        <a
          href={msg.mediaUrl || '#'}
          download={msg.fileName || 'file'}
          className="flex items-center gap-2 p-2.5 rounded-control bg-surface border border-line hover:border-accent-line/40 transition-colors text-xs font-semibold text-ink max-w-[280px]"
        >
          <FileText className="w-4 h-4 text-accent-text shrink-0" />
          <span className="truncate flex-1">{msg.fileName || t('threadView.file')}</span>
          <Download className="w-4 h-4 text-ink-2 shrink-0" />
        </a>
      )}

      {/* 7. Текст листа (SecureMediaBubble вже малює msg.text як підпис) */}
      {sticker && <StickerView packId={sticker.packId} id={sticker.id} emoji={sticker.emoji} size={96} />}
      {msg.text && !hasSecureFileOrImage && !sticker && (
        <p className="text-[13.5px] text-ink leading-relaxed break-words whitespace-pre-wrap">
          {msg.text}
        </p>
      )}

      {!msg.text &&
        !msg.media &&
        !msg.mediaUrl &&
        !msg.audioUrl &&
        !msg.voiceData &&
        !msg.videoNoteData &&
        !msg.stickerData &&
        msg.type !== 'file' && (
          <p className="text-footnote text-ink-2 italic">
            {t('threadView.noText')}
          </p>
        )}
    </div>
  );
};

/**
 * Гілка від листа, на весь екран / бічна панель.
 * Збирає повне транзитивне дерево відповідей у поточному чаті.
 */
export const ThreadView: React.FC = () => {
  const { t } = useTranslation();
  const view = useSpaceStore((s) => s.threadView);
  const close = useSpaceStore((s) => s.openThreadView);

  const chats = useMessengerStore((s) => s.chats);
  const meId = useMessengerStore((s) => s.currentUser.id);
  const startReply = useMessengerStore((s) => s.startReply);
  const sendMessage = useMessengerStore((s) => s.sendMessage);
  const sendAttachment = useMessengerStore((s) => s.sendAttachment);
  const setActiveChat = useMessengerStore((s) => s.setActiveChat);
  const activeChatId = useMessengerStore((s) => s.activeChatId);

  const [draft, setDraft] = useState('');
  const [isUploading, setIsUploading] = useState(false);
  const [lightboxMedia, setLightboxMedia] = useState<{ url: string; title?: string } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const feedRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(feedRef);

  const chat = chats.find((c) => c.id === view?.chatId) ?? null;
  const root = chat?.messages?.find((m) => m.id === view?.rootMessageId) ?? null;

  // Збираємо все дерево відповідей на корінь (включно з відповідями на відповіді)
  const replies = useMemo(() => {
    if (!chat?.messages || !view?.rootMessageId) return [];
    const threadIds = new Set<string>([view.rootMessageId]);
    let added = true;
    while (added) {
      added = false;
      for (const m of chat.messages) {
        if (m.replyTo?.id && threadIds.has(m.replyTo.id) && !threadIds.has(m.id)) {
          threadIds.add(m.id);
          added = true;
        }
      }
    }
    return chat.messages.filter((m) => m.id !== view.rootMessageId && threadIds.has(m.id));
  }, [chat?.messages, view?.rootMessageId]);

  // Писати треба саме в цю розмову: sendMessage іде в активну.
  useEffect(() => {
    if (view && activeChatId !== view.chatId) setActiveChat(view.chatId);
  }, [view, activeChatId, setActiveChat]);

  useEffect(() => {
    feedRef.current?.scrollTo({ top: feedRef.current.scrollHeight, behavior: 'smooth' });
  }, [replies.length]);

  // Escape закриває гілку й повертає в основний чат
  useEscapeClose(Boolean(view), () => close(null));

  if (!view) return null;

  const send = () => {
    const text = draft.trim();
    if (!text || !root) return;
    startReply(root);
    sendMessage(text);
    setDraft('');
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0 || !root) return;
    const file = files[0];
    try {
      setIsUploading(true);
      startReply(root);
      await sendAttachment(file, undefined, draft.trim() || undefined);
      setDraft('');
    } catch (err) {
      useUIStore.getState().toast({
        kind: 'error',
        message: err instanceof Error ? err.message : t('secureMediaBubble.openFileFailed'),
      });
    } finally {
      setIsUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  return (
    <div
      data-testid="thread-view"
      className="fixed inset-0 z-[1100] flex flex-col bg-canvas text-ink md:left-auto md:w-[440px] md:border-l md:border-line shadow-e2"
      style={{ paddingTop: 'var(--sat)', paddingBottom: 'var(--sab)' }}
      role="region"
      aria-label={t('threadView.threadLabel')}
    >
      {/* Header */}
      <div className="shrink-0 h-[56px] px-3 flex items-center gap-2.5 border-b border-line bg-canvas">
        <button
          type="button"
          onClick={() => {
            soundFx.playTap();
            close(null);
          }}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink-2 hover:text-ink hover:bg-overlay active:bg-overlay transition-colors duration-quick ease-standard"
          title={t('threadView.backLabel')}
          aria-label={t('threadView.backLabel')}
        >
          <ChevronLeft className="w-5 h-5" strokeWidth={2} />
        </button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <MessageSquare className="w-4 h-4 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <h2 className="text-headline text-ink truncate">{t('threadView.title')}</h2>
          </div>
          <p className="text-footnote text-ink-2 truncate">
            {t('threadView.meta', { title: chat?.title ?? t('threadView.chatFallback'), count: replies.length })}
          </p>
        </div>
      </div>

      {/* Feed */}
      <div ref={feedRef} className="flex-1 min-h-0 overflow-y-auto px-3.5 py-3.5 bg-canvas">
        <div className="space-y-3">
        {!root ? (
          <p className="px-2 py-4 text-footnote text-ink-2 leading-relaxed">
            {t('threadView.rootGone')}
          </p>
        ) : (
          <>
            {/* Root Message Box */}
            <div className="rounded-card bg-raised p-3.5 text-ink">
              <div className="flex items-center gap-2.5 min-w-0">
                <Avatar src={root.senderAvatar} name={root.senderName} tone={root.senderId} radius="rounded-full" className="w-7 h-7 shrink-0" />
                <span className="text-subhead text-ink truncate">{root.senderName}</span>
                <span className="text-caption text-ink-3 shrink-0 ml-auto">{root.timestamp}</span>
              </div>
              <ThreadMessageBody
                msg={root}
                isSelf={root.senderId === meId}
                onOpenLightbox={(url, title) => setLightboxMedia({ url, title })}
              />
            </div>

            {replies.length === 0 ? (
              <p className="px-2 py-3 text-footnote text-ink-2 leading-relaxed">
                {t('threadView.noReplies')}
              </p>
            ) : (
              replies.map((m: Message) => (
                <div key={m.id} className="flex gap-2 min-w-0 pl-1">
                  <CornerDownRight className="w-4 h-4 mt-3 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
                  <div className="flex-1 min-w-0 rounded-card bg-surface p-3 text-ink">
                    <div className="flex items-center gap-2 min-w-0">
                      <Avatar src={m.senderAvatar} name={m.senderName} tone={m.senderId} radius="rounded-full" className="w-6 h-6 shrink-0" />
                      <span className="text-subhead text-ink truncate">{m.senderName}</span>
                      <span className="text-caption text-ink-3 shrink-0 ml-auto">{m.timestamp}</span>
                    </div>
                    <ThreadMessageBody
                      msg={m}
                      isSelf={m.senderId === meId}
                      onOpenLightbox={(url, title) => setLightboxMedia({ url, title })}
                    />
                  </div>
                </div>
              ))
            )}
          </>
        )}
        </div>
      </div>

      {/* Лист відповіді — ті самі деталі, що й у розмові (MessageComposer):
          аркуш `raised` радіус 24, «+» і «Надіслати» колами 44, поле без
          рамки й кільця. Своє тут лише те, що вони роблять: файл іде у гілку,
          текст — відповіддю на корінь. */}
      {root && (
        <div className="shrink-0 px-3 pt-2 pb-2 flex items-end gap-2">
          <input
            type="file"
            ref={fileInputRef}
            onChange={handleFileSelect}
            className="hidden"
            aria-hidden="true"
          />
          <ComposerSheet data-testid="thread-composer">
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={isUploading}
              className={COMPOSER_GHOST_BUTTON}
              title={t('threadView.attach')}
              aria-label={t('threadView.attach')}
            >
              <Plus className="w-6 h-6" strokeWidth={1.75} />
            </button>
            <ComposerField
              wrapTestId="thread-composer-field"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
              placeholder={t('threadView.replyPlaceholder')}
              aria-label={t('threadView.replyLabel')}
            />
            <SendButton
              onClick={send}
              disabled={!draft.trim() || isUploading}
              label={t('threadView.send')}
              testId="thread-send"
            />
          </ComposerSheet>
        </div>
      )}

      {/* Lightbox Modal for photos opened in Thread */}
      {lightboxMedia && (
        <MediaLightboxModal
          isOpen={true}
          onClose={() => setLightboxMedia(null)}
          mediaUrl={lightboxMedia.url}
          mediaTitle={lightboxMedia.title || t('threadView.mediaTitle')}
        />
      )}
    </div>
  );
};
