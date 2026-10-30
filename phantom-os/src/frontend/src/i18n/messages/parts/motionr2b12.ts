export const uk = {
  'outbox.queuedStack': 'У черзі: {n}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'outbox.queuedStack': 'Queued: {n}',
};
