export const uk = {
  'seen.title': 'Зустрічі наживо',
  'seen.times.one': '{n} раз',
  'seen.times.few': '{n} рази',
  'seen.times.many': '{n} разів',
  'seen.last': 'востаннє {date}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'seen.title': 'Seen in person',
  'seen.times.one': '{n} time',
  'seen.times.few': '{n} times',
  'seen.times.many': '{n} times',
  'seen.last': 'last {date}',
};
