import React, { useEffect, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { onWhereChanged, whereApi, wherePeerOf, type WhereState } from '../../services/peerWhere';
import { chipAction, isWhereAsk, whereChips, type WhereChip } from '../../services/whereAsk';
import type { Chat, Message } from '../../types/messenger';
import { FilterChip } from './kit/FilterChip';

const STORAGE_KEY = 'np.whereAskDismissed';
const FRESH_MS = 2 * 3600_000;

const readDismissed = (): string[] => {
  try {
    const parsed: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

const dismiss = (id: string): void => {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([...readDismissed().filter((x) => x !== id), id].slice(-50)));
  } catch {
    /* без сховища чипи сховає стан компонента */
  }
};

/** Мс до кінця свіжості питання; null — без часу відправлення чипів не буде. */
const freshLeft = (msg: Pick<Message, 'sentAt'>, now: number): number | null => {
  const at = msg.sentAt ? Date.parse(msg.sentAt) : NaN;
  if (Number.isNaN(at)) return null;
  const left = at + FRESH_MS - now;
  return left > 0 ? left : null;
};

const LABEL: Record<WhereChip['id'], MessageKey> = {
  exact15: 'whereAsk.exact15',
  area60: 'whereAsk.area60',
  meet: 'whereAsk.meet',
  notNow: 'whereAsk.notNow',
};

export const WhereAskChips: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const peerId = wherePeerOf(chat)?.id ?? null;
  const messages = Array.isArray(chat.messages) ? chat.messages : [];
  const last = messages.length > 0 ? messages[messages.length - 1] : undefined;
  const [now, setNow] = useState(() => Date.now());
  const [done, setDone] = useState<string | null>(null);
  const [state, setState] = useState<{ id: string; where: WhereState } | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [whereTick, setWhereTick] = useState(0);

  useEffect(() => onWhereChanged(() => setWhereTick((n) => n + 1)), []);

  const left = last ? freshLeft(last, now) : null;
  const askId =
    peerId && last && !last.isSelf && last.text && isWhereAsk(last.text) && left !== null && done !== last.id && !readDismissed().includes(last.id)
      ? last.id
      : null;

  useEffect(() => {
    if (left === null) return;
    const timer = setTimeout(() => setNow(Date.now()), left + 50);
    return () => clearTimeout(timer);
  }, [left]);

  useEffect(() => {
    setFailed(false);
    if (!peerId || !askId) return;
    let alive = true;
    whereApi
      .get(peerId)
      .then((where) => {
        if (alive) setState({ id: askId, where });
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [peerId, askId, whereTick]);

  if (!peerId || !askId || state?.id !== askId) return null;
  const chips = whereChips(state.where);
  if (chips.length === 0) return null;

  const tap = async (chip: WhereChip) => {
    if (busy) return;
    setFailed(false);
    setBusy(true);
    try {
      await chipAction(chip, peerId, Date.now());
      dismiss(askId);
      setDone(askId);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-testid="where-ask-chips" className="flex items-center gap-2 px-4 py-1.5 overflow-x-auto no-scrollbar bg-canvas border-t border-line">
      {chips.map((chip) => (
        <FilterChip key={chip.id} role="button" testId={`where-ask-chip-${chip.id}`} disabled={busy} onClick={() => void tap(chip)}>
          {t(LABEL[chip.id])}
        </FilterChip>
      ))}
      {failed && (
        <span data-testid="where-ask-failed" className="text-footnote text-danger whitespace-nowrap">
          {t('whereAsk.failed')}
        </span>
      )}
    </div>
  );
};
