export const uk = {
  'moments.people.one': 'людина',
  'moments.people.few': 'людини',
  'moments.people.many': 'людей',
  'moments.leaveOut': 'Без {ring}',
  'moments.seeN': 'Бачать {n}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'moments.people.one': 'person',
  'moments.people.few': 'people',
  'moments.people.many': 'people',
  'moments.leaveOut': 'Leave out {ring}',
  'moments.seeN': '{n} can see',
};
