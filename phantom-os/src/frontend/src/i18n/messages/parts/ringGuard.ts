export const uk = {
  'ringGuard.title': 'Інша група',
  'ringGuard.send': 'Надіслати як є',
  'ringGuard.sendRedacted': 'Сховати адреси й номери',
  'ringGuard.cancel': 'Скасувати',
  'ringGuard.skip': 'Більше не питати для цієї пари',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'ringGuard.title': 'Different group',
  'ringGuard.send': 'Send as is',
  'ringGuard.sendRedacted': 'Hide addresses and numbers',
  'ringGuard.cancel': 'Cancel',
  'ringGuard.skip': "Don't ask for this pair",
};
