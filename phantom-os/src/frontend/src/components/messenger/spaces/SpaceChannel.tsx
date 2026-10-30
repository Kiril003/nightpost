import React, { useMemo, useState } from 'react';
import { Bell, BellOff, MessageSquare, Send, X } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { can } from '../../../services/spaces/permissions';
import { channelFeed, commentAllowed, lineAllowed, postsOf, readersOf } from '../../../services/spaces/spaceWire';
import type { Message } from '../../../types/messenger';
import { currentDateTag } from '../../chat/tNow';
import { parseNodeTime } from '../chatListFormat';
import { SpaceFrame } from './SpaceFrame';
import { KIND_ICON } from './spaceKinds';
import { useSpace } from './useSpace';

const postWhen = (m: Message): string => {
  const d = parseNodeTime(m.sentAt ?? m.timestamp);
  if (!d) return m.timestamp || '';
  return d.toLocaleString(currentDateTag(), { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
};

/**
 * Канал чи розмова простору (sv-channel): дописи через `channelFeed` (перевірка
 * прав автора на мить листа), коментарі під кожним дописом, композер для тих,
 * хто має право писати або коментувати, і «Без звуку» внизу для читачів.
 */
export const SpaceChannel: React.FC<{ chatId: string; channelId: string }> = ({ chatId, channelId }) => {
  const { t } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [draft, setDraft] = useState('');
  const [replying, setReplying] = useState<Message | null>(null);
  const { space, chat, meId } = v;
  const ch = space?.channels.find((c) => c.id === channelId);
  const now = Date.now();
  const feed = useMemo(
    () => (space ? channelFeed(space, channelId, chat?.messages ?? []) : []),
    [space, channelId, chat?.messages],
  );
  const posts = useMemo(() => postsOf(feed), [feed]);

  if (!space || !ch || !can(space, meId, 'read', now, channelId)) {
    return (
      <SpaceFrame testId="space-channel" onBack={nav.pop}>
        <p data-testid="space-channel-state" className="px-4 py-8 text-center text-callout text-ink-3">
          {v.state === 'loading' ? t('space.loading') : t('space.none')}
        </p>
      </SpaceFrame>
    );
  }

  const mayWrite = lineAllowed(space, meId, channelId, now);
  const mayComment = commentAllowed(space, meId, channelId, now);
  const target = replying && posts.some((p) => p.post.id === replying.id) ? replying : null;
  const showComposer = mayWrite || Boolean(target && mayComment);
  const Icon = KIND_ICON[ch.type];
  const spaceName = space.name || chat?.title || '';

  const send = () => {
    const text = draft.trim();
    if (!text || !showComposer) return;
    const to = readersOf(space, channelId, Date.now());
    useMessengerStore.getState().sendChannelLine(chatId, text, {
      channelId,
      to,
      ...(target ? { replyTo: { id: target.id, senderName: target.senderName, text: target.text ?? '' } } : {}),
    });
    setDraft('');
    setReplying(null);
  };

  const footer = showComposer ? (
    <div data-testid="space-channel-composer" className="space-y-2">
      {target && (
        <div data-testid="space-reply-banner" className="flex items-center justify-between gap-2 px-3 py-1.5 rounded-control bg-raised text-footnote text-ink-2">
          <span className="truncate">
            <strong className="font-semibold text-ink">{target.senderName}</strong> · {target.text}
          </span>
          <button
            type="button"
            data-testid="space-reply-cancel"
            aria-label={t('space.close')}
            onClick={() => setReplying(null)}
            className="w-11 h-11 shrink-0 flex items-center justify-center rounded-full text-ink-2"
          >
            <X className="w-4 h-4" strokeWidth={2} aria-hidden />
          </button>
        </div>
      )}
      <div className="flex items-center gap-2">
        <input
          data-testid="space-channel-input"
          aria-label={target ? t('space.comment') : ch.type === 'announce' ? t('space.post') : t('space.message')}
          placeholder={target ? t('space.comment') : ch.type === 'announce' ? t('space.post') : t('space.message')}
          value={draft}
          maxLength={2000}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send()}
          className="flex-1 h-11 px-4 rounded-full bg-raised border border-line text-body text-ink placeholder:text-ink-3 outline-none focus:border-ink"
        />
        <button
          type="button"
          data-testid="space-channel-send"
          aria-label={t('space.send')}
          disabled={!draft.trim()}
          onClick={send}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full bg-accent text-on-accent flex items-center justify-center disabled:bg-overlay disabled:text-ink-3"
        >
          <Send className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </button>
      </div>
    </div>
  ) : (
    <button
      type="button"
      data-testid="space-channel-mute"
      aria-pressed={Boolean(chat?.muted)}
      onClick={() => useMessengerStore.getState().toggleMuteChat(chatId)}
      className="w-full h-12 min-h-0 rounded-full bg-overlay text-ink text-headline font-semibold flex items-center justify-center gap-2 active:opacity-80"
    >
      {chat?.muted ? <Bell className="w-5 h-5" strokeWidth={1.75} aria-hidden /> : <BellOff className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
      {chat?.muted ? t('space.unmute') : t('space.mute')}
    </button>
  );

  return (
    <SpaceFrame
      testId="space-channel"
      title={ch.name}
      subtitle={`${t(`space.type.${ch.type}`)} · ${spaceName}`}
      lead={
        <span className="w-9 h-9 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
          <Icon className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </span>
      }
      onBack={nav.pop}
      footer={footer}
    >
      {posts.length === 0 ? (
        <p data-testid="space-channel-empty" className="px-4 py-8 text-center text-callout text-ink-3">
          {t(ch.type === 'chat' ? 'space.emptyChat' : 'space.emptyChannel')}
        </p>
      ) : (
        <ul data-testid="space-channel-feed">
          {posts.map(({ post, comments }) => (
            <li key={post.id} data-testid={`space-post-${post.id}`} className="px-4 py-3 border-b border-line">
              <div className="flex items-center gap-1.5 text-footnote text-ink-3">
                <span className="font-medium text-ink-2">{post.senderName}</span>
                <span>·</span>
                <span>{postWhen(post)}</span>
              </div>
              {post.text && <p className="mt-1 text-body text-ink whitespace-pre-wrap">{post.text}</p>}
              {(mayComment || comments.length > 0) && (
                <div className="mt-2 flex items-center gap-3 text-footnote text-ink-3">
                  {mayComment && (
                    <button
                      type="button"
                      data-testid={`space-comment-btn-${post.id}`}
                      onClick={() => setReplying(post)}
                      className="min-h-0 h-auto inline-flex items-center gap-1.5 text-footnote text-ink-2 hover:text-ink"
                    >
                      <MessageSquare className="w-4 h-4" strokeWidth={1.75} aria-hidden />
                      <span>{comments.length > 0 ? String(comments.length) : t('space.comment')}</span>
                    </button>
                  )}
                </div>
              )}
              {comments.length > 0 && (
                <ul data-testid={`space-comments-${post.id}`} className="mt-2 pl-3 border-l-2 border-line space-y-2">
                  {comments.map((c) => (
                    <li key={c.id} data-testid={`space-comment-${c.id}`} className="text-subhead text-ink">
                      <span className="font-medium text-ink-2">{c.senderName}: </span>
                      <span className="whitespace-pre-wrap">{c.text}</span>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}
    </SpaceFrame>
  );
};
