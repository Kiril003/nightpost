/**
 * Канал в інфо-вигляді: дописи, цифри власника, статистика підписників.
 * Цифри — лише з журналу складу (`add` / `remove` / `leave` з часом вузла);
 * переглядів дріт не возить, тож їх тут немає.
 */
import React, { useMemo, useState } from 'react';
import { Pin } from 'lucide-react';
import type { Message } from '../../types/messenger';
import { useTranslation } from '../../i18n/useTranslation';
import { currentDateTag } from '../chat/tNow';
import { parseNodeTime } from './chatListFormat';
import { soundFx } from '../../utils/messengerSound';

export type StatPeriod = '7' | '30' | 'all';

export interface ChannelStat {
  total: number;
  joined: number;
  left: number;
  rows: { key: string; label: string; n: number }[];
}

const DAY = 86_400_000;
const DELTA: Record<string, number> = { add: 1, remove: -1, leave: -1 };
const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate());
const cap = (s: string) => s.charAt(0).toLocaleUpperCase() + s.slice(1);

/** Зміни складу з часом; рядок без часу вузла не рахується — дату не вигадуємо. */
const moves = (messages: Message[]) =>
  messages.flatMap((m) => {
    const d = m.systemAct ? DELTA[m.systemAct] : undefined;
    const at = d && m.sentAt ? parseNodeTime(m.sentAt)?.getTime() : undefined;
    return d && at !== undefined ? [{ d, at }] : [];
  });

/**
 * Склад на кінець кожного дня (місяця) — назад від нинішнього числа:
 * що прийшло пізніше межі, віднімається.
 */
export function channelStats(messages: Message[], total: number, period: StatPeriod, now = new Date(), tag = currentDateTag()): ChannelStat {
  const acts = moves(messages);
  const at = (end: number) => Math.max(0, total - acts.reduce((s, a) => (a.at >= end ? s + a.d : s), 0));
  const today = startOfDay(now);
  const rows: ChannelStat['rows'] = [];
  let from = -Infinity;
  if (period === 'all') {
    const first = acts.length ? new Date(Math.min(...acts.map((a) => a.at))) : now;
    for (let y = first.getFullYear(), mo = first.getMonth(); y < now.getFullYear() || (y === now.getFullYear() && mo <= now.getMonth()); mo === 11 ? (y++, (mo = 0)) : mo++) {
      const start = new Date(y, mo, 1);
      rows.push({ key: `${y}-${mo}`, label: cap(start.toLocaleDateString(tag, { month: 'short', year: 'numeric' })), n: at(new Date(y, mo + 1, 1).getTime()) });
    }
  } else {
    const days = Number(period);
    from = today.getTime() - (days - 1) * DAY;
    for (let i = days - 1; i >= 0; i--) {
      const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i);
      const label = days === 7 ? cap(start.toLocaleDateString(tag, { weekday: 'short' })) : start.toLocaleDateString(tag, { day: '2-digit', month: '2-digit' });
      rows.push({ key: start.toISOString(), label, n: at(start.getTime() + DAY) });
    }
  }
  const inside = acts.filter((a) => a.at >= from);
  return {
    total,
    joined: inside.filter((a) => a.d > 0).length,
    left: inside.filter((a) => a.d < 0).length,
    rows,
  };
}

/** Дописи каналу — усе, крім службових рядків; новіші вгорі. */
export const channelPosts = (messages: Message[]): Message[] =>
  messages.filter((m) => !m.systemAct && m.type !== 'system').slice().reverse();

const usePostTime = () => {
  const { t } = useTranslation();
  return (iso?: string): string => {
    const d = iso ? parseNodeTime(iso) : null;
    if (!d) return '';
    const days = Math.round((startOfDay(new Date()).getTime() - startOfDay(d).getTime()) / DAY);
    const time = d.toLocaleTimeString(currentDateTag(), { hour: '2-digit', minute: '2-digit' });
    if (days <= 0) return `${t('infoChannel.today')}, ${time}`;
    if (days === 1) return `${t('chatList.yesterday')}, ${time}`;
    return d.toLocaleDateString(currentDateTag(), { day: '2-digit', month: '2-digit' });
  };
};

export const ChannelSummary: React.FC<{ items: { key: string; label: string; value: string }[] }> = ({ items }) => (
  <div className="px-4 pt-4 pb-1 grid grid-cols-3 text-center" data-testid="channel-summary">
    {items.map((x) => (
      <div key={x.key} data-testid={`channel-summary-${x.key}`}>
        <p className="text-title-2 font-semibold text-ink tnum">{x.value}</p>
        <p className="text-footnote text-ink-2">{x.label}</p>
      </div>
    ))}
  </div>
);

