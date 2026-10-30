import { useTranslation } from '../../i18n/useTranslation';
import React, { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';

/**
 * Фото людини чи групи на повний екран.
 *
 * **Чому не [MediaLightboxModal].** Той шар має кнопку «Завантажити
 * оригінал» — і для вкладення це правда, бо байти оригіналу справді лежать на
 * диску. Для аватара це була б неправда, названа в контракті вголос
 * (`docs/PROFILE_WIRE_CONTRACT.md`, «Ціна рішення»): дротом їде МІНІАТЮРА, не
 * більш як 96×96 px і 6 КіБ, а оригіналу співрозмовника цей пристрій не
 * бачив ніколи і не побачить. Кнопка «завантажити оригінал» віддала б
 * зменшену копію під назвою оригіналу.
 *
 * Тож замість неї — рядок, який каже, ЩО саме людина зараз бачить, із
 * справжніми числами. Дрібне фото, розтягнуте на весь екран, інакше читається
 * як зіпсоване; воно не зіпсоване, воно таке на дроті.
 *
 * `image-rendering` не чіпаємо: згладжування браузера на 96 px виглядає
 * краще за піксельну сітку, а вдавати різкість там, де її немає, тут нічим.
 *
 * 03.10, v1.2: тло — полотно теми (`bg-canvas`), а не `bg-black/92`: удень
 * імʼя `text-ink` на чорному зникало. Шар — у порталі (аркуш групи має
 * transform, і `fixed` усередині нього привʼязувався б до аркуша) і в стеку
 * BACK: системне «Назад» закриває спершу фото, потім аркуш.
 */
interface AvatarViewerModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** `data:`-адреса мініатюри. Порожня — шар не відкривається. */
  src: string | null;
  /** Чиє це фото. Стоїть у шапці й у alt. */
  name: string;
  /** Скільки байтів важить мініатюра. 0 — не кажемо числа взагалі. */
  bytes?: number;
  /**
   * Фото цього пристрою, а не співрозмовника. Змінює РЯДОК ПРАВДИ: своє
   * фото людина бачила в оригіналі сама, і речення про «оригінал не їде»
   * стосується того, що побачать ІНШІ.
   */
  isMine?: boolean;
  /**
   * Чиє обличчя: людини (мініатюра профілю, до 96×96) чи групи (обличчя
   * групи їде дротом не довше 8192 знаків, PeerGroup.FACE_MAX, — це до 6 КБ,
   * а розміру в пікселях дріт не обіцяє).
   */
  kind?: 'person' | 'group';
}

export const AvatarViewerModal: React.FC<AvatarViewerModalProps> = ({
  isOpen,
  onClose,
  src,
  name,
}) => {
  const { t } = useTranslation();
  useEscapeClose(isOpen, onClose);
  const layerId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const shown = isOpen && !!src;
  useEffect(() => registerPhoneLayer(`avatar${layerId}`, shown, () => closeRef.current()), [layerId, shown]);

  if (!isOpen || !src) return null;

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('avatarViewerModal.photoOf', { name })}
      data-testid="avatar-viewer"
      className="f-dialog f-dialog-canvas fixed inset-0 z-[1300] bg-canvas text-ink flex flex-col font-sans f-fade-in"
      onClick={() => {
        soundFx.playTap();
        onClose();
      }}
    >
      <div className="flex items-center justify-between gap-3 pl-5 pr-2 pt-[calc(var(--sat,0px)+8px)] pb-2 shrink-0">
        <div className="min-w-0">
          <h3 className="text-headline text-ink truncate">{name}</h3>
        </div>
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            soundFx.playTap();
            onClose();
          }}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink-2 hover:bg-overlay active:bg-overlay"
          aria-label={t('avatarViewerModal.close')}
        >
          <X className="w-5 h-5" strokeWidth={1.75} />
        </button>
      </div>

      <div className="flex-1 min-h-0 flex items-center justify-center px-6">
        <img
          src={src}
          alt={name}
          onClick={(e) => e.stopPropagation()}
          className="max-w-full max-h-full w-auto h-auto rounded-card"
          style={{ maxWidth: 'min(88vw, 520px)', maxHeight: '70vh' }}
        />
      </div>
    </div>,
    document.body,
  );
};

export default AvatarViewerModal;
