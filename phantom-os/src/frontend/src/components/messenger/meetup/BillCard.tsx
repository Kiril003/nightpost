/**
 * Картка рахунку (`widget:bill`): один рядок стану — «2 з 3 оплатили ·
 * 18.50 USDC» — і частка кожного. «Оплачено» лише з переказу, який
 * підтвердила мережа й який посилається на цей рахунок; перемикача немає.
 */
import React, { useEffect, useMemo, useState } from 'react';
import { ReceiptText } from 'lucide-react';
import { TicketCard } from '../kit/TicketCard';
import { TestPayTag } from '../kit/TestPayTag';
import { Button } from '../kit/Button';
import { displayUnits, formatAmount, tableOf } from '../../../services/meetup/split';
import { canPayHere, claimPaidShareOnce, setCardMark } from '../../../services/meetupApi';
import { SPREAD_VALUE, readCardMarks } from '../../../services/meetup/marks';
import { escrowBill, usePayPeer, type EscrowBill } from '../../../services/solanaActions';
import { chatLedger } from '../../../services/money/ledger';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSettleUp } from '../../../stores/settleUpStore';
import { Avatar } from '../Avatar';
import type { Message } from '../../../types/messenger';
import { BillSheet } from './BillSheet';
import { useBillView } from './billModel';
import { billStatus, shareWord, showsDue } from './words';

import { useTranslation } from '../../../i18n/useTranslation';

const PAYABLE = ['unpaid', 'short', 'failed', 'mismatch', 'wrong_token', 'wrong_payee'];
const EMPTY_MESSAGES: readonly Message[] = [];

