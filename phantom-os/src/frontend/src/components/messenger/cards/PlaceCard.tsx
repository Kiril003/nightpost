import { MapPin, Receipt } from 'lucide-react';
import type { Message } from '../../../types/messenger';
import { tNow } from '../../chat/tNow';
import { CardShell } from './CardShell';
import { currencySign, money } from './cardModel';

/** Одна картка місця для закладу, точки «я тут» і місця з мережі: «Місце» в шапці, назва, рядок фактів, одна дія. */
export function PlaceCard({ testId, title, status, action }: {
  testId: string;
  title: string;
  status: string;
  action: { label: string; onClick: () => void };
}) {
  return (
    <CardShell
      testId={testId}
      kind={{ label: tNow('chatList.kind.place'), icon: <MapPin strokeWidth={1.75} /> }}
      title={title}
      status={status}
      action={action}
    />
  );
}

/** Старий рахунок (PH-BILL) у тій самій анатомії, що й новий: лише для читання, оплату ніщо не перевіряло. */
export function LegacyBillCard({ msg, meId, meName }: { msg: Message; meId: string; meName: string }) {
  const b = msg.splitBillData!;
  const total = `${money(b.totalAmount)} ${currencySign(b.currency)}`.trim();
  return (
    <CardShell
      testId="card-bill-legacy"
      kind={{ label: tNow('chatList.kind.bill'), icon: <Receipt strokeWidth={1.75} /> }}
      title={b.title.trim() || tNow('chatList.kind.bill')}
      status={total}
    >
      {b.participants.slice(0, 3).map((p) => (
        <div key={p.id} className="min-h-11 px-4 flex items-center gap-3">
          <span className="flex-1 min-w-0 text-callout text-ink truncate">{p.id === meId || p.name === meName ? tNow('billCard.you') : p.name}</span>
          <span className="text-callout text-ink tnum shrink-0">{money(p.share)} {currencySign(b.currency)}</span>
        </div>
      ))}
      <p className="px-4 pt-1 text-caption text-ink-3" data-testid="legacy-bill-note">
        {tNow('messageEmbeds.legacyBill')}
      </p>
    </CardShell>
  );
}
