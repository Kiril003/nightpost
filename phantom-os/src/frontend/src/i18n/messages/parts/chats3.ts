export const uk = {
  'meetupCard.timeMoved': 'Час змінено: {from} → {to}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'meetupCard.timeMoved': 'Time changed: {from} → {to}',
};
