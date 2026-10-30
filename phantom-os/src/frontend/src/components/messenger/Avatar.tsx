import { Avatar } from './kit/Avatar';

export { Avatar, avatarLetters, personTone, type AvatarProps } from './kit/Avatar';

export const AVATAR_SIZE = {
  row: 'w-[52px] h-[52px] text-title-2',
  rowDesk: 'w-10 h-10 text-subhead',
  header: 'w-10 h-10 text-title-2',
  rail: 'w-8 h-8 text-footnote',
  crumb: 'w-5 h-5 text-caption',
  profile: 'w-[72px] h-[72px] text-[24px]',
} as const;

export default Avatar;
