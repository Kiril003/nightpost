import React, { useMemo, useState } from 'react';
import { Check, ChevronDown, X } from 'lucide-react';
import {
  HANDLE_RE,
  STAFF_ROLES,
  cleanHandle,
  useProfiles,
  type StaffRole,
} from '../../stores/profilesStore';
import { useLocale, useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { ownerOf, useChatProfiles } from '../../stores/chatProfileStore';
import { staffNode } from '../../services/staffPass';
import { chatKind } from './chatListFormat';
import { pluralKey } from '../chat/tNow';
import type { MessageKey } from '../../i18n/messages/uk';
import { soundFx } from '../../utils/messengerSound';

export const BusinessStaff: React.FC<{ profileId: string }> = ({ profileId }) => {
  const { t } = useTranslation();
  const locale = useLocale();
  const staff = useProfiles((s) => s.profiles.find((p) => p.id === profileId)?.staff ?? []);
  const profiles = useProfiles((s) => s.profiles);
  const chats = useMessengerStore((s) => s.chats);
  const map = useChatProfiles((s) => s.map);
  const [openHandle, setOpenHandle] = useState<string | null>(null);
  const bizChats = useMemo(() => {
    const team = new Set(staff.map((m) => m.handle));
    return chats.filter(
      (c) => chatKind(c) === 'person' && !(c.handle && team.has(c.handle)) && ownerOf(c.id, map, profiles) === profileId,
    );
  }, [chats, map, profiles, profileId, staff]);
  const handlerOf = (chatId: string) => staff.find((m) => m.threads.includes(chatId))?.handle;
  const [handle, setHandle] = useState('');
  const [role, setRole] = useState<StaffRole>('staff');
  const [err, setErr] = useState<'bad' | 'dup' | null>(null);
  const roleLabel = (r: StaffRole) => t(`profilesR.staff.${r}` as MessageKey);

  const invite = () => {
    soundFx.playTap();
    if (!HANDLE_RE.test(cleanHandle(handle))) {
      setErr('bad');
      return;
    }
    const res = useProfiles.getState().addStaff(profileId, handle, role);
    if (res === 'ok') {
      setHandle('');
      setErr(null);
    } else setErr(res);
  };

  return (
    <div data-testid="business-staff" className="flex flex-col pt-2">
      {staff.length === 0 && (
        <div data-testid="business-staff-none" className="px-4 pt-3 pb-1 text-caption text-ink-3">
          {t('profilesR.staff.none')}
        </div>
      )}
      {staff.map((m) => {
        const linked = !!staffNode(m.handle, chats);
        const mine = m.threads.filter((id) => bizChats.some((c) => c.id === id)).length;
        const meta = !linked
          ? t('staff.assign.noContact')
          : mine
            ? t(pluralKey(locale, mine, 'staff.work.threads') as MessageKey, { count: mine })
            : t('staff.assign.none');
        const expanded = openHandle === m.handle;
        return (
        <React.Fragment key={m.handle}>
        <div data-testid={`staff-${m.handle}`} className="min-h-12 px-4 py-1 flex items-center gap-2">
          <button
            type="button"
            data-testid={`staff-open-${m.handle}`}
            aria-expanded={expanded}
            onClick={() => {
              soundFx.playTap();
              setOpenHandle(expanded ? null : m.handle);
            }}
            className="flex-1 min-w-0 min-h-11 flex items-center gap-1 text-left"
          >
            <span className="flex-1 min-w-0">
              <span className="block truncate text-body text-ink">@{m.handle}</span>
              <span data-testid={`staff-meta-${m.handle}`} className="block truncate text-caption text-ink-3">{meta}</span>
            </span>
            <ChevronDown
              className={`w-4 h-4 shrink-0 text-ink-3 ${expanded ? 'rotate-180' : ''}`}
              strokeWidth={1.75}
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            data-testid={`staff-role-${m.handle}`}
            onClick={() => {
              soundFx.playTap();
              useProfiles
                .getState()
                .setStaffRole(profileId, m.handle, m.role === 'admin' ? 'staff' : 'admin');
            }}
            className="h-11 min-h-0 px-3 rounded-control bg-overlay text-subhead text-ink"
          >
            {roleLabel(m.role)}
          </button>
          <button
            type="button"
            data-testid={`staff-revoke-${m.handle}`}
            aria-label={t('profilesR.staff.revoke')}
            onClick={() => {
              soundFx.playTap();
              useProfiles.getState().revokeStaff(profileId, m.handle);
            }}
            className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink-3"
          >
            <X className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
          </button>
        </div>
        {expanded && (
          <div data-testid={`staff-assign-${m.handle}`} className="pl-8 pr-4 pb-2 flex flex-col">
            {bizChats.length === 0 && (
              <span className="py-2 text-caption text-ink-3">{t('staff.assign.empty')}</span>
            )}
            {bizChats.map((c) => {
              const by = handlerOf(c.id);
              const on = by === m.handle;
              return (
                <button
                  key={c.id}
                  type="button"
                  data-testid={`staff-assign-${m.handle}-${c.id}`}
                  aria-pressed={on}
                  onClick={() => {
                    soundFx.playTap();
                    useProfiles.getState().assignThread(profileId, c.id, on ? null : m.handle);
                  }}
                  className="min-h-12 flex items-center gap-3 text-left"
                >
                  <span className="flex-1 min-w-0">
                    <span className="block truncate text-body text-ink">{c.title}</span>
                    {by && !on && (
                      <span className="block truncate text-caption text-ink-3">{t('staff.assign.by', { handle: by })}</span>
                    )}
                  </span>
                  <span
                    className={`w-6 h-6 shrink-0 rounded-full border flex items-center justify-center ${
                      on ? 'bg-ink border-ink text-canvas' : 'border-line-strong'
                    }`}
                  >
                    {on && <Check className="w-4 h-4" strokeWidth={2} aria-hidden="true" />}
                  </span>
                </button>
              );
            })}
          </div>
        )}
        </React.Fragment>
        );
      })}
      <div className="px-4 pt-2 pb-3 flex flex-col gap-2">
        <div className="flex gap-2">
          <input
            type="text"
            data-testid="staff-handle"
            aria-label={t('profilesR.staff.handle')}
            placeholder="@"
            maxLength={33}
            value={handle}
            onChange={(e) => {
              setHandle(e.target.value);
              setErr(null);
            }}
            className="flex-1 min-w-0 h-11 px-3 rounded-control bg-canvas border border-line text-body text-ink"
          />
          {STAFF_ROLES.map((r) => (
            <button
              key={r}
              type="button"
              data-testid={`staff-pick-${r}`}
              aria-pressed={role === r}
              onClick={() => {
                soundFx.playTap();
                setRole(r);
              }}
              className={`h-11 min-h-0 px-3 rounded-control border text-subhead ${
                role === r ? 'bg-overlay border-line-strong text-ink' : 'border-line text-ink-2'
              }`}
            >
              {roleLabel(r)}
            </button>
          ))}
        </div>
        {err && (
          <span data-testid="staff-error" className="text-caption text-danger">
            {t(err === 'bad' ? 'profilesR.staff.bad' : 'profilesR.staff.dup')}
          </span>
        )}
        <button
          type="button"
          data-testid="staff-invite"
          onClick={invite}
          className="h-11 min-h-0 rounded-control bg-overlay text-subhead text-ink"
        >
          {t('profilesR.staff.invite')}
        </button>
        <span data-testid="staff-scope" className="text-caption text-ink-3">
          {t('profilesR.staff.scope')}
        </span>
      </div>
    </div>
  );
};
