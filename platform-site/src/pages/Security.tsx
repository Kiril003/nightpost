import {
  Binary,
  Check,
  Fingerprint,
  Gauge,
  Globe,
  KeyRound,
  Lock,
  MapPinned,
  MessageSquareText,
  Server,
  ShieldAlert,
  ShieldCheck,
  ShieldOff,
  Sparkles,
  UserRound,
  MonitorSmartphone,
} from 'lucide-react';
import { CtaBandSection, PageHero, PointGrid, Section, SectionHead, StatusBoard, Steps } from '../components/ui';
import { useLanguage } from '../i18n';
import { securityCopy, type EgressRow, type SecurityCopy } from '../i18n/security';
import type { Item } from '../i18n/types';
import { usePageMeta } from '../lib/meta';
import { CHANNELS, mailto } from '../lib/company';
import { OFFLINE_GRACE_DAYS, TRIAL_DAYS, planById } from '../lib/plans';

/** Межа, яку сторінка називає вголос: тонована підкладка, без рамки й тіні. */
function Caveat({ label, children }: { label: string; children: string }) {
  return (
    <div className="mt-10 flex gap-3 rounded-card bg-accent-soft p-5 md:p-6">
      <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
      <div className="min-w-0">
        <p className="text-subhead font-semibold text-ink">{label}</p>
        <p className="mt-1.5 max-w-prose text-callout text-ink">{children}</p>
      </div>
    </div>
  );
}

/** Шлях запиту відділяємо від людської назви: назва — словами, адреса — кодом. */
function CallName({ call }: { call: string }) {
  const [name, ...rest] = call.split(' · ');
  const path = rest.join(' · ');
  return (
    <>
      <span className="block text-headline text-ink">{name}</span>
      {path && (
        // Моно — лише для адрес. «STUN і TURN на нашій машині» — це слова.
        <span className={`mt-1 block break-words text-footnote text-ink-2 ${path.includes('/') ? 'font-mono' : ''}`}>
          {path}
        </span>
      )}
    </>
  );
}

/**
 * Перепис виходу в мережу.
 *
 * На телефоні — стос карток (чотири колонки в 390px не читаються навіть зі
 * скролом), від md — таблиця. Дані ті самі, розмітка двічі: рядок перепису не
 * має права зникнути лише на одній ширині.
 */
