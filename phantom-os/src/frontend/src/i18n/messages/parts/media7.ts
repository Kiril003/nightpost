export const uk = {
  'media7.video': 'Відео',
  'media7.audio': 'Аудіо',
  'media7.per10min': '10 хв',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'media7.video': 'Video',
  'media7.audio': 'Audio',
  'media7.per10min': '10 min',
};
