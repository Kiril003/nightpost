/**
 * «Переказати» з розмови: монета, сума — і телефон відкриває рідний екран
 * переказу, де людина підтверджує відбитком. Веб нічого не підписує.
 *
 * Монети — ті, що телефон пропонує в цій мережі (`Coin.on`): USDC за
 * замовчуванням, EURC, SOL, SKR лише в mainnet. Комісії тут немає навмисно:
 * людина SOL не бачить (спонсор комісій — окрема гілка).
 * Картку в розмову шле сам телефон після підпису — тут її не дописуємо.
 */
import React, { useEffect, useRef, useState } from 'react';
import { sheetStep } from '../../../feel/paymentSteps';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { amountOf, coinsFor, solanaApi, type SolanaCoin } from '../../../services/solanaActions';
import type { SolanaCluster } from '../../../types/messenger';
import { PaymentNotches } from './PaymentNotches';

import { useTranslation } from '../../../i18n/useTranslation';
import { holdPayNote } from '../../../services/money/payNotes';
import { isLookalikeOk, lookalikeOf, markLookalikeOk } from '../../../services/money/lookalike';
import { HOLD_MS, payGuard } from '../../../services/payGuard';
import { openPeerCheck } from '../../../services/peerWhere';
import { useMessengerStore } from '../../../stores/messengerStore';
import { haptic } from '../../../utils/haptic';
import { pluralKey } from '../../chat/tNow';
export const PayMoneySheet: React.FC<{
  peer: { id: string; name: string };
  cluster: SolanaCluster;
  chat?: { id: string; ref?: string; amount?: string; token?: SolanaCoin; note?: string };
  onClose: () => void;
}> = ({ peer, cluster, chat, onClose }) => {
  const { t, locale } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const targetChat = React.useMemo(() => {
    if (chat?.id) {
      const byId = chats.find((c) => c.id === chat.id);
      if (byId) return byId;
    }
    return chats.find((c) => {
      const cf = c as { id: string; type?: string; peerNodeId?: string };
      const isDm = cf.type === undefined || cf.type === 'direct' || cf.type === 'dm';
      return isDm && (cf.peerNodeId === peer.id || cf.id === peer.id);
    });
  }, [chats, chat?.id, peer.id]);

  const peerKey = (targetChat as { peerNodeId?: string } | undefined)?.peerNodeId || peer.id;
  const [dismissed, setDismissed] = useState(() => isLookalikeOk(peerKey) || isLookalikeOk(peer.id));
  const trusted = React.useMemo(
    () => (targetChat ? lookalikeOf(targetChat, chats, Date.now()) : null),
    [targetChat, chats],
  );
  const held = Boolean(trusted) && !dismissed && !isLookalikeOk(peerKey) && !isLookalikeOk(peer.id);
  const guard = React.useMemo(() => payGuard(targetChat, Date.now()), [targetChat]);
  const hold = guard?.reason === 'key-changed';

  const dismissHold = () => {
    markLookalikeOk(peerKey);
    setDismissed(true);
  };

  const coins = coinsFor(cluster);
  const [coin, setCoin] = useState<SolanaCoin>(chat?.token ?? 'USDC');
  const [text, setText] = useState(chat?.amount ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const amount = amountOf(text, coin);
  const bad = text.trim() !== '' && amount == null;
  const step = sheetStep({ busy });

  const go = () => {
    if (!amount || held || busy) return;
    setBusy(true);
    setError(null);
    solanaApi
      .pay({
        peer_id: peer.id,
        token: coin,
        amount,
        ...(chat ? { chat_id: chat.id, ...(chat.ref ? { ref: chat.ref } : {}) } : {}),
      })
      .then(
        () => {
          if (chat?.note && chat.ref) {
            holdPayNote({ chatId: chat.id, ref: chat.ref, note: chat.note, at: Date.now() });
          }
          onClose();
        },
        (e: { message?: string }) => {
          setBusy(false);
          setError(e?.message || t('payMoneySheet.failed'));
        },
      );
  };

  const holdTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tickTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const goRef = useRef(go);
  goRef.current = go;

  const clearHold = () => {
    if (holdTimerRef.current !== null) {
      clearTimeout(holdTimerRef.current);
      holdTimerRef.current = null;
    }
    if (tickTimerRef.current !== null) {
      clearInterval(tickTimerRef.current);
      tickTimerRef.current = null;
    }
  };

  useEffect(() => clearHold, [amount, coin, held, hold]);

  const startHold = () => {
    if (!hold || !amount || busy || held || holdTimerRef.current !== null) return;
    haptic('tick');
    tickTimerRef.current = setInterval(() => {
      haptic('tick');
    }, 500);
    holdTimerRef.current = setTimeout(() => {
      clearHold();
      haptic('confirm');
      goRef.current();
    }, HOLD_MS);
  };

  const buttonLabel = amount
    ? t('payMoneySheet.sendAmount', { amount: amount.replace('.', ','), coin })
    : t('payMoneySheet.send');

  const chip = (on: boolean) =>
    `touch-floor min-h-[44px] flex-1 px-3 rounded-chip border text-subhead font-semibold transition-colors duration-instant ease-standard ${
      on ? 'bg-overlay border-transparent text-ink' : 'border-line-strong text-ink-2'
    }`;

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('payMoneySheet.titleTo', { name: peer.name })}
      testId="solana-pay-sheet"
      footer={
        hold ? (
          <div
            className="select-none touch-none"
            onContextMenu={(e) => e.preventDefault()}
            onPointerDown={startHold}
            onPointerUp={clearHold}
            onPointerLeave={clearHold}
            onPointerCancel={clearHold}
            onKeyDown={(e) => {
              if (e.key === ' ' || e.key === 'Enter') startHold();
            }}
            onKeyUp={(e) => {
              if (e.key === ' ' || e.key === 'Enter') clearHold();
            }}
          >
            <PrimaryButton disabled={!amount || busy || held} onClick={() => {}}>
              {buttonLabel}
            </PrimaryButton>
            <p className="text-footnote text-ink-3 text-center mt-1">{t('payGuard.hold')}</p>
          </div>
        ) : (
          <PrimaryButton disabled={!amount || busy || held} onClick={go}>
            {buttonLabel}
          </PrimaryButton>
        )
      }
    >
      <div className="px-4 pb-2 space-y-3">
        <div className="flex gap-2" role="group" aria-label={t('payMoneySheet.coin')}>
          {coins.map((c) => (
            <button
              key={c}
              type="button"
              data-testid={`solana-coin-${c}`}
              aria-pressed={coin === c}
              className={chip(coin === c)}
              onClick={() => setCoin(c)}
            >
              {c}
            </button>
          ))}
        </div>
        {guard && (
          <p data-testid="pay-guard" className="text-footnote text-ink-3 flex items-center gap-2">
            <span>
              {guard.reason === 'key-changed'
                ? (guard.daysAgo ?? 0) === 0
                  ? t('payGuard.keyChangedToday')
                  : t(pluralKey(locale, guard.daysAgo ?? 0, 'payGuard.keyChanged'), { n: guard.daysAgo ?? 0 })
                : t('payGuard.first')}
            </span>
            {hold && (
              <button
                type="button"
                onClick={() => openPeerCheck(peerKey)}
                className="touch-floor min-h-[44px] px-2 -my-3 text-footnote font-semibold text-accent-text"
              >
                {t('payGuard.check')}
              </button>
            )}
          </p>
        )}
        {held && trusted && (
          <div className="space-y-2">
            <p data-testid="lookalike-hold" className="text-callout text-danger">
              {t('lookalike.notThe', { name: trusted.title })}
            </p>
            <button
              type="button"
              onClick={dismissHold}
              className="touch-floor min-h-[44px] px-3 rounded-control bg-raised text-headline text-ink transition-colors duration-instant ease-standard"
            >
              {t('lookalike.someoneElse')}
            </button>
          </div>
        )}
        <label className="block">
          <span className="block text-footnote font-semibold text-ink-3 mb-1">{t('payMoneySheet.amount')}</span>
          <input
            data-testid="solana-amount-input"
            inputMode="decimal"
            autoComplete="off"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="0"
            aria-invalid={bad}
            className="w-full h-12 px-4 rounded-control bg-raised text-title-2 text-ink tnum outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
          />
        </label>
        {bad && <p className="text-callout text-danger">{t('payMoneySheet.badAmount', { coin })}</p>}
        <p className="text-callout text-ink-2">{t('payMoneySheet.confirm')}</p>
        {cluster === 'devnet' && <p className="text-footnote text-ink-3" data-testid="solana-pay-cluster">{t('payMoneySheet.testMoney')}</p>}
        {busy && <PaymentNotches step={step} label={t('payNotch.steps')} />}
        {error && <p className="text-callout text-danger">{error}</p>}
      </div>
    </FormSheet>
  );
};

export default PayMoneySheet;
