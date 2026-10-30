/**
 * Рядок і група згрупованого списку «Я» (§7.2): картка `raised`, рядки з
 * іконкою Lucide 1.75, заголовок групи — `SectionTitle`.
 *
 * Рядки тут приходять зі своїх джерел (міст телефона) і можуть не прийти
 * зовсім — тоді рядка немає. Тому розділювач живе в самому рядку (над ним,
 * окрім першого), а група без жодного рядка ховається разом із заголовком:
 * порожньої картки й «висячої» риски не буває.
 */
import React from 'react';
import { ChevronRight, type LucideIcon } from 'lucide-react';
import { SectionTitle } from './MeSections';

export const MeRow: React.FC<{
  icon: LucideIcon;
  title: string;
  meta?: string;
  onClick: () => void;
  testId?: string;
}> = ({ icon: Icon, title, meta, onClick, testId }) => (
  <button
    type="button"
    data-testid={testId}
    onClick={onClick}
    className="relative w-full min-h-14 px-4 py-2 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay before:absolute before:left-12 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden"
  >
    <Icon className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
    <span className="flex-1 min-w-0">
      <span className="block text-body text-ink">{title}</span>
      {meta && <span className="block truncate text-caption text-ink-3">{meta}</span>}
    </span>
    <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
  </button>
);

export const MeTile: React.FC<{
  icon: LucideIcon;
  title: string;
  value?: string;
  onClick: () => void;
  testId: string;
  wide?: boolean;
}> = ({ icon: Icon, title, value, onClick, testId, wide }) => (
  <button
    type="button"
    data-testid={testId}
    onClick={onClick}
    className={`${wide ? 'col-span-2 ' : ''}h-[84px] rounded-[16px] bg-raised px-3.5 py-3 flex flex-col items-start text-left hover:bg-overlay active:bg-overlay`}
  >
    <Icon className="w-[22px] h-[22px] shrink-0 text-ink" strokeWidth={1.75} aria-hidden="true" />
    <span className="mt-auto text-callout font-semibold text-ink">{title}</span>
    <span data-me-tile-value className="min-h-[18px] text-footnote text-ink-3 truncate max-w-full">{value}</span>
  </button>
);

export const MeGroup: React.FC<{ title: string; testId?: string; children: React.ReactNode }> = ({ title, testId, children }) => (
  <section data-testid={testId} className="has-[>div:empty]:hidden">
    <SectionTitle>{title}</SectionTitle>
    <div className="mx-4 rounded-[16px] bg-raised overflow-hidden empty:hidden">{children}</div>
  </section>
);
