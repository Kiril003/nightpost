/**
 * «Що там з Олею?» — людина одним поглядом від помічника.
 *
 * Знімок того, що між вами лежало на телефоні в момент питання: остання
 * репліка, мої відкриті обіцянки, перекази в обидва боки. Тому в статусі —
 * «Станом на», а порожнє лишається порожнім.
 */
import React, { useState } from 'react';
import type { GlanceCardData, Message } from '../../../types/messenger';
import { useTranslation } from '../../../i18n/useTranslation';
import { dateTag } from '../../../i18n/dateTag';
import { messengerApi } from '../../../services/messengerApi';
import { useMessengerStore } from '../../../stores/messengerStore';
import { CardShell } from './CardShell';
import { openChatWith } from './PersonCard';

export const GLANCE_KIND = 'widget:glance';

const str = (x: unknown): x is string => typeof x === 'string';
const num = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x);

export function glanceOf(msg: Message): GlanceCardData | null {
  const d = (msg.type === GLANCE_KIND ? msg.glanceData : undefined) as Record<string, unknown> | undefined;
  if (!d || typeof d !== 'object' || d.v !== 1) return null;
  const name = str(d.name) ? d.name.trim() : '';
  if (!str(d.peer) || !d.peer || !name || !num(d.at)) return null;
  const l = d.last as Record<string, unknown> | null | undefined;
  const last = l && str(l.text) && l.text.trim() && num(l.at) && typeof l.mine === 'boolean' ? { text: l.text.trim(), at: l.at, mine: l.mine } : null;
  const promises = (Array.isArray(d.promises) ? d.promises : [])
    .filter((p): p is { what: string; due: number | null } => !!p && str(p.what) && !!p.what.trim() && (p.due === null || num(p.due)))
    .slice(0, 3)
    .map((p) => ({ what: p.what.trim(), due: p.due }));
  const money = (Array.isArray(d.money) ? d.money : [])
    .filter((f): f is { coin: string; sent: string; got: string } => !!f && str(f.coin) && str(f.sent) && str(f.got))
    .slice(0, 4)
    .map((f) => ({ coin: f.coin, sent: f.sent, got: f.got }));
  return { v: 1, peer: d.peer, name: name.slice(0, 80), at: d.at, last, promises, money, test: d.test === true };
}

function when(ms: number, now: number, locale: string): string {
  const tag = dateTag(locale);
  const time = new Intl.DateTimeFormat(tag, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(ms);
  const a = new Date(ms);
  const b = new Date(now);
  if (a.toDateString() === b.toDateString()) return time;
  const date = new Intl.DateTimeFormat(tag, a.getFullYear() === b.getFullYear() ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' }).format(ms);
  return `${date} ${time}`;
}

const Cap: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="px-4 pt-2 pb-1 text-caption font-semibold text-ink-3">{children}</p>
);

const Row: React.FC<{ main: string; sub: string; testId: string; quote?: boolean }> = ({ main, sub, testId, quote }) => (
  <li data-glance-row={testId} className="px-4 py-1.5 min-h-12 flex flex-col justify-center">
    <span className={`text-body text-ink break-words ${quote ? 'line-clamp-2' : 'truncate'}`}>{quote ? `«${main}»` : main}</span>
    <span className="text-footnote text-ink-3 truncate tnum">{sub}</span>
  </li>
);

export const GlanceCard: React.FC<{ msg: Message }> = ({ msg }) => {
  const { t, locale } = useTranslation();
  const g = glanceOf(msg);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  if (!g) return <CardShell testId="card-unreadable" title={t('msgTypes.person')} status={t('messageCard.unreadable')} />;

  const now = Date.now();
  const first = g.name.split(/\s+/)[0] ?? g.name;
  const empty = !g.last && g.promises.length === 0 && g.money.length === 0;

  const write = async () => {
    if (busy) return;
    setBusy(true);
    setFailed(false);
    try {
      const store = useMessengerStore.getState();
      const chat = store.chats.find((c) => c.peerNodeId === g.peer && c.type === 'dm');
      if (chat) {
        store.setActiveChat(chat.id);
        return;
      }
      const contact = (await messengerApi.listContacts()).find((c) => c.peer_node_id === g.peer);
      if (!contact) throw new Error('no contact');
      await openChatWith(contact);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <CardShell
      testId="glance-card"
      title={g.name}
      status={t('msgTypes.glanceAsOf', { time: when(g.at, now, locale) })}
      action={{ label: t('msgTypes.personWrite'), onClick: () => void write() }}
    >
      {empty ? (
        <p data-glance-empty className="px-4 min-h-12 flex items-center text-body text-ink-2">
          {t('msgTypes.glanceEmpty')}
        </p>
      ) : (
        <>
          {(g.last || g.promises.length > 0) && (
            <>
              <Cap>{t('msgTypes.glanceBetween')}</Cap>
              <ul>
                {g.last && (
                  <Row testId="last" quote main={g.last.text} sub={`${when(g.last.at, now, locale)} · ${g.last.mine ? t('msgTypes.glanceYou') : first}`} />
                )}
                {g.promises.map((p, i) => (
                  <Row
                    key={i}
                    testId="promise"
                    main={p.what}
                    sub={p.due === null ? t('msgTypes.glancePromise') : `${t('msgTypes.glancePromise')} · ${when(p.due, now, locale)}`}
                  />
                ))}
              </ul>
            </>
          )}
          <Cap>{t('msgTypes.glanceMoney')}</Cap>
          <ul>
            {g.money.length === 0 ? (
              <Row testId="money-none" main={t('msgTypes.glanceNoMoney')} sub={g.test ? t('msgTypes.glanceTest') : ''} />
            ) : (
              g.money.map((f) => (
                <Row
                  key={f.coin}
                  testId="money"
                  main={`${t('msgTypes.glanceFromYou')} ${f.sent} ${f.coin} · ${t('msgTypes.glanceToYou')} ${f.got} ${f.coin}`}
                  sub={g.test ? t('msgTypes.glanceTest') : ''}
                />
              ))
            )}
          </ul>
        </>
      )}
      {failed && <p className="px-4 pt-1 text-caption text-danger">{t('createChatModal.openFailed')}</p>}
    </CardShell>
  );
};
