export const uk = {
  'infoView.back': 'Назад',
  'infoView.more': 'Ще',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'infoView.back': 'Back',
  'infoView.more': 'More',
};
