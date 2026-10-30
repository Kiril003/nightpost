import React, { useEffect, useMemo, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { formatAmount, fromUnits } from '../../services/meetup/split';
import { canPayHere, meetupApi } from '../../services/meetupApi';
import { chatLedger, settleRef } from '../../services/money/ledger';
import { useMessengerStore } from '../../stores/messengerStore';
import { useSettleUp } from '../../stores/settleUpStore';
import { Avatar } from './Avatar';
import { FormSheet, PrimaryButton } from './FormSheet';

const EMPTY: readonly never[] = [];

export const SettleUpHost: React.FC = () => {
  const { t } = useTranslation();
  const chatId = useSettleUp((s) => s.chatId);
  const close = useSettleUp((s) => s.close);
  const meId = useMessengerStore((s) => s.currentUser.id);
  const chat = useMessengerStore((s) => (chatId ? s.chats.find((c) => c.id === chatId) : undefined));
  const messages = chat?.messages ?? EMPTY;

  const ledger = useMemo(() => chatLedger(messages, meId), [messages, meId]);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [chatId, messages]);

  useEffect(() => {
    if (chatId && ledger.bills === 0) close();
  }, [chatId, ledger.bills, close]);

  const sorted = useMemo(() => {
    const mine = ledger.transfers.filter((tr) => tr.from === meId || tr.to === meId);
    const others = ledger.transfers.filter((tr) => tr.from !== meId && tr.to !== meId);
    return [...mine, ...others];
  }, [ledger.transfers, meId]);

  const myLargestOpen = useMemo(() => {
    const openOut = ledger.transfers.filter((tr) => tr.from === meId && tr.state === 'open');
    if (openOut.length === 0) return null;
    return openOut.reduce((best, cur) => (cur.token === best.token && cur.units > best.units ? cur : best), openOut[0]);
  }, [ledger.transfers, meId]);

  const nameOf = (id: string) => (id === meId ? t('billCard.you') : ledger.names[id] ?? t('billCard.someone'));

  const handlePay = async () => {
    if (!myLargestOpen || !chatId || busy) return;
    setFailed(false);
    setBusy(true);
    try {
      const res = await meetupApi.pay({
        peer_id: myLargestOpen.to,
        token: myLargestOpen.token,
        amount: fromUnits(myLargestOpen.units, myLargestOpen.token),
        chat_id: chatId,
        ref: settleRef(myLargestOpen.to),
      });
      if (res?.opened !== false) close();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const footer =
    myLargestOpen && canPayHere() ? (
      <PrimaryButton onClick={handlePay} disabled={busy}>
        {t('settle.pay', { amount: formatAmount(myLargestOpen.units, myLargestOpen.token) })}
      </PrimaryButton>
    ) : undefined;

  return (
    <FormSheet open={!!chatId && ledger.bills > 0} onClose={close} title={t('settle.title')} testId="settle-up-sheet" footer={footer}>
      {sorted.length === 0 ? (
        <p className="px-4 py-6 text-callout text-ink-2">{t('settle.settled')}</p>
      ) : (
        <ul className="px-4 py-2">
          {sorted.map((tr, idx) => (
            <li key={`${tr.from}-${tr.to}-${tr.token}-${tr.state}-${idx}`} className="min-h-12 flex items-center gap-2 py-1.5">
              <Avatar name={nameOf(tr.from)} tone={tr.from} className="w-7 h-7 text-caption" />
              <span className="text-callout text-ink truncate">{nameOf(tr.from)}</span>
              <ArrowRight className="w-4 h-4 text-ink-3 shrink-0" strokeWidth={1.75} />
              <Avatar name={nameOf(tr.to)} tone={tr.to} className="w-7 h-7 text-caption" />
              <span className="flex-1 min-w-0 flex flex-wrap items-baseline gap-x-2">
                <span className="text-callout text-ink truncate">{nameOf(tr.to)}</span>
                {tr.state === 'pending' && <span className="text-footnote text-ink-3">{t('settle.pending')}</span>}
              </span>
              <span className="text-callout font-medium text-ink tnum shrink-0">{formatAmount(tr.units, tr.token)}</span>
            </li>
          ))}
        </ul>
      )}
      {failed && <p className="px-4 pb-2 text-footnote text-danger">{t('settle.failed')}</p>}
    </FormSheet>
  );
};
