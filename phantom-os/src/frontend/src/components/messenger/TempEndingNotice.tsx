import { useEffect, useState } from 'react';
import { tempEndingSoon, useProfiles } from '../../stores/profilesStore';
import { useTranslation } from '../../i18n/useTranslation';
import { clockLabel } from '../../utils/clockFormat';
import { soundFx } from '../../utils/messengerSound';

/** Година до кінця «На час»: одна дія — продовжити на добу. */
export function TempEndingNotice() {
  const { t } = useTranslation();
  const profiles = useProfiles((s) => s.profiles);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);
  const ending = tempEndingSoon(profiles, now);
  if (!ending || ending.expiresAt === null) return null;
  return (
    <div role="status" data-testid="temp-ending" className="min-h-12 pl-4 pr-2 flex items-center gap-3">
      <span className="flex-1 min-w-0 truncate text-callout text-ink-2">
        {t('profiles.tempEnding', { name: ending.name, time: clockLabel(new Date(ending.expiresAt)) })}
      </span>
      <button
        type="button"
        data-testid="temp-extend"
        onClick={() => {
          soundFx.playTap();
          useProfiles.getState().extendTemp(ending.id);
        }}
        className="h-12 min-h-0 px-4 shrink-0 rounded-full bg-accent text-on-accent text-callout font-medium hover:bg-accent-hover active:bg-accent-press"
      >
        {t('profiles.tempExtend')}
      </button>
    </div>
  );
}
