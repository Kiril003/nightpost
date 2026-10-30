/**
 * Один інфо-вигляд для людини, групи, каналу, простору й справи:
 * обличчя (обкладинка?) → опис → одна бурштинова дія + до трьох швидких →
 * рядки виду → вкладки, що прилипають під верхньою смугою → вміст вкладки.
 * Коли обличчя виїхало вгору, смуга показує мале обличчя й назву.
 */
import React, { useEffect, useRef, useState } from 'react';
import { ArrowLeft, ChevronRight, MoreVertical } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';

export interface InfoAction {
  key: string;
  label: string;
  icon: React.ReactNode;
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  testId?: string;
}

/** Головна дія; `tonal` — коли бурштин на екрані вже має інше місце. */
export interface InfoPrimary extends InfoAction {
  tonal?: boolean;
}

export interface InfoRowSpec {
  key: string;
  icon: React.ReactNode;
  label: string;
  /** Стан праворуч: «Активне», «Усі». */
  aside?: string;
  /** Лічильник праворуч; 0 не малюється. */
  count?: number;
  onClick?: () => void;
  toggle?: { on: boolean; onChange: (on: boolean) => void };
  danger?: boolean;
  /** Дія на місці (підтвердження тут же), а не перехід — без шеврона. */
  inPlace?: boolean;
  testId?: string;
}

export interface InfoTab {
  key: string;
  label: string;
  /** Невідомо — без числа; 0 — вкладки немає, доки вона не обрана. */
  count?: number;
}

export interface InfoMenuItem {
  key: string;
  label: string;
  onClick: () => void;
  danger?: boolean;
}

export const QUICK_MAX = 3;

export const visibleTabs = (tabs: InfoTab[], active?: string): InfoTab[] =>
  tabs.filter((tab) => tab.count !== 0 || tab.key === active);

const tap = (fn: () => void) => () => {
  soundFx.playTap();
  fn();
};

