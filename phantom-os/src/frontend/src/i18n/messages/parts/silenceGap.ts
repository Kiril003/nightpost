export const uk = {
  'silenceGap.hours': '{n} год потому',
  'silenceGap.days': '{n} дн. потому',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'silenceGap.hours': '{n} h later',
  'silenceGap.days': '{n} d later',
};
