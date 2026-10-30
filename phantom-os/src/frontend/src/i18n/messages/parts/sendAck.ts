export const uk = {
  'sendAck.awaitingAck': 'Чекає підтвердження',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'sendAck.awaitingAck': 'Awaiting confirmation',
};
