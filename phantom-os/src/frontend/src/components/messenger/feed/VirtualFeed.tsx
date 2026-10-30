/**
 * Стрічка розмови — справжня віртуалізація (`@tanstack/react-virtual`).
 *
 * ДОТИ тут було «вікно» на 120 листів, що лише РОСЛО вгору: догорнув історію —
 * у DOM лягли 600, 1200 складних бульбашок, і кожна зміна стану проходила по
 * всіх. Тепер у DOM живе те, що на екрані, плюс запас (`OVERSCAN`) — і так у
 * будь-якому місці історії, в обидва боки.
 *
 * Що стрічка мусить уміти, і хто це робить:
 *   - ПРИЗЕМЛЕННЯ в низ при відкритті — без анімації, людина бачить останнє;
 *   - ТРИМАННЯ НИЗУ: вміст доростає після приземлення (фото дешифрувалось,
 *     рядок переміряно) — ResizeObserver на колонці тягне назад донизу;
 *   - НОВЕ ЗНИЗУ: своє або чуже біля низу — плавно вниз; чуже, поки людина
 *     читає вище, — лічильник на кнопці «нові повідомлення»;
 *   - ДОПИСАНЕ ЗВЕРХУ (старша історія) — `anchorTo: 'end'` віртуалізатора
 *     тримає під пальцем той самий лист, що й до дописування;
 *   - ПЕРЕМІРЯНЕ ВИЩЕ ЕКРАНА — віртуалізатор сам поправляє прокрутку на
 *     різницю між оцінкою й виміром;
 *   - СТРИБОК до листа (закріплене, пошук, цитата) — `scrollToIndex`, навіть
 *     якщо лист зараз не в DOM; далі — плавне доведення й підсвітка.
 *
 * Рядок віртуалізатора — `display: flow-root`: інакше верхній відступ рядка
 * (`mt-3` на зміні мовця) «провалювався» б крізь обгортку, і `offsetHeight`
 * обгортки його не бачив би — рядки налазили б один на одного.
 */
import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { defaultRangeExtractor, useVirtualizer, type Range, type Virtualizer } from '@tanstack/react-virtual';
import { ArrowDown } from 'lucide-react';
import { soundFx } from '../../../utils/messengerSound';
import type { FeedRow } from './feedModel';
import { estimateRowHeight } from './feedModel';
import { DayPill, FoldRow, SilenceLabel, StuckDayPill } from './feedParts';

import { useTranslation } from '../../../i18n/useTranslation';
import { useEdgeStretch } from '../../../feel/useEdgeStretch';
/** Скільки рядків малюється понад видимі з кожного боку. */
export const OVERSCAN = 6;
/** Прилипла пігулка дня живе лише під час гортання: у спокої вона лежала б на бульбашці. */
export const STUCK_IDLE_MS = 1200;
const JUMP_MS = 1200;

export interface VirtualFeedHandle {
  /** Стрибнути до листа за його id; false — такого рядка в стрічці немає.
   * `land` — приземлення на перший непрочитаний: без підсвітки, «вниз» — за фактом. */
  jumpTo: (key: string, opts?: { land?: boolean }) => boolean;
}

interface VirtualFeedProps {
  rows: FeedRow[];
  /** Ключ першого непрочитаного рядка: над ним стоїть роздільник «Нові повідомлення». */
  unreadFromKey?: string | null;
  chatId?: string;
  renderRow: (row: FeedRow) => React.ReactNode;
  onShowFold?: (ids: string[]) => void;
  /** Над листами: тезка, порожній стан. */
  head?: React.ReactNode;
  /** Під листами: хтось друкує, картки «де ти». */
  tail?: React.ReactNode;
  /** Лист, до якого щойно стрибнули, — для підсвітки. */
  onJumped?: (key: string) => void;
}

