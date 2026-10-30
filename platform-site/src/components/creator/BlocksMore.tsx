import { useEffect, useState, type ReactNode } from 'react';
import { Check, Clock, Mail, Phone, Globe } from 'lucide-react';
import type { Block } from '../../lib/creator/arrange.ts';
import { dayMonth, duration, money, shortDate, type Lang } from '../../lib/creator/format.ts';
import { commonTime, courseState, localToday, sessionsOf, weekdaysOf } from '../../lib/creator/course.ts';
import { seatsLeft } from '../../lib/venuesApi.ts';
import { blockLabel } from '../../i18n/blocks.ts';
import { PAGE60 } from '../../i18n/blocks60.ts';
import { heading, phoneText } from '../../lib/creator/show60.ts';
import { Av, Btn, Img, Price, SecHead, useCx, type Render } from './CreatorPage';
import { imageUrl } from '../../lib/creator/samples.ts';
import '../../styles/creator-more.css';

type Row = Record<string, any>;
const WEEK = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];

const dayName = (lang: Lang, d: string) =>
  new Intl.DateTimeFormat(lang === 'uk' ? 'uk-UA' : 'en-GB', { weekday: 'short', timeZone: 'UTC' }).format(Date.UTC(2026, 0, 5 + WEEK.indexOf(d)));

/** Mon–Fri, Sun: contiguous runs joined, the rest listed. */
export function dayRuns(days: string[], name: (d: string) => string): string {
  const idx = [...new Set(days)].map((d) => WEEK.indexOf(d)).filter((i) => i >= 0).sort((a, b) => a - b);
  const runs: number[][] = [];
  for (const i of idx) {
    const last = runs[runs.length - 1];
    if (last && i === last[last.length - 1] + 1) last.push(i);
    else runs.push([i]);
  }
  return runs.map((r) => (r.length > 2 ? `${name(WEEK[r[0]])}–${name(WEEK[r[r.length - 1]])}` : r.map((i) => name(WEEK[i])).join(', '))).join(', ');
}