export const ChannelPostsPanel: React.FC<{ posts: Message[]; pinnedId?: string; onJump: (id: string) => void }> = ({ posts, pinnedId, onJump }) => {
  const { t } = useTranslation();
  const when = usePostTime();
  const pinned = pinnedId ? posts.find((p) => p.id === pinnedId) : undefined;
  const list = pinned ? [pinned, ...posts.filter((p) => p !== pinned)] : posts;
  if (list.length === 0) return <p className="px-4 py-6 text-callout text-ink-3" data-testid="channel-posts-empty">{t('infoChannel.noPosts')}</p>;
  return (
    <ul data-testid="channel-posts">
      {list.map((p, i) => (
        <li key={p.id} className={i > 0 ? 'border-t border-line' : ''}>
          <button
            type="button"
            data-testid="channel-post"
            onClick={() => {
              soundFx.playTap();
              onJump(p.id);
            }}
            className="w-full px-4 py-3 text-left active:bg-raised"
          >
            <span className="flex items-center gap-1.5 text-footnote text-ink-3">
              {p === pinned && <Pin className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />}
              {p === pinned ? `${t('infoChannel.pinned')} · ${when(p.sentAt)}` : when(p.sentAt)}
            </span>
            {p.text && <span className="mt-0.5 block text-body text-ink line-clamp-3">{p.text}</span>}
          </button>
        </li>
      ))}
    </ul>
  );
};

export const ChannelStatsView: React.FC<{ messages: Message[]; total: number }> = ({ messages, total }) => {
  const { t } = useTranslation();
  const when = usePostTime();
  const [period, setPeriod] = useState<StatPeriod>('7');
  const stat = useMemo(() => channelStats(messages, total, period), [messages, total, period]);
  const posts = useMemo(() => channelPosts(messages), [messages]);
  const periods: { key: StatPeriod; label: string }[] = [
    { key: '7', label: t('infoChannel.period7') },
    { key: '30', label: t('infoChannel.period30') },
    { key: 'all', label: t('infoChannel.periodAll') },
  ];
  return (
    <div className="pb-4" data-testid="channel-stats">
      <div role="radiogroup" aria-label={t('infoChannel.stats')} className="mx-4 grid grid-cols-3 rounded-full border border-line overflow-hidden">
        {periods.map((p, i) => (
          <button
            key={p.key}
            type="button"
            role="radio"
            aria-checked={period === p.key}
            data-testid={`channel-period-${p.key}`}
            onClick={() => setPeriod(p.key)}
            className={`h-11 min-h-0 text-callout ${i > 0 ? 'border-l border-line' : ''} ${period === p.key ? 'bg-raised text-ink font-semibold' : 'text-ink-2'}`}
          >
            {p.label}
          </button>
        ))}
      </div>
      <ChannelSummary
        items={[
          { key: 'total', label: t('infoChannel.subscribers'), value: String(stat.total) },
          { key: 'joined', label: t('infoChannel.joined'), value: `+${stat.joined}` },
          { key: 'left', label: t('infoChannel.left'), value: stat.left ? `−${stat.left}` : '0' },
        ]}
      />
      <p className="px-4 pt-5 pb-1.5 text-subhead text-ink-3">{period === 'all' ? t('infoChannel.byMonth') : t('infoChannel.byDay')}</p>
      <ul data-testid="channel-stat-rows">
        {stat.rows.map((r, i) => (
          <li key={r.key} className={`min-h-12 px-4 flex items-center justify-between ${i > 0 ? 'border-t border-line' : ''}`}>
            <span className="text-headline text-ink">{r.label}</span>
            <span className="text-callout text-ink-2 tnum">{r.n}</span>
          </li>
        ))}
      </ul>
      {posts.length > 0 && (
        <>
          <p className="px-4 pt-5 pb-1.5 text-subhead text-ink-3">{t('infoChannel.posts')}</p>
          <ul>
            {posts.slice(0, 20).map((p, i) => (
              <li key={p.id} className={`px-4 py-2.5 ${i > 0 ? 'border-t border-line' : ''}`}>
                <span className="block text-headline text-ink truncate">{p.text || '—'}</span>
                <span className="block text-footnote text-ink-3">{when(p.sentAt)}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
};
