import type { Lang } from './types';

const en = {
  dragFocus: 'Drag to set focus',
  focusPoint: 'Focus point',
  focusAt: (x: number, y: number) => `${x}% across, ${y}% down`,
  cardTone: 'Card tone',
  noTone: 'No tone',
  alsoShowsIn: 'Also shows in',
  less: 'Less',
  more: 'More',
  untitled: 'Untitled',
  noTime: 'No time',
  addStage: 'Add stage',
  dropItems: 'Items',
  editRow: (name: string) => `Edit ${name}`,
  opened: 'Open',
};

export type InspectorCopy = typeof en;

const uk: InspectorCopy = {
  dragFocus: 'Потягніть, щоб задати фокус',
  focusPoint: 'Точка фокуса',
  focusAt: (x, y) => `${x}% по ширині, ${y}% по висоті`,
  cardTone: 'Тон картки',
  noTone: 'Без тону',
  alsoShowsIn: 'Також показується',
  less: 'Менше',
  more: 'Більше',
  untitled: 'Без назви',
  noTime: 'Без часу',
  addStage: 'Додати етап',
  dropItems: 'Речі',
  editRow: (name) => `Редагувати: ${name}`,
  opened: 'Відкрито',
};

export const inspectorCopy: Record<Lang, InspectorCopy> = { en, uk };
