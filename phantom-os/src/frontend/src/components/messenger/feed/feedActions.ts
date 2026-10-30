/**
 * Дії рядка стрічки — через контекст, а не через ~40 пропсів-замикань.
 *
 * Старий коментар у ChatArea казав, що мемо рядка «не втримається й дня»,
 * бо кожна дія — інлайнова стрілка, нова щокадру. Тут рішення: ChatArea
 * кладе в контекст ОДИН стабільний обʼєкт, чиї методи кличуть найсвіжішу
 * реалізацію через ref. Обʼєкт не змінюється ніколи, тож контекст не будить
 * жодного рядка, а рядок, викликаючи дію, отримує актуальний стан розмови.
 */
import { createContext, useContext, useRef } from 'react';
import type React from 'react';
import type { LocationData, Message } from '../../../types/messenger';

export interface FeedActions {
  react: (msgId: string, emoji: string) => void;
  openReactionPicker: (msgId: string) => void;
  openMenu: (msg: Message) => void;
  reply: (msg: Message) => void;
  jumpTo: (msgId: string) => void;
  toggleExpand: (msgId: string, e?: React.MouseEvent | React.TouchEvent) => void;
  toggleTranslate: (msg: Message) => void;
  selectMember: (name: string) => void;
  toggleSelect: (msgId: string) => void;
  /** Shift+клік: вибрати проміжок від останнього вибраного. */
  rangeTo: (msgId: string) => void;
  /** Натиснули позначку й тягнуть: вибирати все, над чим пройде палець. */
  rangeDrag: (msgId: string) => void;
  copyCode: (code: string, msgId: string) => void;
  toast: (text: string) => void;
  flushQueue: () => void;
  openLightbox: (url: string, title?: string) => void;
  openLocation: (loc: LocationData) => void;
  payBillShare: (msgId: string, participantId: string) => void;
  openCanvasSplit: () => void;
  openThread: (msgId: string) => void;
  touchStart: (msg: Message, e: React.TouchEvent) => void;
  touchMove: (msg: Message, e: React.TouchEvent) => void;
  touchEnd: (msg: Message) => void;
  mouseUp: (msg: Message, e: React.MouseEvent) => void;
}

export const FeedActionsContext = createContext<FeedActions | null>(null);

export function useFeedActions(): FeedActions {
  const actions = useContext(FeedActionsContext);
  if (!actions) throw new Error('FeedActionsContext missing: a feed row only lives inside ChatArea');
  return actions;
}

/**
 * Стабільний обʼєкт дій: створюється один раз, а кожен виклик іде до
 * реалізації з останнього рендеру.
 */
export function useStableFeedActions(latest: FeedActions): FeedActions {
  const ref = useRef(latest);
  ref.current = latest;
  const stable = useRef<FeedActions | null>(null);
  if (!stable.current) {
    const out = {} as Record<string, unknown>;
    for (const name of Object.keys(latest) as Array<keyof FeedActions>) {
      out[name] = (...args: unknown[]) => (ref.current[name] as (...a: unknown[]) => unknown)(...args);
    }
    stable.current = out as unknown as FeedActions;
  }
  return stable.current;
}
