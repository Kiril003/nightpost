export const uk = {
  'freeSlots.chip': 'Вільний час',
  'freeSlots.today': 'сьогодні',
  'freeSlots.tomorrow': 'завтра',
  'freeSlots.reply': 'Можу {when} {range}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'freeSlots.chip': 'Free time',
  'freeSlots.today': 'today',
  'freeSlots.tomorrow': 'tomorrow',
  'freeSlots.reply': "I'm free {when} {range}",
};