function EgressTable({ copy }: { copy: SecurityCopy['egress'] }) {
  const { columns, rows } = copy;
  const lastIndex = rows.length - 1;
  return (
    <>
      <ol className="mt-10 space-y-3 md:hidden">
        {rows.map((row, i) => (
          <li key={row.call} className="card p-5">
            <CallName call={row.call} />
            <dl className="mt-4 space-y-3">
              <Field term={columns.when} value={row.when} />
              <Field term={columns.carries} value={row.carries} />
              <Field term={columns.never} value={row.never} strong={i === lastIndex} />
            </dl>
          </li>
        ))}
      </ol>

      <div className="card mt-10 hidden overflow-x-auto md:block">
        <table className="w-full min-w-[880px] border-collapse text-left">
          <caption className="sr-only">{copy.title}</caption>
          <thead>
            <tr className="border-b border-line">
              {[columns.call, columns.when, columns.carries, columns.never].map((h) => (
                <th key={h} scope="col" className="px-5 py-4 align-bottom text-subhead font-semibold text-ink-2">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row: EgressRow, i) => (
              <tr key={row.call} className={i > 0 ? 'border-t border-line' : ''}>
                <th scope="row" className="w-[22%] px-5 py-5 align-top font-normal">
                  <CallName call={row.call} />
                </th>
                <td className="px-5 py-5 align-top text-callout text-ink-2">{row.when}</td>
                <td className="px-5 py-5 align-top text-callout text-ink-2">{row.carries}</td>
                <td className={`px-5 py-5 align-top text-callout ${i === lastIndex ? 'font-semibold text-ink' : 'text-ink-2'}`}>
                  {row.never}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Field({ term, value, strong = false }: { term: string; value: string; strong?: boolean }) {
  return (
    <div>
      <dt className="text-footnote font-semibold text-ink-3">{term}</dt>
      <dd className={`mt-0.5 text-callout ${strong ? 'font-semibold text-ink' : 'text-ink-2'}`}>{value}</dd>
    </div>
  );
}

/** Три числа, які рецензент записує. Значення — з lib/plans.ts. */
function Facts({ items }: { items: { value: string; label: string }[] }) {
  return (
    <dl className="mt-10 grid gap-x-8 gap-y-6 border-t border-line pt-8 sm:grid-cols-3">
      {items.map((fact) => (
        <div key={fact.label} className="flex flex-col-reverse">
          <dt className="mt-2 text-callout text-ink-2">{fact.label}</dt>
          <dd className="tnum text-h2 text-ink">{fact.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * Модель загроз одним екраном. Дві колонки рівної ваги: «не захищено» — не
 * дрібний шрифт під «захищено», а друга половина тієї самої правди.
 */
function ThreatColumn({ title, items, safe }: { title: string; items: Item[]; safe: boolean }) {
  const Icon = safe ? ShieldCheck : ShieldOff;
  return (
    <div className="border-t border-line pt-6">
      <h3 className="flex items-center gap-2.5 text-title-2 text-ink">
        <Icon className={`h-5 w-5 shrink-0 ${safe ? 'text-success' : 'text-ink-2'}`} strokeWidth={1.75} aria-hidden />
        {title}
      </h3>
      <ul className="mt-5 space-y-5">
        {items.map((item) => (
          <li key={item.title}>
            <p className="text-headline text-ink">{item.title}</p>
            <p className="mt-1 text-callout text-ink-2">{item.desc}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function Security() {
  const { lang } = useLanguage();
  const c = securityCopy[lang];
  usePageMeta(c.meta);

  // The free tier is the contract, so read it from the commercial model
  // itself — a feature added there shows up here without a copy edit.
  const neverGated = planById('free').features.map((f) => c.licence.freeLabels[f.key] ?? f.key);

  const facts = [
    { value: String(TRIAL_DAYS), label: c.licence.facts.trialLabel },
    { value: String(OFFLINE_GRACE_DAYS), label: c.licence.facts.graceLabel },
    { value: String(neverGated.length), label: c.licence.facts.freeLabel },
  ];

  const reportMailto = mailto('security', c.report.mailSubject);

  return (
    <>
      <PageHero eyebrow={c.hero.eyebrow} title={c.hero.title} sub={c.hero.sub} chips={c.hero.chips} />

      <Section id="threats" tone="raised">
        <SectionHead eyebrow={c.threats.eyebrow} title={c.threats.title} lead={c.threats.lead} />
        <div className="mt-10 grid gap-x-12 gap-y-10 md:grid-cols-2">
          <ThreatColumn title={c.threats.safeTitle} items={c.threats.safe} safe />
          <ThreatColumn title={c.threats.unsafeTitle} items={c.threats.unsafe} safe={false} />
        </div>
      </Section>

      <Section>
        <SectionHead eyebrow={c.residency.eyebrow} title={c.residency.title} lead={c.residency.lead} />
        <PointGrid cols={2} points={c.residency.points} icons={[Lock, Sparkles, KeyRound, Gauge]} />
        <Caveat label={c.ui.limitLabel}>{c.residency.caveat}</Caveat>
      </Section>

      <Section tone="raised" id="egress">
        <SectionHead eyebrow={c.egress.eyebrow} title={c.egress.title} lead={c.egress.lead} />
        <EgressTable copy={c.egress} />
        <p className="mt-6 max-w-prose text-callout text-ink-2">{c.egress.footnote}</p>
      </Section>

      <Section>
        <SectionHead eyebrow={c.zeroConfig.eyebrow} title={c.zeroConfig.title} lead={c.zeroConfig.lead} />
        <PointGrid cols={2} points={c.zeroConfig.points} icons={[UserRound, KeyRound, MonitorSmartphone, MapPinned]} />
      </Section>

      <Section tone="raised">
        <SectionHead eyebrow={c.licence.eyebrow} title={c.licence.title} lead={c.licence.lead} />
        <Steps steps={c.licence.steps} />
        <Facts items={facts} />

        <div className="mt-10 border-t border-line pt-8">
          <h3 className="text-title-2 text-ink">{c.licence.gateTitle}</h3>
          <p className="mt-3 max-w-prose text-callout text-ink-2">{c.licence.gateLead}</p>
          <ul className="mt-5 flex flex-wrap gap-2">
            {neverGated.map((label) => (
              <li
                key={label}
                className="inline-flex min-h-[36px] items-center gap-2 rounded-full bg-raised px-3.5 text-subhead text-ink"
              >
                <Check className="h-4 w-4 shrink-0 text-success" strokeWidth={2} aria-hidden />
                {label}
              </li>
            ))}
          </ul>
          <p className="mt-5 max-w-prose text-callout text-ink-2">{c.licence.gateNote}</p>
        </div>
      </Section>

      <Section>
        <SectionHead eyebrow={c.pairing.eyebrow} title={c.pairing.title} lead={c.pairing.lead} />
        <Steps steps={c.pairing.steps} />
        <p className="mt-8 max-w-prose text-callout text-ink-2">{c.pairing.note}</p>
      </Section>

      <Section tone="raised">
        <SectionHead eyebrow={c.peer.eyebrow} title={c.peer.title} lead={c.peer.lead} />
        <PointGrid cols={2} points={c.peer.points} icons={[Lock, Fingerprint, ShieldCheck, Binary]} />
        <Caveat label={c.peer.caveatTitle}>{c.peer.caveat}</Caveat>
      </Section>

      <Section>
        <SectionHead eyebrow={c.servers.eyebrow} title={c.servers.title} lead={c.servers.lead} />
        <PointGrid cols={2} points={c.servers.points} icons={[Fingerprint, Server, ShieldCheck, Globe]} />
      </Section>

      <Section tone="raised">
        <SectionHead eyebrow={c.posture.eyebrow} title={c.posture.title} lead={c.posture.lead} />
        <StatusBoard
          shippedTitle={c.posture.haveTitle}
          shipped={c.posture.have}
          nextTitle={c.posture.notYetTitle}
          next={c.posture.notYet}
        />
      </Section>

      <Section id="disclosure">
        <div className="max-w-3xl">
          <p className="eyebrow">{c.report.eyebrow}</p>
          <h2 className="mt-3 text-balance text-h2 text-ink">{c.report.title}</h2>
          <p className="mt-4 max-w-prose text-body text-ink-2 md:text-lead">{c.report.lead}</p>
          <div className="mt-8 flex flex-col items-start gap-4 sm:flex-row sm:items-center sm:gap-6">
            <a href={reportMailto} className="btn-primary btn-lg">
              <MessageSquareText className="h-5 w-5" strokeWidth={1.75} aria-hidden />
              {c.report.cta}
            </a>
            <p className="text-callout text-ink-2">
              {c.report.addressLabel}{' '}
              <a href={`mailto:${CHANNELS.security.email}`} className="link inline-flex min-h-[44px] items-center">
                {CHANNELS.security.email}
              </a>
            </p>
          </div>
          <p className="mt-6 max-w-prose text-callout text-ink-2">{c.report.note}</p>
        </div>
      </Section>

      <CtaBandSection data={c.cta} />
    </>
  );
}

