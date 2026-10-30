export const uk = {
  'callLog.in': 'Вхідний дзвінок',
  'callLog.out': 'Вихідний дзвінок',
  'callLog.missed': 'Пропущений дзвінок',
  'callLog.declined': 'Відхилений дзвінок',
  'callLog.video': 'відео',
  'callLog.callBack': 'Передзвонити',
  'callLog.empty': 'Дзвінків ще немає',
  'callLog.settings': 'Налаштування дзвінків',
  'callLog.recent': 'Останні',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callLog.in': 'Incoming call',
  'callLog.out': 'Outgoing call',
  'callLog.missed': 'Missed call',
  'callLog.declined': 'Declined call',
  'callLog.video': 'video',
  'callLog.callBack': 'Call back',
  'callLog.empty': 'No calls yet',
  'callLog.settings': 'Call settings',
  'callLog.recent': 'Recent',
};
