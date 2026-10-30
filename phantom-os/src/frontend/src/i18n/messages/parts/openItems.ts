export const uk = {
  'openItems.tab': 'Чекає',
  'openItems.asked': 'Питає вас',
  'openItems.share': 'Ваша частка',
  'openItems.dismiss': 'Не треба',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'openItems.tab': 'Open',
  'openItems.asked': 'Asked you',
  'openItems.share': 'Your share',
  'openItems.dismiss': 'Dismiss',
};
