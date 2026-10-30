export const uk = {
  'sameName.suffix': ' · {suffix}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'sameName.suffix': ' · {suffix}',
};
