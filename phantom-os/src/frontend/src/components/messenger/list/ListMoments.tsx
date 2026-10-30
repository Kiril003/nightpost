/**
 * Моменти над пошуком у «Розмовах» (list-fix): лише люди зі свіжими моментами,
 * свій кружок першим. Непобачене — бурштинове кільце, побачене — сильна лінія.
 * Згорнуто — до трьох малих кружків біля заголовка. Відкриває вже змонтований
 * хост MomentsStrip (variant="host"), тож гортання між людьми — як у MomentViewer.
 */
import React, { useMemo } from 'react';
import { Plus } from 'lucide-react';
import type { Chat, UserProfile } from '../../../types/messenger';
import { useMoments, type Moment } from '../../../services/moments';
import { useTranslation } from '../../../i18n/useTranslation';
import { soundFx } from '../../../utils/messengerSound';
import { Avatar } from '../Avatar';
import { isVideo, momentPeople, useMomentMedia } from '../MomentViewer';
import { composeMomentInHost, openMomentInHost, ringRankOf } from '../MomentsStrip';
import { freshPeople, groupSeen, startOf } from './momentsStripModel';
import { useMomentScope } from '../../../services/momentProfile';

type Me = Pick<UserProfile, 'id' | 'name' | 'avatar'>;

export interface ListMomentsData {
  /** Свої моменти (порожньо — «+»). */
  own: Moment[];
  /** Інші люди зі свіжими моментами, у порядку смуги. */
  people: Moment[][];
  nameOf: (m: Moment) => string;
  avatarOf: (m: Moment) => string | undefined;
  /** Тон своєї монограми — той самий ключ, що на «Ви» і в шапці. */
  self?: string;
}

/** Дані смуги; опитування моментів веде хост MomentsStrip у тому ж списку. */
export function useListMoments(chats: Chat[], me: Me): ListMomentsData {
  const { t } = useTranslation();
  const list = useMoments((s) => s.list);
  const ringOf = useMemo(() => ringRankOf(chats), [chats]);
  const persons = useMemo(() => new Map(momentPeople(chats).map((p) => [p.id, p])), [chats]);
  const inScope = useMomentScope(chats);
  return useMemo(() => {
    const groups = freshPeople((list ?? []).filter(inScope), ringOf);
    return {
      own: groups.find((g) => g[0].mine) ?? [],
      people: groups.filter((g) => !g[0].mine),
      nameOf: (m: Moment) => (m.mine ? t('moments.you') : persons.get(m.fromId)?.name || m.fromName || t('chatList.unnamed')),
      avatarOf: (m: Moment) => (m.mine ? me.avatar || undefined : persons.get(m.fromId)?.avatar),
      self: me.id || me.name || undefined,
    };
  }, [list, inScope, ringOf, persons, me.avatar, me.id, me.name, t]);
}

/** Є що показати: хоч один свіжий момент — свій чи чужий. */
export const hasListMoments = (d: ListMomentsData): boolean => d.own.length > 0 || d.people.length > 0;

const open = (group: Moment[]) => {
  soundFx.playTap();
  openMomentInHost(startOf(group).id);
};

/** Бурштин лишається головній дії екрана: непобачене — чорнилом, побачене — тонкою лінією. */
const ringTone = (seen: boolean) => (seen ? 'border border-line-strong p-[3px]' : 'border-2 border-ink p-[2px]');

const Cover: React.FC<{ group: Moment[]; name: string; avatar?: string; className: string; self?: string }> = ({ group, name, avatar, className, self }) => {
  const last = group[group.length - 1];
  const { url } = useMomentMedia(avatar ? null : last);
  const cover = avatar ?? (!isVideo(last) ? url ?? last.media[0]?.thumb ?? null : null);
  return <Avatar src={cover} name={name} tone={last.mine ? self ?? null : last.fromId} className={className} />;
};

const addOwn = () => {
  soundFx.playTap();
  composeMomentInHost();
};

