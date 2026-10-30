/**
 * Тло екрана дзвінка без відео — рівне полотно (17.10: жодного світіння,
 * розмиття чи градієнта; обличчя людини — сам аватар).
 */

import React from 'react';

export const CallBackdrop: React.FC = () => (
  <div data-call-backdrop aria-hidden className="absolute inset-0 bg-canvas pointer-events-none" />
);

/**
 * Своя камера перед розмовою — рівна вуаль на все, щоб імʼя читалось як
 * підпис над дзеркалом, а не над співрозмовником. Над живим відео
 * співрозмовника вуалі нема: шапка й панель кнопок мають власні рівні поверхні.
 */
export const VideoScrim: React.FC<{ dim?: boolean }> = ({ dim = false }) =>
  dim ? (
    <div
      data-call-scrim
      aria-hidden
      className="absolute inset-0 pointer-events-none"
      style={{ background: 'var(--ph-scrim)' }}
    />
  ) : null;
