export const uk = {
  'ringMove.iOwe': 'Ви винні · {amount}',
  'ringMove.owesMe': 'Вам винні · {amount}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'ringMove.iOwe': 'You owe · {amount}',
  'ringMove.owesMe': 'Owed to you · {amount}',
};
