export const uk = {
  'reminderToast.nearSet': 'Нагадаю при зустрічі',
  'reminderToast.open': 'Відкрити',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'reminderToast.nearSet': "I'll remind you when you meet",
  'reminderToast.open': 'Open',
};
