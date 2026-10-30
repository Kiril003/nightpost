import React, { useState } from 'react';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { GROUP_RIGHTS, SLOW_STEPS, type Chat, type ChatMember, type GroupRight, type GroupSettings } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { Check, Inbox, SlidersHorizontal, UserCheck } from 'lucide-react';
import { InfoRows, type InfoRowSpec } from './InfoView';
import { GroupInviteLinkRow } from './GroupInviteLinkRow';
import { GroupJoinRequestsList, useGroupRequests } from './GroupJoinRequests';

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75, 'aria-hidden': true } as const;

export const slowWord = (sec: number, t: Translation['t']): string => {
  if (sec <= 0) return t('groupsTg.slowOff');
  if (sec < 60) return t('groupsTg.sec', { n: sec });
  if (sec < 3600) return t('groupsTg.min', { n: Math.round(sec / 60) });
  return t('groupsTg.hour', { n: Math.round(sec / 3600) });
};

export const isChannel = (chat: Pick<Chat, 'groupSettings'>): boolean => chat.groupSettings?.kind === 'channel';

export const SwitchRow: React.FC<{ label: string; on: boolean; disabled?: boolean; onToggle: () => void; testId?: string }> = ({
  label,
  on,
  disabled = false,
  onToggle,
  testId,
}) => (
  <button
    type="button"
    role="switch"
    aria-checked={on}
    aria-label={label}
    data-testid={testId}
    disabled={disabled}
    onClick={() => {
      soundFx.playTap();
      onToggle();
    }}
    className="flex min-h-[48px] w-full items-center gap-3 rounded-control text-left active:bg-raised transition-colors disabled:opacity-50"
  >
    <span className="flex-1 min-w-0 text-callout text-ink">{label}</span>
    <span
      aria-hidden="true"
      className={`relative shrink-0 w-[52px] h-8 rounded-full transition-colors duration-instant ease-standard ${on ? 'bg-ink' : 'bg-overlay'}`}
    >
      <span
        className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${on ? 'translate-x-5 bg-surface' : 'bg-ink-3'}`}
      />
    </span>
  </button>
);

export type SettingsPage = 'root' | 'perms' | 'requests';

/**
 * Налаштування, як у Telegram: запрошення, заявки, «Дозволи учасників» окремою
 * сторінкою (пауза між повідомленнями й історія — лише групі, не каналу).
 * Кожна зміна їде актом `set`.
 */
export const GroupSettingsPanel: React.FC<{
  chat: Chat;
  page?: SettingsPage;
  onPage?: (p: SettingsPage) => void;
  onOpenPerson?: (m: ChatMember) => void;
}> = ({ chat, page, onPage, onOpenPerson }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState<string | null>(null);
  const [own, setOwn] = useState<SettingsPage>('root');
  const q = useGroupRequests(chat);
  const s: GroupSettings | undefined = chat.groupSettings;
  const may = chat.powers?.settings === true;
  if (!s) return null;
  const cur = page ?? own;
  const go = onPage ?? setOwn;
  const channel = isChannel(chat);

  const save = async (patch: Record<string, string>) => {
    setBusy(true);
    setWhy(null);
    try {
      await useMessengerStore.getState().setGroupSettings(chat.id, patch);
    } catch (e) {
      setWhy(e instanceof Error ? e.message : t('groupsTg.settingsFailed'));
    } finally {
      setBusy(false);
    }
  };

  if (cur === 'requests') return <GroupJoinRequestsList chat={chat} q={q} onOpenPerson={onOpenPerson} />;

  if (cur === 'perms' && !channel) {
    return (
      <div className="px-4 pb-4" data-testid="group-settings-perms-page">
        <SwitchRow label={t('groupsTg.history')} on={s.hist} disabled={!may || busy} testId="setting-hist" onToggle={() => void save({ hist: s.hist ? 'off' : 'on' })} />
        <p className="pt-5 pb-1 text-footnote font-semibold text-ink-2">{t('groupsTg.slow')}</p>
        <ul role="radiogroup" aria-label={t('groupsTg.slow')}>
          {SLOW_STEPS.map((sec, i) => {
            const on = s.slow === sec;
            return (
              <li key={sec} className={i > 0 ? 'border-t border-line' : ''}>
                <button
                  type="button"
                  role="radio"
                  aria-checked={on}
                  data-testid={`slow-${sec}`}
                  disabled={!may || busy}
                  onClick={() => {
                    soundFx.playTap();
                    if (!on) void save({ slow: String(sec) });
                  }}
                  className="w-full min-h-[48px] flex items-center gap-3 text-left active:bg-raised disabled:opacity-50"
                >
                  <span className="flex-1 text-callout text-ink">{slowWord(sec, t)}</span>
                  {on && <Check className="w-5 h-5 text-ink" strokeWidth={2} aria-hidden />}
                </button>
              </li>
            );
          })}
        </ul>
        {why && <p className="pt-2 text-callout text-danger">{why}</p>}
      </div>
    );
  }

  const ask = s.ask === true;
  const rows: InfoRowSpec[] = [
    ...(q.may
      ? [
          { key: 'ask', icon: <UserCheck {...ICO} />, label: t('groupsTg.approve'), toggle: { on: ask, onChange: () => void q.toggleAsk() }, testId: 'setting-ask' },
          ...(ask || q.list.length > 0
            ? [{ key: 'requests', icon: <Inbox {...ICO} />, label: t('groupsTg.requestsRow'), count: q.list.length, onClick: () => go('requests'), testId: 'group-settings-requests' }]
            : []),
        ]
      : []),
    ...(!channel && may
      ? [{ key: 'perms', icon: <SlidersHorizontal {...ICO} />, label: t('groupsTg.perms'), aside: s.slow > 0 ? slowWord(s.slow, t) : undefined, onClick: () => go('perms'), testId: 'group-settings-perms' }]
      : []),
  ];

  return (
    <div className="pb-2" data-testid="group-settings">
      <div className="px-4">
        <GroupInviteLinkRow chat={chat} />
      </div>
      <InfoRows rows={rows} />
      {(why || q.note) && <p className="px-4 pt-2 text-callout text-danger">{why || (q.note ? t(q.note) : '')}</p>}
    </div>
  );
};

const RIGHT_WORD: Record<GroupRight, MessageKey> = {
  info: 'groupsTg.right.info',
  pin: 'groupsTg.right.pin',
  ban: 'groupsTg.right.ban',
  invite: 'groupsTg.right.invite',
  admins: 'groupsTg.right.admins',
};

/** Права адміна чи модератора — перемикачі, як у Telegram. Лише власник. */
export const RightsEditor: React.FC<{ chat: Chat; member: ChatMember }> = ({ chat, member }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState<string | null>(null);
  if (!member.mayRights || member.role === 'member' || member.role === 'owner') return null;
  const have = new Set<GroupRight>(member.rights ?? GROUP_RIGHTS);

  const flip = async (r: GroupRight) => {
    const next = new Set(have);
    if (next.has(r)) next.delete(r);
    else next.add(r);
    setBusy(true);
    setWhy(null);
    try {
      await useMessengerStore.getState().setMemberRights(chat.id, member.id, GROUP_RIGHTS.filter((x) => next.has(x)));
    } catch (e) {
      setWhy(e instanceof Error ? e.message : t('groupsTg.rightsFailed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-testid="member-rights">
      <p className="pb-1 text-footnote font-semibold text-ink-3">{t('groupsTg.rights')}</p>
      {GROUP_RIGHTS.map((r) => (
        <SwitchRow key={r} label={t(RIGHT_WORD[r])} on={have.has(r)} disabled={busy} testId={`right-${r}`} onToggle={() => void flip(r)} />
      ))}
      {why && <p className="text-callout text-danger">{why}</p>}
    </div>
  );
};
