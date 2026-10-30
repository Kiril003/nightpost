import type { uk } from '../billEscrow';

export const cs: Record<keyof typeof uk, string> = {
  'billEscrow.open': 'Escrow: {paid} z {total}',
  'billEscrow.settled': 'Escrow: všichni zaplatili',
  'billEscrow.held': 'Escrow: drží',
  'billEscrow.released': 'Escrow: vyplaceno',
  'billEscrow.refunded': 'Escrow: vráceno',
  'billEscrow.cancelled': 'Escrow: zrušeno',
  'billEscrow.forfeited': 'Escrow: ponecháno příjemci',
};
