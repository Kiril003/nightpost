export type SignatureStatus = { confirmationStatus?: string; err: unknown } | null | undefined;

export function chainStatus(v: SignatureStatus): 'finalized' | 'unconfirmed' {
  return v && !v.err && v.confirmationStatus === 'finalized' ? 'finalized' : 'unconfirmed';
}
