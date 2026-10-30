/**
 * Аркуші рахунку.
 *
 * `BillSheet` — рахунок, що вже в розмові: позначити свої страви (`dish:<id>`),
 * побачити частки й нічиє, «Оплатити мою частку» — прохання до телефона
 * переказати отримувачу рахунку з `chat_id` і `ref`. Оплаченим частку робить
 * лише мережа.
 *
 * `BillDraftSheet` — скласти рахунок із зустрічі: страви з меню закладу (або
 * вручну, якщо меню немає), кому платять (організатору; закладу — лише коли
 * сторінка має гаманець), і надіслати карткою `widget:bill`.
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Minus, Plus, X } from 'lucide-react';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { CheckDot } from '../cards/CardShell';
import { useMessengerStore } from '../../../stores/messengerStore';
import { cashValue, dishValue, readCardMarks } from '../../../services/meetup/marks';
import { isSettled } from '../../../services/meetup/payments';
import { billFromMeetup, cameOf } from '../../../services/meetup/draft';
import { formatAmount, toUnits } from '../../../services/meetup/split';
import {
  BILL_KIND,
  isDecimal,
  hasVenuePage,
  parseMeetupData,
  type BillItem,
  type BillMode,
  type BillToken,
} from '../../../types/meetup';
import { escrow } from '../../../services/solanaActions';
import type { Message } from '../../../types/messenger';
import {
  canPayHere,
  cardKindRefusal,
  meetupApi,
  menuOf,
  refusalWords,
  sendBill,
  setCardMark,
  sharePayAsk,
} from '../../../services/meetupApi';
import { useBillView } from './billModel';
import { billStatus, newCardId, shareWord, showsDue } from './words';

import { useTranslation } from '../../../i18n/useTranslation';
const Label: React.FC<{ children: React.ReactNode; testId?: string }> = ({ children, testId }) => (
  <h3 className="px-4 pt-4 pb-1 text-caption text-ink-3" data-testid={testId}>
    {children}
  </h3>
);

export const BillSheet: React.FC<{ open: boolean; onClose: () => void; msg: Message; meId: string }> = ({
  open,
  onClose,
  msg,
  meId,
}) => {
  const { t } = useTranslation();
  const view = useBillView(msg, meId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [asked, setAsked] = useState<string | null>(null);
  if (!open || !view) return null;
  const { bill, split, settlement, chatId } = view;
  const st = readCardMarks(msg.cardMarks);
  const mine = settlement.people.find((p) => p.id === meId);
  const nameOf = (id: string, name: string | null) => (id === meId ? t('billSheet.you') : name ?? t('billSheet.someone'));
  const iAmPayee = bill.payee.kind === 'peer' && bill.payee.id === meId;
  const payee =
    bill.payee.kind === 'venue' ? t('billSheet.venue') : nameOf(bill.payee.id, bill.people.find((p) => p.id === (bill.payee as { id: string }).id)?.name ?? null);

  const tick = async (itemId: string) => {
    setBusy(true);
    setError(null);
    const said = await setCardMark(msg, meId, dishValue(itemId));
    setBusy(false);
    if (said) setError(said);
  };

  const toggleCash = async (personId: string) => {
    setBusy(true);
    setError(null);
    const said = await setCardMark(msg, meId, cashValue(personId));
    setBusy(false);
    if (said) setError(said);
  };

  const payable = !!mine && mine.due > 0n && ['unpaid', 'short', 'failed', 'mismatch', 'wrong_token', 'wrong_payee'].includes(mine.state);
  // Уже підтверджене лічиться: при «бракує» просимо лише різницю, а не всю частку знову.
  const toPay = mine ? (mine.state === 'short' && mine.missing !== undefined ? mine.missing : mine.due) : 0n;
  const onPhone = canPayHere();
  const pay = async () => {
    if (!mine || !payable || !onPhone || toPay <= 0n) return;
    setBusy(true);
    setError(null);
    try {
      const res = await meetupApi.pay(sharePayAsk(bill, toPay, chatId));
      // Переказ живе в гаманці; після нього людина вертається в розмову, а не
      // в аркуш із тією самою кнопкою «Оплатити». Стан частки скаже картка.
      if (res?.opened !== false) {
        onClose();
        return;
      }
      setAsked(t('billSheet.walletOpened'));
    } catch (e) {
      setError(refusalWords(e, 'svc.meetup.what.openShare'));
    } finally {
      setBusy(false);
    }
  };

  const payNote = !onPhone
    ? t('billSheet.phoneOnly')
    : asked ?? (mine?.state === 'pending' ? t('billSheet.transferWaiting') : null);

  return (
    <FormSheet
      open
      onClose={onClose}
      title={bill.title || t('billSheet.bill')}
      tall
      testId="bill-sheet"
      footer={
        mine && showsDue(mine.state) && !isSettled(mine.state) ? (
          <div>
            <button
              type="button"
              data-testid="bill-pay"
              disabled={!payable || !onPhone || busy}
              onClick={() => void pay()}
              className="w-full h-12 min-h-0 rounded-control text-headline bg-accent text-on-accent disabled:opacity-40 active:bg-accent-press"
            >
              {mine.state === 'short' ? t('billSheet.payRest') : t('billSheet.payMine')} {formatAmount(toPay, bill.token)}
            </button>
            {payNote && (
              <p className="pt-2 text-footnote text-ink-3" data-testid="bill-pay-note">
                {payNote}
              </p>
            )}
          </div>
        ) : undefined
      }
    >
      <p className="px-4 text-footnote text-ink-3">
        {t('billSheet.statusPayee', { status: billStatus(settlement, split, formatAmount(split.total, bill.token)), payee })}
      </p>

      <Label>{t('billSheet.markYours')}</Label>
      {bill.items.map((it) => {
        const holders = st.dishes.get(it.id) ?? [];
        const minePick = holders.includes(meId);
        const others = holders.filter((h) => h !== meId).length;
        return (
          <button
            key={it.id}
            type="button"
            role="checkbox"
            aria-checked={minePick}
            data-testid={`bill-dish-${it.id}`}
            disabled={busy}
            onClick={() => void tick(it.id)}
            className="w-full min-h-12 px-4 flex items-center gap-3 text-left active:bg-overlay disabled:opacity-60"
          >
            <CheckDot done={minePick} />
            <span className="flex-1 min-w-0">
              <span className="block text-callout text-ink truncate">{it.name}</span>
              {others > 0 && (
                <span className="block text-footnote text-ink-3">
                  {minePick ? t('billSheet.splitWith', { n: holders.length }) : t('billSheet.markedBy', { n: others })}
                </span>
              )}
            </span>
            <span className="text-callout text-ink tnum shrink-0">{formatAmount(toUnits(it.price, bill.token), bill.token)}</span>
          </button>
        );
      })}

      {split.unclaimed.length > 0 && (
        <div data-testid="bill-unclaimed">
          <Label>{t('billSheet.unclaimedLabel')}</Label>
          {split.unclaimed.map((it) => (
            <div key={it.id} className="min-h-10 px-4 flex items-center gap-3">
              <span className="flex-1 min-w-0 text-callout text-ink-2 truncate">{it.name}</span>
              <span className="text-callout text-ink-2 tnum">{formatAmount(toUnits(it.price, bill.token), bill.token)}</span>
            </div>
          ))}
        </div>
      )}

      <Label>{t('billSheet.shares')}</Label>
      {settlement.people.map((p) => {
        const cashPressed = (st.cash.get(p.id) ?? []).includes(meId);
        return (
          <div key={p.id} data-testid={`bill-share-${p.id}`} className="min-h-10 px-4 flex items-center gap-3">
            <span className="flex-1 min-w-0 text-callout text-ink truncate">{nameOf(p.id, p.name)}</span>
            {showsDue(p.state) && <span className="text-callout text-ink tnum">{formatAmount(p.due, bill.token)}</span>}
            {iAmPayee && p.id !== meId && !['payee', 'nothing', 'unmarked', 'paid', 'unverified', 'pending'].includes(p.state) && (
              <button
                type="button"
                data-testid={`bill-cash-${p.id}`}
                aria-pressed={cashPressed}
                disabled={busy}
                onClick={() => void toggleCash(p.id)}
                className={`h-11 min-h-0 px-4 rounded-full text-footnote font-semibold disabled:opacity-40 ${
                  cashPressed ? 'bg-accent text-on-accent' : 'bg-surface text-ink'
                }`}
              >
                {cashPressed ? t('money19.undoCash') : t('money19.gotCash')}
              </button>
            )}
            <span className="text-caption text-ink-3 w-28 text-right">{shareWord(p.state, bill.token, p.missing)}</span>
          </div>
        );
      })}
      {error && (
        <p className="px-4 pt-2 text-footnote text-danger" data-testid="bill-error">
          {error}
        </p>
      )}
      <div className="h-4" />
    </FormSheet>
  );
};

// ── складання рахунку з зустрічі ─────────────────────────────────────────

interface Line extends BillItem {
  count: number;
}

/** Страви з кількістю → позиції рахунку: «Латте», «Латте · 2» — кожна зі своїм id. */
function expandLines(lines: readonly Line[]): BillItem[] {
  const out: BillItem[] = [];
  for (const l of lines) {
    for (let k = 1; k <= l.count; k++) {
      out.push({ id: k === 1 ? l.id : `${l.id.slice(0, 33)}_${k}`, name: k === 1 ? l.name : `${l.name} · ${k}`, price: l.price });
    }
  }
  return out;
}

