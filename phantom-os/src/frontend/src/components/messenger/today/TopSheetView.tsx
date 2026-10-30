import React, { useEffect, useRef, useSyncExternalStore } from 'react';
import { CalendarDays, ChevronRight, Search, SquarePen } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { AskField } from '../kit/AskField';
import { FilterChip } from '../kit/FilterChip';
import { canNavigate } from '../../../services/nav';
import { soundFx } from '../../../utils/messengerSound';
import { todayDateLine } from '../todayFormat';
import type { ListFilter } from '../../../services/chatFilters';
import type { TopSheetState } from './topSheet';
import type { DriftSource } from '../../../utils/ownSwipe';
import { PaneTitles } from '../moments/PaneTitles';

const still = () => () => {};
const none = () => null;

const ICON = { strokeWidth: 1.75 } as const;

const ICON_BTN =
  'w-12 h-12 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink hover:bg-overlay active:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-ink';

/** Під 400 px у рядку чотири кнопки: чип профілю — лише значок, щоб заголовок не став «…». */
const NARROW_PILL = 'max-[399px]:[&_[data-profile-pill]>span]:hidden max-[399px]:[&_[data-profile-pill]]:px-2';

export interface FilterTab {
  key: ListFilter;
  label: string;
}

/** Пʼять фільтрів (10 §2) — сталі місця, без перетягування й власних тек. */
export const ChatFilterStrip: React.FC<{
  tabs: FilterTab[];
  active: string;
  onPick: (key: ListFilter) => void;
  drift?: number | null;
  driftSource?: DriftSource;
  desk?: boolean;
}> = ({ tabs, active, onPick, drift: given = null, driftSource, desk = false }) => {
  const { t } = useTranslation();
  const live = useSyncExternalStore(driftSource?.subscribe ?? still, driftSource?.get ?? none);
  const drift = live ?? given;
  const refs = useRef<Record<string, HTMLSpanElement | null>>({});
  const box = useRef<HTMLDivElement>(null);
  const bar = (() => {
    if (drift === null || !tabs.length) return null;
    const i = Math.max(0, Math.min(tabs.length - 1, Math.floor(drift)));
    const j = Math.min(tabs.length - 1, i + 1);
    const f = Math.max(0, Math.min(1, drift - i));
    const a = refs.current[tabs[i].key]?.getBoundingClientRect();
    const b = refs.current[tabs[j].key]?.getBoundingClientRect();
    const base = box.current?.getBoundingClientRect().left ?? 0;
    if (!a || !b) return null;
    return { left: a.left - base + (b.left - a.left) * f, width: a.width + (b.width - a.width) * f };
  })();
  return (
    <div
      ref={box}
      data-filter-strip
      role="tablist"
      aria-label={t('msgV2.filters')}
      className={`relative flex items-center gap-1.5 px-4 ${desk ? 'flex-wrap py-2' : 'h-12 min-h-12 overflow-x-auto no-scrollbar'}`}
    >
      {tabs.map((tab) => (
        <FilterChip
          key={tab.key}
          data-filter-tab={tab.key}
          active={tab.key === active}
          pillRef={(el) => {
            refs.current[tab.key] = el;
          }}
          pillClassName="px-[10px]"
          onClick={() => {
            soundFx.playTap();
            onPick(tab.key);
          }}
        >
          {tab.label}
        </FilterChip>
      ))}
      {bar && (
        <span
          data-tab-drift
          aria-hidden
          className="absolute bottom-1 h-0.5 rounded-[1px] bg-ink pointer-events-none"
          style={{ left: bar.left, width: bar.width }}
        />
      )}
    </div>
  );
};

