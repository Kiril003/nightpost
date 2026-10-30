import React, { useEffect, useRef, useState } from 'react';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import type { LucideIcon } from 'lucide-react';
import { Crown, ShieldCheck, Gavel, Loader2, Check } from 'lucide-react';
import type { Chat, ChatMember, GroupRole } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { Avatar } from './Avatar';
import { stripMetadata } from '../../services/stripMetadata';
import { RightsEditor } from './GroupSettingsPanel';

/**
 * Щаблі гілки — поверхня над дротом, що їх СПРАВДІ застосовує.
 *
 * До 16.09 бейджі «Власник»/«Адмін» тут уже стояли, і комміт 98a90397 їх
 * прибрав як театр: `loadGroupMembers` хардкодив кожному `'member'`, бо на
 * дроті ролей не існувало. Тепер вони існують (`PeerGroupRoles`), і кожна
 * кнопка нижче показується лише тоді, коли вузол сказав, що вона спрацює:
 * `member.mayRemove` / `mayGrant` / `mayOwn` — поімённі відповіді того самого
 * судді, який відмовить приймачеві. Кнопки «про всяк випадок» тут немає
 * жодної, і саме це відрізняє цей файл від того, що прибрали.
 */

const ROLE_WORD: Record<GroupRole, MessageKey> = {
  owner: 'groupRoles.roleOwner',
  admin: 'groupRoles.roleAdmin',
  mod: 'groupRoles.roleMod',
  member: 'groupRoles.roleMember',
};

/** Що саме цей щабель МОЖЕ — одним рядком, без обіцянок. */
const ROLE_CAN: Record<GroupRole, MessageKey> = {
  owner: 'groupRoles.canOwner',
  admin: 'groupRoles.canAdmin',
  mod: 'groupRoles.canMod',
  member: 'groupRoles.canMember',
};

/** Роль словом під імʼям у складі: «власник», «модератор». Учасник — без слова. */
export const roleLine = (role: GroupRole, t: Translation['t']): string | null => (role === 'member' ? null : t(ROLE_WORD[role]).toLocaleLowerCase());

/** `member` без значка навмисно: це не титул, це всі. */
const ROLE_ICON: Record<GroupRole, LucideIcon | null> = {
  owner: Crown,
  admin: ShieldCheck,
  mod: Gavel,
  member: null,
};

