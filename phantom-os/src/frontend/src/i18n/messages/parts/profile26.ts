export const uk = {
  'peerProfileSheet.twin': 'Ще не підтверджено, що це {name} з ваших контактів',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'peerProfileSheet.twin': 'Not yet confirmed as the {name} in your contacts',
};
