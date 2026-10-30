import React, { useMemo, useState } from 'react';
import { FileText, Image as ImageIcon, MessageSquare, Mic, Video } from 'lucide-react';
import { useTranslation, type Translation } from '../../../i18n/useTranslation';
import type { Chat, Message } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useUIStore } from '../../../stores/uiStore';
import { textFor } from '../../../services/recipients';
import { FormSheet } from '../FormSheet';
import { RecipientPicker } from '../recipients/RecipientPicker';

export interface SharePreview {
  label: string;
  kind: 'photo' | 'voice' | 'video' | 'file' | 'text';
  thumb?: string;
  more?: number;
}

/** Що саме йде — одним рядком: мініатюра й рід («Фото»), або сам текст. */
export const previewOfMessages = (list: Message[], t: Translation['t']): SharePreview | null => {
  const m = list[0];
  if (!m) return null;
  const more = list.length - 1;
  if (m.type === 'image' || m.imageData) {
    return { kind: 'photo', label: t('forwardMessageModal.photo'), thumb: m.imageData?.url || m.media?.thumb, more };
  }
  if (m.type === 'voice') return { kind: 'voice', label: t('forwardMessageModal.voice'), more };
  if (m.type === 'video-note') return { kind: 'video', label: t('forwardMessageModal.videoNote'), thumb: m.videoNoteData?.thumbnailUrl, more };
  if (m.fileData) return { kind: 'file', label: m.fileData.name, more };
  return { kind: 'text', label: m.text || t('forwardMessageModal.message'), more };
};

const KIND_ICON = { photo: ImageIcon, voice: Mic, video: Video, file: FileText, text: MessageSquare } as const;

export interface ShareSheetProps {
  chats: Chat[];
  /** Що надсилаємо. Немає — лист кільком (вибір розмов у списку): поле підпису стає текстом. */
  preview?: SharePreview | null;
  /** Вибрані наперед (вибір розмов у списку). */
  initialSelected?: string[];
  /** Керований вибір — коли власник тримає його довше за аркуш (крок охорони). */
  selected?: string[];
  onSelectedChange?: (ids: string[]) => void;
  caption?: string;
  onCaptionChange?: (text: string) => void;
  exclude?: string;
  /** Своя дорога (пересилання). Без неї — кожному окремий лист через sendMessage. */
  onSend?: (ids: string[], caption: string) => void;
  onClose: () => void;
  testId?: string;
  /** Над повноекранним шаром (перегляд моменту): свій z-index. */
  layerClassName?: string;
}

/**
 * Аркуш «Надіслати»: рядок предмета, пошук, сітка людей і розмов, підпис і
 * одна головна дія. Кожен адресат отримує свою копію, не групу.
 */
export const ShareSheet: React.FC<ShareSheetProps> = ({
  chats,
  preview = null,
  initialSelected,
  selected,
  onSelectedChange,
  caption,
  onCaptionChange,
  exclude,
  onSend,
  onClose,
  testId = 'share-sheet',
  layerClassName,
}) => {
  const { t } = useTranslation();
  const [ownSel, setOwnSel] = useState<string[]>(() => initialSelected ?? []);
  const [ownText, setOwnText] = useState('');
  const picked = selected ?? ownSel;
  const setPicked = onSelectedChange ?? setOwnSel;
  const text = caption ?? ownText;
  const setText = onCaptionChange ?? setOwnText;
  const byId = useMemo(() => new Map(chats.map((c) => [c.id, c])), [chats]);
  const targets = picked.filter((id) => byId.has(id));
  const ready = targets.length > 0 && (preview !== null || text.trim() !== '');

  const send = () => {
    if (!ready) return;
    const cap = text.trim();
    if (onSend) {
      onSend(targets, cap);
      return;
    }
    const sendMessage = useMessengerStore.getState().sendMessage;
    for (const id of targets) {
      const body = textFor(cap, byId.get(id)!);
      if (body) void sendMessage(body, { to: id });
    }
    soundFx.playSend();
    useUIStore.getState().toast({ kind: 'success', message: t('pick.sent', { n: targets.length }) });
    onClose();
  };

  const Icon = preview ? KIND_ICON[preview.kind] : null;

  return (
    <FormSheet
      open
      tall
      onClose={onClose}
      title={t('share.title')}
      testId={testId}
      layerKey="share"
      layerClassName={layerClassName}
      footer={
        <div className="flex flex-col gap-3">
          <input
            type="text"
            data-share-caption
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') send();
            }}
            placeholder={preview ? t('share.caption') : t('pick.message')}
            aria-label={preview ? t('share.caption') : t('pick.message')}
            className="w-full h-12 px-3.5 rounded-control border border-line-strong bg-transparent text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
          />
          <button
            type="button"
            data-share-send
            disabled={!ready}
            onClick={send}
            className={`w-full h-12 min-h-0 rounded-full text-headline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-line ${
              ready ? 'bg-accent text-on-accent active:bg-accent-press' : 'bg-raised text-ink-3'
            }`}
          >
            {targets.length ? t('pick.send', { n: targets.length }) : t('share.send')}
          </button>
        </div>
      }
    >
      {preview && Icon && (
        <div data-share-preview className="mx-4 mb-2.5 p-2 flex items-center gap-3 rounded-control bg-surface border border-line">
          <span className="w-10 h-10 shrink-0 rounded-chip overflow-hidden bg-raised text-ink-2 flex items-center justify-center">
            {preview.thumb ? <img src={preview.thumb} alt="" className="w-full h-full object-cover" /> : <Icon className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
          </span>
          <span className="flex-1 min-w-0 truncate text-subhead text-ink">
            {preview.label}
            {preview.more ? <span className="text-ink-3"> · {t('forwardMessageModal.more', { n: preview.more })}</span> : null}
          </span>
        </div>
      )}
      <RecipientPicker chats={chats} selected={picked} onChange={setPicked} exclude={exclude} />
    </FormSheet>
  );
};
