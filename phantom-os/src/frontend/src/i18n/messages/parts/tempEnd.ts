export const uk = {
  'profiles.tempEnding': '«{name}» скінчиться о {time}',
  'profiles.tempExtend': 'Продовжити 24 год',
  'profiles.tempEnded': '{name} · завершено',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'profiles.tempEnding': '“{name}” ends at {time}',
  'profiles.tempExtend': 'Extend 24 h',
  'profiles.tempEnded': '{name} · ended',
};
