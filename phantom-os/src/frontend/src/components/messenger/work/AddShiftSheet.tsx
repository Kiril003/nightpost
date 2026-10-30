import React, { useState } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { sendShift } from '../../../services/workSend';
import type { Chat } from '../../../types/messenger';
import { useWhenFlow } from '../when/WhenFlow';
import { WhenRow } from '../when/WhenRow';
import { dayLabel } from '../when/whenModel';

const FIELD = 'w-full h-12 px-3 rounded-control bg-raised text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line';

export const AddShiftSheet: React.FC<{ chat: Chat; day: string; onClose: () => void }> = ({ chat, day, onClose }) => {
  const { t, locale } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const people = chat.members?.some((m) => m.id === me.id) ? chat.members : [{ id: me.id, name: me.name, avatar: '' }, ...(chat.members ?? [])];
  const [who, setWho] = useState('');
  const [date, setDate] = useState(day);
  const [from, setFrom] = useState('09:00');
  const [to, setTo] = useState('17:00');
  const [role, setRole] = useState('');
  const ok = /^\d{4}-\d{2}-\d{2}$/.test(date) && !!from && !!to && from !== to;
  // День і години — спільні вікна «Коли» (msg-x), без системних полів; минулі дні тижня лишаються доступні.
  const [picking, setPicking] = useState<'start' | 'end' | null>(null);
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    allowPast: true,
    onPick: (r) => {
      if (r.kind === 'at') {
        if (picking === 'end') setTo(r.time);
        else {
          setDate(r.date);
          setFrom(r.time);
        }
      }
      setPicking(null);
      flow.reset();
    },
  });
  const words = { today: t('when.today'), tomorrow: t('when.tomorrow') };
  if (picking) {
    return (
      <FormSheet open onClose={onClose} onBack={flow.back ?? (() => setPicking(null))} title={flow.title} testId="work-add-shift" footer={flow.footer}>
        <div data-shift-when-flow>{flow.body}</div>
      </FormSheet>
    );
  }
  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('work.addShift')}
      testId="work-add-shift"
      footer={
        <PrimaryButton
          disabled={!ok}
          onClick={() => {
            const p = people?.find((x) => x.id === who);
            sendShift(chat.id, { who, day: date, from, to, role: role.trim() }, p?.name ?? '');
            onClose();
          }}
        >
          {t('work.create')}
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-4 flex flex-col gap-3">
        <label className="block">
          <span className="block text-footnote text-ink-2 mb-1">{t('work.who')}</span>
          <select value={who} onChange={(e) => setWho(e.target.value)} aria-label={t('work.who')} className={FIELD}>
            <option value="">{t('work.openShift')}</option>
            {people?.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
        </label>
        <WhenRow
          testId="shift-start"
          label={t('work.from')}
          value={`${dayLabel(date, new Date(), locale, words)}, ${from}`}
          onClick={() => {
            flow.open('date', { date, time: from });
            setPicking('start');
          }}
        />
        <WhenRow
          testId="shift-end"
          label={t('work.to')}
          value={to}
          onClick={() => {
            flow.open('time', { date, time: to });
            setPicking('end');
          }}
        />
        <label className="block">
          <span className="block text-footnote text-ink-2 mb-1">{t('work.role')}</span>
          <input value={role} maxLength={40} onChange={(e) => setRole(e.target.value)} aria-label={t('work.role')} className={FIELD} />
        </label>
      </div>
    </FormSheet>
  );
};