const Tile: React.FC<{ group: Moment[]; name: string; avatar?: string; self?: string }> = ({ group, name, avatar, self }) => {
  const { t } = useTranslation();
  const seen = groupSeen(group);
  const who = group[0].mine ? 'me' : group[0].fromId;
  return (
    <li className="relative shrink-0 w-16">
      <button
        type="button"
        data-list-moment={who}
        data-seen={seen ? 'true' : 'false'}
        onClick={() => open(group)}
        aria-label={t('moments.open', { name })}
        className="w-16 min-w-0 min-h-0 p-0 flex flex-col items-center gap-1"
      >
        <span data-moment-ring={seen ? 'seen' : 'unseen'} className={`flex w-[60px] h-[60px] rounded-full ${ringTone(seen)}`}>
          <Cover group={group} name={name} avatar={avatar} self={self} className="w-full h-full text-title-2" />
        </span>
        <span className={`max-w-16 truncate text-caption ${seen ? 'text-ink-3' : 'text-ink-2'}`}>{name.trim().split(/\s+/)[0] || name}</span>
      </button>
      {group[0].mine && (
        <button
          type="button"
          data-moment-own-plus
          onClick={addOwn}
          aria-label={t('today.door.moment')}
          className="touch-floor-inline absolute left-[42px] top-[42px] w-5 h-5 rounded-full bg-ink text-canvas border-2 border-canvas flex items-center justify-center"
        >
          <Plus aria-hidden className="w-3 h-3" strokeWidth={2.6} />
        </button>
      )}
    </li>
  );
};

/** Ряд історій. Нікого зі свіжими моментами — свій «+»; `quiet` — порожнечу пояснює стрічка під ним. */
export const ListMomentsStrip: React.FC<{ data: ListMomentsData; me: Me; quiet?: boolean }> = ({ data, me, quiet = false }) => {
  const { t } = useTranslation();
  const { own, people, nameOf, avatarOf } = data;
  const empty = !hasListMoments(data);
  return (
    <div
      data-list-moments
      data-own-swipe
      role="group"
      aria-label={t('moments.title')}
      className="flex items-start gap-1 overflow-x-auto no-scrollbar px-4 pt-1 pb-2.5"
    >
      <ul className="contents">
        {own.length > 0 ? (
          <Tile group={own} name={nameOf(own[0])} avatar={avatarOf(own[0])} self={data.self} />
        ) : (
          <li className="shrink-0 w-16">
            <button
              type="button"
              data-list-moment="me"
              data-moment-own-add
              onClick={addOwn}
              aria-label={t('today.door.moment')}
              className="w-16 min-w-0 min-h-0 p-0 flex flex-col items-center gap-1"
            >
              <span className="relative flex w-[60px] h-[60px] rounded-full border-2 border-transparent p-[2px]">
                <Avatar src={me.avatar || null} name={me.name || t('moments.you')} tone={data.self ?? null} className="w-full h-full text-title-2" />
                <span aria-hidden className="absolute -right-0.5 -bottom-0.5 w-5 h-5 rounded-full bg-ink text-canvas border-2 border-canvas flex items-center justify-center">
                  <Plus className="w-3 h-3" strokeWidth={2.6} />
                </span>
              </span>
              <span className="max-w-16 truncate text-caption text-ink-2">{empty && !quiet ? t('momentsX.addShort') : t('moments.you')}</span>
            </button>
          </li>
        )}
        {empty && !quiet && (
          <li data-list-moments-empty className="min-w-0 h-[64px] pl-2 flex items-center text-subhead text-ink-3">
            {t('momentsX.empty')}
          </li>
        )}
        {people.map((g) => (
          <Tile key={g[0].fromId} group={g} name={nameOf(g[0])} avatar={avatarOf(g[0])} />
        ))}
      </ul>
    </div>
  );
};

/** Згорнута смуга: до трьох кружків, що перекриваються; дотик — розгорнути. */
export const ListMomentsMini: React.FC<{ data: ListMomentsData; onExpand: () => void }> = ({ data, onExpand }) => {
  const { t } = useTranslation();
  const top = [...(data.own.length ? [data.own] : []), ...data.people].slice(0, 3);
  if (!top.length) return null;
  return (
    <button
      type="button"
      data-list-moments-mini
      aria-label={t('moments.title')}
      onClick={() => {
        soundFx.playTap();
        onExpand();
      }}
      className="shrink-0 h-11 min-h-0 min-w-0 px-1 flex items-center f-fade-in"
    >
      {top.map((g, i) => {
        const seen = groupSeen(g);
        return (
          <span
            key={g[0].mine ? 'me' : g[0].fromId}
            data-mini-ring={seen ? 'seen' : 'unseen'}
            className={`flex w-8 h-8 rounded-full bg-canvas ${seen ? 'border border-line-strong p-[2px]' : 'border-2 border-ink p-[1.5px]'} ${i > 0 ? '-ml-2.5' : ''}`}
          >
            <Cover group={g} name={data.nameOf(g[0])} avatar={data.avatarOf(g[0])} self={data.self} className="w-full h-full text-caption" />
          </span>
        );
      })}
    </button>
  );
};
