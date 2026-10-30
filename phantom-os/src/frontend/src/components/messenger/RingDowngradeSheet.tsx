import React, { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import type { MessageKey } from '../../i18n/messages/uk';
import { useTranslation } from '../../i18n/useTranslation';
import { apply, type RingPeer, type RingRow, type RingRowKey } from '../../services/ringDowngrade';
import { DoorRow, FormSheet, PrimaryButton } from './FormSheet';

export interface RingDowngradeSheetProps {
  peer: RingPeer;
  rows: RingRow[];
  open: boolean;
  onClose: () => void;
}

const ROW_LABEL: Record<RingRowKey, MessageKey> = {
  where: 'chats7.where',
  moments: 'chats7.moments',
  pin: 'chats7.pin',
  quiet: 'chats7.quiet',
};

export const RingDowngradeSheet: React.FC<RingDowngradeSheetProps> = ({
  peer,
  rows,
  open,
  onClose,
}) => {
  const { t } = useTranslation();
  const [visibleRows, setVisibleRows] = useState<RingRow[]>(rows);
  const [selected, setSelected] = useState<RingRowKey[]>(() => rows.map((r) => r.key));
  const [failed, setFailed] = useState<Partial<Record<RingRowKey, boolean>>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setVisibleRows(rows);
    setSelected(rows.map((r) => r.key));
    setFailed({});
    setBusy(false);
  }, [rows]);

  if (!open || visibleRows.length === 0) return null;

  const activeSelected = visibleRows.map((r) => r.key).filter((k) => selected.includes(k));

  const toggleRow = (key: RingRowKey) => {
    if (busy) return;
    setFailed((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
    setSelected((prev) => (prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]));
  };

  const handleApply = async () => {
    if (busy || activeSelected.length === 0) return;
    setBusy(true);
    try {
      const res = await apply(peer, activeSelected);
      const remaining = visibleRows.filter((r) => res[r.key] === 'failed');
      if (remaining.length === 0) {
        onClose();
        return;
      }
      const nextFailed: Partial<Record<RingRowKey, boolean>> = {};
      for (const r of remaining) {
        nextFailed[r.key] = true;
      }
      setFailed(nextFailed);
      setVisibleRows(remaining);
      setSelected(remaining.map((r) => r.key));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      title={t('chats7.title')}
      testId="ring-downgrade-sheet"
      footer={
        <div className="flex flex-col gap-2">
          <PrimaryButton
            onClick={() => void handleApply()}
            disabled={busy || activeSelected.length === 0}
          >
            {t('chats7.apply')}
          </PrimaryButton>
          <button
            type="button"
            onClick={onClose}
            className="w-full h-12 px-4 rounded-control bg-surface border border-line text-body font-medium text-ink transition-colors duration-fast"
          >
            {t('chats7.skip')}
          </button>
        </div>
      }
    >
      <div className="pb-2">
        {visibleRows.map((row) => {
          const isSelected = selected.includes(row.key);
          const isFailed = Boolean(failed[row.key]);
          return (
            <DoorRow
              key={row.key}
              label={t(ROW_LABEL[row.key])}
              onClick={() => toggleRow(row.key)}
              trailing={
                isFailed ? (
                  <span className="text-footnote text-ink-3">{t('chats7.failed')}</span>
                ) : isSelected ? (
                  <Check className="w-5 h-5 shrink-0 text-ink" strokeWidth={1.75} />
                ) : undefined
              }
            />
          );
        })}
      </div>
    </FormSheet>
  );
};
