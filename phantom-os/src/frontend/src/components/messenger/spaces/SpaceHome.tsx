import React, { useMemo, useState } from 'react';
import { BellOff, Bell, ChevronDown, UserPlus } from 'lucide-react';
import type { SpaceChannel } from '@shared/types/space';
import { useTranslation } from '../../../i18n/useTranslation';
import { people as memberWord } from '../GroupProfileSheet';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { liveMembers, myRole, roomsAsRole, roomsOf } from '../../../services/spaces/spaceRooms';
import { postSpaceEvent } from '../../../services/spaces/spaceWire';
import type { SpacePerson } from '../../../services/spaces/createSpace';
import { Avatar } from '../kit/Avatar';
import { SectionLabel, SpaceFrame, SpacePrimary } from './SpaceFrame';
import { SpaceSwitchSheet } from './SpaceSwitchSheet';
import { PeoplePickSheet } from './PeoplePickSheet';
import { KIND_ICON, NOT_YET } from './spaceKinds';
import { useSpace } from './useSpace';

/** Головна простору (sv-home): хто ми, одна дія, розмови всередині за категоріями. */
export const SpaceHome: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t, locale } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [switching, setSwitching] = useState(false);
  const [inviting, setInviting] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const { space, chat, meId } = v;
  const now = Date.now();
  const previewRole = space && nav.previewRoleId ? space.roles.find((r) => r.id === nav.previewRoleId) ?? null : null;
  const groups = useMemo(
    () => (space ? (previewRole ? roomsAsRole(space, previewRole.id, now) : roomsOf(space, meId, now)) : []),
    [space, meId, previewRole?.id], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const members = space ? liveMembers(space) : [];
  const role = space ? (previewRole ?? myRole(space, meId, now)) : null;
  const roomCount = groups.reduce((n, g) => n + g.rooms.length, 0);

  const lastLine = (c: SpaceChannel): string | undefined => {
    const m = [...(chat?.messages ?? [])].reverse().find((x) => x.channelId === c.id && x.text);
    return m ? `${m.senderName}: ${m.text}` : undefined;
  };

  const invite = async (people: SpacePerson[]) => {
    setInviting(false);
    if (!space) return;
    setNote(null);
    try {
      for (const p of people) {
        await useMessengerStore.getState().addGroupMember(chatId, p.id);
        await postSpaceEvent(v.profileId, chatId, { t: 'member.add', memberId: p.nodeId, roleIds: space.defaultRoleId ? [space.defaultRoleId] : [] });
      }
      nav.bump();
    } catch (e) {
      setNote(e instanceof Error && e.message ? e.message : t('space.inviteFailed'));
    }
  };

  const name = space?.name ?? chat?.title ?? '';
  const kindLine = space
    ? [t('space.kind'), `${members.length} ${memberWord(members.length, t, locale)}`, ...(role ? [t('space.youAre', { role: role.name })] : [])].join(' · ')
    : t('space.kind');
  return (
    <SpaceFrame testId="space-home" onBack={nav.pop}>
      {previewRole && (
        <div
          data-testid="space-preview-banner"
          className="mx-4 mb-2 px-3.5 py-2 rounded-control bg-overlay flex items-center justify-between gap-2 text-subhead text-ink"
        >
          <span className="truncate">{t('space.role.viewingAs', { role: previewRole.name })}</span>
          <button
            type="button"
            data-testid="space-preview-exit"
            onClick={() => nav.setPreviewRole(null)}
            className="min-h-0 h-auto font-semibold text-ink"
          >
            {t('space.role.exitView')}
          </button>
        </div>
      )}
      <div className="px-4 pt-1 flex items-center gap-4">
        <Avatar name={name} tone={chatId} radius="rounded-card" className="w-[72px] h-[72px] text-title-1" />
        <div className="min-w-0 flex-1">
          <button
            type="button"
            data-testid="space-switch"
            aria-label={t('space.switch.open')}
            onClick={() => setSwitching(true)}
            className="max-w-full min-h-0 h-auto flex items-center gap-1 text-left text-title-1 text-ink"
          >
            <span className="truncate">{name}</span>
            <ChevronDown className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={2} aria-hidden />
          </button>
          <p data-testid="space-kind" className="text-footnote text-ink-2 truncate">
            {kindLine}
          </p>
        </div>
      </div>
      <div className="px-4 pt-4 flex items-start gap-3">
        {space ? (
          <SpacePrimary testId="space-invite" className="flex-1" onClick={() => setInviting(true)}>
            <UserPlus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            {t('space.invite')}
          </SpacePrimary>
        ) : (
          <span className="flex-1" />
        )}
        <button
          type="button"
          data-testid="space-mute"
          aria-pressed={Boolean(chat?.muted)}
          onClick={() => useMessengerStore.getState().toggleMuteChat(chatId)}
          className="w-[72px] min-h-0 flex flex-col items-center gap-1 text-caption text-ink-2"
        >
          <span className="w-12 h-12 rounded-full bg-overlay text-ink flex items-center justify-center">
            {chat?.muted ? <Bell className="w-5 h-5" strokeWidth={1.75} aria-hidden /> : <BellOff className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
          </span>
          {chat?.muted ? t('space.unmute') : t('space.mute')}
        </button>
      </div>
      {space && (
        <div role="tablist" className="mt-4 px-4 border-b border-line flex items-center gap-6">
          <button
            type="button"
            role="tab"
            aria-selected={true}
            data-testid="space-tab-rooms"
            className="min-h-0 py-2.5 border-b-2 border-ink text-subhead font-semibold text-ink inline-flex items-center gap-1.5"
          >
            <span>{t('space.tab.rooms')}</span>
            <span className="text-ink-2 font-normal tnum">{roomCount}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={false}
            data-testid="space-tab-members"
            onClick={() => nav.push({ s: 'members', chatId })}
            className="min-h-0 py-2.5 border-b-2 border-transparent text-subhead text-ink-2 inline-flex items-center gap-1.5"
          >
            <span>{t('space.tab.members')}</span>
            <span className="text-ink-3 tnum">{members.length}</span>
          </button>
        </div>
      )}
      {note && <p className="px-4 pt-2 text-footnote text-danger">{note}</p>}

      {!space ? (
        <div data-testid="space-state" className="px-4 py-8 flex flex-col items-center text-center">
          <p className="text-callout text-ink-3">
            {v.state === 'loading' ? t('space.loading') : v.state === 'failed' ? t('space.loadFailed') : t('space.none')}
          </p>
          {v.state === 'failed' && (
            <button type="button" data-testid="space-retry" onClick={v.retry} className="mt-2 h-11 px-4 rounded-full bg-raised text-subhead text-ink active:bg-overlay">
              {t('coreChat.retry')}
            </button>
          )}
        </div>
      ) : groups.length === 0 ? (
        <p data-testid="space-rooms-empty" className="px-4 py-8 text-center text-callout text-ink-3">{t('space.rooms.empty')}</p>
      ) : (
        <div className="pb-6" data-testid="space-rooms">
          {groups.map((g) => (
            <section key={g.id ?? 'loose'}>
              <SectionLabel>{g.name ?? t('space.cat.other')}</SectionLabel>
              <ul>
                {g.rooms.map((c) => {
                  const Icon = KIND_ICON[c.type];
                  const later = NOT_YET.includes(c.type);
                  const openable = c.type === 'announce' || c.type === 'chat' || c.type === 'forum';
                  const row = (
                    <>
                      <span className="w-10 h-10 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
                        <Icon className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-headline text-ink">{c.name}</span>
                        <span className="block truncate text-subhead text-ink-2">{lastLine(c) ?? t(`space.type.${c.type}`)}</span>
                      </span>
                    </>
                  );
                  return (
                    <li key={c.id}>
                      {openable ? (
                        <button
                          type="button"
                          data-testid={`space-room-${c.id}`}
                          data-kind={c.type}
                          onClick={() => nav.push({ s: c.type === 'forum' ? 'forum' : 'channel', chatId, channelId: c.id })}
                          className="w-full min-h-[64px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
                        >
                          {row}
                        </button>
                      ) : (
                        <div data-testid={`space-room-${c.id}`} data-kind={c.type} className={`min-h-[64px] px-4 py-2 flex items-center gap-3 ${later ? 'opacity-50' : ''}`}>
                          {row}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>
      )}
      <SpaceSwitchSheet open={switching} current={chatId} profileId={v.profileId} onClose={() => setSwitching(false)} />
      <PeoplePickSheet open={inviting} picked={[]} exclude={members.map((m) => m.id)} onClose={() => setInviting(false)} onDone={invite} />
    </SpaceFrame>
  );
};
