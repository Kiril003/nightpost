export const uk = {
  'billMode.label': 'Як платимо',
  'billMode.split': 'Порівну',
  'billMode.escrow': 'Через ескроу',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'billMode.label': 'How we pay',
  'billMode.split': 'Split',
  'billMode.escrow': 'Via escrow',
};
