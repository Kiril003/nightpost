import React, { useEffect, useState } from 'react';
import { Store } from 'lucide-react';
import { useStaffInbox } from '../../stores/staffInboxStore';
import { useLocale, useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { pluralKey } from '../chat/tNow';
import { MeGroup, MeRow } from './MeRow';
import { FormSheet } from './FormSheet';
import { soundFx } from '../../utils/messengerSound';

export const StaffWork: React.FC = () => {
  const { t } = useTranslation();
  const locale = useLocale();
  const passes = useStaffInbox((s) => s.passes);
  const [openFrom, setOpenFrom] = useState<string | null>(null);
  const open = passes.find((p) => p.from === openFrom);

  useEffect(() => {
    const load = useStaffInbox.getState().load;
    void load();
    const onVisible = () => {
      if (document.visibilityState === 'visible') void load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, []);

  if (!passes.length) return null;
  const time = (at: number) =>
    at > 0 ? new Date(at).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' }) : '';

  return (
    <>
      <MeGroup title={t('staff.work.title')} testId="staff-work">
        {passes.map((p) => (
          <MeRow
            key={p.from}
            icon={Store}
            testId={`staff-work-${p.from}`}
            title={p.business}
            meta={t(pluralKey(locale, p.threads.length, 'staff.work.threads') as MessageKey, { count: p.threads.length })}
            onClick={() => {
              soundFx.playTap();
              setOpenFrom(p.from);
            }}
          />
        ))}
      </MeGroup>
      {open && (
        <FormSheet open onClose={() => setOpenFrom(null)} title={open.business} testId="staff-work-sheet" tall>
          <div className="flex flex-col gap-4 px-4 pb-4">
            {open.threads.map((th) => (
              <section key={th.id} data-testid={`staff-thread-${th.id}`} className="flex flex-col gap-1.5">
                <h3 className="px-1 text-headline text-ink">{th.client || t('staff.work.client')}</h3>
                {th.lines.map((l, i) => (
                  <div
                    key={i}
                    className={`max-w-[85%] rounded-[16px] px-3 py-2 text-body ${
                      l.mine ? 'self-end bg-overlay text-ink' : 'self-start bg-raised text-ink'
                    }`}
                  >
                    <span className="whitespace-pre-wrap break-words">{l.text}</span>
                    {time(l.at) && <span className="ml-2 text-caption text-ink-3 tabular-nums">{time(l.at)}</span>}
                  </div>
                ))}
              </section>
            ))}
          </div>
        </FormSheet>
      )}
    </>
  );
};
