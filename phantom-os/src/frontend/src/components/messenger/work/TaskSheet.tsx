import React, { useMemo, useState } from 'react';
import { Link2 } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { Avatar } from '../kit/Avatar';
import { sendTask } from '../../../services/workSend';
import { isWorkGroup } from '../../../services/workEvents';
import type { Chat } from '../../../types/messenger';
import { useWhenFlow } from '../when/WhenFlow';
import { WhenRow } from '../when/WhenRow';
import { dayKey, dayLabel, hhmm, slotMs, toSlot } from '../when/whenModel';

/** «Лист → завдання» і «+ Завдання»: назва, виконавець, строк, посилання на лист. */
export const TaskSheet: React.FC<{ chat: Chat; srcMsgId?: string; onClose: () => void }> = ({ chat, srcMsgId, onClose }) => {
  const { t, locale } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const src = srcMsgId ? chat.messages.find((m) => m.id === srcMsgId) : undefined;
  const people = useMemo(() => {
    const list = (chat.members ?? []).map((m) => ({ id: m.id, name: m.name, avatar: m.avatar }));
    return list.some((p) => p.id === me.id) ? list : [{ id: me.id, name: me.name, avatar: me.avatar }, ...list];
  }, [chat.members, me]);
  const [title, setTitle] = useState((src?.text ?? '').replace(/\s+/g, ' ').trim().slice(0, 120));
  const [to, setTo] = useState(me.id);
  // Строк — спільні вікна «Коли» (msg-x): швидкі рядки від «зараз», місяць, колеса; без системних полів.
  const [due, setDue] = useState<number | null>(null);
  const [picking, setPicking] = useState(false);
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    onPick: (r) => {
      if (r.kind === 'at') {
        const ms = slotMs(r);
        if (ms !== null) setDue(ms);
      }
      setPicking(false);
      flow.reset();
    },
  });
  const dueWord = (ms: number) => {
    const d = new Date(ms);
    return `${dayLabel(dayKey(d), new Date(), locale, { today: t('when.today'), tomorrow: t('when.tomorrow') })}, ${hhmm(d)}`;
  };

  const who = people.find((p) => p.id === to);
  const ok = title.trim().length > 0 && !!who && isWorkGroup(chat) && me.id !== 'self';

  if (picking) {
    return (
      <FormSheet open onClose={onClose} onBack={flow.back ?? (() => setPicking(false))} title={flow.win === 'quick' ? t('work.due') : flow.title} testId="work-task-sheet" footer={flow.footer}>
        <div data-work-due-flow>{flow.win === 'quick' ? flow.quick : flow.body}</div>
      </FormSheet>
    );
  }

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('work.task')}
      testId="work-task-sheet"
      footer={
        <PrimaryButton
          disabled={!ok}
          onClick={() => {
            if (!ok || !who) return;
            sendTask(chat.id, { title, to: who.id, toName: who.name, due, src: src?.id });
            onClose();
          }}
        >
          {t('work.create')}
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-4 flex flex-col gap-4">
        {src && (
          <div data-work-src className="flex items-center gap-2 text-footnote text-ink-3">
            <Link2 className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden />
            <span className="truncate">{t('work.linked')} · {src.senderName} · {src.timestamp}</span>
          </div>
        )}
        <label className="block">
          <span className="block text-footnote text-ink-2 mb-1">{t('work.titleLabel')}</span>
          <input
            value={title}
            maxLength={160}
            onChange={(e) => setTitle(e.target.value)}
            aria-label={t('work.titleLabel')}
            className="w-full h-12 px-3 rounded-control bg-raised text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
          />
        </label>
        <div>
          <span className="block text-footnote text-ink-2 mb-2">{t('work.assignee')}</span>
          <div className="flex gap-3 overflow-x-auto no-scrollbar" role="radiogroup" aria-label={t('work.assignee')}>
            {people.map((p) => (
              <button
                key={p.id}
                type="button"
                role="radio"
                aria-checked={to === p.id}
                onClick={() => setTo(p.id)}
                className="shrink-0 min-h-0 min-w-0 flex flex-col items-center gap-1 w-16"
              >
                <span className={`rounded-full p-0.5 ${to === p.id ? 'border-2 border-ink' : 'border-2 border-transparent'}`}>
                  <Avatar src={p.avatar} name={p.name} tone={p.id} className="w-12 h-12" />
                </span>
                <span className={`text-caption truncate max-w-full ${to === p.id ? 'text-ink font-semibold' : 'text-ink-2'}`}>{p.id === me.id ? t('work.me') : p.name}</span>
              </button>
            ))}
          </div>
        </div>
        <WhenRow
          testId="work-due"
          label={t('work.due')}
          value={due !== null ? dueWord(due) : t('work.noDue')}
          empty={due === null}
          onClick={() => {
            flow.reset(due !== null ? toSlot(new Date(due)) : {});
            setPicking(true);
          }}
          onClear={due !== null ? () => setDue(null) : undefined}
          clearLabel={t('work.noDue')}
        />
      </div>
    </FormSheet>
  );
};
