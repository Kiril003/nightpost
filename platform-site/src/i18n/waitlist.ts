import type { Lang } from './types';

export interface WaitlistCopy {
  label: string;
  placeholder: string;
  submit: string;
  sending: string;
  promise: string;
  doneTitle: string;
  doneBody: string;
  badEmail: string;
  offline: string;
  fallbackCta: string;
  fallbackSubject: string;
}

export const waitlistCopy: Record<Lang, WaitlistCopy> = {
  uk: {
    label: 'Повідомити, коли вийде версія для компʼютера',
    placeholder: 'you@example.com',
    submit: 'Повідомити мене',
    // «Записую» — це одна людина за клавіатурою; форму заповнюють у компанії.
    sending: 'Записуємо…',
    promise:
      'Один лист — коли версія вийде. Без розсилки, без передачі третім особам і без стеження на цій сторінці.',
    doneTitle: 'Готово, ви в списку',
    doneBody: 'Напишемо один раз — коли буде що завантажити. Щоб вас прибрали зі списку, просто дайте відповідь на той лист.',
    badEmail: 'У пошті, схоже, одруківка. Перевірте, чи є знак @ і назва скриньки після нього.',
    offline: 'Наш сервер зараз не відповів, тож запис не збережено. Спробуйте за хвилину або запишіться листом — він дійде напевно.',
    fallbackCta: 'Записатися листом',
    fallbackSubject: 'Nightpost — стати в чергу',
  },
  en: {
    label: 'Tell me when the desktop version is out',
    placeholder: 'you@example.com',
    submit: 'Notify me',
    sending: 'Adding…',
    promise:
      'One email, when the version ships. No newsletter, nothing passed to third parties, no tracking on this page.',
    doneTitle: 'Done, you are on the list',
    doneBody: 'We write once — when there is something to download. To be removed, just reply to that email.',
    badEmail: 'That email looks like a typo. Check there is an @ and a mailbox name after it.',
    offline: 'Our server did not answer, so nothing was saved. Try again in a minute, or sign up by email — that always gets through.',
    fallbackCta: 'Sign up by email',
    fallbackSubject: 'Nightpost — join the queue',
  },
};
