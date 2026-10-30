import React, { useEffect, useState } from 'react';
import type { CommitmentCardData } from '../../../types/messenger';
import { phantomSelfApi } from '../../../services/phantomSelfApi';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useTranslation } from '../../../i18n/useTranslation';

const DISMISSED_KEY = 'np.commitmentOffers.dismissed.v1';
const DISMISSED_CAP = 300;

function readDismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY);
    const list: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

export function isOfferDismissed(sourceMessageId: string | null | undefined): boolean {
  return !!sourceMessageId && readDismissed().includes(sourceMessageId);
}

function rememberDismissed(sourceMessageId: string | null | undefined): void {
  if (!sourceMessageId) return;
  try {
    const next = readDismissed().filter((id) => id !== sourceMessageId);
    next.push(sourceMessageId);
    localStorage.setItem(DISMISSED_KEY, JSON.stringify(next.slice(-DISMISSED_CAP)));
  } catch {
    /* storage full or blocked: the offer just comes back next time */
  }
}

export interface CommitmentCardProps {
  card: CommitmentCardData;
  onOpenChat?: (peerId: string) => void;
}

export const CommitmentCard: React.FC<CommitmentCardProps> = ({ card, onOpenChat }) => {
  const { t } = useTranslation();
  const [kind, setKind] = useState(card.kind);
  const [state, setState] = useState(card.state);
  const [commitmentId, setCommitmentId] = useState(card.commitmentId);
  const [peerId, setPeerId] = useState<string | null | undefined>(card.peerId);
  const [chosenName, setChosenName] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(() => isOfferDismissed(card.sourceMessageId));
  const [savedInDay, setSavedInDay] = useState(false);

  useEffect(() => {
    setKind(card.kind);
    setState(card.state);
    setCommitmentId(card.commitmentId);
    setPeerId(card.peerId);
  }, [card.kind, card.state, card.commitmentId, card.peerId]);

  const handleChoose = (candidatePeerId: string, candidateName: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const pendingId = card.pendingId ?? card.commitmentId ?? '';
    if (!pendingId) return;
    setChosenName(candidateName);
    setPeerId(candidatePeerId);
    setKind('saved');
    void phantomSelfApi
      .chooseCommitment(pendingId, candidatePeerId)
      .then((res) => {
        if (res?.id) setCommitmentId(res.id);
        if (res?.state) setState(res.state);
      })
      .catch(() => {
        setChosenName(null);
        setPeerId(card.peerId);
        setKind(card.kind);
      });
  };

  const handleAcceptOffer = (e: React.MouseEvent) => {
    e.stopPropagation();
    setSavedInDay(true);
    void phantomSelfApi
      .addDirectCommitment({
        person: card.person,
        peer_id: card.peerId,
        what: card.what,
        due_ms: card.dueMs,
        source_message_id: card.sourceMessageId,
      })
      .then((res) => {
        if (res?.id) setCommitmentId(res.id);
      })
      .catch(() => {
        setSavedInDay(false);
      });
  };

  const handleDismissOffer = (e: React.MouseEvent) => {
    e.stopPropagation();
    rememberDismissed(card.sourceMessageId);
    setDismissed(true);
  };

  const formatOfferTime = (dueMs: number | null | undefined): string => {
    if (!dueMs) return '';
    const d = new Date(dueMs);
    const timeStr = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();
    const tomorrow = new Date(now);
    tomorrow.setDate(tomorrow.getDate() + 1);
    const isTomorrow = d.toDateString() === tomorrow.toDateString();

    if (isToday) return timeStr;
    if (isTomorrow) return t('commitment.tomorrowAt', { time: timeStr });
    return `${d.getDate()}.${String(d.getMonth() + 1).padStart(2, '0')} ${timeStr}`;
  };

  const handleDone = (e: React.MouseEvent) => {
    e.stopPropagation();
    const id = commitmentId ?? card.commitmentId;
    if (!id) return;
    setState('done');
    void phantomSelfApi.doneCommitment(id).catch(() => {
      setState(card.state);
    });
  };

  const handleOpenChat = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!peerId) return;
    if (onOpenChat) {
      onOpenChat(peerId);
      return;
    }
    useMessengerStore.getState().setActiveChat(peerId);
  };

  if (kind === 'offer') {
    if (dismissed) return null;
    if (savedInDay) {
      return (
        <div data-commitment-card="offer-saved" className="mt-1 flex items-center justify-end gap-1 select-none">
          <span data-testid="commitment-offer-saved" className="text-footnote font-medium text-ink-3 inline-flex items-center gap-1">
            <span>{t('commitment.inDaySaved')}</span>
            <span className="text-tint">✓</span>
          </span>
        </div>
      );
    }
    const timeText = formatOfferTime(card.dueMs);
    return (
      <div data-commitment-card="offer" className="mt-1 flex items-center justify-end gap-1.5 select-none">
        <button
          type="button"
          data-testid="commitment-offer-accept"
          onClick={handleAcceptOffer}
          className="touch-floor-inline inline-flex items-center gap-1.5 h-7 px-2.5 rounded-full bg-ink/[.06] hover:bg-ink/[.10] active:bg-ink/[.14] text-footnote font-medium text-ink transition-colors duration-quick"
        >
          <span className="text-tint">{t('commitment.inDay')}</span>
          {timeText ? (
            <>
              <span className="text-ink-3">·</span>
              <span className="text-ink-2">{timeText}</span>
            </>
          ) : null}
        </button>
        <button
          type="button"
          data-testid="commitment-offer-dismiss"
          onClick={handleDismissOffer}
          aria-label={t('commitment.drop')}
          className="touch-floor-inline inline-flex items-center justify-center w-7 h-7 rounded-full text-ink-3 hover:text-ink active:bg-ink/[.08] text-footnote transition-colors duration-quick"
        >
          ✕
        </button>
      </div>
    );
  }

  if (kind === 'choose' && card.candidates && card.candidates.length > 0) {
    return (
      <div data-commitment-card="choose" className="mt-2 flex flex-wrap items-center gap-2">
        {card.candidates.map((c) => (
          <button
            key={c.peerId}
            type="button"
            data-commitment-candidate={c.peerId}
            onClick={(e) => handleChoose(c.peerId, c.name, e)}
            className="touch-floor-inline relative h-9 px-3.5 rounded-full border border-line-strong text-subhead font-semibold text-ink active:bg-ink/[.06] transition-colors duration-quick"
          >
            <span>{c.name}</span>
            {c.hint ? <span className="text-footnote text-ink-3">{` · ${c.hint}`}</span> : null}
          </button>
        ))}
      </div>
    );
  }

  return (
    <div data-commitment-card={kind} className="mt-2 flex flex-wrap items-center justify-between gap-2">
      {chosenName ? <span className="text-footnote text-ink-2">{chosenName}</span> : null}
      {state === 'done' ? (
        <span data-testid="commitment-done-state" className="text-footnote text-ink-3">
          {t('commitment.doneState')}
        </span>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            data-testid="commitment-done"
            onClick={handleDone}
            className="touch-floor-inline relative h-9 px-3.5 rounded-full border border-line-strong text-subhead font-semibold text-ink active:bg-ink/[.06] transition-colors duration-quick"
          >
            {t('commitment.done')}
          </button>
          {peerId ? (
            <button
              type="button"
              data-testid="commitment-open-chat"
              onClick={handleOpenChat}
              className="touch-floor-inline relative h-9 px-3.5 rounded-full border border-line-strong text-subhead font-semibold text-ink active:bg-ink/[.06] transition-colors duration-quick"
            >
              {t('commitment.openChat')}
            </button>
          ) : null}
        </div>
      )}
    </div>
  );
};
