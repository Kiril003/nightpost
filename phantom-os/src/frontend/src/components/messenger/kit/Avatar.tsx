import React, { useEffect, useState } from 'react';
import { User } from 'lucide-react';
import { toneIndex } from '../../../styles/formTokens';
import { isUnnamed } from '../chatListFormat';

/**
 * Аватар (дизайн-система §7.3).
 *
 * Фото — типово. Без фото — плоска монограма тону людини: один із ШЕСТИ
 * приглушених тонів (`--ph-person-N`), літера Manrope 600 на 40 % розміру
 * тоном того ж відтінку. Жодного градієнта, відблиску чи кільця — «райдужні
 * кулі» прибрано (AUDIT.md №5). Без `tone` аватар нейтральний (`raised` +
 * `ink-2`) — так малюються «Збережене», PHANTOM і все, чиєї особи ми не знаємо.
 */

/** CSS-барви тону людини за ключем (id вузла / id розмови). */
export const personTone = (key: string): { fill: string; ink: string; name: string } => {
  const i = toneIndex(key);
  // fill/ink — монограма (суцільний тон, білі літери); name — той самий тон
  // як ТЕКСТ (імʼя автора в групі, смуга цитати), ≥ 4.5:1 на всіх поверхнях.
  return { fill: `var(--ph-person-${i})`, ink: `var(--ph-person-${i}-ink)`, name: `var(--ph-person-${i}-name)` };
};

export interface AvatarProps {
  src?: string | null;
  name: string;
  className?: string;
  radius?: string;
  /** Лишено для сумісності викликів; тон задає `tone`. */
  seed?: string;
  /** Ключ особи (id вузла чи розмови) — дає сталий тон PEOPLE. Немає — нейтральний. */
  tone?: string | null;
  night?: boolean;
  moments?: 'seen' | 'unseen';
  momentsSize?: string;
}

/**
 * Одна літера — перша буква або цифра імені.
 *
 * Тут були дві літери з перших двох слів. Два наслідки на A52: «МВ» при
 * системному шрифті 1.3 виходили ширшими за сам квадрат (47 px проти 41), а
 * назва «Ноутбук · 9b10» давала «Н·» — друге «слово» було крапкою. Форма
 * каже «літера», і одна літера влазить у будь-який розмір при будь-якому кеглі.
 */
export const avatarLetters = (name: string): string => {
  const first = (name || '').match(/[\p{L}\p{N}]/u)?.[0];
  return first ? first.toLocaleUpperCase('uk-UA') : '?';
};

export const Avatar: React.FC<AvatarProps> = ({ moments, momentsSize = 'w-14 h-14', ...face }) =>
  moments ? (
    <span
      data-moment-ring
      data-ring={moments}
      className={`flex ${momentsSize} rounded-full items-center justify-center ${moments === 'seen' ? 'border border-line' : 'border-[1.5px] border-ink'}`}
    >
      <AvatarFace {...face} />
    </span>
  ) : (
    <AvatarFace {...face} />
  );

const AvatarFace: React.FC<Omit<AvatarProps, 'moments' | 'momentsSize'>> = ({
  src,
  name,
  className = 'w-9 h-9',
  radius = 'rounded-full',
  tone,
}) => {
  const [broken, setBroken] = useState(false);
  const trimmedSrc = src?.trim();
  useEffect(() => {
    setBroken(false);
  }, [trimmedSrc]);
  const isUrl = trimmedSrc && /^(https?:\/\/|data:|blob:|\/|\.\/)/i.test(trimmedSrc);
  const usable = Boolean(isUrl && !broken);

  if (usable) {
    return (
      <img
        src={trimmedSrc as string}
        alt={name}
        onError={() => setBroken(true)}
        className={`${className} ${radius} object-cover bg-raised shrink-0`}
      />
    );
  }

  const isEmoji = Boolean(trimmedSrc && /\p{Extended_Pictographic}/u.test(trimmedSrc) && trimmedSrc.length <= 8);
  const letters = isEmoji ? trimmedSrc! : avatarLetters(name);
  // Імені немає — і літери теж. «Б» від «Без імені» (свіже встановлення,
  // ім'я пропущено, A52 02.10) чи «?» читались як чиєсь ім'я; силует — ні.
  const nameless = !isEmoji && (letters === '?' || isUnnamed((name || '').trim()));

  const t = tone ? personTone(tone) : null;
  return (
    <div
      className={`${className} ${radius} shrink-0 flex items-center justify-center font-semibold select-none ${t ? '' : 'bg-raised text-ink-2'}`}
      style={t ? { backgroundColor: t.fill, color: t.ink } : undefined}
      aria-label={name}
      data-nameless={nameless ? '' : undefined}
    >
      {nameless ? <User className="w-[46%] h-[46%]" strokeWidth={1.75} aria-hidden /> : letters}
    </div>
  );
};

