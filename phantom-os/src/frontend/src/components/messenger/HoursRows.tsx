import React, { useState } from 'react';
import { Clock } from 'lucide-react';
import { FormSheet } from './FormSheet';
import { useWhenFlow } from './when/WhenFlow';
import { WhenRow } from './when/WhenRow';
import { dayKey } from './when/whenModel';

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

export const HoursRows: React.FC<{
  from: string;
  to: string;
  fromLabel: string;
  toLabel: string;
  title?: string;
  testId: string;
  onChange: (from: string, to: string) => void;
}> = ({ from, to, fromLabel, toLabel, title, testId, onChange }) => {
  const [pick, setPick] = useState<'from' | 'to' | null>(null);
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    allowPast: true,
    daily: true,
    onPick: (r) => {
      if (r.kind === 'at') {
        if (pick === 'from') onChange(r.time, to);
        else onChange(from, r.time);
      }
      setPick(null);
      flow.reset();
    },
  });
  const open = (which: 'from' | 'to') => {
    flow.open('time', { date: dayKey(new Date()), time: which === 'from' ? from : to });
    setPick(which);
  };
  return (
    <>
      <div>
        <WhenRow testId={`${testId}-from`} icon={<Clock {...ICO} />} label={fromLabel} value={from} onClick={() => open('from')} />
        <WhenRow testId={`${testId}-to`} icon={<Clock {...ICO} />} label={toLabel} value={to} onClick={() => open('to')} />
      </div>
      {pick && (
        <FormSheet open onClose={() => setPick(null)} onBack={() => setPick(null)} title={[title, pick === 'from' ? fromLabel : toLabel].filter(Boolean).join(' · ')} testId={`${testId}-sheet`} footer={flow.footer}>
          {flow.body}
        </FormSheet>
      )}
    </>
  );
};
