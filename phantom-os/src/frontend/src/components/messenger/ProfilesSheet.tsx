import React, { useEffect, useMemo, useState } from 'react';
import {
  ChevronLeft,
  ChevronRight,
  Clock,
  Palette,
  Plus,
  SlidersHorizontal,
  Trash2,
} from 'lucide-react';
import { FormSheet } from './FormSheet';
import { BusinessProfile } from './BusinessProfile';
import { ProfileLookEditor, ProfileGlyphIcon } from './ProfileLookEditor';
import { ProfileScheduleEditor } from './ProfileScheduleEditor';
import { HoursRows } from './HoursRows';
import { isBusinessKind } from '../../services/businessFace';
import { readLook, type Accent } from '../../services/appearance';
import { unreadByProfile } from '../../services/profileScope';
import { useChatProfiles } from '../../stores/chatProfileStore';
import { PASS_GROUPS, passedGroups, togglePass, type PassGroup } from '../../services/profilePass';
import {
  NEW_PROFILE_KINDS,
  HIDDEN_PROFILE_KINDS,
  PROFILE_KINDS,
  glyphForProfile,
  kindLabel,
  PROFILE_ALERTS,
  registerProfilesOpener,
  templateDefaults,
  useProfiles,
  type Profile,
  type ProfileKind,
} from '../../stores/profilesStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useLocale, useTranslation } from '../../i18n/useTranslation';
import { useUIStore } from '../../stores/uiStore';
import type { MessageKey } from '../../i18n/messages/uk';
import { ProfileFaces } from './ProfileFaces';
import { directionOf } from './directions';
import { ProfileSwitchDiff, faceNameOf, kitWord } from './ProfileSwitchDiff';
import { ProfileSwitchCard } from './ProfileSwitchCard';
import { ProfileTemplatePreview } from './ProfileTemplatePreview';
import { ProfileRemoveConfirm } from './ProfileRemoveConfirm';
import { soundFx } from '../../utils/messengerSound';

const OTHER_PROFILE_KINDS: readonly ProfileKind[] = PROFILE_KINDS.filter(
  (k) => !NEW_PROFILE_KINDS.includes(k) && !HIDDEN_PROFILE_KINDS.includes(k),
);

function defaultQuietFor(kind: ProfileKind): { from: string; to: string } {
  return templateDefaults(kind).quiet ?? { from: '22:00', to: '07:00' };
}

