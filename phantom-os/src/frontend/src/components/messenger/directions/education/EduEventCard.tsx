import React from 'react';
import { CalendarCheck, ClipboardCheck, Check, GraduationCap, MessagesSquare } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen } from '../../../../services/edu/eduActions';
import type { ParsedEdu } from '../../../../services/edu/eduWire';
import { Button } from '../../kit/Button';
import { useClass } from './useClass';

const ICO = { className: 'w-4 h-4 shrink-0', strokeWidth: 1.75, 'aria-hidden': true } as const;

/** Лист навчання в стрічці групи: картка замість рядка з міткою. */
export const EduEventCard: React.FC<{ parsed: ParsedEdu; chatId?: string; senderId: string; text: string }> = ({ parsed, chatId, senderId, text }) => {
  const { t } = useTranslation();
  const k = useClass(chatId ?? '');
  const nav = useEduNav();
  const { ev, body } = parsed;
  // Офіційна картка — лише від вчителя класу й коли ціль є; інакше просто текст.
  const official = (() => {
    if (!k) return false;
    switch (ev.k) {
      case 'class':
        return senderId === k.cls.teacherId;
      case 'assign':
        return senderId === k.cls.teacherId && k.assignments.some((a) => a.id === ev.id);
      case 'attend':
        return senderId === k.cls.teacherId && k.attendance.some((a) => a.id === ev.id);
      case 'done':
        return k.assignments.some((a) => a.id === ev.a);
      case 'topic':
        return k.topics.some((x) => x.id === ev.id);
      case 'reply':
      case 'answer':
        return k.topics.some((x) => x.id === ev.tp);
    }
  })();
  if (!official) {
    return (
      <p data-testid="edu-card-plain" className="text-body text-ink whitespace-pre-wrap">
        {text}
      </p>
    );
  }
  const open = (route: Parameters<typeof nav.push>[0]) => (
    <Button variant="secondary" testId="edu-card-open" className="mt-2" onClick={() => nav.push(route)}>
      {t('edu.card.open')}
    </Button>
  );

  let head: React.ReactNode = null;
  let icon = <ClipboardCheck {...ICO} />;
  let lines: React.ReactNode = null;
  let action: React.ReactNode = null;

  switch (ev.k) {
    case 'class':
      icon = <GraduationCap {...ICO} />;
      head = t('edu.class');
      lines = (
        <>
          <p className="text-headline text-ink">{ev.n}</p>
          {ev.s && <p className="text-subhead text-ink-2">{ev.s}</p>}
        </>
      );
      if (k && chatId) action = open({ s: 'home', chatId });
      break;
    case 'assign':
      head = t('edu.assign');
      lines = (
        <>
          <p className="text-headline text-ink">{ev.t}</p>
          {ev.due !== null && <p className="text-subhead text-ink-2">{t('edu.assign.until', { when: eduWhen(ev.due) })}</p>}
          {ev.note && <p className="mt-1 text-callout text-ink-2 whitespace-pre-wrap">{ev.note}</p>}
        </>
      );
      if (k && chatId) action = open({ s: 'assignment', chatId, id: ev.id });
      break;
    case 'done':
      icon = <Check {...ICO} />;
      head = t('edu.assign.done');
      lines = <p className="text-callout text-ink">{ev.t}</p>;
      break;
    case 'attend':
      icon = <CalendarCheck {...ICO} />;
      head = t('edu.attendance');
      lines = (
        <>
          <p className="text-headline text-ink">{ev.lesson}</p>
          <p className="text-subhead text-ink-2">{t('edu.att.summary', { p: ev.p.length + ev.l.length, n: ev.p.length + ev.l.length + ev.x.length })}</p>
        </>
      );
      if (k && chatId) action = open({ s: 'attendance', chatId });
      break;
    case 'topic':
      icon = <MessagesSquare {...ICO} />;
      head = t('edu.forum');
      lines = (
        <>
          <p className="text-headline text-ink">{ev.t}</p>
          {body && <p className="text-callout text-ink-2 whitespace-pre-wrap">{body}</p>}
        </>
      );
      if (k && chatId) action = open({ s: 'thread', chatId, id: ev.id });
      break;
    case 'reply':
      icon = <MessagesSquare {...ICO} />;
      head = t('edu.forum');
      lines = <p className="text-callout text-ink whitespace-pre-wrap">{body}</p>;
      if (k && chatId) action = open({ s: 'thread', chatId, id: ev.tp });
      break;
    case 'answer':
      icon = <Check {...ICO} />;
      head = t('edu.forum.answer');
      if (k && chatId) action = open({ s: 'thread', chatId, id: ev.tp });
      break;
  }

  return (
    <div data-testid={`edu-card-${ev.k}`} className="min-w-[200px]">
      <p className="flex items-center gap-1.5 text-footnote text-ink-2">
        {icon}
        {head}
      </p>
      <div className="mt-1">{lines}</div>
      {action}
    </div>
  );
};
