/** Groups and calls (K1). uk is the source, en the translation. */
export const uk = {
  'callGate.noRoads': 'Дзвінки ще готуються — спробуйте за хвилинку',
  'callGate.noGroup': 'Дзвонити можна лише одній людині, не всій групі',
  'callGate.noPeer': 'Цій людині поки не можна подзвонити',
  'header.voice': 'Голос',
  // A3
  'meetupOrganiser.move': 'Перенести',
  'meetupOrganiser.cancel': 'Скасувати',
  'meetupOrganiser.cancelTitle': 'Скасувати зустріч',
  'meetupOrganiser.cancelPlan': 'Скасувати зустріч',
  'meetupOrganiser.back': 'Назад',
  'meetupOrganiser.cancelled': 'Скасовано',
  'meetupOrganiser.past': 'Цей час уже минув',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callGate.noRoads': 'Calls are still starting. Try again in a minute',
  'callGate.noGroup': 'Calls work one person at a time, not a whole group',
  'callGate.noPeer': "This person can't be called yet",
  'header.voice': 'Voice',
  // A3
  'meetupOrganiser.move': 'Move',
  'meetupOrganiser.cancel': 'Cancel',
  'meetupOrganiser.cancelTitle': 'Cancel the plan',
  'meetupOrganiser.cancelPlan': 'Cancel plan',
  'meetupOrganiser.back': 'Back',
  'meetupOrganiser.cancelled': 'Cancelled',
  'meetupOrganiser.past': 'That time has passed',
};
