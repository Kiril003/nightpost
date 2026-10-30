export const uk = {
  'callWords.weakMe': 'Слабкий звʼязок у вас',
  'callWords.weakThem': '{name}: слабкий звʼязок',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callWords.weakMe': 'Your connection is weak',
  'callWords.weakThem': "{name}'s connection is weak",
};
