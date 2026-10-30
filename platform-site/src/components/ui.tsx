import type { LucideIcon } from 'lucide-react';
import { ArrowRight, Check, Circle } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';
import type { CtaBand, Item, Step } from '../i18n/types';
import { mailto, type ChannelId } from '../lib/company';
import { BUILD_ANCHOR } from '../lib/downloads';
import { shellCopy } from '../i18n/company';
import { useLanguage } from '../i18n';

/*
 * Будівельні блоки сторінок у мові «теплої ночі».
 *
 * Ієрархію тримають шрифт і повітря, а не коробки: секції розділяє простір і
 * волосина, картка — це тло на крок вище й тонка межа, без тіні та сяйва.
 * Бурштин — лише там, де дія: головна кнопка, посилання, фокус.
 */

/** Шапка секції: підпис, заголовок, одне речення пояснення. */
export function SectionHead({
  eyebrow,
  title,
  lead,
  center = false,
}: {
  eyebrow: string;
  title: string;
  lead: string;
  center?: boolean;
  /** Колись — номер розділу. Лишився в підписі, щоб сторінки не правити. */
  index?: string;
}) {
  return (
    <div className={center ? 'mx-auto max-w-2xl text-center' : 'max-w-3xl'}>
      <p className="eyebrow">{eyebrow}</p>
      <h2 className="mt-3 text-balance text-h2 text-ink">{title}</h2>
      <p className={`mt-4 text-body text-ink-2 md:text-lead ${center ? 'mx-auto max-w-prose' : 'max-w-prose'}`}>{lead}</p>
    </div>
  );
}

/** Смуга сторінки. `raised` — тло на крок вище, щоб довга сторінка мала розділи. */
export function Section({
  children,
  className = '',
  id,
  tone = 'paper',
}: {
  children: ReactNode;
  className?: string;
  id?: string;
  tone?: 'paper' | 'raised';
  /** Колись вмикав content-visibility; лишився, щоб сторінки не правити. */
  contain?: boolean;
}) {
  const skin = tone === 'raised' ? 'bg-surface border-y border-line' : '';
  return (
    <section id={id} className={`px-4 py-14 sm:px-6 lg:px-14 md:py-20 ${skin} ${className}`}>
      <div className="mx-auto max-w-shell">{children}</div>
    </section>
  );
}

/** Відкриття внутрішньої сторінки: заголовок, підзаголовок, короткі факти. */
export function PageHero({
  eyebrow,
  title,
  sub,
  chips,
  aside,
}: {
  eyebrow: string;
  title: string;
  sub: string;
  chips: string[];
  accent?: string;
  aside?: ReactNode;
}) {
  return (
    <section className="px-4 pb-10 pt-12 sm:px-6 lg:px-14 md:pb-14 md:pt-20">
      <div className="mx-auto max-w-shell">
        <p className="eyebrow">{eyebrow}</p>
        <h1 className="mt-3 max-w-4xl text-balance text-page-h1 text-ink">{title}</h1>
        {sub && <p className="mt-5 max-w-prose text-body text-ink-2 md:text-lead">{sub}</p>}
        {aside}
        {chips.length > 0 && (
          <div className="mt-8 border-t border-line pt-6">
            <Chips items={chips} />
          </div>
        )}
      </div>
    </section>
  );
}



/** Рядок для сторінок старої картини продукту. */
export function LegacyNotice() {
  const { lang } = useLanguage();
  const s = shellCopy[lang].legacyNotice;
  return (
    <div role="note" className="border-b border-line bg-accent-soft px-4 py-3 text-center text-footnote text-ink">
      {s.text}{' '}
      <Link to="/" className="link whitespace-nowrap">
        {s.back}
      </Link>
    </div>
  );
}

