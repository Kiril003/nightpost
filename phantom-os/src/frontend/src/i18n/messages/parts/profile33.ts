export const uk = {
  'profile33.next': 'Далі',
  'profile33.youOwe': 'Ви винні · {amount}',
  'profile33.owedToYou': 'Винні вам · {amount}',
  'profile33.pending': 'Очікує · {amount}',
  'profile33.moments': 'Спільні моменти',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'profile33.next': 'Next',
  'profile33.youOwe': 'You owe · {amount}',
  'profile33.owedToYou': 'Owed to you · {amount}',
  'profile33.pending': 'Pending · {amount}',
  'profile33.moments': 'Shared moments',
};
