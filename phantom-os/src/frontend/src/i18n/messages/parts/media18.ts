export const uk = {
  'callSettings.music': 'Звук інструмента',
  'callSettings.musicHint': 'Потрібні навушники',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callSettings.music': 'Instrument sound',
  'callSettings.musicHint': 'Use headphones',
};
