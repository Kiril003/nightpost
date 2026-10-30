import React, { useMemo, useState } from 'react';
import { Check, MessagesSquare, Send } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { can } from '../../../services/spaces/permissions';
import { lineAllowed, postSpaceEvent, readersOf, topicsOf } from '../../../services/spaces/spaceWire';
import type { Message } from '../../../types/messenger';
import { currentDateTag } from '../../chat/tNow';
import { parseNodeTime } from '../chatListFormat';
import { Avatar } from '../kit/Avatar';
import { SpaceFrame } from './SpaceFrame';
import { useSpace } from './useSpace';

const msgTime = (m?: Message, fallbackMs?: number): string => {
  const d = m ? parseNodeTime(m.sentAt ?? m.timestamp) : fallbackMs ? new Date(fallbackMs) : null;
  if (!d) return m?.timestamp ?? '';
  return d.toLocaleTimeString(currentDateTag(), { hour: '2-digit', minute: '2-digit' });
};

/**
 * Тема форуму простору (sv-thread): спільна для всіх профілів. Питання вгорі,
 * позначена відповідь («✓ Відповідь») першою, далі обговорення й композер.
 * Позначити відповідь може автор теми або учасник із правом `forum.solve`.
 */
export const SpaceThread: React.FC<{ chatId: string; channelId: string; topicId: string }> = ({
  chatId,
  channelId,
  topicId,
}) => {
  const { t } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [draft, setDraft] = useState('');
  const { space, chat, meId } = v;
  const ch = space?.channels.find((c) => c.id === channelId && c.type === 'forum');
  const now = Date.now();
  const view = useMemo(
    () => (space ? topicsOf(space, channelId, chat?.messages ?? []).find((x) => x.topic.id === topicId) : undefined),
    [space, channelId, chat?.messages, topicId],
  );

  if (!space || !ch || !view || !can(space, meId, 'read', now, channelId)) {
    return (
      <SpaceFrame testId="space-thread" onBack={nav.pop}>
        <p data-testid="space-thread-state" className="px-4 py-8 text-center text-callout text-ink-3">
          {v.state === 'loading' ? t('space.loading') : t('space.none')}
        </p>
      </SpaceFrame>
    );
  }

  const { topic, opening, replies, accepted } = view;
  const mayWrite = lineAllowed(space, meId, channelId, now);
  const maySolve = topic.authorId === meId || can(space, meId, 'forum.solve', now, channelId);
  const authorName =
    opening?.senderName ??
    chat?.members?.find((m) => m.id === topic.authorId)?.name ??
    (topic.authorId === meId ? useMessengerStore.getState().currentUser.name : topic.authorId);

  const orderedReplies = accepted ? [accepted, ...replies.filter((r) => r.id !== accepted.id)] : replies;

  const send = () => {
    const text = draft.trim();
    if (!text || !mayWrite) return;
    const to = readersOf(space, channelId, Date.now());
    useMessengerStore.getState().sendChannelLine(chatId, text, {
      channelId,
      to,
      replyTo: { id: topic.id, senderName: authorName, text: topic.title },
    });
    setDraft('');
  };

  const markAnswer = async (replyId: string) => {
    if (!maySolve) return;
    await postSpaceEvent(v.profileId, chatId, {
      t: 'topic.status',
      id: topic.id,
      status: 'solved',
      acceptedId: replyId,
    });
    nav.bump();
  };

  return (
    <SpaceFrame
      testId="space-thread"
      title={ch.name}
      subtitle={replies.length > 0 ? t('space.thread.sub', { n: replies.length }) : t('space.thread.subNone')}
      lead={
        <span className="w-9 h-9 shrink-0 rounded-full bg-overlay text-ink-2 flex items-center justify-center">
          <MessagesSquare className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </span>
      }
      onBack={nav.pop}
      footer={
        mayWrite ? (
          <div className="flex items-center gap-2" data-testid="space-thread-composer">
            <input
              data-testid="space-thread-input"
              aria-label={t('space.thread.reply')}
              placeholder={t('space.thread.reply')}
              value={draft}
              maxLength={2000}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && send()}
              className="flex-1 h-11 px-4 rounded-full bg-raised border border-line text-body text-ink placeholder:text-ink-3 outline-none focus:border-ink"
            />
            <button
              type="button"
              data-testid="space-thread-send"
              aria-label={t('space.send')}
              disabled={!draft.trim()}
              onClick={send}
              className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full bg-accent text-on-accent flex items-center justify-center disabled:opacity-40"
            >
              <Send className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            </button>
          </div>
        ) : undefined
      }
    >
      <article data-testid="space-thread-head" className="px-4 pt-3 pb-4 border-b border-line">
        <div className="flex items-center gap-2 text-footnote text-ink-2">
          <Avatar name={authorName} tone={topic.authorId} radius="rounded-full" className="w-7 h-7 text-caption" />
          <span>
            {authorName} · {msgTime(opening, topic.createdAt)}
          </span>
        </div>
        <h3 className="mt-2 text-title-2 text-ink">{topic.title}</h3>
        {opening?.text && <p className="mt-1.5 text-body text-ink whitespace-pre-wrap">{opening.text}</p>}
      </article>
      <ul data-testid="space-thread-replies" className="p-3 space-y-2">
        {orderedReplies.map((r) => {
          const isAnswer = accepted?.id === r.id;
          const isMe = r.senderId === meId || Boolean(r.isSelf);
          return (
            <li
              key={r.id}
              data-testid={`space-thread-reply-${r.id}`}
              className={`flex items-end gap-2 ${isMe ? 'justify-end' : ''}`}
            >
              {!isMe && <Avatar name={r.senderName} tone={r.senderId} radius="rounded-full" className="w-7 h-7 text-caption shrink-0" />}
              <div className={`max-w-[82%] rounded-card px-3.5 py-2.5 border border-line ${isMe ? 'bg-overlay' : 'bg-surface'}`}>
                {isAnswer && (
                  <span
                    data-testid="space-thread-answer-badge"
                    className="mb-1.5 inline-flex items-center gap-1 px-2 py-0.5 rounded-control border border-line text-caption text-ink"
                  >
                    <Check className="w-3.5 h-3.5" strokeWidth={2} aria-hidden />
                    {t('space.thread.answer')}
                  </span>
                )}
                {!isMe && <span className="block text-caption font-medium text-ink-2">{r.senderName}</span>}
                <p className="text-body text-ink whitespace-pre-wrap">{r.text}</p>
                <div className="mt-1 flex items-center justify-end gap-3 text-caption text-ink-3">
                  {maySolve && !isAnswer && (
                    <button
                      type="button"
                      data-testid={`space-thread-solve-${r.id}`}
                      onClick={() => void markAnswer(r.id)}
                      className="min-h-0 h-auto text-caption text-ink-2 hover:text-ink"
                    >
                      {t('space.thread.markAnswer')}
                    </button>
                  )}
                  <span>{msgTime(r)}</span>
                </div>
              </div>
            </li>
          );
        })}
      </ul>
    </SpaceFrame>
  );
};