export const BillCard: React.FC<{ msg: Message; meId: string }> = ({ msg, meId }) => {
  const { t } = useTranslation();
  const view = useBillView(msg, meId);
  const [spreadBusy, setSpreadBusy] = useState(false);
  const [spreadError, setSpreadError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [chain, setChain] = useState<EscrowBill | null>(null);
  const chatMessages = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id))?.messages ?? EMPTY_MESSAGES);
  const ledger = useMemo(() => chatLedger(chatMessages, meId), [chatMessages, meId]);
  const showSettle = ledger.bills >= 2 && ledger.transfers.some((tr) => tr.state === 'open' && (tr.from === meId || tr.to === meId));
  const mine = view?.settlement.people.find((p) => p.id === meId);
  const escrowAddress = view?.bill.mode === 'escrow' ? view.bill.escrow?.address : undefined;

  useEffect(() => {
    setChain(null);
    if (!escrowAddress) return;
    let alive = true;
    escrowBill(escrowAddress).then(
      (res) => {
        if (alive) setChain(res);
      },
      () => {
        if (alive) setChain(null);
      },
    );
    return () => {
      alive = false;
    };
  }, [escrowAddress]);

  useEffect(() => {
    if (!escrowAddress || chain?.status !== 'open') return;
    let alive = true;
    // Мовчання мережі між опитуваннями не стирає останній прочитаний стан.
    const id = setInterval(() => {
      escrowBill(escrowAddress).then(
        (res) => {
          if (alive && res) setChain(res);
        },
        () => {},
      );
    }, 30_000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [escrowAddress, chain?.status]);

  // Моя частка оплачена мережею — заявка на бали «перша оплачена частка».
  // Сервер сам звірить підпис і дасть бали раз; тут — щоб не питати двічі.
  useEffect(() => {
    if (view && mine?.state === 'paid' && mine.signature) claimPaidShareOnce(mine.signature, view.bill.id);
  }, [view, mine?.state, mine?.signature]);

  if (!view) return <TicketCard testId="card-unreadable" title={t('billCard.bill')} status={t('billCard.unreadable')} />;
  const { bill, split, settlement } = view;
  const escrowLabel =
    bill.mode === 'escrow' && bill.escrow && chain
      ? chain.status === 'open'
        ? t('billEscrow.open', { paid: chain.paid, total: chain.total })
        : t(`billEscrow.${chain.status}`)
      : null;
  const nameOf = (id: string, name: string | null) => (id === meId ? t('billCard.you') : name ?? t('billCard.someone'));
  const payable = !!mine && mine.due > 0n && PAYABLE.includes(mine.state) && canPayHere();
  const toPay = mine ? (mine.state === 'short' && mine.missing !== undefined ? mine.missing : mine.due) : 0n;
  const isPayee = bill.payee.kind === 'peer' && bill.payee.id === meId;
  // Мережу рахунку кажуть гаманець, яким людина зараз платитиме, його перекази
  // і застава (escrowState пускає її лише в devnet); немає нічого — мовчимо.
  const { pay: payWallet } = usePayPeer(payable && bill.payee.kind === 'peer' ? { id: bill.payee.id, name: '' } : null);
  const testPay =
    bill.mode === 'escrow' ||
    (!!payWallet && payWallet.cluster !== 'mainnet') ||
    chatMessages.some((m) => m.payment?.ref === bill.id && m.payment.cluster !== 'mainnet');
  const canSpread = isPayee && tableOf(bill, msg.cardMarks).length > 0;
  const spread = isPayee && readCardMarks(msg.cardMarks).spread.has(meId);
  const toggleSpread = async () => {
    setSpreadBusy(true);
    setSpreadError(await setCardMark(msg, meId, SPREAD_VALUE));
    setSpreadBusy(false);
  };

  return (
    <>
      <TicketCard
        testId="card-bill"
        kind={{ label: t('billCard.bill'), icon: <ReceiptText strokeWidth={1.75} />, tag: testPay ? <TestPayTag /> : undefined }}
        perforated
        title={bill.title || (bill.asks ? t('money.request') : t('billCard.bill'))}
        status={billStatus(settlement, split, formatAmount(split.total, bill.token))}
        action={payable ? undefined : { label: t('billCard.open'), onClick: () => setOpen(true) }}
      >
        <ul className="px-4">
          {settlement.people.map((p) => (
            <li
              key={p.id}
              data-testid={`bill-person-${p.id}`}
              className="min-h-10 flex items-center gap-3"
            >
              <Avatar name={nameOf(p.id, p.name)} tone={p.id} className="w-7 h-7 text-caption" />
              <span className="flex-1 min-w-0 flex flex-wrap items-baseline gap-x-2">
                <span className="text-callout text-ink break-words">{nameOf(p.id, p.name)}</span>
                <span className="text-footnote text-ink-3">
                  {p.state === 'short' ? t('words.short', { amount: displayUnits(p.missing ?? 0n, bill.token) }) : shareWord(p.state, bill.token, p.missing)}
                </span>
              </span>
              {(showsDue(p.state) || (p.state === 'payee' && p.due > 0n)) && (
                <span data-bill-due className="text-callout font-medium text-ink tnum shrink-0">
                  {displayUnits(p.due, bill.token)}
                </span>
              )}
            </li>
          ))}
        </ul>
        {escrowLabel && (
          <p data-testid="bill-escrow" className="px-4 pt-1 text-caption text-ink-3">
            {escrowLabel}
          </p>
        )}
        {bill.file && (
          <p data-testid="bill-file" className="px-4 pt-1 text-caption text-ink-3 truncate">
            {t('money48.file', { name: bill.file.name, hash: bill.file.sha256.slice(0, 8) })}
          </p>
        )}
        {split.unclaimed.length > 0 && (
          <div data-unclaimed-line className="px-4 pt-1 flex items-center justify-between gap-3">
            <p className="text-caption text-ink-3">
              {t('billCard.unclaimed', { n: split.unclaimed.length, amount: displayUnits(split.unclaimedTotal, bill.token) })}
            </p>
            {canSpread && (
              <Button variant="text" testId="bill-spread" disabled={spreadBusy} onClick={(e) => { e.stopPropagation(); void toggleSpread(); }}>
                {t('money33.spread')}
              </Button>
            )}
          </div>
        )}
        {spread && (
          <div className="px-4 pt-1 flex justify-end">
            <Button variant="text" testId="bill-unspread" disabled={spreadBusy} onClick={(e) => { e.stopPropagation(); void toggleSpread(); }}>
              {t('money33.undo')}
            </Button>
          </div>
        )}
        {spreadError && (
          <p data-testid="bill-spread-error" className="px-4 pt-1 text-footnote text-danger">
            {spreadError}
          </p>
        )}
        {payable && (
          <div className="mx-4 mt-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
            <span data-testid="bill-card-share" className="whitespace-nowrap text-callout text-ink-2">
              {t('talk.yourShare')} <span className="text-ink tnum">{displayUnits(toPay, bill.token)}</span>
            </span>
            <Button
              variant="primary"
              testId="bill-card-pay"
              className="ml-auto shrink-0 tnum"
              onClick={(e) => {
                e.stopPropagation();
                setOpen(true);
              }}
            >
              {t('talk.payShare', { amount: displayUnits(toPay, bill.token) })}
            </Button>
          </div>
        )}
        {showSettle && (
          <div className="px-4 pt-2 flex justify-end">
            <Button
              variant="text"
              testId="bill-card-settle"
              onClick={(e) => {
                e.stopPropagation();
                useSettleUp.getState().open(view.chatId);
              }}
            >
              {t('settle.title')}
            </Button>
          </div>
        )}
      </TicketCard>
      <BillSheet open={open} onClose={() => setOpen(false)} msg={msg} meId={meId} />
    </>
  );
};