export const BillDraftSheet: React.FC<{ open: boolean; onClose: () => void; meetupMsg: Message }> = ({
  open,
  onClose,
  meetupMsg,
}) => {
  const { t } = useTranslation();
  const meetup = useMemo(() => parseMeetupData(meetupMsg.meetupData), [meetupMsg.meetupData]);
  const me = useMessengerStore((s) => s.currentUser);
  const [lines, setLines] = useState<Line[]>([]);
  const [token, setToken] = useState<BillToken>('USDC');
  const [wallet, setWallet] = useState<string | null>(null);
  const [payTo, setPayTo] = useState<'organizer' | 'venue'>('organizer');
  const [menuNote, setMenuNote] = useState<string | null>(null);
  const [ownName, setOwnName] = useState('');
  const [ownPrice, setOwnPrice] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [escrowOn, setEscrowOn] = useState(false);
  const [mode, setMode] = useState<BillMode>('split');
  const [picked, setPicked] = useState<Set<string> | null>(null);

  useEffect(() => {
    if (!open || !meetup) return;
    let alive = true;
    setLines([]);
    setToken('USDC');
    setWallet(null);
    setPayTo('organizer');
    setOwnName('');
    setOwnPrice('');
    setError(null);
    setPicked(null);
    if (!hasVenuePage(meetup.venue)) {
      setMenuNote(t('billSheet.addManually'));
      return;
    }
    setMenuNote(null);
    meetupApi.venue(meetup.venue.id).then(
      (page) => {
        if (!alive) return;
        const menu = menuOf(page);
        if (menu.token) setToken(menu.token);
        setWallet(page.wallet);
        setLines(menu.items.map((i) => ({ ...i, count: 0 })));
        if (!menu.items.length) setMenuNote(t('billSheet.menuEmpty'));
      },
      (e) => alive && setMenuNote(`${refusalWords(e, 'svc.meetup.what.menu')} ${t('billSheet.addManually')}`),
    );
    return () => {
      alive = false;
    };
  }, [open, meetup]);

  useEffect(() => {
    if (!open) return;
    let alive = true;
    escrow().then((e) => {
      if (!alive) return;
      setEscrowOn(e.on);
      if (!e.on) setMode('split');
    });
    return () => {
      alive = false;
    };
  }, [open]);

  if (!open || !meetup) return null;

  const guests = meetup.invitees.filter((p) => p.id !== meetup.organizer);
  const chosen = picked ?? cameOf(meetup, readCardMarks(meetupMsg.cardMarks)) ?? new Set(guests.map((p) => p.id));
  const flip = (id: string) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setPicked(next);
  };
  const items = expandLines(lines);
  const total = (() => {
    try {
      return items.reduce((acc, i) => acc + toUnits(i.price, token), 0n);
    } catch {
      return 0n;
    }
  })();
  const refused = cardKindRefusal(BILL_KIND);
  const why = refused ?? (items.length === 0 ? t('billSheet.pickOne') : null);
  const organizerName =
    meetup.organizer === me.id ? me.name : meetup.organizer === meetupMsg.senderId ? meetupMsg.senderName : t('billSheet.organizer');

  const ownOk = ownName.trim() !== '' && isDecimal(ownPrice.trim().replace(',', '.')) && (() => {
    try {
      return toUnits(ownPrice.trim().replace(',', '.'), token) > 0n;
    } catch {
      return false;
    }
  })();
  const addOwn = () => {
    if (!ownOk) return;
    const base = `own${lines.length + 1}`;
    setLines([...lines, { id: base, name: ownName.trim(), price: ownPrice.trim().replace(',', '.'), count: 1 }]);
    setOwnName('');
    setOwnPrice('');
  };

  const send = async () => {
    if (why || busy) return;
    setBusy(true);
    setError(null);
    try {
      const bill = billFromMeetup({ ...meetup, venue: { ...meetup.venue, wallet } }, items, {
        id: newCardId(),
        token,
        organizerName,
        payTo,
        title: meetup.venue.name,
        came: chosen,
      });
      await sendBill({ ...bill, mode: escrowOn ? mode : 'split' });
      setLines([]);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : t('billSheet.notSent'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('billSheet.bill')}
      tall
      testId="bill-draft"
      footer={
        <div>
          <PrimaryButton onClick={() => void send()} disabled={!!why || busy}>
            {items.length ? t('billSheet.sendWithTotal', { total: formatAmount(total, token) }) : t('billSheet.send')}
          </PrimaryButton>
          {(why || error) && (
            <p className={`pt-2 text-footnote ${error ? 'text-danger' : 'text-ink-3'}`} data-testid="bill-draft-why">
              {error ?? why}
            </p>
          )}
        </div>
      }
    >
      <p className="px-4 text-footnote text-ink-3">
        {t('billSheet.intro', { venue: meetup.venue.name })}
      </p>
      {menuNote && <p className="px-4 pt-2 text-footnote text-ink-3">{menuNote}</p>}

      {guests.length > 0 && (
        <>
          <Label>{t('billSheet.who')}</Label>
          <div className="px-4 flex flex-wrap gap-2" data-testid="bill-who">
            {guests.map((p) => {
              const on = chosen.has(p.id);
              return (
                <button
                  key={p.id}
                  type="button"
                  aria-pressed={on}
                  data-testid={`bill-who-${p.id}`}
                  onClick={() => flip(p.id)}
                  className={`touch-floor-inline h-9 px-3 rounded-full text-footnote ${on ? 'bg-accent-soft text-ink' : 'bg-raised text-ink-3'}`}
                >
                  {p.name}
                </button>
              );
            })}
          </div>
        </>
      )}

      {lines.length > 0 && <Label>{t('billSheet.ordered')}</Label>}
      {lines.map((l, i) => (
        <div key={l.id} className="min-h-12 px-4 flex items-center gap-2" data-testid={`bill-line-${l.id}`}>
          <span className="flex-1 min-w-0">
            <span className="block text-callout text-ink truncate">{l.name}</span>
            <span className="block text-footnote text-ink-3 tnum">
              {l.price} {token}
            </span>
          </span>
          <button
            type="button"
            aria-label={t('billSheet.less', { name: l.name })}
            disabled={l.count === 0}
            onClick={() => setLines(lines.map((x, j) => (j === i ? { ...x, count: x.count - 1 } : x)))}
            className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-surface flex items-center justify-center disabled:opacity-40"
          >
            <Minus className="w-4 h-4" strokeWidth={1.75} />
          </button>
          <span className="w-6 text-center text-callout text-ink tnum">{l.count}</span>
          <button
            type="button"
            aria-label={t('billSheet.more', { name: l.name })}
            data-testid={`bill-more-${l.id}`}
            disabled={l.count >= 9}
            onClick={() => setLines(lines.map((x, j) => (j === i ? { ...x, count: x.count + 1 } : x)))}
            className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-surface flex items-center justify-center disabled:opacity-40"
          >
            <Plus className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
      ))}

      <Label>{t('billSheet.addManual')}</Label>
      <div className="px-4 flex gap-2">
        <input
          value={ownName}
          onChange={(e) => setOwnName(e.target.value)}
          placeholder={t('billSheet.dishPlaceholder')}
          className="h-11 min-h-0 flex-1 min-w-0 rounded-control bg-surface px-3 text-callout text-ink placeholder:text-ink-3 outline-none"
        />
        <input
          value={ownPrice}
          onChange={(e) => setOwnPrice(e.target.value)}
          placeholder={token}
          inputMode="decimal"
          className="h-11 min-h-0 w-24 rounded-control bg-surface px-3 text-callout text-ink tnum placeholder:text-ink-3 outline-none"
        />
        <button
          type="button"
          aria-label={t('billSheet.addDish')}
          disabled={!ownOk}
          onClick={addOwn}
          className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center disabled:opacity-40"
        >
          <Plus className="w-4 h-4" strokeWidth={1.75} />
        </button>
      </div>

      <Label>{t('billSheet.payTo')}</Label>
      <div className="px-4 flex gap-2" role="radiogroup" aria-label={t('billSheet.payTo')}>
        {(['organizer', 'venue'] as const).map((to) => {
          const allowed = to === 'organizer' || !!wallet;
          return (
            <button
              key={to}
              type="button"
              role="radio"
              aria-checked={payTo === to}
              disabled={!allowed}
              data-testid={`bill-payto-${to}`}
              onClick={() => setPayTo(to)}
              className={`h-11 min-h-0 px-4 rounded-full text-footnote font-semibold disabled:opacity-40 ${
                payTo === to ? 'bg-accent text-on-accent' : 'bg-surface text-ink'
              }`}
            >
              {to === 'organizer' ? (meetup.organizer === me.id ? t('billSheet.toMe') : organizerName) : t('billSheet.toVenue')}
            </button>
          );
        })}
      </div>
      {!wallet && (
        <p className="px-4 pt-1 text-footnote text-ink-3">{t('billSheet.noVenueWallet')}</p>
      )}
      {escrowOn && (
        <>
          <Label>{t('billMode.label')}</Label>
          <div
            role="radiogroup"
            aria-label={t('billMode.label')}
            data-testid="bill-mode"
            className="px-4 flex gap-2"
          >
            {(['split', 'escrow'] as const).map((m) => (
              <button
                key={m}
                type="button"
                role="radio"
                aria-checked={mode === m}
                data-testid={`bill-mode-${m}`}
                onClick={() => setMode(m)}
                className={`h-11 min-h-0 px-4 rounded-full text-footnote font-semibold disabled:opacity-40 ${
                  mode === m ? 'bg-accent text-on-accent' : 'bg-surface text-ink'
                }`}
              >
                {t(m === 'split' ? 'billMode.split' : 'billMode.escrow')}
              </button>
            ))}
          </div>
        </>
      )}
      {lines.some((l) => l.id.startsWith('own')) && (
        <button
          type="button"
          onClick={() => setLines(lines.filter((l) => !l.id.startsWith('own')))}
          className="mx-4 mt-3 h-11 min-h-0 px-4 rounded-full text-caption text-ink-2 bg-surface inline-flex items-center gap-1"
        >
          <X className="w-3.5 h-3.5" strokeWidth={1.75} /> {t('billSheet.removeManual')}
        </button>
      )}
      <div className="h-4" />
    </FormSheet>
  );
};
