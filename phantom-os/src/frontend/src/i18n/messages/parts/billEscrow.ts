export const uk = {
  'billEscrow.open': 'Ескроу: {paid} з {total}',
  'billEscrow.settled': 'Ескроу: усі заплатили',
  'billEscrow.held': 'Ескроу: тримає',
  'billEscrow.released': 'Ескроу: виплачено',
  'billEscrow.refunded': 'Ескроу: повернуто',
  'billEscrow.cancelled': 'Ескроу: скасовано',
  'billEscrow.forfeited': 'Ескроу: залишено отримувачу',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'billEscrow.open': 'Escrow: {paid} of {total}',
  'billEscrow.settled': 'Escrow: all paid',
  'billEscrow.held': 'Escrow: holding',
  'billEscrow.released': 'Escrow: paid out',
  'billEscrow.refunded': 'Escrow: refunded',
  'billEscrow.cancelled': 'Escrow: cancelled',
  'billEscrow.forfeited': 'Escrow: kept by payee',
};
