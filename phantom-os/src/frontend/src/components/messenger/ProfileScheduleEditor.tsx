import React from 'react';
import { ChevronLeft } from 'lucide-react';
import {
  templateDefaults,
  useProfiles,
  type ProfileKind,
} from '../../stores/profilesStore';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';
import { HoursRows } from './HoursRows';

function defaultQuietFor(kind: ProfileKind): { from: string; to: string } {
  return templateDefaults(kind).quiet ?? { from: '22:00', to: '07:00' };
}

const SwitchTrack: React.FC<{ on: boolean }> = ({ on }) => (
  <span
    aria-hidden="true"
    data-switch-track
    className={`relative shrink-0 w-[52px] h-8 rounded-full transition-colors duration-instant ease-standard ${
      on ? 'bg-accent' : 'bg-overlay'
    }`}
  >
    <span
      className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${
        on ? 'translate-x-5 bg-on-accent' : 'bg-ink-2'
      }`}
    />
  </span>
);

export interface ProfileScheduleEditorProps {
  profileId: string;
  onBack: () => void;
}

export const ProfileScheduleEditor: React.FC<ProfileScheduleEditorProps> = ({
  profileId,
  onBack,
}) => {
  const { t } = useTranslation();
  const profile = useProfiles((s) => s.profiles.find((p) => p.id === profileId) ?? null);
  const update = useProfiles((s) => s.update);

  if (!profile) return null;

  return (
    <div data-testid="profile-schedule-editor" className="px-4 pb-5 flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="profile-schedule-back"
          aria-label={t('formSheet.back')}
          onClick={() => {
            soundFx.playTap();
            onBack();
          }}
          className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center text-ink"
        >
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
        </button>
        <div className="flex-1 min-w-0 flex flex-col">
          <span className="truncate text-title-2 text-ink">{t('profiles.quietRow')}</span>
          <span className="truncate text-footnote text-ink-2">{profile.name}</span>
        </div>
      </div>

      <div className="rounded-card bg-surface border border-line p-4 flex flex-col gap-3">
        <button
          type="button"
          role="switch"
          data-testid="profile-schedule-quiet"
          aria-checked={Boolean(profile.quiet)}
          onClick={() => {
            soundFx.playTap();
            const nextQuiet = profile.quiet ? null : defaultQuietFor(profile.kind);
            update(profile.id, { quiet: nextQuiet });
          }}
          className="min-h-11 flex items-center justify-between gap-3 text-left"
        >
          <span className="text-headline text-ink">{t('profiles.quiet')}</span>
          <SwitchTrack on={Boolean(profile.quiet)} />
        </button>

        {profile.quiet && (
          <HoursRows
            testId="profile-schedule-quiet"
            from={profile.quiet.from}
            to={profile.quiet.to}
            fromLabel={t('profiles.quietFrom')}
            toLabel={t('profiles.quietTo')}
            title={t('profiles.quiet')}
            onChange={(from, to) => update(profile.id, { quiet: { from, to } })}
          />
        )}
      </div>
    </div>
  );
};
