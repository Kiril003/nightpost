import React from 'react';
import {
  BookOpen,
  Briefcase,
  Check,
  ChevronLeft,
  Heart,
  Home,
  Plane,
  Send,
  SlidersHorizontal,
  Store,
} from 'lucide-react';
import {
  ACCENTS,
  WALLPAPERS,
  effectiveLook,
  readLook,
  wallpaperUrl,
  type Accent,
  type BuiltinWallpaper,
} from '../../services/appearance';
import {
  PROFILE_GLYPHS,
  glyphForProfile,
  profileToneIndex,
  useProfiles,
  type ProfileGlyph,
} from '../../stores/profilesStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { avatarLetters } from './kit/Avatar';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { soundFx } from '../../utils/messengerSound';

const GLYPH_ICONS: Record<ProfileGlyph, typeof Home> = { home: Home, work: Briefcase, shop: Store, book: BookOpen, heart: Heart, plane: Plane };

export const ProfileGlyphIcon: React.FC<{
  glyph: ProfileGlyph;
  className?: string;
}> = ({ glyph, className = 'w-4 h-4' }) => {
  const props = { className, strokeWidth: 1.75, 'aria-hidden': true as const };
  const Icon = GLYPH_ICONS[glyph] ?? Home;
  return <Icon {...props} />;
};

export interface ProfileLookEditorProps {
  profileId: string;
  onBack: () => void;
  onOpenSettings?: () => void;
}

