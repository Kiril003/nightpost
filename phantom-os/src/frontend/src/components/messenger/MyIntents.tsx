/**
 * Профіль › «Без інтернету» › «Мої прохання до ПК» — що мій ПК робить від мого
 * імені, коли в телефона немає інтернету (розд. 07 §2.3, §8.3).
 *
 * У списку «Я» — один рядок: останнє прохання і де воно зараз; дотик веде на
 * екран «Без інтернету», де лежать останні п'ять. Порожньо або не телефон —
 * немає рядка.
 */
import React, { useEffect } from 'react';
import { ListChecks } from 'lucide-react';
import { answerText, intentLine } from '../../services/intentWords';
import { useIntentsStore } from '../../stores/intentsStore';
import type { IntentRow } from '../../services/intentsApi';
import { MeRow } from './MeRow';
import { useTranslation } from '../../i18n/useTranslation';

const SHOWN = 5;

export const latestIntents = (rows: IntentRow[] | null): IntentRow[] =>
  rows ? [...rows].sort((a, b) => b.created_ms - a.created_ms).slice(0, SHOWN) : [];

export const MyIntents: React.FC<{ onOpen: () => void }> = ({ onOpen }) => {
  const rows = useIntentsStore((s) => s.rows);
  const refresh = useIntentsStore((s) => s.refresh);
  const { t } = useTranslation();

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const [last] = latestIntents(rows);
  if (!last) return null;
  return <MeRow icon={ListChecks} title={t('myIntents.title')} meta={`${last.title} · ${intentLine(last)}`} onClick={onOpen} testId="me-intents" />;
};

/** Останні прохання повністю — на екрані «Без інтернету». */
export const IntentsList: React.FC = () => {
  const rows = useIntentsStore((s) => s.rows);
  const shown = latestIntents(rows);
  if (shown.length === 0) return null;
  return (
    <ul data-testid="intents-list" className="mx-4 rounded-card bg-raised overflow-hidden">
      {shown.map((r, i) => (
        <li key={r.id}>
          {i > 0 && <div aria-hidden="true" className="ml-4 h-px bg-line" />}
          <div className="min-h-14 py-2 px-4 flex flex-col justify-center">
            <span className="block text-body text-ink break-words">{r.title}</span>
            <span className="block text-caption text-ink-3">{intentLine(r)}</span>
            {r.answer && <span className="block text-caption text-ink-2 break-words">{answerText(r.answer)}</span>}
          </div>
        </li>
      ))}
    </ul>
  );
};
