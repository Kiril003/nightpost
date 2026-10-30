/**
 * Під питанням у розмові з PHANTOM, коли в PHANTOM немає дороги в інтернет:
 * питання стало Наміром до мого ПК (розд. 07). Тут — якою смугою воно пішло,
 * де воно зараз, і відповідь, щойно вона приїде.
 */
import React, { useEffect } from 'react';
import { Waypoints } from 'lucide-react';
import { answerText, intentLine } from '../../services/intentWords';
import { useIntentsStore } from '../../stores/intentsStore';

export const IntentTrail: React.FC<{ intentId: string }> = ({ intentId }) => {
  const rows = useIntentsStore((s) => s.rows);
  const refresh = useIntentsStore((s) => s.refresh);
  const row = rows?.find((r) => r.id === intentId) ?? null;

  useEffect(() => {
    if (rows === null) void refresh();
  }, [rows, refresh]);

  if (!row) return null;
  const answer = row.answer ? answerText(row.answer) : '';

  return (
    <div data-testid="intent-trail" className="mt-1.5 flex flex-col gap-1.5">
      <span className="inline-flex items-center gap-1.5 text-caption text-ink-3">
        <Waypoints className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden="true" />
        <span data-testid="intent-line">{intentLine(row)}</span>
      </span>
      {answer && (
        <p data-testid="intent-answer" className="rounded-control bg-raised px-3 py-2 text-callout text-ink break-words">
          {answer}
        </p>
      )}
    </div>
  );
};
