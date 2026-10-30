/**
 * «Скільки я відправив цього тижня?», «Хто мені винен?», «Мій баланс» — картка
 * Гроші від помічника.
 *
 * Показує баланс гаманця, перекази цього тижня по людях і відкриті частки
 * рахунків. У тестовій мережі чесно підписана «Тестові платежі». Жодна дія не
 * переказує гроші сама — лише відкриває чернетку, аркуш поділу або розмову,
 * щоб нагадати.
 */
import React, { useMemo, useState } from 'react';
import type { Message, MoneyCardData } from '../../../types/messenger';
import { useTranslation } from '../../../i18n/useTranslation';
import { dateTag } from '../../../i18n/dateTag';
import { fromUnits } from '../../../services/meetup/split';
import { messengerApi } from '../../../services/messengerApi';
import { chatLedger } from '../../../services/money/ledger';
import { askSolana, type SolanaCoin } from '../../../services/solanaActions';
import { useMessengerStore } from '../../../stores/messengerStore';
import { CardShell } from './CardShell';
import { openChatWith } from './PersonCard';
import { PhantomGlyph } from '../PhantomMark';
import { walletAsk } from '../../../services/askContext';
import { openAsk } from '../../../stores/askSheetStore';

export const MONEY_KIND = 'widget:money';

const str = (x: unknown): x is string => typeof x === 'string';
const num = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x);

export function moneyCardOf(msg: Message): MoneyCardData | null {
  const d = (msg.type === MONEY_KIND ? msg.moneyData : undefined) as Record<string, unknown> | undefined;
  if (!d || typeof d !== 'object' || d.v !== 1 || !num(d.at) || typeof d.wallet !== 'boolean') return null;
  const balances = (Array.isArray(d.balances) ? d.balances : [])
    .filter((b): b is { coin: string; amount: string } => !!b && str(b.coin) && !!b.coin.trim() && str(b.amount) && !!b.amount.trim())
    .slice(0, 4)
    .map((b) => ({ coin: b.coin.trim(), amount: b.amount.trim() }));
  const week = (Array.isArray(d.week) ? d.week : [])
    .filter(
      (w): w is { peer: string; name: string; coin: string; amount: string } =>
        !!w && str(w.peer) && str(w.name) && !!w.name.trim() && str(w.coin) && !!w.coin.trim() && str(w.amount) && !!w.amount.trim(),
    )
    .slice(0, 5)
    .map((w) => ({ peer: w.peer, name: w.name.trim().slice(0, 80), coin: w.coin.trim(), amount: w.amount.trim() }));
  const splits = (Array.isArray(d.splits) ? d.splits : [])
    .filter(
      (s): s is { chat?: string; peer: string; name: string; coin: string; amount: string; owesMe: boolean } =>
        !!s &&
        str(s.peer) &&
        !!s.peer &&
        str(s.name) &&
        !!s.name.trim() &&
        str(s.coin) &&
        !!s.coin.trim() &&
        str(s.amount) &&
        !!s.amount.trim() &&
        typeof s.owesMe === 'boolean',
    )
    .slice(0, 5)
    .map((s) => ({
      ...(str(s.chat) && s.chat ? { chat: s.chat } : {}),
      peer: s.peer,
      name: s.name.trim().slice(0, 80),
      coin: s.coin.trim(),
      amount: s.amount.trim(),
      owesMe: s.owesMe,
    }));
  return { v: 1, at: d.at, wallet: d.wallet, balances, week, splits, test: d.test === true };
}

function when(ms: number, now: number, locale: string): string {
  const tag = dateTag(locale);
  const time = new Intl.DateTimeFormat(tag, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(ms);
  const a = new Date(ms);
  const b = new Date(now);
  if (a.toDateString() === b.toDateString()) return time;
  const date = new Intl.DateTimeFormat(
    tag,
    a.getFullYear() === b.getFullYear() ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' },
  ).format(ms);
  return `${date} ${time}`;
}

const Cap: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="px-4 pt-2 pb-1 text-caption font-semibold text-ink-3">{children}</p>
);

const Row: React.FC<{ main: string; sub?: string; testId: string }> = ({ main, sub, testId }) => (
  <li data-money-row={testId} className="px-4 py-1.5 min-h-12 flex flex-col justify-center">
    <span className="text-body text-ink truncate tnum">{main}</span>
    {sub ? <span className="text-footnote text-ink-3 truncate tnum">{sub}</span> : null}
  </li>
);

