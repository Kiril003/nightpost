export const uk = {
  'callWords.battOff': 'Батарея сідає · відео вимкнено',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callWords.battOff': 'Battery low · video off',
};
