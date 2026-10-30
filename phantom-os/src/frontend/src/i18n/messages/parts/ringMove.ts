export const uk = {
  'ringMove.title': 'Що зміниться',
  'ringMove.location': 'Більше не бачитиме, де ви',
  'ringMove.moments.one': 'Не бачитиме {count} момент',
  'ringMove.moments.few': 'Не бачитиме {count} моменти',
  'ringMove.moments.many': 'Не бачитиме {count} моментів',
  'ringMove.cancel': 'Скасувати',
  'ringMove.move': 'Перемістити',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'ringMove.title': 'What changes',
  'ringMove.location': 'Will no longer see where you are',
  'ringMove.moments.one': 'Won’t see {count} Moment',
  'ringMove.moments.few': 'Won’t see {count} Moments',
  'ringMove.moments.many': 'Won’t see {count} Moments',
  'ringMove.cancel': 'Cancel',
  'ringMove.move': 'Move',
};
