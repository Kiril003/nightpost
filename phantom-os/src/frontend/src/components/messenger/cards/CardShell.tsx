/**
 * Картка в розмові — одна мова для всіх живих карток.
 *
 * `surface-1`, радіус 16, заголовок 16/600 і ОДИН рядок стану 13 text-3 (2.14).
 * Лежить поза бурштиновою бульбашкою: картка на заливці своєї бульбашки
 * читалась як форма всередині форми.
 */
import React from 'react';
import { TicketCard } from '../kit/TicketCard';

export const CardShell = TicketCard;

/** Круглий прапорець пункту: 22, заливка бурштином, коли зроблено. */
export const CheckDot: React.FC<{ done: boolean }> = ({ done }) => (
  <span
    aria-hidden
    className={`w-[22px] h-[22px] shrink-0 rounded-full flex items-center justify-center transition-colors duration-instant ${
      done ? 'bg-accent text-on-accent' : 'border-[1.5px] border-f-ink-3'
    }`}
  >
    {done && (
      <svg viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2.2}>
        <path d="M3.5 8.5l3 3 6-7" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )}
  </span>
);
