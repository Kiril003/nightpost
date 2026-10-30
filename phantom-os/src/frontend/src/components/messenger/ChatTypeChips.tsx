/**
 * Сегменти над списком (§10.1): Усі · Люди · Групи, а ще Кімнати — коли
 * в якійсь гілці говорять, і Архів — коли в ньому щось лежить. БЕЗ
 * лічильників: «Групи 14» читалось як кількість груп. Лишився тільки
 * «Усі» — сегментів немає.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React from 'react';
import { soundFx } from '../../utils/messengerSound';

export type ChatFilter = 'all' | 'person' | 'group' | 'room' | 'archive';

/** Інший екран просить список показати один рід (`detail` — ChatFilter). */
export const LIST_FILTER_EVENT = 'phantom:list-filter';

export interface ChipSpec {
  key: ChatFilter;
  label: string;
  /** Лишено для сумісності; сегменти чисел не показують. */
  unread: number;
  count?: number;
}

export interface ChatTypeChipsProps {
  chips: ChipSpec[];
  active: ChatFilter;
  onPick: (key: ChatFilter) => void;
}

export const ChatTypeChips: React.FC<ChatTypeChipsProps> = ({ chips, active, onPick }) => {
  const { t } = useTranslation();
  if (chips.length < 2) return null;
  return (
    // Намальовано 36 (§7.9), ціль дотику — 44: тло стоїть усередині вищого ряду.
    <div role="tablist" aria-label={t('chatTypeChips.which')} className="relative h-11 flex gap-0.5 px-1.5">
      <span aria-hidden className="absolute inset-x-0 top-1 bottom-1 rounded-control bg-raised" />
      {chips.map((c) => {
        const on = c.key === active;
        return (
          <button
            key={c.key}
            type="button"
            role="tab"
            aria-selected={on}
            data-chip={c.key}
            onClick={() => {
              soundFx.playTap();
              onPick(c.key);
            }}
            className="relative flex-1 min-w-0 h-11 flex items-center focus-visible:outline-none group"
          >
            <span
              className={`w-full h-8 px-2 rounded-chip truncate flex items-center justify-center text-subhead transition-colors duration-quick ease-standard group-focus-visible:outline group-focus-visible:outline-2 group-focus-visible:outline-accent-line ${
                on ? 'bg-surface text-ink font-semibold shadow-e1' : 'text-ink-2 group-hover:text-ink'
              }`}
            >
              {c.label}
            </span>
          </button>
        );
      })}
    </div>
  );
};

export default ChatTypeChips;