const host = (url: string) => url.replace(/^https:\/\//, '').replace(/\/$/, '');

function Contact({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const rows: { icon: ReactNode; label: string; value: string; href: string }[] = [];
  if (d.phone) rows.push({ icon: <Phone size={16} aria-hidden="true" />, label: t.call, value: phoneText(d.phone), href: `tel:${d.phone}` });
  if (d.email) rows.push({ icon: <Mail size={16} aria-hidden="true" />, label: t.email, value: d.email, href: `mailto:${d.email}` });
  if (d.site?.url) rows.push({ icon: <Globe size={16} aria-hidden="true" />, label: t.website, value: d.site.label ?? host(d.site.url), href: d.site.url });
  if (!rows.length) return null;
  if (block.variant === 'buttons') {
    return (
      <div>
        <SecHead title={heading(block, lang)} />
        <div className="cx-chips">
          {rows.map((r) => (
            <a key={r.href} className="cx-btn ghost m" href={r.href}>{r.icon}{r.label}</a>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {rows.map((r) => (
          <a key={r.href} className="cx-row sp g16 cm-link" href={r.href}>
            <span className="cx-row g8 cx-muted">{r.icon}{r.label}</span>
            <span className="cx-tnum">{r.value}</span>
          </a>
        ))}
      </div>
    </div>
  );
}

function Places({ block }: { block: Block }) {
  const { lang } = useCx();
  const d = block.data;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as Row[]).map((p) => (
          <div key={p.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{p.name}</div>
              <div className="cx-faint cx-small">{p.address}</div>
            </div>
            {p.hours && <span className="cx-tnum cx-muted">{p.hours}</span>}
          </div>
        ))}
      </div>
    </div>
  );
}

function Transit({ block }: { block: Block }) {
  const { lang } = useCx();
  const d = block.data;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cm-split">
        {d.image && <Img image={d.image} className="cm-img" />}
        <div className="cx-card cx-pad cx-rows">
          {(d.items as Row[]).map((w) => (
            <div key={w.id} className="cx-row sp g16">
              <span className="cx-muted">{blockLabel(`vocab.mode.${w.mode}`, lang)}</span>
              <span className="cm-right">{w.text}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function Delivery({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as Row[]).map((z) => {
          const sub = [z.min && t.from(`${money(lang, z.min)} ${d.token}`), z.eta && t.within(duration(lang, z.eta))].filter(Boolean).join(' · ');
          return (
            <div key={z.id} className="cx-row sp g16">
              <div>
                <div className="cx-pname">{z.area}</div>
                {sub && <div className="cx-faint cx-small">{sub}</div>}
              </div>
              <Price amount={z.fee} token={d.token} />
            </div>
          );
        })}
        {d.pickup && (
          <div className="cx-row sp g16">
            <div className="cx-pname">{t.pickup}</div>
            <span>{t.free}</span>
          </div>
        )}
      </div>
    </div>
  );
}

function Exceptions({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as Row[]).map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{shortDate(lang, x.date)}</div>
              {x.note && <div className="cx-faint cx-small">{x.note}</div>}
            </div>
            <span className={`cx-tnum ${x.hours ? '' : 'cx-muted'}`}>{x.hours ? `${x.hours.open}–${x.hours.close}` : t.closed}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function HappyHour({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const when = `${dayRuns(d.days, (x) => dayName(lang, x))} · ${d.hours.open}–${d.hours.close}`;
  if (block.variant === 'line') {
    return (
      <div className="cx-card cx-pad cx-row sp g16 cm-wrap">
        <span className="cx-pname">{heading(block, lang)}{typeof d.percent === 'number' && ` · ${t.off(d.percent)}`}</span>
        <span className="cx-tnum cx-muted">{when}</span>
      </div>
    );
  }
  return (
    <div className="cx-card cx-pad cx-col g8">
      <span className="cx-lbl">{heading(block, lang)}</span>
      {typeof d.percent === 'number' && <span className="cx-big cx-tnum">{t.off(d.percent)}</span>}
      <span className="cx-tnum">{when}</span>
      {d.note && <span className="cx-muted">{d.note}</span>}
    </div>
  );
}

function Tags({ block, vocab }: { block: Block; vocab: string }) {
  const { lang } = useCx();
  const d = block.data;
  const names = (d.tags as string[]).map((x) => blockLabel(`vocab.${vocab}.${x}`, lang));
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      {block.variant === 'list' ? (
        <ul className="cx-card cx-pad cx-rows cm-list">
          {names.map((n) => <li key={n} className="cx-row">{n}</li>)}
        </ul>
      ) : (
        <div className="cx-chips">
          {names.map((n) => <span key={n} className="cx-chip line">{n}</span>)}
        </div>
      )}
      {d.note && <p className="cx-muted cx-small cm-note">{d.note}</p>}
    </div>
  );
}

function Rules({ block }: { block: Block }) {
  const { lang } = useCx();
  const d = block.data;
  const Tag = block.variant === 'list' ? 'ul' : 'ol';
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <Tag className={`cx-card cx-pad cm-ol ${Tag}`}>
        {(d.items as Row[]).map((r) => <li key={r.id}>{r.text}</li>)}
      </Tag>
    </div>
  );
}

function PriceTable({ block }: { block: Block }) {
  const { lang } = useCx();
  const d = block.data;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as Row[]).map((r) => (
          <div key={r.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{r.name}</div>
              {(r.unit || r.note) && <div className="cx-faint cx-small">{[r.unit, r.note].filter(Boolean).join(' · ')}</div>}
            </div>
            <Price amount={r.price} token={d.token} />
          </div>
        ))}
      </div>
    </div>
  );
}

const today = () => new Date().toISOString().slice(0, 10);
const range = (lang: Lang, r: { from: string; to: string }) => (r.from === r.to ? shortDate(lang, r.from) : `${shortDate(lang, r.from)} – ${shortDate(lang, r.to)}`);

function Featured({ block }: { block: Block }) {
  const { lang, byId } = useCx();
  const src = byId.get(block.data.from);
  if (!src || src.hidden) return null;
  const items = (src.data.items as Row[]).filter((p) => p.stock !== 0).slice(0, block.data.show);
  if (!items.length) return null;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className={`cx-products cm-feat n${items.length}`}>
        {items.map((p, i) => (
          <article key={p.id} className="cx-prod">
            <div className={`cx-ph ${p.image && imageUrl(p.image.blob) ? '' : 'empty'}`}>
              <Img image={p.image} tone={(i % 6) + 1} empty={p.name} />
            </div>
            <div className="cx-meta">
              <div className="cx-pname">{p.name}</div>
              <Price amount={p.price} token={src.data.token} />
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

function Specials({ block }: { block: Block }) {
  const { lang } = useCx();
  const d = block.data;
  const now = today();
  const items = (d.items as Row[]).filter((x) => !x.dates || x.dates.to >= now);
  if (!items.length) return null;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {items.map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{x.name}</div>
              {(x.dates || x.note) && <div className="cx-faint cx-small">{[x.dates && range(lang, x.dates), x.note].filter(Boolean).join(' · ')}</div>}
            </div>
            <Price amount={x.price} token={d.token} />
          </div>
        ))}
      </div>
    </div>
  );
}

function Rental({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const per = (m: number) => (m === 60 ? t.perHour : m === 1440 ? t.perDay : t.per(duration(lang, m)));
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as Row[]).map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{x.name}</div>
              <div className="cx-faint cx-small">{[per(x.per), x.deposit && t.deposit(`${x.deposit} ${d.token}`)].filter(Boolean).join(' · ')}</div>
            </div>
            <Price amount={x.price} token={d.token} />
          </div>
        ))}
      </div>
    </div>
  );
}

function Offer({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  if (d.dates.to < today()) return null;
  const off = typeof d.percent === 'number' && d.percent > 0 ? t.off(d.percent) : null;
  if (block.variant === 'banner') {
    return (
      <div className="cx-card cx-pad cx-row sp g16 cm-wrap">
        <span className="cx-pname">{d.title}{off && ` · ${off}`}</span>
        <span className="cx-tnum cx-muted">{range(lang, d.dates)}</span>
      </div>
    );
  }
  return (
    <div className="cx-card cx-pad cx-col g8">
      <span className="cx-lbl">{d.title}</span>
      {off && <span className="cx-big cx-tnum">{off}</span>}
      <span className="cx-tnum">{range(lang, d.dates)}</span>
      {d.terms && <span className="cx-muted">{d.terms}</span>}
    </div>
  );
}

function FirstVisit({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  if (block.variant === 'line') {
    return (
      <div className="cx-card cx-pad cx-row sp g16 cm-wrap">
        <span className="cx-pname">{heading(block, lang)} · {t.off(d.percent)}</span>
        {d.on && <span className="cx-muted">{d.on}</span>}
      </div>
    );
  }
  return (
    <div className="cx-card cx-pad cx-col g8">
      <span className="cx-lbl">{heading(block, lang)}</span>
      <span className="cx-big cx-tnum">{t.off(d.percent)}</span>
      {d.on && <span>{d.on}</span>}
      {d.note && <span className="cx-muted">{d.note}</span>}
    </div>
  );
}

function GiftCard({ block }: { block: Block }) {
  const { lang, act } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  return (
    <div className="cx-card cx-pad cx-col g16">
      <span className="cx-lbl">{heading(block, lang)}</span>
      <div className="cx-chips">
        {(d.amounts as Row[]).map((a) => <span key={a.id} className="cx-chip line cx-tnum">{a.value} {d.token}</span>)}
        {d.custom && <span className="cx-chip line">{t.anyAmount}</span>}
      </div>
      <div className="cx-row sp g16 cm-wrap">
        {typeof d.months === 'number' ? <span className="cx-muted">{t.months(d.months)}</span> : <span />}
        <Btn kind="ghost" size="m" onClick={act}>{t.buyGift}</Btn>
      </div>
    </div>
  );
}

function Packages({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const items = d.items as Row[];
  const sub = (x: Row) => [t.sessions(x.sessions), typeof x.months === 'number' && t.months(x.months)].filter(Boolean).join(' · ');
  if (block.variant === 'cards') {
    return (
      <div>
        <SecHead title={heading(block, lang)} />
        <div className="cm-cards">
          {items.map((x) => (
            <div key={x.id} className="cx-card cx-pad cx-col g8">
              <div className="cx-row sp g16">
                <span className="cx-pname">{x.name}</span>
                <Price amount={x.price} token={d.token} />
              </div>
              <span className="cx-muted cx-small">{sub(x)}</span>
              {x.includes && <span>{x.includes}</span>}
            </div>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {items.map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{x.name}</div>
              <div className="cx-faint cx-small">{[sub(x), x.includes].filter(Boolean).join(' · ')}</div>
            </div>
            <Price amount={x.price} token={d.token} />
          </div>
        ))}
      </div>
    </div>
  );
}

function Staff({ block }: { block: Block }) {
  const { lang } = useCx();
  const items = block.data.items as Row[];
  const face = (x: Row) => (x.image && imageUrl(x.image.blob) ? <Img image={x.image} className="cm-face" /> : <Av name={x.name} size="l" />);
  if (block.variant === 'list') {
    return (
      <div>
        <SecHead title={heading(block, lang)} />
        <div className="cx-card cx-pad cx-rows">
          {items.map((x) => (
            <div key={x.id} className="cx-row g16">
              {face(x)}
              <div>
                <div className="cx-pname">{x.name}</div>
                {x.role && <div className="cx-faint cx-small">{x.role}</div>}
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cm-faces">
        {items.map((x) => (
          <div key={x.id} className="cx-col g8 cm-person">
            {face(x)}
            <div className="cx-pname">{x.name}</div>
            {x.role && <div className="cx-faint cx-small">{x.role}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}

function Table({ block }: { block: Block }) {
  const { lang, act } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const line = [t.guests(d.party_min, d.party_max), `${d.hours.open}–${d.hours.close}`, t.tableFor(duration(lang, d.slot))].join(' · ');
  return (
    <div className="cx-card cx-pad cx-col g8">
      <span className="cx-lbl">{heading(block, lang)}</span>
      <span className="cx-tnum">{line}</span>
      {d.auto && <span className="cx-muted cx-small">{t.confirmedAtOnce}</span>}
      {d.note && <span className="cx-muted">{d.note}</span>}
      <div className="cm-act"><Btn kind="ghost" size="m" onClick={act}>{t.bookTable}</Btn></div>
    </div>
  );
}

function Waitlist({ block }: { block: Block }) {
  const { lang, act, byId } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const target = byId.get(d.for);
  if (!target || target.hidden) return null;
  return (
    <div className="cx-card cx-pad cx-col g8">
      <span className="cx-lbl">{heading(block, lang)}</span>
      <span>{t.forWhat(heading(target, lang))}</span>
      {typeof d.capacity === 'number' && <span className="cx-muted cx-small">{t.places(d.capacity)}</span>}
      <div className="cm-act"><Btn kind="ghost" size="m" onClick={act}>{t.joinWait}</Btn></div>
    </div>
  );
}

function Policy({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const rows: [string, string][] = [[t.cancel, d.cancel === 0 ? t.freeUntilStart : t.freeUpTo(duration(lang, d.cancel * 60))]];
  if (typeof d.deposit === 'number' && d.deposit > 0) rows.push([t.depositL, `${d.deposit}%`]);
  if (d.no_show?.amount) rows.push([t.noShow, `${d.no_show.amount} ${d.no_show.token}`]);
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {rows.map(([k, v]) => (
          <div key={k} className="cx-row sp g16">
            <span className="cx-muted">{k}</span>
            <span className="cx-tnum cm-right">{v}</span>
          </div>
        ))}
        {d.note && <div className="cx-faint cx-small">{d.note}</div>}
      </div>
    </div>
  );
}

function Timetable({ block }: { block: Block }) {
  const { lang } = useCx();
  const items = block.data.items as Row[];
  const day = (x: string) => dayName(lang, x);
  const meta = (c: Row) => [duration(lang, c.length), c.coach, c.note].filter(Boolean).join(' · ');
  if (block.variant === 'week') {
    const days = WEEK.filter((w) => items.some((c) => c.days.includes(w)));
    return (
      <div>
        <SecHead title={heading(block, lang)} />
        <div className="cm-week">
          {days.map((w) => (
            <div key={w} className="cx-card cx-pad cx-col g8">
              <span className="cx-lbl">{day(w)}</span>
              {items.filter((c) => c.days.includes(w)).sort((a, b) => a.time.localeCompare(b.time)).map((c) => (
                <div key={c.id}>
                  <div className="cx-pname"><span className="cx-tnum">{c.time}</span> {c.name}</div>
                  <div className="cx-faint cx-small">{meta(c)}</div>
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {items.map((c) => (
          <div key={c.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{c.name}</div>
              <div className="cx-faint cx-small">{meta(c)}</div>
            </div>
            <span className="cx-tnum cx-muted cm-right">{dayRuns(c.days, day)} · {c.time}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function Course({ block }: { block: Block }) {
  const { lang, c, ask, venueId, sent } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const items = d.items as Row[];
  const total = items.reduce((n, m) => n + (m.lessons ?? 0), 0);
  const today = localToday();
  const sessions = sessionsOf(d, today);
  const state = courseState(d, today);
  const [left, setLeft] = useState<number | null>(null);
  useEffect(() => {
    if (!venueId || state !== 'open') return;
    let on = true;
    seatsLeft(venueId, block.id).then((n) => on && setLeft(n), () => {});
    return () => {
      on = false;
    };
  }, [venueId, block.id, state, sent]);
  if (sessions.length === 0) {
    return (
      <div>
        <SecHead title={d.title} right={d.price ? <Price amount={d.price} token={d.token} /> : undefined} />
        <div className="cx-card cx-pad cx-col g8">
          <ol className="cm-ol ol">
            {items.map((m) => (
              <li key={m.id}>
                <span className="cx-pname">{m.name}</span>
                {(m.lessons || m.length) && <span className="cx-faint cx-small"> · {[m.lessons && t.lessons(m.lessons), m.length && duration(lang, m.length)].filter(Boolean).join(' · ')}</span>}
              </li>
            ))}
          </ol>
          {total > 0 && <span className="cx-muted cx-small cm-note">{t.lessons(total)}</span>}
        </div>
      </div>
    );
  }
  const next = sessions.find((x) => x.state === 'next');
  const time = commonTime(sessions);
  const days = weekdaysOf(sessions.filter((x) => x.state !== 'done').length ? sessions.filter((x) => x.state !== 'done') : sessions);
  const free = state === 'open' ? left : null;
  const full = free === 0;
  const priceText = d.price ? ` · ${money(lang, d.price)} ${d.token}` : '';
  return (
    <div className="cx-col g16">
      <SecHead title={d.title} />
      <div className="cm-facts">
        <div className="cx-card cm-fact">
          <span className="cx-lbl">{c.courseLength}</span>
          <b className="cx-tnum">{t.sessions(sessions.length)}</b>
        </div>
        {days.length > 0 && (
          <div className="cx-card cm-fact">
            <span className="cx-lbl">{c.schedule}</span>
            <b className="cx-tnum">{[dayRuns(days, (x) => dayName(lang, x)), time].filter(Boolean).join(' · ')}</b>
          </div>
        )}
        {typeof d.seats === 'number' && (
          <div className="cx-card cm-fact">
            <span className="cx-lbl">{free === null ? c.seats : c.seatsFree}</span>
            <b className="cx-tnum">{c.seatsOf(d.seats, free)}</b>
          </div>
        )}
      </div>
      {state === 'open' && !full && (
        <div className="cm-cta">
          <Btn kind="primary" size="l" wide onClick={() => ask({ block, course: { title: d.title, next: next!, price: d.price ?? null, token: d.token } })}>
            {c.enroll}
            {priceText}
          </Btn>
        </div>
      )}
      {full && <span className="cx-chip line cx-self">{c.courseFull}</span>}
      {state === 'over' && <span className="cx-chip line cx-self">{c.courseOver}</span>}
      <div className="cx-col g8">
        <h3 className="cx-h3">{c.learn}</h3>
        <div className="cx-card cm-lines">
          {items.map((m) => (
            <div key={m.id} className="cx-row g8 cm-learn">
              <span className="cm-dot" aria-hidden="true" />
              <span className="cx-pname">{m.name}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="cx-col g8">
        <h3 className="cx-h3">{c.schedule}</h3>
        <ol className="cx-card cm-lines cm-list cm-sess">
          {sessions.map((x) => (
            <li key={x.id} className={`cx-row sp g12 ${x.state}`}>
              <div className="cx-col cm-g2">
                <span className="cx-row g8 cx-small cx-muted">
                  {c.lessonN(x.n)}
                  {x.state === 'done' && <span className="cm-tag">{c.done}</span>}
                  {x.state === 'next' && <span className="cm-tag on">{c.nextOne}</span>}
                </span>
                <span className="cx-pname cm-sname">{x.name}</span>
                <span className="cx-faint cx-small cx-tnum">{[dayMonth(lang, x.date), x.time].filter(Boolean).join(' · ')}</span>
              </div>
              {x.state === 'done' ? <Check size={18} className="cx-faint" aria-hidden="true" /> : <Clock size={18} className="cx-muted" aria-hidden="true" />}
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

function Passes({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as Row[]).map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{t.visits(x.visits)}</div>
              {typeof x.months === 'number' && <div className="cx-faint cx-small">{t.months(x.months)}</div>}
            </div>
            <Price amount={x.price} token={d.token} />
          </div>
        ))}
      </div>
    </div>
  );
}

function Membership({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const items = d.items as Row[];
  const price = (x: Row) => <span className="cx-row cm-g4"><Price amount={x.price} token={d.token} /><span className="cx-faint cx-small">{t.perMonth}</span></span>;
  if (block.variant === 'cards') {
    return (
      <div>
        <SecHead title={heading(block, lang)} />
        <div className="cm-cards">
          {items.map((x) => (
            <div key={x.id} className="cx-card cx-pad cx-col g8">
              <span className="cx-pname">{x.name}</span>
              {price(x)}
              {x.includes && <span className="cx-muted">{x.includes}</span>}
            </div>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {items.map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{x.name}</div>
              {x.includes && <div className="cx-faint cx-small">{x.includes}</div>}
            </div>
            {price(x)}
          </div>
        ))}
      </div>
    </div>
  );
}

const startLine = (lang: Lang, ev: Block) => `${shortDate(lang, String(ev.data.starts).slice(0, 10))} · ${String(ev.data.starts).slice(11, 16)}`;

function Tickets({ block }: { block: Block }) {
  const { lang, act, byId } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  const ev = byId.get(d.event);
  if (!ev || ev.hidden) return null;
  const now = today();
  const tiers = (d.items as Row[]).filter((x) => !x.until || x.until >= now);
  if (!tiers.length) return null;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        <div className="cx-faint cx-small">{ev.data.title} · {startLine(lang, ev)}</div>
        {tiers.map((x) => (
          <div key={x.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{x.name}</div>
              {x.until && <div className="cx-faint cx-small">{t.until(shortDate(lang, x.until))}</div>}
            </div>
            <Price amount={x.price} token={d.token} />
          </div>
        ))}
        <div className="cm-act"><Btn kind="ghost" size="m" onClick={act}>{t.getTickets}</Btn></div>
      </div>
    </div>
  );
}

function Series({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  if (d.ends && d.ends < today()) return null;
  return (
    <div className="cx-card cx-pad cx-col g8">
      <span className="cx-lbl">{d.title}</span>
      <span className="cx-pname cx-tnum">{t.every(dayRuns(d.days, (x) => dayName(lang, x)))} · {d.hours.open}–{d.hours.close}</span>
      <span className="cx-tnum cx-muted">{d.ends ? range(lang, { from: d.starts, to: d.ends }) : shortDate(lang, d.starts)}</span>
      {d.note && <span className="cx-muted">{d.note}</span>}
    </div>
  );
}

function Agenda({ block }: { block: Block }) {
  const { lang, byId } = useCx();
  const ev = block.data.event ? byId.get(block.data.event) : undefined;
  return (
    <div>
      <SecHead title={heading(block, lang)} />
      <div className="cx-card cx-pad cx-rows">
        {ev && !ev.hidden && <div className="cx-faint cx-small">{ev.data.title} · {startLine(lang, ev)}</div>}
        {(block.data.items as Row[]).map((x) => (
          <div key={x.id} className="cx-row g16">
            <span className="cx-tnum cx-muted cm-time">{x.time}</span>
            <div>
              <div className="cx-pname">{x.name}</div>
              {x.who && <div className="cx-faint cx-small">{x.who}</div>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function Stamps({ block }: { block: Block }) {
  const { lang } = useCx();
  const t = PAGE60[lang];
  const d = block.data;
  return (
    <div className="cx-card cx-pad cx-col g16">
      <span className="cx-lbl">{heading(block, lang)}</span>
      <div className="cm-stamps" aria-hidden="true" style={{ gridTemplateColumns: `repeat(${d.stamps <= 6 ? d.stamps : Math.ceil(d.stamps / 2)}, minmax(0, 40px))` }}>
        {Array.from({ length: d.stamps }, (_, i) => <span key={i} className="cm-stamp" />)}
      </div>
      <div>
        <div className="cx-pname">{d.reward}</div>
        <div className="cx-faint cx-small">{[t.stamps(d.stamps), d.note].filter(Boolean).join(' · ')}</div>
      </div>
    </div>
  );
}

export const MORE_RENDERERS: Record<string, Render> = {
  'loc.contact': ({ block }) => <Contact block={block} />,
  'loc.list': ({ block }) => <Places block={block} />,
  'loc.transit': ({ block }) => <Transit block={block} />,
  'loc.service_area': ({ block }) => <Delivery block={block} />,
  'hrs.exceptions': ({ block }) => <Exceptions block={block} />,
  'hrs.happy_hour': ({ block }) => <HappyHour block={block} />,
  'ame.amenities': ({ block }) => <Tags block={block} vocab="amenity" />,
  'ame.access': ({ block }) => <Tags block={block} vocab="access" />,
  'ame.rules': ({ block }) => <Rules block={block} />,
  'cat.price_table': ({ block }) => <PriceTable block={block} />,
  'cat.featured': ({ block }) => <Featured block={block} />,
  'cat.specials': ({ block }) => <Specials block={block} />,
  'cat.rental': ({ block }) => <Rental block={block} />,
  'deal.offer': ({ block }) => <Offer block={block} />,
  'deal.first_visit': ({ block }) => <FirstVisit block={block} />,
  'deal.gift_card': ({ block }) => <GiftCard block={block} />,
  'book.packages': ({ block }) => <Packages block={block} />,
  'book.staff': ({ block }) => <Staff block={block} />,
  'book.table': ({ block }) => <Table block={block} />,
  'book.waitlist': ({ block }) => <Waitlist block={block} />,
  'book.policy': ({ block }) => <Policy block={block} />,
  'cls.timetable': ({ block }) => <Timetable block={block} />,
  'cls.course': ({ block }) => <Course block={block} />,
  'cls.pass': ({ block }) => <Passes block={block} />,
  'cls.membership': ({ block }) => <Membership block={block} />,
  'evt.tickets': ({ block }) => <Tickets block={block} />,
  'evt.series': ({ block }) => <Series block={block} />,
  'evt.agenda': ({ block }) => <Agenda block={block} />,
  'evt.lineup': ({ block }) => <Staff block={block} />,
  'loy.stamps': ({ block }) => <Stamps block={block} />,
};
