export type WorkOpen = { chatId: string; view?: 'tasks' | 'shifts'; taskFrom?: string };
const EVT = 'nightpost:work-open';

export const openWork = (d: WorkOpen): void => {
  window.dispatchEvent(new CustomEvent<WorkOpen>(EVT, { detail: d }));
};

export const onWorkOpen = (fn: (d: WorkOpen) => void): (() => void) => {
  const h = (e: Event) => fn((e as CustomEvent<WorkOpen>).detail);
  window.addEventListener(EVT, h);
  return () => window.removeEventListener(EVT, h);
};
