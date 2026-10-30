import { useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { messengerApi } from '../../services/messengerApi';
import { selfAdded } from '../../services/selfAdded';
import { useSupportStore } from '../../stores/supportStore';
import type { Chat } from '../../types/messenger';

/** Над композером, коли першим пише людина поза колами (`isRequest`). Блок не потребує контакту. */
export function StrangerBar({ chat, onAdded }: { chat: Chat; onAdded?: () => void }) {
  const { t } = useTranslation();
  const [blocked, setBlocked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const peer = chat.peerNodeId;

  const toggleBlock = async () => {
    if (!peer) return;
    setBusy(true);
    setFailed(false);
    try {
      const next = blocked ? await messengerApi.unblockPeer(peer) : await messengerApi.blockPeer(peer);
      setBlocked(next.blocked);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const btn = 'touch-floor min-h-[44px] flex-1 rounded-control border border-line bg-raised text-callout font-semibold disabled:opacity-40';

  return (
    <div data-testid="stranger-bar" className="shrink-0 border-t border-line bg-surface px-4 py-3">
      <p className="text-footnote text-ink-2">{blocked ? t('strangerBar.blocked') : t('strangerBar.title')}</p>
      {failed && <p className="mt-1 text-footnote text-danger">{t('keyHoldBar.failed')}</p>}
      <div className="mt-2 flex gap-2">
        {!blocked && (
          <button
            type="button"
            data-testid="stranger-add"
            onClick={() => {
              selfAdded.mark(chat.id);
              onAdded?.();
            }}
            className="touch-floor min-h-[44px] flex-1 rounded-control bg-accent text-on-accent text-callout font-semibold"
          >
            {t('strangerBar.add')}
          </button>
        )}
        <button type="button" data-testid="stranger-block" disabled={busy || !peer} onClick={() => void toggleBlock()} className={`${btn} text-danger`}>
          {blocked ? t('strangerBar.unblock') : t('strangerBar.block')}
        </button>
        <button
          type="button"
          data-testid="stranger-report"
          onClick={() =>
            useSupportStore.getState().openReport({
              targetKind: 'person',
              targetId: peer || chat.id,
              targetName: chat.title,
              chatId: chat.id,
              senderKey: peer,
              peerId: peer,
              surface: 'public',
            })
          }
          className={`${btn} text-danger`}
        >
          {t('strangerBar.report')}
        </button>
      </div>
    </div>
  );
}
