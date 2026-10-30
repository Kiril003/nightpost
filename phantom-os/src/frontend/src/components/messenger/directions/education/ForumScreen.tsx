import React, { useState } from 'react';
import { MessagesSquare, Plus } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen, postTopic } from '../../../../services/edu/eduActions';
import { FilterChip } from '../../kit/FilterChip';
import { StatusChip } from '../../kit/StatusChip';
import { EmptyState } from '../../kit/EmptyState';
import { FormSheet } from '../../FormSheet';
import { EduFrame, EduPrimary, fieldClass } from './EduFrame';
import { useClass } from './useClass';

type Filter = 'all' | 'open' | 'solved' | 'mine';

const TopicSheet: React.FC<{ chatId: string; onClose: () => void }> = ({ chatId, onClose }) => {
  const { t } = useTranslation();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('edu.forum.new')}
      testId="edu-topic-sheet"
      footer={
        <EduPrimary
          testId="edu-topic-publish"
          disabled={!title.trim()}
          onClick={() => {
            postTopic(chatId, title, body);
            onClose();
          }}
        >
          {t('edu.forum.publish')}
        </EduPrimary>
      }
    >
      <div className="px-4 pb-3 space-y-3">
        <input
          data-testid="edu-topic-title"
          aria-label={t('edu.forum.titleLabel')}
          placeholder={t('edu.forum.titleLabel')}
          value={title}
          maxLength={120}
          onChange={(e) => setTitle(e.target.value)}
          className={fieldClass}
        />
        <textarea
          data-testid="edu-topic-body"
          aria-label={t('edu.forum.bodyLabel')}
          placeholder={t('edu.forum.bodyLabel')}
          value={body}
          maxLength={600}
          rows={4}
          onChange={(e) => setBody(e.target.value)}
          className={`${fieldClass} h-auto py-3 resize-none`}
        />
      </div>
    </FormSheet>
  );
};

/** Форум класу: теми з відповідями, фільтри Усі · Відкриті · Вирішені · Мої. */
export const ForumScreen: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const k = useClass(chatId);
  const nav = useEduNav();
  const [filter, setFilter] = useState<Filter>('all');
  const [creating, setCreating] = useState(false);
  if (!k) return null;
  const shown = k.topics.filter((x) =>
    filter === 'open' ? !x.solved : filter === 'solved' ? x.solved : filter === 'mine' ? x.authorId === k.meId : true,
  );
  const count = (f: Filter) =>
    k.topics.filter((x) => (f === 'open' ? !x.solved : f === 'solved' ? x.solved : f === 'mine' ? x.authorId === k.meId : true)).length;

  return (
    <EduFrame
      testId="edu-forum"
      title={t('edu.forum')}
      subtitle={k.cls.name}
      onBack={nav.pop}
      footer={
        <EduPrimary testId="edu-topic-new" onClick={() => setCreating(true)}>
          <span className="inline-flex items-center gap-2">
            <Plus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            {t('edu.forum.new')}
          </span>
        </EduPrimary>
      }
    >
      {k.topics.length === 0 ? (
        <EmptyState testId="edu-forum-empty" icon={<MessagesSquare className="w-6 h-6" strokeWidth={1.75} />} label={t('edu.forum.empty')} />
      ) : (
        <>
          <div role="tablist" className="flex gap-1 px-3 pt-2 overflow-x-auto">
            {(['all', 'open', 'solved', 'mine'] as const).map((f) => (
              <FilterChip key={f} testId={`edu-forum-filter-${f}`} active={filter === f} count={count(f)} onClick={() => setFilter(f)}>
                {t(`edu.forum.filter.${f}`)}
              </FilterChip>
            ))}
          </div>
          <ul data-testid="edu-topics">
            {shown.map((x) => (
              <li key={x.id}>
                <button
                  type="button"
                  data-testid={`edu-topic-${x.id}`}
                  onClick={() => nav.push({ s: 'thread', chatId, id: x.id })}
                  className="w-full text-left px-4 py-3 border-b border-line active:bg-overlay"
                >
                  <div className="text-headline text-ink">{x.title}</div>
                  {x.body && <div className="mt-0.5 text-subhead text-ink-2 line-clamp-2">{x.body}</div>}
                  <div className="mt-2 flex items-center gap-2 text-footnote text-ink-2">
                    <span className="min-w-0 flex-1 truncate">
                      {x.author} · {eduWhen(x.lastAt)}
                    </span>
                    <span>{x.replies.length > 0 ? t('edu.forum.replies', { n: x.replies.length }) : t('edu.forum.noReplies')}</span>
                    <StatusChip kind={x.solved ? 'done' : 'neutral'}>{x.solved ? t('edu.forum.solved') : t('edu.forum.openState')}</StatusChip>
                  </div>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {creating && <TopicSheet chatId={chatId} onClose={() => setCreating(false)} />}
    </EduFrame>
  );
};
