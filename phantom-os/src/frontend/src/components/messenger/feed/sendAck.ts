import type { Message } from '../../../types/messenger';

export type SendState = 'outline' | 'solid';

export function sendStateOf(r: {
  isSelf: boolean;
  pending: boolean;
  stored: boolean;
  failed: boolean;
  msg: Pick<Message, 'status'>;
}): SendState | null {
  if (!r.isSelf) return null;
  if (r.failed || r.msg.status === 'failed') return 'outline';
  if (!r.stored && (r.pending || r.msg.status === 'sending' || r.msg.status === 'queued')) {
    return 'outline';
  }
  return 'solid';
}