export interface TopSheetProps {
  state: TopSheetState;
  onStateChange: (next: TopSheetState) => void;
  showChips: boolean;
  scrolled: boolean;
  hidden?: boolean;
  ask?: boolean;
  desk?: boolean;
  onVoice?: () => void;
  onOpenSearch: (seed?: string) => void;
  onNewChat: () => void;
  onOpenDay: () => void;
  tabs: FilterTab[];
  activeTab: string;
  onPickTab: (key: ListFilter) => void;
  drift?: number | null;
  driftSource?: DriftSource;
  profileSlot?: React.ReactNode;
  /** Моменти між заголовком і пошуком (телефон); немає — смуги немає. */
  strip?: React.ReactNode;
  /** Згорнуті моменти біля заголовка. */
  mini?: React.ReactNode;
  /** Смугу згорнуто прокруткою: висота 0, чип профілю — лише значок. */
  stripCollapsed?: boolean;
  /** Режим вибору: ця шапка стає на місце рядка заголовка, моментів немає. */
  selectBar?: React.ReactNode;
  /** Телефон: День, що розгортається з шапки (стан open). */
  day?: React.ReactNode;
  /** Смужка дня під шапкою, поки день згорнутий. */
  dayLine?: React.ReactNode;
  /** Телефон: заголовок стає «Чати · Моменти»; крапка — є непобачені моменти. */
  panes?: { onMoments: () => void; dot?: boolean };
}

/** Висота смуги моментів: 4 + 60 + 4 + 16 + 10. Стала висота — один перехід, рядки не перераховуються. */
export const LIST_STRIP_H = 94;

