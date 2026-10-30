export const uk = {
  'pcToday.agenda': 'Розклад',
  'pcToday.reminders': 'Нагадування',
  'pcToday.asks': 'Потрібна відповідь',
  'pcToday.answer': 'Відповісти',
  'pcToday.allDay': 'Весь день',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'pcToday.agenda': 'Agenda',
  'pcToday.reminders': 'Reminders',
  'pcToday.asks': 'Needs your answer',
  'pcToday.answer': 'Answer',
  'pcToday.allDay': 'All day',
};
