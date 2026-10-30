export const uk = {
  'archiveToast.moved': 'Перенесено в архів',
  'archiveToast.undo': 'Скасувати',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'archiveToast.moved': 'Moved to archive',
  'archiveToast.undo': 'Undo',
};