export const ProfileLookEditor: React.FC<ProfileLookEditorProps> = ({
  profileId,
  onBack,
  onOpenSettings,
}) => {
  const { t } = useTranslation();
  const profiles = useProfiles((s) => s.profiles);
  const setProfileLook = useProfiles((s) => s.setProfileLook);
  const currentUserName = useMessengerStore((s) => s.currentUser.name);

  const profile = profiles.find((p) => p.id === profileId) ?? null;
  if (!profile) return null;

  const baseLook = readLook();
  const eff = effectiveLook(baseLook, profile);
  const activeAccent: Accent = eff.accent;
  const activeWallpaper = profile.look?.wallpaper;
  const activeGlyph: ProfileGlyph = glyphForProfile(profile);
  const tone = profileToneIndex(profiles, profile.id);
  const faceName =
    profile.faces[profile.kind]?.name || profile.ownName || currentUserName || profile.name;
  const previewWallUrl = wallpaperUrl(eff.wallpaper);

  const wallItems: ReadonlyArray<{
    key: string;
    value: BuiltinWallpaper | 'none' | undefined;
    label: string;
    url: string | null;
  }> = [
    {
      key: 'default',
      value: undefined,
      label: t('look.default'),
      url: wallpaperUrl(baseLook.wallpaper),
    },
    ...WALLPAPERS.map((id) => ({
      key: id,
      value: id as BuiltinWallpaper,
      label: t(`look.wp.${id}` as MessageKey),
      url: wallpaperUrl({ kind: 'builtin', id }),
    })),
    {
      key: 'none',
      value: 'none' as const,
      label: t('look.none'),
      url: null,
    },
  ];

  return (
    <div data-testid="profile-look-editor" className="px-4 pb-5 flex flex-col gap-5">
      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="profile-look-back"
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
          <span className="truncate text-title-2 text-ink">{t('profiles.lookTitle')}</span>
          <span className="truncate text-footnote text-ink-2">{profile.name}</span>
        </div>
        {onOpenSettings && (
          <button
            type="button"
            data-testid={`profile-settings-${profile.id}`}
            aria-label={t('profiles.settings')}
            onClick={() => {
              soundFx.playTap();
              onOpenSettings();
            }}
            className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center text-ink-2 hover:text-ink"
          >
            <SlidersHorizontal className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
          </button>
        )}
      </div>

      <label className="flex flex-col gap-1">
        <span className="text-caption text-ink-2">{t('profiles.ownName')}</span>
        <input
          type="text"
          data-testid="profile-own-name"
          maxLength={40}
          value={profile.ownName}
          placeholder={currentUserName}
          onChange={(e) => useProfiles.getState().update(profile.id, { ownName: e.target.value })}
          className="h-11 px-3 rounded-control bg-raised text-body text-ink"
        />
        <span className="text-caption text-ink-3">{t('profiles.nickShared')}</span>
      </label>

      <div
        data-testid="profile-look-preview"
        data-accent={activeAccent}
        data-wallpaper={eff.wallpaper.kind === 'builtin' ? eff.wallpaper.id : 'none'}
        className="rounded-card border border-line overflow-hidden shrink-0"
      >
        <div
          data-testid="profile-look-preview-cover"
          className="h-[52px] bg-accent-soft flex items-center gap-2.5 px-3"
        >
          <span className="w-9 h-9 rounded-full border-2 border-accent-line p-0.5 bg-canvas flex items-center justify-center shrink-0">
            <span
              className="w-full h-full rounded-full flex items-center justify-center text-caption font-semibold"
              style={{
                backgroundColor: `var(--ph-person-${tone})`,
                color: `var(--ph-person-${tone}-ink)`,
              }}
            >
              {avatarLetters(faceName)}
            </span>
          </span>
          <span className="truncate text-subhead font-semibold text-ink">{faceName}</span>
        </div>
        <div
          className="h-28 bg-wall px-3 py-2.5 flex flex-col justify-end gap-1.5 bg-repeat"
          style={
            previewWallUrl
              ? { backgroundImage: `url("${previewWallUrl}")`, backgroundSize: '160px 160px' }
              : undefined
          }
        >
          <div className="self-start px-3 py-1.5 rounded-control rounded-bl-chip bg-surface text-footnote text-ink">
            {t('profiles.previewOther')}
          </div>
          <div className="flex justify-end items-center gap-1.5">
            <div className="px-3 py-1.5 rounded-control rounded-br-chip bg-self text-footnote text-on-self">
              {t('profiles.previewSelf')}
            </div>
            <span
              data-testid="profile-look-preview-send"
              className="w-7 h-7 rounded-full bg-accent text-on-accent flex items-center justify-center shrink-0"
            >
              <Send className="w-3.5 h-3.5" strokeWidth={2} aria-hidden="true" />
            </span>
          </div>
        </div>
      </div>

      <div className="flex flex-col gap-2.5">
        <span className="text-subhead font-semibold text-ink-2">{t('profiles.lookColor')}</span>
        <div className="flex justify-between gap-2">
          {ACCENTS.map((acc) => {
            const selected = activeAccent === acc;
            return (
              <button
                key={acc}
                type="button"
                data-testid={`profile-swatch-${acc}`}
                data-accent={acc}
                aria-label={t(`profiles.accent.${acc}` as MessageKey)}
                aria-pressed={selected}
                onClick={() => {
                  soundFx.playTap();
                  setProfileLook(profile.id, { accent: acc });
                }}
                className={`w-12 h-12 min-h-0 min-w-0 rounded-full border-2 p-[3px] box-border flex items-center justify-center ${
                  selected ? 'border-accent-line' : 'border-transparent'
                }`}
              >
                <span className="w-full h-full rounded-full bg-accent text-on-accent flex items-center justify-center">
                  {selected && <Check className="w-[18px] h-[18px]" strokeWidth={2.5} aria-hidden="true" />}
                </span>
              </button>
            );
          })}
        </div>
        <span className="text-footnote text-ink-2">
          {t(`profiles.accent.${activeAccent}` as MessageKey)}
        </span>
      </div>

      <div className="flex flex-col gap-2.5">
        <span className="text-subhead font-semibold text-ink-2">{t('profiles.lookWallpaper')}</span>
        <div className="grid grid-cols-3 gap-2.5">
          {wallItems.map((w) => {
            const selected = activeWallpaper === w.value;
            return (
              <button
                key={w.key}
                type="button"
                data-testid={`profile-wall-${w.key}`}
                aria-pressed={selected}
                onClick={() => {
                  soundFx.playTap();
                  setProfileLook(profile.id, { wallpaper: w.value });
                }}
                className="flex flex-col items-stretch gap-1.5 text-left"
              >
                <span
                  className={`block w-full h-16 rounded-control border-2 bg-wall bg-repeat ${
                    selected ? 'border-accent-line' : 'border-line'
                  }`}
                  style={
                    w.url
                      ? { backgroundImage: `url("${w.url}")`, backgroundSize: '128px 128px' }
                      : undefined
                  }
                />
                <span
                  className={`text-footnote text-center w-full truncate ${
                    selected ? 'font-semibold text-ink' : 'text-ink-2'
                  }`}
                >
                  {w.label}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex flex-col gap-2.5">
        <span className="text-subhead font-semibold text-ink-2">{t('profiles.lookGlyph')}</span>
        <div className="flex justify-between gap-2">
          {PROFILE_GLYPHS.map((g) => {
            const selected = activeGlyph === g;
            return (
              <button
                key={g}
                type="button"
                data-testid={`profile-glyph-${g}`}
                aria-label={t(`profiles.glyph.${g}` as MessageKey)}
                aria-pressed={selected}
                onClick={() => {
                  soundFx.playTap();
                  setProfileLook(profile.id, { glyph: g });
                }}
                className={`w-12 h-12 min-h-0 min-w-0 rounded-control flex items-center justify-center ${
                  selected ? 'bg-accent-soft text-accent-text' : 'bg-raised text-ink-2'
                }`}
              >
                <ProfileGlyphIcon glyph={g} className="w-[22px] h-[22px]" />
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};
