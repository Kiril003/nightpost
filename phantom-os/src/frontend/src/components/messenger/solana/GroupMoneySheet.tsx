import React, { useMemo, useState } from 'react';
import { Check, Coins, HandCoins } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { formatAmount, fromUnits, toUnits } from '../../../services/meetup/split';
import { sendBill } from '../../../services/meetupApi';
import { groupPayRef } from '../../../services/money/payNotes';
import { lastAsker, requestBill, splitRequest } from '../../../services/money/request';
import { askSolana, solanaApi } from '../../../services/solanaActions';
import { useMessengerStore } from '../../../stores/messengerStore';
import type { BillFile, BillToken } from '../../../types/meetup';
import { Avatar } from '../Avatar';
import { DoorRow, FormSheet, PrimaryButton } from '../FormSheet';

const TOKENS: readonly BillToken[] = ['USDC', 'SOL'];
const EMPTY_MESSAGES: readonly never[] = [];

function parseInputUnits(raw: string, token: BillToken): bigint | null {
  const clean = raw.trim().replace(',', '.').replace(/\s/g, '');
  if (!clean) return 0n;
  try {
    return toUnits(clean, token);
  } catch {
    return null;
  }
}

export interface GroupMoneyPrefill {
  token?: BillToken;
  total?: string;
  note?: string;
}

/** Частки на людину: 1–4 (ночі, порції); тап іде по колу. */
const MAX_WEIGHT = 4;