export const TopSheet: React.FC<TopSheetProps> = ({
  state,
  onStateChange,
  showChips,
  scrolled,
  hidden = false,
  ask = false,
  desk = false,
  onVoice,
  onOpenSearch,
  onNewChat,
  onOpenDay,
  tabs,
  activeTab,
  onPickTab,
  drift = null,
  driftSource,
  profileSlot,
  strip,
  mini,
  stripCollapsed = false,
  selectBar,
  day,
  dayLine,
  panes,
}) => {
  const { t, locale } = useTranslation();
  const dateText = todayDateLine(new Date(), locale);
  const chipsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    chipsRef.current?.toggleAttribute('inert', !desk && hidden);
  }, [hidden, desk, showChips]);
  const stripRef = useRef<HTMLDivElement>(null);
  const folded = stripCollapsed || Boolean(selectBar);
  useEffect(() => {
    stripRef.current?.toggleAttribute('inert', folded);
  }, [folded, strip]);
  const dayRef = useRef<HTMLDivElement>(null);
  const dayOpen = !desk && Boolean(day) && state === 'open' && !selectBar;
  useEffect(() => {
    dayRef.current?.toggleAttribute('inert', !dayOpen);
  }, [dayOpen, day]);

  return (
    <div
      data-top-sheet
      data-sheet-state={state}
      data-bar-hidden={!desk && hidden ? 'true' : undefined}
      className={`bg-canvas${scrolled ? ' border-b-[0.5px] border-line' : ''}`}
    >
      {desk ? (
        <div data-today-bar className="h-14 pl-4 pr-2 flex items-center gap-1">
          <div className="min-w-0 flex-1 flex items-center gap-2">
            <h1 className="truncate text-ink text-[24px] leading-8 font-semibold">{t('today.title')}</h1>
            {profileSlot}
          </div>
          <button
            type="button"
            onClick={() => onOpenSearch()}
            className={ICON_BTN}
            aria-label={ask ? t('phoneChatList.searchOrAsk') : t('phoneChatList.searchChats')}
            data-search-pill
          >
            <Search className="w-[22px] h-[22px]" {...ICON} />
          </button>
          <button
            type="button"
            onClick={() => {
              soundFx.playTap();
              onNewChat();
            }}
            className={ICON_BTN}
            aria-label={t('phoneChatList.newDialog')}
            title={t('phoneChatList.newChat')}
          >
            <SquarePen className="w-[22px] h-[22px]" {...ICON} />
          </button>
        </div>
      ) : (
        <>
          {selectBar ?? (
          <div data-today-bar className="h-14 pl-4 pr-1 flex items-center gap-1">
            {panes ? (
              <PaneTitles pane="chats" dot={panes.dot} onPick={(p) => p === 'moments' && panes.onMoments()} />
            ) : (
              <h1 data-list-title className="min-w-0 shrink truncate text-ink text-[24px] leading-8 font-semibold">
                {t('msgV2.title')}
              </h1>
            )}
            {stripCollapsed && mini}
            <span aria-hidden className="flex-1" />
            {profileSlot && (
              <span
                data-profile-slot
                data-compact={stripCollapsed ? 'true' : undefined}
                data-narrow-icon={day ? 'true' : undefined}
                className={`shrink-0 flex ${stripCollapsed ? '[&_[data-profile-pill]>span]:hidden [&_[data-profile-pill]]:px-2' : day ? NARROW_PILL : ''}`}
              >
                {profileSlot}
              </span>
            )}
            {day && (
              <button
                type="button"
                data-today-open
                aria-expanded={dayOpen}
                onClick={() => {
                  soundFx.playTap();
                  onStateChange(dayOpen ? 'compact' : 'open');
                }}
                className={`${ICON_BTN}${dayOpen ? ' bg-overlay' : ''}`}
                aria-label={t('today.title')}
              >
                <CalendarDays className="w-[22px] h-[22px]" {...ICON} />
              </button>
            )}
            <button
              type="button"
              data-today-compose
              onClick={() => {
                soundFx.playTap();
                onNewChat();
              }}
              className={ICON_BTN}
              aria-label={t('phoneChatList.newDialog')}
              title={t('msgV2.newChat')}
            >
              <SquarePen className="w-[22px] h-[22px]" {...ICON} />
            </button>
          </div>
          )}
          {day && (
            <div
              data-day-wrap
              aria-hidden={dayOpen ? undefined : true}
              className={`grid transition-[grid-template-rows] duration-quick ease-standard motion-reduce:transition-none ${dayOpen ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'}`}
            >
              <div ref={dayRef} className="min-h-0 overflow-hidden">
                {dayOpen && day}
              </div>
            </div>
          )}
          {!dayOpen && !selectBar && dayLine}
          {strip && (
            <div
              data-strip-wrap
              data-collapsed={folded ? 'true' : undefined}
              aria-hidden={folded ? true : undefined}
              className="overflow-hidden transition-[height] duration-quick ease-standard"
              style={{ height: folded ? 0 : LIST_STRIP_H }}
            >
              <div ref={stripRef} className={`transition-opacity duration-quick ease-standard ${folded ? 'opacity-0' : 'opacity-100'}`}>
                {strip}
              </div>
            </div>
          )}
          <div className="px-4 pb-1">
            <AskField
              bar
              data-search-pill
              value=""
              onChange={(typed) => onOpenSearch(typed)}
              label={ask ? t('phoneChatList.searchOrAsk') : t('phoneChatList.searchChats')}
              placeholder={ask ? t('phoneChatList.searchOrAsk') : t('phoneChatList.searchPeople')}
              onVoice={ask && canNavigate() ? onVoice : undefined}
              voiceLabel={t('askNightpost.talk')}
              voiceTestId="search-ask-voice"
              onClick={(e) => {
                if ((e.target as HTMLElement | null)?.closest?.('button')) return;
                onOpenSearch();
              }}
              onFocus={(e) => {
                if ((e.target as HTMLElement | null)?.closest?.('button')) return;
                onOpenSearch();
              }}
            />
          </div>
          <div
            data-bar-collapse
            className={`grid transition-[grid-template-rows] duration-quick ease-standard ${hidden ? 'grid-rows-[0fr]' : 'grid-rows-[1fr]'}`}
          >
            <div ref={chipsRef} className="min-h-0 overflow-hidden">
              {showChips && (
                <ChatFilterStrip
                  tabs={tabs}
                  active={activeTab}
                  onPick={(key) => {
                    if (state === 'open') onStateChange('compact');
                    onPickTab(key);
                  }}
                  drift={drift}
                  driftSource={driftSource}
                />
              )}
            </div>
          </div>
        </>
      )}

      {desk && state === 'open' && (
        <div data-sheet-band className="h-14 px-4 flex items-center">
          <button
            type="button"
            data-today-date
            onClick={() => {
              soundFx.playTap();
              onOpenDay();
            }}
            className="self-start h-9 touch-floor-inline inline-flex items-center gap-1 text-title-2 text-ink active:opacity-80"
          >
            <span>{dateText}</span>
            <ChevronRight className="w-5 h-5 text-ink-3" {...ICON} aria-hidden />
          </button>
        </div>
      )}

      {desk && showChips && (
        <ChatFilterStrip
          tabs={tabs}
          active={activeTab}
          onPick={(key) => {
            if (state === 'open') onStateChange('compact');
            onPickTab(key);
          }}
          drift={drift}
          driftSource={driftSource}
          desk
        />
      )}
    </div>
  );
};
