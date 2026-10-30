import type { ReactNode } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';

export interface RailItem {
  key: string;
  icon: ReactNode;
  /** Доступна назва — повна, з поточним значенням. */
  label: string;
  /** Видимий підпис під іконкою; коротший за `label`. */
  short: string;
  active?: boolean;
  /**
   * Скільки записів стоїть за кнопкою. `null` — величина не рахується
   * (вигляд, інструмент). `0` — кнопка вмикає порожнечу, і мусить це
   * показати, а не світитись так само, як робоча.
   */
  count?: number | null;
  onClick: () => void;
}

/**
 * Вертикальна рейка мапи.
 *
 * Раніше все — сім шарів, три види, п'ять панелей — стояло в ОДНІЙ колонці
 * на п'ятнадцять кнопок із власним скролом і підписами на 7.5 пікселя. На
 * семидюймовому тачі це стовпчик загадок, який ще й не вміщався. Тепер
 * рейка знає лише одну роль, а ролей дві: ліворуч — що намальовано на мапі,
 * праворуч — що з нею робити.
 */
export function MapRail({ items, ariaLabel }: { items: RailItem[]; ariaLabel: string }) {
  const { t } = useTranslation();
  return (
    <div
      role="group"
      aria-label={ariaLabel}
      className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex w-[62px] flex-col items-center rounded-[20px] px-1 py-2"
    >
      {items.map((item) => {
        const empty = item.count === 0;
        return (
          <button
            key={item.key}
            type="button"
            onClick={item.onClick}
            aria-label={empty ? t('map.rail.empty', { label: item.label }) : item.label}
            aria-pressed={item.active}
            title={empty ? t('map.rail.empty', { label: item.label }) : item.label}
            className={`relative flex min-h-[46px] w-[54px] flex-col items-center justify-center gap-[2px] rounded-[14px] transition-all active:scale-90 ${
              item.active && !empty
                ? 'border border-[color:var(--ph-line)] bg-[color:var(--ph-raised)] text-[color:var(--ph-text)]'
                : empty
                  ? 'text-[color:var(--ink-muted)] opacity-45'
                  : 'text-[color:var(--ph-text-2)] hover:bg-[color:var(--ph-raised)]'
            }`}
          >
            {item.icon}
            {/* Пристрій тач — hover не існує, тож `title` не з'явиться ніколи. */}
            <span className="max-w-[52px] truncate text-[9px] font-semibold leading-[10px]">
              {item.short}
            </span>
            {typeof item.count === 'number' && item.count > 0 && (
              <span className="absolute right-1 top-1 rounded-full bg-[color:var(--ph-text)] px-1 text-[8px] font-bold leading-[12px] text-[color:var(--ph-surface)]">
                {item.count > 99 ? '99+' : item.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
