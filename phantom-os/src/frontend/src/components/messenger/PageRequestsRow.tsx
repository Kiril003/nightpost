import React, { useCallback, useEffect, useRef, useState } from 'react';
import { CalendarCheck } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import { pullPageRequests, type Amount, type PageRequest } from '../../services/pageRequests';
import { ListRow, RowBadge } from './kit/ListRow';
import { FormSheet } from './FormSheet';

const POLL_MS = 60_000;
const KIND = { book: 'pageReq.book', message: 'pageReq.message', order: 'pageReq.order', enroll: 'pageReq.enroll' } as const;

/** Сума як рядок сервера, без округлення float; кома там, де мова її пише. */
const dayMonthNum = (iso: string, locale: string) =>
  new Intl.DateTimeFormat(dateTag(locale), { day: '2-digit', month: '2-digit', timeZone: 'UTC' }).format(Date.parse(`${iso}T12:00:00Z`));
const amountText = (a: Amount, locale: string) => `${locale.startsWith('en') ? a.amount : a.amount.replace('.', ',')} ${a.token}`;

/**
 * «Запити зі сторінок»: записи й повідомлення відвідувачів сторінки власника.
 * Рядка немає, доки запитів немає або телефон їх не читає (старий міст, немає ключа).
 */
export const PageRequestsRow: React.FC<{ desk?: boolean; pull?: typeof pullPageRequests }> = ({ desk, pull = pullPageRequests }) => {
  const { t, locale } = useTranslation();
  const [items, setItems] = useState<PageRequest[]>([]);
  const [open, setOpen] = useState(false);
  const seq = useRef(0);

  const load = useCallback(async (ack: string[] = []) => {
    const my = ++seq.current;
    try {
      const next = await pull(ack);
      if (my === seq.current) setItems(next);
    } catch {
      if (my === seq.current && !ack.length) setItems([]);
    }
  }, [pull]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), POLL_MS);
    const onVis = () => {
      if (document.visibilityState === 'visible') void load();
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [load]);

  const fresh = items.filter((r) => !r.seen);
  useEffect(() => {
    if (open && fresh.length) void load(fresh.map((r) => r.id));
  }, [open, fresh.length, load]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!items.length) return null;
  const when = (r: PageRequest) => {
    const at = r.atMs ?? null;
    if (at === null) return '';
    return new Intl.DateTimeFormat(dateTag(locale), { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).format(at);
  };

  return (
    <>
      <ListRow
        data-folder-row="page-requests"
        onClick={() => setOpen(true)}
        density={desk ? 'desk' : 'phone'}
        leading={
          <span className={`${desk ? 'w-11 h-11' : 'w-[52px] h-[52px]'} shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center`}>
            <CalendarCheck className="w-6 h-6" strokeWidth={1.75} />
          </span>
        }
        title={t('pageReq.title')}
        subtitle={items[0].name ? `${items[0].name} · ${t(KIND[items[0].kind])}` : undefined}
        badge={fresh.length ? <RowBadge n={fresh.length} lit label={t('pageReq.newCount', { n: fresh.length })} /> : undefined}
      />
      {open && (
        <FormSheet open onClose={() => setOpen(false)} title={t('pageReq.title')} tall layerKey="page-requests" testId="page-requests-sheet">
          <ul className="flex flex-col gap-2" data-page-requests>
            {items.map((r) => (
              <li key={r.id} data-page-request={r.id} data-seen={r.seen ? '1' : '0'} className="rounded-2xl bg-surface px-4 py-3 flex flex-col gap-1">
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-body font-semibold text-ink truncate">{r.name}</span>
                  <span className="text-footnote text-ink-2 shrink-0">{t(KIND[r.kind])}</span>
                </div>
                {r.venueName && <span className="text-footnote text-ink-2 truncate">{r.venueName}</span>}
                {r.kind === 'book' && (
                  <span className="text-callout text-ink tnum">{r.atMs !== null ? when(r) : t('pageReq.noTime')}</span>
                )}
                {r.kind === 'book' && r.staffName && <span className="text-callout text-ink">{t('pageReq.master', { name: r.staffName })}</span>}
                {r.deposit && <span className="text-callout text-ink tnum">{t('pageReq.deposit', { amount: amountText(r.deposit, locale) })}</span>}
                {r.enroll && (
                  <div className="flex flex-col gap-0.5" data-page-enrol>
                    <span className="text-callout font-semibold text-ink">{r.enroll.course}</span>
                    <span className="text-callout text-ink tnum">{t('pageReq.sessions', { n: r.enroll.sessions })}</span>
                    {r.enroll.next && (
                      <span className="text-callout text-ink tnum">
                        {t('pageReq.next', { when: [r.enroll.next.name, dayMonthNum(r.enroll.next.date, locale), r.enroll.next.time].filter(Boolean).join(' · ') })}
                      </span>
                    )}
                    {r.enroll.price && <span className="text-callout text-ink tnum">{t('pageReq.price', { amount: amountText(r.enroll.price, locale) })}</span>}
                  </div>
                )}
                {r.order && (
                  <div className="flex flex-col gap-0.5" data-page-order>
                    {r.order.lines.map((l) => (
                      <span key={l.id} className="text-callout text-ink">{`${l.qty} × ${l.name}`}</span>
                    ))}
                    <span className="text-callout text-ink-2 tnum">
                      {r.order.fulfil === 'delivery'
                        ? t('pageReq.delivery', { zone: r.order.zone ?? '', fee: amountText({ amount: r.order.fee, token: r.order.token }, locale) })
                        : t('pageReq.pickup')}
                    </span>
                    {r.order.address && <span className="text-callout text-ink select-text break-words">{r.order.address}</span>}
                    <span className="text-callout font-semibold text-ink tnum">{t('pageReq.total', { amount: amountText({ amount: r.order.total, token: r.order.token }, locale) })}</span>
                  </div>
                )}
                {r.text && <p className="text-callout text-ink whitespace-pre-wrap break-words">{r.text}</p>}
                <span className="text-footnote text-ink-2 select-text break-all">{r.contact}</span>
              </li>
            ))}
          </ul>
        </FormSheet>
      )}
    </>
  );
};
