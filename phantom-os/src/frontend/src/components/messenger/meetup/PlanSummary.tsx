/**
 * План групи в її профілі: де й коли, скільки йде, коли група сховається,
 * «Лишити», «Новий план» і «Розділити». Лише для групи, створеної під зустріч.
 */
import React, { useState } from 'react';
import { CalendarPlus, MapPin, ReceiptText } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { KEEP_VALUE, readCardMarks } from '../../../services/meetup/marks';
import { planOfGroup } from '../../../services/meetup/expiry';
import { setCardMark } from '../../../services/meetupApi';
import { openMeetupAsk } from '../../../services/nativeMeetupDraft';
import type { Chat } from '../../../types/messenger';
import { whenWord } from './words';

export const PlanSummary: React.FC<{ chat: Chat; meId: string; onClose: () => void }> = ({ chat, meId, onClose }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const now = Date.now();
  const plan = planOfGroup(chat.messages ?? [], now);
  if (!plan) return null;
  const { msg, meetup, state } = plan;
  const total = new Set([meetup.organizer, ...meetup.invitees.map((p) => p.id)]).size;
  const going = readCardMarks(msg.cardMarks).going.length;

  const keep = async () => {
    setBusy(true);
    setError(await setCardMark(msg, meId, KEEP_VALUE));
    setBusy(false);
  };
  const ask = (a: Parameters<typeof openMeetupAsk>[0]) => {
    onClose();
    openMeetupAsk(a);
  };

  return (
    <div className="mx-4 mt-3 rounded-card bg-raised overflow-hidden" data-testid="group-plan">
      <div className="px-4 pt-3 pb-2 flex items-start gap-3">
        <MapPin className="w-5 h-5 mt-0.5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
        <div className="flex-1 min-w-0">
          <p className="text-headline text-ink truncate">{meetup.venue.name}</p>
          <p className="text-footnote text-ink-2">
            {whenWord(meetup.at, now)} · {t('planGroup.going', { n: going, total })}
          </p>
        </div>
      </div>
      {state.kind === 'will_hide' && (
        <div className="px-4 pb-2 flex items-center gap-2">
          <span className="flex-1 min-w-0 text-footnote text-ink-3" data-testid="group-plan-closes">
            {t('meetupCard.willHide', { when: whenWord(state.at, now) })}
          </span>
          <button
            type="button"
            data-testid="group-plan-keep"
            disabled={busy}
            onClick={() => void keep()}
            className="touch-floor-inline h-9 px-4 shrink-0 rounded-full text-callout font-medium text-ink bg-ink/[.08] active:opacity-80 disabled:opacity-50"
          >
            {t('meetupCard.keep')}
          </button>
        </div>
      )}
      {error && <p className="px-4 pb-2 text-caption text-danger">{error}</p>}
      <div className="flex border-t border-line">
        <button
          type="button"
          data-testid="group-plan-new"
          onClick={() => ask({ kind: 'meetup', chatId: chat.id })}
          className="flex-1 min-h-12 flex items-center justify-center gap-2 text-callout font-medium text-ink active:bg-surface"
        >
          <CalendarPlus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          {t('planGroup.newPlan')}
        </button>
        <button
          type="button"
          data-testid="group-plan-split"
          onClick={() => ask({ kind: 'bill-draft', messageId: msg.id })}
          className="flex-1 min-h-12 flex items-center justify-center gap-2 text-callout font-medium text-ink border-l border-line active:bg-surface"
        >
          <ReceiptText className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          {t('planGroup.split')}
        </button>
      </div>
    </div>
  );
};
