export const uk = {
  'amountTap.pay': 'Оплатити {amount}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'amountTap.pay': 'Pay {amount}',
};
