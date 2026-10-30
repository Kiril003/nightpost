export const uk = {
  "msg.venueSheet.starOf": "{n} з 5",
  'composer.uploadOf': '{i} з {n}',
  'commitment.tomorrowAt': 'завтра {time}',
  'plural.photo.one': 'фото',
  'plural.photo.few': 'фото',
  'plural.photo.many': 'фото',
} as const;

export const en: Record<keyof typeof uk, string> = {
  "msg.venueSheet.starOf": "{n} of 5",
  'composer.uploadOf': '{i} of {n}',
  'commitment.tomorrowAt': 'tomorrow {time}',
  'plural.photo.one': 'photo',
  'plural.photo.few': 'photos',
  'plural.photo.many': 'photos',
};