const COIN_SET: ReadonlySet<string> = new Set(['USDC', 'EURC', 'SOL', 'SKR']);
const toSolanaCoin = (coin: string | undefined): SolanaCoin =>
  coin && COIN_SET.has(coin) ? (coin as SolanaCoin) : 'USDC';

export const MoneyCard: React.FC<{ msg: Message; meId: string }> = ({ msg, meId }) => {
  const { t, locale } = useTranslation();
  const data = moneyCardOf(msg);
  const chats = useMessengerStore((s) => s.chats);
  const [busy, setBusy] = useState(false);

  const liveSplits = useMemo(() => {
    if (!data || data.splits.length > 0 || !meId) return data?.splits ?? [];
    const out: MoneyCardData['splits'] = [];
    for (const chat of chats) {
      if (chat.archived || (chat as { isArchived?: boolean }).isArchived) continue;
      const ledger = chatLedger(chat.messages ?? [], meId);
      if (ledger.bills === 0) continue;
      for (const tr of ledger.transfers) {
        if (tr.state !== 'open') continue;
        if (tr.to === meId && tr.from !== meId) {
          const name = ledger.names[tr.from] ?? chat.title ?? tr.from;
          out.push({ chat: chat.id, peer: tr.from, name, coin: tr.token, amount: fromUnits(tr.units, tr.token), owesMe: true });
        } else if (tr.from === meId && tr.to !== meId) {
          const name = ledger.names[tr.to] ?? chat.title ?? tr.to;
          out.push({ chat: chat.id, peer: tr.to, name, coin: tr.token, amount: fromUnits(tr.units, tr.token), owesMe: false });
        }
      }
    }
    return out.slice(0, 5);
  }, [data, chats, meId]);

  if (!data) return <CardShell testId="card-unreadable" title={t('msgTypes.glanceMoney')} status={t('messageCard.unreadable')} />;

  const now = Date.now();
  const splits = liveSplits;
  const cluster = data.test ? 'devnet' : 'mainnet';
  const statusBase = t('msgTypes.glanceAsOf', { time: when(data.at, now, locale) });
  const status = data.test ? `${statusBase} · ${t('msgTypes.glanceTest')}` : statusBase;

  const openChatForPeer = async (peerId: string, chatId?: string) => {
    if (busy) return;
    setBusy(true);
    try {
      const store = useMessengerStore.getState();
      const byId = chatId ? store.chats.find((c) => c.id === chatId) : undefined;
      if (byId) {
        store.setActiveChat(byId.id);
        return;
      }
      const dm = store.chats.find((c) => (c.peerNodeId === peerId || c.id === peerId) && (c.type === 'dm' || !c.type));
      if (dm) {
        store.setActiveChat(dm.id);
        return;
      }
      const contact = (await messengerApi.listContacts()).find((c) => c.peer_node_id === peerId);
      if (contact) await openChatWith(contact);
    } catch {
      // ignore
    } finally {
      setBusy(false);
    }
  };

  const openDraft = () => {
    const iOwe = splits.find((s) => !s.owesMe);
    const recent = data.week.find((w) => !!w.peer);
    const anySplit = splits[0];
    const dmChat = chats.find((c) => c.type === 'dm' && !!c.peerNodeId);
    const peerId = iOwe?.peer ?? recent?.peer ?? anySplit?.peer ?? dmChat?.peerNodeId ?? '';
    const peerName = iOwe?.name ?? recent?.name ?? anySplit?.name ?? dmChat?.title ?? '';
    askSolana({
      kind: 'pay',
      peer: { id: peerId, name: peerName },
      cluster,
      ...(iOwe
        ? {
            chat: {
              id: iOwe.chat ?? peerId,
              amount: iOwe.amount,
              token: toSolanaCoin(iOwe.coin),
            },
          }
        : {}),
    });
  };

  const openSplit = () => {
    const firstSplit = splits[0];
    const firstWeek = data.week.find((w) => !!w.peer);
    const groupOrDm = chats.find((c) => c.type === 'group') ?? chats.find((c) => c.type === 'dm' && !!c.peerNodeId);
    const chatId = firstSplit?.chat ?? groupOrDm?.id ?? firstSplit?.peer ?? firstWeek?.peer ?? '';
    const peer =
      firstSplit
        ? { id: firstSplit.peer, name: firstSplit.name }
        : firstWeek
          ? { id: firstWeek.peer, name: firstWeek.name }
          : groupOrDm?.peerNodeId
            ? { id: groupOrDm.peerNodeId, name: groupOrDm.title }
            : undefined;
    askSolana({ kind: 'group', chatId, ...(peer ? { peer } : {}) });
  };

  const remind = () => {
    const debtor = splits.find((s) => s.owesMe) ?? splits[0];
    const targetPeer = debtor?.peer ?? data.week.find((w) => !!w.peer)?.peer;
    if (targetPeer) void openChatForPeer(targetPeer, debtor?.chat);
  };

  const balanceLine =
    data.balances.length > 0
      ? data.balances.map((b) => `${b.amount} ${b.coin}`).join(' · ')
      : data.wallet
        ? t('msgTypes.moneyNoBalance')
        : t('msgTypes.moneyNoWallet');

  return (
    <CardShell testId="money-card" title={t('msgTypes.glanceMoney')} status={status}>
      <Cap>{t('msgTypes.moneyBalance')}</Cap>
      <ul>
        <Row
          testId={data.balances.length > 0 ? 'balance' : 'balance-none'}
          main={balanceLine}
          sub={data.test ? t('msgTypes.glanceTest') : undefined}
        />
      </ul>

      <Cap>{t('msgTypes.moneyWeek')}</Cap>
      <ul>
        {data.week.length === 0 ? (
          <Row testId="week-none" main={t('msgTypes.moneyWeekNone')} />
        ) : (
          data.week.map((w, i) => (
            <Row key={`${w.peer}-${w.coin}-${i}`} testId="week" main={`${w.name} · ${w.amount} ${w.coin}`} />
          ))
        )}
      </ul>

      <Cap>{t('msgTypes.moneySplits')}</Cap>
      <ul>
        {splits.length === 0 ? (
          <Row testId="split-none" main={t('msgTypes.moneySplitsNone')} />
        ) : (
          splits.map((s, i) => (
            <Row
              key={`${s.peer}-${s.coin}-${i}`}
              testId="split"
              main={`${s.name} · ${s.amount} ${s.coin}`}
              sub={s.owesMe ? t('msgTypes.moneyOwesYou') : t('msgTypes.moneyYouOwe')}
            />
          ))
        )}
      </ul>

      <div data-card-actions className="px-4 pt-2 flex flex-wrap items-center gap-2">
        <button
          type="button"
          data-testid="money-action-draft"
          data-kit="button"
          data-kit-variant="outline"
          onClick={(e) => {
            e.stopPropagation();
            openDraft();
          }}
          className="touch-floor-inline h-9 px-3.5 rounded-full border border-line-strong text-ink text-subhead font-semibold active:bg-ink/[.06]"
        >
          {t('msgTypes.moneyOpenDraft')}
        </button>
        <button
          type="button"
          data-testid="money-action-split"
          data-kit="button"
          data-kit-variant="outline"
          onClick={(e) => {
            e.stopPropagation();
            openSplit();
          }}
          className="touch-floor-inline h-9 px-3.5 rounded-full border border-line-strong text-ink text-subhead font-semibold active:bg-ink/[.06]"
        >
          {t('msgTypes.moneySplit')}
        </button>
        <button
          type="button"
          data-testid="money-action-remind"
          data-kit="button"
          data-kit-variant="outline"
          onClick={(e) => {
            e.stopPropagation();
            remind();
          }}
          className="touch-floor-inline h-9 px-3.5 rounded-full border border-line-strong text-ink text-subhead font-semibold active:bg-ink/[.06]"
        >
          {t('msgTypes.moneyRemind')}
        </button>
        <button
          type="button"
          data-testid="ask-trigger-wallet"
          data-kit="button"
          data-kit-variant="outline"
          aria-label={t('ask.open')}
          onClick={(e) => {
            e.stopPropagation();
            openAsk(walletAsk({ ...data, splits }, { t, locale }));
          }}
          className="touch-floor-inline h-9 px-3.5 rounded-full border border-line-strong text-ink text-subhead font-semibold flex items-center gap-1.5 active:bg-ink/[.06]"
        >
          <PhantomGlyph className="w-4 h-4" />
          {t('ask.personAction')}
        </button>
      </div>
    </CardShell>
  );
};