/**
 * Розмір прокрутника — з позначкою, чи його взагалі викладено.
 *
 * Невикладений прокрутник (jsdom у тестах, колонка під `display: none`) має
 * висоту 0, і віртуалізатор не намалював би жодного рядка — розмова
 * виглядала б порожньою. Тоді стрічка малює ВСІ рядки, як до віртуалізації:
 * без розкладки віртуалізувати нема за чим, а порожнеча була б брехнею.
 */
function scrollerRectObserver(laidOut: { current: boolean }, width: { current: number }) {
  return (
    instance: Virtualizer<HTMLDivElement, HTMLDivElement>,
    cb: (rect: { width: number; height: number }) => void,
  ) => {
    const el = instance.scrollElement;
    if (!el) return undefined;
    const report = () => {
      laidOut.current = el.offsetHeight > 0;
      if (el.clientWidth > 0) width.current = el.clientWidth;
      // Без розкладки висота — підлога вікна: інакше віртуалізатор не рахує
      // діапазону зовсім, і `rangeExtractor` нижче не отримав би слова.
      const floor = typeof window !== 'undefined' ? window.innerHeight || 800 : 800;
      cb({ width: Math.round(el.offsetWidth), height: Math.round(laidOut.current ? el.offsetHeight : floor) });
    };
    report();
    if (typeof ResizeObserver === 'undefined') return () => {};
    const ro = new ResizeObserver(report);
    ro.observe(el);
    return () => ro.disconnect();
  };
}