export const GroupMoneySheet: React.FC<{
  chatId: string;
  prefill?: GroupMoneyPrefill;
  file?: BillFile;
  peer?: { id: string; name: string };
  onClose: () => void;
}> = ({ chatId, prefill, file, peer, onClose }) => {
  const { t } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const messages = chat?.messages ?? EMPTY_MESSAGES;

  const peers = useMemo(
    () => {
      const list = (chat?.members ?? []).filter((m) => !m.isMe && m.id !== me.id);
      return list.length === 0 && peer ? [{ id: peer.id, name: peer.name, handle: '', avatar: '' }] : list;
    },
    [chat?.members, me.id, peer],
  );

  const asker = useMemo(() => lastAsker(messages, me.id), [messages, me.id]);

  const [step, setStep] = useState<'pick' | 'request' | 'pay'>(file ? 'request' : 'pick');
  const [token, setToken] = useState<BillToken>(() => prefill?.token ?? 'USDC');
  const [totalText, setTotalText] = useState(() => prefill?.total ?? '');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set(peers.map((p) => p.id)));
  const [weights, setWeights] = useState<Record<string, number>>({});
  const [fixedText, setFixedText] = useState<Record<string, string>>({});
  const [reqNote, setReqNote] = useState(() => prefill?.note ?? file?.name ?? '');
  const [reqError, setReqError] = useState(false);
  const [reqBusy, setReqBusy] = useState(false);

  const [payPeerId, setPayPeerId] = useState<string | null>(() =>
    asker && peers.some((p) => p.id === asker.peerId) ? asker.peerId : null,
  );
  const [payNote, setPayNote] = useState(() => prefill?.note ?? '');
  const [payCant, setPayCant] = useState(false);
  const [payBusy, setPayBusy] = useState(false);

  const totalUnits = useMemo(() => {
    const u = parseInputUnits(totalText, token);
    return u !== null && u > 0n ? u : 0n;
  }, [totalText, token]);

  const activeIds = useMemo(
    () => peers.filter((p) => selectedIds.has(p.id)).map((p) => p.id),
    [peers, selectedIds],
  );

  const splitResult = useMemo(() => {
    if (totalUnits <= 0n || activeIds.length === 0) return new Map<string, bigint>();
    const fixedMap = new Map<string, bigint>();
    for (const id of activeIds) {
      if (fixedText[id] !== undefined) {
        const u = parseInputUnits(fixedText[id], token);
        if (u === null) return null;
        fixedMap.set(id, u);
      }
    }
    return splitRequest(totalUnits, activeIds, fixedMap, new Map(Object.entries(weights)));
  }, [totalUnits, activeIds, fixedText, token, weights]);

  const overTotal = totalUnits > 0n && activeIds.length > 0 && splitResult === null;

  const hasPositiveRow = useMemo(() => {
    if (!splitResult) return false;
    for (const v of splitResult.values()) {
      if (v > 0n) return true;
    }
    return false;
  }, [splitResult]);

  const toggleMember = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setFixedText((prev) => {
      if (!(id in prev)) return prev;
      const copy = { ...prev };
      delete copy[id];
      return copy;
    });
  };

  const handleTokenChange = (nextToken: BillToken) => {
    setToken(nextToken);
    setFixedText({});
  };

  const handleSendRequest = async () => {
    if (!splitResult || totalUnits <= 0n || !hasPositiveRow || reqBusy) return;
    if (useMessengerStore.getState().activeChatId !== chatId) {
      setReqError(true);
      return;
    }
    setReqBusy(true);
    setReqError(false);
    try {
      const people = [
        { id: me.id, name: me.name },
        ...peers.map((p) => ({ id: p.id, name: p.name })),
      ];
      const bill = requestBill({
        meId: me.id,
        people,
        amounts: splitResult,
        token,
        note: reqNote,
        file,
      });
      await sendBill(bill);
      onClose();
    } catch {
      setReqError(true);
      setReqBusy(false);
    }
  };

  const handleNextPay = async () => {
    if (!payPeerId || payBusy) return;
    const member = peers.find((p) => p.id === payPeerId);
    if (!member) return;
    setPayBusy(true);
    setPayCant(false);
    try {
      const res = await solanaApi.peer(member.id);
      if (res && res.can_pay && res.address) {
        const matchAsker = asker && asker.peerId === member.id ? asker : null;
        const cleanNote = payNote.trim();
        askSolana({
          kind: 'pay',
          peer: { id: member.id, name: member.name },
          cluster: res.cluster,
          chat: {
            id: chatId,
            ref: matchAsker ? matchAsker.ref : groupPayRef(),
            ...(matchAsker ? { amount: fromUnits(matchAsker.due, matchAsker.token), token: matchAsker.token } : {}),
            ...(cleanNote ? { note: cleanNote } : {}),
          },
        });
      } else {
        setPayCant(true);
        setPayBusy(false);
      }
    } catch {
      setPayCant(true);
      setPayBusy(false);
    }
  };

  const chip = (on: boolean) =>
    `touch-floor min-h-[44px] flex-1 px-3 rounded-chip border text-subhead font-semibold transition-colors duration-instant ease-standard ${
      on ? 'bg-overlay border-transparent text-ink' : 'border-line-strong text-ink-2'
    }`;

  if (peers.length === 0) {
    return (
      <FormSheet open onClose={onClose} title={t('money.title')} testId="group-money-sheet">
        <p className="px-4 py-6 text-callout text-ink-2">{t('money.noMembers')}</p>
      </FormSheet>
    );
  }

  if (step === 'pick') {
    return (
      <FormSheet open onClose={onClose} title={t('money.title')} testId="group-money-sheet">
        <div className="py-2">
          <div data-testid="group-money-request">
            <DoorRow
              icon={<HandCoins className="w-5 h-5" strokeWidth={1.75} />}
              label={t('money.request')}
              onClick={() => setStep('request')}
            />
          </div>
          <div data-testid="group-money-pay">
            <DoorRow
              icon={<Coins className="w-5 h-5" strokeWidth={1.75} />}
              label={t('messageComposer.tilePay')}
              onClick={() => {
                setPayPeerId(asker && peers.some((p) => p.id === asker.peerId) ? asker.peerId : null);
                setPayCant(false);
                setStep('pay');
              }}
            />
          </div>
        </div>
      </FormSheet>
    );
  }

  if (step === 'request') {
    const canSubmit = totalUnits > 0n && hasPositiveRow && !overTotal && !reqBusy;
    const footerLabel =
      totalUnits > 0n
        ? t('money.requestAmount', { amount: formatAmount(totalUnits, token) })
        : t('money.request');

    return (
      <FormSheet
        open
        onClose={onClose}
        onBack={() => setStep('pick')}
        title={t('money.request')}
        testId="group-money-sheet"
        footer={
          <PrimaryButton disabled={!canSubmit} onClick={handleSendRequest}>
            {footerLabel}
          </PrimaryButton>
        }
      >
        <div className="px-4 pb-2 space-y-3">
          <div className="flex gap-2" role="group" aria-label={t('payMoneySheet.coin')}>
            {TOKENS.map((c) => (
              <button
                key={c}
                type="button"
                data-testid={`group-coin-${c}`}
                aria-pressed={token === c}
                className={chip(token === c)}
                onClick={() => handleTokenChange(c)}
              >
                {c}
              </button>
            ))}
          </div>
          <label className="block">
            <span className="block text-footnote font-semibold text-ink-3 mb-1">{t('money.total')}</span>
            <input
              data-testid="group-request-total"
              inputMode="decimal"
              autoComplete="off"
              value={totalText}
              onChange={(e) => setTotalText(e.target.value)}
              placeholder="0"
              className="w-full h-12 px-4 rounded-control bg-raised text-title-2 text-ink tnum outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
            />
          </label>
          <ul className="divide-y divide-line">
            {peers.map((p) => {
              const checked = selectedIds.has(p.id);
              const computed = splitResult?.get(p.id);
              const val = !checked
                ? ''
                : fixedText[p.id] !== undefined
                  ? fixedText[p.id]
                  : computed !== undefined && computed > 0n
                    ? fromUnits(computed, token)
                    : '';
              return (
                <li key={p.id} className="min-h-12 py-1.5 flex items-center gap-3">
                  <button
                    type="button"
                    role="checkbox"
                    aria-checked={checked}
                    data-testid={`group-request-check-${p.id}`}
                    onClick={() => toggleMember(p.id)}
                    className={`touch-floor-inline w-6 h-6 shrink-0 rounded-md flex items-center justify-center transition-colors ${
                      checked ? 'bg-ink text-canvas' : 'text-transparent border border-line-strong'
                    }`}
                  >
                    <Check className="w-4 h-4" strokeWidth={2.25} />
                  </button>
                  <Avatar name={p.name} tone={p.id} className="w-7 h-7 text-caption shrink-0" />
                  <span className="flex-1 min-w-0 text-callout text-ink truncate">{p.name}</span>
                  {checked && fixedText[p.id] === undefined && (
                    <button
                      type="button"
                      data-testid={`group-request-weight-${p.id}`}
                      aria-label={t('money.weight')}
                      onClick={() =>
                        setWeights((prev) => ({ ...prev, [p.id]: ((prev[p.id] ?? 1) % MAX_WEIGHT) + 1 }))
                      }
                      className={`touch-floor-inline h-9 min-w-11 px-2 rounded-control text-callout tnum transition-colors ${
                        (weights[p.id] ?? 1) > 1 ? 'bg-accent-soft text-ink' : 'bg-raised text-ink-3'
                      }`}
                    >
                      ×{weights[p.id] ?? 1}
                    </button>
                  )}
                  <input
                    data-testid={`group-request-amount-${p.id}`}
                    inputMode="decimal"
                    autoComplete="off"
                    disabled={!checked}
                    value={val}
                    onChange={(e) => {
                      const v = e.target.value;
                      setFixedText((prev) => ({ ...prev, [p.id]: v }));
                    }}
                    placeholder="0"
                    className="w-24 h-9 px-2.5 rounded-control bg-raised text-right text-callout text-ink tnum outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line disabled:opacity-40"
                  />
                </li>
              );
            })}
          </ul>
          {overTotal && <p className="text-callout text-danger">{t('money.overTotal')}</p>}
          <label className="block">
            <span className="block text-footnote font-semibold text-ink-3 mb-1">{t('money.note')}</span>
            <input
              data-testid="group-request-note"
              type="text"
              maxLength={80}
              value={reqNote}
              onChange={(e) => setReqNote(e.target.value)}
              className="w-full h-11 px-3.5 rounded-control bg-raised text-callout text-ink outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
            />
          </label>
          {reqError && <p className="text-callout text-danger">{t('settle.failed')}</p>}
        </div>
      </FormSheet>
    );
  }

  return (
    <FormSheet
      open
      onClose={onClose}
      onBack={() => setStep('pick')}
      title={t('messageComposer.tilePay')}
      testId="group-money-sheet"
      footer={
        <PrimaryButton disabled={!payPeerId || payBusy} onClick={handleNextPay}>
          {t('money.next')}
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-2 space-y-3">
        <ul className="divide-y divide-line" role="radiogroup">
          {peers.map((p) => {
            const on = payPeerId === p.id;
            return (
              <li key={p.id}>
                <button
                  type="button"
                  role="radio"
                  aria-checked={on}
                  data-testid={`group-pay-member-${p.id}`}
                  onClick={() => {
                    setPayPeerId(p.id);
                    setPayCant(false);
                  }}
                  className="w-full min-h-12 py-1.5 flex items-center gap-3 text-left"
                >
                  <span
                    className={`w-5 h-5 rounded-full flex items-center justify-center border ${
                      on ? 'border-ink bg-ink' : 'border-line-strong'
                    }`}
                  >
                    {on && <span className="w-2 h-2 rounded-full bg-canvas" />}
                  </span>
                  <Avatar name={p.name} tone={p.id} className="w-7 h-7 text-caption shrink-0" />
                  <span className="flex-1 min-w-0 text-callout text-ink truncate">{p.name}</span>
                </button>
              </li>
            );
          })}
        </ul>
        <label className="block">
          <span className="block text-footnote font-semibold text-ink-3 mb-1">{t('money.note')}</span>
          <input
            data-testid="group-pay-note"
            type="text"
            maxLength={80}
            value={payNote}
            onChange={(e) => setPayNote(e.target.value)}
            className="w-full h-11 px-3.5 rounded-control bg-raised text-callout text-ink outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
          />
        </label>
        {payCant && <p className="text-callout text-danger">{t('money.cantPay')}</p>}
      </div>
    </FormSheet>
  );
};

export default GroupMoneySheet;
