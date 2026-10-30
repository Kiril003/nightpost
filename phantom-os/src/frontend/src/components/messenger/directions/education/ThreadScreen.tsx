import React, { useState } from 'react';
import { Check, Send } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen, markAnswer, postReply } from '../../../../services/edu/eduActions';
import { Avatar } from '../../kit/Avatar';
import { Button } from '../../kit/Button';
import { EduFrame, fieldClass } from './EduFrame';
import { useClass } from './useClass';

/** Тема форуму: початковий допис, відповіді, позначка «Відповідь» (вчитель або автор). */
export const ThreadScreen: React.FC<{ chatId: string; id: string }> = ({ chatId, id }) => {
  const { t } = useTranslation();
  const k = useClass(chatId);
  const nav = useEduNav();
  const [draft, setDraft] = useState('');
  const topic = k?.topics.find((x) => x.id === id);
  if (!k || !topic) return null;
  const mayMark = k.cls.isTeacher || topic.authorId === k.meId;
  const role = (authorId: string): string | null =>
    authorId === k.cls.teacherId ? t('edu.forum.teacher') : authorId === topic.authorId ? t('edu.forum.author') : null;
  const answer = topic.replies.find((r) => r.answer);

  const send = () => {
    if (!draft.trim()) return;
    postReply(chatId, topic.id, topic.title, draft);
    setDraft('');
  };

  const who = (authorId: string, name: string) => (
    <div className="flex items-center gap-2">
      <Avatar name={name} tone={authorId} className="w-8 h-8 text-subhead" />
      <span className="text-subhead font-medium text-ink">{name}</span>
      {role(authorId) && <span className="text-footnote text-ink-2">{role(authorId)}</span>}
    </div>
  );

  return (
    <EduFrame
      testId="edu-thread"
      title={topic.title}
      subtitle={`${t('edu.forum')} · ${topic.replies.length}`}
      onBack={nav.pop}
      footer={
        <div className="flex items-center gap-2">
          <input
            data-testid="edu-reply-input"
            aria-label={t('edu.forum.reply')}
            placeholder={t('edu.forum.reply')}
            value={draft}
            maxLength={600}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && send()}
            className={fieldClass}
          />
          <button
            type="button"
            data-testid="edu-reply-send"
            aria-label={t('edu.forum.send')}
            disabled={!draft.trim()}
            onClick={send}
            className="w-12 h-12 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink disabled:opacity-40"
          >
            <Send className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          </button>
        </div>
      }
    >
      {answer && (
        <section data-testid="edu-answer" className="m-4 rounded-card bg-surface border border-line border-l-4 border-l-ink px-4 py-3">
          <p className="flex items-center gap-1 text-footnote text-ink-2">
            <Check className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            {t('edu.forum.answer')} · {answer.author}
          </p>
          <p className="mt-1 text-callout text-ink whitespace-pre-wrap">{answer.text}</p>
        </section>
      )}
      <article className="px-4 pt-3 pb-3 border-b border-line">
        {who(topic.authorId, topic.author)}
        {topic.body && <p className="mt-2 text-body text-ink whitespace-pre-wrap">{topic.body}</p>}
        <p className="mt-1 text-footnote text-ink-3">{eduWhen(topic.at)}</p>
      </article>
      <ul data-testid="edu-replies">
        {topic.replies.map((r) => (
          <li key={r.id} data-testid={`edu-reply-${r.id}`} className="px-4 py-3 border-b border-line">
            {who(r.authorId, r.author)}
            <p className="mt-2 text-body text-ink whitespace-pre-wrap">{r.text}</p>
            <div className="mt-1 flex items-center gap-3">
              <span className="text-footnote text-ink-3">{eduWhen(r.at)}</span>
              {mayMark && !r.answer && (
                <Button variant="text" testId={`edu-mark-answer-${r.id}`} onClick={() => markAnswer(chatId, topic.id, r.id, topic.title)}>
                  {t('edu.forum.markAnswer')}
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </EduFrame>
  );
};
