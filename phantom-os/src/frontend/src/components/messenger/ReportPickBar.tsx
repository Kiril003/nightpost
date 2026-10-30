import React from 'react';
import { X } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { Chat } from '../../types/messenger';
import { REPORT_PICK_MAX, useMessengerStore } from '../../stores/messengerStore';
import { useSupportStore } from '../../stores/supportStore';
import { soundFx } from '../../utils/messengerSound';
import { inFeedOrder } from '../../services/msgSelection';

/** Вибір листів для скарги: лічильник «N з 10», «Далі» відкриває аркуш скарги з усім вибраним. */
export const ReportPickBar: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const ids = useMessengerStore((s) => s.selectedMessageIds);
  const picked = inFeedOrder(chat.messages, ids);
  const n = picked.length;
  const atMax = n >= REPORT_PICK_MAX;

  const next = () => {
    if (n === 0) return;
    soundFx.playTap();
    const foreign = picked.find((m) => !m.isSelf && m.senderId !== 'system') ?? picked[0];
    useSupportStore.getState().openReport({
      targetKind: 'message',
      targetId: picked[0].id,
      targetName: foreign.senderName || chat.title,
      chatId: chat.id,
      messageIds: picked.map((m) => m.id),
      messageText: picked[0].text,
      senderKey: foreign.senderId || chat.peerNodeId,
      surface: chat.isPublic ? 'public' : 'private',
      peerId: chat.peerNodeId || (foreign.senderId && foreign.senderId !== 'system' ? foreign.senderId : undefined),
    });
    useMessengerStore.getState().clearSelection();
  };

  return (
    <div data-testid="report-pick-bar" className="shrink-0 border-t border-line bg-canvas">
      {atMax && (
        <div role="status" data-testid="report-pick-max" className="mx-4 mt-2 rounded-control bg-raised px-4 py-2">
          <p className="text-callout font-medium text-ink">{t('sel.reportMax', { max: REPORT_PICK_MAX })}</p>
          <p className="text-footnote text-ink-2">{t('sel.reportMaxHint')}</p>
        </div>
      )}
      <div className="flex items-center gap-3 px-2 py-2">
        <button
          type="button"
          aria-label={t('sel.cancel')}
          onClick={() => useMessengerStore.getState().clearSelection()}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay"
        >
          <X className="w-5 h-5" strokeWidth={1.75} />
        </button>
        <span data-testid="report-pick-count" className="flex-1 min-w-0 truncate text-callout text-ink-2">
          {t('sel.reportCount', { n, max: REPORT_PICK_MAX })}
        </span>
        <button
          type="button"
          data-testid="report-pick-next"
          disabled={n === 0}
          onClick={next}
          className="h-12 min-h-0 px-6 rounded-full bg-accent text-on-accent text-callout font-semibold active:bg-accent-press disabled:opacity-40"
        >
          {t('sel.reportNext')}
        </button>
      </div>
    </div>
  );
};
