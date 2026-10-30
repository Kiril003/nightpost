/**
 * Аркуш вкладень (§7.5): один ряд квадратних плиток — Фото, Камера, Файл,
 * Місце — і «Ще» для рідших (опитування, місце наживо, список, витрати,
 * таблиця). Пʼять клітинок — стільки, скільки влазить на 390 без обрізаних
 * підписів. Жодних «до 4 МБ» унизу: про розмір кажемо, лише коли файл
 * справді завеликий.
 *
 * Рамка — спільний аркуш FormSheet (вигляд D).
 *
 * Згори — «Галерея» (системний вибір, кілька) і фото, які людина вже
 * надіслала звідси (attachRecent). Немає таких — лише «Галерея».
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { useEffect, useMemo, useState } from 'react';
import { ImageIcon, type LucideIcon } from 'lucide-react';
import type { Message, SecureMedia } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { cachedMediaUrl, loadMedia } from '../../services/messengerMedia';
import { recentSentPhotos } from './attachRecent';
import { soundFx } from '../../utils/messengerSound';
import { IconTile } from './kit/IconTile';
import { FormSheet } from './FormSheet';

export interface AttachTile {
  icon: LucideIcon;
  label: string;
  pick: () => void;
  testId?: string;
}

/** Сталий тон кожної дії: камера завжди синя, місце зелене — рука знаходить без читання. */
const TILE_TONE: Record<string, number> = {
  'send-photo': 4, 'send-camera': 2, 'send-file': 5, 'composer-where': 1, 'send-place': 1, 'send-live': 1, 'send-ask': 3,
  'send-money': 0, 'send-group-money': 0, 'send-meetup': 4, 'send-gift': 4, 'send-video-note': 2,
  'send-music': 5, 'send-poll': 3, 'send-person': 2, 'send-checklist': 1, 'send-expense': 0, 'send-table': 3,
};
export const tileTone = (testId: string | undefined, index: number): number =>
  (testId ? TILE_TONE[testId] : undefined) ?? index % 6;

const Tile: React.FC<{ tile: AttachTile; tone: number; onDone: () => void }> = ({ tile, tone, onDone }) => (
  <IconTile
    icon={tile.icon}
    label={tile.label}
    testId={tile.testId}
    tone={tone}
    onClick={() => {
      soundFx.playTap();
      onDone();
      tile.pick();
    }}
  />
);

export interface AttachGallery {
  pick: () => void;
  onRecent?: (msg: Message) => void;
}

/** Розшифровка йде по одній: тридцять фото разом не мають забирати телефон. Завислий файл не тримає чергу. */
const DECRYPT_GIVE_UP_MS = 15_000;
let decryptQueue: Promise<unknown> = Promise.resolve();
const queued = (media: SecureMedia): Promise<string> => {
  const next = decryptQueue.then(() =>
    Promise.race([
      loadMedia(media),
      new Promise<string>((_, no) => setTimeout(() => no(new Error('slow')), DECRYPT_GIVE_UP_MS)),
    ]),
  );
  decryptQueue = next.catch(() => undefined);
  return next;
};

const RecentPhoto: React.FC<{ msg: Message; onPick: () => void }> = ({ msg, onPick }) => {
  const media = msg.media!;
  const [src, setSrc] = useState(() => cachedMediaUrl(media.blobId) ?? media.thumb ?? '');
  useEffect(() => {
    if (cachedMediaUrl(media.blobId)) return;
    let alive = true;
    queued(media).then((url) => alive && setSrc(url), () => {});
    return () => {
      alive = false;
    };
  }, [media]);
  if (!src) return null;
  return (
    <button
      type="button"
      data-recent={msg.id}
      onClick={onPick}
      aria-label={media.name}
      className="shrink-0 w-24 h-24 rounded-control overflow-hidden bg-overlay active:scale-[0.97] transition-transform duration-instant focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
    >
      <img src={src} alt="" draggable={false} className="w-full h-full object-cover" />
    </button>
  );
};

const GalleryRow: React.FC<{ gallery: AttachGallery; recent: Message[]; onDone: () => void }> = ({ gallery, recent, onDone }) => {
  const { t } = useTranslation();
  return (
    <div data-testid="attach-recent" className="px-4 pb-3 flex gap-1.5 overflow-x-auto overscroll-x-contain select-none [scrollbar-width:none]">
      <button
        type="button"
        data-testid="send-photo"
        onClick={() => {
          soundFx.playTap();
          onDone();
          gallery.pick();
        }}
        className="shrink-0 w-24 h-24 rounded-control bg-overlay text-ink flex flex-col items-center justify-center gap-1.5 active:scale-[0.97] transition-transform duration-instant focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
      >
        <ImageIcon className="w-6 h-6 text-ink-2" strokeWidth={1.75} aria-hidden />
        <span className="text-caption font-semibold text-center line-clamp-2">{t('tgLook.gallery')}</span>
      </button>
      {recent.map((m) => (
        <RecentPhoto
          key={m.id}
          msg={m}
          onPick={() => {
            soundFx.playTap();
            onDone();
            gallery.onRecent?.(m);
          }}
        />
      ))}
    </div>
  );
};

export const AttachSheet: React.FC<{
  tiles: AttachTile[];
  moreTiles: AttachTile[];
  onClose: () => void;
  gallery?: AttachGallery;
}> = ({ tiles, moreTiles, onClose, gallery }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const recent = useMemo(() => (gallery?.onRecent ? recentSentPhotos(chats) : []), [chats, gallery?.onRecent]);
  // Без надісланих фото «Галерея» — перша плитка сітки, а не самотній рядок.
  const galleryTile: AttachTile[] = gallery && recent.length === 0
    ? [{ icon: ImageIcon, label: t('tgLook.gallery'), pick: gallery.pick, testId: 'send-photo' }]
    : [];
  const row = [...galleryTile, ...tiles, ...moreTiles];
  return (
    <FormSheet open onClose={onClose} title={t('attachSheet.sendWhat')} hideTitle layerKey="attach" testId="send-what-sheet">
      {gallery && recent.length > 0 && <GalleryRow gallery={gallery} recent={recent} onDone={onClose} />}
      <div className="px-4 pt-1 pb-3 grid gap-2 select-none grid-cols-4">
        {row.map((t, i) => (
          <Tile key={t.label} tile={t} tone={tileTone(t.testId, i)} onDone={onClose} />
        ))}
      </div>
    </FormSheet>
  );
};

export default AttachSheet;
