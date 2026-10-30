export const uk = {
  'voiceDraft.resume': 'Продовжити',
  'voiceDraft.send': 'Надіслати',
  'voiceDraft.delete': 'Видалити',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'voiceDraft.resume': 'Resume',
  'voiceDraft.send': 'Send',
  'voiceDraft.delete': 'Delete',
};
