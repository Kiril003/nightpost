/**
 * Картка переказу, купюри чи відбитка файла в стрічці (СЛ-03).
 *
 * Дані — поля `payment` / `seal`, які міст кладе поруч із реченням `body`
 * (`docs/solana/PAYMENT_CARD_SPEC.md`). Стан каже МЕРЕЖА, не ми: міст
 * дописує його при кожній віддачі рядка, а `__phantomMeshChanged` перечитує
 * стрічку, коли мережа відповіла.
 *
 * Тихо навмисно: одна поверхня, сума цифрами `tnum`, стан словом. Жодного
 * сяйва, градієнта чи «крипто»-кольорів — гроші в розмові мають виглядати
 * як квитанція, а не як реклама біржі.
 *
 * Правила специфікації, які тут тримаються:
 *  - «отримано» не кажемо ніколи: лише «на ваш гаманець» (to_mine === true)
 *    або «на адресу 4…4»;
 *  - ні мережі (devnet), ні відбитка транзакції, ні посилання в оглядач на картці
 *    немає: вони лежать у «Подробицях» листа (MessageDetailsModal);
 *  - `mismatch` — червоне слово: транзакція є, але платить не те, що написано.
 */
import React, { useEffect, useRef, useState } from 'react';
import {
  Banknote,
  Check,
  CircleX,
  Clock,
  CloudOff,
  Coins,
  Fingerprint,
  ShieldCheck,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';
import type { Message, SolanaPaymentStatus } from '../../../types/messenger';
import { shortChainId as short } from '../../../services/solanaFields';
import { haptic } from '../../../utils/haptic';
import { tNow, currentLocale, currentDateTag } from '../../chat/tNow';
import { chainStep } from '../../../feel/paymentSteps';
import { PaymentNotches } from '../solana/PaymentNotches';
import { FailedMark, PendingClock, SentTick } from './feedParts';
import { WalletCard } from '../kit/WalletCard';
import { TestPayTag } from '../kit/TestPayTag';
/** Картка є лише там, де поле доїхало й пройшло перевірку форми. */
export const hasSolanaCard = (msg: Message): boolean => Boolean(msg.payment || msg.seal);

/** Десяткова кома, як пише людина; цифри — ті самі, без округлень. */
const amountWords = (amount: string) => (currentLocale() === 'en' ? amount : amount.replace('.', ','));

interface Said {
  text: string;
  /** Пояснення другим ярусом під головним словом (готівка). */
  detail?: string;
  Icon: LucideIcon;
  /** Колір іконки. */
  iconTone: string;
  /** Колір слова: червоне лише там, де мережа заперечує картку. */
  textTone: string;
}

const STATE: Record<SolanaPaymentStatus, Said> = {
  pending: { get text() { return tNow('solanaCard.state.pending'); }, Icon: Clock, iconTone: 'text-ink-3', textTone: 'text-ink-2' },
  confirmed: { get text() { return tNow('solanaCard.state.confirmed'); }, Icon: Check, iconTone: 'text-success', textTone: 'text-success font-medium' },
  failed: { get text() { return tNow('solanaCard.state.failed');}, Icon: CircleX, iconTone: 'text-danger', textTone: 'text-danger' },
  mismatch: { get text() { return tNow('solanaCard.state.mismatch'); }, Icon: TriangleAlert, iconTone: 'text-danger', textTone: 'text-danger' },
  unknown: { get text() { return tNow('solanaCard.state.unknown'); }, Icon: CloudOff, iconTone: 'text-ink-3', textTone: 'text-ink-2' },
};

/** Готівка: головне слово й пояснення — два яруси, щоб «Ще не зараховано → Зараховано» читалося з кадру. */
const CASH_SAID: Partial<Record<SolanaPaymentStatus, [string, string]>> = {
  get pending(): [string, string] { return [tNow('solanaCard.cash.pendingHead'), tNow('solanaCard.cash.pendingDetail')]; },
  get confirmed(): [string, string] { return [tNow('solanaCard.cash.confirmedHead'), tNow('solanaCard.cash.confirmedDetail')]; },
};

const SKR_WORDS: Record<string, string> = {
  get staker() { return tNow('solanaCard.skr.staker'); },
  get '1k'() { return tNow('solanaCard.skr.k1'); },
  get '10k'() { return tNow('solanaCard.skr.k10'); },
};

const seekerTitle = () => tNow('solanaCard.seekerTitle');

export const blockTimeWords = (seconds: number): string =>
  new Intl.DateTimeFormat(currentDateTag(), {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(seconds * 1000));

export interface SolanaCardProps {
  msg: Message;
}

export const SolanaCard: React.FC<SolanaCardProps> = ({ msg }) => {
  const { payment, seal } = msg;
  const pending = Boolean(msg.isSelf && (msg.status === 'sending' || msg.status === 'queued'));
  const failed = Boolean(msg.isSelf && msg.status === 'failed');
  const status = payment?.status;
  const prev = useRef(status);
  const [settled, setSettled] = useState(false);
  // Лише перехід на очах: картка, що вже прийшла «зарахованою», не вібрує й не оживає.
  useEffect(() => {
    if (prev.current === 'pending' && status === 'confirmed') {
      setSettled(true);
      haptic('success');
    }
    prev.current = status;
  }, [status]);
  if (!payment && !seal) return null;

  let label: string;
  let LabelIcon: LucideIcon;
  let said: Said;
  let main: React.ReactNode;

  if (payment) {
    label = payment.offlineCash ? tNow('solanaCard.cash') : tNow('solanaCard.transfer');
    LabelIcon = payment.offlineCash ? Banknote : Coins;
    said = STATE[payment.status];
    const cashSaid = payment.offlineCash ? CASH_SAID[payment.status] : undefined;
    if (cashSaid) said = { ...said, text: cashSaid[0], detail: cashSaid[1] };
    main = (
      <>
        <p data-testid="solana-amount" className="mt-2 text-display tnum text-ink">
          {amountWords(payment.amount)}{' '}
          <span>{payment.token}</span>
        </p>
        <p className="text-footnote text-ink-2 tnum">
          {payment.toMine === true ? tNow('solanaCard.toYourWallet') : tNow('solanaCard.toAddress', { address: short(payment.to) })}
        </p>
        {!payment.offlineCash && (
          <PaymentNotches step={chainStep(payment.status)} label={tNow('payNotch.steps')} />
        )}
      </>
    );
  } else {
    const s = seal!;
    label = tNow('solanaCard.fileFingerprint');
    LabelIcon = Fingerprint;
    said = STATE[s.status];
    if (s.status === 'confirmed') {
      said = {
        ...said,
        text: s.blockTime ? tNow('solanaCard.attestedAt', { time: blockTimeWords(s.blockTime) }) : tNow('solanaCard.attested'),
      };
    }
    main = (
      <p className="mt-1.5 text-headline tabular-nums text-ink" title={s.sha256}>
        {short(s.sha256, 6, 4)}
      </p>
    );
  }

  const StateIcon = said.Icon;
  // Ярус готівки: «Ще не зараховано» / «Зараховано» — і купюра-двійник, яку мережа відхилила,
  // тим самим кеглем, лише червоним словом. Слова ті самі, що й на переказі.
  const cashTier = Boolean(payment?.offlineCash && (said.detail || payment.status === 'failed'));
  return (
    <WalletCard
      testId="solana-card"
      icon={LabelIcon}
      label={label}
      tag={payment && payment.cluster !== 'mainnet' ? <TestPayTag /> : undefined}
      footer={
        <>
          <div className="flex items-start gap-1.5" aria-live="polite">
            <span className={`relative inline-flex mt-0.5 shrink-0 ${settled ? 'ph-seal' : ''}`}>
              {settled && <Clock className="ph-seal-lock w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />}
              <StateIcon className={`ph-seal-mark w-4 h-4 ${said.iconTone}`} strokeWidth={1.75} aria-hidden />
            </span>
            <span key={said.text} className="f-fade-in min-w-0">
              <span
                data-testid="solana-status"
                className={
                  cashTier
                    ? `block text-subhead ${said.textTone.includes('text-danger') ? 'text-danger' : 'text-ink'}`
                    : `text-footnote ${said.textTone}`
                }
              >
                {said.text}
              </span>
              {said.detail && (
                <span data-testid="solana-status-detail" className="block text-footnote text-ink-3">
                  {said.detail}
                </span>
              )}
            </span>
          </div>
          <div className="mt-1 flex items-baseline justify-between gap-2">
            {msg.timestamp && (
              <span data-testid="solana-card-time" className="ml-auto shrink-0 text-caption text-ink-3 tnum">
                {msg.timestamp}
              </span>
            )}
            {msg.isSelf && msg.status === 'sent' && <SentTick msg={msg} />}
            {pending && <PendingClock />}
            {failed && <FailedMark msgId={msg.id} />}
          </div>
        </>
      }
    >
      {main}

      {payment?.offlineCash && <SeekerLine payment={payment} />}
    </WalletCard>
  );
};

type Payment = NonNullable<Message['payment']>;

/**
 * Хто дав купюру: знак Seeker звірено на цьому телефоні без мережі. Поля — лише з
 * перевіреного знака; немає їх — чесне «не перевірено», без щита.
 */
const SeekerLine: React.FC<{ payment: Payment }> = ({ payment }) => {
  const skr = payment.seekerSkrTier ? SKR_WORDS[payment.seekerSkrTier] : undefined;
  if (payment.seekerVerified || skr || payment.seekerLabel) {
    const head = payment.seekerVerified ? tNow('solanaCard.verifiedSeeker') : skr ? tNow('solanaCard.senderSkr', { skr }) : payment.seekerLabel;
    const tail = payment.seekerVerified && skr ? ` · ${skr}` : '';
    return (
      <p
        data-testid="solana-seeker"
        aria-label={seekerTitle()}
        title={seekerTitle()}
        className="mt-2 flex items-center gap-1.5 text-footnote text-ink-2"
      >
        <ShieldCheck className="w-4 h-4 shrink-0 text-success" strokeWidth={1.75} aria-hidden />
        <span>
          {head}
          {tail && <span className="text-ink-3">{tail}</span>}
        </span>
      </p>
    );
  }
  return (
    <p data-testid="solana-seeker-none" className="mt-2 text-footnote text-ink-3">
      {tNow('solanaCard.unverified')}
    </p>
  );
};