/** Щабель учасника. Звичайного учасника не позначаємо: це не титул, це всі. */
export const RoleBadge: React.FC<{ role: GroupRole }> = ({ role }) => {
  const { t } = useTranslation();
  if (role === 'member') return null;
  const Icon = ROLE_ICON[role];
  return (
    <span
      title={t(ROLE_CAN[role])}
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-raised text-caption shrink-0 ${role === 'owner' ? 'text-ink' : 'text-ink-2'}`}
    >
      {Icon && <Icon className="w-3 h-3" strokeWidth={1.75} aria-hidden="true" />}
      {t(ROLE_WORD[role])}
    </span>
  );
};

export { ROLE_WORD, ROLE_CAN };

type Busy = null | 'grant' | 'remove' | 'own';

/**
 * Дії над однією людиною в гілці.
 *
 * Кожна кнопка тут стоїть за прапорцем із вузла. Відмова показується ТЕКСТОМ
 * причини («це може лише власник гілки»), бо вузол відповідає 403
 * `no_right_<причина>`, а не порожнім «не вдалось».
 */
export const MemberActions: React.FC<{
  chat: Chat;
  member: ChatMember;
  onDone?: () => void;
}> = ({ chat, member, onDone }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState<Busy>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [confirmOwn, setConfirmOwn] = useState(false);

  const run = async (kind: Exclude<Busy, null>, fn: () => Promise<void>) => {
    soundFx.playTap();
    setBusy(kind);
    setRefused(null);
    try {
      await fn();
      onDone?.();
    } catch (e) {
      setRefused(e instanceof Error ? e.message : t('groupRoles.failed'));
    } finally {
      setBusy(null);
    }
  };

  const store = () => useMessengerStore.getState();

  // Наступний щабель угору й униз — рівно ті, які вузол дозволить. Список
  // складається з того, що він сказав, а не з того, що ми вважаємо логічним:
  // адмін не робить адміна, і пункту «зробити адміном» у нього немає.
  const ladder: GroupRole[] = ['member', 'mod', 'admin'];
  const canGrant = member.mayGrant === true;
  const canRemove = member.mayRemove === true;
  const canOwn = member.mayOwn === true;

  if (!canGrant && !canRemove && !canOwn && !member.mayRights) return null;

  const pill = 'h-11 min-h-0 rounded-full text-callout font-semibold inline-flex items-center justify-center gap-1.5 transition-colors disabled:opacity-50';

  return (
    <div className="mt-1 px-2 pt-3 space-y-3">
      {canGrant && (
        <div>
          <p className="pb-2 text-footnote font-semibold text-ink-3">{t('groupRoles.role')}</p>
          <div role="group" aria-label={t('groupRoles.role')} data-testid="role-segments" className="grid grid-cols-3 p-1 gap-1 rounded-full bg-surface">
            {ladder.map((r) => {
              const active = member.role === r;
              return (
                <button
                  key={r}
                  type="button"
                  aria-pressed={active}
                  disabled={active || busy !== null}
                  onClick={() => run('grant', () => store().setGroupRole(chat.id, member.id, r))}
                  className={`h-11 min-h-0 min-w-0 rounded-full px-1 text-footnote font-semibold transition-colors disabled:cursor-default ${
                    active ? 'bg-ink text-canvas' : `text-ink-2 active:bg-raised ${busy ? 'opacity-50' : ''}`
                  }`}
                >
                  <span className="block truncate">{t(ROLE_WORD[r])}</span>
                </button>
              );
            })}
          </div>
          <p className="pt-2 text-callout text-ink-3">{t(ROLE_CAN[member.role])}</p>
        </div>
      )}

      <RightsEditor chat={chat} member={member} />

      {(canRemove || (canOwn && !confirmOwn)) && (
        <div className={`grid gap-2 ${canRemove && canOwn && !confirmOwn ? 'grid-cols-2' : 'grid-cols-1'}`}>
          {canOwn && !confirmOwn && (
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => {
                soundFx.playTap();
                setConfirmOwn(true);
              }}
              className={`${pill} bg-surface text-ink`}
            >
              {t('groupRoles.makeOwner')}
            </button>
          )}
          {canRemove && (
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => run('remove', () => store().removeGroupMember(chat.id, member.id))}
              className={`${pill} bg-surface text-danger`}
            >
              {busy === 'remove' && <Loader2 className="w-4 h-4 animate-spin" strokeWidth={2} />}
              {t('groupRoles.removeFromGroup')}
            </button>
          )}
        </div>
      )}

      {/* Передача незворотна й міняє ВАШІ права — тому окремий крок, а не
          одне натискання поруч із рештою. Ціна названа до дії, не після. */}
      {canOwn && confirmOwn && (
        <div className="space-y-3">
          <p className="text-callout text-ink">
            <strong>{member.name}</strong>{t('groupRoles.ownConfirm')}
          </p>
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => setConfirmOwn(false)}
              className={`${pill} bg-surface text-ink`}
            >
              {t('groupRoles.no')}
            </button>
            <button
              type="button"
              disabled={busy !== null}
              onClick={() =>
                run('own', async () => {
                  await store().setGroupRole(chat.id, member.id, 'owner');
                  setConfirmOwn(false);
                })
              }
              className={`${pill} bg-accent text-on-accent`}
            >
              {busy === 'own' && <Loader2 className="w-4 h-4 animate-spin" strokeWidth={2} />}
              {t('groupRoles.transfer')}
            </button>
          </div>
        </div>
      )}

      {refused && <p className="text-callout text-danger">{refused}</p>}
    </div>
  );
};

type FieldState = 'idle' | 'saving' | 'saved' | 'error';

/**
 * Керування гілкою: назва, опис, обличчя.
 *
 * Замість вкладки «Дозволи», у якій сім перемикачів, повільний режим і
 * самознищення нічого не робили — вузол про них не знав, і сама вкладка
 * казала це вголос жовтою плашкою. Плашка, яка зізнається, що екран мертвий,
 * — це все одно мертвий екран.
 *
 * Тут кожне поле або їде дротом, або його немає: `powers` приходить з вузла,
 * і поле, на яке в оператора немає права, не малюється взагалі.
 */
export const GroupManageTab: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const powers = chat.powers;
  const myRole: GroupRole = chat.myRole ?? 'member';
  const [title, setTitle] = useState(chat.title);
  const [about, setAbout] = useState(chat.description ?? '');
  const [titleState, setTitleState] = useState<FieldState>('idle');
  const [aboutState, setAboutState] = useState<FieldState>('idle');
  const [titleWhy, setTitleWhy] = useState<string | null>(null);
  const [aboutWhy, setAboutWhy] = useState<string | null>(null);
  const [faceWhy, setFaceWhy] = useState<string | null>(null);
  const [faceState, setFaceState] = useState<FieldState>('idle');
  const fileRef = useRef<HTMLInputElement>(null);

  // Поле йде за гілкою, доки його не чіпали: перейменування, що приїхало від
  // адміна з іншого пристрою, мусить бути видно, а не зникати під чернеткою.
  useEffect(() => {
    if (titleState === 'idle') setTitle(chat.title);
  }, [chat.title]);
  useEffect(() => {
    if (aboutState === 'idle') setAbout(chat.description ?? '');
  }, [chat.description]);

  const saveTitle = async () => {
    const next = title.trim();
    if (!next || next === chat.title) return;
    setTitleState('saving');
    setTitleWhy(null);
    try {
      await useMessengerStore.getState().renameGroup(chat.id, next);
      setTitleState('saved');
      window.setTimeout(() => setTitleState('idle'), 1400);
    } catch (e) {
      setTitleState('error');
      setTitleWhy(e instanceof Error ? e.message : t('groupRoles.renameFailed'));
    }
  };

  const saveAbout = async () => {
    if (about === (chat.description ?? '')) return;
    setAboutState('saving');
    setAboutWhy(null);
    try {
      await useMessengerStore.getState().describeGroup(chat.id, about.trim());
      setAboutState('saved');
      window.setTimeout(() => setAboutState('idle'), 1400);
    } catch (e) {
      setAboutState('error');
      setAboutWhy(e instanceof Error ? e.message : t('groupRoles.aboutFailed'));
    }
  };

  const pickFace = async (file: File) => {
    setFaceState('saving');
    setFaceWhy(null);
    const reader = new FileReader();
    reader.onerror = () => {
      setFaceState('error');
      setFaceWhy(t('groupRoles.readFailed'));
    };
    reader.onload = async () => {
      const data = String(reader.result || '');
      try {
        await useMessengerStore.getState().setGroupFace(chat.id, data);
        setFaceState('saved');
        window.setTimeout(() => setFaceState('idle'), 1400);
      } catch (e) {
        setFaceState('error');
        setFaceWhy(e instanceof Error ? e.message : t('groupRoles.faceFailed'));
      }
    };
    reader.readAsDataURL(await stripMetadata(file));
  };

  // Порожній `powers` — це «вузол ще не відповів», а не «нічого не можна».
  if (!powers) {
    return (
      <div className="py-10 text-center">
        <Loader2 className="w-5 h-5 animate-spin text-ink-3 mx-auto" strokeWidth={2} />
        <p className="mt-2 text-callout text-ink-3">{t('groupRoles.loading')}</p>
      </div>
    );
  }

  const nothing = !powers.rename && !powers.card;

  return (
    <div className="px-4 pb-4 space-y-5">
      <p className="text-callout text-ink-2">
        {t('groupRoles.youAre')} <span className="text-ink font-semibold">{t(ROLE_WORD[myRole]).toLocaleLowerCase()}</span>
      </p>

      {nothing && <p className="text-callout text-ink-2">{t('groupRoles.adminOnly')}</p>}

      {powers.rename && (
        <div className="space-y-1.5">
          <label className="block text-footnote font-semibold text-ink-2">{t('groupRoles.name')}</label>
          <div className="flex gap-1.5">
            <input
              aria-label={t('groupRoles.nameAria')}
              value={title}
              onChange={(e) => {
                setTitle(e.target.value);
                setTitleState('idle');
              }}
              onKeyDown={(e) => e.key === 'Enter' && void saveTitle()}
              maxLength={64}
              className="flex-1 min-w-0 h-12 px-4 rounded-full bg-raised text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
            />
            <button
              type="button"
              onClick={() => void saveTitle()}
              disabled={titleState === 'saving' || !title.trim() || title.trim() === chat.title}
              className="h-12 px-5 rounded-full bg-accent text-on-accent text-footnote font-semibold transition-colors disabled:bg-raised disabled:text-ink-3 disabled:cursor-default inline-flex items-center gap-1.5"
            >
              {titleState === 'saving' ? (
                <Loader2 className="w-4 h-4 animate-spin" strokeWidth={2} />
              ) : titleState === 'saved' ? (
                <Check className="w-4 h-4" strokeWidth={2} />
              ) : null}
              {t('groupRoles.save')}
            </button>
          </div>
          {titleWhy && <p className="text-callout text-danger">{titleWhy}</p>}
        </div>
      )}

      {powers.card && (
        <div className="space-y-1.5">
          <label className="block text-footnote font-semibold text-ink-2">{t('groupRoles.about')}</label>
          <textarea
            value={about}
            onChange={(e) => {
              setAbout(e.target.value);
              setAboutState('idle');
            }}
            onBlur={() => void saveAbout()}
            rows={3}
            maxLength={512}
            placeholder={t('groupRoles.aboutPlaceholder')}
            className="w-full px-4 py-3 rounded-card bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line resize-none"
          />
          <div className="flex items-center justify-between">
            <span className="text-caption text-ink-3">
              {aboutState === 'saving'
                ? t('groupRoles.saving')
                : aboutState === 'saved'
                  ? t('groupRoles.saved')
                  : `${about.length} / 512`}
            </span>
            {aboutState === 'saved' && (
              <Check className="w-4 h-4 text-ink-2" strokeWidth={2} />
            )}
          </div>
          {aboutWhy && <p className="text-callout text-danger">{aboutWhy}</p>}
        </div>
      )}

      {powers.card && (
        <div className="space-y-1.5">
          <label className="block text-footnote font-semibold text-ink-2">{t('groupRoles.photo')}</label>
          <div className="flex items-center gap-3">
            <Avatar src={chat.avatar} name={chat.title} tone={chat.id} radius="rounded-control" className="w-14 h-14 text-[22px]" />
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              disabled={faceState === 'saving'}
              className="h-11 px-5 rounded-full bg-raised text-callout font-semibold text-ink transition-colors disabled:opacity-50 inline-flex items-center gap-1.5"
            >
              {faceState === 'saving' && (
                <Loader2 className="w-4 h-4 animate-spin" strokeWidth={2} />
              )}
              {chat.avatar ? t('groupRoles.replace') : t('groupRoles.set')}
            </button>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              hidden
              onChange={(e) => {
                const f = e.target.files?.[0];
                e.target.value = '';
                if (f) void pickFace(f);
              }}
            />
          </div>
          {faceWhy && <p className="text-callout text-danger">{faceWhy}</p>}
        </div>
      )}
    </div>
  );
};