export const InfoRows: React.FC<{ rows: InfoRowSpec[]; testId?: string }> = ({ rows, testId }) =>
  rows.length === 0 ? null : (
    <ul className="mt-2" data-testid={testId}>
      {rows.map((r, i) => {
        const body = (
          <>
            <span className={`w-10 h-10 shrink-0 rounded-full bg-raised flex items-center justify-center ${r.danger ? 'text-danger' : 'text-ink-2'}`}>{r.icon}</span>
            <span className={`flex-1 min-w-0 truncate text-headline ${r.danger ? 'text-danger' : 'text-ink'}`}>{r.label}</span>
            {r.aside && <span className="shrink-0 text-subhead text-ink-2">{r.aside}</span>}
            {r.count ? (
              <span className="shrink-0 min-w-6 h-6 px-2 rounded-full bg-raised text-caption font-semibold text-ink tnum flex items-center justify-center">{r.count}</span>
            ) : null}
            {r.toggle ? (
              <span
                aria-hidden="true"
                className={`shrink-0 w-11 h-6 rounded-full p-0.5 flex ${r.toggle.on ? 'bg-ink justify-end' : 'bg-raised justify-start'}`}
              >
                <span className={`w-5 h-5 rounded-full ${r.toggle.on ? 'bg-surface' : 'bg-ink-3'}`} />
              </span>
            ) : (
              r.onClick && !r.inPlace && <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
            )}
          </>
        );
        const cls = `w-full min-h-14 px-4 py-2 flex items-center gap-3.5 text-left ${i > 0 ? 'border-t border-line' : ''}`;
        const toggle = r.toggle;
        return (
          <li key={r.key}>
            {toggle ? (
              <button type="button" role="switch" aria-checked={toggle.on} data-testid={r.testId} onClick={tap(() => toggle.onChange(!toggle.on))} className={`${cls} active:bg-raised`}>
                {body}
              </button>
            ) : r.onClick ? (
              <button type="button" data-testid={r.testId} onClick={tap(r.onClick)} className={`${cls} active:bg-raised`}>
                {body}
              </button>
            ) : (
              <div className={cls} data-testid={r.testId}>
                {body}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );

export const InfoTabs: React.FC<{ tabs: InfoTab[]; active: string; onChange: (key: string) => void; className?: string }> = ({
  tabs,
  active,
  onChange,
  className = 'bg-surface',
}) => {
  const shown = visibleTabs(tabs, active);
  // Одна вкладка — це заголовок розділу, а не перемикач: без риски, без лінії під ним.
  const lone = shown.length === 1;
  return (
    <div role="tablist" className={`flex px-1 overflow-x-auto no-scrollbar ${lone ? '' : 'border-b border-line'} ${className}`} data-testid="info-tabs">
      {shown.map((tab) => {
        const on = tab.key === active;
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={on}
            data-testid={`info-tab-${tab.key}`}
            onClick={() => !on && tap(() => onChange(tab.key))()}
            className={`relative shrink-0 ${lone ? 'h-11' : 'h-12'} px-3 flex items-center gap-1.5 text-subhead whitespace-nowrap ${on ? 'text-ink' : 'text-ink-3'}`}
          >
            {tab.label}
            {tab.count ? <span className="font-normal tnum">{tab.count}</span> : null}
            {on && !lone && <span aria-hidden="true" data-tab-mark className="absolute left-3 right-3 -bottom-px h-[3px] rounded-t-[3px] bg-ink" />}
          </button>
        );
      })}
    </div>
  );
};

export interface InfoViewProps {
  testId?: string;
  /** Тло контейнера: аркуш телефона — canvas, колонка ПК — surface. Смуга й вкладки липнуть на ньому. */
  tone?: 'canvas' | 'surface';
  onBack: () => void;
  cover?: React.ReactNode;
  avatar: React.ReactNode;
  /** Мале обличчя для смуги, коли велике виїхало. */
  compactAvatar?: React.ReactNode;
  title: string;
  /** Свій testid заголовка, коли екран має старі прив'язки (профіль людини). */
  titleTestId?: string;
  kind: string;
  description?: string;
  /** Стани під обличчям: «Ви вийшли», «Заблоковано». */
  notes?: React.ReactNode;
  primary?: InfoPrimary;
  quick?: InfoAction[];
  /** Під діями, над рядками: цифри каналу для власника. */
  summary?: React.ReactNode;
  rows?: InfoRowSpec[];
  /** Блоки між рядками й вкладками: закріплене, план, смуга незнайомця. */
  between?: React.ReactNode;
  menu?: InfoMenuItem[];
  tabs?: InfoTab[];
  tab?: string;
  onTab?: (key: string) => void;
  children?: React.ReactNode;
}

export const InfoView: React.FC<InfoViewProps> = ({
  testId = 'info-view',
  tone = 'canvas',
  onBack,
  cover,
  avatar,
  compactAvatar,
  title,
  titleTestId = 'info-title',
  kind,
  description,
  notes,
  primary,
  quick = [],
  summary,
  rows = [],
  between,
  menu = [],
  tabs = [],
  tab,
  onTab,
  children,
}) => {
  const { t } = useTranslation();
  const head = useRef<HTMLDivElement>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    const el = head.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(([e]) => setCollapsed(!e.isIntersecting), { threshold: 0 });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  const shownQuick = quick.slice(0, QUICK_MAX);
  const bg = tone === 'surface' ? 'bg-surface' : 'bg-canvas';
  const activeTab = tab ?? tabs[0]?.key;

  return (
    <div className="relative" data-testid={testId}>
      <div className={`sticky top-0 z-10 h-14 pl-1 pr-1 flex items-center gap-2 ${bg}`} data-testid="info-bar">
        <button
          type="button"
          onClick={tap(onBack)}
          aria-label={t('infoView.back')}
          className="w-12 h-12 shrink-0 rounded-full text-ink flex items-center justify-center active:bg-raised"
        >
          <ArrowLeft className="w-6 h-6" strokeWidth={1.75} />
        </button>
        <span className={`flex-1 min-w-0 flex items-center gap-2.5 transition-opacity duration-150 motion-reduce:transition-none ${collapsed ? 'opacity-100' : 'opacity-0 pointer-events-none'}`} aria-hidden={!collapsed} data-testid="info-bar-identity">
          {compactAvatar}
          <span className="min-w-0">
            <span className="block text-headline text-ink truncate">{title}</span>
            <span className="block text-footnote text-ink-2 truncate">{kind}</span>
          </span>
        </span>
        {menu.length > 0 && (
          <button
            type="button"
            onClick={tap(() => setMenuOpen((v) => !v))}
            aria-label={t('infoView.more')}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            data-testid="info-more"
            className="w-12 h-12 shrink-0 rounded-full text-ink flex items-center justify-center active:bg-raised"
          >
            <MoreVertical className="w-6 h-6" strokeWidth={1.75} />
          </button>
        )}
        {menuOpen && (
          <>
            <div aria-hidden="true" className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
            <ul role="menu" className="absolute right-2 top-12 z-20 min-w-[220px] py-2 rounded-card bg-raised border border-line" data-testid="info-menu">
              {menu.map((m) => (
                <li key={m.key} role="none">
                  <button
                    type="button"
                    role="menuitem"
                    onClick={tap(() => {
                      setMenuOpen(false);
                      m.onClick();
                    })}
                    className={`w-full min-h-12 px-4 text-left text-body active:bg-overlay ${m.danger ? 'text-danger' : 'text-ink'}`}
                  >
                    {m.label}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>

      <div ref={head} data-testid="info-head">
        {cover}
        <div className={`px-4 flex items-start gap-3 ${cover ? '-mt-8 relative' : 'pt-1'}`}>
          <span className={`shrink-0 rounded-full ${cover ? `border-4 ${tone === 'surface' ? 'border-surface' : 'border-canvas'} ${bg}` : ''}`}>{avatar}</span>
          <span className={`min-w-0 flex-1 ${cover ? 'pt-9' : 'pt-3'}`}>
            <h2 className="text-title-1 text-ink truncate" data-testid={titleTestId}>
              {title}
            </h2>
            <p className="text-subhead text-ink-2 truncate" data-testid="info-kind">
              {kind}
            </p>
          </span>
        </div>
        {description && (
          <p className="px-4 pt-2 text-body text-ink whitespace-pre-wrap break-words" data-testid="info-description">
            {description}
          </p>
        )}
        {notes}
      </div>

      {(primary || shownQuick.length > 0) && (
        <div className="px-4 pt-3.5 pb-0.5 flex items-start gap-2" data-testid="info-actions">
          {primary && (
            <button
              type="button"
              data-testid={primary.testId ?? 'info-primary'}
              disabled={primary.disabled}
              onClick={tap(primary.onClick)}
              className={`flex-1 min-w-0 h-12 px-4 rounded-full flex items-center justify-center gap-2 text-headline disabled:opacity-40 ${
                primary.tonal ? 'bg-raised text-ink' : 'bg-accent text-on-accent active:bg-accent-press'
              }`}
            >
              {primary.icon}
              <span className="truncate">{primary.label}</span>
            </button>
          )}
          {shownQuick.map((q) => (
            <span key={q.key} className="flex flex-col items-center gap-1 text-caption text-ink-2">
              <button
                type="button"
                data-testid={q.testId}
                aria-label={q.label}
                aria-pressed={q.active}
                disabled={q.disabled}
                onClick={tap(q.onClick)}
                className={`w-12 h-12 rounded-full flex items-center justify-center disabled:opacity-40 ${q.active ? 'bg-ink text-surface' : 'bg-raised text-ink'}`}
              >
                {q.icon}
              </button>
              <span aria-hidden="true" className="max-w-[64px] truncate">
                {q.label}
              </span>
            </span>
          ))}
        </div>
      )}

      {summary}
      <InfoRows rows={rows} testId="info-rows" />
      {between}

      {tabs.length > 0 && activeTab && (
        <>
          <InfoTabs tabs={tabs} active={activeTab} onChange={(k) => onTab?.(k)} className={`sticky top-14 z-[9] mt-2 ${bg}`} />
          <div role="tabpanel" data-testid={`info-panel-${activeTab}`}>
            {children}
          </div>
        </>
      )}
      {tabs.length === 0 && children}
    </div>
  );
};

export default InfoView;
