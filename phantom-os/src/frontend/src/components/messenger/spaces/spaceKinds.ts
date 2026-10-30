import { AudioLines, CalendarDays, ClipboardCheck, Folder, Megaphone, MessageCircle, MessagesSquare, Radio, Wallet, type LucideIcon } from 'lucide-react';
import type { ChannelType } from '@shared/types/space';

/** Розмову в просторі впізнають за значком роду, а не за символом перед назвою. */
export const KIND_ICON: Record<ChannelType, LucideIcon> = {
  chat: MessageCircle,
  forum: MessagesSquare,
  announce: Megaphone,
  voice: AudioLines,
  stage: Radio,
  assign: ClipboardCheck,
  files: Folder,
  kitty: Wallet,
  schedule: CalendarDays,
};

/** Голос і ефір чекають на дзвінки в кімнатах. */
export const NOT_YET: readonly ChannelType[] = ['voice', 'stage'];
