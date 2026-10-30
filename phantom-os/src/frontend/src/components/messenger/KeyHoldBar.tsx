import { useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { KeyRound } from 'lucide-react';
import { messengerApi } from '../../services/messengerApi';
import { keyHoldNote } from '../../services/keyChange';
import { openPeerCheck } from '../../services/peerWhere';
import { useMessengerStore } from '../../stores/messengerStore';
import type { Chat } from '../../types/messenger';

/**
 * Замість композера, поки звірений контакт має новий ключ: листи чекають.
 * Дві двері — перевірити (звірка знімає і паузу, і тривогу) або продовжити
 * (пауза знімається, контакт лишається незвіреним).
 */
export function KeyHoldBar({ chat }: { chat: Chat }) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const peer = chat.peerNodeId;

  const carryOn = async () => {
    if (!peer) return;
    setBusy(true);
    setNote(null);
    try {
      await messengerApi.continueAfterKeyChange(peer);
      useMessengerStore.getState().updateChat(chat.id, { contactKeyHold: false });
    } catch {
      setNote(t('keyHoldBar.failed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-testid="key-hold-bar" className="shrink-0 border-t border-line bg-surface px-4 py-3">
      <p className="flex items-start gap-2 text-footnote text-ink-2">
        <KeyRound className="w-4 h-4 mt-0.5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
        {keyHoldNote()}
      </p>
      {note && <p className="mt-1 text-footnote text-danger">{note}</p>}
      <div className="mt-2 flex gap-2">
        <button
          type="button"
          disabled={!peer}
          onClick={() => peer && openPeerCheck(peer)}
          className="touch-floor min-h-[44px] flex-1 rounded-control border border-line bg-raised text-callout font-semibold text-ink disabled:opacity-40"
        >
          {t('keyHoldBar.verify')}
        </button>
        <button
          type="button"
          data-testid="key-hold-continue"
          disabled={busy || !peer}
          onClick={() => void carryOn()}
          className="touch-floor min-h-[44px] flex-1 rounded-control bg-accent text-on-accent text-callout font-semibold disabled:opacity-40"
        >
          {t('keyHoldBar.continue')}
        </button>
      </div>
    </div>
  );
}
