import React, { useMemo, useState } from 'react';
import { Check, MessagesSquare, Plus } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { can } from '../../../services/spaces/permissions';
import { postSpaceEvent, readersOf, topicsOf, type ForumTopicView } from '../../../services/spaces/spaceWire';
import { currentDateTag } from '../../chat/tNow';
import { FormSheet } from '../FormSheet';
import { Avatar } from '../kit/Avatar';
import { SpaceFrame, SpacePrimary } from './SpaceFrame';
import { useSpace } from './useSpace';

type ForumFilter = 'all' | 'new' | 'unanswered' | 'mine';

const topicWhen = (ms: number): string =>
  new Date(ms).toLocaleTimeString(currentDateTag(), { hour: '2-digit', minute: '2-digit' });

/**
 * Форум простору (sv-forum): спільний для всіх профілів. Питання й оголошення
 * окремими темами (`topic.put` у журналі простору), фільтри Усі · Нові · Без
 * відповіді · Мої, створення нової теми з початковим дописом.
 */
export const SpaceForum: React.FC<{ chatId: string; channelId: string }> = ({ chatId, channelId }) => {
  const { t } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [filter, setFilter] = useState<ForumFilter>('all');
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const { space, chat, meId } = v;
  const ch = space?.channels.find((c) => c.id === channelId && c.type === 'forum');
  const now = Date.now();
  const list = useMemo(
    () => (space ? topicsOf(space, channelId, chat?.messages ?? []) : []),
    [space, channelId, chat?.messages],
  );

  if (!space || !ch || !can(space, meId, 'read', now, channelId)) {
    return (
      <SpaceFrame testId="space-forum" onBack={nav.pop}>
        <p data-testid="space-forum-state" className="px-4 py-8 text-center text-callout text-ink-3">
          {v.state === 'loading' ? t('space.loading') : t('space.none')}
        </p>
      </SpaceFrame>
    );
  }

  const mayCreate = can(space, meId, 'thread.create', now, channelId) && can(space, meId, 'write', now, channelId);

  const authorName = (item: ForumTopicView): string =>
    item.opening?.senderName ??
    chat?.members?.find((m) => m.id === item.topic.authorId)?.name ??
    (item.topic.authorId === meId ? useMessengerStore.getState().currentUser.name : item.topic.authorId);

  const shown = list.filter((x) => {
    if (filter === 'new') return x.topic.status === 'open';
    if (filter === 'unanswered') return x.topic.status !== 'solved' && x.replies.length === 0;
    if (filter === 'mine') return x.topic.authorId === meId;
    return true;
  });

  const publish = async () => {
    const cleanTitle = title.trim();
    if (!cleanTitle || busy || !mayCreate) return;
    setBusy(true);
    try {
      const topicId = `tp_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
      await postSpaceEvent(v.profileId, chatId, {
        t: 'topic.put',
        topic: { id: topicId, channelId, title: cleanTitle, tags: [], routedRoleId: null },
      });
      const cleanBody = body.trim();
      if (cleanBody) {
        const to = readersOf(space, channelId, Date.now());
        useMessengerStore.getState().sendChannelLine(chatId, cleanBody, {
          channelId,
          to,
          replyTo: { id: topicId, senderName: useMessengerStore.getState().currentUser.name, text: cleanTitle },
        });
      }
      nav.bump();
      setTitle('');
      setBody('');
      setCreating(false);
      nav.push({ s: 'thread', chatId, channelId, topicId });
    } finally {
      setBusy(false);
    }
  };

  const filters: { key: ForumFilter; label: string }[] = [
    { key: 'all', label: t('space.forum.all') },
    { key: 'new', label: t('space.forum.new') },
    { key: 'unanswered', label: t('space.forum.unanswered') },
    { key: 'mine', label: t('space.forum.mine') },
  ];

  return (
    <SpaceFrame
      testId="space-forum"
      title={ch.name}
      subtitle={t('space.forum.topics', { n: list.length })}
      lead={
        <span className="w-9 h-9 shrink-0 rounded-full bg-overlay text-ink-2 flex items-center justify-center">
          <MessagesSquare className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </span>
      }
      onBack={nav.pop}
      footer={
        mayCreate ? (
          <SpacePrimary testId="space-topic-new" className="w-full" onClick={() => setCreating(true)}>
            <Plus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            {t('space.forum.newTopic')}
          </SpacePrimary>
        ) : undefined
      }
    >
      <div role="tablist" className="px-4 pt-3 pb-1 flex items-center gap-2 overflow-x-auto">
        {filters.map((f) => {
          const on = filter === f.key;
          return (
            <button
              key={f.key}
              type="button"
              role="tab"
              aria-selected={on}
              data-testid={`space-forum-filter-${f.key}`}
              onClick={() => setFilter(f.key)}
              className={`min-h-11 px-3.5 shrink-0 rounded-full text-subhead transition-colors ${
                on ? 'bg-overlay text-ink font-medium' : 'border border-line text-ink-2'
              }`}
            >
              {f.label}
            </button>
          );
        })}
      </div>
      {shown.length === 0 ? (
        <p data-testid="space-forum-empty" className="px-4 py-8 text-center text-callout text-ink-3">
          {t('space.forum.empty')}
        </p>
      ) : (
        <ul data-testid="space-forum-list">
          {shown.map((item) => {
            const who = authorName(item);
            const repliesLabel =
              item.replies.length > 0 ? t('space.forum.replies', { n: item.replies.length }) : t('space.forum.noReplies');
            const solved = item.topic.status === 'solved';
            return (
              <li key={item.topic.id} className="border-b border-line">
                <button
                  type="button"
                  data-testid={`space-topic-${item.topic.id}`}
                  onClick={() => nav.push({ s: 'thread', chatId, channelId, topicId: item.topic.id })}
                  className="w-full min-h-[64px] px-4 py-2.5 flex items-center gap-3 text-left active:bg-raised"
                >
                  <Avatar name={who} tone={item.topic.authorId} radius="rounded-full" className="w-10 h-10" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-headline text-ink">{item.topic.title}</span>
                    <span className="block truncate text-subhead text-ink-2">
                      {who} · {repliesLabel} · {topicWhen(item.lastAt)}
                    </span>
                  </span>
                  {solved ? (
                    <Check
                      data-testid={`space-topic-solved-${item.topic.id}`}
                      className="w-4 h-4 shrink-0 text-ink-2"
                      strokeWidth={2}
                      aria-hidden
                    />
                  ) : (
                    <span
                      data-testid={`space-topic-open-${item.topic.id}`}
                      className="w-2 h-2 shrink-0 rounded-full bg-ink"
                      aria-hidden
                    />
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <FormSheet
        open={creating}
        onClose={() => setCreating(false)}
        title={t('space.forum.newTopic')}
        testId="space-topic-sheet"
        footer={
          <SpacePrimary testId="space-topic-publish" className="w-full" disabled={!title.trim() || busy} onClick={publish}>
            {t('space.forum.publish')}
          </SpacePrimary>
        }
      >
        <div className="px-4 pb-3 space-y-3">
          <input
            data-testid="space-topic-title"
            aria-label={t('space.forum.title')}
            placeholder={t('space.forum.title')}
            value={title}
            maxLength={100}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full h-12 px-4 rounded-control bg-raised border border-line text-body text-ink placeholder:text-ink-3 outline-none focus:border-ink"
          />
          <textarea
            data-testid="space-topic-body"
            aria-label={t('space.forum.body')}
            placeholder={t('space.forum.body')}
            value={body}
            maxLength={1000}
            rows={4}
            onChange={(e) => setBody(e.target.value)}
            className="w-full px-4 py-3 rounded-control bg-raised border border-line text-body text-ink placeholder:text-ink-3 outline-none focus:border-ink resize-none"
          />
        </div>
      </FormSheet>
    </SpaceFrame>
  );
};
