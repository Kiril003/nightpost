import React, { useRef, useState } from 'react';
import { Check, ImagePlus } from 'lucide-react';
import { DoorRow } from './FormSheet';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { soundFx } from '../../utils/messengerSound';
import { WALLPAPERS, useLook, wallpaperPhoto, wallpaperUrl, type ChatWallpaperPick } from '../../services/appearance';
import { resolveLook, sameWallpaper, useRingPresets } from '../../services/ringPresets';
import { circleName, useCirclesStore } from '../../stores/circlesStore';
import { useMessengerStore } from '../../stores/messengerStore';

const CHECK = <Check className="w-5 h-5 shrink-0 text-accent-text" strokeWidth={1.75} aria-hidden />;

/** Меню ⋮ › Тло: своє тло для цієї розмови поверх загального з «Вигляду». */
export const ChatWallpaperPicker: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const look = useLook((s) => s.look);
  const own = look.chats[chatId];
  const setChatWallpaper = useLook((s) => s.setChatWallpaper);
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const circles = useCirclesStore((s) => s.circles);
  const presets = useRingPresets((s) => s.presets);
  const setRingWallpaper = useRingPresets((s) => s.setRingWallpaper);
  const setRingBubbles = useRingPresets((s) => s.setRingBubbles);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const resolved = resolveLook(chat ?? { id: chatId }, look, presets);
  const ring = resolved.ring;
  const ringPreset = ring ? presets[ring] : undefined;
  const ringInfo = ring ? (circles.find((c) => c.id === ring) ?? { id: ring, name: null }) : null;
  const ringLabel = ringInfo ? circleName(ringInfo, t) : '';
  const isRingWallpaper = sameWallpaper(ringPreset?.wallpaper, resolved.wallpaper);
  const isRingCompact = ringPreset?.bubbles === 'compact';

  const pick = async (p: ChatWallpaperPick) => {
    setBusy(true);
    setFailed(false);
    try {
      await setChatWallpaper(chatId, p);
      soundFx.playTap();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const fromGallery = async (file: File | undefined) => {
    if (!file) return;
    const data = await wallpaperPhoto(file);
    if (data) await pick({ kind: 'photo', data });
    else setFailed(true);
  };

  return (
    <div data-testid="chat-wallpaper-picker" className="pb-2" aria-busy={busy}>
      <DoorRow
        label={t('look.default')}
        hint={t('look.defaultHint')}
        onClick={() => void (busy || !own ? undefined : pick({ kind: 'default' }))}
        trailing={!own ? CHECK : undefined}
      />
      <DoorRow
        label={t('look.none')}
        onClick={() => void (busy ? undefined : pick({ kind: 'none' }))}
        trailing={own?.kind === 'none' ? CHECK : undefined}
      />
      <div className="grid grid-cols-3 gap-3 px-4 pt-3 pb-1">
        {WALLPAPERS.map((id) => {
          const on = own?.kind === 'builtin' && own.id === id;
          return (
            <button
              key={id}
              type="button"
              aria-pressed={on}
              aria-label={t(`look.wp.${id}` as MessageKey)}
              data-wallpaper-id={id}
              disabled={busy}
              onClick={() => void pick({ kind: 'builtin', id })}
              className="flex flex-col items-stretch gap-1.5 text-left"
            >
              <span
                className={`block h-20 rounded-control bg-repeat ${on ? 'outline outline-2 outline-offset-2 outline-accent-line' : ''}`}
                style={{ backgroundImage: `url("${wallpaperUrl({ kind: 'builtin', id })}")`, backgroundSize: '192px 192px' }}
              />
              <span className="text-footnote text-ink-2">{t(`look.wp.${id}` as MessageKey)}</span>
            </button>
          );
        })}
      </div>
      <DoorRow
        icon={<ImagePlus className="w-5 h-5" strokeWidth={1.75} />}
        label={t('look.gallery')}
        hint={t('look.galleryNote')}
        onClick={() => input.current?.click()}
        trailing={own?.kind === 'photo' ? CHECK : undefined}
      />
      {ring && (
        <div data-ring-rows className="mt-2 pt-2 border-t border-line">
          {resolved.wallpaper.kind !== 'photo' && (
            <DoorRow
              label={t('ringPreset.wallpaperFor', { ring: ringLabel })}
              onClick={() => {
                setRingWallpaper(ring, isRingWallpaper ? null : resolved.wallpaper);
                soundFx.playTap();
              }}
              trailing={isRingWallpaper ? CHECK : undefined}
            />
          )}
          <DoorRow
            label={t('ringPreset.compactFor', { ring: ringLabel })}
            onClick={() => {
              setRingBubbles(ring, isRingCompact ? null : 'compact');
              soundFx.playTap();
            }}
            trailing={isRingCompact ? CHECK : undefined}
          />
          {own && ringPreset?.wallpaper && (
            <DoorRow
              label={t('ringPreset.reset')}
              onClick={() => void (busy ? undefined : pick({ kind: 'default' }))}
            />
          )}
        </div>
      )}
      <input
        ref={input}
        type="file"
        accept="image/*"
        className="hidden"
        data-testid="chat-wallpaper-file"
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          void fromGallery(file);
        }}
      />
      {failed && <p className="px-4 pt-2 text-footnote text-danger">{t('look.failed')}</p>}
    </div>
  );
};