export const VirtualFeed = forwardRef<VirtualFeedHandle, VirtualFeedProps>(
  ({ rows, chatId, unreadFromKey, renderRow, onShowFold, head, tail, onJumped }, ref) => {
    const { t } = useTranslation();
    const scrollerRef = useRef<HTMLDivElement>(null);
    useEdgeStretch(scrollerRef);
    const contentRef = useRef<HTMLDivElement>(null);
    const listRef = useRef<HTMLDivElement>(null);
    const headRef = useRef<HTMLDivElement>(null);

    const [showScrollBottom, setShowScrollBottom] = useState(false);
    /** Скільки чужих листів прийшло, поки людина читала історію вище. */
    const [newIncoming, setNewIncoming] = useState(0);
    /** Відступ списку від верху прокрутника (тезка, поля колонки). */
    const [scrollMargin, setScrollMargin] = useState(0);
    /** День, чия пігулка зараз «прилипла» до верху. */
    const [stuck, setStuck] = useState<{ label: string; key: string } | null>(null);
    const stuckIdleRef = useRef<ReturnType<typeof setTimeout>>();
    useEffect(() => () => clearTimeout(stuckIdleRef.current), []);

    /** Людина зараз біля низу — тоді нове тягне стрічку за собою. */
    const atBottomRef = useRef(true);
    /** Доки триває стрибок до листа — тримання низу мовчить. */
    const jumpUntilRef = useRef(0);
    const landedRef = useRef(false);
    const landedChatRef = useRef<string | undefined>('\u0000');
    const firstKeyRef = useRef<string | undefined>(undefined);
    const lastKeyRef = useRef<string | undefined>(undefined);
    const seenCountRef = useRef(0);

    const rowsRef = useRef(rows);
    rowsRef.current = rows;
    const laidOutRef = useRef(true);
    // Ширина колонки для оцінки висоти — з виміру прокрутника, а не читанням
    // `clientWidth` на кожну оцінку (їх сотні на один перерахунок).
    const widthRef = useRef(393);
    const [observeRect] = useState(() => scrollerRectObserver(laidOutRef, widthRef));

    const virtualizer = useVirtualizer<HTMLDivElement, HTMLDivElement>({
      count: rows.length,
      getScrollElement: () => scrollerRef.current,
      getItemKey: (i) => rowsRef.current[i]?.key ?? i,
      estimateSize: (i) => {
        const row = rowsRef.current[i];
        return row ? estimateRowHeight(row, widthRef.current) : 60;
      },
      overscan: OVERSCAN,
      scrollMargin,
      observeElementRect: observeRect,
      // До монтування (перший кадр, рендер у рядок) прокрутника ще немає —
      // без початкового розміру стрічка була б порожньою навіть на мить.
      initialRect: { width: 0, height: typeof window !== 'undefined' ? window.innerHeight || 800 : 800 },
      rangeExtractor: (range: Range) =>
        laidOutRef.current ? defaultRangeExtractor(range) : Array.from({ length: range.count }, (_, i) => i),
      // Дописане зверху тримає під пальцем той самий лист; переміряне біля
      // низу лишає низ низом.
      anchorTo: 'end',
    });

    const pinBottom = useCallback((behavior: ScrollBehavior) => {
      const el = scrollerRef.current;
      if (!el) return;
      el.scrollTo({ top: el.scrollHeight, behavior });
      atBottomRef.current = true;
      setShowScrollBottom(false);
      setNewIncoming(0);
    }, []);

    /** Яка пігулка дня зараз мусить бути «прилиплою». */
    const updateStuck = useCallback(() => {
      const el = scrollerRef.current;
      if (!el) return;
      const top = el.scrollTop;
      const items = virtualizer.getVirtualItems();
      const list = rowsRef.current;
      // Перший рядок, що ще видно під верхом.
      const first = items.find((it) => it.end > top + 8);
      if (!first) {
        setStuck((s) => (s ? null : s));
        return;
      }
      // Роздільник цього дня — найближчий вище або сам рядок.
      let i = first.index;
      while (i > 0 && !list[i]?.showDateDivider) i -= 1;
      const owner = list[i];
      const ownerItem = virtualizer.measurementsCache[i];
      const label = owner?.showDateDivider ? owner.dateLabel : '';
      // `sticky top-2` прилипав, щойно верх пігулки ставав вище за 8 px від верху.
      const next = label && ownerItem && ownerItem.start - top < 8 ? { label, key: owner.key } : null;
      setStuck((s) => (s?.key === next?.key && s?.label === next?.label ? s : next));
    }, [virtualizer]);

    const settleBottom = () => {
      const el = scrollerRef.current;
      if (!el) return;
      const { scrollTop, scrollHeight, clientHeight } = el;
      const isScrolledUp = scrollHeight - scrollTop - clientHeight > 150;
      atBottomRef.current = !isScrolledUp;
      setShowScrollBottom(isScrolledUp);
      if (!isScrolledUp) setNewIncoming(0);
    };

    const handleScroll = () => {
      const el = scrollerRef.current;
      if (!el) return;
      updateStuck();
      clearTimeout(stuckIdleRef.current);
      stuckIdleRef.current = setTimeout(() => setStuck(null), STUCK_IDLE_MS);
      // Перші кадри стрибка ще біля низу: якби ми тут повернули atBottom,
      // тримач низу вбив би сам стрибок.
      if (performance.now() < jumpUntilRef.current) return;
      settleBottom();
    };

    // Відступ списку від верху прокрутника: поле колонки згори плюс те, що
    // стоїть над листами (тезка, порожній стан). Міряється спостерігачем
    // розміру, а не на кожен рендер: читання прямокутника після зміни DOM
    // змушувало браузер перекладати сторінку посеред кадру.
    useLayoutEffect(() => {
      const column = contentRef.current;
      const headEl = headRef.current;
      if (!column || !headEl) return undefined;
      const padTop = parseFloat(getComputedStyle(column).paddingTop) || 0;
      const measure = () => {
        const margin = Math.round(padTop + headEl.offsetHeight);
        setScrollMargin((m) => (m === margin ? m : margin));
      };
      measure();
      if (typeof ResizeObserver === 'undefined') return undefined;
      const ro = new ResizeObserver(measure);
      ro.observe(headEl);
      return () => ro.disconnect();
    }, []);

    // Стрічка приїжджає асинхронно, тож приземлення чекає на перші листи.
    // Скидання лічильників живе ТУТ, а не в окремому ефекті: той спрацював би
    // після цього, і перший кадр нової розмови порахував би чужу історію як
    // «нові повідомлення».
    useLayoutEffect(() => {
      if (!scrollerRef.current) return;
      const count = rows.length;
      const first = rows[0]?.key;
      const last = rows[count - 1]?.key;
      const prevFirst = firstKeyRef.current;
      const prevLast = lastKeyRef.current;

      if (landedChatRef.current !== chatId) {
        landedChatRef.current = chatId;
        landedRef.current = false;
        seenCountRef.current = 0;
        firstKeyRef.current = first;
        lastKeyRef.current = last;
        atBottomRef.current = true;
        setNewIncoming(0);
        setShowScrollBottom(false);
      }

      if (!landedRef.current) {
        if (count === 0) return;
        landedRef.current = true;
        seenCountRef.current = count;
        firstKeyRef.current = first;
        lastKeyRef.current = last;
        // Низ — за висотою прокрутника; переміряне після цього тягне назад
        // ResizeObserver колонки і `anchorTo: 'end'`.
        pinBottom('auto');
        return;
      }

      // Історія дописалась ЗВЕРХУ: позицію тримає anchorTo віртуалізатора.
      if (prevFirst && first && first !== prevFirst && last === prevLast) {
        firstKeyRef.current = first;
        seenCountRef.current = count;
        return;
      }
      firstKeyRef.current = first;
      lastKeyRef.current = last;

      if (count <= seenCountRef.current || last === prevLast) {
        seenCountRef.current = count;
        return;
      }

      // Нові листи знизу — ті, що стоять після попереднього останнього.
      const from = prevLast ? rows.findIndex((r) => r.key === prevLast) + 1 : seenCountRef.current;
      const added = rows.slice(Math.max(0, from));
      seenCountRef.current = count;
      if (added.length === 0) return;
      const mine = added.some((r) => r.isSelf);
      if (mine || atBottomRef.current) {
        pinBottom('smooth');
      } else {
        setNewIncoming((n) => n + added.length);
      }
    }, [rows, chatId, pinBottom, virtualizer]);

    // Вміст доростає ВЖЕ ПІСЛЯ приземлення: фото дешифрувалось, рядок
    // переміряно, шрифт домальовано. Ловимо саму висоту колонки — це дешевше
    // і чесніше, ніж вгадувати всі джерела приросту.
    useEffect(() => {
      const content = contentRef.current;
      if (!content || typeof ResizeObserver === 'undefined') return undefined;
      const ro = new ResizeObserver(() => {
        const el = scrollerRef.current;
        if (performance.now() < jumpUntilRef.current) return;
        if (el && atBottomRef.current) el.scrollTop = el.scrollHeight;
      });
      ro.observe(content);
      return () => ro.disconnect();
    }, []);

    useImperativeHandle(
      ref,
      () => ({
        jumpTo: (key: string, opts?: { land?: boolean }) => {
          const index = rowsRef.current.findIndex((r) => r.key === key);
          if (index < 0) return false;
          const land = opts?.land === true;
          // Стрибок до листа означає, що ми більше НЕ внизу: на час перельоту
          // тримання низу мовчить, інакше підсвітка (рамка, приріст висоти)
          // смикнула б scrollTop назад у кінець.
          jumpUntilRef.current = performance.now() + JUMP_MS;
          atBottomRef.current = false;
          if (land) window.setTimeout(settleBottom, JUMP_MS + 50);
          else setShowScrollBottom(true);
          const el = document.getElementById(`message-${key}`);
          if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          } else {
            // Листа немає в DOM — віртуалізатор знає, де він, навіть не
            // намалювавши його. Без анімації: плавний переліт через сотні
            // невиміряних рядків цілиться в оцінку, а не в лист.
            virtualizer.scrollToIndex(index, { align: 'center' });
          }
          if (!land) onJumped?.(key);
          return true;
        },
      }),
      [virtualizer, onJumped],
    );

    const items = virtualizer.getVirtualItems();

    return (
      <div className="relative flex-1 min-h-0 flex flex-col">
        {stuck && <StuckDayPill label={stuck.label} />}
        <div
          ref={scrollerRef}
          onScroll={handleScroll}
          data-testid="messages-scroller"
          className="msg-scroller flex-1 overflow-y-auto"
          style={{ overflowAnchor: 'none' }}
        >
          <div ref={contentRef} className="msg-column max-w-[760px] mx-auto w-full px-2 md:px-6">
            <div ref={headRef}>{head}</div>
            <div
              ref={listRef}
              data-testid="feed-list"
              style={{ height: virtualizer.getTotalSize(), position: 'relative', width: '100%', flexShrink: 0 }}
            >
              {items.map((item) => {
                const row = rows[item.index];
                if (!row) return null;
                return (
                  <div
                    key={item.key}
                    data-index={item.index}
                    ref={virtualizer.measureElement}
                    className="flow-root"
                    style={{
                      position: 'absolute',
                      top: 0,
                      left: 0,
                      width: '100%',
                      transform: `translateY(${item.start - virtualizer.options.scrollMargin}px)`,
                    }}
                  >
                    {unreadFromKey === row.key && (
                      <div
                        data-testid="unread-divider"
                        className="flex items-center gap-3 px-4 py-2 text-footnote text-accent-text"
                      >
                        <span className="h-px flex-1 bg-line" />
                        <span>{t('virtualFeed.unreadDivider')}</span>
                        <span className="h-px flex-1 bg-line" />
                      </div>
                    )}
                    {row.showDateDivider ? (
                      <DayPill label={row.dateLabel} concealed={stuck?.key === row.key} />
                    ) : (
                      row.silence && <SilenceLabel gap={row.silence} />
                    )}
                    {row.fold ? (
                      <FoldRow count={row.fold.count} onShow={() => onShowFold?.(row.fold!.ids)} />
                    ) : (
                      renderRow(row)
                    )}
                  </div>
                );
              })}
            </div>
            {tail}
          </div>
        </div>

        {/* Кнопка «вниз» живе поза прокрутником, але в межах стрічки — інакше
            вона їхала б разом із повідомленнями. */}
        {showScrollBottom &&
          (newIncoming > 0 ? (
            <button
              onClick={() => {
                soundFx.playTap();
                pinBottom('smooth');
              }}
              data-testid="scroll-new-messages"
              className="ph-rise ph-press absolute bottom-3 right-3 w-11 h-11 min-h-0 min-w-0 bg-raised text-ink shadow-e2 rounded-full z-20 flex items-center justify-center"
              title={t('virtualFeed.toNew')}
              aria-label={t('virtualFeed.newMessages', { n: newIncoming })}
            >
              <ArrowDown className="w-5 h-5 shrink-0" strokeWidth={1.75} />
              <span data-testid="scroll-new-count" key={newIncoming} className="ph-land absolute -top-2 inset-x-0 mx-auto w-fit h-5 min-w-5 px-1.5 rounded-full bg-accent text-on-accent text-caption font-semibold tnum flex items-center justify-center ring-2 ring-canvas">
                {newIncoming > 99 ? '99+' : newIncoming}
              </span>
            </button>
          ) : (
            <button
              onClick={() => {
                soundFx.playTap();
                pinBottom('smooth');
              }}
              data-testid="scroll-bottom"
              className="ph-rise ph-press absolute bottom-3 right-3 w-11 h-11 min-h-0 min-w-0 bg-raised text-ink-2 hover:text-ink shadow-e2 rounded-full z-20 flex items-center justify-center"
              title={t('virtualFeed.down')}
              aria-label={t('virtualFeed.down')}
            >
              <ArrowDown className="w-5 h-5" strokeWidth={1.75} />
            </button>
          ))}
      </div>
    );
  },
);

VirtualFeed.displayName = 'VirtualFeed';
