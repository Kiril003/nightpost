export const uk = {
  'carrier.peerTooOld': '«{name}» завеликий для Nightpost співрозмовника',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'carrier.peerTooOld': '"{name}" is too large for their Nightpost',
};