/** Сітка тез: іконка, заголовок, абзац. Іконки однотонні — колір несе зміст. */
export function PointGrid({
  points,
  icons,
  cols = 3,
}: {
  points: Item[];
  icons: LucideIcon[];
  cols?: 2 | 3;
}) {
  const colClass = cols === 3 ? 'md:grid-cols-2 lg:grid-cols-3' : 'md:grid-cols-2';
  return (
    <div className={`mt-10 grid gap-x-10 gap-y-8 ${colClass}`}>
      {points.map((point, i) => {
        const Icon = icons[i % icons.length];
        return (
          <div key={point.title} className="border-t border-line pt-6">
            <Icon className="h-6 w-6 text-ink-2" strokeWidth={1.75} aria-hidden />
            <h3 className="mt-4 text-title-2 text-ink">{point.title}</h3>
            <p className="mt-2 text-callout text-ink-2">{point.desc}</p>
          </div>
        );
      })}
    </div>
  );
}

/** Короткі факти рядком: звичайний текст, без моно і без капсу. */
export function Chips({ items }: { items: string[] }) {
  return (
    <ul className="flex flex-wrap gap-2">
      {items.map((chip) => (
        <li
          key={chip}
          className="inline-flex min-h-[32px] items-center rounded-full bg-raised px-3 text-subhead text-ink-2"
        >
          {chip}
        </li>
      ))}
    </ul>
  );
}

/** Кроки: номер, заголовок, пояснення. Послідовність видно з порядку. */
export function Steps({ steps }: { steps: Step[] }) {
  return (
    <ol className="mt-10 grid gap-x-8 gap-y-8 sm:grid-cols-2 lg:grid-cols-4">
      {steps.map((step, i) => (
        <li key={step.k} className="border-t border-line pt-5">
          <span className="tnum inline-flex h-8 w-8 items-center justify-center rounded-full bg-raised text-subhead font-semibold text-ink">
            {i + 1}
          </span>
          <h3 className="mt-4 text-title-2 text-ink">{step.title}</h3>
          <p className="mt-2 text-callout text-ink-2">{step.desc}</p>
        </li>
      ))}
    </ol>
  );
}

/** Що вже працює і чого ще немає. Обидві правди повні; різна в них гучність. */
export function StatusBoard({
  shippedTitle,
  shipped,
  nextTitle,
  next,
}: {
  shippedTitle: string;
  shipped: string[];
  nextTitle: string;
  next: string[];
}) {
  return (
    <div className="mt-10 grid gap-6 lg:grid-cols-2 lg:gap-8">
      <div className="card p-6">
        <h3 className="text-headline text-ink">{shippedTitle}</h3>
        <ul className="mt-4 space-y-3">
          {shipped.map((item) => (
            <li key={item} className="flex gap-3 text-callout text-ink">
              <Check className="mt-1 h-4 w-4 shrink-0 text-success" strokeWidth={2} aria-hidden />
              <span>{item}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className="rounded-card border border-dashed border-line-strong p-6">
        <h3 className="text-headline text-ink-2">{nextTitle}</h3>
        <ul className="mt-4 space-y-3">
          {next.map((item) => (
            <li key={item} className="flex gap-3 text-callout text-ink-2">
              <Circle className="mt-1.5 h-3 w-3 shrink-0 text-ink-3" strokeWidth={2} aria-hidden />
              <span>{item}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/**
 * Завершальна смуга сторінки: заголовок і дві дії.
 *
 * Головна дія веде до файла (картка завантаження з вагою й джерелом), друга —
 * лист живій людині. Без темних панелей і плям світла: тло на крок вище.
 */
export function CtaBandSection({
  data,
  secondaryChannel = 'sales',
}: {
  data: CtaBand;
  secondaryChannel?: ChannelId;
}) {
  const { lang } = useLanguage();
  return (
    <Section>
      <div className="rounded-sheet bg-raised px-6 py-12 md:px-12 md:py-16">
        <div className="max-w-2xl">
          <h2 className="text-balance text-h2 text-ink">{data.title}</h2>
          <p className="mt-4 text-body text-ink-2 md:text-lead">{data.sub}</p>
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Link to={BUILD_ANCHOR} className="btn-primary btn-lg">
              {data.primary}
              <ArrowRight className="h-5 w-5" strokeWidth={1.75} aria-hidden />
            </Link>
            <a
              href={secondaryChannel === 'sales' ? mailto('sales', shellCopy[lang].demoSubject) : mailto(secondaryChannel)}
              className="btn-secondary btn-lg"
            >
              {data.secondary}
            </a>
          </div>
        </div>
      </div>
    </Section>
  );
}
