import React from 'react';
import { removalImpact } from '../../services/profileImpact';
import { isBusinessKind } from '../../services/businessFace';
import { useProfiles, type Profile } from '../../stores/profilesStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';

export const ProfileRemoveConfirm: React.FC<{
  profile: Profile;
  onCancel: () => void;
  onDone: () => void;
}> = ({ profile, onCancel, onDone }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const profiles = useProfiles((s) => s.profiles);
  const { inside, orphaned } = removalImpact(chats, profiles, profile.id);
  return (
    <div data-testid="profile-remove-confirm" role="alertdialog" className="px-4 pb-5 flex flex-col gap-3">
      <span className="text-title-2 text-ink">{t('profilesR.remove.title', { name: profile.name })}</span>
      <span className="text-body text-ink-2">{t('profilesR.remove.chats', { n: String(inside) })}</span>
      {orphaned > 0 ? (
        <span data-testid="profile-remove-orphan" className="text-body text-ink">
          {t('profilesR.remove.orphan', { n: String(orphaned) })}
        </span>
      ) : profile.kind === 'personal' ? (
        <span data-testid="profile-remove-keep" className="text-body text-ink-2">{t('profilesR.remove.keepPersonal')}</span>
      ) : profiles.some((p) => p.id !== profile.id && p.kind === 'personal') ? (
        <span data-testid="profile-remove-keep" className="text-body text-ink-2">{t('profilesR.remove.keep')}</span>
      ) : null}
      {isBusinessKind(profile.kind) && (profile.staff.length > 0 || profile.biz.services.length > 0) && (
        <span className="text-body text-ink-2">{t('profilesR.remove.staff')}</span>
      )}
      <div className="grid grid-cols-2 gap-2">
        <button
          type="button"
          data-testid="profile-remove-cancel"
          onClick={() => {
            soundFx.playTap();
            onCancel();
          }}
          className="h-12 min-h-0 rounded-control bg-raised text-headline text-ink"
        >
          {t('profilesR.remove.cancel')}
        </button>
        <button
          type="button"
          data-testid="profile-remove-yes"
          onClick={() => {
            soundFx.playTap();
            useProfiles.getState().remove(profile.id);
            onDone();
          }}
          className="h-12 min-h-0 rounded-control bg-danger text-on-accent text-headline"
        >
          {t('profilesR.remove.confirm')}
        </button>
      </div>
    </div>
  );
};