const Toggle: React.FC<{ on: boolean }> = ({ on }) => (
  <span
    aria-hidden="true"
    data-switch-track
    className={`relative shrink-0 w-[52px] h-8 rounded-full transition-colors duration-instant ease-standard ${on ? 'bg-accent' : 'bg-overlay'}`}
  >
    <span
      className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${on ? 'translate-x-5 bg-on-accent' : 'bg-ink-2'}`}
    />
  </span>
);

export const ProfilesHost: React.FC<{ showTrigger?: boolean }> = ({ showTrigger = true }) => {
  const { t } = useTranslation();
  const locale = useLocale();
  const profiles = useProfiles((s) => s.profiles);
  const activeId = useProfiles((s) => s.activeId);
  const setActive = useProfiles((s) => s.setActive);
  const addFromTemplate = useProfiles((s) => s.addFromTemplate);
    const chats = useMessengerStore((s) => s.chats);
  const currentUserName = useMessengerStore((s) => s.currentUser.name);

  const [open, setOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [businessId, setBusinessId] = useState<string | null>(null);
  const [lookId, setLookId] = useState<string | null>(null);
  const [scheduleId, setScheduleId] = useState<string | null>(null);
  const [previewKind, setPreviewKind] = useState<ProfileKind | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [targetId, setTargetId] = useState<string | null>(null);
  const [nameDraft, setNameDraft] = useState('');

  useEffect(() => registerProfilesOpener(() => setOpen(true)), []);

  const active = profiles.find((p) => p.id === activeId) ?? profiles[0];
  const orderedProfiles = useMemo(() => {
    const ranked = [...profiles].sort((a, b) => b.uses - a.uses || a.createdAt - b.createdAt);
    return active ? [active, ...ranked.filter((p) => p.id !== active.id)] : ranked;
  }, [profiles, active]);

  const editingProfile = editingId ? (profiles.find((p) => p.id === editingId) ?? null) : null;
  const target = targetId && targetId !== activeId ? (profiles.find((p) => p.id === targetId) ?? null) : null;
  const globalAccent: Accent = readLook().accent;

  useEffect(() => {
    if (editingProfile) {
      setNameDraft(editingProfile.name);
    }
  }, [editingProfile?.id]);

  const alertsNative =
    typeof (globalThis as { PhantomBridge?: { profileAlerts?: unknown } }).PhantomBridge?.profileAlerts === 'function';
  const chatOwners = useChatProfiles((s) => s.map);
  const unreadCounts = useMemo(() => unreadByProfile(chats, chatOwners, profiles), [chats, chatOwners, profiles]);
  const unreadForProfile = (p: Profile): number => unreadCounts[p.id] ?? 0;

  const commitName = (raw: string) => {
    if (!editingProfile) return;
    const trimmed = raw.trim();
    if (!trimmed) {
      setNameDraft(editingProfile.name);
      return;
    }
    if (trimmed !== editingProfile.name) {
      useProfiles.getState().rename(editingProfile.id, trimmed);
    }
    setNameDraft(trimmed);
  };

  const closeAll = () => {
    setOpen(false);
    setAdding(false);
    setEditingId(null);
    setBusinessId(null);
    setLookId(null);
    setScheduleId(null);
    setPreviewKind(null);
    setRemovingId(null);
    setTargetId(null);
  };

  const activeAccent: Accent = active?.look?.accent ?? globalAccent;
  const scheduleSummary = active?.quiet
    ? `${active.quiet.from}–${active.quiet.to}`
    : '';

  return (
    <>
      {showTrigger && active && (
        <button
          type="button"
          data-testid="me-profiles-open"
          onClick={() => {
            soundFx.playTap();
            setOpen(true);
          }}
          className="touch-floor-inline mt-2 h-8 px-3 rounded-full border border-line-strong text-subhead text-ink hover:bg-overlay/60 active:bg-overlay"
        >
          {active.name}
        </button>
      )}
      {open && (
        <FormSheet
          open
          onClose={closeAll}
          title={t('profiles.title')}
          testId="profiles-sheet"
        >
          {removingId && profiles.some((p) => p.id === removingId) ? (
            <ProfileRemoveConfirm
              profile={profiles.find((p) => p.id === removingId)!}
              onCancel={() => setRemovingId(null)}
              onDone={() => {
                setRemovingId(null);
                setEditingId(null);
              }}
            />
          ) : previewKind ? (
            <ProfileTemplatePreview
              kind={previewKind}
              onBack={() => setPreviewKind(null)}
              onCreate={(name) => {
                const created = addFromTemplate(previewKind, name);
                setActive(created.id);
                useUIStore.getState().toast({ kind: 'success', message: t('profiles.created', { name: created.name }) });
                setPreviewKind(null);
                setAdding(false);
                setLookId(created.id);
              }}
            />
          ) : businessId ? (
            <BusinessProfile profileId={businessId} onBack={() => setBusinessId(null)} />
          ) : lookId ? (
            <ProfileLookEditor
              profileId={lookId}
              onBack={() => setLookId(null)}
              onOpenSettings={() => {
                const id = lookId;
                setLookId(null);
                setEditingId(id);
              }}
            />
          ) : scheduleId ? (
            <ProfileScheduleEditor
              profileId={scheduleId}
              onBack={() => setScheduleId(null)}
            />
          ) : editingProfile ? (
            <div data-testid="profile-settings" className="px-4 pb-4 flex flex-col gap-3">
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  data-testid="profile-settings-back"
                  aria-label={t('formSheet.back')}
                  onClick={() => {
                    soundFx.playTap();
                    setEditingId(null);
                  }}
                  className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center text-ink"
                >
                  <ChevronLeft className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
                </button>
                <span className="flex-1 truncate text-subhead text-ink">{t('profiles.settings')}</span>
                {profiles.length > 1 && (
                  <button
                    type="button"
                    data-testid={`profile-remove-${editingProfile.id}`}
                    aria-label={t('profiles.remove')}
                    onClick={() => {
                      soundFx.playTap();
                      setRemovingId(editingProfile.id);
                    }}
                    className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink-3 hover:text-danger"
                  >
                    <Trash2 className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
                  </button>
                )}
              </div>

              {isBusinessKind(editingProfile.kind) && (
                <button
                  type="button"
                  data-testid="profile-business"
                  onClick={() => {
                    soundFx.playTap();
                    setBusinessId(editingProfile.id);
                  }}
                  className="min-h-12 px-3 rounded-control bg-raised flex items-center justify-between gap-2 text-left"
                >
                  <span className="text-body text-ink">{t('business.open')}</span>
                  <ChevronRight className="w-4 h-4 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
                </button>
              )}

              <label className="flex flex-col gap-1">
                <span className="text-caption text-ink-2">{t('profiles.name')}</span>
                <input
                  type="text"
                  data-testid="profile-name"
                  aria-label={t('profiles.name')}
                  maxLength={40}
                  value={nameDraft}
                  onChange={(e) => setNameDraft(e.target.value)}
                  onBlur={(e) => commitName(e.currentTarget.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      commitName(e.currentTarget.value);
                    }
                  }}
                  className="h-11 px-3 rounded-control bg-raised border border-line text-body text-ink"
                />
              </label>

              <ProfileFaces profile={editingProfile} />

              <div className="flex flex-col gap-1">
                <span className="text-caption text-ink-2">{t('profiles.pass')}</span>
                <div className="flex flex-wrap gap-2">
                  {PASS_GROUPS.map((g: PassGroup) => {
                    const pressed = passedGroups(editingProfile.passGroups).includes(g);
                    return (
                      <button
                        key={g}
                        type="button"
                        data-testid={`profile-pass-${g}`}
                        aria-pressed={pressed}
                        onClick={() => {
                          soundFx.playTap();
                          useProfiles.getState().update(editingProfile.id, {
                            passGroups: togglePass(editingProfile.passGroups, g),
                          });
                        }}
                        className={`h-11 min-h-0 px-3 rounded-control border text-subhead ${
                          pressed
                            ? 'bg-raised border-line-strong text-ink'
                            : 'bg-transparent border-line text-ink-2'
                        }`}
                      >
                        {t(`profiles.group.${g}`)}
                      </button>
                    );
                  })}
                </div>
              </div>

              {alertsNative && (
                <div className="flex flex-col gap-1">
                  <span id="profile-alert-label" className="text-caption text-ink-2">{t('profiles.alert')}</span>
                  <div role="radiogroup" aria-labelledby="profile-alert-label" className="flex flex-wrap gap-2">
                    {PROFILE_ALERTS.map((a) => {
                      const on = editingProfile.alert === a;
                      return (
                        <button
                          key={a}
                          type="button"
                          role="radio"
                          data-testid={`profile-alert-${a}`}
                          aria-checked={on}
                          onClick={() => {
                            soundFx.playTap();
                            useProfiles.getState().update(editingProfile.id, { alert: a });
                          }}
                          className={`h-11 min-h-0 px-3 rounded-control border text-subhead ${
                            on ? 'bg-raised border-line-strong text-ink' : 'bg-transparent border-line text-ink-2'
                          }`}
                        >
                          {t(`profiles.alert.${a}`)}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              <div className="flex flex-col gap-2">
                <button
                  type="button"
                  role="switch"
                  data-testid="profile-quiet"
                  aria-checked={Boolean(editingProfile.quiet)}
                  onClick={() => {
                    soundFx.playTap();
                    const nextQuiet = editingProfile.quiet
                      ? null
                      : defaultQuietFor(editingProfile.kind);
                    useProfiles.getState().update(editingProfile.id, { quiet: nextQuiet });
                  }}
                  className="min-h-11 px-3 rounded-control bg-raised flex items-center justify-between gap-2 text-left"
                >
                  <span className="text-body text-ink">{t('profiles.quiet')}</span>
                  <Toggle on={Boolean(editingProfile.quiet)} />
                </button>

                {editingProfile.quiet && (
                  <HoursRows
                    testId="profile-quiet"
                    from={editingProfile.quiet.from}
                    to={editingProfile.quiet.to}
                    fromLabel={t('profiles.quietFrom')}
                    toLabel={t('profiles.quietTo')}
                    title={t('profiles.quiet')}
                    onChange={(from, to) => useProfiles.getState().update(editingProfile.id, { quiet: { from, to } })}
                  />
                )}
              </div>

            </div>
          ) : adding ? (
            <div data-testid="profile-templates" className="px-4 pb-5 flex flex-col gap-3">
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  data-testid="profile-templates-back"
                  aria-label={t('formSheet.back')}
                  onClick={() => {
                    soundFx.playTap();
                    setAdding(false);
                  }}
                  className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center text-ink"
                >
                  <ChevronLeft className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
                </button>
                <span className="text-title-2 text-ink">{t('profiles.newProfile')}</span>
              </div>

              <div className="grid grid-cols-2 gap-3">
                {NEW_PROFILE_KINDS.map((kind: ProfileKind) => {
                  const defaults = templateDefaults(kind);
                  const tplAccent: Accent = defaults.look?.accent ?? globalAccent;
                  const tplGlyph = glyphForProfile({ kind, look: defaults.look });
                  const tplName = kindLabel(kind);
                  const tplLine = t(
                    (kind === 'personal' || kind === 'author' || kind === 'unnamed'
                      ? `profilesR.tpl.${kind}`
                      : `profiles.tplLine.${kind}`) as MessageKey,
                  );
                  return (
                    <button
                      key={kind}
                      type="button"
                      data-testid={`profile-template-${kind}`}
                      data-accent={tplAccent}
                      onClick={() => {
                        soundFx.playTap();
                        setPreviewKind(kind);
                      }}
                      className="rounded-card border border-line bg-surface text-ink p-0 overflow-hidden text-left flex flex-col"
                    >
                      <span className="h-14 w-full bg-accent-soft flex items-end px-3.5 box-border">
                        <span className="w-11 h-11 rounded-control bg-surface text-accent-text border border-line flex items-center justify-center -mb-4">
                          <ProfileGlyphIcon glyph={tplGlyph} className="w-[22px] h-[22px]" />
                        </span>
                      </span>
                      <span className="pt-6 px-3.5 pb-3.5 flex flex-col gap-1">
                        <span className="text-headline font-semibold text-ink">{tplName}</span>
                        <span className="text-footnote text-ink-2">{tplLine}</span>
                      </span>
                    </button>
                  );
                })}
              </div>

              <div className="flex flex-col gap-2 pt-1">
                <span className="text-subhead font-semibold text-ink-2 px-1">
                  {t('profiles.moreTemplates')}
                </span>
                <div className="flex flex-wrap gap-2">
                  {OTHER_PROFILE_KINDS.map((kind: ProfileKind) => (
                    <button
                      key={kind}
                      type="button"
                      data-testid={`profile-template-${kind}`}
                      onClick={() => {
                        soundFx.playTap();
                        setPreviewKind(kind);
                      }}
                      className="h-11 min-h-0 px-3 rounded-full bg-raised text-subhead text-ink active:bg-overlay"
                    >
                      {kindLabel(kind)}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="pb-4 flex flex-col">
              <div className="flex flex-col gap-3 px-4 pb-1">
                {orderedProfiles.map((p) => {
                  const isCurrent = p.id === activeId;
                  const selected = target ? p.id === target.id : isCurrent;
                  const cardAccent: Accent = p.look?.accent ?? globalAccent;
                  const unread = isCurrent ? 0 : unreadForProfile(p);
                  const stateLine = isCurrent
                    ? t('profiles.now')
                    : unread > 0
                      ? t('profiles.unreadCount', { count: unread })
                      : '';
                  return (
                    <div
                      key={p.id}
                      data-testid={`profile-row-${p.id}`}
                      data-accent={cardAccent}
                      className="relative"
                    >
                      <button
                        type="button"
                        data-testid={`profile-select-${p.id}`}
                        aria-pressed={p.id === target?.id}
                        onClick={() => {
                          soundFx.playTap();
                          setTargetId(isCurrent ? null : p.id);
                        }}
                        className={`w-full rounded-card border-2 bg-canvas text-ink p-4 flex flex-col items-start text-left box-border ${
                          selected ? 'border-accent-line' : 'border-line'
                        }`}
                      >
                        <ProfileSwitchCard
                          profile={p}
                          from={active ?? null}
                          faceName={faceNameOf(p, currentUserName || p.name)}
                          expanded={selected}
                          stateLine={stateLine}
                        />
                      </button>
                      <button
                        type="button"
                        data-testid={`profile-settings-${p.id}`}
                        aria-label={t('profiles.settings')}
                        onClick={() => {
                          soundFx.playTap();
                          setEditingId(p.id);
                        }}
                        className="touch-floor-inline absolute top-3 right-3 w-8 h-8 rounded-full bg-surface text-ink-2 flex items-center justify-center"
                      >
                        <SlidersHorizontal className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
                      </button>
                    </div>
                  );
                })}
              </div>

              {active && directionOf(active.kind).shownQuick.length > 0 && (
                <div data-testid="profile-quick" className="flex gap-2 flex-wrap px-4 pt-3">
                  {directionOf(active.kind).shownQuick.map((q) => (
                    <button
                      key={q}
                      type="button"
                      data-testid={`profile-quick-${q}`}
                      onClick={() => {
                        soundFx.playTap();
                        setBusinessId(active.id);
                      }}
                      className="h-11 min-h-0 px-4 rounded-control bg-overlay text-subhead text-ink"
                    >
                      {kitWord(locale, `dir.quick.${q}`)}
                    </button>
                  ))}
                </div>
              )}

              <div className="flex gap-2 overflow-x-auto no-scrollbar px-4 pt-3 items-center">
                <button
                  type="button"
                  data-testid="profile-add-open"
                  aria-label={t('profiles.newProfile')}
                  onClick={() => {
                    soundFx.playTap();
                    setAdding(true);
                  }}
                  className="shrink-0 w-11 h-11 min-h-0 min-w-0 rounded-full text-ink-2 flex items-center justify-center"
                >
                  <Plus className="w-5 h-5" strokeWidth={1.9} aria-hidden="true" />
                </button>
                {NEW_PROFILE_KINDS.slice(0, 7)
                  .filter((k) => k !== 'personal' || !profiles.some((p) => p.kind === 'personal'))
                  .map((kind) => (
                    <button
                      key={kind}
                      type="button"
                      data-testid={`profile-chip-${kind}`}
                      onClick={() => {
                        soundFx.playTap();
                        setPreviewKind(kind);
                      }}
                      className="shrink-0 h-11 min-h-0 px-3 rounded-full border border-line text-subhead text-ink active:bg-overlay"
                    >
                      {kindLabel(kind)}
                    </button>
                  ))}
              </div>

              {active && target ? (
                <ProfileSwitchDiff to={target} onDone={() => setTargetId(null)} />
              ) : active && (
                <div className="flex flex-col px-2 pt-3">
                  <button
                    type="button"
                    data-testid="profile-look-open"
                    onClick={() => {
                      soundFx.playTap();
                      setLookId(active.id);
                    }}
                    className="min-h-14 px-3 rounded-control flex items-center gap-3.5 text-left hover:bg-overlay/60 active:bg-overlay"
                  >
                    <Palette className="w-[22px] h-[22px] text-ink-2 shrink-0" strokeWidth={1.75} aria-hidden="true" />
                    <span className="flex-1 truncate text-headline font-semibold text-ink">
                      {t('profiles.lookRow', { name: active.name })}
                    </span>
                    <span
                      data-accent={activeAccent}
                      className="w-3 h-3 rounded-full bg-accent shrink-0"
                    />
                  </button>
                  <button
                    type="button"
                    data-testid="profile-schedule-open"
                    onClick={() => {
                      soundFx.playTap();
                      setScheduleId(active.id);
                    }}
                    className="min-h-14 px-3 rounded-control flex items-center gap-3.5 text-left hover:bg-overlay/60 active:bg-overlay"
                  >
                    <Clock className="w-[22px] h-[22px] text-ink-2 shrink-0" strokeWidth={1.75} aria-hidden="true" />
                    <span className="flex-1 truncate text-headline font-semibold text-ink">
                      {t('profiles.quietRow')}
                    </span>
                    <span className="text-subhead text-ink-2 tnum">{scheduleSummary}</span>
                  </button>
                </div>
              )}
            </div>
          )}
        </FormSheet>
      )}
    </>
  );
};
