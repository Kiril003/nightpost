export const uk = {
  'circles.personal': 'Особисте',
  'circles.social': 'Знайомі',
  'circles.new': 'Нова назва',
  'circles.name': 'Назва',
  'circles.create': 'Створити',
  'circles.done': 'Готово',
  'circles.tooMany': 'Не більше 8 своїх',
  'circles.who': 'Хто це',
  'circles.row': 'Розділ',
  'circles.saveFailed': 'Не збережено',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'circles.personal': 'Personal',
  'circles.social': 'Social',
  'circles.new': 'New name',
  'circles.name': 'Name',
  'circles.create': 'Create',
  'circles.done': 'Done',
  'circles.tooMany': 'Up to 8 of your own',
  'circles.who': 'Who is this',
  'circles.row': 'Section',
  'circles.saveFailed': 'Not saved',
};
