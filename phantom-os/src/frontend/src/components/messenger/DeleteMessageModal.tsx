import { useTranslation } from '../../i18n/useTranslation';
import React, { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { soundFx } from '../../utils/messengerSound';
import { FormSheet } from './FormSheet';

interface DeleteMessageModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirmDelete: (deleteForEveryone: boolean) => void;
  isSelfMessage: boolean;
  /** «Збережене»: другої сторони немає, вибору теж. */
  noOtherSide?: boolean;
  group?: boolean;
  messageTextPreview?: string;
}

/**
 * Видалити лист — у себе чи в усіх.
 *
 * З 25.09 телефон уміє «для всіх» (кадр PH-UNS: співрозмовник отримує надгробок
 * і втрачає вкладення). Якщо дороги до людини немає, вузол відповідає
 * `for_everyone: false`, і стор каже це словами — обіцянка не мовчить.
 * Свій лист за замовчуванням стирається в усіх (власник 19.10, як у Telegram);
 * чужий — лише в себе.
 */
export const DeleteMessageModal: React.FC<DeleteMessageModalProps> = ({
  isOpen,
  onClose,
  onConfirmDelete,
  isSelfMessage,
  noOtherSide = false,
  group = false,
  messageTextPreview,
}) => {
  const { t } = useTranslation();
  const [deleteForEveryone, setDeleteForEveryone] = useState(true);
  useEffect(() => {
    if (isOpen) setDeleteForEveryone(true);
  }, [isOpen]);
  const choice = isSelfMessage && !noOtherSide;
  const forEveryone = choice && deleteForEveryone;

  const handleConfirm = () => {
    soundFx.playTap();
    onConfirmDelete(forEveryone);
    onClose();
  };

  const option = (picked: boolean, onPick: () => void, title: string, note: string) => (
    <button
      type="button"
      role="radio"
      aria-checked={picked}
      data-delete-scope={picked ? 'picked' : 'free'}
      onClick={onPick}
      className="w-full min-h-[60px] px-4 py-2.5 flex items-center gap-3 text-left active:bg-raised"
    >
      <span className="flex-1 min-w-0">
        <span className="block text-headline text-ink">{title}</span>
        <span className="block text-caption text-ink-3">{note}</span>
      </span>
      <span
        aria-hidden="true"
        className={`w-6 h-6 shrink-0 rounded-full flex items-center justify-center ${picked ? 'bg-accent text-on-accent' : 'border-2 border-f-ink-3'}`}
      >
        {picked && <Check className="w-4 h-4" strokeWidth={2.5} />}
      </span>
    </button>
  );

  return (
    <FormSheet open={isOpen} onClose={onClose} title={t('deleteMessageModal.title')} testId="delete-message">
      <div className="pb-4">
        {messageTextPreview && (
          <div className="px-4 pb-3 flex justify-end">
            <p className="max-w-[85%] px-4 py-2.5 rounded-bubble bg-raised text-callout text-ink-2 line-clamp-2 break-words">
              {messageTextPreview}
            </p>
          </div>
        )}

        {noOtherSide ? null : choice ? (
          <div role="radiogroup" aria-label={t('deleteMessageModal.scope')}>
            {option(!deleteForEveryone, () => setDeleteForEveryone(false), t('deleteMessageModal.onlyMe'), t(group ? 'deleteMessageModal.staysForGroup' : 'deleteMessageModal.staysForThem'))}
            {option(deleteForEveryone, () => setDeleteForEveryone(true), t('deleteMessageModal.everyone'), t(group ? 'deleteMessageModal.goneForGroup' : 'deleteMessageModal.goneForThem'))}
          </div>
        ) : (
          <p data-delete-scope-fixed className="px-4 text-callout text-ink-2">
            {t('deleteMessageModal.fixedScope')}
          </p>
        )}

        <div className="px-4 mt-4 grid grid-cols-2 gap-2">
          <button type="button" onClick={onClose} className="min-h-12 py-2 rounded-full bg-raised text-headline text-ink">
            {t('deleteMessageModal.cancel')}
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            data-delete-confirm={forEveryone ? 'everyone' : 'self'}
            className="min-h-12 py-2 rounded-full bg-raised text-headline text-danger"
          >
            {noOtherSide ? t('deleteMessageModal.delete') : forEveryone ? t('deleteMessageModal.deleteEveryone') : t('deleteMessageModal.deleteSelf')}
          </button>
        </div>
      </div>
    </FormSheet>
  );
};
