import React, { useRef, useState } from 'react';
import { Star } from 'lucide-react';
import { FormSheet } from '../components/messenger/FormSheet';
import { useTranslation } from '../i18n/useTranslation';
import { soundFx } from '../utils/messengerSound';
import { PACK, type StickerDef } from './pack';
import { shelf, toggleFav } from './stickerShelf';
import { StickerView } from './StickerView';

const HOLD_MS = 450;

const Tile: React.FC<{ d: StickerDef; fav: boolean; onPick: () => void; onHold: () => void }> = ({ d, fav, onPick, onHold }) => {
  const { t } = useTranslation();
  const hold = useRef<number | null>(null);
  const held = useRef(false);
  const stop = () => {
    if (hold.current !== null) window.clearTimeout(hold.current);
    hold.current = null;
  };
  return (
    <button
      type="button"
      data-sticker-pick={`${d.packId}/${d.id}`}
      data-fav={fav ? 'true' : undefined}
      aria-label={`${d.emoji} ${t('chatList.kind.sticker')}`}
      onPointerDown={() => {
        held.current = false;
        stop();
        hold.current = window.setTimeout(() => {
          held.current = true;
          onHold();
        }, HOLD_MS);
      }}
      onPointerUp={stop}
      onPointerLeave={stop}
      onContextMenu={(e) => e.preventDefault()}
      onClick={() => {
        if (held.current) {
          held.current = false;
          return;
        }
        onPick();
      }}
      className="relative aspect-square rounded-control flex items-center justify-center active:bg-overlay active:scale-[0.96] transition-transform duration-instant focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
    >
      <StickerView packId={d.packId} id={d.id} emoji={d.emoji} size={64} still />
      {fav && <Star aria-hidden className="absolute top-1 right-1 w-3.5 h-3.5 text-ink-3 fill-current" strokeWidth={1.75} />}
    </button>
  );
};

const Section: React.FC<{ label: string; testId: string; children: React.ReactNode }> = ({ label, testId, children }) => (
  <section data-testid={testId} className="px-4 pb-2">
    <h3 className="text-caption font-semibold text-ink-2 pb-1">{label}</h3>
    <div className="grid grid-cols-4 gap-1">{children}</div>
  </section>
);

/** Стікери: обрані, нещодавні, увесь набір. Дотик — надіслати; утримання — в обрані. */
export const StickerSheet: React.FC<{ onClose: () => void; onPick: (d: StickerDef) => void }> = ({ onClose, onPick }) => {
  const { t } = useTranslation();
  const [s, setS] = useState(shelf);
  const favs = new Set(s.fav.map((d) => `${d.packId}/${d.id}`));
  const tile = (d: StickerDef, where: string) => (
    <Tile
      key={`${where}-${d.packId}/${d.id}`}
      d={d}
      fav={favs.has(`${d.packId}/${d.id}`)}
      onPick={() => {
        soundFx.playTap();
        onPick(d);
      }}
      onHold={() => {
        toggleFav(d);
        setS(shelf());
      }}
    />
  );
  return (
    <FormSheet open onClose={onClose} title={t('stickers.title')} hideTitle layerKey="stickers" testId="sticker-sheet">
      <div className="pt-1 pb-2 select-none">
        {s.fav.length > 0 && <Section label={t('stickers.fav')} testId="sticker-fav">{s.fav.map((d) => tile(d, 'f'))}</Section>}
        {s.recent.length > 0 && <Section label={t('stickers.recent')} testId="sticker-recent">{s.recent.map((d) => tile(d, 'r'))}</Section>}
        <Section label={t('stickers.pack')} testId="sticker-pack">{PACK.map((d) => tile(d, 'p'))}</Section>
      </div>
    </FormSheet>
  );
};
