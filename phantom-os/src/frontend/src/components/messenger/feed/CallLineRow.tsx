import React from 'react';
import { PhoneIncoming, PhoneMissed, PhoneOff, PhoneOutgoing } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import type { Message } from '../../../types/messenger';
import { formatCallDuration } from '../../../services/callLog';

type Line = NonNullable<Message['callLine']>;

export const CALL_LINE_WORD: Record<Line['kind'], MessageKey> = {
  in: 'callLog.in',
  out: 'callLog.out',
  missed: 'callLog.missed',
  declined: 'callLog.declined',
};

const ICON = { in: PhoneIncoming, out: PhoneOutgoing, missed: PhoneMissed, declined: PhoneOff } as const;

export const callLineText = (line: Line, t: (k: MessageKey) => string): string => {
  const parts = [t(CALL_LINE_WORD[line.kind])];
  if (line.media === 'video') parts.push(t('callLog.video'));
  if (line.durationSec > 0) parts.push(formatCallDuration(line.durationSec));
  return parts.join(' · ');
};

/** Дзвінок у стрічці — сірий рядок посередині, без дій. */
export const CallLineRow: React.FC<{ id: string; line: Line }> = ({ id, line }) => {
  const { t } = useTranslation();
  const Icon = ICON[line.kind];
  return (
    <div id={`message-${id}`} data-testid="call-line" data-call-kind={line.kind} className="message-row flex justify-center my-3 px-6">
      <span className="ph-on-stage inline-flex items-center gap-1.5 text-center text-footnote text-ink-3">
        <Icon className={`w-3.5 h-3.5 shrink-0 ${line.kind === 'missed' ? 'text-danger' : ''}`} strokeWidth={1.75} aria-hidden="true" />
        {callLineText(line, t)}
      </span>
    </div>
  );
};
