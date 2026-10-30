import React, { useEffect, useMemo, useState } from 'react';
import { ChevronRight, X } from 'lucide-react';
import { useProfiles, type Profile } from '../../stores/profilesStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useLocale, useTranslation } from '../../i18n/useTranslation';
import { bridgeMaps, useChatProfiles } from '../../stores/chatProfileStore';
import { pluralKey } from '../chat/tNow';
import { faceChangeCount, resolveFace, withFaceField, type FaceField } from '../../services/profileFace';
import { soundFx } from '../../utils/messengerSound';

const RINGS = ['personal', 'close', 'friends', 'social', 'work'] as const;
const FIELDS: { key: FaceField; label: 'profiles.name' | 'profiles.status' | 'profiles.bio'; max: number }[] = [
  { key: 'name', label: 'profiles.name', max: 60 },
  { key: 'status', label: 'profiles.status', max: 60 },
  { key: 'bio', label: 'profiles.bio', max: 160 },
];

const Tabs: React.FC<{ ring: string; onPick: (r: string) => void }> = ({ ring, onPick }) => {
  const { t } = useTranslation();
  return (
    <div role="tablist" className="flex gap-1 overflow-x-auto no-scrollbar">
      {RINGS.map((r) => (
        <button
          key={r}
          type="button"
          role="tab"
          aria-selected={ring === r}
          data-testid={`face-ring-${r}`}
          onClick={() => {
            soundFx.playTap();
            onPick(r);
          }}
          className={`h-11 min-h-0 px-3 shrink-0 rounded-full text-subhead ${
            ring === r ? 'bg-raised text-ink' : 'text-ink-2'
          }`}
        >
          {t(`profiles.group.${r}`)}
        </button>
      ))}
    </div>
  );
};

export const ProfileFaces: React.FC<{ profile: Profile }> = ({ profile }) => {
  const { t } = useTranslation();
  const locale = useLocale();
  const me = useMessengerStore((s) => s.currentUser);
  const chats = useMessengerStore((s) => s.chats);
  const owners = useChatProfiles((s) => s.map);
  const profiles = useProfiles((s) => s.profiles);
  const seenBy = useMemo(() => {
    const { peerProfile } = bridgeMaps(chats, owners, profiles, me.id);
    return Object.values(peerProfile).filter((id) => id === profile.id).length;
  }, [chats, owners, profiles, me.id, profile.id]);
  const base = { name: me.name ?? '', status: me.status ?? '', bio: me.bio ?? '' };
  const [mode, setMode] = useState<'row' | 'edit' | 'see'>('row');
  const [ring, setRing] = useState<string>('close');
  const [drafts, setDrafts] = useState<Record<FaceField, string>>({ name: '', status: '', bio: '' });

  useEffect(() => {
    const over = profile.faces[ring] ?? {};
    setDrafts({ name: over.name ?? '', status: over.status ?? '', bio: over.bio ?? '' });
  }, [profile.id, ring, mode]);

  const commit = (field: FaceField, value: string) => {
    const faces = withFaceField(profile.faces, ring, field, value);
    useProfiles.getState().update(profile.id, { faces });
  };

  const open = mode !== 'row';
  const header = (
    <button
      type="button"
      data-testid="profile-faces-open"
      aria-expanded={open}
      onClick={() => {
        soundFx.playTap();
        setMode(open ? 'row' : 'edit');
      }}
      className="w-full min-h-11 px-3 rounded-control bg-raised flex items-center justify-between gap-2 text-left"
    >
      <span className="text-body text-ink">{t('profiles.faces')}</span>
      <span className="ml-auto text-subhead text-ink-2 truncate" data-testid="profile-seen-count">
        {seenBy ? t(pluralKey(locale, seenBy, 'profiles.seen'), { count: seenBy }) : t('profiles.seen.none')}
      </span>
      <ChevronRight className={`shrink-0 w-5 h-5 text-ink-2 transition-transform duration-standard ease-standard ${open ? 'rotate-90' : ''}`} strokeWidth={1.75} aria-hidden="true" />
    </button>
  );

  if (mode === 'row') return header;

  if (mode === 'see') {
    const face = resolveFace(base, profile, ring);
    return (
      <div className="flex flex-col gap-3">
      {header}
      <div data-testid="profile-see-as" className="flex flex-col gap-3 f-fade-in">
        <div className="flex items-center justify-between">
          <span className="text-subhead text-ink">{t('profiles.seeAs')}</span>
          <button
            type="button"
            aria-label={t('formSheet.back')}
            onClick={() => setMode('edit')}
            className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink-2"
          >
            <X className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
          </button>
        </div>
        <Tabs ring={ring} onPick={setRing} />
        <div className="rounded-control bg-raised p-4 flex flex-col items-center text-center gap-1">
          <span data-testid="see-as-name" className="text-subhead text-ink">
            {face.name || t('profiles.facePreviewName')}
          </span>
          {face.status && <span data-testid="see-as-status" className="text-footnote text-ink-2">{face.status}</span>}
          {face.bio && <span data-testid="see-as-bio" className="text-footnote text-ink-2">{face.bio}</span>}
        </div>
        <button
          type="button"
          data-testid="see-as-change"
          onClick={() => setMode('edit')}
          className="min-h-11 px-3 rounded-control bg-raised text-body text-ink text-left"
        >
          {t('profiles.changeFace')}
        </button>
      </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
    {header}
    <div data-testid="profile-faces" className="flex flex-col gap-3 f-fade-in">
      <div className="flex items-center justify-end">
        <button
          type="button"
          data-testid="profile-faces-done"
          onClick={() => setMode('row')}
          className="h-11 min-h-0 px-3 rounded-full text-subhead text-ink-2"
        >
          {t('profiles.done')}
        </button>
      </div>
      <Tabs ring={ring} onPick={setRing} />
      {FIELDS.map((f) => (
        <label key={f.key} className="flex flex-col gap-1">
          <span className="text-caption text-ink-2">{t(f.label)}</span>
          <input
            type="text"
            data-testid={`face-${f.key}`}
            maxLength={f.max}
            value={drafts[f.key]}
            placeholder={base[f.key] || t('profiles.faceSame')}
            onChange={(e) => setDrafts((d) => ({ ...d, [f.key]: e.target.value }))}
            onBlur={(e) => commit(f.key, e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                commit(f.key, e.currentTarget.value);
              }
            }}
            className="h-11 px-3 rounded-control bg-raised border border-line text-body text-ink"
          />
        </label>
      ))}
      <span data-testid="face-changes" className="text-caption text-ink-2">
        {faceChangeCount(profile.faces, ring) === 0 ? t('profiles.faceSame') : String(faceChangeCount(profile.faces, ring))}
      </span>
      <button
        type="button"
        data-testid="profile-see-as-open"
        onClick={() => {
          soundFx.playTap();
          setMode('see');
        }}
        className="min-h-11 px-3 rounded-control bg-raised flex items-center justify-between text-left"
      >
        <span className="text-body text-ink">{t('profiles.seeAs')}</span>
        <ChevronRight className="w-5 h-5 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
      </button>
    </div>
    </div>
  );
};
